const fs = require('fs');
const filePath = 'app/src/main/java/com/fgogotran/localization/EnglishUiText.kt';
let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
function replaceOnce(needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return text.replace(needle, replacement);
}
text = replaceOnce(
  '        exact[text]?.let { return it }\n        diagnostic[text]?.let { return it }\n        diagnosticDetails[text]?.let { return it }\n',
  '        exact[text]?.let { return it }\n        diagnostic[text]?.let { return it }\n        diagnosticDetails[text]?.let { return it }\n        if (text.endsWith("：")) {\n            val prefix = text.dropLast(1)\n            val translatedPrefix = exact[prefix] ?: diagnostic[prefix] ?: diagnosticDetails[prefix]\n            return (translatedPrefix ?: prefix) + ":"\n        }\n',
  'add trailing full-width colon fallback'
);
fs.writeFileSync(filePath, text, 'utf8');
console.log('colon fallback applied');
