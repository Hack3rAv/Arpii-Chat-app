package com.systemutility.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidKeystoreManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    private val secureRandom = SecureRandom()

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getOrCreateDatabaseKey(): ByteArray {
        return getOrCreateWrappedKey(DB_KEY_PREF, KEY_ALIAS_DB)
    }

    fun getOrCreateVaultKey(): ByteArray {
        return getOrCreateWrappedKey(VAULT_KEY_PREF, KEY_ALIAS_VAULT)
    }

    @Synchronized
    private fun getOrCreateWrappedKey(prefKey: String, alias: String): ByteArray {
        val existingWrapped = encryptedPrefs.getString(prefKey, null)
        if (existingWrapped != null) {
            return unwrapSecret(alias, existingWrapped)
        }
        val raw = ByteArray(32)
        secureRandom.nextBytes(raw)
        val wrapped = wrapSecret(alias, raw)
        encryptedPrefs.edit().putString(prefKey, wrapped).apply()
        return raw
    }

    private fun getOrCreateKeystoreKey(alias: String): SecretKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun wrapSecret(alias: String, secret: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey(alias))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(secret)
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun unwrapSecret(alias: String, encoded: String): ByteArray {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(alias), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val PREFS_FILE = "system_utility_secure_vault"
        private const val KEY_ALIAS_DB = "sysutil_sqlcipher_wrap_v1"
        private const val KEY_ALIAS_VAULT = "sysutil_media_vault_v1"
        private const val DB_KEY_PREF = "wrapped_sqlcipher_key"
        private const val VAULT_KEY_PREF = "wrapped_vault_key"
    }
}
