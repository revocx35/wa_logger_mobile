package io.github.revocx35.walmobile.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.revocx35.walmobile.core.WaState
import io.github.revocx35.walmobile.core.WaStatus
import io.github.revocx35.walmobile.core.userMessage
import io.github.revocx35.walmobile.ui.components.BtnStyle
import io.github.revocx35.walmobile.ui.components.ConfirmButton
import io.github.revocx35.walmobile.ui.components.ErrorNote
import io.github.revocx35.walmobile.ui.components.FilterChip
import io.github.revocx35.walmobile.ui.components.MutedText
import io.github.revocx35.walmobile.ui.components.PageHeader
import io.github.revocx35.walmobile.ui.components.Spinner
import io.github.revocx35.walmobile.ui.components.SwitchRow
import io.github.revocx35.walmobile.ui.components.WaButton
import io.github.revocx35.walmobile.ui.components.WaIcon
import io.github.revocx35.walmobile.ui.components.WaStatePill
import io.github.revocx35.walmobile.ui.components.WaTextField
import io.github.revocx35.walmobile.ui.components.WarnBanner
import io.github.revocx35.walmobile.ui.theme.Wa
import io.github.revocx35.walmobile.ui.theme.WaIcons
import io.github.revocx35.walmobile.ui.theme.WaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the WhatsApp screens need from the engine (faked in screenshot tests). */
interface WaLink {
    /** WhatsApp's login QR payload while unlinked. */
    val qr: StateFlow<String?>
    /** True while the live page is on screen. */
    val borrowed: StateFlow<Boolean>
    suspend fun restart()
    suspend fun unlink()
    suspend fun pairingCode(phone: String): String
    suspend fun cancelPairing()
    fun refreshQr()
    /** Takes the live WebView for display (null while it is loading). */
    fun borrow(activity: Context): WebView?
    fun giveBack(view: WebView)
}

/** For previews and tests. */
class FakeWaLink(qr: String? = null) : WaLink {
    override val qr: StateFlow<String?> = MutableStateFlow(qr)
    override val borrowed: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun restart() {}
    override suspend fun unlink() {}
    override suspend fun pairingCode(phone: String) = "ABCD1234"
    override suspend fun cancelPairing() {}
    override fun refreshQr() {}
    override fun borrow(activity: Context): WebView? = null
    override fun giveBack(view: WebView) {}
}

fun qrBitmap(text: String, size: Int = 480): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF111B21.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * The real WhatsApp Web page, shown live. The engine lends its WebView to the screen and replaces it
 * with a fresh background one when the screen closes (see WaEngine for why).
 */
