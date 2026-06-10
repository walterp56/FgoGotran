package com.fgogotran.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import com.fgogotran.accessibility.FgoAccessibilityService
import com.fgogotran.runner.FgoRunnerService
import com.fgogotran.ui.StartMediaProjection
import com.fgogotran.R

/**
 * Home screen with service status, "Start Service" flow, and navigation.
 *
 * ## Startup flow (like FGA's toggleOverlayService)
 * 1. Tap "启动服务" FAB
 * 2. Check overlay permission → show dialog if not granted
 * 3. Check accessibility service → show dialog if not running
 * 4. Check MediaProjection token → launch system screen share dialog
 * 5. Start FgoRunnerService → floating button appears on FGO
 *
 * Tap "停止服务" → stop service → floating button disappears
 */

@Composable
fun HomeScreen(
    onSettings: () -> Unit
) {
    val context = LocalContext.current
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

    // MediaProjection launcher (system "Share screen" dialog)
    val mediaProjectionLauncher =
        rememberLauncherForActivityResult(StartMediaProjection()) { intent ->
            if (intent != null) {
                FgoRunnerService.mediaProjectionToken = intent
                FgoRunnerService.startService(context)
            }
        }

    /**
     * Permission-gated service toggle.
     * Checks overlay → accessibility → MediaProjection → start service.
     */
    fun toggleService() {
        if (serviceRunning) {
            FgoRunnerService.stopService(context)
            FgoRunnerService.mediaProjectionToken = null
            return
        }

        // Check 1: Overlay permission
        if (!Settings.canDrawOverlays(context)) {
            AlertDialog.Builder(context, R.style.Theme_FgoGotran_Dialog)
                .setTitle("需要悬浮窗权限")
                .setMessage("FgoGotran 需要“显示在其他应用上层”权限，才能在 FGO 上显示翻译按钮。")
                .setPositiveButton("前往设置") { _, _ ->
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    }
                    context.startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // Check 2: Accessibility service
        if (!accessibilityRunning) {
            AlertDialog.Builder(context, R.style.Theme_FgoGotran_Dialog)
                .setTitle("需要无障碍服务")
                .setMessage("FgoGotran 需要无障碍服务来检测 FGO 中的点击并截取画面。\n\n请在设置中开启“FgoGotran”无障碍服务。")
                .setPositiveButton("前往设置") { _, _ ->
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // Check 3: MediaProjection — ask user to share screen
        if (FgoRunnerService.mediaProjectionToken == null) {
            mediaProjectionLauncher.launch(Unit)
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
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Text(
                text = "FgoGotran",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

//            Text(
//                text = "FGO 日文故事 → 中文即时翻译",
//                style = MaterialTheme.typography.bodyLarge,
//                textAlign = TextAlign.Center
//            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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

            // 1. Accessibility Service Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    //verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatusRow(
                        label = "无障碍服务",
                        statusText = accessibilityRunningStatusText,
                        statusColor = accessibilityRunningStatusColor,
                        enabled = accessibilityRunning
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    ) {
                        Text("前往无障碍设置 →")
                    }
                }
            }

            // 2. Overlay Permission Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatusRow(
                        label = "显示在其他应用上层",
                        statusText = if (canDrawOverlays) "已授权" else "未授权",
                        statusColor = if (canDrawOverlays) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        enabled = canDrawOverlays
                    )

                    TextButton(
                        onClick = {
                            // Direct link to THIS app's overlay setting
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Text("前往权限设置 →")
                    }
                }
            }

            // 3. Battery Optimization Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                val batteryColor = if (isIgnoringBatteryOptimizations) Color(0xFF4CAF50) else Color(0xFFFF9800)
                val batteryText = if (isIgnoringBatteryOptimizations) "已关闭优化" else "优化中（建议关闭）"

                Column(modifier = Modifier.padding(16.dp)) {
                    StatusRow(
                        label = "电池优化",
                        statusText = batteryText,
                        statusColor = batteryColor,
                        enabled = isIgnoringBatteryOptimizations
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    ) {
                        Text("前往管理电池使用量 →")
                    }
                }
            }

            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("设置")
            }

        }
    }
}

/**
 * A row showing a permission/service status with a colored dot indicator.
 */
@Composable
private fun StatusRow(label: String, enabled: Boolean ,statusText: String, statusColor: Color) {
    Row(
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
