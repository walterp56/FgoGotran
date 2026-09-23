const fs = require('fs');
function read(path) { return fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n'); }
function write(path, text) { fs.writeFileSync(path, text, 'utf8'); }
function replaceOnce(text, needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return text.replace(needle, replacement);
}

// Make the language normalizer available to the settings UI.
{
  const path = 'app/src/main/java/com/fgogotran/localization/AppLanguageManager.kt';
  let text = read(path);
  text = replaceOnce(
    text,
    '    private fun normalizeLanguage(language: String): String =\n',
    '    fun normalizeLanguage(language: String): String =\n',
    'AppLanguageManager normalizeLanguage visibility'
  );
  write(path, text);
}

{
  const path = 'app/src/main/java/com/fgogotran/ui/screen/SettingsScreen.kt';
  let text = read(path);

  text = replaceOnce(
    text,
    'import com.fgogotran.data.SettingsRepository\n',
    'import com.fgogotran.data.SettingsRepository\nimport com.fgogotran.localization.AppLanguageManager\n',
    'SettingsScreen AppLanguageManager import'
  );

  text = replaceOnce(
    text,
    '    var pendingUpdate by remember { mutableStateOf<AppVersionInfo?>(null) }\n',
    '    var pendingUpdate by remember { mutableStateOf<AppVersionInfo?>(null) }\n    var appLanguage by remember { mutableStateOf(AppLanguageManager.LANGUAGE_SYSTEM) }\n',
    'SettingsScreen appLanguage state'
  );

  text = replaceOnce(
    text,
    '    LaunchedEffect(Unit) {\n        playerName = settingsRepository.playerName.first()\n',
    '    LaunchedEffect(Unit) {\n        appLanguage = AppLanguageManager.getLanguage(context)\n        playerName = settingsRepository.playerName.first()\n',
    'SettingsScreen appLanguage load'
  );

  text = replaceOnce(
    text,
    '        ) {\n            SettingsCard(\n                iconRes = R.drawable.ic_translate,\n',
    '        ) {\n            SettingsCard(\n                iconRes = R.drawable.ic_settings_tune,\n                title = "界面语言",\n                body = "选择 App 显示语言；系统默认会跟随系统设置，未支援的语言回退到简体中文。"\n            ) {\n                UiLanguageSelector(\n                    selectedLanguage = appLanguage,\n                    onSelect = { language ->\n                        appLanguage = language\n                        AppLanguageManager.setLanguage(context, language)\n                        AppLanguageManager.recreateActivity(context)\n                    }\n                )\n            }\n\n            SettingsCard(\n                iconRes = R.drawable.ic_translate,\n',
    'SettingsScreen language card'
  );

  text = replaceOnce(
    text,
    'private val targetChineseLocaleOptions = listOf(\n',
    'private data class UiLanguageOption(\n    val language: String,\n    val label: String\n)\n\nprivate val uiLanguageOptions = listOf(\n    UiLanguageOption(\n        language = AppLanguageManager.LANGUAGE_SYSTEM,\n        label = "系统默认"\n    ),\n    UiLanguageOption(\n        language = AppLanguageManager.LANGUAGE_TRADITIONAL,\n        label = "繁體中文"\n    ),\n    UiLanguageOption(\n        language = AppLanguageManager.LANGUAGE_SIMPLIFIED,\n        label = "简体中文"\n    )\n)\n\n@Composable\nprivate fun UiLanguageSelector(\n    selectedLanguage: String,\n    onSelect: (String) -> Unit\n) {\n    val normalizedLanguage = AppLanguageManager.normalizeLanguage(selectedLanguage)\n    Row(\n        modifier = Modifier.fillMaxWidth(),\n        horizontalArrangement = Arrangement.spacedBy(10.dp),\n        verticalAlignment = Alignment.CenterVertically\n    ) {\n        uiLanguageOptions.forEach { option ->\n            UiLanguageOptionCard(\n                label = option.label,\n                selected = option.language == normalizedLanguage,\n                onClick = { onSelect(option.language) },\n                modifier = Modifier.weight(1f)\n            )\n        }\n    }\n}\n\n@Composable\nprivate fun UiLanguageOptionCard(\n    label: String,\n    selected: Boolean,\n    onClick: () -> Unit,\n    modifier: Modifier = Modifier\n) {\n    Surface(\n        modifier = modifier.clickable(onClick = onClick),\n        shape = MaterialTheme.shapes.small,\n        color = if (selected) {\n            MaterialTheme.colorScheme.primaryContainer\n        } else {\n            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)\n        },\n        border = BorderStroke(\n            width = 1.dp,\n            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)\n        )\n    ) {\n        Column(\n            modifier = Modifier\n                .fillMaxWidth()\n                .padding(horizontal = 8.dp, vertical = 12.dp),\n            horizontalAlignment = Alignment.CenterHorizontally,\n            verticalArrangement = Arrangement.spacedBy(2.dp)\n        ) {\n            Text(\n                label,\n                style = MaterialTheme.typography.bodyLarge,\n                fontWeight = FontWeight.SemiBold,\n                color = if (selected) {\n                    MaterialTheme.colorScheme.onPrimaryContainer\n                } else {\n                    MaterialTheme.colorScheme.onSurfaceVariant\n                },\n                textAlign = TextAlign.Center\n            )\n        }\n    }\n}\n\nprivate val targetChineseLocaleOptions = listOf(\n',
    'SettingsScreen language selector definitions'
  );

  write(path, text);
}

console.log('phase1 settings edits applied');
