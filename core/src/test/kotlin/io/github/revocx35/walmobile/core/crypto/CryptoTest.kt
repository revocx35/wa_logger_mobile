package io.github.revocx35.walmobile.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private inline fun assertCryptoFails(block: () -> Unit) {
    try {
        block()
        fail("expected CryptoException")
    } catch (_: CryptoException) {
    }
}

class ScryptTest {
    @Test
    fun rfc7914Vectors() {
        assertEquals(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            hex(Scrypt.derive(ByteArray(0), ByteArray(0), 4, 1, 1, 64)),
        )
        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            hex(Scrypt.derive("password".toByteArray(), "NaCl".toByteArray(), 10, 8, 16, 64)),
        )
    }

    @Test
    fun matchesNode() {
        // node: crypto.scryptSync('pleaseletmein', 'SodiumChloride', 64, {N:16384, r:8, p:1})
        assertEquals(
            "7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887",
            hex(Scrypt.derive("pleaseletmein".toByteArray(), "SodiumChloride".toByteArray(), 14, 8, 1, 64)),
        )
        // The production parameters (N = 2^15).
        assertEquals(
            "37ab3da3693bf3a4a1850d10b29efbe2b3ab4398c821f92e70b8fecd92f1b981",
            hex(Scrypt.derive("hunter2 correct horse".toByteArray(), unhex("0102030405060708090a0b0c0d0e0f10"), 15, 8, 1, 32)),
        )
    }
}

class PrimitivesTest {
    @Test
    fun hkdfRfc5869Case3() {
        // RFC 5869 test case 3: empty salt and info.
        val okm = Crypto.hkdf(unhex("0b".repeat(22)), ByteArray(0), "", 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", hex(okm))
    }

    @Test
    fun gcmRoundTripAndTamper() {
        val key = Crypto.randomKey()
        val sealed = Crypto.sealGcm(key, "hello".toByteArray(), "ctx")
        assertEquals("hello", String(Crypto.openGcm(key, sealed, "ctx")))
        assertCryptoFails { Crypto.openGcm(key, sealed, "other") }
        assertCryptoFails { Crypto.openGcm(Crypto.randomKey(), sealed, "ctx") }
        val bad = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertCryptoFails { Crypto.openGcm(key, bad, "ctx") }
        assertCryptoFails { Crypto.openGcm(key, ByteArray(5), "ctx") }
    }

    @Test
    fun ecdhAgrees() {
        val a = Crypto.generateEc()
        val b = Crypto.generateEc()
        val s1 = Crypto.ecdh(Crypto.ecPrivate(a.privatePkcs8), Crypto.ecPublic(b.publicX509))
        val s2 = Crypto.ecdh(Crypto.ecPrivate(b.privatePkcs8), Crypto.ecPublic(a.publicX509))
        assertArrayEquals(s1, s2)
        assertEquals(32, s1.size)
    }
}

class KeyringTest {
    private class MemStore : DataKeyStore {
        val keys = mutableMapOf<Long, WrappedDek>()
        override fun insertDataKey(ephPub: ByteArray, wrapped: ByteArray, createdAt: Long): Long {
            val id = keys.size + 1L
            keys[id] = WrappedDek(ephPub, wrapped)
            return id
        }
        override fun getDataKey(id: Long) = keys[id]
    }

    @Test
    fun writerEncryptsReaderDecrypts() {
        val owner = Crypto.generateEc()
        val store = MemStore()
        var now = 1_000L
        val w = DataWriter(store, owner.publicX509) { now }
        val blob = w.encrypt(F.body("m1"), "secret text")!!
        val r = DataReader(store, Crypto.ecPrivate(owner.privatePkcs8), owner.publicX509)
        assertEquals("secret text", r.tryText(F.body("m1"), blob))
        // Bound to its row: moving it elsewhere fails.
        assertNull(r.tryText(F.body("m2"), blob))
        assertNull(r.tryText(F.meta("m1"), blob))
        assertNull(w.encrypt(F.body("x"), null as String?))
        // Rotation after a day creates a second key; both stay readable.
        now += DataWriter.ROTATE_MS + 1
        val blob2 = w.encrypt(F.body("m3"), "later")!!
        assertEquals(2, store.keys.size)
        assertEquals(2L, Keyring.fieldKeyId(blob2))
        assertEquals("secret text", r.tryText(F.body("m1"), blob))
        assertEquals("later", r.tryText(F.body("m3"), blob2))
        // Someone else's private key cannot read it.
        val other = Crypto.generateEc()
        assertNull(DataReader(store, Crypto.ecPrivate(other.privatePkcs8), owner.publicX509).tryText(F.body("m1"), blob))
    }

