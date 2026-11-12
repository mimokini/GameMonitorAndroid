package com.gamemonitor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamemonitor.android.data.AppSettings
import com.gamemonitor.android.data.GameRepository
import com.gamemonitor.android.data.Snapshot
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun TextSettingsScreen(snapshot: Snapshot, repository: GameRepository) {
    val scope = rememberCoroutineScope()
    val initial = snapshot.settings

    var totalLimit by remember { mutableStateOf(initial.totalDailyLimitMinutes.toString()) }
    var countdown by remember { mutableStateOf(initial.countdownSeconds.toString()) }
    var countdownText by remember { mutableStateOf(initial.countdownText) }
    var cooldownText by remember { mutableStateOf(initial.cooldownText) }
    var blockedText by remember { mutableStateOf(initial.blockedText) }
    var fontSize by remember { mutableStateOf(initial.fontSizeSp.toFloat()) }
    var alpha by remember { mutableStateOf(initial.bgAlphaPercent.toFloat()) }
    var textColor by remember { mutableStateOf(Color(initial.textColor)) }
    var autoForceStop by remember { mutableStateOf(initial.autoForceStopEnabled) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("限额与节奏", fontWeight = FontWeight.SemiBold)
                NumberField("每日总限额（分钟，0=不限）", totalLimit) { totalLimit = it }
                NumberField("倒计时（秒，0=不拦截）", countdown) { countdown = it }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("提示文案", fontWeight = FontWeight.SemiBold)
                Text(
                    "占位符：{app} 应用名 · {minutes} 分钟 · {seconds} 秒 · {clock} 当前时间",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MessageField("倒计时", countdownText) { countdownText = it }
                MessageField("休息期提示", cooldownText) { cooldownText = it }
                MessageField("禁止时段提示", blockedText) { blockedText = it }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("浮层样式", fontWeight = FontWeight.SemiBold)
                Text("字号 ${fontSize.roundToInt()} sp", fontSize = 12.sp)
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 12f..48f,
                    steps = 17
                )
                Text("背景不透明度 ${alpha.roundToInt()}%", fontSize = 12.sp)
                Slider(
                    value = alpha,
                    onValueChange = { alpha = it },
                    valueRange = 20f..100f,
                    steps = 15
                )
                Text("文字颜色", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        Color(0xFFFFFFFF),
                        Color(0xFFFFE082),
                        Color(0xFF7BD88F),
                        Color(0xFFFF8B8B),
                        Color(0xFF8FB4FF),
                    ).forEach { color ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(color, CircleShape)
                                .border(
                                    width = if (color == textColor) 3.dp else 1.dp,
                                    color = if (color == textColor) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable { textColor = color }
                        )
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("拦截兜底", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("自动点击“强行停止”（实验性）", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = autoForceStop, onCheckedChange = { autoForceStop = it })
                }
                Text(
                    "默认关闭。开启后，若返回桌面失败，应用会自己打开该应用的系统设置详情页并点击一次“强行停止 / 确定”。" +
                        "仅在系统设置应用详情页生效，最长 15 秒后自动放弃；点击动作会写入日志（GameMonitor.A11y）。存在误点风险，建议保持关闭。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick = {
                val newSettings = initial.copy(
                    totalDailyLimitMinutes = totalLimit.toIntOrNull() ?: 0,
                    countdownSeconds = (countdown.toIntOrNull() ?: 10).coerceIn(0, 300),
                    countdownText = countdownText,
                    cooldownText = cooldownText,
                    blockedText = blockedText,
                    fontSizeSp = fontSize.roundToInt(),
                    bgAlphaPercent = alpha.roundToInt(),
                    textColor = argbOf(textColor),
                    autoForceStopEnabled = autoForceStop,
                )
                scope.launch { repository.saveSettings(newSettings) }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存样式与文案")
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun argbOf(color: Color): Long {
    val a = (color.alpha * 255).roundToInt().toLong()
    val r = (color.red * 255).roundToInt().toLong()
    val g = (color.green * 255).roundToInt().toLong()
    val b = (color.blue * 255).roundToInt().toLong()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun MessageField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        maxLines = 3,
        modifier = Modifier.fillMaxWidth()
    )
}
