package com.gamemonitor.android.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom

/** PIN 校验：4~8 位数字，SHA-256(pin+salt) 落盘；连续错误 3 次锁定 30 秒。 */
class PinManager(private val repository: SecurityRepository) {

    sealed class Result {
        object Success : Result()
        object NotConfigured : Result()
        data class Wrong(val remainingAttempts: Int) : Result()
        data class Locked(val remainingMillis: Long) : Result()
    }

    private var failures = 0

    val pinSetFlow: Flow<Boolean> = repository.pinConfigured
    val requirePinForSettingsFlow: Flow<Boolean> = repository.requirePinForSettings

    suspend fun isPinSet(): Boolean = repository.readPinRecord() != null

    suspend fun requirePinForSettings(): Boolean = repository.requirePinForSettings.first()

    suspend fun setRequirePinForSettings(require: Boolean) =
        repository.setRequirePinForSettings(require)

    suspend fun setPin(pin: String, requireForSettings: Boolean): Boolean {
        if (!isValidPin(pin)) return false
        val salt = randomSalt()
        repository.savePin(SecurityRepository.PinRecord(sha256(pin, salt), salt), requireForSettings)
        repository.setLockoutUntil(0L)
        failures = 0
        Log.i(TAG, "admin PIN updated, requireForSettings=$requireForSettings")
        return true
    }

    suspend fun verify(pin: String): Result {
        val now = System.currentTimeMillis()
        val lockUntil = repository.readLockoutUntil()
        if (lockUntil > now) {
            Log.w(TAG, "PIN verify rejected: lockout remaining=${lockUntil - now}ms")
            return Result.Locked(lockUntil - now)
        }
        val record = repository.readPinRecord() ?: return Result.NotConfigured
        val ok = MessageDigest.isEqual(
            record.hash.toByteArray(),
            sha256(pin, record.salt).toByteArray()
        )
        if (ok) {
            failures = 0
            repository.setLockoutUntil(0L)
            Log.i(TAG, "PIN verified")
            return Result.Success
        }
        failures++
        Log.w(TAG, "PIN mismatch, attempt=$failures/$MAX_FAILURES")
        if (failures >= MAX_FAILURES) {
            failures = 0
            val until = now + LOCKOUT_MS
            repository.setLockoutUntil(until)
            Log.w(TAG, "PIN locked out for 30s, until=$until")
            return Result.Locked(LOCKOUT_MS)
        }
        return Result.Wrong(MAX_FAILURES - failures)
    }

    suspend fun lockoutRemaining(): Long {
        val remaining = repository.readLockoutUntil() - System.currentTimeMillis()
        return remaining.coerceAtLeast(0L)
    }

    companion object {
        private const val TAG = "GameMonitor.Pin"
        const val MAX_FAILURES = 3
        const val LOCKOUT_MS = 30_000L

        fun isValidPin(pin: String): Boolean =
            pin.length in 4..8 && pin.all { it.isDigit() }

        private fun randomSalt(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        private fun sha256(pin: String, salt: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest((pin + salt).toByteArray())
            return Base64.encodeToString(digest, Base64.NO_WRAP)
        }
    }
}
