import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rename, rm, rmdir, stat, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { japaneseTsv, parseJapaneseScript } from './japanese-script.mjs';
import { parseTsv } from './lib/story-catalog.mjs';
import { dialogueSizeOf, stripDialogueMarker } from './lib/dialogue-size.mjs';

const atlas = 'https://api.atlasacademy.io';
const atlasEventIndex = atlas + '/export/JP/nice_event.json';
const atlasScriptHost = 'static.atlasacademy.io';
const defaultWork = fileURLToPath(new URL('./work/', import.meta.url));
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const json = (value) => JSON.stringify(value, null, 2) + '\n';
const positiveInteger = (value) => Number.isSafeInteger(value) && value > 0;
const validScriptId = (id) => typeof id === 'string' && /^\d{1,32}$/.test(id);
const utf8 = new TextDecoder('utf-8', { fatal: true });
const usage = 'Usage: node story-library.mjs lookup "<JP chapter name>"\n' +
  '       node story-library.mjs prepare <event-id> [work-dir]\n' +
  '       node story-library.mjs stage <event-id> [work-dir]\n' +
  '       node story-library.mjs inventory <event-id> [work-dir]\n' +
  '       node story-library.mjs build <event-id> <local-source-dir> [work-dir]\n' +
  '       node story-library.mjs size-tokens <event-id> [work-dir]';

function scriptUrl(scriptId, value) {
  if (typeof value !== 'string' || !value) {
    throw new Error('Atlas script ' + scriptId + ' has no source URL');
  }
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.host !== atlasScriptHost ||
    !url.pathname.endsWith('/' + scriptId + '.txt')) {
    throw new Error('Atlas script ' + scriptId + ' does not resolve to its ' + atlasScriptHost + ' URL');
  }
  return url.href;
}

function warOccurrences(war) {
  const occurrences = [];
  if (war.scriptId && war.scriptId !== 'NONE') {
    occurrences.push({
      war_id: war.id, quest_id: null, phase: null, script_slot: 1,
      script_id: war.scriptId, script_url: scriptUrl(war.scriptId, war.script),
    });
  }
  const quests = war.spots.flatMap((spot) => spot.quests);
  for (const quest of quests) {
    for (const phase of quest.phaseScripts) {
      for (const [index, script] of phase.scripts.entries()) {
        if (script.scriptId === 'NONE') continue;
        occurrences.push({
          war_id: war.id,
          quest_id: quest.id,
          phase: phase.phase,
          script_slot: index + 1,
          script_id: script.scriptId,
          script_url: scriptUrl(script.scriptId, script.script),
        });
      }
    }
  }
  return occurrences;
}

async function metadata(path) {
  const url = atlas + path;
  const response = await fetch(url, {
    redirect: 'error',
    signal: AbortSignal.timeout(20_000),
  });
  if (!response.ok) throw new Error('Atlas metadata HTTP ' + response.status + ' for ' + path);
  const bytes = Buffer.from(await response.arrayBuffer());
  return { url, metadata_sha256: sha256(bytes), data: JSON.parse(utf8.decode(bytes)) };
}

function occurrenceSlot(occurrence) {
  return [
    occurrence.war_id, occurrence.quest_id ?? 'opening',
    occurrence.phase ?? 0, occurrence.script_slot,
  ].join(':');
}

function validScriptSource(scriptId, value) {
  try {
    return scriptUrl(scriptId, value) === value;
  } catch {
    return false;
  }
}

function validOccurrence(row) {
  return positiveInteger(row?.war_id) && positiveInteger(row.script_slot) &&
    validScriptId(row.script_id) && validScriptSource(row.script_id, row.script_url) &&
    ((row.quest_id === null && row.phase === null) ||
      (positiveInteger(row.quest_id) && positiveInteger(row.phase)));
}

