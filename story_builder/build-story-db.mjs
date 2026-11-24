#!/usr/bin/env node
/**
 * build-story-db.mjs - turn one translated Story Library TSV into the chapter
 * database the app downloads from the CDN.
 *
 * Input:
 *   work/<chapter-id>/<chapter-id>.tsv          canonical JP + cn/tw/en columns
 *   work/<chapter-id>/source-inventory.json     script, scene, position, branch
 *   story_builder/catalog.tsv                   titles, category, latest, status
 *
 * Output:
 *   work/<chapter-id>/<chapter-id>.db
 *
 * The database holds all three target locales in one file, keeps the story
 * order (`seq`), the script/scene/branch structure, and a normalized JP key
 * computed with the same contract the app uses for OCR matching. The app narrows
 * by chapter, then matches jp_hash exactly, then follows the stored flow.
 *
 * Usage:
 *   node story_builder/build-story-db.mjs <chapter-id> [work-dir] [--check] [--pretty]
 *
 * `--check` builds and validates without replacing an existing database.
 */

import { createHash } from 'node:crypto';
import { mkdir, readFile, rename, rm, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';
import { parseTsv, readCatalog } from './lib/story-catalog.mjs';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, '..');
const defaultWork = join(scriptDir, 'work');
const catalogPath = join(scriptDir, 'catalog.tsv');

const COLUMNS = [
  'jp_name', 'jp_dialog', 'cn_name', 'cn_dialog',
  'tw_name', 'tw_dialog', 'en_name', 'en_dialog', 'type',
];
const JP_NAME = 0;
const JP_DIALOG = 1;
const CN_NAME = 2;
const CN_DIALOG = 3;
const TW_NAME = 4;
const TW_DIALOG = 5;
const EN_NAME = 6;
const EN_DIALOG = 7;
const TYPE = 8;
const LOCALES = {
  cn: { name: CN_NAME, dialog: CN_DIALOG },
  tw: { name: TW_NAME, dialog: TW_DIALOG },
  en: { name: EN_NAME, dialog: EN_DIALOG },
};
const SCHEMA_VERSION = 1;
const BUILDER = 'story_builder/build-story-db.mjs v1';
const NORMALIZATION = 'fgogotran-ocr-v2';

const usage = 'Usage: node story_builder/build-story-db.mjs <chapter-id> [work-dir] [--check] [--pretty]';

const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const sha1 = (value) => createHash('sha1').update(value).digest('hex');

// Same normalization contract as app/src/main/java/com/fgogotran/scriptmemory/ScriptMemory.kt:
// gender control keeps the male side, the player name keeps a token, ruby
// readings are dropped, then NFKC, whitespace, and punctuation are removed.
const genderControl = /\[&([^:\]]+):([^\]]+)\]/g;
const masterName = /\[%(\d+)\]/g;
const ruby = /〈[^〉]*〉/g;
const spaces = /[\s\u3000]+/g;
const punctuation = /[。、，．,.！？!?…‥・：:；;「」『』（）()\[\]［］〈〉《》\u2015\u2014\u2500\u2501]/g;
function normalizeJp(text) {
  return text
    .replace(genderControl, '$1')
    .replace(masterName, '%$1')
    .replace(ruby, '')
    .normalize('NFKC')
    .replace(spaces, '')
    .replace(punctuation, '');
}

function coverageOf(rows) {
  const result = {};
  for (const [locale, config] of Object.entries(LOCALES)) {
    const filled = rows.filter((row) => (row[config.dialog] ?? '').trim()).length;
    const named = rows.filter((row) => (row[JP_NAME] ?? '').trim());
    const namedFilled = named.filter((row) => (row[config.name] ?? '').trim()).length;
    result[locale] = {
      rows: rows.length,
      dialogs: filled,
      named: named.length,
      names: namedFilled,
      complete: filled === rows.length && namedFilled === named.length,
      dialogRatio: rows.length ? +(filled / rows.length).toFixed(4) : 0,
    };
  }
  return result;
}

