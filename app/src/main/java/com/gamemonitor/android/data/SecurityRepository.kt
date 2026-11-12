package com.gamemonitor.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.securityStore: DataStore<Preferences> by preferencesDataStore(name = "game_monitor_security")

private val KEY_PIN_HASH = stringPreferencesKey("pin_hash")
private val KEY_PIN_SALT = stringPreferencesKey("pin_salt")
private val KEY_REQUIRE_PIN = booleanPreferencesKey("settings_require_pin")
private val KEY_LOCKOUT_UNTIL = longPreferencesKey("lockout_until")

/** 管理密码与锁定状态（独立于业务配置的 DataStore）。 */
class SecurityRepository(private val context: Context) {

    data class PinRecord(val hash: String, val salt: String)

    val requirePinForSettings: Flow<Boolean> =
        context.securityStore.data.map { it[KEY_REQUIRE_PIN] ?: true }

    /** 是否已设置管理密码（供 Compose 门禁判断）。 */
    val pinConfigured: Flow<Boolean> =
        context.securityStore.data.map { it[KEY_PIN_HASH] != null }

    suspend fun readPinRecord(): PinRecord? {
        val prefs = context.securityStore.data.first()
        val hash = prefs[KEY_PIN_HASH] ?: return null
        val salt = prefs[KEY_PIN_SALT] ?: return null
        return PinRecord(hash, salt)
    }

    suspend fun savePin(record: PinRecord, requirePinForSettings: Boolean) {
        context.securityStore.edit {
            it[KEY_PIN_HASH] = record.hash
            it[KEY_PIN_SALT] = record.salt
            it[KEY_REQUIRE_PIN] = requirePinForSettings
        }
    }

    suspend fun setRequirePinForSettings(require: Boolean) {
        context.securityStore.edit { it[KEY_REQUIRE_PIN] = require }
    }

    suspend fun readLockoutUntil(): Long =
        context.securityStore.data.first()[KEY_LOCKOUT_UNTIL] ?: 0L

    suspend fun setLockoutUntil(value: Long) {
        context.securityStore.edit { it[KEY_LOCKOUT_UNTIL] = value }
    }
}