function checkInventory(inventory, eventId) {
  const rows = inventory.script_occurrences;
  if (inventory.schema_version !== 2 || inventory.atlas_event_id !== eventId ||
    inventory.story_library_id !== 'event-' + eventId || inventory.region !== 'JP' ||
    !Array.isArray(rows) || !rows.length ||
    rows.some((row) => !validOccurrence(row)) ||
    new Set(rows.map(occurrenceSlot)).size !== rows.length) {
    throw new Error('Inventory has an invalid event identity or script reference');
  }
}

async function inventoryEvent(eventId) {
  const before = (await metadata('/info')).data.JP;
  const { data: event, ...eventSource } = await metadata('/nice/JP/event/' + eventId);
  if (event.id !== eventId || !event.warIds?.length ||
    !event.warIds.every(positiveInteger)) {
    throw new Error('Atlas event has an invalid identity or no linked wars');
  }
  const sources = [eventSource];
  const occurrences = [];
  for (const id of event.warIds) {
    const { data: war, ...warSource } = await metadata('/nice/JP/war/' + id);
    if (war.id !== id || war.eventId !== eventId) {
      throw new Error('Atlas war ' + id + ' does not match the event');
    }
    sources.push(warSource);
    occurrences.push(...warOccurrences(war));
  }
  const after = (await metadata('/info')).data.JP;
  if (!before?.hash || before.hash !== after?.hash || before.timestamp !== after?.timestamp) {
    throw new Error('Atlas JP data changed during inventory lookup. Retry the inventory.');
  }
  const inventory = {
    schema_version: 2,
    story_library_id: 'event-' + eventId,
    region: 'JP',
    atlas_event_id: eventId,
    atlas_revision: before,
    retrieved_at_utc: new Date().toISOString(),
    sources,
    script_occurrences: occurrences,
  };
  checkInventory(inventory, eventId);
  return inventory;
}

function scriptIds(inventory) {
  return new Set(inventory.script_occurrences.map((row) => row.script_id));
}

const titleKey = (value) => String(value ?? '').normalize('NFKC').replace(/\s+/g, '');

// Phase 1: resolve a JP chapter name to Atlas candidates without writing anything.
async function lookupChapter(query) {
  const needle = titleKey(query);
  if (!needle) throw new Error('A JP chapter name is required for lookup');
  const response = await fetch(atlasEventIndex, {
    redirect: 'error',
    signal: AbortSignal.timeout(60_000),
  });
  if (!response.ok) throw new Error('Atlas event index HTTP ' + response.status);
  const events = JSON.parse(utf8.decode(Buffer.from(await response.arrayBuffer())));
  if (!Array.isArray(events)) throw new Error('Atlas event index has an unexpected shape');
  const candidates = events
    .filter((event) => {
      const title = titleKey(event.name);
      return title.includes(needle) || needle.includes(title);
    })
    .map((event) => ({
      atlas_event_id: event.id,
      type: event.type,
      name: event.name.replace(/\s+/g, ' ').trim(),
      war_ids: event.warIds ?? [],
      started_at: event.startedAt,
    }))
    .sort((a, b) => (b.type === 'eventQuest') - (a.type === 'eventQuest') ||
      a.atlas_event_id - b.atlas_event_id);
  return { query, read_only: true, candidates };
}

function checkedSource(scriptId, file, bytes, url) {
  let text;
  try {
    text = utf8.decode(bytes);
  } catch {
    throw new Error(scriptId + ': source is not valid UTF-8');
  }
  if (!text.trim()) throw new Error(scriptId + ': source is empty');
  return {
    bytes,
    text,
    metadata: { script_id: scriptId, file, sha256: sha256(bytes), source_url: url },
  };
}

async function readSources(inventory, sourceDir) {
  const inputs = new Map();
  const missing = [];
  const sourceUrls = atlasSources(inventory);
  for (const scriptId of scriptIds(inventory)) {
    const file = scriptId + '.txt';
    let bytes;
    try {
      bytes = await readFile(join(sourceDir, file));
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      missing.push(scriptId);
      continue;
    }
    inputs.set(scriptId, { file, bytes });
  }
  if (missing.length) throw new Error('Missing Japanese source files for script IDs: ' + missing.join(', '));
  return new Map([...inputs].map(([id, { file, bytes }]) =>
    [id, checkedSource(id, file, bytes, sourceUrls.get(id))]));
}

