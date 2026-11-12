@file:OptIn(ExperimentalMaterial3Api::class)

package com.gamemonitor.android.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamemonitor.android.data.GameRepository
import com.gamemonitor.android.data.MonitoredApp
import com.gamemonitor.android.data.Snapshot
import com.gamemonitor.android.domain.InstalledAppsProvider
import com.gamemonitor.android.service.MonitorService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppsScreen(snapshot: Snapshot, repository: GameRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serviceRunning by remember { mutableStateOf(MonitorService.running) }
    var foregroundLabel by remember { mutableStateOf(MonitorService.lastForegroundLabel) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MonitoredApp?>(null) }
    var extending by remember { mutableStateOf<MonitoredApp?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            serviceRunning = MonitorService.running
            foregroundLabel = MonitorService.lastForegroundLabel
            delay(1000)
        }
    }

    fun persist(list: List<MonitoredApp>) {
        scope.launch {
            repository.saveApps(list.mapIndexed { index, app -> app.copy(order = index) })
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (serviceRunning) "监控服务：运行中" else "监控服务：已停止",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "当前前台：$foregroundLabel",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = serviceRunning,
                    onCheckedChange = { checked ->
                        if (checked) MonitorService.start(context) else MonitorService.stop(context)
                    }
                )
            }
        }

        if (snapshot.apps.isEmpty()) {
            Text(
                "还没有监控条目。添加后设置单次时长、每日配额与休息间隔。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        snapshot.apps.forEachIndexed { index, app ->
            AppRow(
                app = app,
                index = index,
                total = snapshot.apps.size,
                onToggle = { enabled ->
                    persist(snapshot.apps.map { if (it.packageName == app.packageName) it.copy(enabled = enabled) else it })
                },
                onMove = { delta ->
                    val list = snapshot.apps.toMutableList()
                    val target = index + delta
                    if (target in list.indices) {
                        val tmp = list[index]
                        list[index] = list[target]
                        list[target] = tmp
                        persist(list)
                    }
                },
                onEdit = { editing = app },
                onExtend = { extending = app },
                onDelete = {
                    persist(snapshot.apps.filterNot { it.packageName == app.packageName })
                }
            )
        }

        Button(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("添加监控应用")
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAddDialog) {
        AddAppDialog(
            existing = snapshot.apps.map { it.packageName }.toSet(),
            onDismiss = { showAddDialog = false },
            onPick = { entry ->
                showAddDialog = false
                persist(
                    snapshot.apps + MonitoredApp(
                        packageName = entry.packageName,
                        label = entry.label,
                        singleLimitMinutes = 30,
                        dailyQuotaMinutes = 120,
                        intervalMinutes = 10,
                        enabled = true,
                        order = snapshot.apps.size,
                    )
                )
            }
        )
    }

    extending?.let { app ->
        ExtendDialog(
            app = app,
            onDismiss = { extending = null },
            onConfirm = { minutes ->
                extending = null
                val ok = MonitorService.requestExtension(app.packageName, minutes)
                Toast.makeText(
                    context,
                    if (ok) "已为「${app.label}」加时 $minutes 分钟" else "监控服务未运行，无法加时",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    editing?.let { app ->
        AppEditDialog(
            app = app,
            onDismiss = { editing = null },
            onSave = { updated ->
                editing = null
                persist(snapshot.apps.map { if (it.packageName == updated.packageName) updated else it })
            }
        )
    }
}

@Composable
private fun AppRow(
    app: MonitoredApp,
    index: Int,
    total: Int,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onEdit: () -> Unit,
    onExtend: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. ${app.label}", fontWeight = FontWeight.SemiBold)
                    Text(app.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = app.enabled, onCheckedChange = onToggle)
            }
            Text(
                "单次 ${limitText(app.singleLimitMinutes)} · 每日 ${limitText(app.dailyQuotaMinutes)} · 间隔 ${intervalText(app.intervalMinutes)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onExtend) { Text("加时") }
                TextButton(onClick = { onMove(-1) }, enabled = index > 0) { Text("上移") }
                TextButton(onClick = { onMove(1) }, enabled = index < total - 1) { Text("下移") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private fun limitText(minutes: Int): String = if (minutes <= 0) "不限" else "$minutes 分钟"

private fun intervalText(minutes: Int): String = if (minutes <= 0) "无休息" else "$minutes 分钟"

@Composable
private fun ExtendDialog(app: MonitoredApp, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var minutes by remember { mutableStateOf("30") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给「${app.label}」加时") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "加时只作用于当前这一轮；若正在倒计时或配额已用尽，会立即恢复可用。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("加时分钟（默认 30）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm((minutes.toIntOrNull() ?: 30).coerceIn(1, 720))
            }) { Text("加时") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AppEditDialog(app: MonitoredApp, onDismiss: () -> Unit, onSave: (MonitoredApp) -> Unit) {
    var single by remember { mutableStateOf(app.singleLimitMinutes.toString()) }
    var quota by remember { mutableStateOf(app.dailyQuotaMinutes.toString()) }
    var interval by remember { mutableStateOf(app.intervalMinutes.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = single,
                    onValueChange = { single = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("单次时长（分钟，0=不限）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = quota,
                    onValueChange = { quota = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("每日配额（分钟，0=不限）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("休息间隔（分钟，0=无）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    app.copy(
                        singleLimitMinutes = single.toIntOrNull() ?: 0,
                        dailyQuotaMinutes = quota.toIntOrNull() ?: 0,
                        intervalMinutes = interval.toIntOrNull() ?: 0,
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AddAppDialog(
    existing: Set<String>,
    onDismiss: () -> Unit,
    onPick: (InstalledAppsProvider.Entry) -> Unit,
) {
    val context = LocalContext.current
    var all by remember { mutableStateOf<List<InstalledAppsProvider.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        all = runCatching { InstalledAppsProvider.load(context) }.getOrDefault(emptyList())
        loading = false
    }

    val filtered = all.filter {
        it.packageName !in existing &&
            (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加监控应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> CircularProgressIndicator(Modifier.padding(16.dp))
                    filtered.isEmpty() -> Text(
                        "没有可添加的第三方应用（系统应用与自身已排除）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(filtered.size) { i ->
                            val entry = filtered[i]
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { onPick(entry) }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(entry.label, fontWeight = FontWeight.Medium)
                                Text(
                                    entry.packageName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
