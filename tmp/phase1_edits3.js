const fs = require('fs');
function read(path) { return fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n'); }
function write(path, text) { fs.writeFileSync(path, text, 'utf8'); }
function replaceOnce(text, needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return text.replace(needle, replacement);
}

{
  const path = 'app/src/main/java/com/fgogotran/ui/overlay/HistoryOverlayPanel.kt';
  let text = read(path);
  if (!text.includes('import com.fgogotran.localization.AppLanguageManager')) {
    text = replaceOnce(
      text,
      'import android.content.Context\n',
      'import android.content.Context\nimport com.fgogotran.localization.AppLanguageManager\n',
      'HistoryOverlayPanel AppLanguageManager import'
    );
  }
  text = replaceOnce(
    text,
    'addView(historyTextView(context, "暂无翻译LOG。", historyTypeface(context)).apply {',
    'addView(historyTextView(context, AppLanguageManager.localizeUiText(context, "暂无翻译LOG。"), historyTypeface(context)).apply {',
    'HistoryOverlayPanel empty state'
  );
  write(path, text);
}

{
  const path = 'app/src/main/java/com/fgogotran/runner/FgoRunnerService.kt';
  let text = read(path);
  if (!text.includes('import com.fgogotran.localization.AppLanguageManager')) {
    text = replaceOnce(
      text,
      'import com.fgogotran.diagnostic.DiagnosticEventStore\n',
      'import com.fgogotran.diagnostic.DiagnosticEventStore\nimport com.fgogotran.localization.AppLanguageManager\n',
      'FgoRunnerService AppLanguageManager import'
    );
  }
  text = replaceOnce(
    text,
    '.setContentText("翻译悬浮窗正在运行")',
    '.setContentText(AppLanguageManager.localizeUiText(this, "翻译悬浮窗正在运行"))',
    'FgoRunnerService notification text'
  );
  write(path, text);
}

console.log('phase1 edits3 applied');
