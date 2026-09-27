package io.github.revocx35.walmobile.store

import io.github.revocx35.walmobile.core.ChatKind
import io.github.revocx35.walmobile.core.ChatRef
import io.github.revocx35.walmobile.core.ChatSummary
import io.github.revocx35.walmobile.core.MediaInfo
import io.github.revocx35.walmobile.core.MediaStatus
import io.github.revocx35.walmobile.core.Mention
import io.github.revocx35.walmobile.core.Message
import io.github.revocx35.walmobile.core.MessagePreview
import io.github.revocx35.walmobile.core.MessageType
import io.github.revocx35.walmobile.core.QuotedSnapshot
import io.github.revocx35.walmobile.core.Reaction
import io.github.revocx35.walmobile.core.WaJson
import io.github.revocx35.walmobile.core.crypto.DataReader
import io.github.revocx35.walmobile.core.crypto.F
import io.github.revocx35.walmobile.core.wa.MessageMeta
import io.github.revocx35.walmobile.core.wa.phoneOf
import io.github.revocx35.walmobile.core.wa.renderSystemText
import kotlinx.serialization.json.Json

/** `walm://media/<id>`: served by the app's own image/video loaders (media/MediaAccess). */
fun mediaUrl(mediaId: Long) = "walm://media/$mediaId"

private const val PREVIEW_LEN = 120

private val MEDIA_LABEL = mapOf(
    MessageType.Image to "📷 Photo",
    MessageType.Video to "🎥 Video",
    MessageType.Gif to "GIF",
    MessageType.Audio to "🎵 Audio",
    MessageType.Ptt to "🎤 Voice message",
    MessageType.Document to "📄 Document",
    MessageType.Sticker to "Sticker",
    MessageType.Location to "📍 Location",
    MessageType.LiveLocation to "📍 Live location",
    MessageType.Vcard to "👤 Contact",
    MessageType.MultiVcard to "👥 Contacts",
    MessageType.Poll to "📊 Poll",
    MessageType.CallLog to "📞 Call",
    MessageType.ViewOnce to "View-once media",
    MessageType.Revoked to "Deleted message",
    MessageType.Ciphertext to "Waiting for this message",
)

private val MEDIA_TYPES = setOf("image", "video", "gif", "audio", "ptt", "document", "sticker", "view_once")

private val metaJson = Json(WaJson) {}

fun messageTypeOf(s: String): MessageType = runCatching { WaJson.decodeFromString(MessageType.serializer(), "\"$s\"") }.getOrDefault(MessageType.Unknown)
fun mediaStatusOf(s: String): MediaStatus = runCatching { WaJson.decodeFromString(MediaStatus.serializer(), "\"$s\"") }.getOrDefault(MediaStatus.None)
fun chatKindOf(s: String): ChatKind = runCatching { WaJson.decodeFromString(ChatKind.serializer(), "\"$s\"") }.getOrDefault(ChatKind.User)

/**
 * Turns encrypted rows into the UI's DTOs (port of the server's `api/view.ts`). Short-lived: one per
 * request, with small caches of decrypted names.
 */
class Presenter(private val repo: Repo, private val reader: DataReader) {
    private val contactCache = HashMap<String, ContactRow?>()
    private val nameCache = HashMap<String, String>()
    val meId: String? = repo.getState(STATE_WA_ME)
    private val meLid: String? = repo.getState(STATE_WA_ME_LID)

    private fun isMe(id: String?) = id != null && (id == meId || id == meLid)

    /* ---------------------------------------------------------- names */

    fun preloadContacts(ids: Collection<String>) {
        val missing = ids.filter { it !in contactCache }.distinct()
        if (missing.isEmpty()) return
        for (row in repo.getContactsByIds(missing)) contactCache[row.id] = row
        for (id in missing) if (id !in contactCache) contactCache[id] = null
    }

