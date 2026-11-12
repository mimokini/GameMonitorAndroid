package com.gamemonitor.android.domain

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 已安装可启动第三方应用列表（排除系统应用与自身）。 */
object InstalledAppsProvider {

    data class Entry(val packageName: String, val label: String)

    suspend fun load(context: Context): List<Entry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())

        val seen = mutableSetOf<String>()
        resolved.mapNotNull { info ->
            val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            val pkg = appInfo.packageName
            if (pkg == context.packageName || !seen.add(pkg)) return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystem) return@mapNotNull null
            Entry(pkg, runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg))
        }.sortedBy { it.label.lowercase() }
    }

    fun labelOf(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrElse {
        Log.w("GameMonitor", "labelOf failed for $packageName")
        packageName
    }
}
