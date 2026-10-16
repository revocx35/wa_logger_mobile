package io.github.revocx35.walmobile.engine

import android.content.Context
import io.github.revocx35.walmobile.core.ApiException
import io.github.revocx35.walmobile.core.SendMediaKind
import io.github.revocx35.walmobile.core.ServerEvent
import io.github.revocx35.walmobile.security.Vault
import io.github.revocx35.walmobile.store.EventBus
import io.github.revocx35.walmobile.store.MediaFiles
import io.github.revocx35.walmobile.store.Repo
import io.github.revocx35.walmobile.store.SettingsStore
import io.github.revocx35.walmobile.store.values
import io.github.revocx35.walmobile.ui.MessageSender
import io.github.revocx35.walmobile.ui.OutgoingFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.InputStream
import java.util.Base64

/** The page operations the sender uses (the engine's bridge; a fake in tests). */
fun interface PageOps {
    suspend fun call(op: String, args: JsonObject, timeoutMs: Long): JsonElement
}

/**
 * Sending as the linked account through the engine's WhatsApp Web page (walm-bridge.js, the same WhatsApp Web
 * send actions as the wa_logger server's walpage.ts). Sent messages come back through the page's message
 * events and are logged like any other; media we send is stored from the original file, because WhatsApp adds the
 * message before its upload finishes (so there is nothing to download yet).
 */
