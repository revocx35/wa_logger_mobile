package io.github.revocx35.walmobile.store

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.revocx35.walmobile.core.crypto.DataKeyStore
import io.github.revocx35.walmobile.core.crypto.WrappedDek

/**
 * Typed queries over [Db] (port of the server's `db/repo.ts`). Thread-safe: SQLite serializes writers,
 * and WAL lets the UI read while the logger writes. Never call it on the main thread.
 */
class Repo(private val helper: Db) : DataKeyStore {
    val db: SQLiteDatabase get() = helper.writableDatabase

    fun <T> transaction(block: () -> T): T {
        val d = db
        d.beginTransactionNonExclusive()
        try {
            val r = block()
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    private inline fun <T> query(sql: String, vararg args: Any?, read: (Cursor) -> T): T =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use(read)

    /** rawQuery can only bind strings; blobs and exact integers go through a compiled statement. */
    private fun exec(sql: String, vararg args: Any?): Int = db.compileStatement(sql).use { st ->
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> st.bindNull(idx)
                is ByteArray -> st.bindBlob(idx, a)
                is Long -> st.bindLong(idx, a)
                is Int -> st.bindLong(idx, a.toLong())
                is Boolean -> st.bindLong(idx, if (a) 1 else 0)
                is Double -> st.bindDouble(idx, a)
                else -> st.bindString(idx, a.toString())
            }
        }
        st.executeUpdateDelete()
    }

    /** Placeholders for an IN (...) list. */
    private fun marks(n: Int) = List(n) { "?" }.joinToString(",")

    /* ----------------------------------------------------------- state */

