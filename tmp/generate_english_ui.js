const fs = require('fs');
const rows = JSON.parse(fs.readFileSync('tmp/ui_english_todo.json', 'utf8'));
const translations = require('./english_translations.js');
const sorted = rows.slice().sort((a, b) => a.Literal.localeCompare(b.Literal));
if (sorted.length !== translations.length) {
  throw new Error(`length mismatch: ${sorted.length} vs ${translations.length}`);
}
function innerLiteral(literal) {
  if (literal.startsWith('"') && literal.endsWith('"')) return literal.slice(1, -1);
  return literal;
}
function kotlinString(value) {
  return '"' + value
    .replace(/\r\n/g, '\\n')
    .replace(/\r/g, '\\n')
    .replace(/\n/g, '\\n')
    .replace(/"/g, '\\"')
    .replace(/\$/g, '\\$') + '"';
}
const mapLines = sorted.map((row, index) => {
  const key = innerLiteral(row.Literal);
  const value = translations[index];
  return '        ' + kotlinString(key) + ' to ' + kotlinString(value);
});
const lines = [
  'package com.fgogotran.localization',
  '',
  '/**',
  ' * English translations for legacy UI strings that are still hard-coded in Kotlin.',
  ' *',
  ' * This is a migration bridge for the English UI phase. New UI should use Android string',
  ' * resources; keep this map limited to user-visible UI text and remove entries as screens',
  ' * are migrated to values-en.',
  ' */',
  'internal object EnglishUiText {',
  '    private val exact = mapOf(',
  mapLines.join(',\n'),
  '    )',
  '',
  '    private val patterns: List<Pair<Regex, (MatchResult) -> String>> = listOf(',
  '        Regex("""^已清除 (\\d+) 条缓存$""") to { m -> "Cleared " + m.groupValues[1] + " cache entries" },',
  '        Regex("""^(\\d+) 幕$""") to { m -> m.groupValues[1] + " scenes" },',
  '        Regex("""^(.+) 秒$""") to { m -> m.groupValues[1] + " s" },',
  '        Regex("""^发布日期：(.+)$""") to { m -> "Release date: " + m.groupValues[1] },',
  '        Regex("""^当前版本：(.+)$""") to { m -> "Current version: " + m.groupValues[1] },',
  '        Regex("""^最新版本：(.+)$""") to { m -> "Latest version: " + m.groupValues[1] },',
  '        Regex("""^状态：(.+)\\n用时：(.+)\\n结果：([\\s\\S]+)$""") to { m ->',
  '            "Status: " + m.groupValues[1] + "\\nTime: " + m.groupValues[2] + "\\nResult: " + m.groupValues[3]',
  '        },',
  '        Regex("""^测试失败：(.+)$""") to { m -> "Test failed: " + m.groupValues[1] },',
  '        Regex("""^测试语音已播放(.+)$""") to { m -> "Test voice played" + m.groupValues[1] },',
  '        Regex("""^服务商返回错误：(.+)$""") to { m -> "Provider error: " + m.groupValues[1] },',
  '        Regex("""^模型或请求参数不被服务商接受：(.+)$""") to { m -> "Provider rejected the model or request parameters: " + m.groupValues[1] },',
  '        Regex("""^额度或频率限制异常：(.+)$""") to { m -> "Quota or rate limit issue: " + m.groupValues[1] },',
  '        Regex("""^Azure 语音请求失败：(.+)$""") to { m -> "Azure voice request failed: " + m.groupValues[1] },',
  '        Regex("""^可用于 FgoGotran 翻译；接口不接受 (.+)，已自动忽略$""") to { m -> "Works with FgoGotran translation; unsupported parameters ignored: " + m.groupValues[1] },',
  '        Regex("""^自动：Temperature (.+)；不发送 Top-p。$""") to { m -> "Auto: Temperature " + m.groupValues[1] + "; no Top-p." }',
  '    )',
  '',
  '    fun translate(text: String): String? {',
  '        exact[text]?.let { return it }',
  '        patterns.forEach { (regex, transform) ->',
  '            val match = regex.matchEntire(text) ?: return@forEach',
  '            return transform(match)',
  '        }',
  '        return null',
  '    }',
  '}',
  ''
];
fs.writeFileSync('app/src/main/java/com/fgogotran/localization/EnglishUiText.kt', lines.join('\n'), 'utf8');
console.log(`generated ${sorted.length} exact English UI entries`);
