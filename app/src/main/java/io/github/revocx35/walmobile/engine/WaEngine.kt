package io.github.revocx35.walmobile.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.revocx35.walmobile.BuildConfig
import io.github.revocx35.walmobile.core.ApiException
import io.github.revocx35.walmobile.core.ServerEvent
import io.github.revocx35.walmobile.core.SyncProgress
import io.github.revocx35.walmobile.core.WaJson
import io.github.revocx35.walmobile.core.WaMe
import io.github.revocx35.walmobile.core.WaState
import io.github.revocx35.walmobile.core.WaStatus
import io.github.revocx35.walmobile.core.wa.phoneOf
import io.github.revocx35.walmobile.security.Vault
import io.github.revocx35.walmobile.store.EventBus
import io.github.revocx35.walmobile.store.MediaFiles
import io.github.revocx35.walmobile.store.Presenter
import io.github.revocx35.walmobile.store.Repo
import io.github.revocx35.walmobile.store.SettingsStore
import io.github.revocx35.walmobile.store.WaControl
import io.github.revocx35.walmobile.store.values
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.util.Base64

/**
 * Runs WhatsApp Web in a WebView that is never shown on screen, and logs what happens in it
 * (the phone's version of the server's WaService + whatsapp-web.js + Chromium container).
 *
 * Why the WebView stays off-screen: a WebView that has been attached to a window and detached again is
 * "hidden" for Chromium, and Chromium freezes hidden pages after about a minute — WhatsApp Web would stop
 * receiving anything while the app is in the background. A WebView that was never attached stays
 * "visible" and keeps running with the screen off (checked on the emulator, also in Doze). So when the
 * UI shows the live page ([borrowView]), the engine hands its WebView over and, when it comes back,
 * replaces it with a fresh one; WhatsApp Web reloads from its own storage and catches up.
 */
