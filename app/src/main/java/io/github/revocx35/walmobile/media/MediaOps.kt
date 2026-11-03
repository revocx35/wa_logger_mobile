package io.github.revocx35.walmobile.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.revocx35.walmobile.core.Message
import io.github.revocx35.walmobile.core.MessageType
import io.github.revocx35.walmobile.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Saving and opening media. "Save" decrypts straight into the file the owner picked; "Open" writes a
 * temporary decrypted copy into the app's private cache (shared read-only with the chosen viewer app
 * through a content:// grant) and those copies are deleted the next time the app starts.
 */
object MediaOps {
    private const val OPEN_DIR = "open"

    fun defaultName(msg: Message): String = defaultName(msg.type, msg.ts, msg.media?.mime, msg.media?.filename)

    fun defaultName(type: MessageType, ts: Long, mime: String?, filename: String?): String {
        filename?.let { safeName(it) }?.takeIf { it.isNotBlank() }?.let { return it }
        val ext = when {
            mime?.startsWith("image/jpeg") == true -> "jpg"
            mime?.startsWith("image/png") == true -> "png"
            mime?.startsWith("image/webp") == true -> "webp"
            mime?.startsWith("video/mp4") == true -> "mp4"
            mime?.startsWith("audio/ogg") == true -> "ogg"
            mime?.startsWith("audio/mpeg") == true -> "mp3"
            mime?.startsWith("audio/mp4") == true -> "m4a"
            else -> mime?.substringAfter('/')?.substringBefore(';')?.takeIf { it.length in 1..5 } ?: "bin"
        }
        val kind = when (type) {
            MessageType.Image -> "photo"
            MessageType.Video, MessageType.Gif -> "video"
            MessageType.Ptt -> "voice"
            MessageType.Audio -> "audio"
            MessageType.Sticker -> "sticker"
            else -> "document"
        }
        return "wa_$kind-$ts.$ext"
    }

    private fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f\\u007f\"*:<>?|]"), "_").take(150)

    private suspend fun download(session: Session, msg: Message, out: OutputStream) {
        val url = session.mediaUrl(msg.media?.url) ?: throw IOException("no media")
        withContext(Dispatchers.IO) { session.media.copyTo(url, out) }
    }

    suspend fun saveTo(context: Context, session: Session, msg: Message, uri: Uri) {
        try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")?.use { out -> download(session, msg, out) } ?: throw IOException("cannot write")
            }
            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            Toast.makeText(context, "Could not save the file.", Toast.LENGTH_LONG).show()
        }
    }

    suspend fun open(context: Context, session: Session, msg: Message) {
        val file = try {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "$OPEN_DIR/${UUID.randomUUID()}").apply { mkdirs() }
                File(dir, defaultName(msg)).also { f -> f.outputStream().use { download(session, msg, it) } }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not download the file.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = msg.media?.mime?.substringBefore(';')?.takeIf { it.contains('/') } ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this phone can open this file. Use Save instead.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * A new entry in the shared gallery (MediaStore, no storage permission, Android 10+): photos in Pictures/wa_logger,
     * videos and GIFs in Movies/wa_logger. Write to [out], then [commit]; [abort] removes the half-written entry.
     */
    class GalleryFile(private val context: Context, type: MessageType, ts: Long, mime: String?, filename: String?) {
        private val resolver = context.contentResolver
        private val uri: Uri
        val out: OutputStream

        init {
            if (Build.VERSION.SDK_INT < 29) throw IOException("needs Android 10")
            val video = type != MessageType.Image
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val m = mime?.substringBefore(';')?.trim()?.takeIf { it.startsWith(if (video) "video/" else "image/") } ?: if (video) "video/mp4" else "image/jpeg"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, defaultName(type, ts, m, filename))
                put(MediaStore.MediaColumns.MIME_TYPE, m)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/wa_logger")
                put(MediaStore.MediaColumns.DATE_TAKEN, ts)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(collection, values) ?: throw IOException("cannot create file")
            out = try {
                resolver.openOutputStream(uri, "w") ?: throw IOException("cannot write")
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }

        fun commit() {
            out.close()
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }

        fun abort() {
            runCatching { out.close() }
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    /** Saves one downloaded photo/video/GIF (decrypted) into the shared gallery. */
    suspend fun saveToGallery(context: Context, session: Session, msg: Message) {
        withContext(Dispatchers.IO) {
            val g = GalleryFile(context, msg.type, msg.ts, msg.media?.mime, null)
            try {
                download(session, msg, g.out)
                g.commit()
            } catch (e: Throwable) {
                g.abort()
                throw e
            }
        }
    }

    /** "Save selected": one after another, then reports the result. Never throws. */
    suspend fun saveAllToGallery(context: Context, session: Session, messages: List<Message>) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(context, "Saving several files at once needs Android 10 or newer.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(context, "Saving ${messages.size} to the gallery…", Toast.LENGTH_SHORT).show()
        var failed = 0
        for (m in messages) {
            try {
                saveToGallery(context, session, m)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed++
            }
        }
        val ok = messages.size - failed
        val text = if (failed == 0) "Saved $ok to the gallery (wa_logger album)." else "Saved $ok of ${messages.size}; $failed could not be saved."
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    /** Deletes temporary copies from earlier "Open" actions. */
    fun cleanup(context: Context) {
        File(context.cacheDir, OPEN_DIR).deleteRecursively()
    }
}
