package io.github.revocx35.walmobile.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.revocx35.walmobile.core.userMessage
import io.github.revocx35.walmobile.ui.components.BtnStyle
import io.github.revocx35.walmobile.ui.components.CheckRow
import io.github.revocx35.walmobile.ui.components.ErrorNote
import io.github.revocx35.walmobile.ui.components.LinkButton
import io.github.revocx35.walmobile.ui.components.MutedText
import io.github.revocx35.walmobile.ui.components.Note
import io.github.revocx35.walmobile.ui.components.WaButton
import io.github.revocx35.walmobile.ui.components.WaIcon
import io.github.revocx35.walmobile.ui.components.WaTextField
import io.github.revocx35.walmobile.ui.theme.Wa
import io.github.revocx35.walmobile.ui.theme.WaIcons
import io.github.revocx35.walmobile.ui.theme.WaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** `.auth-page`: green banner behind a centered card with the wa_logger brand (like the web). */
@Composable
fun AuthShell(title: String, subtitle: String? = null, wide: Boolean = false, content: @Composable () -> Unit) {
    val c = Wa.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val compact = maxWidth < 600.dp
        Box(Modifier.fillMaxWidth().height(220.dp).background(c.authBanner))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().imePadding()
                .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 24.dp else 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = if (wide) 620.dp else 440.dp)
                    .fillMaxWidth()
                    .shadow(24.dp, RoundedCornerShape(12.dp), ambientColor = Color(0x3D0B141A), spotColor = Color(0x3D0B141A))
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.panel)
                    .padding(if (compact) 22.dp else 32.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                        WaIcon(WaIcons.shield, 26.dp, Color.White)
                    }
                    Text("wa_logger", style = WaType.base.copy(fontWeight = FontWeight.SemiBold), color = if (c.isDark) c.ok else c.accent2)
                }
                Text(title, style = WaType.authTitle, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
                if (subtitle != null) MutedText(subtitle)
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
            }
        }
    }
}

@Composable
private fun PasswordHints(password: String) {
    val c = Wa.colors
    val long = password.codePointCount(0, password.length) >= 8
    val better = password.codePointCount(0, password.length) >= 12
    Column(Modifier.padding(start = 4.dp)) {
        for ((ok, text) in listOf(long to "At least 8 characters", better to "12 or more is better (a passphrase of 4+ words is best)")) {
            Text("•  $text", style = WaType.small, color = if (ok) (if (c.isDark) c.ok else c.accent2) else c.muted)
        }
    }
}

class AuthActions(
    val create: suspend (password: String) -> Unit,
    val unlock: suspend (password: String) -> Unit,
    val recover: suspend (key: String, password: String) -> Unit,
    /** Fingerprint unlock, when it is set up and available. */
    val biometric: (suspend () -> Unit)? = null,
)

@Composable
fun SignupScreen(actions: AuthActions) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val submit: () -> Unit = submit@{
        error = null
        if (password != confirm) {
            error = "Passwords do not match."
            return@submit
        }
        busy = true
        scope.launch {
            try {
                actions.create(password)
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }
    AuthShell("Set up wa_logger", "Everything wa_logger records stays on this phone, encrypted. Choose the password that unlocks it.") {
        WaTextField(password, { password = it }, "Password", password = true)
        WaTextField(confirm, { confirm = it }, "Confirm password", password = true, imeAction = ImeAction.Done, onIme = submit)
        PasswordHints(password)
        Note("Your log is encrypted with a key protected by this password. If you lose both the password and the recovery key (next step), nobody can recover the logged messages.", WaIcons.lock)
        ErrorNote(error)
        WaButton("Continue", submit, style = BtnStyle.Primary, block = true, busy = busy, enabled = password.length >= 8)
    }
}

@Composable
fun LoginScreen(actions: AuthActions, onRecover: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val submit: () -> Unit = {
        error = null
        busy = true
        scope.launch {
            try {
                actions.unlock(password)
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }
    val bio = actions.biometric
    val runBio: () -> Unit = {
        if (bio != null) {
            error = null
            scope.launch {
                try {
                    bio()
                } catch (e: io.github.revocx35.walmobile.core.ApiException) {
                    if (e.code != "cancelled") error = e.userMessage
                } catch (e: Exception) {
                    error = e.userMessage()
                }
            }
        }
    }
    // Offer the fingerprint prompt right away when it is set up.
    LaunchedEffect(bio != null) { if (bio != null) runBio() }
    AuthShell("Unlock", "Your WhatsApp log is encrypted. Logging continues in the background while the app is locked.") {
        WaTextField(password, { password = it }, "Password", password = true, imeAction = ImeAction.Done, onIme = submit)
        ErrorNote(error)
        WaButton("Unlock", submit, style = BtnStyle.Primary, block = true, busy = busy, enabled = password.isNotEmpty())
        if (bio != null) WaButton("Use fingerprint", runBio, style = BtnStyle.Ghost, block = true, icon = WaIcons.lock)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LinkButton("Forgot your password?", onRecover, style = WaType.small)
        }
    }
}

@Composable
fun RecoverScreen(actions: AuthActions, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val submit: () -> Unit = submit@{
        error = null
        if (password != confirm) {
            error = "Passwords do not match."
            return@submit
        }
        busy = true
        scope.launch {
            try {
                actions.recover(key, password)
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }
    androidx.activity.compose.BackHandler(onBack = onBack)
    AuthShell("Recover access", "Use the recovery key you saved during setup to choose a new password. Fingerprint unlock will be turned off.") {
        WaTextField(key, { key = it }, "Recovery key", mono = true, singleLine = false, minLines = 3)
        WaTextField(password, { password = it }, "New password", password = true)
        WaTextField(confirm, { confirm = it }, "Confirm new password", password = true, imeAction = ImeAction.Done, onIme = submit)
        PasswordHints(password)
        ErrorNote(error)
        WaButton("Reset password", submit, style = BtnStyle.Primary, block = true, busy = busy, enabled = key.isNotBlank() && password.length >= 8)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LinkButton("Back to unlock", onBack, style = WaType.small)
        }
    }
}

/** Copies a secret without it showing up in clipboard previews (Android 13+) or keyboard suggestions. */
fun copySensitive(context: android.content.Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
    context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
}

@Composable
fun RecoveryKeyBox(recoveryKey: String) {
    val c = Wa.colors
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(8.dp)).background(c.panel2).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
            Text(recoveryKey, style = WaType.mono.copy(fontSize = 16.sp, letterSpacing = 0.6.sp), color = c.text)
        }
        WaButton(if (copied) "Copied" else "Copy", { copySensitive(context, "Recovery key", recoveryKey); copied = true }, style = BtnStyle.Ghost)
    }
}

@Composable
fun SetupRecoveryScreen(recoveryKey: String?, onContinue: () -> Unit) {
    var ack by remember { mutableStateOf(false) }
    if (recoveryKey == null) {
        AuthShell("Recovery key", "The recovery key is only shown once. You can create a new one any time in Settings → Recovery key.") {
            WaButton("Continue", onContinue, style = BtnStyle.Primary, block = true)
        }
        return
    }
    AuthShell(
        "Save your recovery key",
        "This key is the only way to regain access to your logs if you forget your password. It is shown only once — store it in a password manager or print it.",
        wide = true,
    ) {
        RecoveryKeyBox(recoveryKey)
        CheckRow(ack, { ack = it }, "I have stored my recovery key somewhere safe")
        WaButton("Continue", onContinue, style = BtnStyle.Primary, block = true, enabled = ack)
    }
}
