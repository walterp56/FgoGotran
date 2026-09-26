package com.fgogotran.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.fgogotran.localization.AppLanguageManager
import com.fgogotran.localization.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.fgogotran.accessibility.AccessibilityConnectionState
import com.fgogotran.accessibility.FgoAccessibilityService
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.runner.FgoRunnerService
import com.fgogotran.ui.component.LanguagePickerDialog
import com.fgogotran.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home screen with service status, "Start Service" flow, and navigation.
 *
 * ## Startup flow
 * 1. Tap "启动服务" FAB
 * 2. Check overlay permission → show dialog if not granted
 * 3. Check accessibility service → show dialog if not running
 * 4. Start FgoRunnerService → floating button appears on FGO
 *
 * Tap "停止服务" → stop service → floating button disappears
 */

@Composable
fun HomeScreen(
    settingsRepository: SettingsRepository,
    diagnosticEventStore: DiagnosticEventStore,
    onGuide: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioCapturePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_SETUP,
                eventId = "playback_audio_permission_missing",
                title = "播放声音捕获权限未授权",
                message = "OCR 翻译仍会启动，实时语音翻译不会启动",
                detail = "Android 使用 RECORD_AUDIO 权限保护其他应用的播放声音捕获"
            )
        }
        if (ensureAccessibilityConnected(context, diagnosticEventStore, "audio_permission_result")) {
            FgoRunnerService.startService(context)
        }
    }
    val scrollState = rememberScrollState()
    val gameServer by settingsRepository.gameServer.collectAsState(
        initial = SettingsRepository.DEFAULT_GAME_SERVER
    )
    val liveVoiceTranslationEnabled by settingsRepository.liveVoiceTranslationEnabled.collectAsState(
        initial = false
    )
    val serviceRunning = FgoRunnerService.serviceStarted.value
    val accessibilityState = FgoAccessibilityService.connectionState.value
    val accessibilityRunningStatusColor = when (accessibilityState) {
        AccessibilityConnectionState.CONNECTED -> Color(0xFF4CAF50)
        else -> Color(0xFFFF9800)
    }
    val accessibilityRunningStatusText = when (accessibilityState) {
        AccessibilityConnectionState.CONNECTED -> stringResource(R.string.home_accessibility_connected)
        AccessibilityConnectionState.ENABLED_NOT_CONNECTED -> stringResource(R.string.home_accessibility_enabled_not_connected)
        AccessibilityConnectionState.DISABLED -> stringResource(R.string.home_accessibility_disabled)
        AccessibilityConnectionState.UNKNOWN -> stringResource(R.string.home_accessibility_unknown)
    }

    // Reactive state for permissions that change via system settings
    // (refreshed on Activity resume via LifecycleEventObserver)
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isIgnoringBatteryOptimizations by remember {
        val pm = context.getSystemService(PowerManager::class.java)
        mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
    }
    var showServerDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var appLanguage by remember { mutableStateOf(AppLanguageManager.getLanguage(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var homeResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    // Keep the accessibility card honest while it is on screen: the binding can die or come
    // back without any lifecycle callback reaching the app.
    LaunchedEffect(homeResumed) {
        while (homeResumed) {
            FgoAccessibilityService.refreshConnectionState(context, "home_poll")
            delay(2_000L)
        }
    }

    // update permissions state
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    homeResumed = true
                    FgoAccessibilityService.refreshConnectionState(context, "activity_resumed")
                    canDrawOverlays = Settings.canDrawOverlays(context)
                    isIgnoringBatteryOptimizations = context
                        .getSystemService(PowerManager::class.java)
                        .isIgnoringBatteryOptimizations(context.packageName)
                }
                Lifecycle.Event.ON_PAUSE -> homeResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * Permission-gated service toggle.
     * Checks overlay → accessibility → start service.
     */
    fun toggleService() {
        if (serviceRunning) {
            FgoRunnerService.stopService(context)
            return
        }

        // Check 1: Overlay permission
        if (!Settings.canDrawOverlays(context)) {
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_SETUP,
                eventId = "overlay_permission_missing",
                title = "悬浮窗权限未授权",
                message = "启动服务被阻止",
                detail = "显示在其他应用上层权限未开启"
            )
            showOverlayPermissionDisclosure(context)
            return
        }

        // The system switch alone does not mean Android has bound our service.
        if (!ensureAccessibilityConnected(context, diagnosticEventStore, "toggle_service")) return

        // All permissions granted → start service
        if (!isIgnoringBatteryOptimizations) {
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_SETUP,
                eventId = "battery_optimization_active",
                title = "电池优化可能限制后台服务",
                message = "服务仍会尝试启动，但部分手机或模拟器可能会中断悬浮窗",
                detail = "battery_optimization=active"
            )
        }
        // Plain OCR no longer needs a MediaProjection consent at startup; the live voice feature
        // asks for screen capture itself when the user switches it on.
        if (
            liveVoiceTranslationEnabled &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioCapturePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            FgoRunnerService.startService(context)
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { toggleService() },
                icon = {
                    Text(
                        if (serviceRunning) "⏹" else "▶",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                text = {
                    Text(
                        if (serviceRunning) stringResource(R.string.home_stop_service) else stringResource(R.string.home_start_service)
                    )
                },
                containerColor = if (serviceRunning)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary,
                contentColor = if (serviceRunning)
                    MaterialTheme.colorScheme.onError
                else
                    MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FgoGotran",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showLanguageDialog = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_language_globe),
                        contentDescription = stringResource(R.string.home_language),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            ServerPreference(
                selectedServer = gameServer,
                onClick = { showServerDialog = true }
            )

//            Text(
//                text = "FGO 日文故事 → 中文即时翻译",
//                style = MaterialTheme.typography.bodyLarge,
//                textAlign = TextAlign.Center
//            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // Service running notice bar
//            if (serviceRunning) {
//                Surface(
//                    modifier = Modifier.fillMaxWidth(),
//                    color = Color(0xFF4CAF50).copy(alpha = 0.15f),
//                    shape = MaterialTheme.shapes.small
//                ) {
//                    Row(
//                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
//                        verticalAlignment = Alignment.CenterVertically,
//                        horizontalArrangement = Arrangement.Center
//                    ) {
//                        Text("●", color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall)
//                        Spacer(modifier = Modifier.width(8.dp))
//                        Text(
//                            "FgoGotran 服务中",
//                            color = Color(0xFF4CAF50),
//                            style = MaterialTheme.typography.bodyMedium
//                        )
//                    }
//                }
//            }

            StatusActionCard(
                label = stringResource(R.string.home_accessibility_label),
                statusText = accessibilityRunningStatusText,
                statusColor = accessibilityRunningStatusColor,
                enabled = accessibilityState == AccessibilityConnectionState.CONNECTED,
                actionText = stringResource(R.string.home_action_settings),
                onClick = {
                    when (accessibilityState) {
                        AccessibilityConnectionState.DISABLED,
                        AccessibilityConnectionState.CONNECTED -> showAccessibilityDisclosure(context)
                        AccessibilityConnectionState.ENABLED_NOT_CONNECTED,
                        AccessibilityConnectionState.UNKNOWN -> showAccessibilityNotConnectedDialog(context)
                    }
                }
            )

            StatusActionCard(
                label = stringResource(R.string.home_overlay_label),
                statusText = if (canDrawOverlays) stringResource(R.string.home_granted) else stringResource(R.string.home_not_granted),
                statusColor = if (canDrawOverlays) Color(0xFF4CAF50) else Color(0xFFFF9800),
                enabled = canDrawOverlays,
                actionText = stringResource(R.string.home_action_grant),
                onClick = { showOverlayPermissionDisclosure(context) }
            )

            Text(
                text = stringResource(R.string.home_optional_stability),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )

            val batteryColor = if (isIgnoringBatteryOptimizations) Color(0xFF4CAF50) else Color(0xFFFF9800)
            val batteryText = if (isIgnoringBatteryOptimizations) stringResource(R.string.home_battery_optimization_off) else stringResource(R.string.home_battery_optimization_on)
            StatusActionCard(
                label = stringResource(R.string.home_battery_optimization),
                statusText = batteryText,
                statusColor = batteryColor,
                enabled = isIgnoringBatteryOptimizations,
                actionText = stringResource(R.string.home_action_manage),
                onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.home_settings))
                }

                OutlinedButton(
                    onClick = onGuide,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.home_guide))
                }
            }
        }

        if (showLanguageDialog) {
            LanguagePickerDialog(
                selectedLanguage = appLanguage,
                onDismiss = { showLanguageDialog = false },
                onSelect = { language ->
                    showLanguageDialog = false
                    appLanguage = language
                    AppLanguageManager.setLanguage(context, language)
                    AppLanguageManager.recreateActivity(context)
                    FgoRunnerService.refreshOverlayLanguage()
                }
            )
        }

        if (showServerDialog) {
            ServerChoiceDialog(
                selectedServer = gameServer,
                onDismiss = { showServerDialog = false },
                onSelect = { server ->
                    showServerDialog = false
                    scope.launch {
                        settingsRepository.setGameServer(server)
                    }
                }
            )
        }
    }
}

