const fs = require('fs');
const filePath = 'app/src/main/java/com/fgogotran/ui/screen/DiagnosticLogScreen.kt';
let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
function replaceOnce(needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return text.replace(needle, replacement);
}
text = replaceOnce(
  '                    Text(\n                        event.metaLine(),',
  '                    Text(\n                        event.metaLine(context),',
  'metaLine context'
);
text = replaceOnce(
  '            event.bodyLine()?.let { line ->',
  '            event.bodyLine(context)?.let { line ->',
  'bodyLine context'
);
text = replaceOnce(
  '            event.detailLine()?.let { line ->',
  '            event.detailLine(context)?.let { line ->',
  'detailLine context'
);
text = replaceOnce(
  'private fun DiagnosticEvent.metaLine(): String {\n    return listOfNotNull(\n        formatEventTime(timestampMs),\n        server.takeIf { it.isNotBlank() },\n        mode.takeIf { it.isNotBlank() },\n        speaker.takeIf { it.isNotBlank() }\n    ).joinToString(" · ")\n}\n',
  'private fun DiagnosticEvent.metaLine(context: android.content.Context): String {\n    return listOfNotNull(\n        formatEventTime(timestampMs),\n        server.takeIf { it.isNotBlank() }\n            ?.let { AppLanguageManager.localizeUiText(context, it) },\n        mode.takeIf { it.isNotBlank() }\n            ?.let { AppLanguageManager.localizeUiText(context, it) },\n        speaker.takeIf { it.isNotBlank() }\n    ).joinToString(" · ")\n}\n',
  'metaLine implementation'
);
text = replaceOnce(
  'private fun DiagnosticEvent.bodyLine(): String? {\n    return listOfNotNull(\n        message.takeIf { it.isNotBlank() },\n',
  'private fun DiagnosticEvent.bodyLine(context: android.content.Context): String? {\n    return listOfNotNull(\n        message.takeIf { it.isNotBlank() }\n            ?.let { AppLanguageManager.localizeUiText(context, it) },\n',
  'bodyLine implementation'
);
text = replaceOnce(
  'private fun DiagnosticEvent.detailLine(): String? {\n    return listOfNotNull(\n        detail.takeIf { it.isNotBlank() },\n',
  'private fun DiagnosticEvent.detailLine(context: android.content.Context): String? {\n    return listOfNotNull(\n        detail.takeIf { it.isNotBlank() }\n            ?.let { AppLanguageManager.localizeUiText(context, it) },\n',
  'detailLine implementation'
);
fs.writeFileSync(filePath, text, 'utf8');
console.log('diagnostic fields localized before joining');
