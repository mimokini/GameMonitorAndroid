package com.gamemonitor.android.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamemonitor.android.data.PinManager
import com.gamemonitor.android.data.Snapshot
import com.gamemonitor.android.service.EnforcementManager
import com.gamemonitor.android.service.LauncherIconController
import com.gamemonitor.android.service.MonitorService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PermissionsScreen(
    enforcement: EnforcementManager,
    snapshot: Snapshot,
    pinManager: PinManager,
    launcherIcon: LauncherIconController,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            refresh++
            delay(1500)
        }
    }

    val usageAccess = remember(refresh) { enforcement.hasUsageAccess() }
    val deviceOwner = remember(refresh) { enforcement.isDeviceOwner() }
    val accessibility = remember(refresh) { enforcement.isAccessibilityEnabled() }
    val overlay = remember(refresh) { enforcement.canOverlay() }
    val battery = remember(refresh) { enforcement.isIgnoringBatteryOptimizations() }
    val notifications = remember(refresh) { enforcement.canPostNotifications() }

    val requirePinForSettings by pinManager.requirePinForSettingsFlow.collectAsState(initial = true)
    var pinConfigured by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinMessage by remember { mutableStateOf<String?>(null) }
    var iconHidden by remember { mutableStateOf(launcherIcon.isHidden()) }

    LaunchedEffect(refresh) { pinConfigured = pinManager.isPinSet() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "管控能力分级：设备所有者（静默挂起）> 无障碍 + 悬浮窗（浮层倒计时 + 返回桌面）> 仅悬浮窗（只提示）> 仅通知。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ------------------------------------------------------------ 管理密码
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("管理密码（家长控制）", fontWeight = FontWeight.SemiBold)
                Text(
                    if (pinConfigured) "状态：已设置（SHA-256 加盐保存，不落明文）"
                    else "状态：未设置 —— 未设置时禁止隐藏桌面图标",
                    fontSize = 11.sp,
                    color = if (pinConfigured) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("进入设置页（权限 Tab）需要密码", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = requirePinForSettings,
                        onCheckedChange = { checked ->
                            scope.launch { pinManager.setRequirePinForSettings(checked) }
                        }
                    )
                }
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { input -> pinInput = input.filter { it.isDigit() }.take(8) },
                    label = { Text(if (pinConfigured) "新密码（4~8 位数字）" else "密码（4~8 位数字）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                pinMessage?.let {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        scope.launch {
                            val ok = pinManager.setPin(pinInput, requirePinForSettings)
                            if (ok) {
                                pinInput = ""
                                pinConfigured = true
                                pinMessage = null
                                Toast.makeText(context, "管理密码已保存", Toast.LENGTH_SHORT).show()
                            } else {
                                pinMessage = "密码需为 4~8 位数字"
                            }
                        }
                    },
                    enabled = pinInput.length in 4..8,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (pinConfigured) "修改管理密码" else "设置管理密码")
                }
            }
        }

        // ------------------------------------------------------------ 桌面图标
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("桌面图标", fontWeight = FontWeight.SemiBold)
                Text(
                    if (iconHidden) "状态：已隐藏（可通过通知栏「管理」入口恢复）" else "状态：显示中",
                    fontSize = 11.sp,
                    color = if (iconHidden) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                if (!launcherIcon.hide()) {
                                    Toast.makeText(context, "隐藏失败", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                iconHidden = true
                                MonitorService.refreshNotification(context)
                                Toast.makeText(
                                    context,
                                    "图标已隐藏；恢复需通知栏「管理」+ 管理密码",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        enabled = pinConfigured && !iconHidden,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("隐藏图标", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                if (!launcherIcon.restore()) {
                                    Toast.makeText(context, "恢复失败，请用 adb 兜底", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                iconHidden = false
                                MonitorService.refreshNotification(context)
                                Toast.makeText(context, "桌面图标已恢复", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = iconHidden,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("恢复图标", fontSize = 12.sp)
                    }
                }
                if (!pinConfigured) {
                    Text(
                        "未设置管理密码：隐藏图标不可用。请先设置管理密码。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "adb 兜底恢复（通知入口也失效时）：",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    LauncherIconController.ADB_RESTORE_COMMAND,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(LauncherIconController.ADB_RESTORE_COMMAND))
                    Toast.makeText(context, "已复制 adb 恢复命令", Toast.LENGTH_SHORT).show()
                }) {
                    Text("复制 adb 恢复命令", fontSize = 12.sp)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前实际能力", fontWeight = FontWeight.SemiBold)
                val level = when {
                    deviceOwner -> "L3 · 设备所有者：可静默挂起/解除挂起"
                    overlay && accessibility -> "L2 · 无障碍 + 悬浮窗：全屏倒计时后可返回桌面"
                    overlay -> "L1 · 仅悬浮窗：只提示，无法强制退出"
                    else -> "L0 · 仅通知：无法拦截"
                }
                Text(level, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text(
                    "已配置 ${snapshot.apps.size} 个条目、${snapshot.rules.size} 条时段规则",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        PermissionRow(
            title = "使用情况访问（必需）",
            description = "读取前台应用，计时与拦截的前提。",
            granted = usageAccess,
            actionText = "去开启",
            onAction = { enforcement.openUsageAccessSettings() }
        )

        PermissionRow(
            title = "设备所有者（最高优先级）",
            description = "一次性 adb 授权后可静默挂起应用。需要设备未添加账号/未设置工作资料。",
            granted = deviceOwner,
            actionText = "复制 adb 命令",
            onAction = {
                clipboard.setText(AnnotatedString(EnforcementManager.DEVICE_OWNER_COMMAND))
                Toast.makeText(context, "已复制设备所有者命令", Toast.LENGTH_SHORT).show()
            }
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("设备所有者命令（在电脑执行）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    EnforcementManager.DEVICE_OWNER_COMMAND,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Text(
                    "解除设备所有者：adb shell dpm remove-active-admin com.gamemonitor.android/.service.MonitorDeviceAdminReceiver",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        PermissionRow(
            title = "无障碍服务",
            description = "仅用于返回桌面；开启「自动强行停止」后还可在系统设置应用详情页点击一次。不监听其他应用窗口。",
            granted = accessibility,
            actionText = "去开启",
            onAction = { enforcement.openAccessibilitySettings() }
        )

        PermissionRow(
            title = "悬浮窗权限",
            description = "显示全屏倒计时 / 休息 / 时段禁止浮层（浮层不拦截触摸）。",
            granted = overlay,
            actionText = "去开启",
            onAction = { enforcement.openOverlaySettings() }
        )

        PermissionRow(
            title = "忽略电池优化",
            description = "避免后台计时服务被系统冻结。",
            granted = battery,
            actionText = "去开启",
            onAction = { enforcement.openBatteryOptimizationSettings() }
        )

        PermissionRow(
            title = "通知权限",
            description = "前台服务与拦截提醒需要通知（也是隐藏图标的恢复入口）。",
            granted = notifications,
            actionText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "去授权" else "无需授权",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    enforcement.openAppNotificationSettings()
                }
            }
        )

        Text(
            "开机自启：应用已注册 BOOT_COMPLETED，可在系统设置中额外允许自启动。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    actionText: String,
    onAction: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (granted) "状态：已授权" else "状态：未授权",
                    fontSize = 11.sp,
                    color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}