@Composable
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

@Composable
private fun ServerChoiceDialog(
    selectedServer: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val normalizedServer = SettingsRepository.normalizeGameServer(selectedServer)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.home_server_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val serverOptions = listOf(
                        SettingsRepository.GAME_SERVER_JP to stringResource(R.string.home_server_jp),
                        SettingsRepository.GAME_SERVER_CN to stringResource(R.string.home_server_cn),
                        SettingsRepository.GAME_SERVER_TW to stringResource(R.string.home_server_tw)
                    )
                    serverOptions.forEach { (server, label) ->
                        LanguageDialogOption(
                            label = label,
                            selected = server == normalizedServer,
                            onClick = { onSelect(server) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageDirectionChip(
    label: String,
    value: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val valueColor = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = valueColor.copy(alpha = 0.72f)
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = valueColor
            )
        }
    }
}

@Composable
private fun StatusActionCard(
    label: String,
    statusText: String,
    statusColor: Color,
    enabled: Boolean,
    actionText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusDot(enabled)
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    modifier = Modifier.padding(start = 18.dp)
                )
            }
            TextButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(actionText, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatusDot(enabled: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (enabled) Color(0xFF4CAF50) else Color(0xFFFF9800),
        modifier = Modifier.size(10.dp)
    ) {}
}

