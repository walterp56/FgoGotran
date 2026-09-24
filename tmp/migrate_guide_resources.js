const fs = require('fs');
const filePath = 'app/src/main/java/com/fgogotran/ui/screen/GuideScreen.kt';
let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
const rows = JSON.parse(fs.readFileSync('tmp/ui_english_todo.json', 'utf8'));
const translations = require('./english_translations.js');
const sortedRows = rows.slice().sort((a, b) => a.Literal.localeCompare(b.Literal));
const translationMap = new Map();
sortedRows.forEach((row, index) => {
  const key = row.Literal.startsWith('"') && row.Literal.endsWith('"') ? row.Literal.slice(1, -1) : row.Literal;
  translationMap.set(key, translations[index]);
});
const overrides = new Map([
  ['战', 'B'],
  ['BATTLE 字幕模式：仅翻译战斗字幕区域；长按悬浮按钮打开菜单后选择。', 'BATTLE subtitle mode: translates only battle subtitles. Long-press the floating button and choose it from the menu.'],
  ['文字送り / 表示速度', 'Text speed: 文字送り / 表示速度'],
  ['ページ送り / 表示速度', 'Page turn: ページ送り / 表示速度'],
  ['句読点待ち時間 / 标点等待时间', 'Punctuation wait: 句読点待ち時間'],
  ['设置位置', 'Location'],
  ['マイルーム → ゲームオプション → テキスト表示速度\n我的房间 → 游戏选项 → 文本显示速度', 'My Room → Game Options → Text Display Speed\nマイルーム → ゲームオプション → テキスト表示速度']
]);
const matches = [...text.matchAll(/"([^"\r\n]*[\p{Script=Han}][^"\r\n]*)"/gu)].map(m => m[1]);
const unique = [...new Set(matches)].sort((a, b) => b.length - a.length);
const entries = [];
const missing = [];
for (const source of unique) {
  const english = overrides.get(source) ?? translationMap.get(source);
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
  const key = `guide_auto_${index + 1}`;
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
  text = text.replace('import androidx.compose.runtime.Composable\n', 'import androidx.compose.runtime.Composable\nimport androidx.compose.ui.res.stringResource\n');
}
if (!text.includes('import com.fgogotran.R')) {
  text = text.replace('import com.fgogotran.data.SettingsRepository\n', 'import com.fgogotran.R\nimport com.fgogotran.data.SettingsRepository\n');
}
fs.writeFileSync(filePath, text, 'utf8');
console.log(`guide resources added: ${entries.length}`);
