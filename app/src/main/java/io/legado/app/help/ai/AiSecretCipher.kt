package io.legado.app.help.ai

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AI 配置敏感字段（apiKey、自定义请求头等）的本地加密存储。
 *
 * 密文格式: enc:v1:<base64(iv || ciphertext)>，AES-256-GCM，密钥保存在 AndroidKeyStore，
 * 不会随备份导出。历史明文读取时原样返回（懒迁移），下次写回时自动加密。
 * API < 23 或 Keystore 不可用时退化为明文；密钥丢失（如换机恢复备份）时解密返回空串。
 */
object AiSecretCipher {

    private const val KEY_ALIAS = "legadoh_ai_secret_v1"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val PREFIX = "enc:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    @Volatile
    private var cachedKey: SecretKey? = null

    fun encrypt(plain: String): String {
        if (plain.isBlank() || plain.startsWith(PREFIX)) return plain
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return plain
        val key = runCatching { obtainKey() }.getOrNull() ?: return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            require(iv.size == IV_BYTES) { "unexpected GCM iv length: ${iv.size}" }
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val bytes = ByteArray(iv.size + encrypted.size)
            iv.copyInto(bytes)
            encrypted.copyInto(bytes, iv.size)
            PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    fun decrypt(stored: String): String {
        if (stored.isBlank() || !stored.startsWith(PREFIX)) return stored
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return ""
        return runCatching {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return ""
            val key = obtainKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrElse {
            // 密钥丢失（换机恢复备份、清除凭据等），不返回乱码
            ""
        }
    }

    private fun obtainKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
                cachedKey = it
                return it
            }
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey().also { cachedKey = it }
        }
    }
}
