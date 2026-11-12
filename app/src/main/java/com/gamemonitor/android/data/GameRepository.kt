package com.gamemonitor.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "game_monitor")

private val KEY_APPS = stringPreferencesKey("apps_json")
private val KEY_RULES = stringPreferencesKey("rules_json")
private val KEY_SETTINGS = stringPreferencesKey("settings_json")
private val KEY_USAGE = stringPreferencesKey("usage_json")

/** DataStore + JSON 持久化：条目、时段规则、设置、当日用量。 */
class GameRepository(private val context: Context) {

    val snapshotFlow: Flow<Snapshot> = context.dataStore.data.map { decode(it) }

    suspend fun readSnapshot(): Snapshot = snapshotFlow.first()

    suspend fun saveApps(apps: List<MonitoredApp>) {
        context.dataStore.edit { prefs -> prefs[KEY_APPS] = encodeApps(apps).toString() }
    }

    suspend fun saveRules(rules: List<ScheduleRule>) {
        context.dataStore.edit { prefs -> prefs[KEY_RULES] = encodeRules(rules).toString() }
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { prefs -> prefs[KEY_SETTINGS] = encodeSettings(settings).toString() }
    }

    suspend fun saveUsage(records: Collection<UsageRecord>) {
        context.dataStore.edit { prefs -> prefs[KEY_USAGE] = encodeUsage(records).toString() }
    }

    // ---------------------------------------------------------------- decode

    private fun decode(prefs: Preferences): Snapshot {
        val apps = runCatching { decodeApps(prefs[KEY_APPS]) }.getOrDefault(emptyList())
        val rules = runCatching { decodeRules(prefs[KEY_RULES]) }.getOrDefault(emptyList())
        val settings = runCatching { decodeSettings(prefs[KEY_SETTINGS]) }.getOrNull() ?: AppSettings()
        val today = TimeUtils.todayKey()
        val usage = runCatching { decodeUsage(prefs[KEY_USAGE]) }
            .getOrDefault(emptyList())
            .filter { it.date == today }
        return Snapshot(apps = apps.sortedBy { it.order }, rules = rules, settings = settings, usage = usage)
    }

    private fun decodeApps(raw: String?): List<MonitoredApp> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    MonitoredApp(
                        packageName = o.optString("pkg"),
                        label = o.optString("label", o.optString("pkg")),
                        singleLimitMinutes = o.optInt("single", 0),
                        dailyQuotaMinutes = o.optInt("quota", 0),
                        intervalMinutes = o.optInt("interval", 0),
                        enabled = o.optBoolean("enabled", true),
                        order = o.optInt("order", i),
                    )
                )
            }
        }.filter { it.packageName.isNotBlank() }
    }

    private fun encodeApps(apps: List<MonitoredApp>): JSONArray {
        val arr = JSONArray()
        apps.forEachIndexed { index, app ->
            arr.put(
                JSONObject().apply {
                    put("pkg", app.packageName)
                    put("label", app.label)
                    put("single", app.singleLimitMinutes)
                    put("quota", app.dailyQuotaMinutes)
                    put("interval", app.intervalMinutes)
                    put("enabled", app.enabled)
                    put("order", index)
                }
            )
        }
        return arr
    }

    private fun decodeRules(raw: String?): List<ScheduleRule> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val daysArr = o.optJSONArray("days") ?: JSONArray()
                val days = buildSet {
                    for (d in 0 until daysArr.length()) add(daysArr.optInt(d))
                }
                add(
                    ScheduleRule(
                        id = o.optString("id").ifBlank { "rule-$i" },
                        type = if (o.optString("type") == "ALLOW") ScheduleType.ALLOW else ScheduleType.BLOCK,
                        startMinute = o.optInt("start", 480),
                        endMinute = o.optInt("end", 1320),
                        days = days.ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) },
                        enabled = o.optBoolean("enabled", true),
                    )
                )
            }
        }
    }

    private fun encodeRules(rules: List<ScheduleRule>): JSONArray {
        val arr = JSONArray()
        rules.forEach { rule ->
            val days = JSONArray()
            rule.days.sorted().forEach { days.put(it) }
            arr.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("type", rule.type.name)
                    put("start", rule.startMinute)
                    put("end", rule.endMinute)
                    put("days", days)
                    put("enabled", rule.enabled)
                }
            )
        }
        return arr
    }

    private fun decodeSettings(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        val o = JSONObject(raw)
        val def = AppSettings()
        return AppSettings(
            totalDailyLimitMinutes = o.optInt("totalLimit", def.totalDailyLimitMinutes),
            countdownSeconds = o.optInt("countdown", def.countdownSeconds),
            countdownText = o.optString("countdownText", def.countdownText),
            cooldownText = o.optString("cooldownText", def.cooldownText),
            blockedText = o.optString("blockedText", def.blockedText),
            fontSizeSp = o.optInt("fontSize", def.fontSizeSp),
            textColor = o.optLong("textColor", def.textColor),
            bgAlphaPercent = o.optInt("bgAlpha", def.bgAlphaPercent),
            autoForceStopEnabled = o.optBoolean("autoForceStop", def.autoForceStopEnabled),
        )
    }

    private fun encodeSettings(s: AppSettings): JSONObject = JSONObject().apply {
        put("totalLimit", s.totalDailyLimitMinutes)
        put("countdown", s.countdownSeconds)
        put("countdownText", s.countdownText)
        put("cooldownText", s.cooldownText)
        put("blockedText", s.blockedText)
        put("fontSize", s.fontSizeSp)
        put("textColor", s.textColor)
        put("bgAlpha", s.bgAlphaPercent)
        put("autoForceStop", s.autoForceStopEnabled)
    }

    private fun decodeUsage(raw: String?): List<UsageRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("pkg")
                if (pkg.isBlank()) continue
                add(
                    UsageRecord(
                        packageName = pkg,
                        date = o.optString("date"),
                        usedSeconds = o.optLong("used", 0L),
                        sessions = o.optInt("sessions", 0),
                        firstOpenMillis = if (o.has("firstOpen") && !o.isNull("firstOpen")) o.optLong("firstOpen") else null,
                        lastCloseMillis = if (o.has("lastClose") && !o.isNull("lastClose")) o.optLong("lastClose") else null,
                    )
                )
            }
        }
    }

    private fun encodeUsage(records: Collection<UsageRecord>): JSONArray {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("pkg", r.packageName)
                    put("date", r.date)
                    put("used", r.usedSeconds)
                    put("sessions", r.sessions)
                    put("firstOpen", r.firstOpenMillis ?: JSONObject.NULL)
                    put("lastClose", r.lastCloseMillis ?: JSONObject.NULL)
                }
            )
        }
        return arr
    }
}
