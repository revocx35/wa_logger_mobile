package io.github.revocx35.walmobile.store

import io.github.revocx35.walmobile.core.ApiException
import io.github.revocx35.walmobile.core.AuditEntry
import io.github.revocx35.walmobile.core.ChatSummary
import io.github.revocx35.walmobile.core.DeletedFeedItem
import io.github.revocx35.walmobile.core.DeletedFeedPage
import io.github.revocx35.walmobile.core.Message
import io.github.revocx35.walmobile.core.MessageEdit
import io.github.revocx35.walmobile.core.MessagePage
import io.github.revocx35.walmobile.core.SearchHit
import io.github.revocx35.walmobile.core.SearchResponse
import io.github.revocx35.walmobile.core.ServerEvent
import io.github.revocx35.walmobile.core.Settings
import io.github.revocx35.walmobile.core.SettingsPatch
import io.github.revocx35.walmobile.core.WaJson
import io.github.revocx35.walmobile.core.WaStatus
import io.github.revocx35.walmobile.core.crypto.F
import io.github.revocx35.walmobile.security.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Live change notifications (ids and states only, never content), like the server's SSE stream. */
class EventBus {
    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val _reopened = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Fires when screens should reload everything (e.g. after a data wipe). */
    val reopened: SharedFlow<Unit> = _reopened.asSharedFlow()

    fun emit(e: ServerEvent) {
        _events.tryEmit(e)
    }

    fun reload() {
        _reopened.tryEmit(Unit)
    }
}

/** What the UI and the store need from the WhatsApp engine. */
interface WaControl {
    val status: StateFlow<WaStatus>
    suspend fun restart()
    suspend fun logout()
    fun retryMedia(messageId: String): Boolean
    fun onDataWiped()
}

/** Logging settings (the server's `/api/settings`), stored in app_state. */
class SettingsStore(private val repo: Repo) {
    @Volatile private var cached: Settings? = null

    fun get(): Settings = cached ?: (repo.getState(KEY)?.let { runCatching { WaJson.decodeFromString(Settings.serializer(), it) }.getOrNull() } ?: Settings()).also { cached = it }

    fun update(p: SettingsPatch): Settings {
        val cur = get()
        p.mediaMaxMb?.let { if (it !in 1..2000) throw ApiException.validation("Media size must be between 1 and 2000 MB.") }
        p.historyPerChat?.let { if (it !in 0..5000) throw ApiException.validation("Messages per chat must be between 0 and 5000.") }
        val next = cur.copy(
            logStatus = p.logStatus ?: cur.logStatus,
            downloadHistoryMedia = p.downloadHistoryMedia ?: cur.downloadHistoryMedia,
            mediaMaxMb = p.mediaMaxMb ?: cur.mediaMaxMb,
            historyPerChat = p.historyPerChat ?: cur.historyPerChat,
            readReceipts = p.readReceipts ?: cur.readReceipts,
        )
        repo.setState(KEY, WaJson.encodeToString(Settings.serializer(), next))
        cached = next
        return next
    }

    fun mediaMaxBytes(): Long = get().mediaMaxMb.toLong() * 1024 * 1024

    companion object {
        const val KEY = "settings"
    }
}

private const val SEARCH_MAX_SCAN = 25_000
private const val SEARCH_MAX_HITS = 100
private const val SEARCH_MAX_MS = 4_000L
private const val SEARCH_BATCH = 500
private val MARKS = Regex("\\p{M}+")

private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(MARKS, "").lowercase()

/**
 * The data API the screens use, in place of the server's HTTP API (same routes, same DTOs, see the
 * server's ARCHITECTURE.md §8). Everything runs on the IO dispatcher; reading needs the unlocked vault.
 */
