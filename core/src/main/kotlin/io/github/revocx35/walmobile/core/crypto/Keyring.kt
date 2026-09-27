package io.github.revocx35.walmobile.core.crypto

import java.security.PrivateKey
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/*
 * Envelope encryption, the same design as the wa_logger server (its ARCHITECTURE.md §6), with P-256 in
 * place of X25519:
 *   owner keypair ─ private key wrapped by the password KEK and, separately, by the recovery KEK
 *   DEK (random 32 B, one per process start, rotated daily) ─ ECIES-wrapped to the owner's public key
 *   fields & media ─ AES-256-GCM under a DEK, AAD-bound to their table/column/row
 * The logger only ever holds the public key and the current DEK, so it can write while the app is
 * locked but cannot read anything written earlier.
 */

object Kdf {
    const val SALT_LEN = 32
    const val SCRYPT_LOG_N = 15 // 32 MiB, r = 8, p = 1
    const val SCRYPT_R = 8
    const val SCRYPT_P = 1

    class DerivedKeys(val authHash: ByteArray, val kek: ByteArray) {
        fun wipe() {
            authHash.fill(0)
            kek.fill(0)
        }
    }

    fun normalize(password: String): String = Normalizer.normalize(password, Normalizer.Form.NFKC)

    /** master = scrypt(password); authHash (stored) and KEK (never stored) are independent HKDF expansions. */
    fun passwordKeys(password: String, salt: ByteArray, logN: Int = SCRYPT_LOG_N): DerivedKeys {
        val master = Scrypt.derive(normalize(password).toByteArray(), salt, logN, SCRYPT_R, SCRYPT_P, 32)
        try {
            return DerivedKeys(Crypto.hkdf(master, salt, "walm/v1/auth"), Crypto.hkdf(master, salt, "walm/v1/kek"))
        } finally {
            master.fill(0)
        }
    }

    /** The recovery key has 256 bits of entropy, so no slow KDF is needed. */
    fun recoveryKeys(recovery: ByteArray, salt: ByteArray): DerivedKeys =
        DerivedKeys(Crypto.hkdf(recovery, salt, "walm/v1/recovery-auth"), Crypto.hkdf(recovery, salt, "walm/v1/recovery-kek"))

    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 256

    private val COMMON = setOf(
        "password", "password1", "password12", "password123", "password1234", "12345678", "123456789", "1234567890",
        "qwertyuiop", "qwerty123", "iloveyou", "letmein1", "11111111", "00000000", "abcdefgh", "whatsapp", "whatsapp1",
        "passw0rd", "changeme", "welcome1", "admin123", "asdfghjkl", "87654321", "abc12345",
    )

    /** A user-facing reason when the password is not acceptable, otherwise null. */
    fun policyError(password: String): String? {
        val pw = normalize(password)
        val chars = pw.codePointCount(0, pw.length)
        if (chars < PASSWORD_MIN) return "Password must be at least $PASSWORD_MIN characters."
        if (chars > PASSWORD_MAX) return "Password must be at most $PASSWORD_MAX characters."
        val lower = pw.lowercase()
        if (lower in COMMON) return "This password is too common."
        if (lower.toSet().size < 4) return "Password is too repetitive."
        return null
    }
}

/** 32 random bytes shown as groups of 4 Crockford base32 characters (no I, L, O, U). */
object RecoveryKey {
    private const val B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val BYTES = 32

    class Generated(val display: String, val bytes: ByteArray)

    fun generate(): Generated {
        val bytes = Crypto.random(BYTES)
        return Generated(encode(bytes).chunked(4).joinToString("-"), bytes)
    }

