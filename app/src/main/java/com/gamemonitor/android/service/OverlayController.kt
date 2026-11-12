package com.gamemonitor.android.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.gamemonitor.android.data.AppSettings

/**
 * 全屏悬浮提示窗口：只做展示，不拦截触摸（从不吞掉用户手势）。
 * 需要 SYSTEM_ALERT_WINDOW，只能在主线程调用。
 */
class OverlayController(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var rootView: LinearLayout? = null
    private var textView: TextView? = null
    private var currentKey: String? = null

    fun show(key: String, text: String, settings: AppSettings): Boolean {
        if (!runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)) return false
        val wm = windowManager ?: return false
        return runCatching {
            if (rootView == null) {
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(28), dp(24), dp(28))
                }
                val label = TextView(context).apply {
                    gravity = Gravity.CENTER
                    setLineSpacing(10f, 1f)
                }
                container.addView(label)
                wm.addView(container, layoutParams())
                rootView = container
                textView = label
            }
            applyStyle(settings)
            textView?.text = text
            currentKey = key
            true
        }.onFailure { Log.w(TAG, "overlay show failed", it) }.getOrDefault(false)
    }

    fun update(key: String, text: String, settings: AppSettings) {
        if (currentKey != key || rootView == null) {
            show(key, text, settings)
            return
        }
        runCatching {
            applyStyle(settings)
            textView?.text = text
        }.onFailure { Log.w(TAG, "overlay update failed", it) }
    }

    fun dismiss() {
        val wm = windowManager ?: return
        runCatching { rootView?.let { wm.removeView(it) } }
            .onFailure { Log.w(TAG, "overlay dismiss failed", it) }
        rootView = null
        textView = null
        currentKey = null
    }

    private fun applyStyle(settings: AppSettings) {
        val alpha = settings.bgAlphaPercent.coerceIn(0, 100) * 255 / 100
        rootView?.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
        val c = settings.textColor.toInt()
        textView?.apply {
            textSize = settings.fontSizeSp.toFloat()
            setTextColor(Color.argb(255, Color.red(c), Color.green(c), Color.blue(c)))
        }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "GameMonitor.Overlay"
    }
}
