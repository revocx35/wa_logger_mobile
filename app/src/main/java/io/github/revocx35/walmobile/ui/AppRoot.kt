package io.github.revocx35.walmobile.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.revocx35.walmobile.core.Formatter
import io.github.revocx35.walmobile.data.AppGraph
import io.github.revocx35.walmobile.data.Session
import io.github.revocx35.walmobile.engine.WaEngine
import io.github.revocx35.walmobile.media.AudioController
import io.github.revocx35.walmobile.security.Biometric
import io.github.revocx35.walmobile.security.VaultState
import io.github.revocx35.walmobile.ui.components.Spinner
import io.github.revocx35.walmobile.ui.screens.AuthActions
import io.github.revocx35.walmobile.ui.screens.LoginScreen
import io.github.revocx35.walmobile.ui.screens.RecoverScreen
import io.github.revocx35.walmobile.ui.screens.SetupLinkScreen
import io.github.revocx35.walmobile.ui.screens.SetupRecoveryScreen
import io.github.revocx35.walmobile.ui.screens.SignupScreen
import io.github.revocx35.walmobile.ui.screens.WaLink
import io.github.revocx35.walmobile.ui.theme.Wa
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private class SessionMediaHost(private val s: Session) : MediaHost {
    override val imageLoader: ImageLoader get() = s.imageLoader
    override val audio: AudioController get() = s.audio
    override fun url(relative: String?): String? = s.mediaUrl(relative)
}

/** The engine as the WhatsApp screens see it. */
class EngineLink(private val engine: WaEngine) : WaLink {
    override val qr: StateFlow<String?> get() = engine.qr
    override val borrowed: StateFlow<Boolean> get() = engine.borrowed
    override suspend fun restart() = engine.restart()
    override suspend fun unlink() = engine.logout()
    override suspend fun pairingCode(phone: String): String = engine.requestPairingCode(phone)
    override suspend fun cancelPairing() = engine.cancelPairing()
    override fun refreshQr() = engine.refreshQr()
    override fun borrow(activity: Context): WebView? = engine.borrowView(activity)
    override fun giveBack(view: WebView) = engine.returnView(view)
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize().background(Wa.colors.bg), contentAlignment = Alignment.Center) { Spinner(32.dp) }
}

/**
 * Decides which screen the owner belongs on, like the web's route guard: set up → unlock/recover →
 * recovery key → link WhatsApp → main.
 */
@Composable
fun AppRoot(graph: AppGraph, openTarget: MutableStateFlow<String?>) {
    val ctrl = graph.controller
    val vault by ctrl.vault.collectAsState()
    val session by ctrl.session.collectAsState()
    val onboarded by ctrl.onboarded.collectAsState()
    val wa by ctrl.wa.collectAsState()
    val pendingKey by ctrl.pendingRecoveryKey.collectAsState()
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val formatter = remember(config) { Formatter(config.locales[0], java.time.ZoneId.systemDefault(), DateFormat.is24HourFormat(context)) }
    var recoverMode by rememberSaveable { mutableStateOf(false) }
    val link = remember { EngineLink(graph.engine) }

    var bioReady by remember { mutableStateOf(false) }
    LaunchedEffect(vault) {
        bioReady = vault == VaultState.Locked && graph.vault.biometricBlob() != null && Biometric.available(context)
    }
    val auth = remember(bioReady) {
        AuthActions(
            create = { ctrl.create(it) },
            unlock = { ctrl.unlock(it) },
            recover = { key, pw -> ctrl.recover(key, pw) },
            biometric = if (bioReady) ({ Biometric.unlock(context, graph.vault) }) else null,
        )
    }

    val s = session
    val host = remember(s) { s?.let { SessionMediaHost(it) } }
    CompositionLocalProvider(LocalFormatter provides formatter, LocalMediaHost provides host) {
        when {
            vault == VaultState.Loading || onboarded == null -> Loading()
            vault == VaultState.NoOwner -> SignupScreen(auth)
            vault == VaultState.Locked -> if (recoverMode) RecoverScreen(auth) { recoverMode = false } else LoginScreen(auth) { recoverMode = true }
            s == null -> Loading()
            pendingKey != null -> SetupRecoveryScreen(pendingKey) { ctrl.pendingRecoveryKey.value = null; recoverMode = false }
            onboarded != true -> {
                AskForNotifications()
                SetupLinkScreen(link, wa, onComplete = { ctrl.completeOnboarding(it) }, onLock = { ctrl.lock() })
            }
            else -> MainShell(graph, s, wa, link, openTarget, onLock = { ctrl.lock() })
        }
    }

    // A crash or "not responding" since the last start: offer the report (stays on the phone unless copied).
    var report by remember { mutableStateOf(graph.diagnostics.unseen()) }
    report?.let {
        io.github.revocx35.walmobile.ui.components.ReportDialog(
            "wa_logger stopped unexpectedly",
            "Something went wrong the last time the app ran. The report below contains only technical details (no messages, names or passwords). Copy it and send it to whoever maintains your app.",
            it,
            onClose = { graph.diagnostics.markSeen(); report = null },
        )
    }
}

/** Android 13+: the status and "unlinked"/"deleted" notifications need permission. Asked once during setup. */
@Composable
private fun AskForNotifications() {
    if (Build.VERSION.SDK_INT < 33) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
