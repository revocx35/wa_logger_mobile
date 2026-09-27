package io.github.revocx35.walmobile.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.revocx35.walmobile.core.ApiException
import io.github.revocx35.walmobile.core.WaState
import io.github.revocx35.walmobile.engine.WaService
import io.github.revocx35.walmobile.security.VaultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-level state, like the web's route guard: set up → unlock/recover → recovery key → link
 * WhatsApp → main. Starts the logger service once the app is set up, and locks the app a while after
 * it leaves the screen (Settings → Unlock).
 */
class AppController(private val graph: AppGraph) {
    val vault: StateFlow<VaultState> get() = graph.vault.state
    val wa get() = graph.engine.status

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** null while loading. */
    private val _onboarded = MutableStateFlow<Boolean?>(null)
    val onboarded: StateFlow<Boolean?> = _onboarded.asStateFlow()

    /** The recovery key after setup/recovery (memory only, shown once). */
    val pendingRecoveryKey = MutableStateFlow<String?>(null)

    private var lockJob: Job? = null

    init {
        graph.scope.launch {
            graph.vault.load()
            _onboarded.value = withContext(Dispatchers.IO) { graph.repo.getState(ONBOARDED_KEY) != null }
            if (graph.vault.writer() != null) WaService.start(graph.context)
        }
        graph.scope.launch {
            graph.vault.state.collect { st ->
                if (st == VaultState.Unlocked) {
                    if (_session.value == null) _session.value = Session(graph)
                } else {
                    _session.value?.close()
                    _session.value = null
                }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lockJob?.cancel()
            }

            override fun onStop(owner: LifecycleOwner) {
                val minutes = graph.prefs.autoLockMinutes.value
                if (minutes < 0 || !graph.vault.unlocked) return
                lockJob?.cancel()
                lockJob = graph.scope.launch {
                    delay(minutes * 60_000L)
                    graph.vault.lock()
                }
            }
        })
    }

    suspend fun create(password: String) {
        val key = graph.vault.create(password)
        pendingRecoveryKey.value = key
        WaService.start(graph.context)
    }

    suspend fun unlock(password: String) = graph.vault.unlock(password)

    suspend fun recover(recoveryKey: String, password: String) {
        pendingRecoveryKey.value = graph.vault.recover(recoveryKey, password)
    }

    fun lock() {
        graph.vault.lock()
    }

    /** Onboarding is done once WhatsApp is linked (or the owner insists). */
    suspend fun completeOnboarding(force: Boolean = false) {
        val s = graph.engine.status.value.state
        if (!force && s != WaState.Ready && s != WaState.Syncing) throw ApiException.conflict("WhatsApp is not linked yet.")
        withContext(Dispatchers.IO) {
            graph.repo.setState(ONBOARDED_KEY, System.currentTimeMillis().toString())
            graph.repo.audit("onboarding_complete")
        }
        _onboarded.value = true
    }

    companion object {
        const val ONBOARDED_KEY = "onboarding_complete"
    }
}
