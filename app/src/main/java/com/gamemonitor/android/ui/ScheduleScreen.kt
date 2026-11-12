@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gamemonitor.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamemonitor.android.data.GameRepository
import com.gamemonitor.android.data.ScheduleRule
import com.gamemonitor.android.data.ScheduleType
import com.gamemonitor.android.data.Snapshot
import com.gamemonitor.android.data.TimeUtils
import kotlinx.coroutines.launch

@Composable
fun ScheduleScreen(snapshot: Snapshot, repository: GameRepository) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ScheduleRule?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "规则说明：禁止时段优先命中即拦截；若存在启用中的开放时段，未命中任何开放时段同样拦截。结束时间早于开始时间视为跨零点。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (snapshot.rules.isEmpty()) {
            Text("还没有时段规则。", fontSize = 13.sp)
        }

        snapshot.rules.forEach { rule ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (rule.type == ScheduleType.BLOCK) "禁止时段" else "开放时段",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${rule.startText()} - ${rule.endText()}  " +
                                    rule.days.sorted().joinToString(" ") { TimeUtils.dayLabel(it) },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    repository.saveRules(
                                        snapshot.rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }
                                    )
                                }
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { editing = rule }) { Text("编辑") }
                        TextButton(onClick = {
                            scope.launch { repository.saveRules(snapshot.rules.filterNot { it.id == rule.id }) }
                        }) { Text("删除") }
                    }
                }
            }
        }

        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
            Text("新增时段规则")
        }
        Spacer(Modifier.height(24.dp))
    }

    if (creating || editing != null) {
        RuleDialog(
            initial = editing ?: ScheduleRule(),
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { rule ->
                val list = if (editing == null) snapshot.rules + rule
                else snapshot.rules.map { if (it.id == rule.id) rule else it }
                creating = false
                editing = null
                scope.launch { repository.saveRules(list) }
            }
        )
    }
}

@Composable
private fun RuleDialog(
    initial: ScheduleRule,
    onDismiss: () -> Unit,
    onSave: (ScheduleRule) -> Unit,
) {
    var type by remember { mutableStateOf(initial.type) }
    var days by remember { mutableStateOf(initial.days) }
    var enabled by remember { mutableStateOf(initial.enabled) }

    val startState = rememberTimePickerState(
        initialHour = initial.startMinute / 60,
        initialMinute = initial.startMinute % 60,
        is24Hour = true
    )
    val endState = rememberTimePickerState(
        initialHour = initial.endMinute / 60,
        initialMinute = initial.endMinute % 60,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "新增时段规则" else "编辑时段规则") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == ScheduleType.BLOCK,
                        onClick = { type = ScheduleType.BLOCK },
                        label = { Text("禁止时段") }
                    )
                    FilterChip(
                        selected = type == ScheduleType.ALLOW,
                        onClick = { type = ScheduleType.ALLOW },
                        label = { Text("开放时段") }
                    )
                }

                Text("开始时间", fontSize = 12.sp)
                TimeInput(state = startState)
                Text("结束时间", fontSize = 12.sp)
                TimeInput(state = endState)

                Text("生效周几", fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { day ->
                        FilterChip(
                            selected = day in days,
                            onClick = {
                                days = if (day in days) days - day else days + day
                            },
                            label = { Text("周${TimeUtils.dayLabel(day)}") }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", modifier = Modifier.weight(1f), fontSize = 12.sp)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        type = type,
                        startMinute = startState.hour * 60 + startState.minute,
                        endMinute = endState.hour * 60 + endState.minute,
                        days = days.ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) },
                        enabled = enabled,
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
