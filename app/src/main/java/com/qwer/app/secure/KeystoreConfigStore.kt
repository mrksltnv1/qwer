package com.qwer.app.secure

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
 * Stores VPN configs (which contain private keys) encrypted with a hardware-backed
 * Android Keystore key.
 *
 * Design notes:
 *  - The keystore key never leaves the TEE/StrongBox; only AES-GCM ciphertext is persisted.
 *  - SharedPreferences holds ciphertext + IV, never plaintext. A plain SharedPreferences
 *    dump therefore does not expose a private key.
 *  - The key is generated on first use and is non-exportable.
 *  - Configs are never written to logcat, crash reports or analytics.
 */
interface ConfigStore {
    fun putConfig(profileId: String, rawConfig: String)
    fun getConfig(profileId: String): String?
    fun removeConfig(profileId: String)
}

class KeystoreConfigStore(context: Context) : ConfigStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-authentication requirement: the VPN must be able to reconnect
                // in the background (e.g. after a reboot) without unlocking the app.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    override fun putConfig(profileId: String, rawConfig: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(rawConfig.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(ivKey(profileId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(dataKey(profileId), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    override fun getConfig(profileId: String): String? {
        val ivB64 = prefs.getString(ivKey(profileId), null) ?: return null
        val dataB64 = prefs.getString(dataKey(profileId), null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivB64, Base64.NO_WRAP)),
            )
            val plain = cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP))
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            // Key invalidated (e.g. device credential reset) or corrupted entry.
            // Delete the unusable copy so the user is prompted to re-import.
            removeConfig(profileId)
            null
        }
    }

    override fun removeConfig(profileId: String) {
        prefs.edit()
            .remove(ivKey(profileId))
            .remove(dataKey(profileId))
            .apply()
    }

    private fun ivKey(id: String) = "cfg_iv_$id"
    private fun dataKey(id: String) = "cfg_ct_$id"

    private companion object {
        const val PREFS_NAME = "vpn_secure_configs"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "qwer_config_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