    fun encode(buf: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        for (b in buf) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
            value = value and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(B32[(value shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** Accepts any separators/case and maps easily confused letters (O→0, I/L→1). */
    fun parse(input: String): ByteArray? {
        val cleaned = input.uppercase().filterNot { it.isWhitespace() || it == '-' }.replace('O', '0').replace('I', '1').replace('L', '1')
        if (cleaned.length != (BYTES * 8 + 4) / 5) return null
        val out = java.io.ByteArrayOutputStream()
        var bits = 0
        var value = 0
        for (ch in cleaned) {
            val idx = B32.indexOf(ch)
            if (idx < 0) return null
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                out.write((value ushr (bits - 8)) and 0xFF)
                bits -= 8
            }
            value = value and ((1 shl bits) - 1)
        }
        val bytes = out.toByteArray()
        return if (bytes.size == BYTES) bytes else null
    }
}

class WrappedDek(val ephPub: ByteArray, val wrapped: ByteArray)

interface DataKeyStore {
    fun insertDataKey(ephPub: ByteArray, wrapped: ByteArray, createdAt: Long): Long
    fun getDataKey(id: Long): WrappedDek?
}

object Keyring {
    const val PRIVKEY_PASSWORD_AAD = "walm/v1|privkey|password"
    const val PRIVKEY_RECOVERY_AAD = "walm/v1|privkey|recovery"
    const val PRIVKEY_BIOMETRIC_AAD = "walm/v1|privkey|biometric"

    private const val FIELD_VERSION: Byte = 0x01
    const val FIELD_HEADER_LEN = 5 // version ‖ keyId(u32be)

    fun wrapPrivateKey(kek: ByteArray, privatePkcs8: ByteArray, aad: String): ByteArray = Crypto.sealGcm(kek, privatePkcs8, aad)

    fun unwrapPrivateKey(kek: ByteArray, sealed: ByteArray, aad: String): ByteArray = Crypto.openGcm(kek, sealed, aad)

    private fun dekWrapKey(shared: ByteArray, ephPub: ByteArray, ownerPub: ByteArray) = Crypto.hkdf(shared, ephPub + ownerPub, "walm/v1/dek")

    /** ECIES: ephemeral P-256 → ECDH with the owner's public key → HKDF → AES-256-GCM. */
    fun wrapDek(ownerPublicX509: ByteArray, dek: ByteArray): WrappedDek {
        val eph = Crypto.generateEc()
        val shared = Crypto.ecdh(Crypto.ecPrivate(eph.privatePkcs8), Crypto.ecPublic(ownerPublicX509))
        val key = dekWrapKey(shared, eph.publicX509, ownerPublicX509)
        try {
            return WrappedDek(eph.publicX509, Crypto.sealGcm(key, dek, "walm/v1|dek"))
        } finally {
            shared.fill(0)
            key.fill(0)
            eph.privatePkcs8.fill(0)
        }
    }

    fun unwrapDek(ownerPrivate: PrivateKey, ownerPublicX509: ByteArray, w: WrappedDek): ByteArray {
        val shared = Crypto.ecdh(ownerPrivate, Crypto.ecPublic(w.ephPub))
        val key = dekWrapKey(shared, w.ephPub, ownerPublicX509)
        try {
            val dek = Crypto.openGcm(key, w.wrapped, "walm/v1|dek")
            if (dek.size != Crypto.KEY_LEN) throw CryptoException("bad dek")
            return dek
        } finally {
            shared.fill(0)
            key.fill(0)
        }
    }

    fun fieldAad(table: String, column: String, rowId: String, header: ByteArray): ByteArray =
        "walm/v1|$table|$column|$rowId|".toByteArray() + header

    fun fieldKeyId(blob: ByteArray): Long {
        if (blob.size < FIELD_HEADER_LEN || blob[0] != FIELD_VERSION) throw CryptoException("bad field header")
        return blob.u32be(1)
    }

    fun encryptField(key: ByteArray, keyId: Long, table: String, column: String, rowId: String, plaintext: ByteArray): ByteArray {
        val header = byteArrayOf(FIELD_VERSION) + u32beBytes(keyId)
        return header + Crypto.sealGcm(key, plaintext, fieldAad(table, column, rowId, header))
    }

    fun decryptField(key: ByteArray, table: String, column: String, rowId: String, blob: ByteArray): ByteArray {
        fieldKeyId(blob)
        val header = blob.copyOfRange(0, FIELD_HEADER_LEN)
        return Crypto.openGcm(key, blob.copyOfRange(FIELD_HEADER_LEN, blob.size), fieldAad(table, column, rowId, header))
    }
}

/** Where a field lives: its AAD context. Message bodies and their edit history share "msgbody". */
data class FieldRef(val table: String, val column: String, val rowId: String)

object F {
    fun chatName(id: String) = FieldRef("chats", "name", id)
    fun contactName(id: String) = FieldRef("contacts", "name", id)
    fun contactPushname(id: String) = FieldRef("contacts", "pushname", id)
    fun contactPhone(id: String) = FieldRef("contacts", "phone", id)
    fun body(messageId: String) = FieldRef("msgbody", "body", messageId)
    fun meta(messageId: String) = FieldRef("messages", "meta", messageId)
    fun thumb(messageId: String) = FieldRef("messages", "thumb", messageId)
    fun reaction(messageId: String, senderId: String) = FieldRef("reactions", "emoji", "$messageId|$senderId")
    fun mediaMime(path: String) = FieldRef("media", "mime", path)
    fun mediaFilename(path: String) = FieldRef("media", "filename", path)
}

/**
 * Encrypts new data. Holds only the *current* DEK; it can never read data written under earlier DEKs
 * (that needs the owner's private key, which only an unlocked app has).
 */
class DataWriter(
    private val store: DataKeyStore,
    private val ownerPublicX509: ByteArray,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class CurrentKey(val id: Long, val key: ByteArray)

    private var current: CurrentKey? = null
    private var createdAt = 0L

    @Synchronized
    fun currentKey(): CurrentKey {
        val t = now()
        val c = current
        if (c != null && t - createdAt <= ROTATE_MS) return c
        // The previous key is not zeroed: an in-flight media write may still use it.
        val key = Crypto.randomKey()
        val w = Keyring.wrapDek(ownerPublicX509, key)
        val id = store.insertDataKey(w.ephPub, w.wrapped, t)
        createdAt = t
        return CurrentKey(id, key).also { current = it }
    }

    fun encrypt(ref: FieldRef, value: ByteArray?): ByteArray? {
        if (value == null) return null
        val k = currentKey()
        return Keyring.encryptField(k.key, k.id, ref.table, ref.column, ref.rowId, value)
    }

    fun encrypt(ref: FieldRef, value: String?): ByteArray? = value?.let { encrypt(ref, it.toByteArray()) }

    @Synchronized
    fun dispose() {
        current?.key?.fill(0)
        current = null
    }

    companion object {
        const val ROTATE_MS = 24 * 3600_000L
    }
}

/** Decrypts with the unlocked private key; unwrapped DEKs are cached until [dispose]. Thread-safe. */
class DataReader(private val store: DataKeyStore, private val ownerPrivate: PrivateKey, private val ownerPublicX509: ByteArray) {
    private val deks = ConcurrentHashMap<Long, ByteArray>()

    fun key(keyId: Long): ByteArray = deks[keyId] ?: run {
        val w = store.getDataKey(keyId) ?: throw CryptoException("unknown data key")
        Keyring.unwrapDek(ownerPrivate, ownerPublicX509, w).also { deks[keyId] = it }
    }

    fun decrypt(ref: FieldRef, blob: ByteArray?): ByteArray? {
        if (blob == null) return null
        return Keyring.decryptField(key(Keyring.fieldKeyId(blob)), ref.table, ref.column, ref.rowId, blob)
    }

    /** Like [decrypt] but null instead of an exception for corrupted or tampered data. */
    fun tryBytes(ref: FieldRef, blob: ByteArray?): ByteArray? = try {
        decrypt(ref, blob)
    } catch (_: Exception) {
        null
    }

    fun tryText(ref: FieldRef, blob: ByteArray?): String? = tryBytes(ref, blob)?.toString(Charsets.UTF_8)

    fun dispose() {
        for (k in deks.values) k.fill(0)
        deks.clear()
    }
}