@Composable
fun LiveWaWeb(link: WaLink, viewOnly: Boolean, modifier: Modifier = Modifier) {
    val c = Wa.colors
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<WebView?>(null) }
    val borrowed by link.borrowed.collectAsState()
    DisposableEffect(attempt) {
        val v = link.borrow(context)
        view = v
        onDispose {
            view = null
            if (v != null) {
                (v.parent as? ViewGroup)?.removeView(v)
                link.giveBack(v)
            }
        }
    }
    // Not available yet (loading, restarting): try again shortly.
    LaunchedEffect(view, attempt) {
        if (view == null) {
            delay(2000)
            attempt++
        }
    }
    // The page restarted while on screen (crash, restart button): take the new one.
    LaunchedEffect(borrowed) {
        if (!borrowed && view != null) {
            view = null
            attempt++
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val v = view
        if (v == null) {
            Column(Modifier.fillMaxSize().background(c.panel2), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(28.dp)
                MutedText("WhatsApp Web is loading…", style = WaType.small)
            }
        } else {
            AndroidView(
                factory = { ctx -> FrameLayout(ctx).apply { (v.parent as? ViewGroup)?.removeView(v); addView(v, FrameLayout.LayoutParams(-1, -1)) } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.removeAllViews() },
            )
            if (viewOnly) {
                // Swallow every touch so nothing on the page is clicked by accident.
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                e.changes.forEach { it.consume() }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** The real WhatsApp Web session running in the background (like the web UI's WA Web page). */
@Composable
fun WaWebScreen(link: WaLink, wa: WaStatus?) {
    val c = Wa.colors
    val scope = rememberCoroutineScope()
    val needsLink = wa?.state == WaState.Qr || wa?.state == WaState.Disconnected
    var viewOnly by rememberSaveable { mutableStateOf(true) }
    var showPage by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun act(block: suspend () -> Unit) {
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.userMessage()
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.panel)) {
        val side = if (maxWidth < 900.dp) 16.dp else minOf(maxWidth * 0.05f, 48.dp)
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = side, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageHeader(
                WaIcons.monitor,
                "WA Web",
                "The real WhatsApp Web session running in the background on this phone" +
                    (wa?.me?.let { " — linked to ${it.name ?: ""} ${it.phone ?: ""}".trimEnd() } ?: "") + ".",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                WaStatePill(wa)
                if (showPage && !needsLink) SwitchRow(viewOnly, { viewOnly = it }, "View only")
                WaButton("Restart", { act { link.restart() } }, style = BtnStyle.Ghost, icon = WaIcons.refresh)
                ConfirmButton("Unlink", "Unlink this device", { act { link.unlink() } }, style = BtnStyle.Ghost, enabled = wa?.state == WaState.Ready || wa?.state == WaState.Syncing)
            }
            ErrorNote(error)
            wa?.detail?.let { MutedText(it, style = WaType.small) }
            if (needsLink) {
                WarnBanner(Modifier.clip(RoundedCornerShape(8.dp))) {
                    Text("WhatsApp is not linked, so new messages are not logged. Link it again below.", style = WaType.small, color = c.text)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { LinkPanel(link, wa) }
            } else if (showPage) {
                LiveWaWeb(link, viewOnly, Modifier.weight(1f).fillMaxWidth().heightIn(min = 300.dp))
                MutedText("While the page is shown here you can use it like WhatsApp Web on a computer. When you leave this screen it reloads in the background (a few seconds).", style = WaType.small)
            } else {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                    WaIcon(WaIcons.monitor, 48.dp, c.muted)
                    MutedText("WhatsApp Web runs hidden in the background and records everything. You can open the live page to check it or to use it.", textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
                    WaButton("Show WhatsApp Web", { showPage = true }, style = BtnStyle.Primary, icon = WaIcons.eye)
                }
            }
        }
    }
}

private fun steps(samePhone: Boolean): List<androidx.compose.ui.text.AnnotatedString> {
    fun b(vararg parts: Pair<String, Boolean>) = buildAnnotatedString {
        for ((t, bold) in parts) if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t) } else append(t)
    }
    return if (samePhone) {
        listOf(
            b("Enter your WhatsApp phone number with the country code and tap " to false, "Get code" to true, "." to false),
            b("Open " to false, "WhatsApp" to true, " → " to false, "Settings" to true, " → " to false, "Linked devices" to true, " → " to false, "Link a device" to true, "." to false),
            b("Tap " to false, "Link with phone number instead" to true, " and type the 8-character code (WhatsApp may also show a notification asking for it)." to false),
            b("Come back here and wait until the status says " to false, "Importing history" to true, " or " to false, "Connected" to true, "." to false),
        )
    } else {
        listOf(
            b("Open " to false, "WhatsApp" to true, " on the phone you want to log." to false),
            b("Tap " to false, "Menu ⋮" to true, " or " to false, "Settings" to true, " → " to false, "Linked devices" to true, " → " to false, "Link a device" to true, "." to false),
            b("Point that phone at the QR code below." to false),
        )
    }
}

/** QR code (another phone) or pairing code (this phone), like WhatsApp Web's own login screen. */
@Composable
fun LinkPanel(link: WaLink, wa: WaStatus?, samePhoneFirst: Boolean = true) {
    val c = Wa.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var samePhone by rememberSaveable { mutableStateOf(samePhoneFirst) }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPage by rememberSaveable { mutableStateOf(false) }
    val qr by link.qr.collectAsState()
    val unlinked = wa?.state == WaState.Qr || wa?.state == WaState.Disconnected

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip("WhatsApp is on this phone", samePhone) { samePhone = true }
            FilterChip("On another phone (QR)", !samePhone) {
                samePhone = false
                if (code != null) scope.launch { link.cancelPairing() }
                code = null
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            steps(samePhone).forEachIndexed { i, s ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${i + 1}.", style = WaType.base, color = c.text)
                    Text(s, style = WaType.base, color = c.text)
                }
            }
        }
        if (samePhone) {
            val cd = code
            if (cd == null) {
                Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WaTextField(phone, { phone = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' }.take(20) }, "Phone number", placeholder = "+90 555 123 45 67", keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done)
                    ErrorNote(error)
                    WaButton("Get code", {
                        error = null
                        busy = true
                        scope.launch {
                            try {
                                code = link.pairingCode(phone)
                            } catch (e: Exception) {
                                error = e.userMessage()
                            } finally {
                                busy = false
                            }
                        }
                    }, style = BtnStyle.Primary, busy = busy, enabled = unlinked && phone.count { it.isDigit() } >= 7)
                    if (!unlinked) MutedText("Waiting for WhatsApp Web to be ready for linking…", style = WaType.small)
                }
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MutedText("Your code", style = WaType.small)
                    val shown = if (cd.length == 8) cd.take(4) + "-" + cd.drop(4) else cd
                    Text(shown, style = WaType.mono.copy(fontSize = 34.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.panel2).padding(horizontal = 20.dp, vertical = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WaButton("Copy", { copySensitive(context, "Link code", cd) }, style = BtnStyle.Ghost, icon = WaIcons.copy)
                        WaButton("New code", { code = null; scope.launch { link.cancelPairing() } }, style = BtnStyle.Ghost, icon = WaIcons.refresh)
                    }
                    MutedText("The code is valid for a few minutes. The number must be the one WhatsApp runs with.", style = WaType.small, textAlign = TextAlign.Center)
                }
            }
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val q = qr
                if (q != null && unlinked) {
                    val bmp = remember(q) { qrBitmap(q).asImageBitmap() }
                    Image(bmp, "QR code to link WhatsApp", Modifier.size(264.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp))
                } else {
                    Column(Modifier.size(264.dp).clip(RoundedCornerShape(8.dp)).background(c.panel2), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Spinner(28.dp)
                        MutedText(if (unlinked) "Getting a QR code…" else "Waiting for WhatsApp Web…", style = WaType.small, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (wa?.detail?.contains("expired") == true || qr == null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    WaButton("Refresh QR code", { link.refreshQr() }, style = BtnStyle.Ghost, icon = WaIcons.refresh)
                }
            }
        }
        MutedText("View-once photos and videos are never logged. Everything else you send or receive from now on is saved on this phone, encrypted, even if it is later deleted.", style = WaType.small)
        if (!showPage) {
            WaButton("Trouble? Show WhatsApp Web itself", { showPage = true }, style = BtnStyle.Ghost, icon = WaIcons.monitor)
        } else {
            LiveWaWeb(link, viewOnly = false, modifier = Modifier.fillMaxWidth().height(560.dp))
        }
    }
}

/** Onboarding step 3 (like the web's SetupLink page): link WhatsApp, then Complete. */
@Composable
fun SetupLinkScreen(link: WaLink, wa: WaStatus?, onComplete: suspend (force: Boolean) -> Unit, onLock: () -> Unit, samePhoneFirst: Boolean = true) {
    val c = Wa.colors
    val scope = rememberCoroutineScope()
    val state = wa?.state ?: WaState.Starting
    val linked = state == WaState.Ready || state == WaState.Syncing
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val wide = maxWidth >= 900.dp
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(if (wide) 20.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Link WhatsApp", style = WaType.authTitle, color = c.text)
                    MutedText("wa_logger runs the real WhatsApp Web in the background on this phone. Link it once, like a computer.", style = WaType.small)
                }
                WaStatePill(wa)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.panel).verticalScroll(rememberScrollState()).padding(if (wide) 20.dp else 14.dp),
            ) {
                if (linked) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        WaIcon(WaIcons.checks, 48.dp, c.ok)
                        Text("WhatsApp is linked", style = WaType.base.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        MutedText(
                            (wa?.me?.let { "Linked to ${it.name ?: ""} ${it.phone ?: ""}".trimEnd() + ". " } ?: "") + "Press Complete to open your log. The chat history import continues in the background.",
                            textAlign = TextAlign.Center,
                        )
                    }
                } else if (state == WaState.Authenticating) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Spinner(32.dp)
                        MutedText(wa?.detail ?: "Linking…", textAlign = TextAlign.Center)
                    }
                } else {
                    LinkPanel(link, wa, samePhoneFirst)
                }
            }
            val detail = listOfNotNull(wa?.detail?.takeIf { state != WaState.Authenticating }, wa?.sync?.let { "${it.chatsDone}/${it.chatsTotal} chats" }).joinToString(" · ")
            if (detail.isNotEmpty()) MutedText(detail, style = WaType.small)
            ErrorNote(error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                WaButton("Restart", {
                    error = null
                    scope.launch { runCatching { link.restart() }.onFailure { error = it.userMessage() } }
                }, style = BtnStyle.Ghost, icon = WaIcons.refresh)
                WaButton(if (linked) "Complete" else "Waiting for WhatsApp…", {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            onComplete(false)
                        } catch (e: Exception) {
                            error = e.userMessage()
                        } finally {
                            busy = false
                        }
                    }
                }, style = BtnStyle.Primary, enabled = linked, busy = busy)
                WaButton("Lock", onLock, style = BtnStyle.Ghost, icon = WaIcons.lock)
            }
        }
    }
}
