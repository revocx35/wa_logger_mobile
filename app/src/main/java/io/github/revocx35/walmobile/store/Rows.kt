package io.github.revocx35.walmobile.store

import android.database.Cursor

/* Row types of the tables in [Db]. Blobs ending in `Enc` are encrypted fields (see core/crypto/Keyring). */

class OwnerRow(
    val pwSalt: ByteArray,
    val pwAuthHash: ByteArray,
    val pwLogN: Int,
    val publicKey: ByteArray,
    val privkeyPw: ByteArray,
    val privkeyRecovery: ByteArray,
    val recoverySalt: ByteArray,
    val recoveryAuthHash: ByteArray,
    val privkeyBio: ByteArray?,
    val failedUnlocks: Int,
    val lockedUntil: Long?,
    val createdAt: Long,
    val passwordChangedAt: Long,
)

class AuditRow(val id: Long, val ts: Long, val event: String, val detail: String?)

class MediaRow(
    val id: Long,
    val path: String,
    val keyId: Long,
    val mimeEnc: ByteArray?,
    val filenameEnc: ByteArray?,
    val size: Long,
    val kind: String,
    val createdAt: Long,
)

open class ChatRow(
    val id: String,
    val kind: String,
    val nameEnc: ByteArray?,
    val lastTs: Long?,
    val archived: Boolean,
    val pinned: Boolean,
    val muted: Boolean,
    val removedAt: Long?,
    val avatarMediaId: Long?,
    val avatarCheckedAt: Long?,
)

class ChatListRow(
    base: ChatRow,
    val messageCount: Int,
    val deletedCount: Int,
    val lastMessageId: String?,
) : ChatRow(base.id, base.kind, base.nameEnc, base.lastTs, base.archived, base.pinned, base.muted, base.removedAt, base.avatarMediaId, base.avatarCheckedAt)

class ContactRow(
    val id: String,
    val nameEnc: ByteArray?,
    val pushnameEnc: ByteArray?,
    val phoneEnc: ByteArray?,
    val isMe: Boolean,
    val isBusiness: Boolean,
)

data class MessageRow(
    val rowid: Long = 0,
    val id: String,
    val chatId: String,
    val senderId: String?,
    val fromMe: Boolean,
    val ts: Long,
    val type: String,
    val bodyEnc: ByteArray?,
    val metaEnc: ByteArray?,
    val thumbEnc: ByteArray?,
    val quotedId: String?,
    val mediaId: Long?,
    val mediaStatus: String,
    val mediaAttempts: Int,
    val mediaSize: Long?,
    val mediaW: Double?,
    val mediaH: Double?,
    val mediaDuration: Double?,
    val isViewOnce: Boolean,
    val isForwarded: Boolean,
    val isStatus: Boolean,
    val ack: Int?,
    val deletedAt: Long?,
    val deletedBy: String?,
    val deletedForMeAt: Long?,
    val editedAt: Long?,
    val lastEditKey: String?,
    val source: String,
    val capturedAt: Long,
    val updatedAt: Long,
)

class EditRow(val id: Long, val messageId: String, val bodyEnc: ByteArray?, val capturedAt: Long)

class ReactionRow(val messageId: String, val senderId: String, val emojiEnc: ByteArray?, val ts: Long, val removedAt: Long?)

/** Keyset cursor for paging (`<ts>_<rowid>`), like the server's. */
data class PageCursor(val ts: Long, val rowid: Long) {
    fun encode() = "${ts}_$rowid"

    companion object {
        private val RE = Regex("^(\\d{1,16})_(\\d{1,16})$")
        fun decode(s: String?): PageCursor? = s?.let { RE.matchEntire(it) }?.let { PageCursor(it.groupValues[1].toLong(), it.groupValues[2].toLong()) }
    }
}

/* ---------------------------------------------------------------- cursors */

internal fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
internal fun Cursor.long(i: Int): Long? = if (isNull(i)) null else getLong(i)
internal fun Cursor.int(i: Int): Int? = if (isNull(i)) null else getInt(i)
internal fun Cursor.dbl(i: Int): Double? = if (isNull(i)) null else getDouble(i)
internal fun Cursor.blob(i: Int): ByteArray? = if (isNull(i)) null else getBlob(i)
internal fun Cursor.bool(i: Int): Boolean = !isNull(i) && getLong(i) != 0L

