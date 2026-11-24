#!/usr/bin/env node
/**
 * package-story-release.mjs — package one chapter DB for the story CDN and
 * regenerate the app menu catalog.
 *
 * Output (mirrors the terminology DB release layout):
 *   release/cdn/story/<chapter-id>/releases/<version>/story.db
 *   release/cdn/story/<chapter-id>/releases/<version>/story.db.sha256
 *   release/cdn/story/<chapter-id>/latest.json
 *   release/cdn/story/latest/catalog.json
 *
 * Usage:
 *   node story_builder/package-story-release.mjs <chapter-id> [--version <ver>]
 *     [--out <release/cdn>] [--base-url <url>] [--minimum-app-version <ver>]
 *     [--check] [--pretty]
 *
 * `--check` verifies the published catalog against catalog.tsv and the files on
 * disk without writing anything.
 */

import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, readdir, stat, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';
import { loadCatalog } from './lib/story-catalog.mjs';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, '..');
const defaultWork = join(scriptDir, 'work');
const catalogPath = join(scriptDir, 'catalog.tsv');
const announcementsPath = join(scriptDir, 'announcements.json');
const MANIFEST_VERSION = 1;
const SCHEMA_VERSION = 1;
const ANNOUNCEMENT_LEVELS = ['info', 'success', 'warning'];

const usage = 'Usage: node story_builder/package-story-release.mjs <chapter-id> [--version <ver>] ' +
  '[--out <dir>] [--base-url <url>] [--minimum-app-version <ver>] [--check] [--pretty]\n' +
  '       node story_builder/package-story-release.mjs --catalog-only [--out <dir>] ' +
  '[--base-url <url>] [--minimum-app-version <ver>] [--check] [--pretty]';

const sha256 = (value) => createHash('sha256').update(value).digest('hex');

function parseArgs(argv) {
  const flags = {
    version: '', out: join(repoRoot, 'release', 'cdn'), baseUrl: 'https://cdn.fgogotran.com',
    minimumAppVersion: '4.1.0', check: false, pretty: false, catalogOnly: false,
  };
  const positional = [];
  const value = (index) => {
    const next = argv[index + 1];
    if (next === undefined || next.startsWith('--')) throw new Error(usage);
    return next;
  };
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    if (arg === '--version') { flags.version = value(index); index++; }
    else if (arg === '--out') { flags.out = resolve(value(index)); index++; }
    else if (arg === '--base-url') { flags.baseUrl = value(index).replace(/\/+$/, ''); index++; }
    else if (arg === '--minimum-app-version') { flags.minimumAppVersion = value(index); index++; }
    else if (arg === '--check') flags.check = true;
    else if (arg === '--pretty') flags.pretty = true;
    else if (arg === '--catalog-only') flags.catalogOnly = true;
    else if (arg.startsWith('--')) throw new Error(usage);
    else positional.push(arg);
  }
  return { positional, flags };
}

async function exists(path) {
  try { await stat(path); return true; } catch { return false; }
}

async function nextVersion(releasesDir) {
  const day = new Date().toISOString().slice(0, 10).replace(/-/g, '.');
  let patch = 0;
  if (await exists(releasesDir)) {
    for (const name of await readdir(releasesDir)) {
      const match = /^(\d{4}\.\d{2}\.\d{2})\.(\d+)$/.exec(name);
      if (match && match[1] === day) patch = Math.max(patch, Number(match[2]));
    }
  }
  return day + '.' + (patch + 1);
}

function readMeta(db) {
  const meta = {};
  for (const row of db.prepare('SELECT key, value FROM meta').all()) meta[row.key] = row.value;
  return meta;
}

function catalogEntry(row, extra) {
  return {
    chapterId: row.chapterId,
    titles: { jp: row.jpTitle, cn: row.cnTitle, tw: row.twTitle, en: row.enTitle },
    category: row.category,
    latest: row.latest === 1,
    status: row.status,
    order: row.order,
    ...extra,
  };
}

