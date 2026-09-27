package io.github.revocx35.walmobile.store

import io.github.revocx35.walmobile.core.ApiException
import io.github.revocx35.walmobile.core.crypto.Crypto
import io.github.revocx35.walmobile.core.crypto.DataReader
import io.github.revocx35.walmobile.core.crypto.DataWriter
import io.github.revocx35.walmobile.core.crypto.F
import io.github.revocx35.walmobile.core.crypto.MediaCrypto
import java.io.File

/**
 * Encrypted media files under `no_backup/media/<xx>/<random>.bin` (random names, no plaintext hashes),
 * in the chunked format of core/crypto/MediaCrypto, recorded in the `media` table.
 */
class MediaFiles(private val root: File, private val repo: Repo, private val databaseFile: () -> File?) {
    class Pending(val writer: MediaCrypto.Writer, val relPath: String, val keyId: Long)

    init {
        root.mkdirs()
    }

    fun file(relPath: String): File {
        require(Regex("^[0-9a-f]{2}/[0-9a-f]{32}\\.bin$").matches(relPath)) { "bad media path" }
        return File(root, relPath)
    }

    fun begin(writer: DataWriter): Pending {
        val k = writer.currentKey()
        val fileId = Crypto.random(16)
        val hex = fileId.joinToString("") { "%02x".format(it) }
        val rel = "${hex.take(2)}/$hex.bin"
        val f = file(rel)
        f.parentFile?.mkdirs()
        return Pending(MediaCrypto.Writer(f, k.key, k.id, fileId), rel, k.id)
    }

    /** Seals the file and records it; returns (mediaId, size). */
    fun finish(p: Pending, writer: DataWriter, kind: String, mime: String?, filename: String?): Pair<Long, Long> {
        val size = p.writer.finish()
        val id = repo.insertMedia(
            path = p.relPath,
            keyId = p.keyId,
            mimeEnc = writer.encrypt(F.mediaMime(p.relPath), mime),
            filenameEnc = writer.encrypt(F.mediaFilename(p.relPath), filename),
            size = size,
            kind = kind,
            createdAt = System.currentTimeMillis(),
        )
        return id to size
    }

    fun delete(relPath: String) {
        runCatching { file(relPath).delete() }
    }

    /** Opens a stored file for decryption (needs the unlocked vault). */
    fun open(mediaId: Long, reader: DataReader): Opened {
        val row = repo.getMedia(mediaId) ?: throw ApiException.notFound("Media")
        val r = MediaCrypto.Reader(file(row.path)) { reader.key(it) }
        return Opened(r, reader.tryText(F.mediaMime(row.path), row.mimeEnc), reader.tryText(F.mediaFilename(row.path), row.filenameEnc))
    }

    class Opened(val reader: MediaCrypto.Reader, val mime: String?, val filename: String?)

    fun freeBytes(): Long = runCatching { root.usableSpace }.getOrDefault(Long.MAX_VALUE)

    fun databaseBytes(): Long = databaseFile()?.let { db ->
        listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf { if (it.exists()) it.length() else 0L }
    } ?: 0L
}