class LocalApi(
    private val repo: Repo,
    private val vault: Vault,
    private val settingsStore: SettingsStore,
    private val wa: WaControl,
    private val events: EventBus,
    private val media: MediaFiles,
) {
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun presenter() = Presenter(repo, vault.reader())

    private fun limit(n: Int) = n.coerceIn(1, 200)

    suspend fun chats(): List<ChatSummary> = io { presenter().chats(repo.listChats()) }

    suspend fun messages(chatId: String, before: String? = null, limit: Int = 60): MessagePage = io {
        val p = presenter()
        if (repo.getChat(chatId) == null) throw ApiException.notFound("Chat")
        val n = limit(limit)
        val rows = repo.listMessagesBefore(chatId, PageCursor.decode(before), n + 1)
        val more = rows.size > n
        val page = if (more) rows.drop(1) else rows
        MessagePage(p.messages(page), if (more) page.firstOrNull()?.let { PageCursor(it.ts, it.rowid).encode() } else null)
    }

    suspend fun messagesAfter(chatId: String, after: String, limit: Int = 60): MessagePage = io {
        val p = presenter()
        val cursor = PageCursor.decode(after) ?: throw ApiException.validation("Bad cursor.")
        val n = limit(limit)
        val rows = repo.listMessagesAfter(chatId, cursor, n + 1)
        val more = rows.size > n
        val page = if (more) rows.take(n) else rows
        MessagePage(p.messages(page), null, if (more) page.lastOrNull()?.let { PageCursor(it.ts, it.rowid).encode() } else null)
    }

    suspend fun messagesAround(chatId: String, messageId: String, limit: Int = 60): MessagePage = io {
        val p = presenter()
        val target = repo.getMessage(messageId)
        if (target == null || target.chatId != chatId) throw ApiException.notFound("Message")
        val half = maxOf(5, limit(limit) / 2)
        val at = PageCursor(target.ts, target.rowid)
        val older = repo.listMessagesBefore(chatId, at, half + 1)
        val newer = repo.listMessagesAfter(chatId, at, half + 1)
        val hasOlder = older.size > half
        val hasNewer = newer.size > half
        val page = (if (hasOlder) older.drop(1) else older) + target + (if (hasNewer) newer.take(half) else newer)
        MessagePage(
            p.messages(page),
            if (hasOlder) PageCursor(page.first().ts, page.first().rowid).encode() else null,
            if (hasNewer) PageCursor(page.last().ts, page.last().rowid).encode() else null,
        )
    }

    suspend fun message(messageId: String): Message = io {
        val p = presenter()
        val row = repo.getMessage(messageId) ?: throw ApiException.notFound("Message")
        p.messages(listOf(row)).first()
    }

    suspend fun edits(messageId: String): List<MessageEdit> = io {
        val r = vault.reader()
        if (repo.getMessage(messageId) == null) throw ApiException.notFound("Message")
        repo.listEdits(messageId).map { MessageEdit(r.tryText(F.body(messageId), it.bodyEnc), it.capturedAt) }
    }

    suspend fun retryMedia(messageId: String) = io {
        vault.reader()
        if (repo.getMessage(messageId) == null) throw ApiException.notFound("Message")
        if (!wa.retryMedia(messageId)) throw ApiException.conflict("This media cannot be retried.")
    }

    suspend fun deleted(before: String? = null, limit: Int = 50): DeletedFeedPage = io {
        val p = presenter()
        val n = limit(limit)
        val rows = repo.listDeleted(PageCursor.decode(before), n + 1)
        val more = rows.size > n
        val page = if (more) rows.take(n) else rows
        val chats = repo.getChatsByIds(page.map { it.chatId }).associateBy { it.id }
        val msgs = p.messages(page)
        DeletedFeedPage(
            msgs.map { DeletedFeedItem(p.chatRef(chats[it.chatId], it.chatId), it) },
            if (more) page.lastOrNull()?.let { PageCursor(it.deletedAt ?: 0, it.rowid).encode() } else null,
        )
    }

    /** Decrypt-and-scan search, bounded like the server's (hits, rows scanned, time). */
    suspend fun search(q: String, chatId: String? = null): SearchResponse = io {
        val needle = norm(q.trim())
        if (needle.length < 2 || needle.length > 100) throw ApiException.validation("Search for 2 to 100 characters.")
        val p = presenter()
        val hits = mutableListOf<MessageRow>()
        var scanned = 0
        var cursor: PageCursor? = null
        val started = System.currentTimeMillis()
        var truncated = false
        while (true) {
            val batch = repo.scanForSearch(chatId, cursor, SEARCH_BATCH)
            if (batch.isEmpty()) break
            for (row in batch) {
                scanned++
                if (norm(p.searchableText(row)).contains(needle)) {
                    hits += row
                    if (hits.size >= SEARCH_MAX_HITS) break
                }
            }
            val last = batch.last()
            cursor = PageCursor(last.ts, last.rowid)
            if (hits.size >= SEARCH_MAX_HITS || scanned >= SEARCH_MAX_SCAN || System.currentTimeMillis() - started > SEARCH_MAX_MS) {
                truncated = batch.size == SEARCH_BATCH
                break
            }
            if (batch.size < SEARCH_BATCH) break
        }
        val chats = repo.getChatsByIds(hits.map { it.chatId }).associateBy { it.id }
        SearchResponse(p.messages(hits).map { SearchHit(p.chatRef(chats[it.chatId], it.chatId), it) }, truncated)
    }

    suspend fun settings(): Settings = io { settingsStore.get() }

    suspend fun updateSettings(patch: SettingsPatch): Settings = io {
        vault.reader()
        settingsStore.update(patch).also { repo.audit("settings_changed") }
    }

    suspend fun audit(before: Long? = null): List<AuditEntry> = io {
        vault.reader()
        repo.listAudit(100, before).map { AuditEntry(it.id, it.ts, it.event, null, it.detail) }
    }

    /** Deletes all logged messages and media (keeps the keys, settings and the WhatsApp link). */
    suspend fun wipe(password: String) {
        vault.verifyPassword(password)
        io {
            val paths = repo.wipeLoggedData()
            for (p in paths) media.delete(p)
            repo.scrub()
            repo.audit("data_wiped")
        }
        wa.onDataWiped()
        events.reload()
    }

    fun waStatus(): WaStatus = wa.status.value

    suspend fun waRestart() = wa.restart()

    suspend fun waLogout() = wa.logout()

    suspend fun changePassword(current: String, next: String) = vault.changePassword(current, next)

    suspend fun rotateRecoveryKey(password: String): String = vault.rotateRecoveryKey(password)

    /** Totals for Settings → Storage. */
    suspend fun storage(): StorageInfo = io {
        val (files, bytes) = repo.mediaTotals()
        StorageInfo(repo.countMessages(), files, bytes, media.databaseBytes())
    }
}

data class StorageInfo(val messages: Long, val mediaFiles: Long, val mediaBytes: Long, val databaseBytes: Long)
