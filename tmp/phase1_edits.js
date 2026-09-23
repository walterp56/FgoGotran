const fs = require('fs');

function read(path) {
  return fs.readFileSync(path, 'utf8');
}

function write(path, text) {
  fs.writeFileSync(path, text, 'utf8');
}

function replaceOnce(text, needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) {
    throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  }
  return text.replace(needle, replacement);
}

function replaceAll(text, needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count < 1) {
    throw new Error(`${label}: expected at least 1 occurrence, found ${count}`);
  }
  return text.split(needle).join(replacement);
}

// MainActivity: apply explicit UI locale before Compose is created.
{
  const path = 'app/src/main/java/com/fgogotran/MainActivity.kt';
  let text = read(path);
  text = replaceOnce(
    text,
    'import android.os.Bundle\n',
    'import android.content.Context\nimport android.os.Bundle\n',
    'MainActivity Context import'
  );
  text = replaceOnce(
    text,
    'import com.fgogotran.diagnostic.DiagnosticEventStore\n',
    'import com.fgogotran.diagnostic.DiagnosticEventStore\nimport com.fgogotran.localization.AppLanguageManager\n',
    'MainActivity AppLanguageManager import'
  );
  text = replaceOnce(
    text,
    '    override fun onCreate(savedInstanceState: Bundle?) {\n',
    '    override fun attachBaseContext(newBase: Context) {\n        super.attachBaseContext(AppLanguageManager.wrap(newBase))\n    }\n\n    override fun onCreate(savedInstanceState: Bundle?) {\n',
    'MainActivity attachBaseContext'
  );
  write(path, text);
}

// Manifest: use the app label resource and declare supported app locales.
{
  const path = 'app/src/main/AndroidManifest.xml';
  let text = read(path);
  text = replaceOnce(
    text,
    '        android:label="FgoGotran"\n',
    '        android:label="@string/app_name"\n        android:localeConfig="@xml/locales_config"\n',
    'Manifest label/localeConfig'
  );
  write(path, text);
}

// Compose UI files: route Text through LocalizedText while legacy strings are migrated.
{
  const files = [
    'app/src/main/java/com/fgogotran/ui/component/AppUpdateDialog.kt',
    'app/src/main/java/com/fgogotran/ui/component/BackendProviderLabel.kt',
    'app/src/main/java/com/fgogotran/ui/overlay/FloatingArcMenu.kt',
    'app/src/main/java/com/fgogotran/ui/overlay/FloatingButton.kt',
    'app/src/main/java/com/fgogotran/ui/overlay/FloatingMenu.kt',
    'app/src/main/java/com/fgogotran/ui/screen/DiagnosticLogScreen.kt',
    'app/src/main/java/com/fgogotran/ui/screen/GuideScreen.kt',
    'app/src/main/java/com/fgogotran/ui/screen/SettingsScreen.kt',
    'app/src/main/java/com/fgogotran/ui/screen/VoiceSettingsScreen.kt'
  ];
  for (const path of files) {
    let text = read(path);
    text = replaceOnce(
      text,
      'import androidx.compose.material3.Text\n',
      'import com.fgogotran.localization.LocalizedText as Text\n',
      `${path} Text import`
    );
    write(path, text);
  }
}

// Wildcard Material3 imports need an explicit alias import to win over the star import.
{
  for (const path of [
    'app/src/main/java/com/fgogotran/ui/screen/HomeScreen.kt',
    'app/src/main/java/com/fgogotran/ui/screen/ApiSettingsScreen.kt'
  ]) {
    let text = read(path);
    text = replaceOnce(
      text,
      'import androidx.compose.material3.*\n',
      'import androidx.compose.material3.*\nimport com.fgogotran.localization.LocalizedText as Text\n',
      `${path} wildcard Text import`
    );
    write(path, text);
  }
}

// HistoryOverlayPanel is the only View-based Compose-less UI string in the history panel.
{
  const path = 'app/src/main/java/com/fgogotran/ui/overlay/HistoryOverlayPanel.kt';
  let text = read(path);
  text = replaceOnce(
    text,
    'import android.os.Bundle\n',
    'import android.os.Bundle\nimport com.fgogotran.localization.AppLanguageManager\n',
    'HistoryOverlayPanel AppLanguageManager import'
  );
  text = replaceOnce(
    text,
    'addView(historyTextView(context, "暂无翻译LOG。", historyTypeface(context)).apply {',
    'addView(historyTextView(context, AppLanguageManager.localizeUiText(context, "暂无翻译LOG。"), historyTypeface(context)).apply {',
    'HistoryOverlayPanel empty state'
  );
  write(path, text);
}

// Service notification text also follows the selected app UI language.
{
  const path = 'app/src/main/java/com/fgogotran/runner/FgoRunnerService.kt';
  let text = read(path);
  text = replaceOnce(
    text,
    'import com.fgogotran.diagnostic.DiagnosticEventStore\n',
    'import com.fgogotran.diagnostic.DiagnosticEventStore\nimport com.fgogotran.localization.AppLanguageManager\n',
    'FgoRunnerService AppLanguageManager import'
  );
  text = replaceOnce(
    text,
    '.setContentText("翻译悬浮窗正在运行")',
    '.setContentText(AppLanguageManager.localizeUiText(this, "翻译悬浮窗正在运行"))',
    'FgoRunnerService notification text'
  );
  write(path, text);
}

console.log('phase1 edits applied');