    @Test
    fun passwordAndRecoveryWrapping() {
        val owner = Crypto.generateEc()
        val salt = Crypto.random(Kdf.SALT_LEN)
        val keys = Kdf.passwordKeys("correct horse battery", salt, logN = 10)
        val sealed = Keyring.wrapPrivateKey(keys.kek, owner.privatePkcs8, Keyring.PRIVKEY_PASSWORD_AAD)
        val again = Kdf.passwordKeys("correct horse battery", salt, logN = 10)
        assertArrayEquals(keys.authHash, again.authHash)
        assertArrayEquals(owner.privatePkcs8, Keyring.unwrapPrivateKey(again.kek, sealed, Keyring.PRIVKEY_PASSWORD_AAD))
        val wrong = Kdf.passwordKeys("correct horse batterY", salt, logN = 10)
        assertFalse(Crypto.ctEqual(keys.authHash, wrong.authHash))
        assertCryptoFails { Keyring.unwrapPrivateKey(wrong.kek, sealed, Keyring.PRIVKEY_PASSWORD_AAD) }
        assertCryptoFails { Keyring.unwrapPrivateKey(keys.kek, sealed, Keyring.PRIVKEY_RECOVERY_AAD) }
        // NFKC: the same password typed with a different Unicode form derives the same keys.
        val composed = Kdf.passwordKeys("café au lait", salt, logN = 10)
        val decomposed = Kdf.passwordKeys("café au lait", salt, logN = 10)
        assertArrayEquals(composed.kek, decomposed.kek)
    }

    @Test
    fun recoveryKeyFormat() {
        val k = RecoveryKey.generate()
        assertTrue(Regex("^([0-9A-Z]{4}-){12}[0-9A-Z]{4}$").matches(k.display))
        assertArrayEquals(k.bytes, RecoveryKey.parse(k.display))
        assertArrayEquals(k.bytes, RecoveryKey.parse(k.display.lowercase().replace("-", " ")))
        assertArrayEquals(k.bytes, RecoveryKey.parse(k.display.replace('0', 'O').replace('1', 'l')))
        assertNull(RecoveryKey.parse(k.display.dropLast(1)))
        assertNull(RecoveryKey.parse("U" + k.display.drop(1)))
        val zeros = ByteArray(32)
        assertArrayEquals(zeros, RecoveryKey.parse(RecoveryKey.encode(zeros)))
        val ff = ByteArray(32) { -1 }
        assertArrayEquals(ff, RecoveryKey.parse(RecoveryKey.encode(ff)))
    }

    @Test
    fun passwordPolicy() {
        assertNotNull(Kdf.policyError("short"))
        assertNotNull(Kdf.policyError("password"))
        assertNotNull(Kdf.policyError("aaaaaaaaaaaa"))
        assertNull(Kdf.policyError("blue kettle 42"))
    }
}

class MediaCryptoTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun roundTrip(size: Int, writeIn: Int) {
        val key = Crypto.randomKey()
        val data = ByteArray(size) { (it * 31 + 7).toByte() }
        val f = File(tmp.root, "m$size.bin")
        val w = MediaCrypto.Writer(f, key, 7, Crypto.random(16))
        var off = 0
        while (off < size) {
            val n = minOf(writeIn, size - off)
            w.write(data, off, n)
            off += n
        }
        assertEquals(size.toLong(), w.finish())
        assertFalse(File(f.path + ".tmp").exists())
        MediaCrypto.Reader(f) { id -> assertEquals(7L, id); key }.use { r ->
            assertEquals(size.toLong(), r.size)
            assertArrayEquals(data, r.readAll())
            if (size > 10) {
                // Random access across a chunk boundary.
                val pos = (MediaCrypto.CHUNK - 3).coerceAtMost(size - 5).toLong()
                val buf = ByteArray(5)
                var got = 0
                while (got < 5) got += r.read(pos + got, buf, got, 5 - got)
                assertArrayEquals(data.copyOfRange(pos.toInt(), pos.toInt() + 5), buf)
            }
            assertEquals(-1, r.read(size.toLong(), ByteArray(1), 0, 1))
        }
    }

    @Test
    fun sizes() {
        roundTrip(0, 10)
        roundTrip(1, 10)
        roundTrip(MediaCrypto.CHUNK, 1000)
        roundTrip(MediaCrypto.CHUNK + 1, MediaCrypto.CHUNK * 3)
        roundTrip(3 * MediaCrypto.CHUNK + 12345, 7777)
    }

    @Test
    fun detectsTruncationAndTampering() {
        val key = Crypto.randomKey()
        val f = File(tmp.root, "t.bin")
        MediaCrypto.Writer(f, key, 1, Crypto.random(16)).apply {
            write(ByteArray(3 * MediaCrypto.CHUNK) { 1 })
            finish()
        }
        val bytes = f.readBytes()
        // Drop the final chunk: the (new) last chunk was not sealed as final.
        val cut = File(tmp.root, "cut.bin").apply { writeBytes(bytes.copyOf(MediaCrypto.HEADER_LEN + 2 * MediaCrypto.ENC_CHUNK)) }
        assertCryptoFails { MediaCrypto.Reader(cut) { key }.use { it.readAll() } }
        val flipped = File(tmp.root, "flip.bin").apply { writeBytes(bytes.copyOf().also { it[MediaCrypto.HEADER_LEN + 10] = (it[MediaCrypto.HEADER_LEN + 10] + 1).toByte() }) }
        assertCryptoFails { MediaCrypto.Reader(flipped) { key }.use { it.readAll() } }
        assertCryptoFails { MediaCrypto.Reader(f) { Crypto.randomKey() }.use { it.readAll() } }
        val abortTarget = File(tmp.root, "a.bin")
        MediaCrypto.Writer(abortTarget, key, 1, Crypto.random(16)).apply { write(ByteArray(10)); abort() }
        assertFalse(abortTarget.exists())
        assertFalse(File(abortTarget.path + ".tmp").exists())
    }
}
