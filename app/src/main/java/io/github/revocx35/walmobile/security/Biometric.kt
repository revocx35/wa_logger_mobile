package io.github.revocx35.walmobile.security

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import io.github.revocx35.walmobile.core.ApiException
import io.github.revocx35.walmobile.core.crypto.Keyring
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fingerprint/face unlock: a second copy of the owner's private key, encrypted with a non-exportable
 * Android Keystore key that can only be used right after a strong biometric check (and is invalidated
 * when biometrics are added or removed). The password and recovery key keep working as before.
 */
object Biometric {
    private const val ALIAS = "walm_biometric_v1"
    private const val IV_LEN = 12

    /** Strong biometrics enrolled and usable (Android 11+: the class 3 check this key needs). */
    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val bm = context.getSystemService(BiometricManager::class.java) ?: return false
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun newKey(): SecretKey {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        g.init(spec.build())
        return g.generateKey()
    }

    fun forget() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private suspend fun prompt(context: Context, cipher: Cipher, title: String, subtitle: String): Cipher = suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        val executor = context.mainExecutor
        val builder = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButton("Use password", executor) { _, _ ->
                if (cont.isActive) cont.resumeWithException(ApiException(400, "cancelled", "Cancelled."))
            }
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        builder.build().authenticate(BiometricPrompt.CryptoObject(cipher), cancel, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (!cont.isActive) return
                if (c != null) cont.resume(c) else cont.resumeWithException(ApiException(401, "invalid_credentials", "Fingerprint unlock failed."))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (cont.isActive) cont.resumeWithException(ApiException(400, "cancelled", errString.toString()))
            }
        })
    }

    /** Wraps the private key of the unlocked vault after a biometric check, and stores it. */
    suspend fun enable(context: Context, vault: Vault) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, newKey())
        val ok = prompt(context, cipher, "Turn on fingerprint unlock", "Confirm with your fingerprint or face")
        val pkcs8 = vault.privateKeyForBiometric()
        try {
            ok.updateAAD(Keyring.PRIVKEY_BIOMETRIC_AAD.toByteArray())
            val sealed = ok.iv + ok.doFinal(pkcs8)
            vault.storeBiometric(sealed)
        } finally {
            pkcs8.fill(0)
        }
    }

    suspend fun disable(vault: Vault) {
        vault.storeBiometric(null)
        forget()
    }

    /** Unlocks with a biometric check; throws when cancelled or when the key was invalidated. */
    suspend fun unlock(context: Context, vault: Vault) {
        val blob = vault.biometricBlob() ?: throw ApiException(400, "unavailable", "Fingerprint unlock is not set up.")
        val key = keyStore().getKey(ALIAS, null) as? SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            if (key == null) throw KeyPermanentlyInvalidatedException()
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, IV_LEN))
        } catch (e: KeyPermanentlyInvalidatedException) {
            vault.storeBiometric(null)
            forget()
            throw ApiException(401, "invalid_credentials", "Fingerprints changed since fingerprint unlock was set up. Unlock with your password and turn it on again.", cause = e)
        }
        val ok = prompt(context, cipher, "Unlock wa_logger", "Use your fingerprint or face")
        ok.updateAAD(Keyring.PRIVKEY_BIOMETRIC_AAD.toByteArray())
        val pkcs8 = try {
            ok.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        } catch (e: Exception) {
            throw ApiException(401, "invalid_credentials", "Fingerprint unlock failed. Use your password.", cause = e)
        }
        vault.unlockWithPrivateKey(pkcs8)
    }
}
