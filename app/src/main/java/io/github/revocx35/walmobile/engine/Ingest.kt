package io.github.revocx35.walmobile.engine

import io.github.revocx35.walmobile.core.ChatKind
import io.github.revocx35.walmobile.core.MessageType
import io.github.revocx35.walmobile.core.ServerEvent
import io.github.revocx35.walmobile.core.WaJson
import io.github.revocx35.walmobile.core.crypto.DataWriter
import io.github.revocx35.walmobile.core.crypto.F
import io.github.revocx35.walmobile.core.wa.MappedMessage
import io.github.revocx35.walmobile.core.wa.MessageMeta
import io.github.revocx35.walmobile.core.wa.chatKind
import io.github.revocx35.walmobile.core.wa.looksLikeBase64Blob
import io.github.revocx35.walmobile.core.wa.mapMessage
import io.github.revocx35.walmobile.security.Vault
import io.github.revocx35.walmobile.store.EventBus
import io.github.revocx35.walmobile.store.MessageRow
import io.github.revocx35.walmobile.store.Repo
import io.github.revocx35.walmobile.store.SettingsStore
import io.github.revocx35.walmobile.store.values
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class ChatLite(
    val id: String = "",
    val name: String? = null,
    /** epoch ms */
    val timestamp: Long? = null,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val muted: Boolean = false,
)

@Serializable
data class ContactInfo(
    val name: String? = null,
    val pushname: String? = null,
    /** "+<digits>" when WhatsApp exposes it (LID contacts map to a phone number). */
    val phone: String? = null,
    val isMe: Boolean = false,
    val isBusiness: Boolean = false,
)

/** The page reads ingest needs (abstracted for tests). */
interface PageApi {
    suspend fun chatInfo(chatId: String): ChatLite?
    suspend fun contactInfo(id: String): ContactInfo?
}

enum class Priority { Live, History }

interface MediaSink {
    fun enqueue(messageId: String, priority: Priority)
}

enum class Source(val db: String) { Live("live"), History("history") }

/** Notified after a message was flagged "deleted for everyone" (for the optional notification). */
fun interface DeletionListener {
    fun onDeleted(chatId: String, messageId: String)
}

private const val CHAT_REFRESH_MS = 6 * 3600_000L
private const val CONTACT_REFRESH_MS = 24 * 3600_000L

/**
 * Turns WhatsApp events into encrypted rows (port of the server's `wa/ingest.ts`). Every handler is an
 * idempotent upsert keyed by the message id, so live events, history import and reconcile can overlap.
 * Called from one thread at a time (WaEngine's ingest queue), in the order WhatsApp emitted the events.
 */
