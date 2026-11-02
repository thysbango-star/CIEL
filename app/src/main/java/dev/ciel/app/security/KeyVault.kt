package dev.ciel.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API keys and tokens encrypted with an AES-256-GCM key that lives in the Android
 * Keystore (hardware-backed where the device supports it). The key never leaves the Keystore;
 * only ciphertext + IV are written to app-private storage, which is excluded from backups.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("ciel_vault", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun put(name: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ct
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }

    @Synchronized
    fun get(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_BYTES)
            val ct = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key invalidated (e.g. device credential reset) — the stored secret is unrecoverable.
            null
        }
    }

    fun has(name: String): Boolean = prefs.contains(name)

    /** Removes the ciphertext. Without it, nothing remains that could be decrypted. */
    @Synchronized
    fun delete(name: String) { prefs.edit().remove(name).commit() }

    /** Deletes every stored secret AND the Keystore key itself. */
    @Synchronized
    fun wipeAll() {
        prefs.edit().clear().commit()
        runCatching { keyStore.deleteEntry(ALIAS) }
    }

    /** Last 4 characters, for display ("••••abcd"). Never shows the key. */
    fun hint(name: String): String? = get(name)?.takeLast(4)?.let { "••••$it" }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "ciel_vault_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12

        fun providerKeyName(providerId: String) = "provider:$providerId"
        fun searchKeyName() = "search:key"
        fun discordWebhookName(name: String) = "discord:$name"
    }
}
