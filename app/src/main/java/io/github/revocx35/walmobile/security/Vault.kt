package io.github.revocx35.walmobile.security

import io.github.revocx35.walmobile.core.ApiException
import io.github.revocx35.walmobile.core.crypto.Crypto
import io.github.revocx35.walmobile.core.crypto.DataReader
import io.github.revocx35.walmobile.core.crypto.DataWriter
import io.github.revocx35.walmobile.core.crypto.Kdf
import io.github.revocx35.walmobile.core.crypto.Keyring
import io.github.revocx35.walmobile.core.crypto.RecoveryKey
import io.github.revocx35.walmobile.store.OwnerRow
import io.github.revocx35.walmobile.store.Repo
import io.github.revocx35.walmobile.store.values
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class VaultState { Loading, NoOwner, Locked, Unlocked }

/**
 * The owner's keys (the phone's version of the server's signup/login/recovery, ARCHITECTURE.md §6).
 *
 * - The public key is always available, so the logger can encrypt while the app is locked ([writer]).
 * - The private key exists only in memory between [unlock] and [lock]; it is stored wrapped by the
 *   password KEK (scrypt), by the recovery key, and optionally by a biometric-bound Keystore key.
 * - Failed unlocks are counted in the database *before* the slow KDF runs, so killing the app mid-try
 *   doesn't reset the count. After 5 failures: 30 s … 1 h between attempts.
 */
class Vault(private val repo: Repo, private val now: () -> Long = System::currentTimeMillis, private val logN: Int = Kdf.SCRYPT_LOG_N) {
    private val _state = MutableStateFlow(VaultState.Loading)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    @Volatile private var reader: DataReader? = null
    @Volatile private var privatePkcs8: ByteArray? = null
    @Volatile private var writer: DataWriter? = null
    private val lock = Any()

    /** Reads the owner row (off the main thread). */
    suspend fun load() = withContext(Dispatchers.IO) {
        val owner = repo.getOwner()
        synchronized(lock) {
            if (owner != null && writer == null) writer = DataWriter(repo, owner.publicKey, now)
            if (_state.value == VaultState.Loading || _state.value == VaultState.NoOwner) {
                _state.value = if (owner == null) VaultState.NoOwner else VaultState.Locked
            }
        }
    }

    val unlocked: Boolean get() = reader != null

    /** Encrypts new data; null until an owner exists. Works while locked. */
    fun writer(): DataWriter? = writer

    /** Decrypts data; throws `locked` when the app is locked. */
    fun reader(): DataReader = reader ?: throw ApiException.locked()

    fun hasBiometric(): Boolean = repo.getOwner()?.privkeyBio != null

    private fun open(pkcs8: ByteArray, publicKey: ByteArray) {
        val r = DataReader(repo, Crypto.ecPrivate(pkcs8), publicKey)
        synchronized(lock) {
            reader?.dispose()
            privatePkcs8?.fill(0)
            reader = r
            privatePkcs8 = pkcs8
            _state.value = VaultState.Unlocked
        }
    }

    /** Forgets the private key and every unwrapped data key. */
    fun lock() {
        synchronized(lock) {
            reader?.dispose()
            reader = null
            privatePkcs8?.fill(0)
            privatePkcs8 = null
            if (_state.value == VaultState.Unlocked) _state.value = VaultState.Locked
        }
    }

    /* ---------------------------------------------------------- setup */

    /** Creates the owner keys and unlocks. Returns the recovery key (shown once). */
    suspend fun create(password: String): String = withContext(Dispatchers.IO) {
        Kdf.policyError(password)?.let { throw ApiException.validation(it) }
        if (repo.hasOwner()) throw ApiException.conflict("The app is already set up.")
        val kp = Crypto.generateEc()
        val pwSalt = Crypto.random(Kdf.SALT_LEN)
        val pw = Kdf.passwordKeys(password, pwSalt, logN)
        val rk = RecoveryKey.generate()
        val recSalt = Crypto.random(Kdf.SALT_LEN)
        val rec = Kdf.recoveryKeys(rk.bytes, recSalt)
        val t = now()
        val created = repo.createOwner(
            OwnerRow(
                pwSalt = pwSalt,
                pwAuthHash = pw.authHash,
                pwLogN = logN,
                publicKey = kp.publicX509,
                privkeyPw = Keyring.wrapPrivateKey(pw.kek, kp.privatePkcs8, Keyring.PRIVKEY_PASSWORD_AAD),
                privkeyRecovery = Keyring.wrapPrivateKey(rec.kek, kp.privatePkcs8, Keyring.PRIVKEY_RECOVERY_AAD),
                recoverySalt = recSalt,
                recoveryAuthHash = rec.authHash,
                privkeyBio = null,
                failedUnlocks = 0,
                lockedUntil = null,
                createdAt = t,
                passwordChangedAt = t,
            ),
        )
        pw.wipe()
        rec.wipe()
        rk.bytes.fill(0)
        if (!created) throw ApiException.conflict("The app is already set up.")
        repo.audit("setup")
        synchronized(lock) { writer = DataWriter(repo, kp.publicX509, now) }
        open(kp.privatePkcs8, kp.publicX509)
        rk.display
    }

