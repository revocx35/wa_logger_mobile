package io.github.revocx35.walmobile.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import coil3.ImageLoader
import coil3.decode.DataSource as CoilDataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.github.revocx35.walmobile.security.Vault
import io.github.revocx35.walmobile.store.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.IOException
import java.io.OutputStream

/**
 * Decrypted access to stored media by its `walm://media/<id>` URL, for the image loader, the players,
 * "Save" and "Open with". Works only while the app is unlocked. Nothing decrypted is written to disk
 * except files the owner explicitly saves or opens.
 */
class MediaAccess(private val files: MediaFiles, private val vault: Vault) {
    fun idOf(url: String?): Long? = url?.let { RE.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()

    /** Opens the decryptor (blocking; call off the main thread). */
    fun open(mediaId: Long): MediaFiles.Opened = files.open(mediaId, vault.reader())

    /** Streams the whole plaintext into [out] (blocking). */
    fun copyTo(url: String, out: OutputStream) {
        val id = idOf(url) ?: throw IOException("not a media url")
        open(id).reader.use { r ->
            val buf = ByteArray(64 * 1024)
            var pos = 0L
            while (true) {
                val n = r.read(pos, buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
                pos += n
            }
        }
    }

    companion object {
        private val RE = Regex("^walm://media/(\\d{1,18})$")
    }
}

/** Coil fetcher for `walm://media/<id>` (images, stickers, avatars): decrypts into memory. */
class WalmImageFetcher(private val id: Long, private val options: Options, private val access: MediaAccess) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val opened = access.open(id)
        val bytes = opened.reader.use { it.readAll() }
        SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = opened.mime,
            dataSource = CoilDataSource.DISK,
        )
    }

    class Factory(private val access: MediaAccess) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "walm") return null
            val id = access.idOf(data.toString()) ?: return null
            return WalmImageFetcher(id, options, access)
        }
    }
}

/** ExoPlayer source for `walm://media/<id>` with random access (seeking decrypts only needed chunks). */
@OptIn(UnstableApi::class)
class WalmDataSource(private val access: MediaAccess) : BaseDataSource(false) {
    private var opened: MediaFiles.Opened? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = access.idOf(dataSpec.uri.toString()) ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val o = try {
            access.open(id)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        opened = o
        val size = o.reader.size
        position = dataSpec.position
        if (position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, size - position) else size - position
        started = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = opened?.reader ?: return C.RESULT_END_OF_INPUT
        val n = try {
            r.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { opened?.reader?.close() }
        opened = null
        uri = null
        if (started) {
            started = false
            transferEnded()
        }
    }

    class Factory(private val access: MediaAccess) : DataSource.Factory {
        override fun createDataSource(): DataSource = WalmDataSource(access)
    }
}
