package io.github.revocx35.walmobile.engine

import io.github.revocx35.walmobile.core.ServerEvent
import io.github.revocx35.walmobile.security.Vault
import io.github.revocx35.walmobile.store.EventBus
import io.github.revocx35.walmobile.store.MediaFiles
import io.github.revocx35.walmobile.store.Repo
import io.github.revocx35.walmobile.store.SettingsStore
import io.github.revocx35.walmobile.store.values
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Result of preparing a download inside the page. */
sealed interface Prep {
    data class Ok(val token: String, val size: Long, val mime: String?, val filename: String?) : Prep
    data class Status(val status: String, val reason: String? = null) : Prep
}

/** The page side of a media download (see walm-bridge.js mediaPrepare/mediaSlice/mediaRelease). */
interface MediaDownloader {
    suspend fun prepare(messageId: String, maxBytes: Long): Prep
    suspend fun slice(token: String, offset: Long, length: Int): ByteArray?
    suspend fun release(token: String)
}

/**
 * Downloads media as soon as a message arrives, because after a revoke it can no longer be fetched
 * (port of the server's MediaQueue). Live before history, [concurrency] at a time, up to 4 attempts with
 * backoff; expired history media becomes "unavailable"; pauses while storage is low.
 */
class MediaQueue(
    private val repo: Repo,
    private val vault: Vault,
    private val settings: SettingsStore,
    private val events: EventBus,
    private val files: MediaFiles,
    private val downloader: MediaDownloader,
    private val scope: CoroutineScope,
    private val concurrency: Int = 2,
) : MediaSink {
    private val high = ArrayDeque<String>()
    private val low = ArrayDeque<String>()
    private val queued = HashSet<String>()
    private var running = 0
    private var paused = true
    private val timers = HashSet<Job>()
    private val lock = Any()
    @Volatile var onSizeChanged: (() -> Unit)? = null

    fun size(): Int = synchronized(lock) { queued.size + running }

    override fun enqueue(messageId: String, priority: Priority) {
        synchronized(lock) {
            if (!queued.add(messageId)) return
            (if (priority == Priority.Live) high else low).addLast(messageId)
        }
        onSizeChanged?.invoke()
        pump()
    }

    /** Re-queues everything still marked pending (after a restart). */
    fun restorePending() {
        for (r in repo.pendingMediaMessages(5000)) enqueue(r.id, Priority.History)
    }

    fun pause() {
        synchronized(lock) { paused = true }
    }

    fun resume() {
        synchronized(lock) { paused = false }
        pump()
    }

    fun stop() {
        synchronized(lock) {
            paused = true
            timers.forEach { it.cancel() }
            timers.clear()
        }
    }

    fun clear() {
        synchronized(lock) {
            high.clear()
            low.clear()
            queued.clear()
        }
        onSizeChanged?.invoke()
    }

    private fun pump() {
        while (true) {
            val id = synchronized(lock) {
                if (paused || running >= concurrency) return
                val next = high.removeFirstOrNull() ?: low.removeFirstOrNull() ?: return
                queued.remove(next)
                running++
                next
            }
            // Database, file and crypto work: never on the (main-thread) app scope's default dispatcher.
            scope.launch(Dispatchers.IO) {
                try {
                    process(id)
                } finally {
                    synchronized(lock) { running-- }
                    onSizeChanged?.invoke()
                    pump()
                }
            }
        }
    }

    private fun retryLater(id: String, attempts: Int) {
        val wait = BACKOFF_MS[minOf(attempts - 1, BACKOFF_MS.size - 1)]
        synchronized(lock) {
            var job: Job? = null
            job = scope.launch {
                delay(wait)
                synchronized(lock) { timers.remove(job) }
                enqueue(id, Priority.History)
            }
            timers += job
        }
    }

    private suspend fun process(id: String) {
        val row = repo.getMessage(id) ?: return
        if (row.mediaStatus != "pending") return
        // Keep headroom on the phone's storage: the database must always be able to write.
        if (files.freeBytes() < MIN_FREE_BYTES + (row.mediaSize ?: 0)) {
            retryLater(id, BACKOFF_MS.size)
            return
        }
        val attempts = row.mediaAttempts + 1
        fun finish(vararg patch: Pair<String, Any?>) {
            repo.updateMessage(id, values(*patch, "media_attempts" to attempts, "updated_at" to System.currentTimeMillis()))
            events.emit(ServerEvent.MessageUpdate(row.chatId, id, "media"))
        }
        try {
            when (val out = download(id)) {
                is Outcome.Ok -> {
                    if (repo.getMessage(id) == null) {
                        // Wiped while downloading.
                        repo.getMedia(out.mediaId)?.let { m ->
                            repo.deleteMedia(m.id)
                            files.delete(m.path)
                        }
                        return
                    }
                    finish("media_id" to out.mediaId, "media_status" to "downloaded", "media_size" to out.size)
                }
                is Outcome.Status -> when {
                    out.status == "too_large" -> finish("media_status" to "too_large")
                    out.status == "view_once" -> finish("media_status" to "view_once", "is_view_once" to true)
                    // Expired on WhatsApp's servers: one confirmation retry, then give up (the UI offers Retry).
                    out.status == "unavailable" && attempts >= 2 -> finish("media_status" to "unavailable")
                    attempts >= MAX_ATTEMPTS -> finish("media_status" to if (out.status == "unavailable") "unavailable" else "failed")
                    else -> {
                        repo.updateMessage(id, values("media_attempts" to attempts))
                        retryLater(id, attempts)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (attempts >= MAX_ATTEMPTS) {
                finish("media_status" to "failed")
            } else {
                repo.updateMessage(id, values("media_attempts" to attempts))
                retryLater(id, attempts)
            }
        }
    }

    private sealed interface Outcome {
        data class Ok(val mediaId: Long, val size: Long) : Outcome
        data class Status(val status: String) : Outcome
    }

    /** Prepares in the page, pulls 2 MiB slices and encrypts them straight to disk. */
    private suspend fun download(id: String): Outcome {
        val writer = vault.writer() ?: return Outcome.Status("retry")
        val prep = withTimeoutOrNull(PREP_TIMEOUT_MS) { downloader.prepare(id, settings.mediaMaxBytes()) } ?: return Outcome.Status("retry")
        val ok = when (prep) {
            is Prep.Status -> return Outcome.Status(prep.status)
            is Prep.Ok -> prep
        }
        val pending = files.begin(writer)
        try {
            var off = 0L
            while (off < ok.size) {
                val bytes = withTimeoutOrNull(SLICE_TIMEOUT_MS) { downloader.slice(ok.token, off, SLICE) } ?: throw IllegalStateException("media slice unavailable")
                if (bytes.isEmpty()) throw IllegalStateException("empty media slice")
                pending.writer.write(bytes)
                off += bytes.size
            }
            val (mediaId, size) = files.finish(pending, writer, "message", ok.mime, ok.filename)
            return Outcome.Ok(mediaId, size)
        } catch (e: Exception) {
            pending.writer.abort()
            throw e
        } finally {
            runCatching { downloader.release(ok.token) }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        const val SLICE = 2 * 1024 * 1024
        const val PREP_TIMEOUT_MS = 180_000L
        const val SLICE_TIMEOUT_MS = 60_000L
        const val MIN_FREE_BYTES = 1024L * 1024 * 1024
        val BACKOFF_MS = longArrayOf(5_000, 30_000, 120_000, 600_000)
    }
}
