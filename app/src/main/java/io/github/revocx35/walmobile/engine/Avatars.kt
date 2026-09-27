package io.github.revocx35.walmobile.engine

import io.github.revocx35.walmobile.core.ServerEvent
import io.github.revocx35.walmobile.security.Vault
import io.github.revocx35.walmobile.store.EventBus
import io.github.revocx35.walmobile.store.MediaFiles
import io.github.revocx35.walmobile.store.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Best-effort chat pictures (port of the server's `wa/avatars.ts`). The page supplies WhatsApp's signed
 * CDN URL; the phone downloads it directly (only https on *.whatsapp.net, at most 2 MiB) and stores it
 * encrypted like any other media.
 */
class Avatars(
    private val repo: Repo,
    private val vault: Vault,
    private val files: MediaFiles,
    private val events: EventBus,
    private val http: OkHttpClient,
    private val urlFor: suspend (String) -> String?,
) {
    suspend fun refresh(batch: Int = 15): Int {
        val writer = vault.writer() ?: return 0
        val t = System.currentTimeMillis()
        var updated = 0
        for (chat in withContext(Dispatchers.IO) { repo.chatsNeedingAvatar(t - REFRESH_AFTER_MS, batch) }) {
            if (chat.kind == "status" || chat.kind == "broadcast") {
                withContext(Dispatchers.IO) { repo.setChatAvatar(chat.id, null, t) }
                continue
            }
            try {
                val url = runCatching { urlFor(chat.id) }.getOrNull()
                val img = url?.let { download(it) }
                withContext(Dispatchers.IO) {
                    val old = chat.avatarMediaId?.let { repo.getMedia(it) }
                    if (img == null || img.first.isEmpty()) {
                        repo.setChatAvatar(chat.id, old?.id, t)
                        return@withContext
                    }
                    val p = files.begin(writer)
                    try {
                        p.writer.write(img.first)
                    } catch (e: Exception) {
                        p.writer.abort()
                        throw e
                    }
                    val mime = img.second?.takeIf { Regex("^image/(jpeg|png|webp)$").matches(it) } ?: "image/jpeg"
                    val (mediaId, _) = files.finish(p, writer, "avatar", mime, null)
                    repo.setChatAvatar(chat.id, mediaId, t)
                    if (old != null) {
                        repo.deleteMedia(old.id)
                        files.delete(old.path)
                    }
                    updated++
                    events.emit(ServerEvent.ChatUpdate(chat.id))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.IO) { repo.setChatAvatar(chat.id, chat.avatarMediaId, t) }
            }
        }
        return updated
    }

    private suspend fun download(url: String): Pair<ByteArray, String?>? = withContext(Dispatchers.IO) {
        val u = url.toHttpUrlOrNull() ?: return@withContext null
        if (u.scheme != "https" || !(u.host == "whatsapp.net" || u.host.endsWith(".whatsapp.net"))) return@withContext null
        http.newCall(Request.Builder().url(u).build()).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            val len = res.body.contentLength()
            if (len > MAX_BYTES) return@withContext null
            val bytes = res.body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return@withContext null
                }
                out.toByteArray()
            }
            bytes to res.header("Content-Type")?.substringBefore(';')?.trim()
        }
    }

    companion object {
        const val REFRESH_AFTER_MS = 3 * 86400_000L
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
