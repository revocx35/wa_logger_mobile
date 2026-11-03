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
    /** Lock the app this long after it leaves the screen (0 = at once, -1 = never while the app process runs). */
    val autoLockMinutes: StateFlow<Int> = _autoLockMinutes.asStateFlow()

    private val _autoSave = flag(KEY_AUTOSAVE, false)
    /** Copy new photos to the phone's gallery as they are downloaded (media/AutoSave.kt). Off by default. */
    val autoSave: StateFlow<Boolean> = _autoSave.asStateFlow()
    private val _autoSaveVideos = flag(KEY_AUTOSAVE_VIDEOS, false)
    val autoSaveVideos: StateFlow<Boolean> = _autoSaveVideos.asStateFlow()
    private val _autoSaveStatus = flag(KEY_AUTOSAVE_STATUS, false)
    val autoSaveStatus: StateFlow<Boolean> = _autoSaveStatus.asStateFlow()
    private val _autoSaveOnlyDisappearing = flag(KEY_AUTOSAVE_DISAPPEARING, false)
    val autoSaveOnlyDisappearing: StateFlow<Boolean> = _autoSaveOnlyDisappearing.asStateFlow()
    private val _autoSaveCount = MutableStateFlow(sp.getInt(KEY_AUTOSAVE_COUNT, 0))
    val autoSaveCount: StateFlow<Int> = _autoSaveCount.asStateFlow()

    fun setAutoSave(on: Boolean) = set(KEY_AUTOSAVE, on, _autoSave)
    fun setAutoSaveVideos(on: Boolean) = set(KEY_AUTOSAVE_VIDEOS, on, _autoSaveVideos)
    fun setAutoSaveStatus(on: Boolean) = set(KEY_AUTOSAVE_STATUS, on, _autoSaveStatus)
    fun setAutoSaveOnlyDisappearing(on: Boolean) = set(KEY_AUTOSAVE_DISAPPEARING, on, _autoSaveOnlyDisappearing)

    @Synchronized
    fun addAutoSaved() {
        val n = sp.getInt(KEY_AUTOSAVE_COUNT, 0) + 1
        sp.edit().putInt(KEY_AUTOSAVE_COUNT, n).apply()
        _autoSaveCount.value = n
    }

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
        const val KEY_AUTOSAVE = "autosave"
        const val KEY_AUTOSAVE_VIDEOS = "autosave_videos"
        const val KEY_AUTOSAVE_STATUS = "autosave_status"
        const val KEY_AUTOSAVE_DISAPPEARING = "autosave_disappearing"
        const val KEY_AUTOSAVE_COUNT = "autosave_count"
    }
}
