const fs = require('fs');
function read(path) { return fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n'); }
function write(path, text) { fs.writeFileSync(path, text, 'utf8'); }
function replaceOnce(text, needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return text.replace(needle, replacement);
}

{
  const path = 'app/src/main/java/com/fgogotran/ui/screen/HomeScreen.kt';
  let text = read(path);

  text = replaceOnce(
    text,
    'import androidx.compose.ui.platform.LocalLifecycleOwner\n',
    'import androidx.compose.ui.platform.LocalLifecycleOwner\nimport androidx.compose.ui.res.painterResource\n',
    'HomeScreen painterResource import'
  );
  text = replaceOnce(
    text,
    'import com.fgogotran.localization.LocalizedText as Text\n',
    'import com.fgogotran.localization.AppLanguageManager\nimport com.fgogotran.localization.LocalizedText as Text\n',
    'HomeScreen AppLanguageManager import'
  );
  text = replaceOnce(
    text,
    'import com.fgogotran.runner.FgoRunnerService\n',
    'import com.fgogotran.runner.FgoRunnerService\nimport com.fgogotran.ui.component.LanguagePickerDialog\n',
    'HomeScreen LanguagePickerDialog import'
  );
  text = replaceOnce(
    text,
    '    var showServerDialog by remember { mutableStateOf(false) }\n',
    '    var showServerDialog by remember { mutableStateOf(false) }\n    var showLanguageDialog by remember { mutableStateOf(false) }\n    var appLanguage by remember { mutableStateOf(AppLanguageManager.getLanguage(context)) }\n',
    'HomeScreen language dialog state'
  );
  text = replaceOnce(
    text,
    '            Text(\n                text = "FgoGotran",\n                style = MaterialTheme.typography.headlineLarge,\n                color = MaterialTheme.colorScheme.primary\n            )\n',
    '            Row(\n                modifier = Modifier.fillMaxWidth(),\n                verticalAlignment = Alignment.CenterVertically\n            ) {\n                Text(\n                    text = "FgoGotran",\n                    style = MaterialTheme.typography.headlineLarge,\n                    color = MaterialTheme.colorScheme.primary,\n                    modifier = Modifier.weight(1f)\n                )\n                IconButton(onClick = { showLanguageDialog = true }) {\n                    Icon(\n                        painter = painterResource(R.drawable.ic_language_globe),\n                        contentDescription = AppLanguageManager.localizeUiText(context, "语言"),\n                        tint = MaterialTheme.colorScheme.primary\n                    )\n                }\n            }\n',
    'HomeScreen header row with globe'
  );
  text = replaceOnce(
    text,
    '        if (showServerDialog) {\n',
    '        if (showLanguageDialog) {\n            LanguagePickerDialog(\n                selectedLanguage = appLanguage,\n                onDismiss = { showLanguageDialog = false },\n                onSelect = { language ->\n                    showLanguageDialog = false\n                    appLanguage = language\n                    AppLanguageManager.setLanguage(context, language)\n                    AppLanguageManager.recreateActivity(context)\n                }\n            )\n        }\n\n        if (showServerDialog) {\n',
    'HomeScreen language dialog'
  );

  write(path, text);
}

console.log('home language edits applied');
