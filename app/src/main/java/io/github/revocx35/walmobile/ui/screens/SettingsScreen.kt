package io.github.revocx35.walmobile.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.revocx35.walmobile.BuildConfig
import io.github.revocx35.walmobile.core.ApiException
import io.github.revocx35.walmobile.core.AuditEntry
import io.github.revocx35.walmobile.core.Settings
import io.github.revocx35.walmobile.core.SettingsPatch
import io.github.revocx35.walmobile.core.WaStatus
import io.github.revocx35.walmobile.core.userMessage
import io.github.revocx35.walmobile.data.AppGraph
import io.github.revocx35.walmobile.security.Biometric
import io.github.revocx35.walmobile.store.LocalApi
import io.github.revocx35.walmobile.store.StorageInfo
import io.github.revocx35.walmobile.ui.LocalFormatter
import io.github.revocx35.walmobile.ui.components.BtnStyle
import io.github.revocx35.walmobile.ui.components.CheckRow
import io.github.revocx35.walmobile.ui.components.ConfirmButton
import io.github.revocx35.walmobile.ui.components.Divider
import io.github.revocx35.walmobile.ui.components.ErrorNote
import io.github.revocx35.walmobile.ui.components.FilterChip
import io.github.revocx35.walmobile.ui.components.MutedText
import io.github.revocx35.walmobile.ui.components.OkNote
import io.github.revocx35.walmobile.ui.components.PageHeader
import io.github.revocx35.walmobile.ui.components.SectionCard
import io.github.revocx35.walmobile.ui.components.Spinner
import io.github.revocx35.walmobile.ui.components.SwitchRow
import io.github.revocx35.walmobile.ui.components.WaButton
import io.github.revocx35.walmobile.ui.components.WaStatePill
import io.github.revocx35.walmobile.ui.components.WaTextField
import io.github.revocx35.walmobile.ui.theme.Wa
import io.github.revocx35.walmobile.ui.theme.WaIcons
import io.github.revocx35.walmobile.ui.theme.WaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** busy / error / ok for one form, like the web's useAsync(). */
private class AsyncOp(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var ok by mutableStateOf<String?>(null)

    fun run(success: String? = null, then: () -> Unit = {}, block: suspend () -> Unit) {
        busy = true
        error = null
        ok = null
        scope.launch {
            try {
                block()
                ok = success
                then()
            } catch (e: ApiException) {
                if (e.code != "cancelled") error = e.userMessage
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }
}

@Composable
private fun rememberOp(): AsyncOp {
    val scope = rememberCoroutineScope()
    return remember { AsyncOp(scope) }
}

class SettingsDeps(
    val api: LocalApi,
    /** Null in screenshot tests. */
    val graph: AppGraph?,
    val wa: WaStatus?,
)

@Composable
fun SettingsScreen(deps: SettingsDeps) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val positions = remember { mutableStateMapOf<String, Int>() }
    val toc = listOf(
        "password" to "Password", "unlock" to "Unlock", "recovery" to "Recovery key", "logging" to "Logging", "whatsapp" to "WhatsApp",
        "background" to "Background", "storage" to "Storage", "audit" to "Security log", "danger" to "Danger zone", "app" to "App",
    )
    fun Modifier.anchor(id: String) = onGloballyPositioned { positions[id] = it.positionInParent().y.toInt() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Wa.colors.panel)) {
        val side = if (maxWidth < 900.dp) 16.dp else minOf(maxWidth * 0.05f, 48.dp)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = side, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageHeader(WaIcons.settings, "Settings", "Everything stays on this phone")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((id, label) in toc) FilterChip(label, false) { scope.launch { positions[id]?.let { scroll.animateScrollTo(it) } } }
            }
            Column(Modifier.anchor("password")) { PasswordSection(deps) }
            Column(Modifier.anchor("unlock")) { UnlockSection(deps) }
            Column(Modifier.anchor("recovery")) { RecoverySection(deps) }
            Column(Modifier.anchor("logging")) { LoggingSection(deps) }
            Column(Modifier.anchor("whatsapp")) { WhatsAppSection(deps) }
            Column(Modifier.anchor("background")) { BackgroundSection(deps) }
            Column(Modifier.anchor("storage")) { StorageSection(deps) }
            Column(Modifier.anchor("audit")) { AuditSection(deps) }
            Column(Modifier.anchor("danger")) { DangerSection(deps) }
            Column(Modifier.anchor("app")) { AppSection(deps) }
        }
    }
}

