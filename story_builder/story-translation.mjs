import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, rename, stat, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Translation handoff for the Story Library TSV.
//
// export: writes locale batches that an agent translates.
// apply: validates agent responses and merges them into the locale columns.
// status: reports per-locale coverage and the last applied response.
//
// The canonical Japanese rows, row order, entry IDs, and `type` never change here.

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, '..');
const defaultWork = join(scriptDir, 'work');

const COLUMNS = [
  'jp_name', 'jp_dialog', 'cn_name', 'cn_dialog',
  'tw_name', 'tw_dialog', 'en_name', 'en_dialog', 'type', 'script',
];
const JP_NAME = 0;
const JP_DIALOG = 1;
const TYPE = 8;
const SCRIPT = 9;
const LOCALES = {
  cn: { label: 'Simplified Chinese', name: 2, dialog: 3, playbook: 'translation-agent-cn.md' },
  tw: { label: 'Traditional Chinese', name: 4, dialog: 5, playbook: 'translation-agent-tw.md' },
  en: { label: 'English', name: 6, dialog: 7, playbook: 'translation-agent-en.md' },
};
// A batch is one part of one subchapter: a scene is never split across two
// workers, and `previous_context` never reaches into another scene. Splitting by
// script alone would put a 377-row scene in one batch, so long scenes split into
// equal parts of at most this many rows.
const MAX_BATCH_SIZE = 60;
const PREVIOUS_CONTEXT_ROWS = 6;
const BATCH_FORMAT = 'compact-v1';
const MAX_GLOSSARY_PER_ROW = 5;

const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const json = (value) => JSON.stringify(value, null, 2) + '\n';
const usage = 'Usage: node story-translation.mjs export <event-id> <cn|tw|en> [work-dir] [--pretty]\n' +
  '       node story-translation.mjs check <event-id> <cn|tw|en> [response-file-or-dir] [work-dir]\n' +
  '       node story-translation.mjs apply <event-id> <cn|tw|en> <response-file-or-dir> [work-dir] [--allow-partial] [--replace] [--model <label>] [--harness <label>]\n' +
  '       node story-translation.mjs status <event-id> [work-dir]';

// ─── TSV ─────────────────────────────────────────────────────────────────────

