#!/usr/bin/env node
/**
 * Publish an announcement to the hosted native-asset catalogue.
 *
 * Usage:
 *   node scripts/publish-announcements.mjs --list
 *   node scripts/publish-announcements.mjs --title "标题" --body "内容" [--level success] [--date 2026-11-02]
 *   node scripts/publish-announcements.mjs --remove <id>
 *   node scripts/publish-announcements.mjs ... --dry-run
 *   node scripts/publish-announcements.mjs ... --no-upload
 *
 * The app fetches announcements from the catalogue; no APK rebuild is needed.
 * Lambda caches the catalogue for 60 seconds.
 */

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const ANNOUNCEMENTS = path.join(ROOT, "story_assets", "cloud", "host", "announcements.json");
const CATALOG = path.join(ROOT, "story_assets", "cloud", "host", "catalog", "v1", "catalog.json");
const BUILDER = path.join(ROOT, "story_assets", "tools", "build-host-catalog.mjs");
const BUCKET = "fgogotran-native-assets";
const LEVELS = new Set(["info", "success", "warning"]);

function fail(message) {
  console.error(`error: ${message}`);
  process.exit(1);
}

function parseArgs(argv) {
  const args = { level: "info", dryRun: false, upload: true };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      const v = argv[++i];
      if (v === undefined) fail(`missing value for ${a}`);
      return v;
    };
    if (a === "--title") args.title = next();
    else if (a === "--body") args.body = next();
    else if (a === "--level") args.level = next();
    else if (a === "--date") args.date = next();
    else if (a === "--id") args.id = next();
    else if (a === "--remove") args.remove = next();
    else if (a === "--list") args.list = true;
    else if (a === "--dry-run") args.dryRun = true;
    else if (a === "--no-upload") args.upload = false;
    else if (a === "--help" || a === "-h") args.help = true;
    else fail(`unknown argument: ${a}`);
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  console.log(fs.readFileSync(fileURLToPath(import.meta.url), "utf8").split("*/")[0]);
  process.exit(0);
}
if (!LEVELS.has(args.level)) fail(`--level must be one of: ${[...LEVELS].join(", ")}`);

let items = [];
if (fs.existsSync(ANNOUNCEMENTS)) {
  try {
    const parsed = JSON.parse(fs.readFileSync(ANNOUNCEMENTS, "utf8"));
    items = Array.isArray(parsed) ? parsed : [];
  } catch (e) {
    fail(`cannot parse ${ANNOUNCEMENTS}: ${e.message}`);
  }
}

if (args.list) {
  items.forEach((item) => console.log(`${item.id}\t${item.date}\t${item.level}\t${item.title}`));
  process.exit(0);
}

if (args.remove) {
  const before = items.length;
  items = items.filter((item) => item.id !== args.remove);
  if (items.length === before) fail(`announcement not found: ${args.remove}`);
  console.log(`removed ${args.remove}`);
} else {
  if (!args.title || !args.body) fail("--title and --body are required (or use --remove/--list)");
  const date = args.date || new Date().toISOString().slice(0, 10);
  const slug = args.title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 40);
  const id = args.id || (slug ? `${date}-${slug}` : `announcement-${Date.now().toString(36)}`);
  const entry = { id, title: args.title, body: args.body, date, level: args.level };
  // Replace the same id, otherwise put the newest announcement first.
  const index = items.findIndex((item) => item.id === id);
  if (index >= 0) items[index] = entry;
  else items.unshift(entry);
  console.log(`upserted ${id}`);
}

if (args.dryRun) {
  console.log(JSON.stringify(items, null, 2));
  process.exit(0);
}

fs.writeFileSync(ANNOUNCEMENTS, `${JSON.stringify(items, null, 2)}\n`, "utf8");
console.log(`wrote ${ANNOUNCEMENTS} (${items.length} announcements)`);

const build = spawnSync(process.execPath, [BUILDER], { cwd: ROOT, stdio: "inherit" });
if (build.status !== 0) fail("catalogue build failed");

if (!args.upload) {
  console.log("skipped upload (--no-upload)");
  process.exit(0);
}

const upload = spawnSync(
  "aws",
  [
    "s3",
    "cp",
    CATALOG,
    `s3://${BUCKET}/catalog/v1/catalog.json`,
    "--cache-control",
    "no-store",
    "--content-type",
    "application/json",
  ],
  { cwd: ROOT, stdio: "inherit" },
);
if (upload.error) fail(`aws CLI not found: ${upload.error.message}`);
if (upload.status !== 0) fail("S3 upload failed");

console.log("published. Lambda cache is 60s; use ?refresh=1 to verify immediately.");
