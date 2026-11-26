package io.github.revocx35.walmobile.store

import io.github.revocx35.walmobile.core.RETAINED_MEDIA_TYPES
import io.github.revocx35.walmobile.core.ServerEvent
import io.github.revocx35.walmobile.core.retentionCutoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val BATCH = 500

/**
 * Storage controls (port of the wa_logger server's `retention.ts`): deletes logged messages and photo/video files
 * older than the owner's retention settings (Settings → Storage). Runs 30 s after start, every hour and whenever the
 * settings change. Needs no key (it only deletes), so it also works while the app is locked. `Ingest` and the media
 * queue apply the same cutoffs, so history sync doesn't log (or download) what this would delete again.
 */
class Retention(
    private val repo: Repo,
    private val settings: SettingsStore,
    private val events: EventBus,
    private val files: MediaFiles,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    data class Result(val messages: Int, val media: Int)

    fun start(intervalMs: Long = 3600_000L) {
        scope.launch(Dispatchers.IO) {
            delay(30_000)
            while (true) {
                runCatching { sweep() }
                delay(intervalMs)
            }
        }
    }

    fun trigger() {
        scope.launch(Dispatchers.IO) { runCatching { sweep() } }
    }

    /** One pass over both settings. Call off the main thread. */
    suspend fun sweep(): Result = lock.withLock {
        val s = settings.get()
        val t = now()
        var messages = 0
        var media = 0
        val chats = HashSet<String>()
        retentionCutoff(s.messageRetentionDays, t)?.let { cutoff ->
            while (true) {
                val ids = repo.messageIdsBefore(cutoff, BATCH)
                if (ids.isEmpty()) break
                // No tombstones: the cutoff itself keeps history sync from logging these again.
                val out = repo.removeMessages(ids, t, remember = false)
                out.messages.forEach { chats += it.second }
                messages += out.messages.size
                for (p in out.paths) files.delete(p)
                if (ids.size < BATCH) break
            }
        }
        retentionCutoff(s.mediaRetentionDays, t)?.let { cutoff ->
            while (true) {
                val rows = repo.mediaMessagesBefore(cutoff, RETAINED_MEDIA_TYPES, BATCH)
                if (rows.isEmpty()) break
                val paths = repo.expireMessageMedia(rows.map { it.first }, t)
                rows.forEach { chats += it.second }
                media += paths.size
                for (p in paths) files.delete(p)
                if (rows.size < BATCH) break
            }
        }
        if (messages > 0 || media > 0) {
            repo.audit("retention_sweep", "$messages messages, $media media files")
            for (c in chats) events.emit(ServerEvent.ChatUpdate(c))
        }
        Result(messages, media)
    }
}
