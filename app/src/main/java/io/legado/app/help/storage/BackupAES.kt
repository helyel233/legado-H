package io.legado.app.help.storage

import cn.hutool.core.codec.Base64
import cn.hutool.crypto.symmetric.AES
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.MD5Utils
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup secret cipher:
 * - New exports use PBKDF2-HMAC-SHA256 + AES-256-GCM with random salt & IV (`v2:` prefix).
 * - Decryption remains backward-compatible with legacy MD5+AES-ECB payloads.
 */
class BackupAES(
    private val password: String = LocalConfig.password.orEmpty()
) : AES(legacyKeyBytes(password)) {

    val hasPassword: Boolean
        get() = password.isNotBlank()

    override fun encryptBase64(data: String): String {
        if (!hasPassword) {
            throw IllegalStateException("Backup password is not set")
        }
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }
        val keyBytes = deriveKey(password, salt)
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        val cipherText = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(SALT_BYTES + IV_BYTES + cipherText.size)
        salt.copyInto(payload, 0)
        iv.copyInto(payload, SALT_BYTES)
        cipherText.copyInto(payload, SALT_BYTES + IV_BYTES)
        return V2_PREFIX + Base64.encode(payload)
    }

    override fun decryptStr(data: String): String {
        if (data.startsWith(V2_PREFIX)) {
            if (!hasPassword) {
                throw IllegalStateException("Backup password is not set")
            }
            val payload = Base64.decode(data.removePrefix(V2_PREFIX))
            require(payload.size > SALT_BYTES + IV_BYTES) { "Invalid v2 backup ciphertext" }
            val salt = payload.copyOfRange(0, SALT_BYTES)
            val iv = payload.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
            val keyBytes = deriveKey(password, salt)
            val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            val plain = cipher.doFinal(payload, SALT_BYTES + IV_BYTES, payload.size - SALT_BYTES - IV_BYTES)
            return String(plain, Charsets.UTF_8)
        }
        return super.decryptStr(data)
    }

    companion object {
        const val V2_PREFIX = "v2:"
        private const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val PBKDF2_ITERATIONS = 65_536
        private const val KEY_BITS = 256
        private const val KEY_BYTES = KEY_BITS / 8
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private val secureRandom = SecureRandom()

        private fun legacyKeyBytes(password: String): ByteArray {
            return MD5Utils.md5Encode(password).encodeToByteArray(0, 16)
        }

        private fun deriveKey(password: String, salt: ByteArray): ByteArray {
            return runCatching {
                val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
                val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
                try {
                    factory.generateSecret(spec).encoded
                } finally {
                    spec.clearPassword()
                }
            }.getOrElse {
                // Fallback for Android API 21-25 where PBKDF2WithHmacSHA256 is unavailable in default JCE.
                pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), salt, PBKDF2_ITERATIONS, KEY_BYTES)
            }
        }

        private fun pbkdf2HmacSha256(
            passwordBytes: ByteArray,
            salt: ByteArray,
            iterations: Int,
            keyLen: Int
        ): ByteArray {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(passwordBytes, HMAC_ALGORITHM))
            val hashLen = mac.macLength
            val blockCount = (keyLen + hashLen - 1) / hashLen
            val output = ByteArray(blockCount * hashLen)
            val blockIndexBytes = ByteArray(4)
            for (block in 1..blockCount) {
                blockIndexBytes[0] = (block ushr 24).toByte()
                blockIndexBytes[1] = (block ushr 16).toByte()
                blockIndexBytes[2] = (block ushr 8).toByte()
                blockIndexBytes[3] = block.toByte()
                mac.update(salt)
                var u = mac.doFinal(blockIndexBytes)
                val t = u.copyOf()
                for (i in 1 until iterations) {
                    u = mac.doFinal(u)
                    for (j in t.indices) {
                        t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                    }
                }
                t.copyInto(output, (block - 1) * hashLen)
            }
            return output.copyOf(keyLen)
        }
    }
}