@Composable
private fun PasswordSection(d: SettingsDeps) {
    var cur by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val op = rememberOp()
    SectionCard("Password") {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            WaTextField(cur, { cur = it }, "Current password", password = true)
            WaTextField(next, { next = it }, "New password", password = true)
            WaTextField(confirm, { confirm = it }, "Confirm new password", password = true, imeAction = ImeAction.Done)
            if (next.isNotEmpty() && confirm.isNotEmpty() && next != confirm) ErrorNote("Passwords do not match.")
            ErrorNote(op.error)
            OkNote(op.ok)
            WaButton("Change password", {
                if (next == confirm) op.run("Password changed.", then = { cur = ""; next = ""; confirm = "" }) { d.api.changePassword(cur, next) }
            }, style = BtnStyle.Primary, busy = op.busy, enabled = cur.isNotEmpty() && next.length >= 8)
        }
    }
}

@Composable
private fun UnlockSection(d: SettingsDeps) {
    val graph = d.graph
    val context = LocalContext.current
    val op = rememberOp()
    var hasBio by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { hasBio = graph?.vault?.biometricBlob() != null }
    val autoLock = graph?.prefs?.autoLockMinutes?.collectAsState()?.value ?: 5
    SectionCard("Unlock") {
        val available = graph != null && Biometric.available(context)
        if (available || hasBio == true) {
            SwitchRow(hasBio == true, { on ->
                op.run(if (on) "Fingerprint unlock is on." else "Fingerprint unlock is off.") {
                    if (on) Biometric.enable(context, graph!!.vault) else Biometric.disable(graph!!.vault)
                    hasBio = on
                }
            }, "Unlock with fingerprint or face", enabled = !op.busy)
        } else {
            MutedText("Fingerprint unlock needs Android 11 or newer and a fingerprint (or strong face unlock) set up on this phone.", style = WaType.small)
        }
        ErrorNote(op.error)
        OkNote(op.ok)
        Text("Lock the app", style = WaType.base.copy(fontWeight = FontWeight.Medium), color = Wa.colors.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((m, label) in listOf(0 to "At once", 1 to "After 1 min", 5 to "After 5 min", 15 to "After 15 min", 60 to "After 1 hour", -1 to "Only on restart")) {
                FilterChip(label, autoLock == m) { graph?.prefs?.setAutoLockMinutes(m) }
            }
        }
        MutedText("…after the app leaves the screen. Logging continues while it is locked; only reading needs the password.", style = WaType.small)
    }
}

@Composable
private fun RecoverySection(d: SettingsDeps) {
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf<String?>(null) }
    val op = rememberOp()
    SectionCard("Recovery key") {
        MutedText("Creates a new recovery key and invalidates the old one. Store the new key safely — it is shown only once.")
        val k = key
        if (k != null) {
            RecoveryKeyBox(k)
            WaButton("I have saved it", { key = null })
        } else {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WaTextField(password, { password = it }, "Password", password = true)
                ErrorNote(op.error)
                WaButton("Generate new recovery key", {
                    op.run(then = { password = "" }) { key = d.api.rotateRecoveryKey(password) }
                }, busy = op.busy, enabled = password.isNotEmpty())
            }
        }
    }
}

@Composable
private fun NumberSetting(label: String, value: Int, range: IntRange, onSave: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    fun commit() {
        val n = text.toIntOrNull()?.coerceIn(range)
        if (n != null && n != value) onSave(n) else text = value.toString()
    }
    Column(Modifier.onFocusChanged { if (!it.hasFocus) commit() }) {
        WaTextField(text, { text = it.filter(Char::isDigit).take(5) }, label, keyboardType = KeyboardType.Number, imeAction = ImeAction.Done, onIme = ::commit)
    }
}

