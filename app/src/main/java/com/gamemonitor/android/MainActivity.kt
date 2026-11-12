package com.gamemonitor.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gamemonitor.android.service.MonitorService
import com.gamemonitor.android.ui.AppRoot
import com.gamemonitor.android.ui.PinGateScreen
import com.gamemonitor.android.ui.theme.GameMonitorTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 当前 Activity 实例内的解锁状态；切到后台（onStop）即重新锁定。 */
    private val unlockedState = mutableStateOf(false)

    /** 快捷方式请求隐藏图标时，先通过密码门，验证成功后再执行。 */
    private var pendingHideIcon = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GameMonitorApp
        val iconHidden = app.launcherIcon.isHidden()
        pendingHideIcon = intent?.action == ACTION_HIDE_ICON

        setContent {
            GameMonitorTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    // null = 尚未读完 DataStore，避免无密码时误挡主界面
                    var pinConfigured by remember { mutableStateOf<Boolean?>(null) }
                    val unlocked = unlockedState.value

                    LaunchedEffect(Unit) {
                        val configured = app.pinManager.isPinSet()
                        pinConfigured = configured
                        if (!configured && pendingHideIcon) {
                            pendingHideIcon = false
                            hideIcon()
                        }
                    }

                    when {
                        pinConfigured == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator()
                        }

                        // 只要设置过管理密码，任何入口（桌面图标/通知/快捷方式）都必须先验证。
                        pinConfigured == true && !unlocked -> Box(
                            Modifier.fillMaxSize().padding(16.dp),
                            Alignment.Center
                        ) {
                            PinGateScreen(
                                pinManager = app.pinManager,
                                title = if (iconHidden) "图标已隐藏，需要管理密码" else "需要管理密码",
                                subtitle = "无论从桌面图标、通知还是快捷方式进入，都需验证管理密码；切出应用后会重新锁定。",
                                onSuccess = {
                                    unlockedState.value = true
                                    if (pendingHideIcon) {
                                        pendingHideIcon = false
                                        hideIcon()
                                    }
                                }
                            )
                        }

                        else -> AppRoot(
                            repository = app.repository,
                            enforcement = app.enforcement,
                            pinManager = app.pinManager,
                            launcherIcon = app.launcherIcon
                        )
                    }
                }
            }
        }
        requestNotificationPermissionIfNeeded()
        runCatching { MonitorService.start(this) }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingHideIcon = intent.action == ACTION_HIDE_ICON
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // 任何离开前台的切换都视为锁屏：回来必须重新输入管理密码，不留长期免密窗口。
        unlockedState.value = false
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_HIDE_ICON -> {
                if (unlockedState.value) hideIcon() else pendingHideIcon = true
            }

            ACTION_OPEN_APP -> Unit
        }
    }

    /** 桌面长按快捷方式「隐藏图标」：未设置管理密码时直接拒绝，不允许隐藏。 */
    private fun hideIcon() {
        val app = application as GameMonitorApp
        lifecycleScope.launch {
            if (!app.pinManager.isPinSet()) {
                Log.w(TAG, "hide icon rejected: admin PIN not configured")
                Toast.makeText(
                    this@MainActivity,
                    "请先在设置中设置管理密码",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            if (!app.launcherIcon.hide()) {
                Toast.makeText(this@MainActivity, "隐藏失败，请稍后重试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            Log.i(TAG, "launcher icon hidden by shortcut")
            MonitorService.refreshNotification(this@MainActivity)
            Toast.makeText(
                this@MainActivity,
                "桌面图标已隐藏；可在通知栏点击「管理」，验证管理密码后到「权限」页恢复",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val ACTION_HIDE_ICON = "com.gamemonitor.android.ACTION_HIDE_ICON"
        const val ACTION_OPEN_APP = "com.gamemonitor.android.ACTION_OPEN_APP"
        private const val TAG = "GameMonitor.MainActivity"
    }
}
