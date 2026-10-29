package com.vsc.android.runtime

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 AndroidKeyStore 的 AES/GCM 加密存储，用于保存 code-server 访问密码。
 *
 * 部分定制 ROM 的 Keystore 会抛异常，此时降级为明文存储并在日志中标注，
 * 保证功能可用、不因加密失败导致启动崩溃。
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val PREF_NAME = "vsc_secure"
    private const val KEY_ALIAS = "vsc_android_secure_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PLAIN_PREFIX = "plain:"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    fun put(key: String, value: String) {
        val p = prefs ?: return
        val encoded = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        }.getOrElse {
            Log.w(TAG, "Keystore 不可用，降级为明文存储", it)
            PLAIN_PREFIX + Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
        }
        p.edit().putString(key, encoded).apply()
    }

    fun get(key: String): String? {
        val p = prefs ?: return null
        val raw = p.getString(key, null) ?: return null
        if (raw.startsWith(PLAIN_PREFIX)) {
            return runCatching {
                String(Base64.decode(raw.removePrefix(PLAIN_PREFIX), Base64.NO_WRAP))
            }.getOrNull()
        }
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return null
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val body = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrElse {
            Log.w(TAG, "解密失败，忽略已存密码", it)
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