class LocalSender(
    private val context: Context,
    private val page: PageOps,
    private val linked: () -> Boolean,
    private val repo: Repo,
    private val vault: Vault,
    private val files: MediaFiles,
    private val settings: SettingsStore,
    private val events: EventBus,
    private val ensureChat: suspend (String) -> Unit,
) : MessageSender {
    private val sent = ArrayDeque<Long>()

    /** At most [MAX_PER_HOUR] sends per rolling hour (protects the account from runaway sending). */
    @Synchronized
    private fun charge() {
        val now = System.currentTimeMillis()
        while (sent.isNotEmpty() && sent.first() < now - 3600_000) sent.removeFirst()
        if (sent.size >= MAX_PER_HOUR) throw ApiException(429, "rate_limited", "Too many messages sent in the last hour.", ((sent.first() + 3600_000 - now) / 1000).toInt().coerceAtLeast(1))
        sent.addLast(now)
    }

    private suspend fun op(op: String, timeoutMs: Long, vararg args: Pair<String, Any?>): JsonElement {
        if (!linked()) throw ApiException.unavailable("WhatsApp is not connected right now.")
        val json = JsonObject(
            args.associate { (k, v) ->
                k to when (v) {
                    null -> kotlinx.serialization.json.JsonNull
                    is String -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
            },
        )
        try {
            return page.call(op, json, timeoutMs)
        } catch (e: PageError) {
            throw toApi(e)
        }
    }

    private fun idOf(r: JsonElement): String? = ((r as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull

    override suspend fun sendText(chatId: String, text: String, quotedId: String?) {
        if (text.isBlank()) throw ApiException.validation("Message is empty.")
        if (text.length > 65_536) throw ApiException.validation("The message is too long.")
        charge()
        op("sendText", 70_000, "chatId" to chatId, "text" to text, "quotedId" to quotedId)
        audit("text")
        markReadQuietly(chatId)
    }

    override suspend fun sendFile(chatId: String, file: OutgoingFile, kind: SendMediaKind, caption: String?, quotedId: String?) {
        charge()
        val resolver = context.applicationContext.contentResolver
        val open: () -> InputStream = { resolver.openInputStream(file.uri) ?: throw ApiException.validation("Cannot read the file.") }
        val size = withContext(Dispatchers.IO) { if (file.size >= 0) file.size else open().use { it.skipAll() } }
        if (size <= 0) throw ApiException.validation("The file is empty.")
        if (size > io.github.revocx35.walmobile.ui.SEND_MAX_BYTES) throw ApiException.validation("The file is too large (64 MB at most).")
        val token = (op("uploadBegin", 30_000, "size" to size) as? JsonPrimitive)?.contentOrNull ?: throw ApiException.unavailable("WhatsApp Web did not accept the file.")
        var sentOk = false
        try {
            withContext(Dispatchers.IO) {
                open().use { input ->
                    val buf = ByteArray(CHUNK)
                    var offset = 0L
                    while (true) {
                        val n = input.readFully(buf, CHUNK)
                        if (n <= 0) break
                        op("uploadChunk", 60_000, "token" to token, "offset" to offset, "b64" to Base64.getEncoder().encodeToString(buf.copyOf(n)))
                        offset += n
                    }
                    if (offset != size) throw ApiException.validation("The file changed while sending.")
                }
            }
            val kindName = when (kind) {
                SendMediaKind.Auto -> "auto"
                SendMediaKind.Document -> "document"
                SendMediaKind.Voice -> "voice"
                SendMediaKind.Gif -> "gif"
            }
            val r = op("sendMedia", 330_000, "chatId" to chatId, "token" to token, "mime" to file.mime, "filename" to file.name, "kind" to kindName, "caption" to caption, "quotedId" to quotedId)
            sentOk = true
            audit(kindName)
            markReadQuietly(chatId)
            idOf(r)?.let { id -> runCatching { storeSentMedia(id, open, file.mime, file.name) } }
        } finally {
            if (!sentOk) runCatching { page.call("uploadDrop", JsonObject(mapOf("token" to JsonPrimitive(token))), 10_000) }
        }
    }

    /** Encrypts the file we just sent into the media store for its logged message. */
    suspend fun storeSentMedia(messageId: String, open: () -> InputStream, mime: String?, filename: String?) = withContext(Dispatchers.IO) {
        var row = repo.getMessage(messageId)
        var tries = 0
        while (row == null && tries++ < 20) {
            delay(500)
            row = repo.getMessage(messageId)
        }
        if (row == null || row.mediaId != null || row.mediaStatus == "downloaded") return@withContext
        val writer = vault.writer() ?: return@withContext
        val pending = files.begin(writer)
        try {
            open().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) pending.writer.write(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            pending.writer.abort()
            throw e
        }
        val (mediaId, size) = files.finish(pending, writer, "message", mime, filename)
        repo.updateMessage(messageId, values("media_id" to mediaId, "media_status" to "downloaded", "media_size" to size, "updated_at" to System.currentTimeMillis()))
        events.emit(ServerEvent.MessageUpdate(row.chatId, messageId, "media"))
    }

    override suspend fun react(messageId: String, emoji: String) {
        charge()
        op("react", 40_000, "id" to messageId, "emoji" to emoji)
    }

    override suspend fun revoke(messageId: String) {
        charge()
        op("revoke", 40_000, "id" to messageId)
        audit("revoke")
    }

    override suspend fun edit(messageId: String, text: String) {
        if (text.isBlank()) throw ApiException.validation("Message is empty.")
        charge()
        op("edit", 70_000, "id" to messageId, "text" to text)
    }

    override suspend fun markRead(chatId: String) {
        op("markRead", 30_000, "chatId" to chatId)
    }

    private suspend fun markReadQuietly(chatId: String) {
        runCatching { markRead(chatId) }
    }

    override suspend fun newChat(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length !in 6..15) throw ApiException.validation("Enter the full number with country code.")
        val id = (op("resolvePhone", 30_000, "phone" to digits) as? JsonPrimitive)?.contentOrNull
            ?: throw ApiException.notFound("WhatsApp account for this number")
        ensureChat(id)
        return id
    }

    override suspend fun readReceipts(): Boolean = withContext(Dispatchers.IO) { settings.get().readReceipts }

    private suspend fun audit(kind: String) = withContext(Dispatchers.IO) { runCatching { repo.audit("message_sent", kind) } }

    companion object {
        const val MAX_PER_HOUR = 300
        /** Base64 slices into the page (web messages carry strings). */
        const val CHUNK = 512 * 1024

        /** Bridge error codes → errors the UI shows as they are (the page writes them to be shown). */
        fun toApi(e: PageError): ApiException = when (e.code) {
            "not_found" -> ApiException(404, "not_found", e.message ?: "Not found.")
            "not_allowed" -> ApiException.conflict(e.message ?: "Not allowed.")
            "validation" -> ApiException.validation(e.message ?: "Invalid.")
            "send_failed" -> ApiException(502, "send_failed", e.message ?: "WhatsApp could not send it.")
            "timeout" -> ApiException(504, "timeout", e.message ?: "WhatsApp did not answer in time.")
            null -> ApiException.unavailable("WhatsApp Web is not ready. Try again in a moment.")
            else -> ApiException(502, "wa_error", "WhatsApp Web could not do that.")
        }
    }
}

private fun InputStream.skipAll(): Long {
    var total = 0L
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) return total
        total += n
    }
}

/** Reads up to [len] bytes (fewer only at the end). InputStream.readNBytes needs Android 13. */
private fun InputStream.readFully(buf: ByteArray, len: Int): Int {
    var n = 0
    while (n < len) {
        val r = read(buf, n, len - n)
        if (r < 0) break
        n += r
    }
    return n
}