function parseTsv(text) {
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

function formatTsv(rows) {
  const field = (value) => /["\t\r\n]/.test(value) ? '"' + value.replaceAll('"', '""') + '"' : value;
  return rows.map((row) => row.map(field).join('\t')).join('\n') + '\n';
}

async function readTsv(path) {
  const text = await readFile(path, 'utf8');
  const rows = parseTsv(text);
  if (JSON.stringify(rows[0]) !== JSON.stringify(COLUMNS)) {
    throw new Error('Unexpected TSV header at ' + path);
  }
  return { text, header: rows[0], rows: rows.slice(1) };
}

async function readOptionalTsv(path) {
  try {
    const text = await readFile(path, 'utf8');
    const rows = parseTsv(text);
    return { header: rows[0] ?? [], rows: rows.slice(1) };
  } catch (error) {
    if (error.code === 'ENOENT') return { header: [], rows: [] };
    throw error;
  }
}

function eventPaths(work, eventId) {
  const id = 'event-' + eventId;
  return {
    work,
    id,
    tsv: join(work, id, id + '.tsv'),
    inventory: join(work, id, 'source-inventory.json'),
    translation: join(work, id, 'translation'),
  };
}

async function readChapter(work, eventId) {
  const paths = eventPaths(work, eventId);
  const chapter = await readTsv(paths.tsv);
  const inventory = JSON.parse(await readFile(paths.inventory, 'utf8'));
  const entries = inventory.entries;
  if (!Array.isArray(entries) || entries.length !== chapter.rows.length) {
    throw new Error('TSV rows do not match source-inventory entries; rerun prepare for event ' + eventId);
  }
  for (let index = 0; index < entries.length; index++) {
    if (entries[index].tsv_row !== index + 1) {
      throw new Error('source-inventory entries are out of order at row ' + (index + 1));
    }
    if ((chapter.rows[index][SCRIPT] ?? '') !== entries[index].script_id) {
      throw new Error('row ' + (index + 1) + ': script ' + JSON.stringify(chapter.rows[index][SCRIPT] ?? '') +
        ' != source-inventory ' + entries[index].script_id);
    }
  }
  return { paths, chapter, entries, inventory };
}

// ─── Glossary ────────────────────────────────────────────────────────────────

async function loadGlossary(overlayPath) {
  const terms = await readOptionalTsv(join(repoRoot, 'term_builder', 'term.tsv'));
  const names = await readOptionalTsv(join(repoRoot, 'term_builder', 'character_names.tsv'));
  const overrides = await readOptionalTsv(
    join(repoRoot, 'app', 'src', 'main', 'assets', 'translation', 'traditional_glossary_overrides.tsv'));
  const overrideBySource = new Map();
  for (const row of overrides.rows) {
    if (row.length < 3 || !row[0].trim() || !row[2].trim()) continue;
    overrideBySource.set(row[0].trim(), { reference: row[1].trim(), traditional: row[2].trim() });
  }
  let overlayEntries = [];
  try {
    overlayEntries = JSON.parse(await readFile(overlayPath, 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  if (!Array.isArray(overlayEntries)) {
    throw new Error('Chapter term overlay must be a JSON array: ' + overlayPath);
  }
  const overlayBySource = new Map();
  for (const entry of overlayEntries) {
    if (!entry || typeof entry !== 'object' ||
      typeof entry.source !== 'string' || !entry.source.trim() ||
      typeof entry.target !== 'string' || !entry.target.trim()) {
      throw new Error('Chapter term overlay entries need nonempty "source" and "target" strings');
    }
    overlayBySource.set(entry.source.trim(), entry);
  }
  return { terms, names, overrides, overrideBySource, overlayEntries, overlayBySource };
}

// Reviewed official name forms per server, used to detect cross-server leakage:
// a CN target that uses a TW reviewed spelling (or the reverse) is a fit defect.
let officialNamesCache = null;
async function loadOfficialNames() {
  if (officialNamesCache) return officialNamesCache;
  const names = await readOptionalTsv(join(repoRoot, 'term_builder', 'character_names.tsv'));
  const overrides = await readOptionalTsv(
    join(repoRoot, 'app', 'src', 'main', 'assets', 'translation', 'traditional_glossary_overrides.tsv'));
  const cnNames = new Set();
  for (const row of names.rows) {
    if (row.length < 3) continue;
    const cn = (row[1] ?? '').trim();
    if (cn.length >= 2) cnNames.add(cn);
  }
  const twNames = new Set();
  for (const row of overrides.rows) {
    if (row.length < 3) continue;
    const tw = (row[2] ?? '').trim();
    if (tw.length >= 2) twNames.add(tw);
  }
  officialNamesCache = { cnNames, twNames };
  return officialNamesCache;
}

function splitAliases(value) {
  return (value ?? '').split(/[,，\n]/)
    .map((alias) => alias.trim().replace(/^[[\]"'\s]+|[[\]"'\s]+$/g, ''))
    .filter((alias) => alias.length >= 2);
}

function addGlossaryEntry(list, seeSources, entry) {
  if (!entry.source || !entry.target) return;
  if (seeSources.has(entry.source)) return;
  seeSources.add(entry.source);
  list.push(entry);
}

function glossaryForRow(text, speaker, locale, glossary) {
  const list = [];
  const seen = new Set();
  const targetOf = (cn, en) => locale === 'en' ? en : cn;
  const twTarget = (source, cn) => {
    const override = glossary.overrideBySource.get(source);
    if (override && (!override.reference || override.reference === cn)) {
      return { target: override.traditional, note: 'reviewed zh-Hant override' };
    }
    return { target: cn, note: 'zh-Hans glossary; render in Traditional Chinese unless a reviewed override exists' };
  };
  // Chapter-local ledger first: decisions made while translating this chapter
  // override the shipped glossary and stay consistent across batches.
  for (const entry of glossary.overlayEntries) {
    if (!(entry.source.length >= 2 && text.includes(entry.source))) continue;
    addGlossaryEntry(list, seen, {
      source: entry.source,
      target: entry.target,
      category: entry.category ?? '',
      note: ['chapter glossary', entry.note].filter(Boolean).join(': '),
    });
    if (list.length >= MAX_GLOSSARY_PER_ROW) break;
  }
  const push = (source, cn, en, category, gender) => {
    const overlay = glossary.overlayBySource.get(source);
    if (overlay) {
      addGlossaryEntry(list, seen, {
        source,
        target: overlay.target,
        category: overlay.category ?? category,
        note: ['chapter glossary', overlay.note].filter(Boolean).join(': '),
      });
      return;
    }
    const picked = locale === 'tw' ? twTarget(source, cn) : { target: targetOf(cn, en), note: '' };
    addGlossaryEntry(list, seen, {
      source,
      target: picked.target,
      category,
      note: [picked.note, gender ? 'gender: ' + gender : ''].filter(Boolean).join('; '),
    });
  };

  for (const row of glossary.terms.rows) {
    if (row.length < 6) continue;
    const [jpTerm, cnTerm, enTerm, , category, aliases] = row;
    if (!jpTerm?.trim()) continue;
    const needles = [jpTerm.trim(), ...splitAliases(aliases)];
    if (!needles.some((needle) => needle.length >= 2 && text.includes(needle))) continue;
    if (!cnTerm?.trim() || !enTerm?.trim()) continue;
    push(jpTerm.trim(), cnTerm.trim(), enTerm.trim(), category?.trim() ?? '');
    if (list.length >= MAX_GLOSSARY_PER_ROW) break;
  }
  if (list.length < MAX_GLOSSARY_PER_ROW) {
    for (const row of glossary.names.rows) {
      if (row.length < 6) continue;
      const [jpName, cnName, enName, , gender, aliases] = row;
      if (!jpName?.trim()) continue;
      const needles = [jpName.trim(), ...splitAliases(aliases)];
      const matched = needles.some((needle) => needle.length >= 2 && text.includes(needle));
      const isSpeaker = speaker && jpName.trim() === speaker;
      if (!matched && !isSpeaker) continue;
      if (!cnName?.trim() || !enName?.trim()) continue;
      push(jpName.trim(), cnName.trim(), enName.trim(), 'character', gender?.trim() ?? '');
      if (list.length >= MAX_GLOSSARY_PER_ROW) break;
    }
  }
  return list;
}

// ─── Export ──────────────────────────────────────────────────────────────────

/**
 * Splits the chapter into batch ranges that never straddle a subchapter.
 *
 * Each range is one part of one `script_id`: consecutive rows that share the id,
 * split evenly when the scene is longer than [maxRows]. Returns
 * `{start, end, script, scriptStart, part, parts}` with a zero-based `end`.
 */
function scriptBatchRanges(entries, maxRows) {
  const ranges = [];
  let scriptStart = 0;
  while (scriptStart < entries.length) {
    const script = entries[scriptStart].script_id;
    let end = scriptStart;
    while (end < entries.length && entries[end].script_id === script) end++;
    const size = end - scriptStart;
    const parts = Math.max(1, Math.ceil(size / maxRows));
    const perPart = Math.ceil(size / parts);
    for (let part = 1, at = scriptStart; at < end; part++) {
      const stop = Math.min(end, at + perPart);
      ranges.push({ start: at, end: stop, script, scriptStart, part, parts });
      at = stop;
    }
    scriptStart = end;
  }
  return ranges;
}

async function exportBatches(eventId, locale, work, flags) {
  const config = LOCALES[locale];
  const book = await readChapter(work, eventId);
  const rows = book.chapter.rows;
  const overlayPath = join(book.paths.translation, locale, 'terms.json');
  const glossary = await loadGlossary(overlayPath);
  // Compact batches: the translation contract only needs the source text, the
  // exact entry IDs, and the matching glossary. Emitting objects with every key
  // spelled out on every row made each batch ~713 bytes/row, of which the
  // Japanese text was only ~11%. The compact shape is ~3x smaller for the same
  // information, which is paid for on every worker that reads a batch.
  const contextItem = (index) => [book.entries[index].entry_id, rows[index][JP_NAME], rows[index][JP_DIALOG]];
  const needsTranslation = (row) =>
    !row[config.dialog].trim() || (!!row[JP_NAME].trim() && !row[config.name].trim());
  const pending = rows.filter(needsTranslation).length;
  const batches = [];
  for (const range of scriptBatchRanges(book.entries, MAX_BATCH_SIZE)) {
    const { start, end, script, scriptStart, part, parts } = range;
    // Context never crosses a subchapter: a part that continues the same scene
    // keeps its last six rows, a new scene starts empty.
    const previousStart = Math.max(scriptStart, start - PREVIOUS_CONTEXT_ROWS);
    const glossaryEntries = [];
    const glossaryIndex = new Map();
    const entries = [];
    const pendingIds = [];
    const existing = {};
    const scenes = [];
    const controls = new Map();
    rows.slice(start, end).forEach((row, offset) => {
      const index = start + offset;
      const entry = book.entries[index];
      const text = [row[JP_NAME], row[JP_DIALOG], entry.scene_id].join('\n');
      const refs = [];
      for (const match of glossaryForRow(text, row[JP_NAME], locale, glossary)) {
        const key = match.source + ' ' + match.target;
        let at = glossaryIndex.get(key);
        if (at === undefined) {
          at = glossaryEntries.length;
          glossaryEntries.push({
            source: match.source, target: match.target, category: match.category, note: match.note,
          });
          glossaryIndex.set(key, at);
        }
        refs.push(at);
      }
      if (needsTranslation(row)) pendingIds.push(entry.entry_id);
      if ((row[config.name] ?? '').trim() || (row[config.dialog] ?? '').trim()) {
        existing[entry.entry_id] = { name: row[config.name], dialog: row[config.dialog] };
      }
      const last = scenes[scenes.length - 1];
      if (last && last[0] === entry.scene_id) last[2] = offset + 1;
      else scenes.push([entry.scene_id, offset + 1, offset + 1]);
      const source = row[JP_NAME] + '\n' + row[JP_DIALOG];
      const bump = (label, count) => { if (count) controls.set(label, (controls.get(label) ?? 0) + count); };
      bump('master_name', countMatches(source, /\[%\d+\]/g));
      bump('gender_control', countMatches(source, /\[&/g));
      bump('ruby', countMatches(source, /〈/g));
      bump('emphasis', countMatches(source, /⟦/g));
      entries.push([entry.entry_id, row[TYPE], row[JP_NAME], row[JP_DIALOG], refs]);
    });
    batches.push({
      previous_context: start > previousStart ? rows.slice(previousStart, start)
        .map((row, offset) => contextItem(previousStart + offset)) : [],
      glossary: glossaryEntries,
      entries,
      pending: pendingIds.length === entries.length ? 'all' : pendingIds,
      existing,
      scenes,
      scripts: [script],
      ...(parts > 1 ? { script_part: [part, parts] } : {}),
      controls: Object.fromEntries(controls),
    });
  }

  const dir = join(book.paths.translation, locale);
  await mkdir(dir, { recursive: true });
  const manifest = {
    schema_version: 1,
    event_id: Number(eventId),
    story_library_id: book.paths.id,
    locale,
    label: config.label,
    agent_playbook: config.playbook,
    source_tsv: book.paths.tsv,
    source_tsv_sha256: sha256(book.chapter.text),
    rows: rows.length,
    dialogue_rows: rows.filter((row) => row[TYPE] === 'dialogue').length,
    choice_rows: rows.filter((row) => row[TYPE] === 'choice').length,
    pending_rows: pending,
    batch_align: 'script',
    batch_max_rows: MAX_BATCH_SIZE,
    batch_count: batches.length,
    glossary: {
      terms: glossary.terms.rows.length,
      character_names: glossary.names.rows.length,
      traditional_overrides: glossary.overrides.rows.length,
      chapter_ledger: glossary.overlayEntries.length,
    },
    created_at_utc: new Date().toISOString(),
  };
  const names = [];
  for (let index = 0; index < batches.length; index++) {
    const name = 'batch-' + String(index + 1).padStart(3, '0') + '.json';
    const batch = batches[index];
    const body = {
      event_id: Number(eventId),
      story_library_id: book.paths.id,
      locale,
      batch: index + 1,
      batch_count: batches.length,
      source_tsv_sha256: manifest.source_tsv_sha256,
      format: BATCH_FORMAT,
      fields: ['entry_id', 'type', 'jp_name', 'jp_dialog', 'glossary_indexes'],
      scripts: batch.scripts,
      ...(batch.script_part ? { script_part: batch.script_part } : {}),
      scenes: batch.scenes,
      pending: batch.pending,
      ...(Object.keys(batch.controls).length ? { controls: batch.controls } : {}),
      glossary: batch.glossary,
      previous_context: batch.previous_context,
      ...(Object.keys(batch.existing).length ? { existing: batch.existing } : {}),
      entries: batch.entries,
    };
    await writeFile(join(dir, name), flags.pretty ? json(body) : JSON.stringify(body) + '\n');
    names.push(name);
  }
  manifest.batches = names;
  await writeFile(join(dir, 'manifest.json'), json(manifest));
  return {
    story_library_id: book.paths.id,
    locale,
    label: config.label,
    directory: dir,
    rows: manifest.rows,
    pending_rows: pending,
    batch_count: manifest.batch_count,
    chapter_ledger: manifest.glossary.chapter_ledger,
    agent_playbook: config.playbook,
    source_tsv_sha256: manifest.source_tsv_sha256,
  };
}

// ─── Apply ───────────────────────────────────────────────────────────────────

const masterNameToken = /\[%\d+\]/g;
const genderControlToken = /\[&[^:\]]+:[^\]]+\]/g;
const genderControlShape = /^\[&([^:\]]+):([^:\]]+)\]$/;
const brackets = /[\[\]]/g;
const kana = /[\u3040-\u30FF\u31F0-\u31FF\uFF66-\uFF9D]/;
const han = /[\u3400-\u9FFF\uF900-\uFAFF]/;
// Characters whose simplified and traditional forms differ. They enforce the
// script of the official server a locale belongs to (CN Simplified, TW
// Traditional); using the other server's forms is a localization fit defect.
const twOnlyChars =
  /[們這嗎說時會對為與從國學靈寶術無劍戰龍聖轉還麼啟動變擊強選擇顯現實間關係條約證驗經歷處樣數電車東兒妳臺灣瑪爾亞奧羅達貝賽澤貢剛絲維測試譯語話請謝讓錯誤讀寫聽響開閉問題題機關門長隊軍給統壞載運過遠進遲適邊遞確認識議護費貨賣買資質財貴賓賢賜贈贏購贊麗樂愛願覺張鐘錢鐵銀鋼錄鍵藥藝節簡續織繪終組細紛紅紀級糧腦臉臨舉舊範築簽籌幫帶幣廣廠廢庫應憶態慣慮慶憂憐憑戲擔據擠擺擬擴攜敗敵斷曉暫書極標樹橋權歡殺氣決沒淚淺湯準滅滿漢潔濃濟牆獨獲環產畫異當療發盤眾瞬間禮禍種稱穩窮競筆類紙結絕綠綱網緊線練縮績罷聲職聯聰脅脫艙]/;
const cnOnlyChars =
  /[们这吗说时会对为与从国学灵宝术无剑战龙圣转还么启动变击强选择显现实间关系条约证验经历处样数电车东儿湾玛尔亚奥罗达贝赛泽贡刚丝维测试译语话请谢让错误读写听响开闭问题题机关门长队军给统坏载运过远进迟适边递确认识议护费货卖买资质财贵宾贤赐赠赢购赞丽乐爱愿觉张钟钱铁银钢录键药艺节简续织绘终组细纷红纪级粮脑脸临举旧范筑签筹帮带币广厂废库应忆态惯虑庆忧怜凭戏担据挤摆拟扩携败敌断晓暂书极标树桥权欢杀气决没泪浅汤准灭满汉洁浓济墙独获环产画异当疗发盘众瞬间礼祸种称稳穷竞笔类纸结绝绿纲网紧线练缩绩罢声职联聪胁脱舱]/;

function countMatches(value, pattern) {
  return (value.match(pattern) ?? []).length;
}

function masterNameTokens(value) {
  return (value.match(masterNameToken) ?? []).sort();
}

function genderControlTokens(value) {
  return value.match(genderControlToken) ?? [];
}

// Visible text with controls stripped to their content: [%1] disappears, while
// the two [&male:female] variants stay so untranslated variants are detected.
function visibleControlText(value) {
  return value
    .replace(masterNameToken, '')
    .replace(genderControlToken, (token) => {
      const match = genderControlShape.exec(token);
      return match ? match[1] + ' ' + match[2] : token;
    })
    .replace(brackets, '');
}

function validateTranslation(row, config, translation, officialNames) {
  const errors = [];
  const warnings = [];
  const source = { name: row[JP_NAME], dialog: row[JP_DIALOG] };
  const target = { name: translation.name ?? '', dialog: translation.dialog ?? '' };
  const type = row[TYPE];
  const sourceText = source.name + '\n' + source.dialog;
  const targetText = target.name + '\n' + target.dialog;

  for (const field of ['name', 'dialog']) {
    if (target[field] !== target[field].trim()) errors.push(field + '-has-edge-whitespace');
    if (/[\r\n]/.test(target[field]) && field === 'name') errors.push('name-has-line-break');
  }
  if (!target.dialog.trim()) errors.push('empty-dialog');
  if (type === 'choice' && target.name.trim()) errors.push('choice-has-name');
  if (!!source.name.trim() !== !!target.name.trim()) {
    errors.push(source.name.trim() ? 'missing-translated-name' : 'name-added-for-unnamed-row');
  }

  // [%1] is the in-game Master name, so it must survive unchanged. The
  // [&male:female] gender control keeps its wrapper but both variants are
  // translated, so it is validated structurally instead of byte-for-byte.
  if (JSON.stringify(masterNameTokens(sourceText)) !== JSON.stringify(masterNameTokens(targetText))) {
    errors.push('master-name-placeholder-changed');
  }
  const sourceGender = genderControlTokens(sourceText);
  const targetGender = genderControlTokens(targetText);
  if (sourceGender.length !== targetGender.length) errors.push('gender-controls-changed');
  for (const token of targetGender) {
    const match = genderControlShape.exec(token);
    if (!match || !match[1].trim() || !match[2].trim()) {
      errors.push('gender-control-malformed');
      break;
    }
  }
  const kanaTarget = visibleControlText(target.name) + '\n' + visibleControlText(target.dialog);
  if (kana.test(kanaTarget)) errors.push('untranslated-kana-remains');
  for (const field of ['name', 'dialog']) {
    const open = countMatches(target[field], /〈/g);
    const close = countMatches(target[field], /〉/g);
    if (open !== close) errors.push(field + '-unbalanced-ruby');
    const sourceOpen = countMatches(source[field], /〈/g);
    if (open > sourceOpen) warnings.push(field + '-invented-ruby');
    if (open < sourceOpen) warnings.push(field + '-ruby-dropped');
    const markOpen = countMatches(target[field], /⟦/g);
    const markClose = countMatches(target[field], /⟧/g);
    if (markOpen !== markClose) errors.push(field + '-unbalanced-emphasis');
    const sourceMark = countMatches(source[field], /⟦/g);
    if (markOpen !== sourceMark) warnings.push(field + '-emphasis-changed');
  }
  if (target.dialog === source.dialog && kana.test(source.dialog)) errors.push('dialog-not-translated');
  const targetVisible = visibleControlText(target.dialog);
  const targetVisibleAll = visibleControlText(target.name) + '\n' + visibleControlText(target.dialog);
  if (config.label === 'English') {
    if (han.test(targetVisible)) warnings.push('han-remains-in-english');
  } else if (!han.test(targetVisible) && han.test(source.dialog)) {
    warnings.push('no-han-in-chinese');
  }
  // Official-server fit: CN must be Simplified with mainland official terms,
  // TW must be Traditional with Taiwan official terms. Mixing scripts or the
  // other server's reviewed name forms is a server-fit defect.
  if (config.label === 'Traditional Chinese') {
    if (cnOnlyChars.test(targetVisibleAll)) errors.push('simplified-chars-in-tw');
    for (const name of officialNames.cnNames) {
      if (targetVisibleAll.includes(name)) {
        warnings.push('cn-name-form-in-tw: ' + name);
        break;
      }
    }
  } else if (config.label === 'Simplified Chinese') {
    if (twOnlyChars.test(targetVisibleAll)) errors.push('traditional-chars-in-cn');
    for (const name of officialNames.twNames) {
      if (targetVisibleAll.includes(name)) {
        warnings.push('tw-name-form-in-cn: ' + name);
        break;
      }
    }
  }
  if (target.name === source.name && kana.test(source.name)) errors.push('name-not-translated');
  if (target.dialog.length > source.dialog.length * 3 + 40) warnings.push('dialog-suspiciously-long');
  return { errors, warnings };
}

async function readResponses(input) {
  const info = await stat(input);
  const files = info.isDirectory()
    ? (await readdir(input)).filter((name) => name.endsWith('.json')).sort().map((name) => join(input, name))
    : [input];
  const translations = [];
  const proposals = [];
  for (const file of files) {
    const payload = JSON.parse(await readFile(file, 'utf8'));
    const items = Array.isArray(payload)
      ? payload
      : payload.translations ?? payload.entries ?? [payload];
    if (!Array.isArray(items)) throw new Error('Response file has no translations array: ' + file);
    for (const item of items) {
      if (!item || typeof item !== 'object' || typeof item.entry_id !== 'string') {
        throw new Error('Response item without entry_id in ' + file);
      }
      translations.push({ ...item, response_file: file, locale: payload.locale });
    }
    if (payload.term_proposals !== undefined) {
      if (!Array.isArray(payload.term_proposals)) {
        throw new Error('term_proposals must be an array in ' + file);
      }
      for (const proposal of payload.term_proposals) {
        if (!proposal || typeof proposal !== 'object' ||
          typeof proposal.source !== 'string' || !proposal.source.trim() ||
          typeof proposal.target !== 'string' || !proposal.target.trim()) {
          throw new Error('term_proposals entries need nonempty "source" and "target" strings in ' + file);
        }
        proposals.push({ ...proposal, response_file: file });
      }
    }
  }
  return { translations, proposals };
}

function batchLabel(file) {
  return typeof file === 'string' ? file.split(/[\\/]/).pop() : 'unknown';
}

// Shared validation used by both `check` (read-only) and `apply` (writes TSV).
async function collectResponses(book, config, input, locale) {
  const rows = book.chapter.rows;
  const officialNames = await loadOfficialNames();
  const byEntry = new Map(book.entries.map((entry, index) => [entry.entry_id, { entry, index }]));
  const { translations: responses, proposals } = await readResponses(input);
  const errors = [];
  const warnings = [];
  const chosen = new Map();
  for (const translation of responses) {
    const batch = batchLabel(translation.response_file);
    if (translation.locale && translation.locale !== locale) {
      errors.push({ entry_id: translation.entry_id, problems: ['response-locale-mismatch'], batch });
      continue;
    }
    const known = byEntry.get(translation.entry_id);
    if (!known) {
      errors.push({ entry_id: translation.entry_id, problems: ['unknown-entry-id'], batch });
      continue;
    }
    if (chosen.has(translation.entry_id)) {
      errors.push({ entry_id: translation.entry_id, problems: ['duplicate-entry-id'], batch });
      continue;
    }
    const result = validateTranslation(rows[known.index], config, translation, officialNames);
    if (result.errors.length) {
      errors.push({ entry_id: translation.entry_id, problems: result.errors, batch });
      continue;
    }
    if (result.warnings.length) {
      warnings.push({ entry_id: translation.entry_id, problems: result.warnings, batch });
    }
    chosen.set(translation.entry_id, { translation, index: known.index });
  }
  const required = rows
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => !row[config.dialog].trim() ||
      (!!row[JP_NAME].trim() && !row[config.name].trim()))
    .map(({ index }) => book.entries[index].entry_id);
  const missing = required.filter((entryId) => !chosen.has(entryId));
  return { chosen, errors, warnings, missing, proposals, responses };
}

// Check a response set without touching the TSV: per-entry validation, coverage,
// cross-batch term/speaker consistency, and source anomalies.
async function checkResponses(eventId, locale, input, work) {
  const config = LOCALES[locale];
  const book = await readChapter(work, eventId);
  const rows = book.chapter.rows;
  const collected = await collectResponses(book, config, input, locale);
  const errors = [...collected.errors];
  const warnings = [...collected.warnings];

  const termTargets = new Map();
  for (const proposal of collected.proposals) {
    const source = proposal.source.trim();
    if (!termTargets.has(source)) termTargets.set(source, new Set());
    termTargets.get(source).add(proposal.target.trim());
  }
  const termConflicts = [...termTargets.entries()].filter(([, targets]) => targets.size > 1)
    .map(([source, targets]) => ({ source, targets: [...targets] }));
  for (const conflict of termConflicts) {
    errors.push({
      entry_id: null,
      problems: ['term-target-conflict: ' + conflict.source],
      batch: null,
      targets: conflict.targets,
    });
  }

  const nameTargets = new Map();
  for (const { translation, index } of collected.chosen.values()) {
    const jpName = rows[index][JP_NAME].trim();
    if (!jpName) continue;
    if (!nameTargets.has(jpName)) nameTargets.set(jpName, new Set());
    nameTargets.get(jpName).add((translation.name ?? '').trim());
  }
  const speakerNameConflicts = [...nameTargets.entries()].filter(([, targets]) => targets.size > 1)
    .map(([jpName, targets]) => ({ jp_name: jpName, targets: [...targets] }));
  for (const conflict of speakerNameConflicts) {
    errors.push({
      entry_id: null,
      problems: ['speaker-name-conflict: ' + conflict.jp_name],
      batch: null,
      targets: conflict.targets,
    });
  }

  let sourceAnomalies = 0;
  for (const [entryId, { index }] of collected.chosen) {
    const row = rows[index];
    if (/[\uE000-\uF8FF]/.test(row[JP_NAME] + row[JP_DIALOG])) {
      sourceAnomalies++;
      warnings.push({ entry_id: entryId, problems: ['source-private-use-glyph'], batch: null });
    }
  }

  const batches = new Map();
  const bucket = (batch) => {
    const key = batchLabel(batch);
    if (!batches.has(key)) batches.set(key, { batch: key, entries: 0, errors: 0, warnings: 0 });
    return batches.get(key);
  };
  for (const item of errors) bucket(item.batch).errors++;
  for (const item of warnings) bucket(item.batch).warnings++;
  for (const { translation } of collected.chosen.values()) bucket(translation.response_file).entries++;

  return {
    check: true,
    ok: errors.length === 0,
    locale,
    event_id: Number(eventId),
    entries: collected.chosen.size,
    missing_from_required: collected.missing.length,
    error_count: errors.length,
    warning_count: warnings.length,
    term_conflicts: termConflicts,
    speaker_name_conflicts: speakerNameConflicts,
    source_anomalies: sourceAnomalies,
    batches: [...batches.values()].sort((a, b) => a.batch.localeCompare(b.batch)),
    errors: errors.slice(0, 50),
    warnings: warnings.slice(0, 50),
  };
}

async function refreshManifests(work, eventId, tsvText, rows) {
  const refreshed = [];
  const tsvHash = sha256(tsvText);
  for (const [locale, config] of Object.entries(LOCALES)) {
    const manifestPath = join(work, 'event-' + eventId, 'translation', locale, 'manifest.json');
    let manifest;
    try {
      manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
    } catch (error) {
      if (error.code === 'ENOENT') continue;
      throw error;
    }
    manifest.source_tsv_sha256 = tsvHash;
    manifest.pending_rows = rows.filter((row) => !row[config.dialog].trim() ||
      (!!row[JP_NAME].trim() && !row[config.name].trim())).length;
    const temporary = manifestPath + '.tmp-' + process.pid;
    await writeFile(temporary, json(manifest));
    await rename(temporary, manifestPath);
    refreshed.push(locale);
  }
  return refreshed;
}

// Record accepted proposals into the chapter ledger so later exports carry them.
// First decision per source wins; a conflicting later target is reported, not applied.
async function updateLedger(dir, proposals) {
  if (!proposals.length) return { added: 0, known: 0, conflicts: [] };
  const ledgerPath = join(dir, 'terms.json');
  let ledger = [];
  try {
    ledger = JSON.parse(await readFile(ledgerPath, 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  if (!Array.isArray(ledger)) throw new Error('Chapter term ledger must be a JSON array: ' + ledgerPath);
  const bySource = new Map(ledger
    .filter((entry) => entry && typeof entry.source === 'string')
    .map((entry) => [entry.source, entry]));
  const conflicts = [];
  let added = 0;
  let known = 0;
  for (const proposal of proposals) {
    const source = proposal.source.trim();
    const target = proposal.target.trim();
    const existing = bySource.get(source);
    if (existing) {
      if (existing.target === target) known++;
      else conflicts.push({ source, kept: existing.target, proposed: target });
      continue;
    }
    const entry = {
      source,
      target,
      category: proposal.category?.trim() ?? '',
      note: proposal.note?.trim() ?? '',
    };
    ledger.push(entry);
    bySource.set(source, entry);
    added++;
  }
  const temporary = ledgerPath + '.tmp-' + process.pid;
  await writeFile(temporary, json(ledger));
  await rename(temporary, ledgerPath);
  return { added, known, conflicts: conflicts.slice(0, 20) };
}

async function applyResponses(eventId, locale, input, work, flags) {
  const config = LOCALES[locale];
  const book = await readChapter(work, eventId);
  const rows = book.chapter.rows;
  const dir = join(book.paths.translation, locale);
  const manifest = JSON.parse(await readFile(join(dir, 'manifest.json'), 'utf8'));
  const currentHash = sha256(book.chapter.text);
  if (manifest.source_tsv_sha256 !== currentHash && !flags.replace) {
    throw new Error('TSV changed since export; re-run export before apply');
  }
  const collected = await collectResponses(book, config, input, locale);
  const { chosen, warnings, missing, proposals } = collected;
  const errors = [...collected.errors];
  if (missing.length && !flags.allowPartial) {
    errors.push({ entry_id: null, problems: ['missing-translations: ' + missing.length] });
  }
  if (errors.length) {
    const report = {
      applied: false,
      locale,
      event_id: Number(eventId),
      error_count: errors.length,
      errors: errors.slice(0, 20),
      warning_count: warnings.length,
      pending: missing.length,
    };
    console.log(JSON.stringify(report, null, 2));
    throw new Error('Response validation failed; TSV was not modified');
  }

  const applied = [];
  for (const [entryId, { translation, index }] of chosen) {
    const row = rows[index];
    if (row[config.dialog].trim() && !flags.replace) {
      warnings.push({ entry_id: entryId, problems: ['already-translated-row-kept'] });
      continue;
    }
    row[config.name] = (translation.name ?? '').trim();
    row[config.dialog] = (translation.dialog ?? '').trim();
    applied.push(entryId);
  }
  const nextTsv = formatTsv([COLUMNS, ...rows]);
  const temporary = join(book.paths.work, '.' + book.paths.id + '.tsv.tmp-' + process.pid);
  await writeFile(temporary, nextTsv);
  await rename(temporary, book.paths.tsv);

  // Refresh every locale manifest so sequential cross-locale applies stay valid.
  const pending = rows.filter((row) => !row[config.dialog].trim() ||
    (!!row[JP_NAME].trim() && !row[config.name].trim())).length;
  const refreshedManifests = await refreshManifests(work, eventId, nextTsv, rows);

  const appliedPath = join(dir, 'applied.json');
  let state = { locale, status: 'ai_draft', entries: {} };
  try {
    state = JSON.parse(await readFile(appliedPath, 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  state.status = 'ai_draft';
  state.applied_at_utc = new Date().toISOString();
  state.model = flags.model || state.model || '';
  state.harness = flags.harness || state.harness || '';
  state.source_tsv_sha256 = sha256(nextTsv);
  for (const [entryId, { translation }] of chosen) {
    state.entries[entryId] = {
      status: 'ai_draft',
      name: (translation.name ?? '').trim(),
      dialog: (translation.dialog ?? '').trim(),
      response_sha256: sha256(JSON.stringify([translation.name ?? '', translation.dialog ?? ''])),
    };
  }
  const stateTemp = appliedPath + '.tmp-' + process.pid;
  await writeFile(stateTemp, json(state));
  await rename(stateTemp, appliedPath);

  let recorded = 0;
  let proposalsPath = null;
  if (proposals.length) {
    proposalsPath = join(dir, 'term-proposals.json');
    let ledger = { schema_version: 1, locale, status: 'proposed', proposals: [] };
    try {
      ledger = JSON.parse(await readFile(proposalsPath, 'utf8'));
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    const known = new Set((ledger.proposals ?? []).map((p) => p.source + '\u0000' + p.target));
    for (const proposal of proposals) {
      const entry = {
        source: proposal.source.trim(),
        target: proposal.target.trim(),
        category: proposal.category?.trim() ?? '',
        note: proposal.note?.trim() ?? '',
        response_file: proposal.response_file,
        proposed_at_utc: new Date().toISOString(),
      };
      const key = entry.source + '\u0000' + entry.target;
      if (known.has(key)) continue;
      known.add(key);
      ledger.proposals.push(entry);
      recorded++;
    }
    const proposalsTemp = proposalsPath + '.tmp-' + process.pid;
    await writeFile(proposalsTemp, json(ledger));
    await rename(proposalsTemp, proposalsPath);
  }
  const ledger = await updateLedger(dir, proposals);
  return {
    applied: true,
    locale,
    event_id: Number(eventId),
    applied_rows: applied.length,
    warnings: warnings.slice(0, 20),
    warning_count: warnings.length,
    pending,
    term_proposals_recorded: recorded,
    term_proposals_path: proposalsPath,
    chapter_ledger: ledger,
    refreshed_manifests: refreshedManifests,
    tsv_path: book.paths.tsv,
    state_path: appliedPath,
  };
}

// ─── Status ──────────────────────────────────────────────────────────────────

async function reportStatus(eventId, work) {
  const book = await readChapter(work, eventId);
  const rows = book.chapter.rows;
  const locales = {};
  for (const [locale, config] of Object.entries(LOCALES)) {
    const verified = rows.filter((row) => row[config.dialog].trim()).length;
    const named = rows.filter((row) => row[JP_NAME].trim());
    const namedDone = named.filter((row) => row[config.name].trim()).length;
    let applied = null;
    try {
      const state = JSON.parse(await readFile(
        join(book.paths.translation, locale, 'applied.json'), 'utf8'));
      applied = {
        status: state.status ?? '',
        applied_at_utc: state.applied_at_utc ?? '',
        model: state.model ?? '',
        harness: state.harness ?? '',
        entries: Object.keys(state.entries ?? {}).length,
      };
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    locales[locale] = {
      label: config.label,
      rows: rows.length,
      dialogue_rows: rows.filter((row) => row[TYPE] === 'dialogue').length,
      choice_rows: rows.filter((row) => row[TYPE] === 'choice').length,
      translated_dialogs: verified,
      pending_dialogs: rows.length - verified,
      named_rows: named.length,
      translated_names: namedDone,
      pending_names: named.length - namedDone,
      applied,
    };
  }
  return {
    story_library_id: book.paths.id,
    tsv_path: book.paths.tsv,
    locales,
  };
}

// ─── CLI ─────────────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const positional = [];
  const flags = { allowPartial: false, replace: false, pretty: false, model: '', harness: '' };
  for (let index = 0; index < argv.length; index++) {
    const value = argv[index];
    if (value === '--allow-partial') flags.allowPartial = true;
    else if (value === '--replace') flags.replace = true;
    else if (value === '--pretty') flags.pretty = true;
    else if (value === '--model') flags.model = argv[++index] ?? '';
    else if (value === '--harness') flags.harness = argv[++index] ?? '';
    else positional.push(value);
  }
  return { positional, flags };
}

async function main(argv) {
  const [action] = argv;
  const { positional, flags } = parseArgs(argv.slice(1));
  if (!/^\d+$/.test(positional[0] ?? '')) throw new Error(usage);
  if (action === 'status') {
    if (positional.length < 1 || positional.length > 2) throw new Error(usage);
    const work = resolve(positional[1] ?? defaultWork);
    console.log(JSON.stringify(await reportStatus(positional[0], work), null, 2));
    return;
  }
  if (action === 'check') {
    if (positional.length < 2 || positional.length > 4) throw new Error(usage);
    const checkLocale = positional[1];
    if (!LOCALES[checkLocale]) throw new Error(usage);
    const checkWork = resolve(positional[3] ?? defaultWork);
    const defaultInput = join(checkWork, 'event-' + Number(positional[0]), 'translation',
      checkLocale, 'responses');
    const report = await checkResponses(
      positional[0], checkLocale, resolve(positional[2] ?? defaultInput), checkWork);
    console.log(JSON.stringify(report, null, 2));
    if (!report.ok) process.exitCode = 1;
    return;
  }
  const locale = positional[1];
  if (!LOCALES[locale]) throw new Error(usage);

  if (action === 'export') {
    if (positional.length > 3) throw new Error(usage);
    const work = resolve(positional[2] ?? defaultWork);
    console.log(JSON.stringify(await exportBatches(positional[0], locale, work, flags), null, 2));
    return;
  }
  if (action === 'apply') {
    if (positional.length < 3 || positional.length > 4) throw new Error(usage);
    const work = resolve(positional[3] ?? defaultWork);
    const report = await applyResponses(positional[0], locale, resolve(positional[2]), work, flags);
    console.log(JSON.stringify(report, null, 2));
    return;
  }
  if (action === 'status') {
    if (positional.length < 1 || positional.length > 2) throw new Error(usage);
    const work = resolve(positional[1] ?? defaultWork);
    console.log(JSON.stringify(await reportStatus(positional[0], work), null, 2));
    return;
  }
  throw new Error(usage);
}

main(process.argv.slice(2)).catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
