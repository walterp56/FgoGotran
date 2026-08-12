import crypto from "node:crypto";
import { DynamoDBClient, PutItemCommand, UpdateItemCommand } from "@aws-sdk/client-dynamodb";
import { CloudWatchClient, PutMetricDataCommand } from "@aws-sdk/client-cloudwatch";

const ddb = new DynamoDBClient({});
const cw = new CloudWatchClient({});

const INSTALLS_TABLE = process.env.INSTALLS_TABLE;
const DAILY_TABLE = process.env.DAILY_TABLE;
const SEEN_TABLE = process.env.SEEN_TABLE;
const INSTALL_HASH_SECRET = process.env.INSTALL_HASH_SECRET || "";
const METRIC_NAMESPACE = "FgoGotran/App";

const allowedEvents = new Set([
  "first_install",
  "daily_active",
  "translation_mode_used",
  "api_backend_type",
  "game_server_used",
  "voice_server_used"
]);

const allowedModes = new Set(["manual", "semi_auto", "auto", "crop"]);
const allowedServers = new Set(["jp", "cn", "tw"]);

function json(statusCode, body) {
  return {
    statusCode,
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body)
  };
}

function parseBody(event) {
  const raw = event.isBase64Encoded
    ? Buffer.from(event.body || "", "base64").toString("utf8")
    : event.body || "{}";

  if (raw.length > 2048) {
    throw Object.assign(new Error("Body too large"), { statusCode: 413 });
  }

  return JSON.parse(raw);
}

