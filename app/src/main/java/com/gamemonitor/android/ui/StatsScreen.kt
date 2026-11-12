package com.gamemonitor.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamemonitor.android.data.Snapshot
import com.gamemonitor.android.data.TimeUtils

@Composable
fun StatsScreen(snapshot: Snapshot) {
    val totalUsed = snapshot.totalUsedSeconds()
    val totalLimitMinutes = snapshot.settings.totalDailyLimitMinutes

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("今日总用时", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDuration(totalUsed), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(
                    if (totalLimitMinutes <= 0) "未设置每日总限额"
                    else "总限额 $totalLimitMinutes 分钟（${if (totalUsed >= totalLimitMinutes * 60L) "已达上限" else "还剩 " + formatDuration(totalLimitMinutes * 60L - totalUsed)}）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (snapshot.apps.isEmpty()) {
            Text("先添加监控条目，这里会显示每个应用的今日用量。", fontSize = 13.sp)
        }

        snapshot.apps.forEach { app ->
            val record = snapshot.usageOf(app.packageName)
            val used = record?.usedSeconds ?: 0L
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row {
                        Text(app.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            formatDuration(used),
                            fontWeight = FontWeight.Medium,
                            color = if (app.dailyQuotaMinutes > 0 && used >= app.dailyQuotaMinutes * 60L) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    Text(
                        "次数 ${record?.sessions ?: 0} · 配额 ${quotaText(app.dailyQuotaMinutes)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "首次开启 ${TimeUtils.hhmm(record?.firstOpenMillis)} · 末次关闭 ${TimeUtils.hhmm(record?.lastCloseMillis)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun quotaText(minutes: Int): String = if (minutes <= 0) "不限" else "$minutes 分钟"

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return "0 秒"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "$hours 小时 $minutes 分"
        minutes > 0 -> "$minutes 分 $secs 秒"
        else -> "$secs 秒"
    }
}
