package io.github.sim7b.tele.data.storage

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A profile of the TV: its own favorites, positions, my list, collections and languages. A [kids] profile always
 * has the locks of the parental control applied (they cannot be lifted from it). A [pinHash] asks a 4-digit code to
 * enter the profile. The [MAIN] profile is the one the app had before profiles: its data stay where they were.
 */
data class Profile(
    val id: String,
    val name: String,
    val kids: Boolean = false,
    val pinSalt: String? = null,
    val pinHash: String? = null
) {
    val hasPin: Boolean get() = pinHash != null
    val isMain: Boolean get() = id == MAIN

    companion object {
        const val MAIN = "main"
    }
}

/** The profiles of the TV and the one in use, kept in the preferences. */
class ProfileStore(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {

    private var failures = 0
    private var blockedUntil = 0L

    private val _profiles = MutableStateFlow(fromJson(prefs.getString(KEY_PROFILES, null)))
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _current = MutableStateFlow(
        _profiles.value.firstOrNull { it.id == prefs.getString(KEY_CURRENT, null) } ?: _profiles.value.first()
    )
    val current: StateFlow<Profile> = _current.asStateFlow()

    /** Adds a profile; [pin] (4 digits) optional. Null when the name is empty or there are too many. */
    fun add(name: String, kids: Boolean, pin: String?): Profile? {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty() || _profiles.value.size >= MAX_PROFILES) return null
        if (pin != null && !ParentalControl.isValidPin(pin)) return null
        val profile = withPin(Profile(newId(), clean, kids), pin)
        save(_profiles.value + profile)
        return profile
    }

    /** Changes the name, the kids flag or the code of a profile (null [pin]: kept, empty: removed). */
    fun update(id: String, name: String? = null, kids: Boolean? = null, pin: String? = null): Boolean {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return false
        if (pin != null && pin.isNotEmpty() && !ParentalControl.isValidPin(pin)) return false
        var updated = profile.copy(
            name = name?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() } ?: profile.name,
            kids = kids ?: profile.kids
        )
        if (pin != null) updated = withPin(updated, pin.takeIf { it.isNotEmpty() })
        save(_profiles.value.map { if (it.id == id) updated else it })
        if (_current.value.id == id) _current.value = updated
        return true
    }

    /** Removes a profile (never the main one); its data are removed by the caller. */
    fun remove(id: String): Boolean {
        if (id == Profile.MAIN || _profiles.value.none { it.id == id }) return false
        save(_profiles.value.filterNot { it.id == id })
        if (_current.value.id == id) select(Profile.MAIN)
        return true
    }

    /** Whether [pin] opens [profile] (always without a code); false while waiting after too many wrong codes. */
    fun checkPin(profile: Profile, pin: String): Boolean {
        val hash = profile.pinHash ?: return true
        if (waitMs() > 0) return false
        val salt = profile.pinSalt ?: return false
        if (MessageDigest.isEqual(ParentalControl.hash(pin, salt).toByteArray(), hash.toByteArray())) {
            failures = 0
            return true
        }
        failures++
        if (failures >= ParentalControl.MAX_ATTEMPTS) {
            failures = 0
            blockedUntil = clock() + ParentalControl.COOLDOWN_MS
        }
        return false
    }

    /** Milliseconds to wait before another code (0: may try). */
    fun waitMs(): Long = (blockedUntil - clock()).coerceAtLeast(0)

    fun select(id: String): Profile {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: _profiles.value.first()
        prefs.edit { putString(KEY_CURRENT, profile.id) }
        _current.value = profile
        return profile
    }

    private fun save(list: List<Profile>) {
        prefs.edit { putString(KEY_PROFILES, toJson(list)) }
        _profiles.value = list
    }

    private fun withPin(profile: Profile, pin: String?): Profile {
        if (pin == null) return profile.copy(pinSalt = null, pinHash = null)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return profile.copy(pinSalt = salt, pinHash = ParentalControl.hash(pin, salt))
    }

    companion object {
        const val MAX_PROFILES = 8
        private const val MAX_NAME = 24
        private const val KEY_PROFILES = "profiles"
        private const val KEY_CURRENT = "profile_current"

        private fun newId(): String = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** Profiles saved by [toJson], the main one always first (also when nothing is saved). */
        fun fromJson(raw: String?): List<Profile> {
            val saved = try {
                val array = JSONArray(raw ?: "[]")
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = obj.optString("id").takeIf { it.matches(ID) } ?: return@mapNotNull null
                    Profile(
                        id = id,
                        name = obj.optString("name").take(MAX_NAME),
                        kids = obj.optBoolean("kids", false),
                        pinSalt = obj.optString("salt").takeIf { it.isNotEmpty() },
                        pinHash = obj.optString("hash").takeIf { it.isNotEmpty() }
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
            val main = saved.firstOrNull { it.isMain } ?: Profile(Profile.MAIN, "")
            return (listOf(main) + saved.filterNot { it.isMain }).distinctBy { it.id }.take(MAX_PROFILES)
        }

        fun toJson(list: List<Profile>): String = JSONArray().apply {
            list.forEach { profile ->
                put(
                    JSONObject().put("id", profile.id).put("name", profile.name).put("kids", profile.kids)
                        .putOpt("salt", profile.pinSalt).putOpt("hash", profile.pinHash)
                )
            }
        }.toString()

        private val ID = Regex("^(main|[0-9a-f]{12})$")
    }
}
