package io.github.sim7b.tele.data.storage

import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Parental control: a 4-digit code (kept as a salted hash, never in clear), the live categories it locks,
 * and the movies and series. Locked content is left out everywhere until the code is entered; it locks again
 * when the app goes to the background ([lock]). After [MAX_ATTEMPTS] wrong codes, [COOLDOWN_MS] to wait.
 * Kept on this TV: not part of the configuration sent to another TV.
 */
class ParentalControl(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {

    private val _hasPin = MutableStateFlow(prefs.getString(KEY_HASH, null) != null)
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()

    private val _lockedGroups = MutableStateFlow(prefs.getStringSet(KEY_GROUPS, emptySet()).orEmpty().toSet())
    /** Live categories locked by the code (displayed names, as the hidden categories). */
    val lockedGroups: StateFlow<Set<String>> = _lockedGroups.asStateFlow()

    private val _vodLocked = MutableStateFlow(prefs.getBoolean(KEY_VOD, false))
    /** Movies and series locked by the code. */
    val vodLocked: StateFlow<Boolean> = _vodLocked.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    /** Code entered since the app came to the foreground (always true without a code). */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private var failures = 0
    private var blockedUntil = 0L

    /** Whether the locks apply now: a code set and not entered. */
    fun active(): Boolean = _hasPin.value && !_unlocked.value

    /** Movies and series left out now. */
    fun vodHiddenNow(): Boolean = active() && _vodLocked.value

    /** The categories left out now. */
    fun hiddenGroupsNow(): Set<String> = if (active()) _lockedGroups.value else emptySet()

    /** Milliseconds to wait before another try (0: may try). */
    fun waitMs(): Long = (blockedUntil - clock()).coerceAtLeast(0)

    /** Checks [pin]; right, the locks are lifted until [lock]. False when wrong or while waiting. */
    fun unlock(pin: String): Boolean {
        if (!_hasPin.value) return true
        if (waitMs() > 0) return false
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        if (!MessageDigest.isEqual(hash(pin, salt).toByteArray(), prefs.getString(KEY_HASH, null).orEmpty().toByteArray())) {
            failures++
            if (failures >= MAX_ATTEMPTS) {
                failures = 0
                blockedUntil = clock() + COOLDOWN_MS
            }
            return false
        }
        failures = 0
        _unlocked.value = true
        return true
    }

    fun lock() {
        _unlocked.value = false
    }

    /** Sets (or replaces) the code; only a 4-digit one. The control stays open for the one setting it. */
    fun setPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        val salt = newSalt()
        prefs.edit { putString(KEY_SALT, salt).putString(KEY_HASH, hash(pin, salt)) }
        _hasPin.value = true
        _unlocked.value = true
        return true
    }

    /** Removes the code and every lock. */
    fun clear() {
        prefs.edit { remove(KEY_SALT).remove(KEY_HASH).remove(KEY_GROUPS).remove(KEY_VOD) }
        _hasPin.value = false
        _lockedGroups.value = emptySet()
        _vodLocked.value = false
        _unlocked.value = false
    }

    fun setGroupLocked(group: String, locked: Boolean) = setGroupsLocked(listOf(group), locked)

    fun setGroupsLocked(groups: Collection<String>, locked: Boolean) {
        val current = if (locked) _lockedGroups.value + groups else _lockedGroups.value - groups.toSet()
        prefs.edit { putStringSet(KEY_GROUPS, current) }
        _lockedGroups.value = current
    }

    fun setVodLocked(locked: Boolean) {
        prefs.edit { putBoolean(KEY_VOD, locked) }
        _vodLocked.value = locked
    }

    /** The categories left out, as they change (code set or entered, categories chosen). */
    fun hiddenGroupsFlow() = combine(_hasPin, _unlocked, _lockedGroups) { hasPin, unlocked, groups ->
        if (hasPin && !unlocked) groups else emptySet()
    }

    /** Movies and series left out, as it changes. */
    fun vodHiddenFlow() = combine(_hasPin, _unlocked, _vodLocked) { hasPin, unlocked, vod -> hasPin && !unlocked && vod }

    companion object {
        const val PIN_LENGTH = 4
        const val MAX_ATTEMPTS = 5
        const val COOLDOWN_MS = 60_000L
        private const val KEY_SALT = "parental_salt"
        private const val KEY_HASH = "parental_hash"
        private const val KEY_GROUPS = "parental_groups"
        private const val KEY_VOD = "parental_vod"

        fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

        internal fun hash(pin: String, salt: String): String =
            MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray()).joinToString("") { "%02x".format(it) }

        private fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
