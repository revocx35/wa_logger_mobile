package io.github.revocx35.walmobile.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-secret device settings (the logging settings themselves live in the database). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("walm_prefs", Context.MODE_PRIVATE)

    private fun flag(key: String, default: Boolean) = MutableStateFlow(sp.getBoolean(key, default))

    private val _secureScreen = flag(KEY_SECURE_SCREEN, true)
    /** FLAG_SECURE: no screenshots, screen recording or previews in the app switcher. On by default. */
    val secureScreen: StateFlow<Boolean> = _secureScreen.asStateFlow()

    private val _keepAwake = flag(KEY_KEEP_AWAKE, false)
    /** Hold a partial wake lock so WhatsApp Web keeps working instantly with the screen off (battery!). */
    val keepAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()

    private val _autostart = flag(KEY_AUTOSTART, true)
    /** Start logging after a reboot. */
    val autostart: StateFlow<Boolean> = _autostart.asStateFlow()

    private val _notifyDeleted = flag(KEY_NOTIFY_DELETED, true)
    /** Notify when someone deletes a message for everyone. */
    val notifyDeleted: StateFlow<Boolean> = _notifyDeleted.asStateFlow()

    private val _autoLockMinutes = MutableStateFlow(sp.getInt(KEY_AUTOLOCK, 5))
    /** Lock the app this long after it leaves the screen (0 = at once, -1 = only when the process ends). */
    val autoLockMinutes: StateFlow<Int> = _autoLockMinutes.asStateFlow()

    fun setSecureScreen(on: Boolean) = set(KEY_SECURE_SCREEN, on, _secureScreen)
    fun setKeepAwake(on: Boolean) = set(KEY_KEEP_AWAKE, on, _keepAwake)
    fun setAutostart(on: Boolean) = set(KEY_AUTOSTART, on, _autostart)
    fun setNotifyDeleted(on: Boolean) = set(KEY_NOTIFY_DELETED, on, _notifyDeleted)

    fun setAutoLockMinutes(m: Int) {
        sp.edit().putInt(KEY_AUTOLOCK, m).apply()
        _autoLockMinutes.value = m
    }

    private fun set(key: String, on: Boolean, flow: MutableStateFlow<Boolean>) {
        sp.edit().putBoolean(key, on).apply()
        flow.value = on
    }

    private companion object {
        const val KEY_SECURE_SCREEN = "secure_screen"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_AUTOSTART = "autostart"
        const val KEY_NOTIFY_DELETED = "notify_deleted"
        const val KEY_AUTOLOCK = "autolock_minutes"
    }
}
