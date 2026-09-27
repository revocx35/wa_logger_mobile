package io.github.revocx35.walmobile.core.crypto

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/*
 * Chunked AEAD file format for media (random access for video seeking), same layout as the server's:
 *   header  = "WAL1" ‖ version(1) ‖ keyId(u32be) ‖ noncePrefix(8) ‖ fileId(16)          (33 bytes)
 *   chunk_i = AES-256-GCM(key, nonce = noncePrefix ‖ u32be(i),
 *                         aad = "walm/v1|media|" ‖ hex(fileId) ‖ "|" ‖ i ‖ "|" ‖ final(0/1) ‖ "|" ‖ header)
 *             of CHUNK plaintext bytes (the final chunk may be shorter, even empty); stored as ct ‖ tag.
 * The final flag detects truncation; the index in nonce + AAD detects reordering.
 */
object MediaCrypto {
    private val MAGIC = "WAL1".toByteArray()
    private const val VERSION: Byte = 1
    const val HEADER_LEN = 4 + 1 + 4 + 8 + 16
    const val CHUNK = 64 * 1024
    const val ENC_CHUNK = CHUNK + Crypto.TAG_LEN

    class Header(val keyId: Long, val noncePrefix: ByteArray, val fileId: ByteArray, val raw: ByteArray)

    fun parseHeader(h: ByteArray): Header {
        if (h.size < HEADER_LEN || !h.copyOfRange(0, 4).contentEquals(MAGIC) || h[4] != VERSION) throw CryptoException("bad media header")
        return Header(h.u32be(5), h.copyOfRange(9, 17), h.copyOfRange(17, 33), h.copyOfRange(0, HEADER_LEN))
    }

    internal fun nonce(prefix: ByteArray, index: Int): ByteArray = prefix + u32beBytes(index.toLong())

    internal fun aad(header: ByteArray, fileId: ByteArray, index: Int, final: Boolean): ByteArray =
        "walm/v1|media|${fileId.joinToString("") { "%02x".format(it) }}|$index|${if (final) 1 else 0}|".toByteArray() + header

    /** Plaintext size implied by the encrypted file size. */
    fun plaintextSize(fileSize: Long): Long {
        val body = fileSize - HEADER_LEN
        if (body < Crypto.TAG_LEN) throw CryptoException("truncated media file")
        val n = (body + ENC_CHUNK - 1) / ENC_CHUNK
        val size = body - n * Crypto.TAG_LEN
        if (size < 0) throw CryptoException("truncated media file")
        return size
    }

    /**
     * Streaming encryptor. Writes `<file>.tmp` and renames it on [finish]. A chunk is flushed only when
     * *more* than CHUNK bytes are pending, so finish() always writes a (possibly empty) final chunk.
     */
    class Writer(private val target: File, key: ByteArray, keyId: Long, fileId: ByteArray) : AutoCloseable {
        private val key = key.copyOf()
        private val tmp = File(target.path + ".tmp")
        private val noncePrefix = Crypto.random(8)
        private val header = MAGIC + byteArrayOf(VERSION) + u32beBytes(keyId) + noncePrefix + fileId
        private val fos = java.io.FileOutputStream(tmp)
        private val out = java.io.BufferedOutputStream(fos, ENC_CHUNK)
        private val pending = ByteArray(CHUNK + 1)
        private var pendingLen = 0
        private var index = 0
        private var written = 0L
        private var closed = false

        init {
            require(key.size == Crypto.KEY_LEN && fileId.size == 16)
            out.write(header)
        }

        val bytesWritten: Long get() = written + pendingLen

        private fun emit(len: Int, final: Boolean) {
            val sealed = Crypto.sealGcm(key, pending.copyOf(len), aad(header, header.copyOfRange(17, 33), index, final), nonce(noncePrefix, index))
            out.write(sealed, Crypto.NONCE_LEN, sealed.size - Crypto.NONCE_LEN)
            index++
            written += len
        }

        fun write(data: ByteArray, off: Int = 0, len: Int = data.size) {
            check(!closed) { "writer closed" }
            var o = off
            var remaining = len
            while (remaining > 0) {
                val n = minOf(remaining, pending.size - pendingLen)
                System.arraycopy(data, o, pending, pendingLen, n)
                pendingLen += n
                o += n
                remaining -= n
                if (pendingLen > CHUNK) {
                    emit(CHUNK, false)
                    pending[0] = pending[CHUNK]
                    pendingLen -= CHUNK
                }
            }
        }

        /** Seals the final chunk, syncs and moves the file into place. Returns the plaintext size. */
        fun finish(): Long {
            check(!closed) { "writer closed" }
            emit(pendingLen, true)
            pendingLen = 0
            out.flush()
            fos.fd.sync()
            closed = true
            out.close()
            key.fill(0)
            if (!tmp.renameTo(target)) throw IOException("cannot move media file into place")
            return written
        }

        /** Discards the partial file (safe to call after finish: then it does nothing). */
        fun abort() {
            if (closed) return
            closed = true
            key.fill(0)
            runCatching { out.close() }
            tmp.delete()
        }

        override fun close() = abort()
    }

    /** Random-access decryptor over one media file. Not thread-safe; open one per reader. */
    class Reader(file: File, keyFor: (Long) -> ByteArray) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        val header: Header
        val size: Long
        private val key: ByteArray
        private val lastIndex: Int
        private var cachedIndex = -1
        private var cached: ByteArray = ByteArray(0)
        private val buf = ByteArray(ENC_CHUNK)

        init {
            try {
                val h = ByteArray(HEADER_LEN)
                raf.readFully(h)
                header = parseHeader(h)
                size = plaintextSize(raf.length())
                key = keyFor(header.keyId)
                lastIndex = if (size == 0L) 0 else ((size - 1) / CHUNK).toInt()
            } catch (e: Exception) {
                raf.close()
                throw if (e is CryptoException) e else CryptoException("cannot open media file", e)
            }
        }

        private fun chunk(i: Int): ByteArray {
            if (i == cachedIndex) return cached
            val final = i == lastIndex
            val plainLen = if (final) (size - i.toLong() * CHUNK).toInt() else CHUNK
            val encLen = plainLen + Crypto.TAG_LEN
            raf.seek(HEADER_LEN + i.toLong() * ENC_CHUNK)
            try {
                raf.readFully(buf, 0, encLen)
            } catch (e: IOException) {
                throw CryptoException("truncated media file", e)
            }
            val sealed = nonce(header.noncePrefix, i) + buf.copyOf(encLen)
            cached = Crypto.openGcm(key, sealed, aad(header.raw, header.fileId, i, final))
            cachedIndex = i
            return cached
        }

        /** Reads up to [len] plaintext bytes at [position]; returns -1 at the end. */
        fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (position >= size) return -1
            if (len == 0) return 0
            val i = (position / CHUNK).toInt()
            val c = chunk(i)
            val from = (position - i.toLong() * CHUNK).toInt()
            val n = minOf(len, c.size - from)
            System.arraycopy(c, from, dst, off, n)
            return n
        }

        /** Whole file in memory (small files: avatars, stickers, images). Authenticates an empty file too. */
        fun readAll(): ByteArray {
            if (size == 0L) {
                chunk(0)
                return ByteArray(0)
            }
            require(size <= Int.MAX_VALUE)
            val out = ByteArray(size.toInt())
            var pos = 0L
            while (pos < size) pos += read(pos, out, pos.toInt(), (size - pos).toInt().coerceAtMost(CHUNK))
            return out
        }

        override fun close() {
            cached.fill(0)
            raf.close()
        }
    }
}
