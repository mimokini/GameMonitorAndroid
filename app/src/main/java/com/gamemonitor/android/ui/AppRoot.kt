package com.gamemonitor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamemonitor.android.data.GameRepository
import com.gamemonitor.android.data.PinManager
import com.gamemonitor.android.data.Snapshot
import com.gamemonitor.android.service.EnforcementManager
import com.gamemonitor.android.service.LauncherIconController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    repository: GameRepository,
    enforcement: EnforcementManager,
    pinManager: PinManager,
    launcherIcon: LauncherIconController,
) {
    val snapshot by repository.snapshotFlow.collectAsState(initial = Snapshot())
    val pinConfigured by pinManager.pinSetFlow.collectAsState(initial = false)
    val requirePinForSettings by pinManager.requirePinForSettingsFlow.collectAsState(initial = true)
    var tab by remember { mutableIntStateOf(0) }
    var settingsUnlocked by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val titles = listOf("条目", "时段", "统计", "样式", "权限")

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("GameMonitor", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    TextButton(onClick = { showAbout = true }) {
                        Text("关于", fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // 60dp 菜单高度 + 系统导航栏 insets：经典三键导航时按钮不再被遮挡；
            // 手势导航下只占用系统给出的手势区 insets，不会额外多出一块空白。
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
            ) {
                // Tab 内再留 6dp 上下 padding，360dp 宽也不挤压文字
                TabRow(selectedTabIndex = tab, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                    titles.forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            modifier = Modifier.fillMaxHeight().padding(vertical = 6.dp),
                            text = { Text(title, fontSize = 13.sp) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> AppsScreen(snapshot = snapshot, repository = repository)
                1 -> ScheduleScreen(snapshot = snapshot, repository = repository)
                2 -> StatsScreen(snapshot = snapshot)
                3 -> TextSettingsScreen(snapshot = snapshot, repository = repository)
                else -> {
                    if (pinConfigured && requirePinForSettings && !settingsUnlocked) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            PinGateScreen(
                                pinManager = pinManager,
                                title = "设置需要管理密码",
                                subtitle = "已开启「进入设置页需要密码」，防止被直接修改管控参数。",
                                onSuccess = { settingsUnlocked = true }
                            )
                        }
                    } else {
                        PermissionsScreen(
                            enforcement = enforcement,
                            snapshot = snapshot,
                            pinManager = pinManager,
                            launcherIcon = launcherIcon
                        )
                    }
                }
            }
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}
