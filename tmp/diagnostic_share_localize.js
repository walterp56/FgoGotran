const fs = require('fs');
const filePath = 'app/src/main/java/com/fgogotran/ui/screen/DiagnosticLogScreen.kt';
let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
function replaceOnce(needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return text.replace(needle, replacement);
}
text = replaceOnce(
  'import com.fgogotran.diagnostic.DiagnosticEventStore\n',
  'import com.fgogotran.diagnostic.DiagnosticEventStore\nimport com.fgogotran.localization.AppLanguageManager\n',
  'import AppLanguageManager'
);
text = replaceOnce(
  'putExtra(Intent.EXTRA_SUBJECT, "FgoGotran 错误纪录")',
  'putExtra(Intent.EXTRA_SUBJECT, AppLanguageManager.localizeUiText(context, "FgoGotran 错误纪录"))',
  'localize share subject'
);
text = replaceOnce(
  'context.startActivity(Intent.createChooser(shareIntent, "分享错误纪录"))',
  'context.startActivity(Intent.createChooser(shareIntent, AppLanguageManager.localizeUiText(context, "分享错误纪录")))',
  'localize chooser title'
);
fs.writeFileSync(filePath, text, 'utf8');
console.log('diagnostic share strings localized');
