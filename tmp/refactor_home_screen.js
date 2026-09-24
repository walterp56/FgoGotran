const fs = require('fs');
const filePath = 'app/src/main/java/com/fgogotran/ui/screen/HomeScreen.kt';
let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
function replaceOnce(source, needle, replacement, label) {
  const count = source.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return source.replace(needle, replacement);
}
function replaceAll(source, needle, replacement, expected, label) {
  const count = source.split(needle).length - 1;
  if (count !== expected) throw new Error(`${label}: expected ${expected}, found ${count}`);
  return source.split(needle).join(replacement);
}

text = replaceOnce(text, 'import androidx.compose.ui.res.painterResource\n', 'import androidx.compose.ui.res.painterResource\nimport androidx.compose.ui.res.stringResource\n', 'stringResource import');

text = replaceOnce(
  text,
  '    val accessibilityRunningStatusText = when (accessibilityState) {\n        AccessibilityConnectionState.CONNECTED -> "已连接"\n        AccessibilityConnectionState.ENABLED_NOT_CONNECTED -> "已开启，未连接"\n        AccessibilityConnectionState.DISABLED -> "未启用"\n        AccessibilityConnectionState.UNKNOWN -> "状态无法确认"\n    }\n',
  '    val accessibilityRunningStatusText = when (accessibilityState) {\n        AccessibilityConnectionState.CONNECTED -> stringResource(R.string.home_accessibility_connected)\n        AccessibilityConnectionState.ENABLED_NOT_CONNECTED -> stringResource(R.string.home_accessibility_enabled_not_connected)\n        AccessibilityConnectionState.DISABLED -> stringResource(R.string.home_accessibility_disabled)\n        AccessibilityConnectionState.UNKNOWN -> stringResource(R.string.home_accessibility_unknown)\n    }\n',
  'accessibility status text'
);
text = replaceOnce(text, '                        if (serviceRunning) "停止服务" else "启动服务"\n', '                        if (serviceRunning) stringResource(R.string.home_stop_service) else stringResource(R.string.home_start_service)\n', 'fab text');
text = replaceOnce(text, 'contentDescription = AppLanguageManager.localizeUiText(context, "语言"),', 'contentDescription = stringResource(R.string.home_language),', 'globe description');
text = replaceOnce(text, '                selectedLabel = SettingsRepository.gameServerDisplayName(gameServer),', '                selectedServer = gameServer,', 'server preference call');
text = replaceOnce(text, '                label = "无障碍服务",', '                label = stringResource(R.string.home_accessibility_label),', 'accessibility label');
text = replaceOnce(text, '                actionText = "设置 →",', '                actionText = stringResource(R.string.home_action_settings),', 'accessibility action');
text = replaceOnce(text, '                label = "显示在其他应用上层",', '                label = stringResource(R.string.home_overlay_label),', 'overlay label');
text = replaceOnce(text, '                statusText = if (canDrawOverlays) "已授权" else "未授权",', '                statusText = if (canDrawOverlays) stringResource(R.string.home_granted) else stringResource(R.string.home_not_granted),', 'overlay status');
text = replaceOnce(text, '                actionText = "去授权 →",', '                actionText = stringResource(R.string.home_action_grant),', 'overlay action');
text = replaceOnce(text, '                text = "可选稳定性（非必要）",', '                text = stringResource(R.string.home_optional_stability),', 'optional stability');
text = replaceOnce(text, '            val batteryText = if (isIgnoringBatteryOptimizations) "已关闭优化" else "优化中"', '            val batteryText = if (isIgnoringBatteryOptimizations) stringResource(R.string.home_battery_optimization_off) else stringResource(R.string.home_battery_optimization_on)', 'battery text');
text = replaceOnce(text, '                label = "电池优化",', '                label = stringResource(R.string.home_battery_optimization),', 'battery label');
text = replaceOnce(text, '                actionText = "去管理 →",', '                actionText = stringResource(R.string.home_action_manage),', 'battery action');
text = replaceOnce(text, '                    Text("设置")', '                    Text(stringResource(R.string.home_settings))', 'settings button');
text = replaceOnce(text, '                    Text("使用指南")', '                    Text(stringResource(R.string.home_guide))', 'guide button');

const serverStart = text.indexOf('private val gameServerOptions = listOf(');
const serverEnd = text.indexOf('@Composable\nprivate fun ServerChoiceDialog(');
if (serverStart < 0 || serverEnd < 0 || serverEnd <= serverStart) throw new Error('server block bounds not found');
const serverBlock = `@Composable
private fun ServerPreference(
    selectedServer: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val selectedLabel = gameServerLabel(context, selectedServer)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.home_server_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    selectedLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LanguageDirectionChip(
                    label = stringResource(R.string.home_current),
                    value = selectedLabel,
                    highlighted = false,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "→",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                LanguageDirectionChip(
                    label = stringResource(R.string.home_mode),
                    value = serverFeatureLabel(context, selectedServer),
                    highlighted = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun serverFeatureLabel(context: Context, server: String): String =
    if (SettingsRepository.normalizeGameServer(server) == SettingsRepository.GAME_SERVER_JP) {
        context.getString(R.string.home_mode_translate_voice)
    } else {
        context.getString(R.string.home_mode_voice)
    }

private fun gameServerLabel(context: Context, server: String): String =
    when (SettingsRepository.normalizeGameServer(server)) {
        SettingsRepository.GAME_SERVER_CN -> context.getString(R.string.home_server_cn)
        SettingsRepository.GAME_SERVER_TW -> context.getString(R.string.home_server_tw)
        else -> context.getString(R.string.home_server_jp)
    }

`;
text = text.slice(0, serverStart) + serverBlock + text.slice(serverEnd);

text = replaceOnce(text, '                    "FGO 服务器",', '                    stringResource(R.string.home_server_title),', 'server dialog title');
text = replaceOnce(
  text,
  '                    gameServerOptions.forEach { (server, label) ->\n                        LanguageDialogOption(\n                            label = label,\n                            selected = server == normalizedServer,\n                            onClick = { onSelect(server) }\n                        )\n                    }\n',
  '                    val serverOptions = listOf(\n                        SettingsRepository.GAME_SERVER_JP to stringResource(R.string.home_server_jp),\n                        SettingsRepository.GAME_SERVER_CN to stringResource(R.string.home_server_cn),\n                        SettingsRepository.GAME_SERVER_TW to stringResource(R.string.home_server_tw)\n                    )\n                    serverOptions.forEach { (server, label) ->\n                        LanguageDialogOption(\n                            label = label,\n                            selected = server == normalizedServer,\n                            onClick = { onSelect(server) }\n                        )\n                    }\n',
  'server dialog options'
);

text = replaceAll(text, 'AppLanguageManager.localizeUiText(context, "我同意并前往设置")', 'context.getString(R.string.home_agree_open_settings)', 2, 'agree button');
text = replaceAll(text, 'AppLanguageManager.localizeUiText(context, "取消")', 'context.getString(R.string.home_cancel)', 2, 'cancel button');
text = replaceOnce(text, 'AppLanguageManager.localizeUiText(context, "去设置")', 'context.getString(R.string.home_go_settings)', 'go settings button');
text = replaceOnce(text, 'AppLanguageManager.localizeUiText(context, "关闭")', 'context.getString(R.string.home_close)', 'close button');

fs.writeFileSync(filePath, text, 'utf8');
console.log('home screen refactor applied');