    fun contactName(id: String?): String {
        if (id == null) return "Unknown"
        if (isMe(id)) return "You"
        nameCache[id]?.let { return it }
        if (id !in contactCache) preloadContacts(listOf(id))
        val row = contactCache[id]
        var name: String? = null
        if (row != null) {
            name = reader.tryText(F.contactName(id), row.nameEnc)?.ifEmpty { null }
            if (name == null) reader.tryText(F.contactPushname(id), row.pushnameEnc)?.ifEmpty { null }?.let { name = "~$it" }
            if (name == null) name = reader.tryText(F.contactPhone(id), row.phoneEnc)?.ifEmpty { null }
        }
        val out = name ?: phoneOf(id) ?: id.substringBefore('@')
        nameCache[id] = out
        return out
    }

    fun chatName(chat: ChatRow): String {
        reader.tryText(F.chatName(chat.id), chat.nameEnc)?.ifEmpty { null }?.let { return it }
        return when (chat.kind) {
            "user" -> contactName(chat.id)
            "status" -> "Status updates"
            else -> phoneOf(chat.id) ?: chat.id.substringBefore('@')
        }
    }

    fun chatRef(chat: ChatRow?, chatId: String): ChatRef =
        if (chat != null) ChatRef(chat.id, chatName(chat), chatKindOf(chat.kind)) else ChatRef(chatId, phoneOf(chatId) ?: chatId.substringBefore('@'), ChatKind.User)

    /* ------------------------------------------------------- messages */

    fun meta(row: MessageRow): MessageMeta? =
        reader.tryText(F.meta(row.id), row.metaEnc)?.let { runCatching { metaJson.decodeFromString(MessageMeta.serializer(), it) }.getOrNull() }

    private fun text(row: MessageRow, meta: MessageMeta?): String? {
        if (row.type == "system" && meta?.system != null) return renderSystemText(meta.system!!, { contactName(it) }, meta.rawType)
        reader.tryText(F.body(row.id), row.bodyEnc)?.ifEmpty { null }?.let { return it }
        meta?.invite?.let { return "Group invite" + (it.groupName?.let { n -> ": $n" } ?: "") }
        return null
    }

