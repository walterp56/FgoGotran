const fs = require('fs');
const files = {
  'app/src/main/res/values/strings.xml': [
    ['settings_clear_cache_result', '已清除 %1$d 条缓存'],
    ['settings_target_simplified', '简体中文'],
    ['settings_target_traditional', '繁體中文'],
    ['settings_gender_male', '男'],
    ['settings_gender_female', '女'],
    ['settings_context_scenes', '%1$d 幕'],
    ['voice_read_chinese', '中文']
  ],
  'app/src/main/res/values-b+zh+Hant/strings.xml': [
    ['settings_clear_cache_result', '已清除 %1$d 條快取'],
    ['settings_target_simplified', '簡體中文'],
    ['settings_target_traditional', '繁體中文'],
    ['settings_gender_male', '男'],
    ['settings_gender_female', '女'],
    ['settings_context_scenes', '%1$d 幕'],
    ['voice_read_chinese', '中文']
  ],
  'app/src/main/res/values-en/strings.xml': [
    ['settings_clear_cache_result', 'Cleared %1$d cache entries'],
    ['settings_target_simplified', 'Simplified Chinese'],
    ['settings_target_traditional', 'Traditional Chinese'],
    ['settings_gender_male', 'Male'],
    ['settings_gender_female', 'Female'],
    ['settings_context_scenes', '%1$d scenes'],
    ['voice_read_chinese', 'Chinese']
  ]
};
function xmlEscape(value) { return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '\\"').replace(/'/g, "\\'"); }
for (const [filePath, entries] of Object.entries(files)) {
  let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
  const block = entries.map(([key, value]) => `    <string name="${key}">${xmlEscape(value)}</string>`).join('\n');
  text = text.replace('</resources>', block + '\n</resources>');
  fs.writeFileSync(filePath, text, 'utf8');
}
console.log('final UI strings added');