async function buildCatalogDocument(catalog, out, flags) {
  const chapters = [];
  for (const row of [...catalog.values()].sort((a, b) =>
    (b.order || '').localeCompare(a.order || '') || a.chapterId.localeCompare(b.chapterId))) {
    const latestPath = join(out, 'story', row.chapterId, 'latest.json');
    if (await exists(latestPath)) {
      const published = JSON.parse(await readFile(latestPath, 'utf8'));
      chapters.push(catalogEntry(row, {
        available: true,
        contentVersion: published.contentVersion,
        dbUrl: published.dbUrl,
        dbSha256: published.dbSha256,
        dbSize: published.dbSize,
        entryCount: published.entryCount,
        coverage: published.coverage,
      }));
    } else {
      chapters.push(catalogEntry(row, {
        available: false, contentVersion: null, dbUrl: null, dbSha256: null,
        dbSize: null, entryCount: null, coverage: null,
      }));
    }
  }
  return {
    manifestVersion: MANIFEST_VERSION,
    schemaVersion: SCHEMA_VERSION,
    minimumAppVersion: flags.minimumAppVersion,
    baseUrl: flags.baseUrl,
    generatedAt: new Date().toISOString(),
    chapters,
  };
}

/**
 * The announcement board source. A missing file means "no announcements"; the
 * array shape is the one the native catalogue used, so migrated entries keep
 * working. Wrapped in `{schemaVersion, generatedAt, announcements}` for the CDN.
 */
async function readAnnouncements() {
  if (!(await exists(announcementsPath))) return [];
  const parsed = JSON.parse(await readFile(announcementsPath, 'utf8'));
  const items = Array.isArray(parsed) ? parsed : parsed?.announcements;
  if (!Array.isArray(items)) throw new Error('announcements.json must be an array');
  const ids = new Set();
  for (const item of items) {
    const id = String(item?.id ?? '').trim();
    if (!id) throw new Error('announcement is missing id');
    if (ids.has(id)) throw new Error('duplicate announcement id ' + id);
    ids.add(id);
    if (item.title == null) throw new Error(id + ': missing title');
    if (item.body == null) throw new Error(id + ': missing body');
    if (!String(item.date ?? '').trim()) throw new Error(id + ': missing date');
    if (!ANNOUNCEMENT_LEVELS.includes(item.level ?? 'info')) {
      throw new Error(id + ': level must be ' + ANNOUNCEMENT_LEVELS.join(', '));
    }
  }
  return items.map((item) => ({
    id: String(item.id),
    title: item.title,
    body: item.body,
    date: String(item.date),
    level: item.level ?? 'info',
  }));
}

async function buildAnnouncementsDocument() {
  return {
    schemaVersion: SCHEMA_VERSION,
    generatedAt: new Date().toISOString(),
    announcements: await readAnnouncements(),
  };
}

/** Writes `story/latest/catalog.json` and `story/latest/announcements.json`. */
async function writeCatalogAndAnnouncements(catalog, flags) {
  const document = await buildCatalogDocument(catalog, flags.out, flags);
  const announcements = await buildAnnouncementsDocument();
  await mkdir(join(flags.out, 'story', 'latest'), { recursive: true });
  await writeFile(join(flags.out, 'story', 'latest', 'catalog.json'),
    JSON.stringify(document, null, 2) + '\n', 'utf8');
  await writeFile(join(flags.out, 'story', 'latest', 'announcements.json'),
    JSON.stringify(announcements, null, 2) + '\n', 'utf8');
  return { document, announcements };
}

