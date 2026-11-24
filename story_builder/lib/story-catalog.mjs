/**
 * Shared parsing for the Story Library menu catalog (story_builder/catalog.tsv).
 *
 * The catalog is the editable source of truth for the app menu: one row per
 * chapter with its four locale titles, category, newest flag, translation
 * status, and menu order. `build-story-db.mjs` and `package-story-release.mjs`
 * both read it through this module so the rules cannot drift.
 */

import { readFile } from 'node:fs/promises';

export const CATALOG_COLUMNS = [
  'chapter_id', 'jp_title', 'cn_title', 'tw_title', 'en_title',
  'category', 'latest', 'status', 'order',
];
export const CATEGORIES = new Set(['Main Story', 'Events', 'Interludes']);
export const STATUSES = new Set(['finished', 'progressing']);

export function parseTsv(text) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  let index = 0;
  while (index < text.length) {
    const char = text[index];
    if (quoted) {
      if (char === '"') {
        if (text[index + 1] === '"') { field += '"'; index += 2; continue; }
        quoted = false; index++; continue;
      }
      field += char; index++; continue;
    }
    if (char === '"') { quoted = true; index++; continue; }
    if (char === '\t') { row.push(field); field = ''; index++; continue; }
    if (char === '\n') { row.push(field); rows.push(row); row = []; field = ''; index++; continue; }
    if (char === '\r') { index++; continue; }
    field += char; index++;
  }
  if (quoted) throw new Error('TSV ends inside a quoted field');
  if (field.length || row.length) { row.push(field); rows.push(row); }
  if (!rows.length) throw new Error('TSV is empty');
  return rows;
}

const truthy = (value) => /^(✓|✔|1|yes|y|true)$/i.test(value.trim());
const falsy = (value) => value.trim() === '' || /^(0|no|n|false)$/i.test(value.trim());

/** Parse catalog.tsv text into a Map keyed by chapter_id. */
export function readCatalog(text) {
  const rows = parseTsv(text);
  const header = rows[0];
  for (const key of CATALOG_COLUMNS) {
    if (!header.includes(key)) throw new Error('catalog.tsv is missing the ' + key + ' column');
  }
  const at = Object.fromEntries(header.map((key, index) => [key, index]));
  const entries = new Map();
  for (const row of rows.slice(1)) {
    const id = (row[at.chapter_id] ?? '').trim();
    if (!id || id.startsWith('#')) continue;
    const pick = (key) => (row[at[key]] ?? '').trim();
    const latestCell = pick('latest');
    const category = pick('category');
    const status = pick('status');
    if (entries.has(id)) throw new Error('catalog.tsv has a duplicate row for ' + id);
    if (!CATEGORIES.has(category)) {
      throw new Error('catalog.tsv ' + id + ': category must be Main Story, Events, or Interludes');
    }
    if (!STATUSES.has(status)) {
      throw new Error('catalog.tsv ' + id + ': status must be finished or progressing');
    }
    if (!truthy(latestCell) && !falsy(latestCell)) {
      throw new Error('catalog.tsv ' + id + ': latest must be ✓ or blank');
    }
    entries.set(id, {
      chapterId: id,
      jpTitle: pick('jp_title'),
      cnTitle: pick('cn_title'),
      twTitle: pick('tw_title'),
      enTitle: pick('en_title'),
      category,
      latest: truthy(latestCell) ? 1 : 0,
      status,
      order: pick('order'),
    });
  }
  return entries;
}

export async function loadCatalog(path) {
  return readCatalog(await readFile(path, 'utf8'));
}