@Composable
private fun LoggingSection(d: SettingsDeps) {
    var s by remember { mutableStateOf<Settings?>(null) }
    val op = rememberOp()
    LaunchedEffect(Unit) { s = runCatching { d.api.settings() }.getOrNull() }
    val cur = s
    SectionCard("Logging") {
        if (cur == null) {
            Spinner()
            return@SectionCard
        }
        fun save(p: SettingsPatch) = op.run("Saved.") { s = d.api.updateSettings(p) }
        CheckRow(cur.logStatus, { save(SettingsPatch(logStatus = it)) }, "Log status updates (stories) of your contacts")
        CheckRow(cur.downloadHistoryMedia, { save(SettingsPatch(downloadHistoryMedia = it)) }, "Download media of imported history (not only new messages)")
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NumberSetting("Maximum media size to download (MB)", cur.mediaMaxMb, 1..2000) { save(SettingsPatch(mediaMaxMb = it)) }
            NumberSetting("Messages to import per chat on first link", cur.historyPerChat, 0..5000) { save(SettingsPatch(historyPerChat = it)) }
        }
        MutedText("View-once photos and videos are never logged.", style = WaType.small)
        ErrorNote(op.error)
        OkNote(op.ok)
    }
}

@Composable
private fun WhatsAppSection(d: SettingsDeps) {
    val op = rememberOp()
    val wa = d.wa
    SectionCard("WhatsApp connection") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            WaStatePill(wa)
            if (wa?.detail != null) MutedText(wa.detail!!, style = WaType.small)
            wa?.me?.let { Text("Linked: ${it.name ?: ""} ${it.phone ?: ""}".trimEnd(), style = WaType.small, color = Wa.colors.text) }
            MutedText("Media queue: ${wa?.mediaQueue ?: 0}", style = WaType.small)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WaButton("Restart WhatsApp Web", { op.run("Restarting…") { d.api.waRestart() } }, icon = WaIcons.refresh, busy = op.busy)
            ConfirmButton("Unlink this device from WhatsApp", "Unlink device", { op.run("Device unlinked.") { d.api.waLogout() } })
        }
        ErrorNote(op.error)
        OkNote(op.ok)
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BackgroundSection(d: SettingsDeps) {
    val context = LocalContext.current
    val graph = d.graph
    var unrestricted by remember { mutableStateOf(true) }
    var notificationsOk by remember { mutableStateOf(true) }
    fun check() {
        unrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        notificationsOk = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    LifecycleResumeEffect(Unit) {
        check()
        onPauseOrDispose { }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { check() }
    val keepAwake = graph?.prefs?.keepAwake?.collectAsState()?.value ?: false
    val autostart = graph?.prefs?.autostart?.collectAsState()?.value ?: true
    val notifyDeleted = graph?.prefs?.notifyDeleted?.collectAsState()?.value ?: true
    SectionCard("Background") {
        MutedText("WhatsApp Web runs in the background with a permanent notification. Android may still stop it to save battery unless you allow unrestricted battery use.", style = WaType.small)
        if (unrestricted) {
            OkNote("Battery use is unrestricted for wa_logger.", WaIcons.check)
        } else {
            ErrorNote("Battery optimization can stop logging in the background.")
            WaButton("Allow unrestricted battery use", {
                runCatching {
                    context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }.onFailure { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
            }, style = BtnStyle.Primary)
        }
        if (!notificationsOk) {
            ErrorNote("Notifications are off: you won't be told when WhatsApp gets unlinked or a message is deleted.")
            WaButton("Allow notifications", { if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }, style = BtnStyle.Ghost)
        }
        SwitchRow(notifyDeleted, { graph?.prefs?.setNotifyDeleted(it) }, "Notify me when someone deletes a message")
        SwitchRow(autostart, { graph?.prefs?.setAutostart(it) }, "Start logging when the phone starts")
        SwitchRow(keepAwake, { graph?.prefs?.setKeepAwake(it) }, "Keep processing with the screen off (uses more battery)")
        MutedText("Some phones (Xiaomi, Huawei, Samsung, OnePlus…) have extra battery savers that stop background apps anyway; see dontkillmyapp.com for your model.", style = WaType.small)
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
private fun StorageSection(d: SettingsDeps) {
    var info by remember { mutableStateOf<StorageInfo?>(null) }
    LaunchedEffect(Unit) { info = runCatching { d.api.storage() }.getOrNull() }
    SectionCard("Storage") {
        val i = info
        if (i == null) {
            Spinner()
        } else {
            Text("${i.messages} messages · ${i.mediaFiles} media files", style = WaType.base, color = Wa.colors.text)
            MutedText("Media ${formatBytes(i.mediaBytes)} · database ${formatBytes(i.databaseBytes)}", style = WaType.small)
        }
    }
}

@Composable
private fun AuditSection(d: SettingsDeps) {
    val fmt = LocalFormatter.current
    val c = Wa.colors
    var rows by remember { mutableStateOf<List<AuditEntry>?>(null) }
    LaunchedEffect(Unit) { rows = runCatching { d.api.audit() }.getOrDefault(emptyList()) }
    SectionCard("Security log") {
        val r = rows
        if (r == null) {
            Spinner()
        } else {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                r.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    val warn = Regex("fail|wipe|disconnected|logout|lost").containsMatchIn(e.event)
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(e.event, style = WaType.small.merge(WaType.mono), color = if (warn) c.danger else c.text)
                        MutedText(fmt.dateTime(e.ts) + (e.detail?.let { " · $it" } ?: ""), style = WaType.tiny)
                    }
                }
            }
        }
    }
}

@Composable
private fun DangerSection(d: SettingsDeps) {
    var password by remember { mutableStateOf("") }
    var armed by remember { mutableStateOf(false) }
    val op = rememberOp()
    SectionCard("Danger zone", danger = true) {
        MutedText("Permanently deletes every logged message, edit, reaction and media file. Your password and the WhatsApp link stay; logging continues for new messages.")
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            WaTextField(password, { password = it }, "Password", password = true)
            ErrorNote(op.error)
            OkNote(op.ok)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WaButton(if (armed) "Tap again to wipe everything" else "Wipe all logged data", {
                    if (!armed) {
                        armed = true
                    } else {
                        op.run("All logged messages and media were deleted.", then = { password = ""; armed = false }) { d.api.wipe(password) }
                    }
                }, style = BtnStyle.Danger, busy = op.busy, enabled = password.isNotEmpty())
                if (armed) WaButton("Cancel", { armed = false }, style = BtnStyle.Ghost)
            }
        }
    }
}

/** Screen protection, crash reports, version. */
@Composable
private fun AppSection(d: SettingsDeps) {
    val graph = d.graph
    val secure = graph?.prefs?.secureScreen?.collectAsState()?.value ?: true
    SectionCard("App") {
        SwitchRow(secure, { graph?.prefs?.setSecureScreen(it) }, "Block screenshots and hide the app in the recent-apps preview")
        var reports by remember { mutableStateOf<String?>(null) }
        var showReports by remember { mutableStateOf(false) }
        WaButton("Crash reports", { reports = graph?.diagnostics?.all(); showReports = true }, style = BtnStyle.Ghost, icon = WaIcons.warning)
        if (showReports) {
            io.github.revocx35.walmobile.ui.components.ReportDialog(
                "Crash reports",
                "Crashes, \"not responding\" events and unexpected errors recorded on this phone. Only technical details, never message content.",
                reports,
                onClose = { graph?.diagnostics?.markSeen(); showReports = false },
                onClear = { graph?.diagnostics?.clear(); reports = null },
            )
        }
        MutedText("wa_logger mobile ${BuildConfig.VERSION_NAME}", style = WaType.small)
    }
}