@Composable
private fun LanguageDialogOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = CircleShape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Both start paths must check the live binding immediately before launching the runner. */
private fun ensureAccessibilityConnected(
    context: Context,
    diagnosticEventStore: DiagnosticEventStore,
    reason: String
): Boolean {
    return when (FgoAccessibilityService.refreshConnectionState(context, reason)) {
        AccessibilityConnectionState.CONNECTED -> true
        AccessibilityConnectionState.DISABLED -> {
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_SETUP,
                eventId = "accessibility_service_missing",
                title = "无障碍服务未启用",
                message = "启动服务被阻止",
                detail = "若 Android 已显示开启，请关闭 FgoGotran 无障碍服务后重新开启"
            )
            showAccessibilityDisclosure(context)
            false
        }
        AccessibilityConnectionState.ENABLED_NOT_CONNECTED,
        AccessibilityConnectionState.UNKNOWN -> {
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_SETUP,
                eventId = "accessibility_service_not_connected",
                title = "无障碍服务未连接",
                message = "启动服务被阻止：FgoGotran 没有收到无障碍服务连接",
                detail = "关闭 FgoGotran 无障碍服务 → 等 2–3 秒 → 重新开启；若仍失败请重启模拟器"
            )
            showAccessibilityNotConnectedDialog(context)
            false
        }
    }
}

private fun showAccessibilityDisclosure(context: Context) {
    AlertDialog.Builder(context, R.style.Theme_FgoGotran_Dialog)
        .setTitle(context.getString(R.string.accessibility_disclosure_title))
        .setMessage(context.getString(R.string.accessibility_disclosure_message))
        .setPositiveButton(context.getString(R.string.home_agree_open_settings)) { _, _ ->
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        .setNegativeButton(context.getString(R.string.home_cancel), null)
        .show()
}

/**
 * Shown when no live binding exists even though Android may show the service as enabled.
 */
private fun showAccessibilityNotConnectedDialog(context: Context) {
    AlertDialog.Builder(context, R.style.Theme_FgoGotran_Dialog)
        .setTitle(context.getString(R.string.accessibility_not_connected_title))
        .setMessage(context.getString(R.string.accessibility_not_connected_message))
        .setPositiveButton(context.getString(R.string.home_go_settings)) { _, _ ->
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        .setNegativeButton(context.getString(R.string.home_close), null)
        .show()
}

private fun showOverlayPermissionDisclosure(context: Context) {
    AlertDialog.Builder(context, R.style.Theme_FgoGotran_Dialog)
        .setTitle(context.getString(R.string.overlay_permission_title))
        .setMessage(context.getString(R.string.overlay_permission_message))
        .setPositiveButton(context.getString(R.string.home_agree_open_settings)) { _, _ ->
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
        .setNegativeButton(context.getString(R.string.home_cancel), null)
        .show()
}