class WaEngine(
    private val context: Context,
    private val repo: Repo,
    private val vault: Vault,
    private val settings: SettingsStore,
    private val events: EventBus,
    private val files: MediaFiles,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    private val onNonFatal: (String, Throwable) -> Unit,
) : WaControl {
    private val _status = MutableStateFlow(WaStatus(WaState.Idle, since = System.currentTimeMillis()))
    override val status: StateFlow<WaStatus> = _status.asStateFlow()

    /** WhatsApp's login QR payload while unlinked (rendered natively on the link screen). */
    private val _qr = MutableStateFlow<String?>(null)
    val qr: StateFlow<String?> = _qr.asStateFlow()

    /** Set while the UI shows the live page. */
    private val _borrowed = MutableStateFlow(false)
    val borrowed: StateFlow<Boolean> = _borrowed.asStateFlow()

    private val bridge = PageBridge(scope) { page, msg -> onPageMessage(page, msg) }
    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var started = false
    private var generation = 0
    private var attempt = 0
    private var reloadJob: Job? = null
    private var timers = mutableListOf<Job>()
    private var readyPage = -1
    private var waSeenPage = -1
    private var watchdogFailures = 0
    private var lastSocketState: String? = null
    @Volatile private var loggedOut = false

    /* ----------------------------------------------------- ingest queue */

    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Runs [block] after everything queued before it (WhatsApp event order); waits for it. */
    suspend fun serial(block: suspend () -> Unit) {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        work.send {
            try {
                block()
                done.complete(Unit)
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        done.await()
    }

    private fun enqueue(label: String, block: suspend () -> Unit) {
        work.trySend {
            try {
                block()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException && e !is PageError) onNonFatal("ingest:$label", e)
            }
        }
    }

    /* ----------------------------------------------------------- APIs */

    private val pageApi = object : PageApi {
        override suspend fun chatInfo(chatId: String): ChatLite? =
            bridge.call("chatInfo", args("id" to chatId), 20_000).asObj()?.let { WaJson.decodeFromJsonElement(ChatLite.serializer(), it) }

        override suspend fun contactInfo(id: String): ContactInfo? =
            bridge.call("contactInfo", args("id" to id), 20_000).asObj()?.let { WaJson.decodeFromJsonElement(ContactInfo.serializer(), it) }
    }

    private val syncApi = object : SyncApi {
        override suspend fun listChats(): List<ChatLite> =
            WaJson.decodeFromJsonElement(ListSerializer(ChatLite.serializer()), bridge.call("listChats", timeoutMs = 60_000))

        override suspend fun fetchMessages(chatId: String, limit: Int): List<JsonObject> =
            (bridge.call("fetchMessages", args("id" to chatId, "limit" to limit), 300_000) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    private val downloader = object : MediaDownloader {
        override suspend fun prepare(messageId: String, maxBytes: Long): Prep {
            val r = bridge.call("mediaPrepare", args("id" to messageId, "max" to maxBytes), MediaQueue.PREP_TIMEOUT_MS).asObj() ?: return Prep.Status("failed")
            return when (val s = r.str("status")) {
                "ok" -> Prep.Ok(r.str("token") ?: return Prep.Status("failed"), r.num("size")?.toLong() ?: 0, r.str("mimetype"), r.str("filename"))
                null -> Prep.Status("failed")
                else -> Prep.Status(s, r.str("reason"))
            }
        }

        override suspend fun slice(token: String, offset: Long, length: Int): ByteArray? {
            val b64 = (bridge.call("mediaSlice", args("token" to token, "offset" to offset, "length" to length), MediaQueue.SLICE_TIMEOUT_MS) as? JsonPrimitive)?.contentOrNull
            return b64?.let { Base64.getDecoder().decode(it) }
        }

        override suspend fun release(token: String) {
            bridge.call("mediaRelease", args("token" to token), 10_000)
        }
    }

    val ingest = Ingest(repo, vault, settings, events, pageApi, object : MediaSink {
        override fun enqueue(messageId: String, priority: Priority) = media.enqueue(messageId, priority)
    })
    val media = MediaQueue(repo, vault, settings, events, files, downloader, scope)
    private val sync = Sync(repo, settings, ingest, syncApi, { p -> onSyncProgress(p) }, { block -> serial(block) })
    private val avatars = Avatars(repo, vault, files, events, http) { chatId ->
        (bridge.call("profilePicUrl", args("id" to chatId), 30_000) as? JsonPrimitive)?.contentOrNull
    }

    init {
        scope.launch(Dispatchers.IO.limitedParallelism(1)) {
            for (job in work) job()
        }
        media.onSizeChanged = { publish(_status.value.copy(mediaQueue = media.size()), announce = false) }
    }

    /* --------------------------------------------------------- status */

    private fun publish(s: WaStatus, announce: Boolean = true) {
        _status.value = s
        if (announce) events.emit(ServerEvent.WaStateChanged(s))
    }

    private fun setState(state: WaState, detail: String? = null) {
        val cur = _status.value
        if (cur.state == state && cur.detail == detail) return
        publish(cur.copy(state = state, detail = detail, since = System.currentTimeMillis(), mediaQueue = media.size()))
    }

    /** Audit entries are written off the calling (often main) thread. */
    private fun audit(event: String, detail: String? = null) {
        scope.launch(Dispatchers.IO) { runCatching { repo.audit(event, detail?.take(80)) } }
    }

    private fun onSyncProgress(p: SyncProgress?) {
        publish(_status.value.copy(sync = p), announce = false)
        events.emit(ServerEvent.SyncProgressChanged(p))
    }

    private val linkedStates = setOf(WaState.Ready, WaState.Syncing)

    /* ------------------------------------------------------ lifecycle */

    /** Starts WhatsApp Web (idempotent). Needs an owner (the public key encrypts everything). */
    fun start() {
        scope.launch(Dispatchers.Main) {
            if (started) return@launch
            if (vault.writer() == null) return@launch
            started = true
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                setState(WaState.Error, "Update “Android System WebView” (or Chrome) from the Play Store: this version is too old.")
                started = false
                return@launch
            }
            watchNetwork()
            create()
        }
    }

    // The WebView features are checked in start() before any WebView is created.
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun create() {
        val gen = ++generation
        destroyWebView()
        loggedOut = false
        readyPage = -1
        _qr.value = null
        setState(WaState.Starting, "Loading WhatsApp Web…")
        val w = try {
            val wr = MutableContextWrapper(context.applicationContext)
            wrapper = wr
            WebView(wr)
        } catch (e: Exception) {
            // No WebView installed / being updated.
            onNonFatal("webview", e)
            setState(WaState.Error, "Android System WebView is not available. Retrying…")
            scheduleReload(gen, "WebView unavailable")
            return
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        configure(w)
        WebViewCompat.addWebMessageListener(w, "WalBridge", ORIGINS) { _, message, _, isMainFrame, reply ->
            if (isMainFrame && gen == generation) bridge.receive(message.data, reply)
        }
        WebViewCompat.addDocumentStartJavaScript(w, bridgeScript(), ORIGINS)
        w.webViewClient = Client(gen)
        w.webChromeClient = Chrome()
        web = w
        w.loadUrl(WA_URL)
        startWatchdog(gen)
    }

    private fun bridgeScript(): String = context.assets.open("walm-bridge.js").bufferedReader().use { it.readText() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mediaPlaybackRequiresUserGesture = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.userAgentString = desktopUserAgent(s.userAgentString)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val major = chromeMajor(WebSettings.getDefaultUserAgent(context))
            val full = WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "$major.0.0.0"
            WebSettingsCompat.setUserAgentMetadata(
                s,
                UserAgentMetadata.Builder()
                    .setBrandVersionList(
                        listOf(
                            UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
                            UserAgentMetadata.BrandVersion.Builder().setBrand("Not-A.Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build(),
                        ),
                    )
                    .setFullVersion(full)
                    .setPlatform("Linux")
                    .setPlatformVersion("")
                    .setArchitecture("x86")
                    .setModel("")
                    .setMobile(false)
                    .setBitness(64)
                    .setWow64(false)
                    .build(),
            )
        }
        // Keep WhatsApp Web's renderer at the app's priority even while no window shows it.
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        w.setDownloadListener { _, _, _, _, _ -> }
        w.isFocusable = true
        w.isFocusableInTouchMode = true
    }

    /** Test builds only (debug, e2e): serve the fake WhatsApp Web of src/debug/assets/fakewa (scripts/e2e.py). */
    private val fakeWa: Boolean
        get() = BuildConfig.FAKE_WA && context.getSharedPreferences("walm_prefs", Context.MODE_PRIVATE).getBoolean("fake_wa", false)

    // Lint misses the onRenderProcessGone override below in an inner class.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class Client(private val gen: Int) : WebViewClient() {
        private val fake = fakeWa

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
            if (!fake || request.url.host != WA_HOST) return null
            val path = request.url.path?.trimStart('/').orEmpty().ifEmpty { "fakewa/index.html" }
            val type = when (path.substringAfterLast('.')) {
                "html" -> "text/html"
                "js" -> "application/javascript"
                "png" -> "image/png"
                "wav" -> "audio/wav"
                else -> "application/octet-stream"
            }
            val body = if (path.startsWith("fakewa/")) runCatching { context.assets.open(path) }.getOrNull() else null
            return if (body != null) android.webkit.WebResourceResponse(type, "utf-8", body)
            else android.webkit.WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.scheme == "https" && url.host == WA_HOST) return false
            // Everything else (help pages, links in messages): only when the owner tapped it on screen.
            if (_borrowed.value && request.hasGesture() && (url.scheme == "https" || url.scheme == "http")) {
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (gen != generation) return
            if (url?.contains("post_logout=1") == true) loggedOut = true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (gen != generation || !request.isForMainFrame) return
            bridge.detach("page failed to load")
            scheduleReload(gen, if (isOnline()) "Cannot load WhatsApp Web" else "No internet connection")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view !== web) {
                runCatching { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
                return true
            }
            audit("wa_page_lost", if (detail.didCrash()) "crash" else "killed")
            bridge.detach("renderer gone")
            web = null
            runCatching { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
            _borrowed.value = false
            scheduleReload(gen, "WhatsApp Web stopped")
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<android.net.Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
            filePathCallback?.onReceiveValue(null)
            return true
        }
    }

    private fun scheduleReload(gen: Int, reason: String) {
        if (gen != generation) return
        val wait = minOf(5_000L shl minOf(attempt, 4), 60_000L)
        if (attempt == 0) audit("wa_reload", reason)
        attempt++
        media.pause()
        setState(WaState.Error, "$reason. Retrying in ${wait / 1000}s")
        reloadJob?.cancel()
        reloadJob = scope.launch(Dispatchers.Main) {
            delay(wait)
            if (gen == generation) create()
        }
    }

    private fun destroyWebView() {
        stopTimers()
        bridge.detach("reset")
        media.pause()
        val w = web ?: return
        web = null
        _borrowed.value = false
        runCatching {
            (w.parent as? ViewGroup)?.removeView(w)
            w.stopLoading()
            w.destroy()
        }
    }

    override suspend fun restart() {
        withContext(Dispatchers.Main) {
            attempt = 0
            reloadJob?.cancel()
            if (!started) start() else create()
        }
    }

    /** Unlinks this device from the WhatsApp account (the page shows a new QR code afterwards). */
    override suspend fun logout() {
        try {
            bridge.call("logout", timeoutMs = 30_000)
        } catch (e: PageError) {
            throw ApiException.fromPage(e)
        }
        audit("wa_logout")
        withContext(Dispatchers.IO) {
            repo.deleteState(Presenter.STATE_WA_ME)
            repo.deleteState(Presenter.STATE_WA_ME_LID)
        }
        publish(_status.value.copy(me = null))
        setState(WaState.Disconnected, "Unlinked. Link WhatsApp again to continue logging.")
    }

    override fun retryMedia(messageId: String): Boolean {
        val row = repo.getMessage(messageId) ?: return false
        if (row.isViewOnce) return false
        if (row.mediaStatus !in setOf("failed", "too_large", "skipped", "unavailable")) return false
        repo.updateMessage(messageId, values("media_status" to "pending", "media_attempts" to 0, "updated_at" to System.currentTimeMillis()))
        media.enqueue(messageId, Priority.Live)
        events.emit(ServerEvent.MessageUpdate(row.chatId, messageId, "media"))
        return true
    }

    override fun onDataWiped() {
        media.clear()
        enqueue("wipe") { ingest.resetCaches() }
    }

    /** Runs a bridge operation (sending, `LocalSender`); throws [PageError]. */
    suspend fun pageCall(op: String, args: JsonObject, timeoutMs: Long): kotlinx.serialization.json.JsonElement = bridge.call(op, args, timeoutMs)

    /** Linked and logging (sending is possible). */
    val linked: Boolean get() = _status.value.state in linkedStates

    fun refreshQr() {
        scope.launch(Dispatchers.IO) { runCatching { bridge.call("refreshQr", timeoutMs = 15_000) } }
    }

    /** "Link with phone number": returns the 8-character code to type into WhatsApp on the phone. */
    suspend fun requestPairingCode(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length !in 7..15) throw ApiException.validation("Enter the full number with country code, digits only.")
        return try {
            (bridge.call("pairingCode", args("phone" to digits), 60_000) as? JsonPrimitive)?.contentOrNull
                ?: throw ApiException.unavailable("WhatsApp did not return a code. Try again.")
        } catch (e: PageError) {
            throw ApiException(503, "unavailable", "Could not get a code from WhatsApp (${e.message}). Try again.", cause = e)
        }
    }

    suspend fun cancelPairing() {
        runCatching { bridge.call("cancelPairing", timeoutMs = 20_000) }
    }

    /* ------------------------------------------------ showing the page */

    /** Hands the live WebView to the UI (main thread). Call [returnView] when it leaves the screen. */
    fun borrowView(activity: Context): WebView? {
        val w = web ?: return null
        _borrowed.value = true
        wrapper?.baseContext = activity
        (w.parent as? ViewGroup)?.removeView(w)
        // WhatsApp Web's desktop layout is wider than a phone: lay it out wide and let the WebView zoom out.
        scope.launch(Dispatchers.IO) { runCatching { bridge.call("setViewport", args("width" to LIVE_VIEW_WIDTH), 10_000) } }
        return w
    }

    /** The UI is done with the page: it has been on screen, so replace it with a fresh, never-shown one. */
    fun returnView(view: WebView) {
        if (view !== web) return
        _borrowed.value = false
        (view.parent as? ViewGroup)?.removeView(view)
        wrapper?.baseContext = context.applicationContext
        scope.launch(Dispatchers.Main) {
            attempt = 0
            create()
        }
    }

    /* ------------------------------------------------- page messages */

    private suspend fun onPageMessage(page: Int, msg: JsonObject) {
        // Debug builds: message types and states only, never content.
        if (BuildConfig.DEBUG) android.util.Log.d("walm", "page#$page ${msg.str("t")} ${msg.str("state") ?: msg.str("ev") ?: msg.str("msg")?.take(120) ?: ""}")
        when (msg.str("t")) {
            "hello" -> {
                watchdogFailures = 0
                if (loggedOut) {
                    loggedOut = false
                    onLoggedOut()
                }
            }
            "wa" -> {
                waSeenPage = page
                attempt = 0
                withContext(Dispatchers.IO) { runCatching { repo.setState("wa_web_version", msg.str("version") ?: "") } }
                onSocketState(msg.str("state"))
            }
            "state" -> onSocketState(msg.str("state"))
            "qr" -> {
                _qr.value = msg.str("qr")
                // After an unlink the state stays "disconnected" (it raises the alert) until the owner relinks.
                val st = _status.value.state
                if (st !in linkedStates && st != WaState.Disconnected) setState(WaState.Qr, "Waiting for you to link WhatsApp")
            }
            "loading" -> msg.num("percent")?.let { p ->
                if (_status.value.state !in linkedStates) setState(WaState.Authenticating, "Loading chats ${p.toInt()}%")
            }
            "authenticated" -> {
                _qr.value = null
                if (_status.value.state !in linkedStates) setState(WaState.Authenticating, "Linked, loading WhatsApp…")
            }
            "ready" -> onReady(page, msg.obj("me"))
            "logout" -> onLoggedOut()
            "ev" -> onEvent(msg)
            "log" -> Unit
        }
    }

    private fun onSocketState(state: String?) {
        lastSocketState = state
        when (state) {
            "UNPAIRED", "UNPAIRED_IDLE" -> when {
                // Linked before (now, or before the app restarted): WhatsApp unlinked this device.
                _status.value.state in linkedStates || wasLinked() -> onLoggedOut()
                _status.value.state != WaState.Disconnected -> setState(WaState.Qr, "Waiting for you to link WhatsApp")
            }
            // "Disconnected" stays until the owner links again (the UI treats it like "not linked").
            "OPENING", "PAIRING" -> if (_status.value.state !in linkedStates && _status.value.state != WaState.Qr && _status.value.state != WaState.Disconnected) setState(WaState.Authenticating, "Connecting…")
            "CONNECTED" -> if (_status.value.state !in linkedStates) setState(WaState.Authenticating, "Connected, loading chats…")
            "CONFLICT" -> setState(_status.value.state, "WhatsApp Web was opened elsewhere; taking it back…")
            "TIMEOUT" -> if (_status.value.state in linkedStates) setState(_status.value.state, "Reconnecting to WhatsApp…")
            "DEPRECATED_VERSION" -> setState(WaState.Error, "WhatsApp says this browser is too old. Update Android System WebView.")
            "TOS_BLOCK", "SMB_TOS_BLOCK", "PROXYBLOCK" -> setState(WaState.Disconnected, "WhatsApp refused the connection ($state).")
        }
        if (state == "CONNECTED" && _status.value.detail == "Reconnecting to WhatsApp…") setState(_status.value.state, null)
    }

    /** The account this app was linked to is still recorded (cleared when an unlink is noticed). */
    private fun wasLinked(): Boolean = runCatching { repo.getState(Presenter.STATE_WA_ME) != null }.getOrDefault(false)

    private fun onLoggedOut() {
        stopTimers()
        media.pause()
        readyPage = -1
        audit("wa_disconnected", "LOGOUT")
        scope.launch(Dispatchers.IO) {
            runCatching {
                repo.deleteState(Presenter.STATE_WA_ME)
                repo.deleteState(Presenter.STATE_WA_ME_LID)
            }
        }
        publish(_status.value.copy(me = null))
        setState(WaState.Disconnected, "Device was unlinked — link WhatsApp again")
    }

    private fun onEvent(m: JsonObject) {
        when (m.str("ev")) {
            "message" -> m.obj("msg")?.let { raw -> enqueue("message") { ingest.message(raw, Source.Live) } }
            "revoke" -> m.obj("after")?.let { after -> enqueue("revoke") { ingest.revoke(after, m.obj("before")) } }
            "revoke_me" -> m.str("id")?.let { id -> enqueue("revoke_me") { ingest.revokeMe(id) } }
            "edit" -> m.obj("msg")?.let { raw -> enqueue("edit") { ingest.edit(raw, m.str("newBody"), m.str("prevBody"), m.bool("blob")) } }
            "ack" -> m.str("id")?.let { id -> enqueue("ack") { ingest.ack(id, m.num("ack")?.toInt()) } }
            "reactions" -> (m["reactions"] as? JsonArray)?.forEach { el ->
                val r = el as? JsonObject ?: return@forEach
                enqueue("reaction") { ingest.reaction(r.str("msgId"), r.str("senderId"), r.str("reaction"), r.num("timestamp")) }
            }
            "chat_removed" -> m.str("id")?.let { id -> enqueue("chat_removed") { ingest.chatRemoved(id) } }
            "chat_archived" -> m.str("id")?.let { id -> enqueue("chat_archived") { ingest.chatArchived(id, m.bool("archived")) } }
        }
    }

    private suspend fun onReady(page: Int, me: JsonObject?) {
        if (page == readyPage) return
        readyPage = page
        _qr.value = null
        val id = me?.str("id")
        withContext(Dispatchers.IO) {
            if (id != null) {
                repo.setState(Presenter.STATE_WA_ME, id)
                me.str("lid")?.let { repo.setState(Presenter.STATE_WA_ME_LID, it) }
            }
            repo.audit("wa_ready")
        }
        publish(_status.value.copy(me = id?.let { WaMe(it, me.str("name"), phoneOf(it)) }))
        if (id != null) enqueue("me") { ingest.ensureMe(id, me.str("name")) }
        val firstSync = repo.getState(Sync.INITIAL_SYNC_KEY) == null
        setState(if (firstSync) WaState.Syncing else WaState.Ready, if (firstSync) "Importing chat history…" else null)
        media.resume()
        withContext(Dispatchers.IO) { media.restorePending() }
        val gen = generation
        startTimers(gen, page)
        // Sync and avatars touch the database: keep them off the app scope's main dispatcher.
        scope.launch(Dispatchers.IO) {
            try {
                if (firstSync) sync.initial() else periodicSync()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            if (gen == generation && page == readyPage && _status.value.state in linkedStates) setState(WaState.Ready)
            refreshAvatars(gen, page)
        }
    }

    /**
     * Reconcile, except during the first hours after linking: WhatsApp keeps syncing older history from the
     * phone then, so a few extra history passes pick up what the first import could not see yet.
     */
    private suspend fun periodicSync() {
        val first = repo.getState(Sync.INITIAL_SYNC_KEY)?.toLongOrNull() ?: 0
        val passes = repo.getState(HISTORY_PASSES_KEY)?.toIntOrNull() ?: 1
        if (first > 0 && System.currentTimeMillis() - first < HISTORY_CATCHUP_WINDOW_MS && passes < HISTORY_CATCHUP_PASSES) {
            repo.setState(HISTORY_PASSES_KEY, (passes + 1).toString())
            sync.historyPass()
            return
        }
        sync.reconcile()
    }

    private suspend fun refreshAvatars(gen: Int, page: Int) {
        if (gen != generation || page != readyPage) return
        runCatching { avatars.refresh() }
    }

    /* --------------------------------------------------------- timers */

    private fun startTimers(gen: Int, page: Int) {
        stopTimers()
        timers += scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(RECONCILE_MS)
                if (gen != generation || page != readyPage) break
                if (_status.value.state == WaState.Ready) runCatching { periodicSync() }
            }
        }
        timers += scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(AVATAR_INTERVAL_MS)
                if (gen != generation || page != readyPage) break
                refreshAvatars(gen, page)
            }
        }
    }

    private fun stopTimers() {
        timers.forEach { it.cancel() }
        timers.clear()
    }

    private var watchdog: Job? = null

    /** Proves the page still runs JavaScript; reloads it when it hangs. */
    private fun startWatchdog(gen: Int) {
        watchdog?.cancel()
        watchdogFailures = 0
        watchdog = scope.launch(Dispatchers.IO) {
            var sinceLoad = 0L
            while (isActive && gen == generation) {
                delay(WATCHDOG_INTERVAL_MS)
                sinceLoad += WATCHDOG_INTERVAL_MS
                if (gen != generation) break
                if (_borrowed.value) continue
                val r = try {
                    (bridge.call("ping", timeoutMs = 20_000) as? JsonPrimitive)?.contentOrNull
                } catch (_: PageError) {
                    null
                }
                if (BuildConfig.DEBUG) android.util.Log.d("walm", "watchdog ping=$r")
                val linked = _status.value.state in linkedStates
                val healthy = r == "ok" || (r == "loading" && !linked) || (r == null && !bridge.connected && _status.value.state == WaState.Error)
                watchdogFailures = if (healthy) 0 else watchdogFailures + 1
                // No WhatsApp Web at all after 3 minutes, or two failed probes: reload.
                val neverLoaded = waSeenPage != bridge.page && sinceLoad >= 180_000 && _status.value.state == WaState.Starting
                if (watchdogFailures >= 2 || neverLoaded) {
                    audit("wa_page_lost", "unresponsive")
                    withContext(Dispatchers.Main) {
                        if (gen == generation) scheduleReload(gen, "WhatsApp Web stopped responding")
                    }
                    break
                }
            }
        }
    }

    /* -------------------------------------------------------- network */

    private var networkWatched = false

    private fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.activeNetwork != null
    }.getOrDefault(true)

    /** Coming back online while waiting to retry: retry now. */
    private fun watchNetwork() {
        if (networkWatched) return
        networkWatched = true
        runCatching {
            context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch(Dispatchers.Main) {
                        if (_status.value.state == WaState.Error && reloadJob?.isActive == true && !_borrowed.value) {
                            reloadJob?.cancel()
                            attempt = 0
                            create()
                        }
                    }
                }
            })
        }
    }

    companion object {
        const val WA_HOST = "web.whatsapp.com"
        const val WA_URL = "https://web.whatsapp.com/"
        val ORIGINS = setOf("https://web.whatsapp.com")
        const val RECONCILE_MS = 10 * 60_000L
        const val AVATAR_INTERVAL_MS = 30 * 60_000L
        const val WATCHDOG_INTERVAL_MS = 60_000L
        const val LIVE_VIEW_WIDTH = 960
        const val HISTORY_PASSES_KEY = "history_passes"
        const val HISTORY_CATCHUP_PASSES = 4
        const val HISTORY_CATCHUP_WINDOW_MS = 3 * 3600_000L

        fun chromeMajor(ua: String): String = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1) ?: "140"

        /** WhatsApp Web only serves the full site to desktop browsers: present the WebView's Chrome as desktop. */
        fun desktopUserAgent(defaultUa: String): String =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajor(defaultUa)}.0.0.0 Safari/537.36"
    }
}