    fun getState(key: String): String? = query("SELECT value FROM app_state WHERE key = ?", key) { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setState(key: String, value: String) {
        exec("INSERT INTO app_state (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value)
    }

    fun deleteState(key: String) {
        exec("DELETE FROM app_state WHERE key = ?", key)
    }

    /* ----------------------------------------------------------- owner */

    fun hasOwner(): Boolean = query("SELECT 1 FROM owner WHERE id = 1") { it.moveToFirst() }

    fun getOwner(): OwnerRow? = query("SELECT * FROM owner WHERE id = 1") { c ->
        if (!c.moveToFirst()) return@query null
        fun i(n: String) = c.getColumnIndexOrThrow(n)
        OwnerRow(
            pwSalt = c.getBlob(i("pw_salt")),
            pwAuthHash = c.getBlob(i("pw_auth_hash")),
            pwLogN = c.getInt(i("pw_log_n")),
            publicKey = c.getBlob(i("public_key")),
            privkeyPw = c.getBlob(i("privkey_pw")),
            privkeyRecovery = c.getBlob(i("privkey_recovery")),
            recoverySalt = c.getBlob(i("recovery_salt")),
            recoveryAuthHash = c.getBlob(i("recovery_auth_hash")),
            privkeyBio = c.blob(i("privkey_bio")),
            failedUnlocks = c.getInt(i("failed_unlocks")),
            lockedUntil = c.long(i("locked_until")),
            createdAt = c.getLong(i("created_at")),
            passwordChangedAt = c.getLong(i("password_changed_at")),
        )
    }

    /** False when an owner already exists. */
    fun createOwner(o: OwnerRow): Boolean = exec(
        """INSERT OR IGNORE INTO owner (id, pw_salt, pw_auth_hash, pw_log_n, public_key, privkey_pw, privkey_recovery,
             recovery_salt, recovery_auth_hash, created_at, password_changed_at) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        o.pwSalt, o.pwAuthHash, o.pwLogN, o.publicKey, o.privkeyPw, o.privkeyRecovery, o.recoverySalt, o.recoveryAuthHash, o.createdAt, o.passwordChangedAt,
    ) == 1

    private val ownerUpdatable = setOf(
        "pw_salt", "pw_auth_hash", "pw_log_n", "privkey_pw", "privkey_recovery", "recovery_salt", "recovery_auth_hash",
        "privkey_bio", "failed_unlocks", "locked_until", "password_changed_at",
    )

    fun updateOwner(values: ContentValues) {
        for (k in values.keySet()) require(k in ownerUpdatable) { "owner column not updatable: $k" }
        if (values.size() > 0) db.update("owner", values, "id = 1", null)
    }

    /** Flushes and truncates the WAL, so superseded pages (old key wraps) don't linger after a change. */
    fun scrub() {
        runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
    }

    /* ----------------------------------------------------------- audit */

    fun audit(event: String, detail: String? = null, ts: Long = System.currentTimeMillis()) {
        exec("INSERT INTO audit_log (ts, event, detail) VALUES (?, ?, ?)", ts, event, detail?.take(200))
        exec("DELETE FROM audit_log WHERE id <= (SELECT MAX(id) - 5000 FROM audit_log)")
    }

    fun listAudit(limit: Int, beforeId: Long? = null): List<AuditRow> {
        val sql = if (beforeId != null) "SELECT * FROM audit_log WHERE id < ? ORDER BY id DESC LIMIT ?" else "SELECT * FROM audit_log ORDER BY id DESC LIMIT ?"
        val args: Array<Any?> = if (beforeId != null) arrayOf(beforeId, limit) else arrayOf(limit)
        return query(sql, *args) { c ->
            buildList { while (c.moveToNext()) add(AuditRow(c.getLong(0), c.getLong(1), c.getString(2), c.str(3))) }
        }
    }

    /* ------------------------------------------------------- data keys */

    override fun insertDataKey(ephPub: ByteArray, wrapped: ByteArray, createdAt: Long): Long =
        db.insertOrThrow("data_keys", null, ContentValues().apply {
            put("eph_pub", ephPub)
            put("wrapped", wrapped)
            put("created_at", createdAt)
        })

    override fun getDataKey(id: Long): WrappedDek? = query("SELECT eph_pub, wrapped FROM data_keys WHERE id = ?", id) { c ->
        if (c.moveToFirst()) WrappedDek(c.getBlob(0), c.getBlob(1)) else null
    }

    /* ----------------------------------------------------------- media */

    fun insertMedia(path: String, keyId: Long, mimeEnc: ByteArray?, filenameEnc: ByteArray?, size: Long, kind: String, createdAt: Long): Long =
        db.insertOrThrow("media", null, ContentValues().apply {
            put("path", path)
            put("key_id", keyId)
            put("mime_enc", mimeEnc)
            put("filename_enc", filenameEnc)
            put("size", size)
            put("kind", kind)
            put("created_at", createdAt)
        })

    fun getMedia(id: Long): MediaRow? = query("SELECT * FROM media WHERE id = ?", id) { c -> if (c.moveToFirst()) c.mediaRow() else null }

    fun deleteMedia(id: Long) {
        exec("DELETE FROM media WHERE id = ?", id)
    }

    fun allMediaPaths(): List<String> = query("SELECT path FROM media") { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** (count, bytes) of stored media files. */
    fun mediaTotals(): Pair<Long, Long> = query("SELECT COUNT(*), COALESCE(SUM(size), 0) FROM media") { c -> c.moveToFirst(); c.getLong(0) to c.getLong(1) }

    /* ----------------------------------------------------------- chats */

    fun getChat(id: String): ChatRow? = query("SELECT * FROM chats WHERE id = ?", id) { c -> if (c.moveToFirst()) c.chatRow() else null }

    /** Insert or update; null flags mean "leave unchanged", a null name keeps the stored one. */
    fun upsertChat(id: String, kind: String, nameEnc: ByteArray?, lastTs: Long?, archived: Boolean?, pinned: Boolean?, muted: Boolean?, now: Long) {
        exec(
            """INSERT INTO chats (id, kind, name_enc, last_ts, archived, pinned, muted, created_at, updated_at)
               VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?8)
               ON CONFLICT (id) DO UPDATE SET
                 kind = excluded.kind,
                 name_enc = COALESCE(excluded.name_enc, chats.name_enc),
                 last_ts = MAX(COALESCE(chats.last_ts, 0), COALESCE(excluded.last_ts, 0)),
                 archived = CASE WHEN ?9 THEN excluded.archived ELSE chats.archived END,
                 pinned = CASE WHEN ?10 THEN excluded.pinned ELSE chats.pinned END,
                 muted = CASE WHEN ?11 THEN excluded.muted ELSE chats.muted END,
                 removed_at = NULL,
                 updated_at = excluded.updated_at""",
            id, kind, nameEnc, lastTs, archived == true, pinned == true, muted == true, now, archived != null, pinned != null, muted != null,
        )
    }

    fun bumpChatTs(id: String, ts: Long, now: Long) {
        exec("UPDATE chats SET last_ts = MAX(COALESCE(last_ts, 0), ?), updated_at = ? WHERE id = ?", ts, now, id)
    }

    fun markChatRemoved(id: String, at: Long) {
        exec("UPDATE chats SET removed_at = ?, updated_at = ? WHERE id = ?", at, at, id)
    }

    fun setChatArchived(id: String, archived: Boolean, now: Long) {
        exec("UPDATE chats SET archived = ?, updated_at = ? WHERE id = ?", archived, now, id)
    }

    fun setChatAvatar(id: String, mediaId: Long?, checkedAt: Long) {
        exec("UPDATE chats SET avatar_media_id = ?, avatar_checked_at = ? WHERE id = ?", mediaId, checkedAt, id)
    }

    fun chatsNeedingAvatar(olderThan: Long, limit: Int): List<ChatRow> =
        query("SELECT * FROM chats WHERE avatar_checked_at IS NULL OR avatar_checked_at < ? ORDER BY last_ts DESC LIMIT ?", olderThan, limit) { c ->
            buildList { while (c.moveToNext()) add(c.chatRow()) }
        }

    fun listChats(): List<ChatListRow> = query(
        """SELECT c.*,
             (SELECT COUNT(*) FROM messages m WHERE m.chat_id = c.id) AS message_count,
             (SELECT COUNT(*) FROM messages m WHERE m.chat_id = c.id AND m.deleted_at IS NOT NULL) AS deleted_count,
             (SELECT m.id FROM messages m WHERE m.chat_id = c.id ORDER BY m.ts DESC, m.rowid DESC LIMIT 1) AS lm_id
           FROM chats c
           ORDER BY c.pinned DESC, COALESCE(c.last_ts, 0) DESC""",
    ) { c ->
        val mc = c.getColumnIndexOrThrow("message_count")
        val dc = c.getColumnIndexOrThrow("deleted_count")
        val lm = c.getColumnIndexOrThrow("lm_id")
        buildList { while (c.moveToNext()) add(ChatListRow(c.chatRow(), c.getInt(mc), c.getInt(dc), c.str(lm))) }
    }

    fun getChatsByIds(ids: Collection<String>): List<ChatRow> = ids.distinct().chunked(500).flatMap { part ->
        query("SELECT * FROM chats WHERE id IN (${marks(part.size)})", *part.toTypedArray()) { c -> buildList { while (c.moveToNext()) add(c.chatRow()) } }
    }

    /* -------------------------------------------------------- contacts */

    fun getContactsByIds(ids: Collection<String>): List<ContactRow> = ids.distinct().chunked(500).flatMap { part ->
        query("SELECT * FROM contacts WHERE id IN (${marks(part.size)})", *part.toTypedArray()) { c -> buildList { while (c.moveToNext()) add(c.contactRow()) } }
    }

    fun upsertContact(id: String, nameEnc: ByteArray?, pushnameEnc: ByteArray?, phoneEnc: ByteArray?, isMe: Boolean, isBusiness: Boolean, now: Long) {
        exec(
            """INSERT INTO contacts (id, name_enc, pushname_enc, phone_enc, is_me, is_business, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (id) DO UPDATE SET
                 name_enc = COALESCE(excluded.name_enc, contacts.name_enc),
                 pushname_enc = COALESCE(excluded.pushname_enc, contacts.pushname_enc),
                 phone_enc = COALESCE(excluded.phone_enc, contacts.phone_enc),
                 is_me = MAX(contacts.is_me, excluded.is_me),
                 is_business = excluded.is_business,
                 updated_at = excluded.updated_at""",
            id, nameEnc, pushnameEnc, phoneEnc, isMe, isBusiness, now,
        )
    }

    /* -------------------------------------------------------- messages */

    private fun messages(sql: String, vararg args: Any?): List<MessageRow> = query(sql, *args) { c ->
        val cols = MessageCols(c)
        buildList { while (c.moveToNext()) add(cols.read(c)) }
    }

    fun getMessage(id: String): MessageRow? = messages("SELECT rowid, * FROM messages WHERE id = ?", id).firstOrNull()

    fun getMessagesByIds(ids: Collection<String>): List<MessageRow> = ids.distinct().chunked(500).flatMap { part ->
        messages("SELECT rowid, * FROM messages WHERE id IN (${marks(part.size)})", *part.toTypedArray())
    }

    private val stanzaRe = Regex("^[A-Za-z0-9]{1,128}$")

    /** Resolves a quoted message (WhatsApp only gives the stanza id) to our message id within a chat. */
    fun findByStanza(chatId: String, stanzaId: String): String? {
        if (!stanzaRe.matches(stanzaId)) return null
        return query(
            "SELECT id FROM messages WHERE chat_id = ?1 AND (instr(id, '_' || ?2 || '_') > 0 OR substr(id, -(length(?2) + 1)) = '_' || ?2) LIMIT 1",
            chatId, stanzaId,
        ) { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    /** Inserts if absent. True when a row was inserted. */
    fun insertMessage(m: MessageRow): Boolean = exec(
        """INSERT OR IGNORE INTO messages (id, chat_id, sender_id, from_me, ts, type, body_enc, meta_enc, thumb_enc, quoted_id,
             media_id, media_status, media_attempts, media_size, media_w, media_h, media_duration, is_view_once, is_forwarded,
             is_status, ack, deleted_at, deleted_by, deleted_for_me_at, edited_at, last_edit_key, source, captured_at, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        m.id, m.chatId, m.senderId, m.fromMe, m.ts, m.type, m.bodyEnc, m.metaEnc, m.thumbEnc, m.quotedId,
        m.mediaId, m.mediaStatus, m.mediaAttempts, m.mediaSize, m.mediaW, m.mediaH, m.mediaDuration, m.isViewOnce, m.isForwarded,
        m.isStatus, m.ack, m.deletedAt, m.deletedBy, m.deletedForMeAt, m.editedAt, m.lastEditKey, m.source, m.capturedAt, m.updatedAt,
    ) == 1

    fun updateMessage(id: String, values: ContentValues) {
        for (k in values.keySet()) require(k in MESSAGE_UPDATABLE) { "message column not updatable: $k" }
        if (values.size() > 0) db.update("messages", values, "id = ?", arrayOf(id))
    }

    /** Newest-first page older than [before], returned in ascending order. */
    fun listMessagesBefore(chatId: String, before: PageCursor?, limit: Int): List<MessageRow> = (
        if (before != null) {
            messages(
                "SELECT rowid, * FROM messages WHERE chat_id = ? AND (ts < ? OR (ts = ? AND rowid < ?)) ORDER BY ts DESC, rowid DESC LIMIT ?",
                chatId, before.ts, before.ts, before.rowid, limit,
            )
        } else {
            messages("SELECT rowid, * FROM messages WHERE chat_id = ? ORDER BY ts DESC, rowid DESC LIMIT ?", chatId, limit)
        }
        ).reversed()

    /** Oldest-first page newer than [after] (exclusive). */
    fun listMessagesAfter(chatId: String, after: PageCursor, limit: Int): List<MessageRow> = messages(
        "SELECT rowid, * FROM messages WHERE chat_id = ? AND (ts > ? OR (ts = ? AND rowid > ?)) ORDER BY ts ASC, rowid ASC LIMIT ?",
        chatId, after.ts, after.ts, after.rowid, limit,
    )

    fun listDeleted(before: PageCursor?, limit: Int): List<MessageRow> = if (before != null) {
        messages(
            "SELECT rowid, * FROM messages WHERE deleted_at IS NOT NULL AND (deleted_at < ? OR (deleted_at = ? AND rowid < ?)) ORDER BY deleted_at DESC, rowid DESC LIMIT ?",
            before.ts, before.ts, before.rowid, limit,
        )
    } else {
        messages("SELECT rowid, * FROM messages WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC, rowid DESC LIMIT ?", limit)
    }

    /** Batch of messages with a body/meta to scan for search, newest first. */
    fun scanForSearch(chatId: String?, before: PageCursor?, limit: Int): List<MessageRow> {
        val where = mutableListOf("(body_enc IS NOT NULL OR meta_enc IS NOT NULL)")
        val args = mutableListOf<Any?>()
        if (chatId != null) {
            where += "chat_id = ?"
            args += chatId
        }
        if (before != null) {
            where += "(ts < ? OR (ts = ? AND rowid < ?))"
            args.addAll(listOf(before.ts, before.ts, before.rowid))
        }
        args += limit
        return messages("SELECT rowid, * FROM messages WHERE ${where.joinToString(" AND ")} ORDER BY ts DESC, rowid DESC LIMIT ?", *args.toTypedArray())
    }

    fun pendingMediaMessages(limit: Int): List<MessageRow> =
        messages("SELECT rowid, * FROM messages WHERE media_status = 'pending' ORDER BY ts DESC LIMIT ?", limit)

    fun countMessages(): Long = query("SELECT COUNT(*) FROM messages") { c -> c.moveToFirst(); c.getLong(0) }

    /* ----------------------------------------------------------- edits */

    fun insertEdit(messageId: String, bodyEnc: ByteArray?, capturedAt: Long) {
        exec("INSERT INTO message_edits (message_id, body_enc, captured_at) VALUES (?, ?, ?)", messageId, bodyEnc, capturedAt)
    }

    fun listEdits(messageId: String): List<EditRow> = query("SELECT * FROM message_edits WHERE message_id = ? ORDER BY id ASC", messageId) { c ->
        buildList { while (c.moveToNext()) add(EditRow(c.getLong(0), c.getString(1), c.blob(2), c.getLong(3))) }
    }

    fun editCounts(ids: Collection<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (part in ids.distinct().chunked(500)) {
            query("SELECT message_id, COUNT(*) FROM message_edits WHERE message_id IN (${marks(part.size)}) GROUP BY message_id", *part.toTypedArray()) { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            }
        }
        return out
    }

    /* ------------------------------------------------------- reactions */

    fun upsertReaction(messageId: String, senderId: String, emojiEnc: ByteArray?, ts: Long, removed: Boolean) {
        exec(
            """INSERT INTO reactions (message_id, sender_id, emoji_enc, ts, removed_at) VALUES (?1, ?2, ?3, ?4, ?5)
               ON CONFLICT (message_id, sender_id) DO UPDATE SET
                 emoji_enc = COALESCE(excluded.emoji_enc, reactions.emoji_enc),
                 ts = excluded.ts,
                 removed_at = excluded.removed_at
               WHERE excluded.ts >= reactions.ts""",
            messageId, senderId, emojiEnc, ts, if (removed) ts else null,
        )
    }

    fun reactionsFor(ids: Collection<String>): List<ReactionRow> = ids.distinct().chunked(500).flatMap { part ->
        query("SELECT * FROM reactions WHERE message_id IN (${marks(part.size)}) ORDER BY ts ASC", *part.toTypedArray()) { c ->
            buildList { while (c.moveToNext()) add(ReactionRow(c.getString(0), c.getString(1), c.blob(2), c.getLong(3), c.long(4))) }
        }
    }

    /* ------------------------------------------------------------ wipe */

    /** Deletes all logged WhatsApp data (keeps owner, audit log, settings). Returns media paths to unlink. */
    fun wipeLoggedData(): List<String> = transaction {
        val paths = allMediaPaths()
        for (t in listOf("reactions", "message_edits", "messages", "contacts", "chats", "media")) db.execSQL("DELETE FROM $t")
        paths
    }

    companion object {
        val MESSAGE_UPDATABLE = setOf(
            "sender_id", "from_me", "ts", "type", "body_enc", "meta_enc", "thumb_enc", "quoted_id", "media_id", "media_status",
            "media_attempts", "media_size", "media_w", "media_h", "media_duration", "is_view_once", "is_forwarded", "is_status",
            "ack", "deleted_at", "deleted_by", "deleted_for_me_at", "edited_at", "last_edit_key", "updated_at",
        )
    }
}

/** ContentValues from pairs (nulls become SQL NULL). */
fun values(vararg pairs: Pair<String, Any?>): ContentValues = ContentValues().apply {
    for ((k, v) in pairs) {
        when (v) {
            null -> putNull(k)
            is ByteArray -> put(k, v)
            is Long -> put(k, v)
            is Int -> put(k, v)
            is Boolean -> put(k, if (v) 1 else 0)
            is Double -> put(k, v)
            is String -> put(k, v)
            else -> error("unsupported value type for $k")
        }
    }
}
