const fs = require('fs');
const path = 'app/src/main/java/com/fgogotran/localization/EnglishUiText.kt';
let text = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
function replaceOnce(needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  text = text.replace(needle, replacement);
}
replaceOnce(
  '    )\n\n        "无障碍服务不可用" to "Accessibility service unavailable",',
  '    )\n\n    private val diagnosticDetails = mapOf(\n        "无障碍服务不可用" to "Accessibility service unavailable",',
  'open diagnosticDetails map'
);
replaceOnce(
  '        "Android 无法读取当前窗口画面。请回到 FGO 主画面后再试，或重启游戏/模拟器。" to "Android cannot read the current window. Return to the FGO main screen and try again, or restart the game/emulator.",\n    private val patterns',
  '        "Android 无法读取当前窗口画面。请回到 FGO 主画面后再试，或重启游戏/模拟器。" to "Android cannot read the current window. Return to the FGO main screen and try again, or restart the game/emulator."\n    )\n\n    private val patterns',
  'close diagnosticDetails map'
);
replaceOnce(
  '        diagnostic[text]?.let { return it }\n',
  '        diagnostic[text]?.let { return it }\n        diagnosticDetails[text]?.let { return it }\n',
  'lookup diagnosticDetails'
);
fs.writeFileSync(path, text, 'utf8');
console.log('diagnostic details map fixed');
