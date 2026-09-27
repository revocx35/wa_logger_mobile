package io.github.revocx35.walmobile.core.crypto

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Any failure to authenticate or decrypt. The message never contains key material or plaintext. */
class CryptoException(message: String = "decryption failed", cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * Building blocks, all from the platform providers (Conscrypt on Android, SunJCE/SunEC on the JVM),
 * so the same code runs on every Android version the app supports (API 26+) and in JVM tests.
 * P-256 is used for public-key encryption because X25519 is only in the platform from API 33.
 */
object Crypto {
    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_LEN = 16

    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun randomKey(): ByteArray = random(KEY_LEN)

    /** AES-256-GCM. Output: nonce(12) ‖ ciphertext ‖ tag(16). */
    fun sealGcm(key: ByteArray, plaintext: ByteArray, aad: ByteArray, nonce: ByteArray = random(NONCE_LEN)): ByteArray {
        require(key.size == KEY_LEN) { "bad key length" }
        require(nonce.size == NONCE_LEN) { "bad nonce length" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
        c.updateAAD(aad)
        val ct = c.doFinal(plaintext)
        return nonce + ct
    }

    fun sealGcm(key: ByteArray, plaintext: ByteArray, aad: String): ByteArray = sealGcm(key, plaintext, aad.toByteArray())

    /** Inverse of [sealGcm]; throws [CryptoException] on any failure (wrong key, tampering, truncation). */
    fun openGcm(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        if (key.size != KEY_LEN) throw CryptoException("bad key length")
        if (sealed.size < NONCE_LEN + TAG_LEN) throw CryptoException("ciphertext too short")
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, sealed, 0, NONCE_LEN))
            c.updateAAD(aad)
            c.doFinal(sealed, NONCE_LEN, sealed.size - NONCE_LEN)
        } catch (e: GeneralSecurityException) {
            throw CryptoException(cause = e)
        }
    }

    fun openGcm(key: ByteArray, sealed: ByteArray, aad: String): ByteArray = openGcm(key, sealed, aad.toByteArray())

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An empty HMAC key is valid (RFC 2104), but SecretKeySpec rejects it: use the zero block instead.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, len: Int = KEY_LEN): ByteArray {
        require(len in 1..255 * 32)
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        val infoBytes = info.toByteArray()
        while (pos < len) {
            t = hmacSha256(prk, t + infoBytes + byteArrayOf(i.toByte()))
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        prk.fill(0)
        return out
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Constant-time equality (length differences are not leaked through timing either). */
    fun ctEqual(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(sha256(a), sha256(b)) && a.size == b.size

    /* ---------------------------------------------------------------- P-256 */

    class EcKeyPair(val privatePkcs8: ByteArray, val publicX509: ByteArray)

    fun generateEc(): EcKeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), rng)
        val kp = g.generateKeyPair()
        return EcKeyPair(kp.private.encoded, kp.public.encoded)
    }

    fun ecPrivate(pkcs8: ByteArray): PrivateKey = try {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    } catch (e: GeneralSecurityException) {
        throw CryptoException("bad private key", e)
    }

    fun ecPublic(x509: ByteArray): PublicKey = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509))
    } catch (e: GeneralSecurityException) {
        throw CryptoException("bad public key", e)
    }

    /** ECDH shared secret (32 bytes, the x coordinate). */
    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray = try {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        ka.generateSecret()
    } catch (e: GeneralSecurityException) {
        throw CryptoException("key agreement failed", e)
    }
}

internal fun ByteArray.u32be(offset: Int): Long =
    ((this[offset].toLong() and 0xFF) shl 24) or ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or (this[offset + 3].toLong() and 0xFF)

internal fun u32beBytes(v: Long): ByteArray =
    byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
