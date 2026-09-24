const fs = require('fs');
function replaceOnce(source, needle, replacement, label) {
  const count = source.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1, found ${count}`);
  return source.replace(needle, replacement);
}
{
  const filePath = 'app/src/main/java/com/fgogotran/ui/overlay/FloatingArcMenu.kt';
  let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
  text = replaceOnce(text, 'import androidx.compose.ui.platform.LocalDensity\n', 'import androidx.compose.ui.platform.LocalDensity\nimport androidx.compose.ui.res.stringResource\n', 'stringResource import');
  text = replaceOnce(text, 'import com.fgogotran.data.SettingsRepository\n', 'import com.fgogotran.R\nimport com.fgogotran.data.SettingsRepository\n', 'R import');
  text = replaceOnce(
    text,
    '    val isJapaneseServer =\n        SettingsRepository.normalizeGameServer(gameServer) == SettingsRepository.GAME_SERVER_JP\n',
    '    val isJapaneseServer =\n        SettingsRepository.normalizeGameServer(gameServer) == SettingsRepository.GAME_SERVER_JP\n    val logLabel = stringResource(R.string.menu_log)\n    val areaLabel = stringResource(R.string.menu_area)\n    val battleLabel = stringResource(R.string.menu_battle)\n    val liveLabel = stringResource(R.string.menu_live)\n    val manualLabel = stringResource(R.string.menu_manual)\n    val semiLabel = stringResource(R.string.menu_semi)\n    val autoLabel = stringResource(R.string.menu_auto)\n    val closeLabel = stringResource(R.string.menu_close)\n',
    'menu labels'
  );
  text = replaceOnce(
    text,
    '            ArcPage.FEATURES -> featureSlots(\n                battleModeActive = battleModeActive,\n',
    '            ArcPage.FEATURES -> featureSlots(\n                logLabel = logLabel,\n                areaLabel = areaLabel,\n                battleLabel = battleLabel,\n                liveLabel = liveLabel,\n                battleModeActive = battleModeActive,\n',
    'feature slots call'
  );
  text = replaceOnce(
    text,
    '            ArcPage.MODES -> modeSlots(\n                translationMode = translationMode,\n',
    '            ArcPage.MODES -> modeSlots(\n                manualLabel = manualLabel,\n                semiLabel = semiLabel,\n                autoLabel = autoLabel,\n                closeLabel = closeLabel,\n                translationMode = translationMode,\n',
    'mode slots call'
  );
  text = replaceOnce(
    text,
    'private fun featureSlots(\n    battleModeActive: Boolean,',
    'private fun featureSlots(\n    logLabel: String,\n    areaLabel: String,\n    battleLabel: String,\n    liveLabel: String,\n    battleModeActive: Boolean,',
    'feature slots signature'
  );
  text = replaceOnce(text, '        label = "翻译LOG",', '        label = logLabel,', 'feature log label');
  text = replaceOnce(text, '        label = "区域翻译",', '        label = areaLabel,', 'feature area label');
  text = replaceOnce(text, '        label = "BATTLE",', '        label = battleLabel,', 'feature battle label');
  text = replaceOnce(text, '        label = "实时字幕",', '        label = liveLabel,', 'feature live label');
  text = replaceOnce(
    text,
    'private fun modeSlots(\n    translationMode: TranslationMode,',
    'private fun modeSlots(\n    manualLabel: String,\n    semiLabel: String,\n    autoLabel: String,\n    closeLabel: String,\n    translationMode: TranslationMode,',
    'mode slots signature'
  );
  text = replaceOnce(text, '        label = "手动",', '        label = manualLabel,', 'mode manual label');
  text = replaceOnce(text, '        label = "半自动",', '        label = semiLabel,', 'mode semi label');
  text = replaceOnce(text, '        label = "全自动",', '        label = autoLabel,', 'mode auto label');
  text = replaceOnce(text, '        label = "关闭服务",', '        label = closeLabel,', 'mode close label');
  fs.writeFileSync(filePath, text, 'utf8');
}
{
  const filePath = 'app/src/main/java/com/fgogotran/ui/overlay/FloatingButton.kt';
  let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
  text = replaceOnce(text, 'import android.content.Context\n', '', 'remove Context import');
  text = replaceOnce(text, 'import com.fgogotran.localization.AppLanguageManager\n', '', 'remove AppLanguageManager import');
  text = replaceOnce(text, 'import androidx.compose.ui.platform.LocalHapticFeedback\n', 'import androidx.compose.ui.platform.LocalHapticFeedback\nimport androidx.compose.ui.res.stringResource\n', 'stringResource import');
  text = replaceOnce(text, 'import com.fgogotran.localization.LocalizedText as Text\n', 'import com.fgogotran.R\nimport com.fgogotran.localization.LocalizedText as Text\n', 'R import');
  text = replaceOnce(
    text,
    'internal fun FloatingActionIcon.textLabel(context: Context): String {\n    val english = AppLanguageManager.effectiveLanguageTag(context) == AppLanguageManager.LANGUAGE_ENGLISH\n    return when (this) {\n        FloatingActionIcon.GO -> "GO"\n        FloatingActionIcon.SEMI -> if (english) "S" else "半"\n        FloatingActionIcon.AUTO -> if (english) "A" else "全"\n        FloatingActionIcon.BATTLE -> if (english) "B" else "戰"\n        else -> error("$this is not a text action")\n    }\n}\n',
    '@Composable\nprivate fun FloatingActionIcon.textLabel(): String = when (this) {\n    FloatingActionIcon.GO -> stringResource(R.string.mode_glyph_manual)\n    FloatingActionIcon.SEMI -> stringResource(R.string.mode_glyph_semi)\n    FloatingActionIcon.AUTO -> stringResource(R.string.mode_glyph_auto)\n    FloatingActionIcon.BATTLE -> stringResource(R.string.mode_glyph_battle)\n    else -> error("$this is not a text action")\n}\n',
    'glyph function'
  );
  text = replaceOnce(text, '    val context = androidx.compose.ui.platform.LocalContext.current\n    when (icon) {', '    when (icon) {', 'remove glyph context');
  text = replaceOnce(text, '                text = icon.textLabel(context),', '                text = icon.textLabel(),', 'glyph call');
  fs.writeFileSync(filePath, text, 'utf8');
}
console.log('menu and glyph resources applied');
