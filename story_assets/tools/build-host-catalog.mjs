#!/usr/bin/env node
/**
 * build-host-catalog.mjs — build the catalogue object that the hosting Lambda serves.
 *
 * Input:  story_assets/cloud/releases/catalog.json + each release manifest
 * Output: story_assets/cloud/host/catalog/v1/catalog.json
 *
 * S3 keys are relative to the bucket root and mirror story_assets/cloud/:
 *   releases/<lang>/<version>/<bundleDir>/bundle.bin
 *   releases/<lang>/<version>/<bundleDir>/index.bin
 *   releases/<lang>/<version>/<bundleDir>/manifest.json
 */

import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

const ROOT = "C:/mywork/FgoGotran/story_assets/cloud";
const releasesRoot = path.join(ROOT, "releases");
const catalogPath = path.join(releasesRoot, "catalog.json");
const outDir = path.resolve(ROOT, "..", "..", "release", "native-assets", "catalog");
const outFile = path.join(outDir, "catalog.json");
const sha256 = (b) => crypto.createHash("sha256").update(b).digest("hex");
const announcementsPath = path.join(ROOT, "host", "announcements.json");

const source = JSON.parse(fs.readFileSync(catalogPath, "utf8"));
const packs = [];
for (const [id, entry] of Object.entries(source.releases ?? {})) {
  const dir = path.join(releasesRoot, entry.language, entry.version, entry.logical_bundle.replace(/\//g, "_"));
  const manifestPath = path.join(dir, "manifest.json");
  if (!fs.existsSync(manifestPath)) continue;
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  const rel = (p) => path.relative(releasesRoot, p).replace(/\\/g, "/");
  const bundle = path.join(dir, "bundle.bin");
  const index = path.join(dir, "index.bin");
  const b = fs.readFileSync(bundle), i = fs.readFileSync(index), m = fs.readFileSync(manifestPath);
  packs.push({
    language: entry.language,
    region: entry.region,
    game_version: entry.version,
    logical_bundle: entry.logical_bundle,
    status: entry.status,
    scripts: manifest.coverage?.scripts ?? [],
    scripts_count: manifest.coverage?.scripts?.length ?? 0,
    bundle: { key: `releases/${rel(bundle)}`, sha256: sha256(b), size: b.length, crc32: entry.crc32 },
    index: { key: `releases/${rel(index)}`, sha256: sha256(i), size: i.length },
    manifest: { key: `releases/${rel(manifestPath)}`, sha256: sha256(m), size: m.length },
  });
}

packs.sort((a, b) => a.language.localeCompare(b.language));
const catalog = {
  schema_version: 1,
  type: "fgogotran-host-catalog",
  generated_at_utc: new Date().toISOString(),
  game_version: "2.139.5",
  announcements: fs.existsSync(announcementsPath) ? JSON.parse(fs.readFileSync(announcementsPath, "utf8")) : [],
  packs,
};
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(outFile, JSON.stringify(catalog, null, 2));
console.log(`wrote ${outFile} (${packs.length} packs)`);
for (const p of packs) console.log(`${p.language} ${p.logical_bundle}: ${p.bundle.key} ${p.bundle.size}B crc=${p.bundle.crc32} scripts=${p.scripts_count}`);