async function checkRelease(catalog, out, flags) {
  const problems = [];
  problems.push(...await checkAnnouncements(out));
  const catalogJsonPath = join(out, 'story', 'latest', 'catalog.json');
  if (!(await exists(catalogJsonPath))) {
    return { ok: false, problems: ['missing ' + catalogJsonPath] };
  }
  const document = JSON.parse(await readFile(catalogJsonPath, 'utf8'));
  const byId = new Map(document.chapters.map((chapter) => [chapter.chapterId, chapter]));
  for (const row of catalog.values()) {
    const chapter = byId.get(row.chapterId);
    if (!chapter) { problems.push('catalog.json is missing ' + row.chapterId); continue; }
    if (chapter.titles.jp !== row.jpTitle || chapter.titles.cn !== row.cnTitle ||
      chapter.titles.tw !== row.twTitle || chapter.titles.en !== row.enTitle) {
      problems.push(row.chapterId + ': catalog.json titles differ from catalog.tsv');
    }
    if (chapter.category !== row.category) problems.push(row.chapterId + ': category differs');
    if (chapter.latest !== (row.latest === 1)) problems.push(row.chapterId + ': latest differs');
    if (chapter.status !== row.status) problems.push(row.chapterId + ': status differs');
    if (!chapter.available) continue;
    const dbPath = join(out, 'story', row.chapterId, 'releases', chapter.contentVersion, 'story.db');
    if (!(await exists(dbPath))) { problems.push(row.chapterId + ': missing ' + dbPath); continue; }
    const bytes = await readFile(dbPath);
    if (sha256(bytes) !== chapter.dbSha256) problems.push(row.chapterId + ': dbSha256 mismatch');
    if (bytes.length !== chapter.dbSize) problems.push(row.chapterId + ': dbSize mismatch');
    const db = new DatabaseSync(dbPath, { readOnly: true });
    try {
      const meta = readMeta(db);
      if (Number(meta.entry_count) !== chapter.entryCount) {
        problems.push(row.chapterId + ': entryCount differs from DB meta');
      }
      if (meta.content_version !== chapter.contentVersion) {
        problems.push(row.chapterId + ': DB content_version differs from catalog');
      }
      const integrity = db.prepare('PRAGMA integrity_check').get();
      if (!integrity || Object.values(integrity)[0] !== 'ok') {
        problems.push(row.chapterId + ': integrity_check failed');
      }
    } finally {
      db.close();
    }
  }
  return { ok: problems.length === 0, problems };
}

/** `--check`: the published announcement board must match its source file. */
async function checkAnnouncements(out) {
  const problems = [];
  const source = await readAnnouncements();
  const publishedPath = join(out, 'story', 'latest', 'announcements.json');
  if (!(await exists(publishedPath))) {
    problems.push('missing ' + publishedPath);
    return problems;
  }
  const published = JSON.parse(await readFile(publishedPath, 'utf8'));
  const items = Array.isArray(published?.announcements) ? published.announcements : [];
  const sourceIds = source.map((item) => item.id);
  const publishedIds = items.map((item) => String(item?.id ?? ''));
  if (sourceIds.join('\u0000') !== publishedIds.join('\u0000')) {
    problems.push('announcements.json is stale: published ' + JSON.stringify(publishedIds) +
      ' != source ' + JSON.stringify(sourceIds));
  }
  for (const item of items) {
    if (!ANNOUNCEMENT_LEVELS.includes(item?.level ?? 'info')) {
      problems.push('announcement ' + item?.id + ': unknown level ' + JSON.stringify(item?.level));
    }
  }
  return problems;
}

