const fs = require('fs');
const path = 'app/src/main/java/com/fgogotran/localization/EnglishUiText.kt';
let text = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
function replaceOnce(needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  text = text.replace(needle, replacement);
}
const diagnosticEntries = [
  ['已建立临时语音档案', 'Temporary voice profile created'],
  ['区域翻译失败', 'Area translation failed'],
  ['无障碍手势注入不可用', 'Accessibility gesture injection unavailable'],
  ['无障碍服务已开启但未连接', 'Accessibility service enabled but not connected'],
  ['无障碍服务已连接', 'Accessibility service connected'],
  ['无障碍截屏权限失效', 'Accessibility screenshot permission lost'],
  ['处理流程失败', 'Processing failed'],
  ['未支持的 FGO 包名', 'Unsupported FGO package'],
  ['术语库更新失败', 'Glossary update failed'],
  ['自动侦测循环失败', 'Auto-detection loop failed'],
  ['找不到语音档案', 'Voice profile not found'],
  ['实时语音翻译不可用', 'Live voice translation unavailable'],
  ['临时语音 API 冷却中', 'Temporary voice API cooling down'],
  ['临时语音档案写入失败', 'Temporary voice profile write failed'],
  ['临时语音档案读取失败', 'Temporary voice profile read failed'],
  ['建立临时语音档案失败', 'Failed to create temporary voice profile'],
  ['语气增强请求失败', 'AI expression request failed'],
  ['语气增强请求超时', 'AI expression request timed out'],
  ['语音资料更新失败', 'Voice data update failed'],
  ['音讯播放无法开始', 'Audio playback cannot start'],
  ['音讯播放失败', 'Audio playback failed'],
  ['请求建立临时语音档案', 'Requesting temporary voice profile'],
  ['截图内容为空（全黑）', 'Screenshot is empty (all black)'],
  ['截屏失败', 'Screenshot failed'],
  ['翻译 API 失败', 'Translation API failed'],
  ['翻译结果悬浮层显示失败', 'Failed to show translation overlay'],
  ['API返回了不允许的语音模型', 'API returned a disallowed voice model'],
  ['API返回了不支持的 voice_type', 'API returned an unsupported voice_type'],
  ['API返回格式不正确', 'API response format is invalid'],
  ['Azure Speech Key 未设置', 'Azure Speech Key is not set'],
  ['OCR 识别失败', 'OCR recognition failed'],
  ['OCR 预载失败', 'OCR preload failed'],
  ['上次建立失败，暂时不重复请求', 'Last attempt failed; not retrying for now.'],
  ['已改用本机语气规则', 'Using local expression rules'],
  ['已是最新', 'Up to date'],
  ['已是最新版本', 'Up to date'],
  ['区域翻译覆盖层点击无法转发给 FGO', 'Area translation overlay taps cannot be forwarded to FGO'],
  ['无障碍截图返回了全黑画面，OCR 无法识别文字', 'Accessibility screenshot returned an all-black frame; OCR could not read text'],
  ['主语音表与临时语音表都未命中', 'No match in the main or temporary voice table'],
  ['正在下载术语库', 'Downloading glossary'],
  ['正在安装术语库', 'Installing glossary'],
  ['正在更新术语库', 'Updating glossary'],
  ['正在校验术语库', 'Verifying glossary'],
  ['正在检查版本', 'Checking for updates'],
  ['更新完成', 'Update complete'],
  ['没有可用 TSV 行', 'No usable TSV row'],
  ['系统仍标记服务为已启用，但 FgoGotran 没有收到连接', 'System still marks the service as enabled, but FgoGotran has not received a connection'],
  ['系统设置显示已开启，但 FgoGotran 没有收到连接', 'System settings show it as on, but FgoGotran has not received a connection'],
  ['服务仍显示已连接，但系统拒绝了截屏请求', 'Service still shows connected, but the system denied the screenshot request'],
  ['检测到疑似 FGO，但包名不在支持列表中', 'Suspected FGO detected, but the package is not in the supported list'],
  ['翻译覆盖层点击无法转发给 FGO', 'Translation overlay taps cannot be forwarded to FGO'],
  ['AI语音已开启，但 Azure Speech Key 为空', 'AI voice is on, but Azure Speech Key is empty'],
  ['Azure 会话已结束', 'Azure session ended']
];
function kotlinString(value) {
  return '"' + value.replace(/"/g, '\\"').replace(/\$/g, '\\$') + '"';
}
const diagnosticLines = diagnosticEntries.map(([k, v]) => '        ' + kotlinString(k) + ' to ' + kotlinString(v));
const diagnosticBlock = [
  '',
  '    private val diagnostic = mapOf(',
  diagnosticLines.join(',\n'),
  '    )',
  ''
].join('\n');
replaceOnce(
  '    private val patterns: List<Pair<Regex, (MatchResult) -> String>> = listOf(',
  diagnosticBlock + '\n    private val patterns: List<Pair<Regex, (MatchResult) -> String>> = listOf(',
  'insert diagnostic map'
);
replaceOnce(
  '        exact[text]?.let { return it }\n',
  '        exact[text]?.let { return it }\n        diagnostic[text]?.let { return it }\n',
  'use diagnostic map'
);
replaceOnce(
  '        Regex("""^自动：Temperature (.+)；不发送 Top-p。$""") to { m -> "Auto: Temperature " + m.groupValues[1] + "; no Top-p." }\n',
  '        Regex("""^自动：Temperature (.+)；不发送 Top-p。$""") to { m -> "Auto: Temperature " + m.groupValues[1] + "; no Top-p." },\n' +
  '        Regex("""^发现新版本 (.+)$""") to { m -> "Update available " + m.groupValues[1] },\n' +
  '        Regex("""^当前版本 (.+)$""") to { m -> "Current version " + m.groupValues[1] },\n' +
  '        Regex("""^截图能力=(.+)，屏幕=(.+)x(.+)$""") to { m -> "Screenshot capability=" + m.groupValues[1] + ", screen=" + m.groupValues[2] + "x" + m.groupValues[3] },\n' +
  '        Regex("""^Android/模拟器没有返回截图：(.+)$""") to { m -> "Android/emulator did not return a screenshot: " + m.groupValues[1] }\n',
  'add diagnostic patterns'
);
fs.writeFileSync(path, text, 'utf8');
console.log('diagnostic English entries added');
