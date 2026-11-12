package com.gamemonitor.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.gamemonitor.android.MainActivity
import com.gamemonitor.android.R

/**
 * 通知渠道与提示通知。
 *
 * 常驻通知文案保持中性（“游戏监控运行中”），不出现“恢复图标”等字样。
 * 图标隐藏时额外挂一个中性标签的 action「管理」，点击进入受管理密码保护的 MainActivity。
 */
class Notifier(private val context: Context) {

    fun ensureChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val monitor = NotificationChannel(
            CHANNEL_MONITOR, "GameMonitor 运行状态", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "前台计时服务常驻通知" }
        val alerts = NotificationChannel(
            CHANNEL_ALERT, "管控提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "拦截、休息、配额提醒" }
        nm.createNotificationChannel(monitor)
        nm.createNotificationChannel(alerts)
    }

    /**
     * @param manageAction 是否显示「管理」action（仅图标已隐藏且已设置管理密码时为 true）。
     * 点击通知正文同样进入该受保护入口；否则只打开主界面。
     */
    fun ongoing(text: String, manageAction: Boolean): Notification {
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_APP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            immutable
        )

        val hint = if (manageAction) {
            "$text\n点击可打开管理（需要管理密码），也可使用下方「管理」按钮"
        } else {
            "$text\n点击可打开管理界面（需要管理密码）"
        }

        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("游戏监控运行中")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(hint))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (manageAction) {
                    addAction(0, "管理", contentIntent)
                }
            }
            .build()
    }

    fun alert(text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GameMonitor")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(ALERT_ID, notification) }
    }

    companion object {
        const val CHANNEL_MONITOR = "gm_monitor"
        const val CHANNEL_ALERT = "gm_alert"
        const val MONITOR_NOTIFICATION_ID = 1001
        private const val ALERT_ID = 1002
        private const val REQUEST_OPEN = 12
    }
}
