package io.github.revocx35.walmobile.engine

import io.github.revocx35.walmobile.core.SyncProgress
import io.github.revocx35.walmobile.store.Repo
import io.github.revocx35.walmobile.store.SettingsStore
import kotlinx.serialization.json.JsonObject

interface SyncApi {
    suspend fun listChats(): List<ChatLite>
    suspend fun fetchMessages(chatId: String, limit: Int): List<JsonObject>
}

/**
 * History import (once, after the first link) and periodic reconcile, which re-reads recent messages
 * to catch anything missed while disconnected — including deletions (messages whose type became
 * "revoked"). Port of the server's `wa/sync.ts`.
 */
class Sync(
    private val repo: Repo,
    private val settings: SettingsStore,
    private val ingest: Ingest,
    private val api: SyncApi,
    private val onProgress: (SyncProgress?) -> Unit,
    /** Runs ingest work in event order (WaEngine's ingest queue). */
    private val serial: suspend (suspend () -> Unit) -> Unit,
) {
    @Volatile var running = false
        private set

    /** First import after linking. WaService repeats [historyPass] a few times during the first hours. */
    suspend fun initial() {
        historyPass()
        if (repo.getState(INITIAL_SYNC_KEY) == null) repo.setState(INITIAL_SYNC_KEY, System.currentTimeMillis().toString())
    }

    suspend fun historyPass() {
        if (running) return
        running = true
        try {
            val chats = api.listChats().sortedByDescending { it.timestamp ?: 0 }
            val limit = settings.get().historyPerChat
            var done = 0
            onProgress(SyncProgress("history", 0, chats.size))
            for (chat in chats) {
                if (chat.id == "status@broadcast" && !settings.get().logStatus) {
                    done++
                    continue
                }
                serial { ingest.upsertChatInfo(chat) }
                if (limit > 0) {
                    try {
                        val raws = api.fetchMessages(chat.id, limit)
                        serial { for (raw in raws) ingest.message(raw, Source.History) }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                    }
                }
                done++
                onProgress(SyncProgress("history", done, chats.size))
            }
        } finally {
            running = false
            onProgress(null)
        }
    }

    suspend fun reconcile() {
        if (running) return
        running = true
        try {
            val chats = api.listChats().sortedByDescending { it.timestamp ?: 0 }
            val logStatus = settings.get().logStatus
            val targets = mutableListOf<ChatLite>()
            chats.forEachIndexed { i, chat ->
                if (chat.id == "status@broadcast" && !logStatus) return@forEachIndexed
                val stored = repo.getChat(chat.id)
                if (stored == null || stored.removedAt != null) serial { ingest.upsertChatInfo(chat) }
                val moved = stored == null || (chat.timestamp ?: 0) > (stored.lastTs ?: 0)
                if (moved || i < RECONCILE_TOP_CHATS) targets += chat
            }
            var done = 0
            onProgress(SyncProgress("reconcile", 0, targets.size))
            for (chat in targets) {
                try {
                    val raws = api.fetchMessages(chat.id, RECONCILE_LIMIT)
                    serial { for (raw in raws) ingest.message(raw, Source.Live) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
                done++
                onProgress(SyncProgress("reconcile", done, targets.size))
            }
        } finally {
            running = false
            onProgress(null)
        }
    }

    companion object {
        const val INITIAL_SYNC_KEY = "initial_sync_done"
        const val RECONCILE_TOP_CHATS = 20
        const val RECONCILE_LIMIT = 40
    }
}
