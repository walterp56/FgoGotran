const fs = require('fs');
const resources = {
  'app/src/main/res/values/strings.xml': [
    ['guide_mode_battle_label', '战斗字幕'],
    ['guide_mode_battle_text', '适合不想错过战斗内剧情的你。只锁定战斗字幕区域']
  ],
  'app/src/main/res/values-b+zh+Hant/strings.xml': [
    ['guide_mode_battle_label', '戰鬥字幕'],
    ['guide_mode_battle_text', '適合不想錯過戰鬥內劇情的你。只鎖定戰鬥字幕區域']
  ],
  'app/src/main/res/values-en/strings.xml': [
    ['guide_mode_battle_label', 'Battle'],
    ['guide_mode_battle_text', "For players who don't want to miss in-battle story. Locks onto the battle subtitle area."]
  ]
};
function escapeXml(value) {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '\\"').replace(/'/g, "\\'");
}
for (const [path, entries] of Object.entries(resources)) {
  let text = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
  const block = entries.map(([key, value]) => `    <string name="${key}">${escapeXml(value)}</string>`).join('\n');
  text = text.replace('</resources>', block + '\n</resources>');
  fs.writeFileSync(path, text, 'utf8');
}
let guide = fs.readFileSync('app/src/main/java/com/fgogotran/ui/screen/GuideScreen.kt', 'utf8').replace(/\r\n/g, '\n');
const anchor = `                GuideModeRow(
                    mode = stringResource(R.string.guide_auto_36),
                    text = stringResource(R.string.guide_auto_11)
                )
`;
const replacement = anchor + `                GuideModeRow(
                    mode = stringResource(R.string.guide_mode_battle_label),
                    text = stringResource(R.string.guide_mode_battle_text)
                )
`;
const count = guide.split(anchor).length - 1;
if (count !== 1) throw new Error(`Guide anchor expected 1, found ${count}`);
guide = guide.replace(anchor, replacement);
fs.writeFileSync('app/src/main/java/com/fgogotran/ui/screen/GuideScreen.kt', guide, 'utf8');
console.log('battle guide mode added');