async function main(argv) {
  const { positional, flags } = parseArgs(argv);
  const catalog = await loadCatalog(catalogPath);

  if (flags.check) {
    const result = await checkRelease(catalog, flags.out, flags);
    console.log(flags.pretty ? JSON.stringify(result, null, 2) : JSON.stringify(result));
    if (!result.ok) process.exitCode = 1;
    return;
  }

  // Catalog and announcement board only: no chapter database is packaged or
  // versioned, so a translation update can be announced without a release.
  if (flags.catalogOnly) {
    const written = await writeCatalogAndAnnouncements(catalog, flags);
    const report = {
      ok: true,
      catalogOnly: true,
      chaptersInCatalog: written.document.chapters.length,
      published: written.document.chapters.filter((chapter) => chapter.available)
        .map((chapter) => chapter.chapterId),
      announcements: written.announcements.announcements.length,
      catalog: join(flags.out, 'story', 'latest', 'catalog.json'),
      announcementsFile: join(flags.out, 'story', 'latest', 'announcements.json'),
    };
    console.log(flags.pretty ? JSON.stringify(report, null, 2) : JSON.stringify(report));
    return;
  }

  const chapterId = positional[0];
  if (!/^[a-z0-9][a-z0-9-]*$/i.test(chapterId ?? '')) throw new Error(usage);
  const row = catalog.get(chapterId);
  if (!row) throw new Error('catalog.tsv has no row for ' + chapterId);

  const work = resolve(positional[1] && !positional[1].startsWith('--') ? positional[1] : defaultWork);
  const dbPath = join(work, chapterId, chapterId + '.db');
  if (!(await exists(dbPath))) {
    throw new Error('missing chapter database ' + dbPath + '; run build-story-db.mjs first');
  }

  const chapterRoot = join(flags.out, 'story', chapterId);
  const releasesDir = join(chapterRoot, 'releases');
  const version = flags.version || (await nextVersion(releasesDir));
  if (!/^\d{4}\.\d{2}\.\d{2}\.\d+$/.test(version)) {
    throw new Error('version must look like 2026.11.24.1, got ' + version);
  }

  // Stamp the version into the DB so the file describes itself.
  const writable = new DatabaseSync(dbPath);
  const meta = readMeta(writable);
  const stamp = writable.prepare(
    'INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value');
  stamp.run('content_version', version);
  stamp.run('packaged_at', new Date().toISOString());
  const integrity = writable.prepare('PRAGMA integrity_check').get();
  if (!integrity || Object.values(integrity)[0] !== 'ok') throw new Error('DB integrity_check failed');
  const entryCount = Number(meta.entry_count);
  const coverage = {
    cn: Number(meta.coverage_cn), tw: Number(meta.coverage_tw), en: Number(meta.coverage_en),
  };
  writable.close();

  const releaseDir = join(releasesDir, version);
  await mkdir(releaseDir, { recursive: true });
  const publishedDb = join(releaseDir, 'story.db');
  await copyFile(dbPath, publishedDb);
  const bytes = await readFile(publishedDb);
  const digest = sha256(bytes);
  await writeFile(join(releaseDir, 'story.db.sha256'), digest + '  story.db\n', 'utf8');

  const dbUrl = flags.baseUrl + '/story/' + chapterId + '/releases/' + version + '/story.db';
  const chapterManifest = {
    manifestVersion: MANIFEST_VERSION,
    schemaVersion: SCHEMA_VERSION,
    minimumAppVersion: flags.minimumAppVersion,
    generatedAt: new Date().toISOString(),
    chapterId,
    titles: { jp: row.jpTitle, cn: row.cnTitle, tw: row.twTitle, en: row.enTitle },
    category: row.category,
    latest: row.latest === 1,
    status: row.status,
    order: row.order,
    contentVersion: version,
    dbUrl,
    dbSha256: digest,
    dbSize: bytes.length,
    entryCount,
    coverage,
  };
  await writeFile(join(chapterRoot, 'latest.json'), JSON.stringify(chapterManifest, null, 2) + '\n', 'utf8');

  const written = await writeCatalogAndAnnouncements(catalog, flags);
  const document = written.document;

  const report = {
    ok: true,
    chapterId,
    version,
    db: publishedDb,
    dbBytes: bytes.length,
    dbSha256: digest,
    dbUrl,
    entryCount,
    status: row.status,
    latest: row.latest === 1,
    chaptersInCatalog: document.chapters.length,
    published: document.chapters.filter((chapter) => chapter.available).map((chapter) => chapter.chapterId),
    announcements: written.announcements.announcements.length,
  };
  console.log(flags.pretty ? JSON.stringify(report, null, 2) : JSON.stringify(report));
}

main(process.argv.slice(2)).catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
