package com.kitsuneandroid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object SecureLocalStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "kitsune_private_values"

    fun save(context: Context, preferencesName: String, key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP))
            .toString()
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
            .putString(key, payload)
            .apply()
    }

    fun load(context: Context, preferencesName: String, key: String): String? {
        val payload = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .getString(key, null) ?: return null
        return try {
            val json = JSONObject(payload)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(Base64.decode(json.getString("data"), Base64.NO_WRAP)))
        } catch (_: Exception) {
            remove(context, preferencesName, key)
            null
        }
    }

    fun remove(context: Context, preferencesName: String, key: String) {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