function sourceFolder(work, id) {
  return join(work, 'sources', id);
}

function atlasSources(inventory) {
  const urls = new Map();
  for (const occurrence of inventory.script_occurrences) {
    const known = urls.get(occurrence.script_id);
    if (known && known !== occurrence.script_url) {
      throw new Error('Atlas script ' + occurrence.script_id + ' has conflicting source URLs');
    }
    urls.set(occurrence.script_id, occurrence.script_url);
  }
  return urls;
}

async function downloadSources(inventory) {
  const sources = new Map();
  for (const [scriptId, url] of atlasSources(inventory)) {
    const response = await fetch(url, {
      redirect: 'error',
      signal: AbortSignal.timeout(20_000),
    });
    if (!response.ok) throw new Error('Atlas script ' + scriptId + ' returned HTTP ' + response.status);
    const bytes = Buffer.from(await response.arrayBuffer());
    sources.set(scriptId, checkedSource(scriptId, scriptId + '.txt', bytes, url));
  }
  return sources;
}

async function stageSources(work, inventory) {
  const id = inventory.story_library_id;
  const parent = join(work, 'sources');
  const local = sourceFolder(work, id);
  try {
    if (!(await stat(local)).isDirectory()) throw new Error('Expected a source directory at ' + local);
    return await readSources(inventory, local);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const sources = await downloadSources(inventory);
  await mkdir(parent, { recursive: true });
  const files = new Map([...sources.values()].map(({ metadata, bytes }) => [metadata.file, bytes]));
  await publish(parent, id, files);
  return sources;
}

function chapterFiles(inventory, sources) {
  const scripts = new Map([...sources].map(([id, source]) => [id, parseJapaneseScript(id, source.text)]));
  const rows = [];
  const entries = [];
  for (const occurrence of inventory.script_occurrences) {
    const scene = occurrenceSlot(occurrence) + ':' + occurrence.script_id;
    const parsed = scripts.get(occurrence.script_id);
    for (const [index, row] of parsed.entries()) {
      // The subchapter id travels with the row so the TSV can show which scene a
      // line belongs to; the inventory stays the authority and is cross-checked.
      rows.push({ ...row, script: occurrence.script_id });
      entries.push({
        ...occurrence,
        tsv_row: rows.length,
        entry_id: scene + ':' + row.line,
        scene_id: scene,
        position: index + 1,
        branch_id: row.branch ? scene + ':' + row.branch : null,
        choice_group: row.choiceGroup ? scene + ':' + row.choiceGroup : null,
        choice_slot: row.choiceSlot ?? null,
        jp_fingerprint: sha256(JSON.stringify([row.name, row.dialog, row.type])),
      });
    }
  }
  if (!rows.length) throw new Error('No dialogue or choices found in the supplied scripts');
  const validation = {
    story_library_id: inventory.story_library_id,
    script_occurrences: inventory.script_occurrences.length,
    distinct_script_ids: sources.size,
    dialogue_rows: rows.filter((row) => row.type === 'dialogue').length,
    choice_rows: rows.filter((row) => row.type === 'choice').length,
  };
  const files = new Map([
    [inventory.story_library_id + '.tsv', japaneseTsv(rows)],
    ['source-inventory.json', json({ ...inventory, local_sources: [...sources.values()].map((value) => value.metadata), entries })],
    ['validation.json', json(validation)],
  ]);
  return { files, validation };
}

const TSV_COLUMNS = [
  'jp_name', 'jp_dialog', 'cn_name', 'cn_dialog',
  'tw_name', 'tw_dialog', 'en_name', 'en_dialog', 'type', 'script',
];

const tsvField = (value) => /["\t\r\n]/.test(value ?? '')
  ? '"' + value.replaceAll('"', '""') + '"'
  : value;

const tsvText = (rows) => rows
  .map((row) => row.map((value) => tsvField(value ?? '')).join('\t'))
  .join('\n') + '\n';

/**
 * Rewrites the leading size marker of an existing chapter TSV from the staged
 * scripts.
 *
 * Translations are expensive and hand-reviewed, so only the marker may change:
 * every other column, and the text after the marker, is compared with a fresh
 * conversion of the same scripts, and any difference aborts before a write.
 */
async function patchSizeTokens(work, id) {
  const folder = join(work, id);
  const tsvPath = join(folder, id + '.tsv');
  const inventory = JSON.parse(await readFile(join(folder, 'source-inventory.json'), 'utf8'));
  const sources = await stageSources(work, inventory);
  const { files } = chapterFiles(inventory, sources);
  const rebuilt = parseTsv(files.get(id + '.tsv')).slice(1);
  const current = parseTsv(await readFile(tsvPath, 'utf8'));
  if (JSON.stringify(current[0]) !== JSON.stringify(TSV_COLUMNS)) {
    throw new Error('Unexpected TSV header in ' + tsvPath);
  }
  const rows = current.slice(1);
  if (rows.length !== rebuilt.length) {
    throw new Error('TSV rows ' + rows.length + ' != converted rows ' + rebuilt.length);
  }
  const sizes = { large: 0, small: 0, xlarge: 0 };
  let changed = 0;
  for (let index = 0; index < rows.length; index++) {
    const have = rows[index];
    const want = rebuilt[index];
    for (const column of [0, 8, 9]) {
      if ((have[column] ?? '') !== (want[column] ?? '')) {
        throw new Error('row ' + (index + 1) + ': ' + TSV_COLUMNS[column] +
          ' differs from the source scripts');
      }
    }
    const dialog = want[1] ?? '';
    // A previous run may already have written a marker into this cell, so compare
    // the text with both markers removed; only a real text change aborts.
    if (stripDialogueMarker(dialog) !== stripDialogueMarker(have[1] ?? '')) {
      throw new Error('row ' + (index + 1) + ': jp_dialog differs beyond the size marker (' +
        JSON.stringify(stripDialogueMarker(have[1] ?? '')) + ' != ' +
        JSON.stringify(stripDialogueMarker(dialog)) + ')');
    }
    const size = dialogueSizeOf(dialog);
    if (size === 'normal') continue;
    sizes[size]++;
    if ((have[1] ?? '') === dialog) continue;
    rows[index] = [...have];
    rows[index][1] = dialog;
    changed++;
  }
  if (changed) {
    const temporary = tsvPath + '.tmp-' + process.pid;
    await writeFile(temporary, tsvText([TSV_COLUMNS, ...rows]));
    await rename(temporary, tsvPath);
  }
  const validationPath = join(folder, 'validation.json');
  let validation = {};
  try {
    validation = JSON.parse(await readFile(validationPath, 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const report = { ...validation, dialogue_size: sizes, size_tokens_updated: changed };
  const temporaryValidation = validationPath + '.tmp-' + process.pid;
  await writeFile(temporaryValidation, json(report));
  await rename(temporaryValidation, validationPath);
  return { ...report, tsv_path: tsvPath };
}

async function publish(work, name, files) {
  // Build a complete set alongside the destination, then swap it in so a
  // failed run never leaves a partial chapter folder behind. An existing
  // output folder is moved aside first because directory rename over an
  // existing directory is not portable.
  const staging = await mkdtemp(join(work, '.story-'));
  const destination = join(work, name);
  let previous = null;
  try {
    for (const [file, body] of files) await writeFile(join(staging, file), body, { flag: 'wx' });
    try {
      await stat(destination);
      previous = destination + '.previous-' + process.pid + '-' + Date.now();
      await rename(destination, previous);
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    await rename(staging, destination);
  } catch (error) {
    if (previous) {
      try { await rename(previous, destination); } catch { /* keep the replacement attempt as the reported failure */ }
    }
    for (const file of files.keys()) await rm(join(staging, file), { force: true });
    await rmdir(staging);
    throw error;
  }
  if (previous) await rm(previous, { recursive: true, force: true }).catch(() => {});
}

async function writeInventory(work, file, values) {
  const staging = await mkdtemp(join(work, '.atlas-'));
  const temporary = join(staging, file);
  try {
    await writeFile(temporary, json(values), { flag: 'wx' });
    await rename(temporary, join(work, file));
  } finally {
    await rm(temporary, { force: true });
    await rmdir(staging);
  }
}

async function completedPreparation(work, id) {
  const folder = join(work, id);
  const tsv = join(folder, id + '.tsv');
  try {
    const report = JSON.parse(await readFile(join(folder, 'validation.json'), 'utf8'));
    const files = await Promise.all([stat(tsv), stat(join(folder, 'source-inventory.json'))]);
    if (report.story_library_id !== id || !positiveInteger(report.dialogue_rows + report.choice_rows) ||
      !files.every((file) => file.isFile() && file.size > 0)) {
      throw new Error('Existing chapter output is incomplete at ' + folder);
    }
    return { ...report, status: 'already_prepared', tsv_path: tsv };
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

async function main(argv) {
  const [action, ...rest] = argv;
  if (action === 'lookup') {
    if (rest.length !== 1) throw new Error(usage);
    console.log(JSON.stringify(await lookupChapter(rest[0]), null, 2));
    return;
  }
  const [id, ...args] = rest;
  const eventId = Number(id);
  const isBuild = action === 'build';
  if (!/^\d+$/.test(id ?? '') || !positiveInteger(eventId) ||
    !['inventory', 'stage', 'build', 'prepare', 'size-tokens'].includes(action) ||
    (isBuild ? args.length < 1 || args.length > 2 : args.length > 1)) {
    throw new Error(usage);
  }
  const work = resolve(args[isBuild ? 1 : 0] ?? defaultWork);
  const idName = 'event-' + eventId;
  const inventoryFile = 'atlas-' + idName + '.json';
  const paths = () => ({
    story_library_id: idName,
    metadata_path: join(work, inventoryFile),
    jp_source_directory: sourceFolder(work, idName),
  });
  if (action === 'prepare') {
    const completed = await completedPreparation(work, idName);
    if (completed) {
      console.log(JSON.stringify({ ...paths(), ...completed }));
      return;
    }
  }
  let inventory;
  if (isBuild) {
    inventory = JSON.parse(await readFile(join(work, inventoryFile), 'utf8'));
    checkInventory(inventory, eventId);
  } else {
    inventory = await inventoryEvent(eventId);
    await mkdir(work, { recursive: true });
    await writeInventory(work, inventoryFile, inventory);
  }
  if (action === 'inventory') {
    console.log(JSON.stringify({
      story_library_id: idName,
      script_occurrences: inventory.script_occurrences.length,
      distinct_script_ids: scriptIds(inventory).size,
      inventory_path: join(work, inventoryFile),
    }));
    return;
  }
  if (action === 'size-tokens') {
    console.log(JSON.stringify({ ...(await patchSizeTokens(work, idName)), ...paths() }));
    return;
  }
  const sources = isBuild
    ? await readSources(inventory, resolve(args[0]))
    : await stageSources(work, inventory);
  if (action === 'stage') {
    console.log(JSON.stringify({ ...paths(), jp_files: sources.size }));
    return;
  }
  const { files, validation } = chapterFiles(inventory, sources);
  await publish(work, idName, files);
  console.log(JSON.stringify({
    ...validation,
    ...paths(),
    jp_files: sources.size,
    tsv_path: join(work, idName, idName + '.tsv'),
  }));
}

main(process.argv.slice(2)).catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
