#!/usr/bin/env node
// Rejects staged story content. The repository keeps the story tooling, but never
// the scripts, translations, ledgers, SQLite packs, or CDN chapter releases.
//
// Usage:
//   node scripts/check-story-content.mjs                 # staged files (pre-commit)
//   node scripts/check-story-content.mjs --files <path>  # explicit test paths

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const PATH_RULES = [
  { pattern: /^story_builder\/work\//, reason: 'story work directory (TSV, DB, inventory, ledgers)' },
  { pattern: /^release\/cdn\/story\//, reason: 'story CDN release' },
  { pattern: /^story_assets\//, reason: 'local Atlas/story asset directory' },
  { pattern: /(^|\/)atlas-scripts\//, reason: 'downloaded Atlas raw scripts' },
  { pattern: /(^|\/)event-[^/]+\.tsv$/, reason: 'translated story TSV' },
  { pattern: /(^|\/)event-[^/]+\.db$/, reason: 'chapter SQLite database' },
  { pattern: /(^|\/)story\.db$/, reason: 'chapter SQLite database' },
  { pattern: /(^|\/)source-inventory\.json$/, reason: 'story source inventory' },
  { pattern: /(^|\/)translation\/(cn|tw|en)\//, reason: 'story translation ledger' },
];

const TEXT_EXTENSION = /\.(?:tsv|json|txt|md)$/i;
const SCRIPT_START = /^＠/gm;
const SCRIPT_END = /^\[k\]$/gm;
const TSV_HEADER = 'jp_name\tjp_dialog\tcn_name';
const JSON_KEYS = /"jp_dialog"[\s\S]{0,200}"cn_dialog"/;

function stagedFiles() {
  const output = execFileSync(
    'git',
    ['diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z'],
    { encoding: 'utf8' }
  );
  return output.split('\0').filter(Boolean);
}

function stagedText(path) {
  try {
    return execFileSync('git', ['show', `:${path}`], {
      encoding: 'utf8',
      maxBuffer: 32 * 1024 * 1024,
    });
  } catch {
    return null;
  }
}

function pushedFiles() {
  let input = '';
  try {
    input = readFileSync(0, 'utf8');
  } catch {
    return [];
  }
  const paths = new Set();
  for (const line of input.split('\n')) {
    const fields = line.trim().split(/\s+/);
    if (fields.length < 4) continue;
    const localSha = fields[1];
    if (/^0+$/.test(localSha)) continue;
    // Only commits that no remote ref already reaches. History that is already
    // public on another branch (main) cannot leak anything new, and re-scanning
    // it made every push of such a branch fail on an old, already-deleted path.
    const listArgs = ['rev-list', localSha, '--not', '--remotes'];
    let commits = [localSha];
    try {
      commits = execFileSync('git', listArgs, { encoding: 'utf8' }).split('\n').filter(Boolean);
    } catch {
      commits = [localSha];
    }
    for (const commit of commits) {
      try {
        const names = execFileSync(
          'git',
          ['diff-tree', '--no-commit-id', '--name-only', '-r', commit],
          { encoding: 'utf8' }
        );
        for (const name of names.split('\n')) if (name) paths.add(name);
      } catch {
        // A commit may vanish during a force push; the remaining range is enough.
      }
    }
  }
  return [...paths];
}

function looksLikeScript(text) {
  const normalized = text.replace(/^\uFEFF/, '').replaceAll('\r\n', '\n').replaceAll('\r', '\n');
  const starts = [...normalized.matchAll(SCRIPT_START)].length;
  const ends = [...normalized.matchAll(SCRIPT_END)].length;
  return starts >= 3 && ends >= 3;
}

function looksLikeStoryTable(text) {
  const normalized = text.replace(/^\uFEFF/, '');
  if (normalized.startsWith(TSV_HEADER)) return true;
  if (JSON_KEYS.test(normalized)) return true;
  return looksLikeScript(normalized);
}

function looksLikeStoryDatabase(path) {
  if (!/\.db$/i.test(path)) return false;
  try {
    const prefix = execFileSync('git', ['show', `:${path}`], {
      encoding: 'latin1',
      maxBuffer: 64 * 1024 * 1024,
    });
    return prefix.startsWith('SQLite format 3') && /jp_dialog/.test(prefix);
  } catch {
    return false;
  }
}

const args = process.argv.slice(2);
const pushMode = args[0] === '--push';
const explicit = args[0] === '--files' ? args.slice(1) : null;
const files = pushMode ? pushedFiles() : (explicit ?? stagedFiles());
const violations = [];

for (const path of files) {
  const normalized = path.replaceAll('\\', '/');
  // Test sources use small synthetic script fixtures; they are versioned code,
  // not story data.
  if (/(^|\/)src\/test\//.test(normalized)) continue;
  const rule = PATH_RULES.find(({ pattern }) => pattern.test(normalized));
  if (rule) {
    violations.push(`${path} — ${rule.reason}`);
    continue;
  }
  if (!pushMode && looksLikeStoryDatabase(path)) {
    violations.push(`${path} — SQLite database with story tables`);
    continue;
  }
  if (!pushMode && TEXT_EXTENSION.test(path)) {
    const text = explicit ? safeRead(path) : stagedText(path);
    if (text && looksLikeStoryTable(text)) {
      violations.push(`${path} — story script, TSV, inventory, or ledger content`);
    }
  }
}

if (violations.length === 0) process.exit(0);

console.error('Story content must not be committed to this repository:');
for (const violation of violations) console.error(`  - ${violation}`);
console.error('');
console.error('Keep it under story_builder/work/ or release/cdn/story/ (both ignored),');
console.error('or unstage it with:  git restore --staged <path>');
console.error('If it was already pushed, stop and rewrite the remote history before continuing.');
process.exit(1);

function safeRead(path) {
  try {
    return readFileSync(path, 'utf8');
  } catch {
    return null;
  }
}
