package com.fgogotran.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.fgogotran.accessibility.FgoAccessibilityService
import com.fgogotran.data.SettingsRepository
import com.fgogotran.runner.FgoRunnerService
import com.fgogotran.R
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
    onGuide: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val targetChineseLocale by settingsRepository.targetChineseLocale.collectAsState(
        initial = SettingsRepository.TARGET_LOCALE_SIMPLIFIED
    )
    val serviceRunning = FgoRunnerService.serviceStarted.value
    val accessibilityRunning = FgoAccessibilityService.serviceStarted.value
    val accessibilityRunningStatusColor = if (accessibilityRunning) Color(0xFF4CAF50) else Color(0xFFFF9800) // Green vs Orange
    val accessibilityRunningStatusText = if (accessibilityRunning) "已启用" else "未启用"

    // Reactive state for permissions that change via system settings
    // (refreshed on Activity resume via LifecycleEventObserver)
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isIgnoringBatteryOptimizations by remember {
        val pm = context.getSystemService(PowerManager::class.java)
        mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
    }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // update permissions state
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
                isIgnoringBatteryOptimizations = context
                    .getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName)
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
            showOverlayPermissionDisclosure(context)
            return
        }

        // Check 2: Accessibility service
        if (!accessibilityRunning) {
            showAccessibilityDisclosure(context)
            return
        }

        // All permissions granted → start service
        FgoRunnerService.startService(context)
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
                        if (serviceRunning) "停止服务" else "启动服务"
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
            Text(
                text = "FgoGotran",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

            LanguagePreference(
                selectedLabel = targetChineseLocaleLabel(targetChineseLocale),
                onClick = { showLanguageDialog = true }
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
                label = "无障碍服务",
                statusText = accessibilityRunningStatusText,
                statusColor = accessibilityRunningStatusColor,
                enabled = accessibilityRunning,
                actionText = "去设置 →",
                onClick = { showAccessibilityDisclosure(context) }
            )

            StatusActionCard(
                label = "显示在其他应用上层",
                statusText = if (canDrawOverlays) "已授权" else "未授权",
                statusColor = if (canDrawOverlays) Color(0xFF4CAF50) else Color(0xFFFF9800),
                enabled = canDrawOverlays,
                actionText = "去授权 →",
                onClick = { showOverlayPermissionDisclosure(context) }
            )

            Text(
                text = "可选稳定性（非必要）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )

            val batteryColor = if (isIgnoringBatteryOptimizations) Color(0xFF4CAF50) else Color(0xFFFF9800)
            val batteryText = if (isIgnoringBatteryOptimizations) "已关闭优化" else "优化中"
            StatusActionCard(
                label = "电池优化",
                statusText = batteryText,
                statusColor = batteryColor,
                enabled = isIgnoringBatteryOptimizations,
                actionText = "去管理 →",
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
                    Text("设置")
                }

                OutlinedButton(
                    onClick = onGuide,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("使用指南")
                }
            }
        }

        if (showLanguageDialog) {
            LanguageChoiceDialog(
                selectedLocale = targetChineseLocale,
                onDismiss = { showLanguageDialog = false },
                onSelect = { locale ->
                    showLanguageDialog = false
                    scope.launch {
                        settingsRepository.setTargetChineseLocale(locale)
                    }
                }
            )
        }
    }
}

private val targetChineseLocaleOptions = listOf(
    SettingsRepository.TARGET_LOCALE_SIMPLIFIED to "简体中文",
    SettingsRepository.TARGET_LOCALE_TRADITIONAL to "繁體中文"
)

private fun targetChineseLocaleLabel(locale: String): String {
    val normalizedLocale = SettingsRepository.normalizeTargetChineseLocale(locale)
    return targetChineseLocaleOptions.firstOrNull { it.first == normalizedLocale }?.second
        ?: "简体中文"
}

@Composable
private fun LanguagePreference(
    selectedLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    "翻译语言",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "›",
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
                    label = "原文",
                    value = "日文",
                    highlighted = false,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "→",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                LanguageDirectionChip(
                    label = "译文",
                    value = selectedLabel,
                    highlighted = true,
                    modifier = Modifier.weight(1f)
                )
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
            StatusRow(
                label = label,
                statusText = statusText,
                statusColor = statusColor,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(actionText)
            }
        }
    }
}

@Composable
private fun LanguageChoiceDialog(
    selectedLocale: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val normalizedLocale = SettingsRepository.normalizeTargetChineseLocale(selectedLocale)

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
                    "Translate To",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    targetChineseLocaleOptions.forEach { (locale, label) ->
                        LanguageDialogOption(
                            label = label,
                            selected = locale == normalizedLocale,
                            onClick = { onSelect(locale) }
                        )
                    }
                }
            }
        }
    }
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

/**
 * A row showing a permission/service status with a colored dot indicator.
 */
@Composable
private fun StatusRow(
    label: String,
    enabled: Boolean,
    statusText: String,
    statusColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status dot
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = if (enabled) Color(0xFF4CAF50) else Color(0xFFFF9800),
            modifier = Modifier.size(10.dp)
        ) {}
        // Label (Standard color)
        Text(
            text = "$label：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Status Text (Only this part is Orange/Green)
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor
        )
    }
}

private fun showAccessibilityDisclosure(context: Context) {
    AlertDialog.Builder(context, R.style.Theme_FgoGotran_Dialog)
        .setTitle("无障碍服务用途说明")
        .setMessage(
            """
            FgoGotran 不是无障碍辅助工具。开启后，它只用于 FGO 翻译功能：

            • 检测 FGO 窗口变化和点击，用于判断何时刷新剧情翻译
            • 在您启动服务后截取当前 FGO 画面，用 OCR 识别日文剧情文字
            • 在 FGO 上方显示翻译覆盖层
            • 当您点击翻译覆盖层时，将该点击转发给 FGO

            如果使用在线翻译接口，识别出的待翻译文字会发送到您选择或配置的翻译服务。FgoGotran 不会读取联系人、短信、密码、银行应用内容，也不会在未启动服务时自动控制其他应用。

            继续表示您理解并同意上述用途。
            """.trimIndent()
        )
        .setPositiveButton("我同意并前往设置") { _, _ ->
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        .setNegativeButton("取消", null)
        .show()
}

private fun showOverlayPermissionDisclosure(context: Context) {
    AlertDialog.Builder(context, R.style.Theme_FgoGotran_Dialog)
        .setTitle("悬浮窗权限用途说明")
        .setMessage(
            """
            FgoGotran 需要“显示在其他应用上层”权限，才能在 FGO 上显示翻译按钮、菜单和翻译结果。

            该权限只用于 FGO 翻译覆盖层。您可以随时在系统设置中关闭此权限；关闭后翻译按钮和覆盖层将无法显示。

            继续表示您理解并同意上述用途。
            """.trimIndent()
        )
        .setPositiveButton("我同意并前往设置") { _, _ ->
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
        .setNegativeButton("取消", null)
        .show()
}
