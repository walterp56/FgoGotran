const fs = require('fs');
const filePath = 'app/src/main/java/com/fgogotran/ui/screen/SettingsScreen.kt';
let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
const rows = JSON.parse(fs.readFileSync('tmp/ui_english_todo.json', 'utf8'));
const translations = require('./english_translations.js');
const sortedRows = rows.slice().sort((a, b) => a.Literal.localeCompare(b.Literal));
const translationMap = new Map();
sortedRows.forEach((row, index) => {
  const key = row.Literal.startsWith('"') && row.Literal.endsWith('"') ? row.Literal.slice(1, -1) : row.Literal;
  translationMap.set(key, translations[index]);
});
const skip = new Set(['简体中文', '繁體中文', '男', '女']);
const matches = [...text.matchAll(/"([^"\r\n]*[\p{Script=Han}][^"\r\n]*)"/gu)].map(m => m[1]);
const unique = [...new Set(matches)].sort((a, b) => b.length - a.length).filter(s => !s.includes('$') && !skip.has(s));
const entries = [];
const missing = [];
for (const source of unique) {
  const english = translationMap.get(source);
  if (!english) { missing.push(source); continue; }
  entries.push({ source, english });
}
if (missing.length) throw new Error('missing English translations:\n' + missing.join('\n'));
function xmlEscape(value) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '\\"')
    .replace(/'/g, "\\'");
}
const resourceBlocks = {
  'app/src/main/res/values/strings.xml': [],
  'app/src/main/res/values-b+zh+Hant/strings.xml': [],
  'app/src/main/res/values-en/strings.xml': []
};
entries.forEach((entry, index) => {
  const key = `settings_auto_${index + 1}`;
  entry.key = key;
  resourceBlocks['app/src/main/res/values/strings.xml'].push(`    <string name="${key}">${xmlEscape(entry.source)}</string>`);
  resourceBlocks['app/src/main/res/values-b+zh+Hant/strings.xml'].push(`    <string name="${key}">${xmlEscape(entry.source)}</string>`);
  resourceBlocks['app/src/main/res/values-en/strings.xml'].push(`    <string name="${key}">${xmlEscape(entry.english)}</string>`);
});
for (const [path, lines] of Object.entries(resourceBlocks)) {
  let resourceText = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
  resourceText = resourceText.replace('</resources>', lines.join('\n') + '\n</resources>');
  fs.writeFileSync(path, resourceText, 'utf8');
}
for (const entry of entries) {
  text = text.split(`"${entry.source}"`).join(`stringResource(R.string.${entry.key})`);
}
if (!text.includes('import androidx.compose.ui.res.stringResource')) {
  text = text.replace('import androidx.compose.ui.platform.LocalContext\n', 'import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.res.stringResource\n');
}
fs.writeFileSync(filePath, text, 'utf8');
console.log(`settings resources added: ${entries.length}`);