internal class MessageCols(c: Cursor) {
    val rowid = c.getColumnIndexOrThrow("rowid")
    val id = c.getColumnIndexOrThrow("id")
    val chatId = c.getColumnIndexOrThrow("chat_id")
    val senderId = c.getColumnIndexOrThrow("sender_id")
    val fromMe = c.getColumnIndexOrThrow("from_me")
    val ts = c.getColumnIndexOrThrow("ts")
    val type = c.getColumnIndexOrThrow("type")
    val bodyEnc = c.getColumnIndexOrThrow("body_enc")
    val metaEnc = c.getColumnIndexOrThrow("meta_enc")
    val thumbEnc = c.getColumnIndexOrThrow("thumb_enc")
    val quotedId = c.getColumnIndexOrThrow("quoted_id")
    val mediaId = c.getColumnIndexOrThrow("media_id")
    val mediaStatus = c.getColumnIndexOrThrow("media_status")
    val mediaAttempts = c.getColumnIndexOrThrow("media_attempts")
    val mediaSize = c.getColumnIndexOrThrow("media_size")
    val mediaW = c.getColumnIndexOrThrow("media_w")
    val mediaH = c.getColumnIndexOrThrow("media_h")
    val mediaDuration = c.getColumnIndexOrThrow("media_duration")
    val isViewOnce = c.getColumnIndexOrThrow("is_view_once")
    val isForwarded = c.getColumnIndexOrThrow("is_forwarded")
    val isStatus = c.getColumnIndexOrThrow("is_status")
    val ack = c.getColumnIndexOrThrow("ack")
    val deletedAt = c.getColumnIndexOrThrow("deleted_at")
    val deletedBy = c.getColumnIndexOrThrow("deleted_by")
    val deletedForMeAt = c.getColumnIndexOrThrow("deleted_for_me_at")
    val editedAt = c.getColumnIndexOrThrow("edited_at")
    val lastEditKey = c.getColumnIndexOrThrow("last_edit_key")
    val source = c.getColumnIndexOrThrow("source")
    val capturedAt = c.getColumnIndexOrThrow("captured_at")
    val updatedAt = c.getColumnIndexOrThrow("updated_at")

    fun read(c: Cursor) = MessageRow(
        rowid = c.getLong(rowid),
        id = c.getString(id),
        chatId = c.getString(chatId),
        senderId = c.str(senderId),
        fromMe = c.bool(fromMe),
        ts = c.getLong(ts),
        type = c.getString(type),
        bodyEnc = c.blob(bodyEnc),
        metaEnc = c.blob(metaEnc),
        thumbEnc = c.blob(thumbEnc),
        quotedId = c.str(quotedId),
        mediaId = c.long(mediaId),
        mediaStatus = c.getString(mediaStatus),
        mediaAttempts = c.getInt(mediaAttempts),
        mediaSize = c.long(mediaSize),
        mediaW = c.dbl(mediaW),
        mediaH = c.dbl(mediaH),
        mediaDuration = c.dbl(mediaDuration),
        isViewOnce = c.bool(isViewOnce),
        isForwarded = c.bool(isForwarded),
        isStatus = c.bool(isStatus),
        ack = c.int(ack),
        deletedAt = c.long(deletedAt),
        deletedBy = c.str(deletedBy),
        deletedForMeAt = c.long(deletedForMeAt),
        editedAt = c.long(editedAt),
        lastEditKey = c.str(lastEditKey),
        source = c.getString(source),
        capturedAt = c.getLong(capturedAt),
        updatedAt = c.getLong(updatedAt),
    )
}

internal fun Cursor.chatRow(): ChatRow = ChatRow(
    id = getString(getColumnIndexOrThrow("id")),
    kind = getString(getColumnIndexOrThrow("kind")),
    nameEnc = blob(getColumnIndexOrThrow("name_enc")),
    lastTs = long(getColumnIndexOrThrow("last_ts")),
    archived = bool(getColumnIndexOrThrow("archived")),
    pinned = bool(getColumnIndexOrThrow("pinned")),
    muted = bool(getColumnIndexOrThrow("muted")),
    removedAt = long(getColumnIndexOrThrow("removed_at")),
    avatarMediaId = long(getColumnIndexOrThrow("avatar_media_id")),
    avatarCheckedAt = long(getColumnIndexOrThrow("avatar_checked_at")),
)

internal fun Cursor.contactRow(): ContactRow = ContactRow(
    id = getString(getColumnIndexOrThrow("id")),
    nameEnc = blob(getColumnIndexOrThrow("name_enc")),
    pushnameEnc = blob(getColumnIndexOrThrow("pushname_enc")),
    phoneEnc = blob(getColumnIndexOrThrow("phone_enc")),
    isMe = bool(getColumnIndexOrThrow("is_me")),
    isBusiness = bool(getColumnIndexOrThrow("is_business")),
)

internal fun Cursor.mediaRow(): MediaRow = MediaRow(
    id = getLong(getColumnIndexOrThrow("id")),
    path = getString(getColumnIndexOrThrow("path")),
    keyId = getLong(getColumnIndexOrThrow("key_id")),
    mimeEnc = blob(getColumnIndexOrThrow("mime_enc")),
    filenameEnc = blob(getColumnIndexOrThrow("filename_enc")),
    size = getLong(getColumnIndexOrThrow("size")),
    kind = getString(getColumnIndexOrThrow("kind")),
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
)