    /* --------------------------------------------------------- unlock */

    private fun owner(): OwnerRow = repo.getOwner() ?: throw ApiException.conflict("The app is not set up yet.")

    /** Throws `rate_limited` while locked out; otherwise records one attempt (reset on success). */
    private fun charge(o: OwnerRow) {
        val until = o.lockedUntil
        val t = now()
        if (until != null && until > t) {
            throw ApiException(429, "rate_limited", "Too many wrong attempts.", ((until - t + 999) / 1000).toInt())
        }
        val failed = o.failedUnlocks + 1
        val lockMs = if (failed >= FREE_ATTEMPTS) minOf(30_000L shl minOf(failed - FREE_ATTEMPTS, 7), 3600_000L) else 0L
        repo.updateOwner(values("failed_unlocks" to failed, "locked_until" to (if (lockMs > 0) t + lockMs else null)))
    }

    private fun resetFailures() {
        repo.updateOwner(values("failed_unlocks" to 0, "locked_until" to null))
    }

    /** Checks the password; returns the unwrapped private key. Counts as an attempt. */
    private fun checkPassword(o: OwnerRow, password: String): ByteArray {
        charge(o)
        val started = System.nanoTime()
        val keys = Kdf.passwordKeys(password, o.pwSalt, o.pwLogN)
        if (io.github.revocx35.walmobile.BuildConfig.DEBUG) android.util.Log.d("walm", "password KDF took ${(System.nanoTime() - started) / 1_000_000} ms")
        try {
            if (!Crypto.ctEqual(keys.authHash, o.pwAuthHash)) {
                repo.audit("unlock_failed")
                throw ApiException(401, "invalid_credentials", "Wrong password.")
            }
            val pkcs8 = try {
                Keyring.unwrapPrivateKey(keys.kek, o.privkeyPw, Keyring.PRIVKEY_PASSWORD_AAD)
            } catch (e: Exception) {
                throw ApiException(500, "internal", "The stored key could not be decrypted.", cause = e)
            }
            resetFailures()
            return pkcs8
        } finally {
            keys.wipe()
        }
    }

    suspend fun unlock(password: String) = withContext(Dispatchers.IO) {
        val o = owner()
        val pkcs8 = checkPassword(o, password)
        repo.audit("unlock")
        open(pkcs8, o.publicKey)
    }

    /** Re-checks the password for sensitive actions (wipe). */
    suspend fun verifyPassword(password: String) = withContext(Dispatchers.IO) {
        checkPassword(owner(), password).fill(0)
    }

    /** Recovery key + new password → unlocked, with a new recovery key (returned, shown once). */
    suspend fun recover(recoveryKey: String, newPassword: String): String = withContext(Dispatchers.IO) {
        Kdf.policyError(newPassword)?.let { throw ApiException.validation(it) }
        val o = owner()
        charge(o)
        val bytes = RecoveryKey.parse(recoveryKey) ?: run {
            repo.audit("recover_failed")
            throw ApiException(401, "invalid_credentials", "That recovery key is not valid.")
        }
        val keys = Kdf.recoveryKeys(bytes, o.recoverySalt)
        bytes.fill(0)
        if (!Crypto.ctEqual(keys.authHash, o.recoveryAuthHash)) {
            keys.wipe()
            repo.audit("recover_failed")
            throw ApiException(401, "invalid_credentials", "That recovery key is not valid.")
        }
        val pkcs8 = try {
            Keyring.unwrapPrivateKey(keys.kek, o.privkeyRecovery, Keyring.PRIVKEY_RECOVERY_AAD)
        } finally {
            keys.wipe()
        }
        val newKey = rewrap(pkcs8, newPassword, newRecovery = true, clearBiometric = true)!!
        resetFailures()
        repo.audit("recover")
        open(pkcs8, o.publicKey)
        newKey
    }

