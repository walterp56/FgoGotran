const fs = require('fs');
const path = 'app/src/main/java/com/fgogotran/localization/EnglishUiText.kt';
let text = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
function replaceOnce(needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  text = text.replace(needle, replacement);
}
const entries = [
  ['无障碍服务不可用', 'Accessibility service unavailable'],
  ['未知截屏失败。请确认游戏画面没有黑屏，FgoGotran 无障碍服务仍开启，并尝试重启游戏或模拟器。', 'Unknown screenshot failure. Make sure the game screen is not black, the FgoGotran accessibility service is still on, and try restarting the game or emulator.'],
  ['关闭无障碍服务，等待 2–3 秒后重新开启，然后回到 FGO 重试', 'Turn off the accessibility service, wait 2–3 seconds, turn it back on, then return to FGO and try again.'],
  ['多见于模拟器、投屏、黑屏、省电锁屏或显示状态切换。请确认游戏画面实际显示在手机/模拟器主屏上。', 'Often caused by emulators, screen casting, black screens, battery saver lock screens, or display-state changes. Make sure the game is actually visible on the phone or emulator main screen.'],
  ['多见于模拟器、系统图形层或当前画面状态异常。可尝试重启游戏、重启模拟器，或切换模拟器图形渲染模式。', 'Often caused by emulator or system graphics issues or an abnormal screen state. Try restarting the game or emulator, or switching the emulator graphics renderer.'],
  ['多见于模拟器图形兼容问题。可尝试切换 DirectX/OpenGL/Vulkan 渲染模式、更新模拟器，或改用 64 位实例。', 'Often caused by emulator graphics compatibility. Try switching DirectX/OpenGL/Vulkan rendering, updating the emulator, or using a 64-bit instance.'],
  ['多见于模拟器图形渲染异常：请完整重启模拟器，或切换图形渲染模式后再试', 'Often caused by emulator graphics rendering issues. Fully restart the emulator or switch the graphics renderer and try again.'],
  ['系统标记了安全画面，应用无法读取截图。请避开受保护页面后再试。', 'The system marked this as a secure screen, so the app cannot read screenshots. Leave protected screens and try again.'],
  ['请关闭无障碍服务，等待 2–3 秒后重新开启；刚切换模拟器图形模式时请完整重启模拟器', 'Turn off the accessibility service, wait 2–3 seconds, and turn it back on; after switching emulator graphics mode, fully restart the emulator.'],
  ['请关闭并重新开启 FgoGotran 无障碍服务；若系统限制无障碍权限，请在系统设置中允许。', 'Turn the FgoGotran accessibility service off and on again; if the system restricts accessibility, allow it in system settings.'],
  ['请关闭并重新开启 FgoGotran 无障碍服务；模拟器切换图形模式后需完整重启模拟器', 'Turn the FgoGotran accessibility service off and on again; after switching emulator graphics mode, fully restart the emulator.'],
  ['请等待约 1 秒后再试；半自动/全自动模式会稍后重试。', 'Wait about 1 second and try again; semi-auto and auto modes will retry later.'],
  ['截屏请求超时，通常是模拟器图形层或系统繁忙。稍后会自动重试；若持续出现可重启游戏或切换模拟器渲染模式。', 'Screenshot request timed out, usually because the emulator graphics layer or system is busy. It will retry automatically; if it keeps happening, restart the game or switch the emulator renderer.'],
  ['Android 无法读取当前窗口画面。请回到 FGO 主画面后再试，或重启游戏/模拟器。', 'Android cannot read the current window. Return to the FGO main screen and try again, or restart the game/emulator.']
];
function kotlinString(value) {
  return '"' + value.replace(/"/g, '\\"').replace(/\$/g, '\\$') + '"';
}
const lines = entries.map(([k, v]) => '        ' + kotlinString(k) + ' to ' + kotlinString(v)).join(',\n');
replaceOnce(
  '    private val patterns: List<Pair<Regex, (MatchResult) -> String>> = listOf(',
  lines + ',\n    private val patterns: List<Pair<Regex, (MatchResult) -> String>> = listOf(',
  'insert detail entries'
);
replaceOnce(
  '        Regex("""^Android/模拟器没有返回截图：(.+)$""") to { m -> "Android/emulator did not return a screenshot: " + m.groupValues[1] }\n',
  '        Regex("""^Android/模拟器没有返回截图：(.+)$""") to { m -> "Android/emulator did not return a screenshot: " + m.groupValues[1] },\n' +
  '        Regex("""^远端版本：(.+)，本地版本：(.+)$""") to { m -> "Remote version: " + m.groupValues[1] + ", local version: " + m.groupValues[2] }\n',
  'add remote version pattern'
);
fs.writeFileSync(path, text, 'utf8');
console.log('detail English entries added');