function clean(value, max = 80) {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

function cleanSegment(value, max = 80) {
  return clean(value, max)
    .toLowerCase()
    .replace(/[^a-z0-9_.-]/g, "_")
    .replace(/^_+|_+$/g, "")
    || "unknown";
}

function validInstallId(value) {
  return /^[0-9a-fA-F-]{20,64}$/.test(value);
}

function isConditionalDuplicate(error) {
  return error?.name === "ConditionalCheckFailedException";
}

function installHash(installId) {
  if (INSTALL_HASH_SECRET) {
    return crypto
      .createHmac("sha256", INSTALL_HASH_SECRET)
      .update(installId)
      .digest("hex")
      .slice(0, 32);
  }
  return crypto
    .createHash("sha256")
    .update(installId)
    .digest("hex")
    .slice(0, 32);
}

function ttlThirtyDaysFromNow() {
  return Math.floor(Date.now() / 1000) + 30 * 24 * 60 * 60;
}

async function putMetric(metricName, dimensions = []) {
  await cw.send(new PutMetricDataCommand({
    Namespace: METRIC_NAMESPACE,
    MetricData: [
      {
        MetricName: metricName,
        Value: 1,
        Unit: "Count",
        Dimensions: dimensions
      }
    ]
  }));
}

async function putSeenDaily(bucket, installId, item) {
  const hashedInstallId = installHash(installId);
  const dedupeKey = `${bucket}#${hashedInstallId}`;

  try {
    await ddb.send(new PutItemCommand({
      TableName: SEEN_TABLE,
      Item: {
        dedupe_key: { S: dedupeKey },
        bucket: { S: bucket },
        install_hash: { S: hashedInstallId },
        expires_at: { N: String(ttlThirtyDaysFromNow()) },
        ...item
      },
      ConditionExpression: "attribute_not_exists(#dedupeKey)",
      ExpressionAttributeNames: {
        "#dedupeKey": "dedupe_key"
      }
    }));

    return true;
  } catch (error) {
    if (isConditionalDuplicate(error)) {
      return false;
    }

    throw error;
  }
}

async function incrementDailyCounter(date, counterName, now) {
  await ddb.send(new UpdateItemCommand({
    TableName: DAILY_TABLE,
    Key: {
      bucket: { S: `${date}#summary` },
      install_id: { S: "summary" }
    },
    UpdateExpression: "SET #date = :date, #updatedAt = :now ADD #counter :one",
    ExpressionAttributeNames: {
      "#date": "date",
      "#updatedAt": "updated_at",
      "#counter": counterName
    },
    ExpressionAttributeValues: {
      ":date": { S: date },
      ":now": { S: now },
      ":one": { N: "1" }
    }
  }));
}

async function recordDailyUnique({
  date,
  installId,
  bucket,
  commonItem,
  counterName,
  metricName,
  metricDimensions = [],
  now
}) {
  const isNew = await putSeenDaily(bucket, installId, commonItem);
  if (!isNew) return false;

  const writes = [];
  if (counterName) {
    writes.push(incrementDailyCounter(date, counterName, now));
  }
  if (metricName) {
    writes.push(putMetric(metricName, metricDimensions));
  }
  await Promise.all(writes);
  return true;
}

export const handler = async (event) => {
  try {
    const method = event.requestContext?.http?.method || event.httpMethod || "POST";
    if (method !== "POST") {
      return json(405, { error: "method_not_allowed" });
    }

    if (!INSTALLS_TABLE || !DAILY_TABLE || !SEEN_TABLE) {
      return json(500, { error: "missing_table_env" });
    }

    const body = parseBody(event);
    const installId = clean(body.install_id);
    const eventType = clean(body.event_type, 40);
    const appVersion = clean(body.app_version, 40);
    const appVersionCode = String(body.app_version_code ?? "").replace(/[^\d]/g, "").slice(0, 12);
    const locale = clean(body.locale, 32);
    const androidVersion = clean(body.android_version, 32);
    const mode = clean(body.mode, 32);
    const rawBackendType = clean(body.backend_type, 32).replace(/[^a-zA-Z0-9_.-]/g, "");
    const backendType = rawBackendType ? cleanSegment(rawBackendType, 32) : "";
    const server = cleanSegment(body.server, 8);

    if (!validInstallId(installId)) {
      return json(400, { error: "invalid_install_id" });
    }

    if (!allowedEvents.has(eventType)) {
      return json(400, { error: "invalid_event_type" });
    }

    if (eventType === "translation_mode_used" && !allowedModes.has(mode)) {
      return json(400, { error: "invalid_mode" });
    }

    if (eventType === "api_backend_type" && !backendType) {
      return json(400, { error: "invalid_backend_type" });
    }

    const isServerEvent = eventType === "game_server_used" || eventType === "voice_server_used";
    if (isServerEvent && !allowedServers.has(server)) {
      return json(400, { error: "invalid_server" });
    }

    const now = new Date().toISOString();
    const date = now.slice(0, 10);

    await ddb.send(new UpdateItemCommand({
      TableName: INSTALLS_TABLE,
      Key: {
        install_id: { S: installId }
      },
      UpdateExpression: "SET #first = if_not_exists(#first, :now), #last = :now, #firstVer = if_not_exists(#firstVer, :appVersion), #latestVer = :appVersion, #versionCode = :versionCode, #locale = :locale, #android = :android",
      ExpressionAttributeNames: {
        "#first": "first_seen_at",
        "#last": "last_seen_at",
        "#firstVer": "first_app_version",
        "#latestVer": "latest_app_version",
        "#versionCode": "app_version_code",
        "#locale": "locale",
        "#android": "android_version"
      },
      ExpressionAttributeValues: {
        ":now": { S: now },
        ":appVersion": { S: appVersion || "unknown" },
        ":versionCode": { S: appVersionCode || "unknown" },
        ":locale": { S: locale || "unknown" },
        ":android": { S: androidVersion || "unknown" }
      }
    }));

    const commonItem = {
      event_type: { S: eventType },
      app_version: { S: appVersion || "unknown" },
      app_version_code: { S: appVersionCode || "unknown" },
      created_at: { S: now }
    };

    if (mode) {
      commonItem.mode = { S: mode };
    }

    if (backendType) {
      commonItem.backend_type = { S: backendType };
    }

    if (isServerEvent) {
      commonItem.server = { S: server };
    }

    if (eventType === "first_install") {
      await recordDailyUnique({
        date,
        installId,
        bucket: `${date}#first_install`,
        commonItem,
        counterName: "first_install",
        metricName: "FirstInstall",
        now
      });
    }

    if (eventType === "daily_active") {
      await recordDailyUnique({
        date,
        installId,
        bucket: `${date}#daily_active`,
        commonItem,
        counterName: "daily_active",
        metricName: "DailyActive",
        now
      });
    }

    const versionValue = appVersionCode || appVersion || "unknown";
    await recordDailyUnique({
      date,
      installId,
      bucket: `${date}#version#${cleanSegment(versionValue, 40)}`,
      commonItem,
      counterName: `app_version_active_${cleanSegment(versionValue, 40)}`,
      metricName: "AppVersionActive",
      metricDimensions: [
        { Name: "AppVersion", Value: versionValue }
      ],
      now
    });

    if (eventType === "translation_mode_used") {
      await recordDailyUnique({
        date,
        installId,
        bucket: `${date}#mode#${mode}`,
        commonItem,
        counterName: `translation_mode_used_${mode}`,
        metricName: "ModeUsed",
        metricDimensions: [
          { Name: "Mode", Value: mode }
        ],
        now
      });
    }

    if (eventType === "api_backend_type") {
      await recordDailyUnique({
        date,
        installId,
        bucket: `${date}#backend#${backendType}`,
        commonItem,
        counterName: `api_backend_type_${backendType}`,
        metricName: "BackendUsed",
        metricDimensions: [
          { Name: "Backend", Value: backendType }
        ],
        now
      });
    }

    if (eventType === "game_server_used") {
      await recordDailyUnique({
        date,
        installId,
        bucket: `${date}#game_server_used#${server}`,
        commonItem,
        counterName: `game_server_used_${server}`,
        metricName: "GameServerUsed",
        metricDimensions: [
          { Name: "Server", Value: server }
        ],
        now
      });
    }

    if (eventType === "voice_server_used") {
      await recordDailyUnique({
        date,
        installId,
        bucket: `${date}#voice_server_used#${server}`,
        commonItem,
        counterName: `voice_server_used_${server}`,
        metricName: "VoiceServerUsed",
        metricDimensions: [
          { Name: "Server", Value: server }
        ],
        now
      });
    }

    return json(200, { ok: true });
  } catch (error) {
    console.error(error);
    return json(error.statusCode || 500, { error: "server_error" });
  }
};
