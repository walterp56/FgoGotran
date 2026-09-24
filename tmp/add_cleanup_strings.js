const fs = require('fs');
const files = {
  'app/src/main/res/values/strings.xml': [
    ['ui_language_system', '系统默认'],
    ['ui_language_follow_system', '跟随系统设置'],
    ['ui_language_title', '语言'],
    ['ui_language_close', '关闭'],
    ['mode_desc_manual', '手動翻譯'],
    ['mode_desc_semi', '半自動翻譯'],
    ['mode_desc_auto', '全自動翻譯'],
    ['mode_desc_battle', 'BATTLE字幕模式，长按切换模式'],
    ['mode_desc_crop', '裁切翻譯']
  ],
  'app/src/main/res/values-b+zh+Hant/strings.xml': [
    ['ui_language_system', '系統預設'],
    ['ui_language_follow_system', '跟隨系統設定'],
    ['ui_language_title', '語言'],
    ['ui_language_close', '關閉'],
    ['mode_desc_manual', '手動翻譯'],
    ['mode_desc_semi', '半自動翻譯'],
    ['mode_desc_auto', '全自動翻譯'],
    ['mode_desc_battle', 'BATTLE字幕模式，長按切換模式'],
    ['mode_desc_crop', '裁切翻譯']
  ],
  'app/src/main/res/values-en/strings.xml': [
    ['ui_language_system', 'System default'],
    ['ui_language_follow_system', 'Follow system settings'],
    ['ui_language_title', 'Language'],
    ['ui_language_close', 'Close'],
    ['mode_desc_manual', 'Manual translation'],
    ['mode_desc_semi', 'Semi-auto translation'],
    ['mode_desc_auto', 'Full-auto translation'],
    ['mode_desc_battle', 'BATTLE subtitle mode. Long-press to switch modes.'],
    ['mode_desc_crop', 'Crop translation']
  ]
};
function xmlEscape(value) { return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '\\"').replace(/'/g, "\\'"); }
for (const [filePath, entries] of Object.entries(files)) {
  let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
  const block = entries.map(([key, value]) => `    <string name="${key}">${xmlEscape(value)}</string>`).join('\n');
  text = text.replace('</resources>', block + '\n</resources>');
  fs.writeFileSync(filePath, text, 'utf8');
}
console.log('cleanup strings added');