    private fun media(row: MessageRow): MediaInfo? {
        val status = mediaStatusOf(row.mediaStatus)
        val hasMedia = status != MediaStatus.None || row.mediaId != null || row.type in MEDIA_TYPES
        if (!hasMedia) return null
        var mime: String? = null
        var filename: String? = null
        row.mediaId?.let { id ->
            repo.getMedia(id)?.let { m ->
                mime = reader.tryText(F.mediaMime(m.path), m.mimeEnc)
                filename = reader.tryText(F.mediaFilename(m.path), m.filenameEnc)
            }
        }
        val thumb = reader.tryBytes(F.thumb(row.id), row.thumbEnc)?.let { "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(it) }
        return MediaInfo(
            status = status,
            url = if (row.mediaId != null && status == MediaStatus.Downloaded) mediaUrl(row.mediaId) else null,
            mime = mime,
            filename = filename,
            size = row.mediaSize,
            width = row.mediaW,
            height = row.mediaH,
            durationSec = row.mediaDuration,
            thumbDataUrl = thumb,
        )
    }

    /** Full DTOs for a batch of rows (one query each for reactions, edits and contacts). */
    fun messages(rows: List<MessageRow>): List<Message> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.id }
        val metas = rows.associate { it.id to meta(it) }
        val people = HashSet<String>()
        for (r in rows) {
            r.senderId?.let(people::add)
            val m = metas[r.id]
            m?.quoted?.participant?.let(people::add)
            m?.mentions?.let(people::addAll)
            m?.system?.author?.let(people::add)
            m?.system?.recipients?.let(people::addAll)
        }
        val reactions = repo.reactionsFor(ids)
        for (r in reactions) people.add(r.senderId)
        preloadContacts(people)
        val editCounts = repo.editCounts(ids)
        val byMsg = HashMap<String, MutableList<Reaction>>()
        for (r in reactions) {
            if (r.removedAt != null) continue
            val emoji = reader.tryText(F.reaction(r.messageId, r.senderId), r.emojiEnc)?.ifEmpty { null } ?: continue
            byMsg.getOrPut(r.messageId) { mutableListOf() }.add(Reaction(r.senderId, contactName(r.senderId), emoji, r.ts))
        }
        return rows.map { row ->
            val meta = metas[row.id]
            val quoted = meta?.quoted?.let { q ->
                val fromMe = isMe(q.participant)
                QuotedSnapshot(
                    id = q.stanzaId?.let { repo.findByStanza(row.chatId, it) },
                    senderName = if (fromMe) "You" else q.participant?.let { contactName(it) },
                    fromMe = fromMe,
                    type = q.type,
                    text = q.text,
                )
            }
            val edits = editCounts[row.id] ?: 0
            Message(
                id = row.id,
                chatId = row.chatId,
                senderId = row.senderId,
                senderName = if (row.fromMe) "You" else row.senderId?.let { contactName(it) },
                fromMe = row.fromMe,
                ts = row.ts,
                type = messageTypeOf(row.type),
                text = text(row, meta),
                media = media(row),
                quoted = quoted,
                location = meta?.location,
                vcards = meta?.vcards,
                poll = meta?.poll,
                call = meta?.call,
                mentions = meta?.mentions?.map { Mention(it, contactName(it)) } ?: emptyList(),
                forwarded = row.isForwarded,
                isStatus = row.isStatus,
                ack = row.ack,
                reactions = byMsg[row.id] ?: emptyList(),
                edited = row.editedAt != null || edits > 0,
                editedAt = row.editedAt,
                editCount = edits,
                deletedAt = row.deletedAt,
                deletedBy = row.deletedBy,
                deletedForMeAt = row.deletedForMeAt,
                source = if (row.source == "history") "history" else "live",
                capturedAt = row.capturedAt,
            )
        }
    }

    fun preview(row: MessageRow): MessagePreview {
        val type = messageTypeOf(row.type)
        var t = text(row, meta(row))
        val label = MEDIA_LABEL[type]
        t = when {
            t == null -> label
            label != null && type != MessageType.System -> "$label · $t"
            else -> t
        }
        if (t != null && t.length > PREVIEW_LEN) t = t.take(PREVIEW_LEN - 1) + "…"
        return MessagePreview(
            id = row.id,
            type = type,
            fromMe = row.fromMe,
            senderName = if (row.fromMe) "You" else row.senderId?.let { contactName(it) },
            text = t,
            deleted = row.deletedAt != null,
            ts = row.ts,
        )
    }

    fun chats(rows: List<ChatListRow>): List<ChatSummary> {
        val last = repo.getMessagesByIds(rows.mapNotNull { it.lastMessageId }).associateBy { it.id }
        preloadContacts(rows.filter { it.kind == "user" }.map { it.id } + last.values.mapNotNull { it.senderId })
        return rows.map { c ->
            val lm = c.lastMessageId?.let { last[it] }
            ChatSummary(
                id = c.id,
                kind = chatKindOf(c.kind),
                name = chatName(c),
                avatarUrl = c.avatarMediaId?.let { mediaUrl(it) },
                lastTs = lm?.ts ?: c.lastTs,
                lastMessage = lm?.let { preview(it) },
                archived = c.archived,
                pinned = c.pinned,
                muted = c.muted,
                removed = c.removedAt != null,
                messageCount = c.messageCount,
                deletedCount = c.deletedCount,
            )
        }
    }

    /** Plain text used for search (body, captions, poll/location/contact names). */
    fun searchableText(row: MessageRow): String {
        val parts = mutableListOf<String>()
        reader.tryText(F.body(row.id), row.bodyEnc)?.let(parts::add)
        val m = if (row.metaEnc != null) meta(row) else null
        m?.poll?.let { p -> parts += p.question; parts += p.options.map { it.name } }
        m?.location?.let { l -> parts += l.name ?: ""; parts += l.address ?: "" }
        m?.vcards?.let { v -> parts += v.map { it.displayName ?: "" } }
        return parts.joinToString("\n")
    }

    companion object {
        const val STATE_WA_ME = "wa_me"
        const val STATE_WA_ME_LID = "wa_me_lid"
    }
}
