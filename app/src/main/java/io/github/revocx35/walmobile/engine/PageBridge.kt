package io.github.revocx35.walmobile.engine

import androidx.webkit.JavaScriptReplyProxy
import io.github.revocx35.walmobile.core.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app side of walm-bridge.js. Messages from the page arrive on the main thread; they are parsed in
 * arrival order on one background coroutine ([inbox]) so the UI thread never parses large JSON.
 * Command results complete their [call]; everything else goes to [onMessage] (also in order).
 * Only the main frame of https://web.whatsapp.com can reach the bridge (addWebMessageListener rules).
 */
class PageBridge(scope: CoroutineScope, private val onMessage: suspend (page: Int, JsonObject) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true }
    private val inbox = Channel<Pair<String, JavaScriptReplyProxy>>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val nextId = AtomicInteger(1)

    @Volatile private var proxy: JavaScriptReplyProxy? = null

    /** Increments with every page load (a "hello"); calls never cross pages. */
    @Volatile var page = 0
        private set

    init {
        scope.launch(Dispatchers.Default) {
            for ((data, from) in inbox) {
                val obj = try {
                    json.parseToJsonElement(data) as? JsonObject
                } catch (_: Exception) {
                    null
                } ?: continue
                when (obj["t"]?.jsonPrimitive?.contentOrNull) {
                    "hello" -> {
                        proxy = from
                        page++
                        failAll("page reloaded")
                        onMessage(page, obj)
                    }
                    "res" -> {
                        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                        val d = pending.remove(id) ?: continue
                        if (obj["ok"]?.jsonPrimitive?.contentOrNull == "true") d.complete(obj["data"] ?: JsonNull)
                        else d.completeExceptionally(PageError(obj["error"]?.jsonPrimitive?.contentOrNull ?: "page error", obj["code"]?.jsonPrimitive?.contentOrNull))
                    }
                    else -> if (from === proxy) onMessage(page, obj)
                }
            }
        }
    }

    /** From the WebMessageListener (main thread). */
    fun receive(data: String?, from: JavaScriptReplyProxy) {
        if (data == null || data.length > MAX_MESSAGE) return
        inbox.trySend(data to from)
    }

    /** The page is gone (reload, crash, WebView destroyed). */
    fun detach(reason: String) {
        proxy = null
        failAll(reason)
    }

    private fun failAll(reason: String) {
        val all = pending.keys.toList()
        for (id in all) pending.remove(id)?.completeExceptionally(PageError(reason))
    }

    val connected: Boolean get() = proxy != null

    /** Runs `op` in the page; throws [PageError] (page error, reload, timeout). */
    @android.annotation.SuppressLint("RequiresFeature") // only reachable after WaEngine.start() checked the feature
    suspend fun call(op: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 60_000): JsonElement {
        val p = proxy ?: throw PageError("WhatsApp Web is not loaded")
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JsonElement>()
        pending[id] = d
        val msg = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("op", JsonPrimitive(op))
            put("args", args)
        }.toString()
        try {
            withContext(Dispatchers.Main) { p.postMessage(msg) }
            return withTimeout(timeoutMs) { d.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw PageError("$op timed out")
        } catch (e: IllegalStateException) {
            throw PageError("bridge unavailable")
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        /** Larger messages are dropped (a 200-message history page is well below this). */
        const val MAX_MESSAGE = 64 * 1024 * 1024
    }
}

/** A failed page call; [code] is the bridge's error code (e.g. "not_found", "send_failed") when the page gave one. */
class PageError(message: String, val code: String? = null) : Exception(message)

/** Helpers to read bridge JSON. */
internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
internal fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.contentOrNull == "true"
internal fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull()
internal fun JsonElement.asObj(): JsonObject? = this as? JsonObject
internal fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) {
        when (v) {
            null -> put(k, JsonNull)
            is String -> put(k, JsonPrimitive(v))
            is Number -> put(k, JsonPrimitive(v))
            is Boolean -> put(k, JsonPrimitive(v))
            else -> error("unsupported arg")
        }
    }
}

internal fun ApiException.Companion.fromPage(e: Throwable) = ApiException(503, "unavailable", "WhatsApp Web is not ready.", cause = e)