    suspend fun changePassword(current: String, next: String) = withContext(Dispatchers.IO) {
        Kdf.policyError(next)?.let { throw ApiException.validation(it) }
        val o = owner()
        val pkcs8 = checkPassword(o, current)
        rewrap(pkcs8, next, newRecovery = false, clearBiometric = false)
        repo.audit("password_changed")
        open(pkcs8, o.publicKey)
    }

    /** A new recovery key (the old one stops working). */
    suspend fun rotateRecoveryKey(password: String): String = withContext(Dispatchers.IO) {
        val o = owner()
        val pkcs8 = checkPassword(o, password)
        try {
            val rk = RecoveryKey.generate()
            val salt = Crypto.random(Kdf.SALT_LEN)
            val rec = Kdf.recoveryKeys(rk.bytes, salt)
            repo.updateOwner(
                values(
                    "privkey_recovery" to Keyring.wrapPrivateKey(rec.kek, pkcs8, Keyring.PRIVKEY_RECOVERY_AAD),
                    "recovery_salt" to salt,
                    "recovery_auth_hash" to rec.authHash,
                ),
            )
            rec.wipe()
            rk.bytes.fill(0)
            repo.scrub()
            repo.audit("recovery_key_rotated")
            rk.display
        } finally {
            pkcs8.fill(0)
        }
    }

    /** Wraps [pkcs8] under a new password (and optionally a new recovery key, returned). */
    private fun rewrap(pkcs8: ByteArray, password: String, newRecovery: Boolean, clearBiometric: Boolean): String? {
        val salt = Crypto.random(Kdf.SALT_LEN)
        val pw = Kdf.passwordKeys(password, salt, logN)
        val v = values(
            "pw_salt" to salt,
            "pw_auth_hash" to pw.authHash,
            "pw_log_n" to logN,
            "privkey_pw" to Keyring.wrapPrivateKey(pw.kek, pkcs8, Keyring.PRIVKEY_PASSWORD_AAD),
            "password_changed_at" to now(),
        )
        var rec: Kdf.DerivedKeys? = null
        var display: String? = null
        if (newRecovery) {
            val rk = RecoveryKey.generate()
            val rsalt = Crypto.random(Kdf.SALT_LEN)
            val r = Kdf.recoveryKeys(rk.bytes, rsalt)
            v.put("privkey_recovery", Keyring.wrapPrivateKey(r.kek, pkcs8, Keyring.PRIVKEY_RECOVERY_AAD))
            v.put("recovery_salt", rsalt)
            v.put("recovery_auth_hash", r.authHash)
            rk.bytes.fill(0)
            rec = r
            display = rk.display
        }
        if (clearBiometric) v.putNull("privkey_bio")
        try {
            repo.updateOwner(v)
        } finally {
            // ContentValues keeps references: wipe only after the row is written.
            pw.wipe()
            rec?.wipe()
        }
        repo.scrub()
        return display
    }

    /* ------------------------------------------------------ biometric */

    /** The private key, for wrapping with a biometric-bound key (only while unlocked). */
    fun privateKeyForBiometric(): ByteArray = privatePkcs8?.copyOf() ?: throw ApiException.locked()

    suspend fun storeBiometric(sealed: ByteArray?) = withContext(Dispatchers.IO) {
        repo.updateOwner(values("privkey_bio" to sealed))
        repo.scrub()
        repo.audit(if (sealed != null) "biometric_enabled" else "biometric_disabled")
    }

    suspend fun biometricBlob(): ByteArray? = withContext(Dispatchers.IO) { repo.getOwner()?.privkeyBio }

    /** Unlock with the private key a successful biometric prompt decrypted. */
    suspend fun unlockWithPrivateKey(pkcs8: ByteArray) = withContext(Dispatchers.IO) {
        val o = owner()
        // Prove the key belongs to this owner before trusting it.
        val probe = Crypto.random(32)
        val w = Keyring.wrapDek(o.publicKey, probe)
        val back = try {
            Keyring.unwrapDek(Crypto.ecPrivate(pkcs8), o.publicKey, w)
        } catch (e: Exception) {
            throw ApiException(401, "invalid_credentials", "Fingerprint unlock failed. Use your password.", cause = e)
        }
        if (!back.contentEquals(probe)) throw ApiException(401, "invalid_credentials", "Fingerprint unlock failed. Use your password.")
        resetFailures()
        repo.audit("unlock_biometric")
        open(pkcs8, o.publicKey)
    }

    companion object {
        const val FREE_ATTEMPTS = 5
    }
}