class Ingest(
    private val repo: Repo,
    private val vault: Vault,
    private val settings: SettingsStore,
    private val events: EventBus,
    private val api: PageApi,
    private val media: MediaSink,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val chatSeen = HashMap<String, Long>()
    private val contactSeen = HashMap<String, Long>()
    @Volatile var deletionListener: DeletionListener? = null

    private val writer: DataWriter? get() = vault.writer()

    fun resetCaches() {
        chatSeen.clear()
        contactSeen.clear()
    }

    /* ------------------------------------------------------------ chats */

    suspend fun ensureChat(chatId: String, force: Boolean = false) {
        val w = writer ?: return
        val t = now()
        val seen = chatSeen[chatId]
        if (!force && seen != null && t - seen < CHAT_REFRESH_MS && repo.getChat(chatId) != null) return
        chatSeen[chatId] = t
        val kind = chatKind(chatId)
        val info = if (kind == ChatKind.Status) null else runCatching { api.chatInfo(chatId) }.getOrNull()
        val name = if (kind == ChatKind.Status) "Status updates" else info?.name
        repo.upsertChat(chatId, kind.db(), w.encrypt(F.chatName(chatId), name), info?.timestamp, info?.archived, info?.pinned, info?.muted, t)
        events.emit(ServerEvent.ChatUpdate(chatId))
    }

    /** From history/reconcile with already-fetched chat info. */
    fun upsertChatInfo(chat: ChatLite) {
        val w = writer ?: return
        val t = now()
        chatSeen[chat.id] = t
        val kind = chatKind(chat.id)
        repo.upsertChat(chat.id, kind.db(), w.encrypt(F.chatName(chat.id), if (kind == ChatKind.Status) "Status updates" else chat.name), chat.timestamp, chat.archived, chat.pinned, chat.muted, t)
    }

    suspend fun ensureContact(id: String, notifyName: String?, force: Boolean = false) {
        val w = writer ?: return
        val t = now()
        val seen = contactSeen[id]
        if (!force && seen != null && t - seen < CONTACT_REFRESH_MS) return
        contactSeen[id] = t
        val info = runCatching { api.contactInfo(id) }.getOrNull()
        repo.upsertContact(
            id,
            w.encrypt(F.contactName(id), info?.name),
            w.encrypt(F.contactPushname(id), info?.pushname ?: notifyName),
            w.encrypt(F.contactPhone(id), info?.phone),
            info?.isMe ?: false,
            info?.isBusiness ?: false,
            t,
        )
    }

    /* --------------------------------------------------------- messages */

    private fun mediaStatusFor(m: MappedMessage, source: Source): String = when {
        m.isViewOnce -> "view_once"
        m.media == null -> "none"
        !m.downloadable -> "unavailable"
        (m.media?.size ?: 0) > settings.mediaMaxBytes() -> "too_large"
        source == Source.History && !settings.get().downloadHistoryMedia -> "skipped"
        else -> "pending"
    }

    private fun newRow(m: MappedMessage, source: Source, t: Long, w: DataWriter): MessageRow = MessageRow(
        id = m.id,
        chatId = m.chatId,
        senderId = m.senderId,
        fromMe = m.fromMe,
        ts = m.ts,
        type = m.type.db(),
        bodyEnc = w.encrypt(F.body(m.id), m.body),
        metaEnc = m.meta?.let { w.encrypt(F.meta(m.id), WaJson.encodeToString(MessageMeta.serializer(), it)) },
        thumbEnc = m.thumbBase64?.let { b -> runCatching { java.util.Base64.getDecoder().decode(b) }.getOrNull() }?.let { w.encrypt(F.thumb(m.id), it) },
        quotedId = m.quotedStanzaId,
        mediaId = null,
        mediaStatus = mediaStatusFor(m, source),
        mediaAttempts = 0,
        mediaSize = m.media?.size,
        mediaW = m.media?.width,
        mediaH = m.media?.height,
        mediaDuration = m.media?.durationSec,
        isViewOnce = m.isViewOnce,
        isForwarded = m.isForwarded,
        isStatus = m.isStatus,
        ack = m.ack,
        deletedAt = null,
        deletedBy = null,
        deletedForMeAt = null,
        editedAt = if (m.editKey != null) (m.editTs ?: m.ts) else null,
        lastEditKey = m.editKey,
        source = source.db,
        capturedAt = t,
        updatedAt = t,
    )

    /** message_create / history / reconcile for one message. Returns the stored id or null. */
    suspend fun message(raw: JsonObject, source: Source): String? {
        val w = writer ?: return null
        val m = mapMessage(raw)
        if (m.skip) return null
        if (m.isStatus && !settings.get().logStatus) return null

        ensureChat(m.chatId)
        if (m.senderId != null && !m.fromMe) ensureContact(m.senderId!!, m.notifyName)

        val t = now()
        val existing = repo.getMessage(m.id)
        if (existing == null) {
            if (m.revoked) {
                insertRevokedStub(m, source, t)
                return m.id
            }
            val row = newRow(m, source, t, w)
            if (repo.insertMessage(row)) {
                repo.bumpChatTs(m.chatId, m.ts, t)
                if (row.mediaStatus == "pending") media.enqueue(m.id, if (source == Source.Live) Priority.Live else Priority.History)
                events.emit(ServerEvent.NewMessage(m.chatId, m.id))
            }
            if (m.type == MessageType.System && (m.meta?.system?.subtype == "subject" || m.meta?.system?.subtype == "create")) ensureChat(m.chatId, force = true)
            return m.id
        }
        updateExisting(existing, m, source, t, w)
        return m.id
    }

    private fun insertRevokedStub(m: MappedMessage, source: Source, t: Long) {
        val inserted = repo.insertMessage(
            MessageRow(
                id = m.id, chatId = m.chatId, senderId = m.senderId, fromMe = m.fromMe, ts = m.ts, type = "revoked",
                bodyEnc = null, metaEnc = null, thumbEnc = null, quotedId = null, mediaId = null, mediaStatus = "none",
                mediaAttempts = 0, mediaSize = null, mediaW = null, mediaH = null, mediaDuration = null, isViewOnce = false,
                isForwarded = false, isStatus = m.isStatus, ack = m.ack,
                // Seen only as already deleted during history import: the deletion time is unknown, so use
                // the message time rather than pretending it was deleted now.
                deletedAt = if (source == Source.History) (m.ts.takeIf { it > 0 } ?: t) else t,
                deletedBy = m.revokedBy ?: "sender", deletedForMeAt = null, editedAt = null, lastEditKey = null,
                source = source.db, capturedAt = t, updatedAt = t,
            ),
        )
        if (inserted) {
            repo.bumpChatTs(m.chatId, m.ts, t)
            events.emit(ServerEvent.NewMessage(m.chatId, m.id))
        }
    }

    private fun markDeleted(existing: MessageRow, by: String, t: Long) {
        repo.updateMessage(existing.id, values("deleted_at" to t, "deleted_by" to by, "updated_at" to t))
        events.emit(ServerEvent.MessageUpdate(existing.chatId, existing.id, "deleted"))
        deletionListener?.onDeleted(existing.chatId, existing.id)
    }

    private fun updateExisting(existing: MessageRow, m: MappedMessage, source: Source, t: Long, w: DataWriter) {
        // Deleted for everyone: keep the logged content, just flag it.
        if (m.revoked) {
            if (existing.deletedAt == null) markDeleted(existing, m.revokedBy ?: "sender", t)
            return
        }
        // Placeholder rows (undecrypted ciphertext / revoked stub) get their real content now.
        val placeholder = existing.type == "ciphertext" || (existing.type == "revoked" && existing.bodyEnc == null && existing.metaEnc == null)
        if (placeholder && m.type != MessageType.Ciphertext) {
            val f = newRow(m, source, t, w)
            repo.updateMessage(
                existing.id,
                values(
                    "sender_id" to f.senderId, "from_me" to f.fromMe, "ts" to f.ts, "type" to f.type, "body_enc" to f.bodyEnc,
                    "meta_enc" to f.metaEnc, "thumb_enc" to f.thumbEnc, "quoted_id" to f.quotedId, "media_status" to f.mediaStatus,
                    "media_attempts" to 0, "media_size" to f.mediaSize, "media_w" to f.mediaW, "media_h" to f.mediaH,
                    "media_duration" to f.mediaDuration, "is_view_once" to f.isViewOnce, "is_forwarded" to f.isForwarded,
                    "is_status" to f.isStatus, "ack" to f.ack, "edited_at" to f.editedAt, "last_edit_key" to f.lastEditKey,
                    // A revoked stub stays flagged as deleted.
                    "deleted_at" to existing.deletedAt, "deleted_by" to existing.deletedBy, "updated_at" to t,
                ),
            )
            if (f.mediaStatus == "pending") media.enqueue(existing.id, if (source == Source.Live) Priority.Live else Priority.History)
            events.emit(ServerEvent.MessageUpdate(existing.chatId, existing.id, "edited"))
            return
        }
        // An edit we have not recorded yet (missed live event, or history/reconcile).
        if (m.editKey != null && m.editKey != existing.lastEditKey && existing.deletedAt == null && m.type != MessageType.Ciphertext) {
            applyEdit(existing, m.body, m.editKey!!, m.editTs ?: t, t, w)
        }
        val patch = values()
        var reason = "media"
        if (m.ack != null && (existing.ack == null || m.ack!! > existing.ack)) {
            patch.put("ack", m.ack)
            reason = "ack"
        }
        // Media that failed earlier can be retried when WhatsApp offers it again.
        if (existing.mediaStatus == "failed" && m.downloadable && existing.mediaAttempts < 8) {
            patch.put("media_status", "pending")
        }
        if (patch.size() > 0) {
            patch.put("updated_at", t)
            repo.updateMessage(existing.id, patch)
            // Enqueue only after the row says "pending": the queue skips rows that don't.
            if (patch.getAsString("media_status") == "pending") media.enqueue(existing.id, if (source == Source.Live) Priority.Live else Priority.History)
            events.emit(ServerEvent.MessageUpdate(existing.chatId, existing.id, reason))
        }
    }

    /** Moves the current body ciphertext into the edit history (same AAD context) and stores the new body. */
    private fun applyEdit(existing: MessageRow, newBody: String?, editKey: String, editTs: Long, t: Long, w: DataWriter) {
        repo.transaction {
            repo.insertEdit(existing.id, existing.bodyEnc, t)
            repo.updateMessage(existing.id, values("body_enc" to w.encrypt(F.body(existing.id), newBody), "edited_at" to editTs, "last_edit_key" to editKey, "updated_at" to t))
        }
        events.emit(ServerEvent.MessageUpdate(existing.chatId, existing.id, "edited"))
    }

    /** Body/caption changed (`blob`: it was a thumbnail update, texts omitted by the bridge). */
    suspend fun edit(raw: JsonObject, newBody: String?, prevBody: String?, blob: Boolean) {
        val w = writer ?: return
        val m = mapMessage(raw)
        if (m.skip || m.revoked) return
        val existing = repo.getMessage(m.id)
        if (existing == null) {
            // First sight of an edited message: store it and keep the previous text as history.
            message(raw, Source.Live)
            if (!blob && prevBody != null && !looksLikeBase64Blob(prevBody) && prevBody != m.body && repo.getMessage(m.id) != null) {
                repo.insertEdit(m.id, w.encrypt(F.body(m.id), prevBody), now())
            }
            return
        }
        if (existing.type == "ciphertext" || existing.deletedAt != null) {
            message(raw, Source.Live)
            return
        }
        if (blob || looksLikeBase64Blob(newBody) || looksLikeBase64Blob(prevBody)) return
        if (m.editKey != null) {
            if (m.editKey != existing.lastEditKey) applyEdit(existing, m.body, m.editKey!!, m.editTs ?: now(), now(), w)
            return
        }
        if (prevBody != null && newBody != null && prevBody != newBody && m.type == MessageType.Chat) {
            val t = now()
            applyEdit(existing, newBody, "evt:$t", t, t, w)
        }
    }

    /** Deleted for everyone. [beforeRaw] is the original message if the page still had it. */
    suspend fun revoke(afterRaw: JsonObject, beforeRaw: JsonObject?) {
        writer ?: return
        val after = mapMessage(afterRaw)
        if (after.id.isEmpty()) return
        val t = now()
        var existing = repo.getMessage(after.id)
        if (beforeRaw != null) {
            val before = mapMessage(beforeRaw)
            val target = repo.getMessage(before.id) ?: existing
            val withAfterId = JsonObject(beforeRaw + ("id" to (afterRaw["id"] ?: beforeRaw["id"] ?: JsonPrimitive(""))))
            if (target == null || (target.type == "revoked" && target.bodyEnc == null)) {
                // Never logged (or only as a stub): store the original content first.
                message(withAfterId, Source.Live)
                existing = repo.getMessage(after.id)
            } else {
                existing = target
            }
        }
        if (existing == null) {
            ensureChat(after.chatId)
            insertRevokedStub(after.copy(revoked = true), Source.Live, t)
            deletionListener?.onDeleted(after.chatId, after.id)
            return
        }
        if (existing.deletedAt == null) markDeleted(existing, after.revokedBy ?: "sender", t)
    }

    /** Deleted only on the owner's devices. */
    fun revokeMe(messageId: String) {
        val existing = repo.getMessage(messageId) ?: return
        if (existing.deletedForMeAt != null) return
        val t = now()
        repo.updateMessage(existing.id, values("deleted_for_me_at" to t, "updated_at" to t))
        events.emit(ServerEvent.MessageUpdate(existing.chatId, existing.id, "deleted"))
    }

    fun reaction(messageId: String?, senderId: String?, emoji: String?, timestamp: Double?) {
        val w = writer ?: return
        if (messageId.isNullOrEmpty() || senderId.isNullOrEmpty()) return
        val existing = repo.getMessage(messageId) ?: return
        val e = emoji ?: ""
        var ts = timestamp?.toLong() ?: now()
        if (ts < 1_000_000_000_000L) ts *= 1000
        repo.upsertReaction(messageId, senderId, if (e.isNotEmpty()) w.encrypt(F.reaction(messageId, senderId), e) else null, ts, e.isEmpty())
        events.emit(ServerEvent.MessageUpdate(existing.chatId, messageId, "reaction"))
    }

    fun ack(messageId: String?, ack: Int?) {
        if (messageId == null || ack == null) return
        val existing = repo.getMessage(messageId) ?: return
        if (existing.ack != null && existing.ack >= ack) return
        repo.updateMessage(existing.id, values("ack" to ack, "updated_at" to now()))
        events.emit(ServerEvent.MessageUpdate(existing.chatId, existing.id, "ack"))
    }

    fun chatRemoved(chatId: String) {
        if (repo.getChat(chatId) == null) return
        repo.markChatRemoved(chatId, now())
        events.emit(ServerEvent.ChatUpdate(chatId))
    }

    fun chatArchived(chatId: String, archived: Boolean) {
        if (repo.getChat(chatId) == null) return
        repo.setChatArchived(chatId, archived, now())
        events.emit(ServerEvent.ChatUpdate(chatId))
    }

    /** Remembers the linked account itself as a contact ("You"). */
    suspend fun ensureMe(id: String, name: String?) {
        ensureContact(id, name, force = true)
    }

    companion object {
        /** A minimal raw message, for tests and diagnostics. */
        fun rawText(id: String, chatId: String, fromMe: Boolean, body: String, tSec: Long): JsonObject = buildJsonObject {
            put("id", buildJsonObject {
                put("fromMe", JsonPrimitive(fromMe))
                put("remote", JsonPrimitive(chatId))
                put("id", JsonPrimitive(id))
            })
            put("type", JsonPrimitive("chat"))
            put("body", JsonPrimitive(body))
            put("t", JsonPrimitive(tSec))
            put("from", JsonPrimitive(if (fromMe) "me@c.us" else chatId))
        }
    }
}

fun ChatKind.db(): String = WaJson.encodeToString(ChatKind.serializer(), this).trim('"')
fun MessageType.db(): String = WaJson.encodeToString(MessageType.serializer(), this).trim('"')