function validate(rows, inventoryEntries, catalogRow, problems) {
  if (rows.length !== inventoryEntries.length) {
    problems.push('TSV rows ' + rows.length + ' != source-inventory entries ' + inventoryEntries.length);
    return;
  }
  const ids = new Set();
  const masterTokens = (value) => (value.match(/\[%\d+\]/g) ?? []).join(',');
  const genderCount = (value) => (value.match(/\[&/g) ?? []).length;
  for (let index = 0; index < rows.length; index++) {
    const row = rows[index];
    const inventory = inventoryEntries[index];
    if (inventory.tsv_row !== index + 1) {
      problems.push('source-inventory is out of order at row ' + (index + 1));
      return;
    }
    const type = row[TYPE];
    if (type !== 'dialogue' && type !== 'choice') {
      problems.push('row ' + (index + 1) + ': unknown type ' + JSON.stringify(type));
      return;
    }
    if (ids.has(inventory.entry_id)) {
      problems.push('duplicate entry_id ' + inventory.entry_id);
      return;
    }
    ids.add(inventory.entry_id);
    const jpName = (row[JP_NAME] ?? '').trim();
    if (type === 'choice' && jpName) problems.push('row ' + (index + 1) + ': choice with a name');
    for (const [locale, config] of Object.entries(LOCALES)) {
      const name = (row[config.name] ?? '').trim();
      const dialog = (row[config.dialog] ?? '').trim();
      if (!dialog) problems.push('row ' + (index + 1) + ': ' + locale + ' dialog is empty');
      if (!!jpName !== !!name) {
        problems.push('row ' + (index + 1) + ': ' + locale + ' name presence differs from JP');
      }
      if (masterTokens(row[JP_DIALOG]) !== masterTokens(dialog)) {
        problems.push('row ' + (index + 1) + ': ' + locale + ' [%N] tokens differ from JP');
      }
      if (genderCount(row[JP_DIALOG]) !== genderCount(dialog)) {
        problems.push('row ' + (index + 1) + ': ' + locale + ' [&] gender controls differ from JP');
      }
    }
  }
  if (catalogRow.status === 'finished') {
    // A finished chapter has to be complete in every locale.
    for (const [locale, info] of Object.entries(coverageOf(rows))) {
      if (!info.complete) problems.push(locale + ' coverage is ' + info.dialogRatio + ' but status is finished');
    }
  }
}

async function main(argv) {
  const positional = [];
  let check = false;
  let pretty = false;
  for (const value of argv) {
    if (value === '--check') check = true;
    else if (value === '--pretty') pretty = true;
    else if (value.startsWith('--')) throw new Error(usage);
    else positional.push(value);
  }
  const chapterId = positional[0];
  if (!/^[a-z0-9][a-z0-9-]*$/i.test(chapterId ?? '')) throw new Error(usage);
  const work = resolve(positional[1] ?? defaultWork);
  const dir = join(work, chapterId);
  const tsvPath = join(dir, chapterId + '.tsv');
  const inventoryPath = join(dir, 'source-inventory.json');
  const dbPath = join(dir, chapterId + '.db');

  const catalog = readCatalog(await readFile(catalogPath, 'utf8'));
  const catalogRow = catalog.get(chapterId);
  if (!catalogRow) throw new Error('catalog.tsv has no row for ' + chapterId);

  const tsvText = await readFile(tsvPath, 'utf8');
  const rows = parseTsv(tsvText);
  if (JSON.stringify(rows[0]) !== JSON.stringify(COLUMNS)) {
    throw new Error('Unexpected TSV header in ' + tsvPath);
  }
  const dataRows = rows.slice(1);
  const inventoryText = await readFile(inventoryPath, 'utf8');
  const inventory = JSON.parse(inventoryText);
  const inventoryEntries = inventory.entries;
  if (inventory.story_library_id !== chapterId) {
    throw new Error('source-inventory story_library_id ' + inventory.story_library_id + ' != ' + chapterId);
  }

  const problems = [];
  validate(dataRows, inventoryEntries, catalogRow, problems);
  if (problems.length) {
    console.error(JSON.stringify({ ok: false, chapterId, problems: problems.slice(0, 20) }, null, 2));
    process.exitCode = 1;
    return;
  }

  const coverage = coverageOf(dataRows);
  const scripts = new Map();
  const openings = [];
  const prepared = [];
  const hashCounts = new Map();
  let emptyKeys = 0;
  for (let index = 0; index < dataRows.length; index++) {
    const row = dataRows[index];
    const entry = inventoryEntries[index];
    const seq = index + 1;
    const jpDialog = row[JP_DIALOG] ?? '';
    const norm = normalizeJp(jpDialog);
    const hash = norm ? sha1(norm) : '';
    hashCounts.set(hash, (hashCounts.get(hash) ?? 0) + 1);
    if (norm === '') emptyKeys++;
    if (!scripts.has(entry.script_id)) {
      scripts.set(entry.script_id, {
        script_id: entry.script_id,
        quest_id: entry.quest_id == null ? null : String(entry.quest_id),
        phase: entry.phase == null ? null : String(entry.phase),
        script_slot: entry.script_slot == null ? null : String(entry.script_slot),
        first_seq: seq,
        last_seq: seq,
      });
      openings.push([entry.script_id, seq, norm]);
    } else {
      scripts.get(entry.script_id).last_seq = seq;
    }
    prepared.push([
      entry.entry_id,
      seq,
      entry.script_id,
      entry.scene_id ?? '',
      entry.position ?? 0,
      row[TYPE],
      entry.branch_id == null ? null : String(entry.branch_id),
      entry.choice_group == null ? null : String(entry.choice_group),
      entry.choice_slot == null ? null : String(entry.choice_slot),
      row[JP_NAME] ?? '',
      jpDialog,
      norm,
      hash,
      row[CN_NAME] ?? '',
      row[CN_DIALOG] ?? '',
      row[TW_NAME] ?? '',
      row[TW_DIALOG] ?? '',
      row[EN_NAME] ?? '',
      row[EN_DIALOG] ?? '',
    ]);
  }

const meta = {
  schema: String(SCHEMA_VERSION),
  builder: BUILDER,
  normalization: NORMALIZATION,
  chapter_id: chapterId,
  story_library_id: inventory.story_library_id,
  atlas_event_id: String(inventory.atlas_event_id ?? ''),
  jp_title: catalogRow.jpTitle,
  cn_title: catalogRow.cnTitle,
  tw_title: catalogRow.twTitle,
  en_title: catalogRow.enTitle,
  category: catalogRow.category,
  latest: String(catalogRow.latest),
  status: catalogRow.status,
  order: catalogRow.order,
  entry_count: String(prepared.length),
  script_count: String(scripts.size),
  dialogue_count: String(dataRows.filter((row) => row[TYPE] === 'dialogue').length),
  choice_count: String(dataRows.filter((row) => row[TYPE] === 'choice').length),
  coverage_cn: String(coverage.cn.dialogRatio),
  coverage_tw: String(coverage.tw.dialogRatio),
  coverage_en: String(coverage.en.dialogRatio),
  source_tsv: chapterId + '.tsv',
  source_tsv_sha256: sha256(tsvText),
  source_inventory_sha256: sha256(inventoryText),
  content_version: '',
  built_at: new Date().toISOString(),
};

  const temporary = dbPath + '.tmp-' + process.pid;
  await rm(temporary, { force: true });
  const db = new DatabaseSync(temporary);
  try {
    db.exec(`
      PRAGMA journal_mode = OFF;
      PRAGMA synchronous = OFF;
      PRAGMA user_version = ${SCHEMA_VERSION};
      CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
      CREATE TABLE scripts(
        script_id TEXT PRIMARY KEY,
        quest_id TEXT,
        phase TEXT,
        script_slot TEXT,
        first_seq INTEGER NOT NULL,
        last_seq INTEGER NOT NULL
      );
      CREATE TABLE entries(
        entry_id TEXT PRIMARY KEY,
        seq INTEGER NOT NULL UNIQUE,
        script_id TEXT NOT NULL,
        scene_id TEXT NOT NULL,
        position INTEGER NOT NULL,
        type TEXT NOT NULL,
        branch_id TEXT,
        choice_group TEXT,
        choice_slot TEXT,
        jp_name TEXT NOT NULL,
        jp_dialog TEXT NOT NULL,
        jp_norm TEXT NOT NULL,
        jp_hash TEXT NOT NULL,
        cn_name TEXT NOT NULL,
        cn_dialog TEXT NOT NULL,
        tw_name TEXT NOT NULL,
        tw_dialog TEXT NOT NULL,
        en_name TEXT NOT NULL,
        en_dialog TEXT NOT NULL
      );
      CREATE TABLE script_openings(script_id TEXT NOT NULL, seq INTEGER NOT NULL, jp_norm TEXT NOT NULL);
    `);
    db.exec('BEGIN');
    const insertMeta = db.prepare('INSERT INTO meta(key, value) VALUES (?, ?)');
    const insertScript = db.prepare(`INSERT INTO scripts
      (script_id, quest_id, phase, script_slot, first_seq, last_seq) VALUES (?,?,?,?,?,?)`);
    const insertEntry = db.prepare(`INSERT INTO entries
      (entry_id, seq, script_id, scene_id, position, type, branch_id, choice_group, choice_slot,
       jp_name, jp_dialog, jp_norm, jp_hash,
       cn_name, cn_dialog, tw_name, tw_dialog, en_name, en_dialog)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`);
    const insertOpening = db.prepare('INSERT INTO script_openings(script_id, seq, jp_norm) VALUES (?,?,?)');

    for (const [key, value] of Object.entries(meta)) insertMeta.run(key, value);
    for (const script of scripts.values()) {
      insertScript.run(script.script_id, script.quest_id, script.phase, script.script_slot,
        script.first_seq, script.last_seq);
    }
    for (const row of prepared) insertEntry.run(...row);
    for (const opening of openings) insertOpening.run(...opening);
    db.exec('COMMIT');
    db.exec("CREATE INDEX entries_hash ON entries(jp_hash) WHERE jp_hash <> '';");
    db.exec('CREATE INDEX entries_norm ON entries(jp_norm);');
    db.exec('CREATE INDEX entries_script_position ON entries(script_id, position);');
    db.exec('CREATE INDEX entries_type ON entries(type);');
    db.exec('CREATE INDEX script_openings_norm ON script_openings(jp_norm);');
    db.exec('VACUUM;');
  } finally {
    db.close();
  }

  const size = (await stat(temporary)).size;
  if (check) {
    await rm(temporary, { force: true });
  } else {
    await mkdir(dir, { recursive: true });
    await rename(temporary, dbPath);
  }

  const report = {
    ok: true,
    check,
    chapterId,
    written: !check,
    db: check ? null : dbPath,
    dbBytes: size,
    entries: prepared.length,
    scripts: scripts.size,
    duplicateHashes: [...hashCounts.entries()].filter(([hash, count]) => hash && count > 1).length,
    emptyKeys,
    category: catalogRow.category,
    latest: catalogRow.latest === 1,
    status: catalogRow.status,
    coverage: {
      cn: coverage.cn.dialogRatio,
      tw: coverage.tw.dialogRatio,
      en: coverage.en.dialogRatio,
    },
    sourceTsvSha256: meta.source_tsv_sha256,
  };
  console.log(pretty ? JSON.stringify(report, null, 2) : JSON.stringify(report));
}

main(process.argv.slice(2)).catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
