package io.legado.app.help.config

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感凭据的静态加密存储（AndroidKeyStore + AES-GCM）。
 *
 * ## 背景
 *
 * WebDav 密码、S3 密钥、AI Key 等此前以明文存于 SharedPreferences，
 * 设备遗失或越权读取（root / adb backup / 同签名应用）即可拿到全部凭据。
 * 备份时虽有二次加密，但设备本地一直是明文。
 *
 * ## 方案
 *
 * - 写入时用 AndroidKeyStore 里的密钥做 AES-GCM 加密，落盘格式为
 *   [PREFIX] + Base64(version | ivLen | iv | ciphertext)，每条独立 IV；
 * - 读取时带前缀则解密，**不带前缀按明文原样返回**——这同时承担两个职责：
 *   兼容历史明文数据，以及 KeyStore 异常（密钥丢失/硬件异常）时降级不丢功能；
 * - 解密失败（典型场景：换了设备恢复了备份密文）返回 null，按未设置处理，
 *   用户重新填写即恢复，绝不会把密文当明文用造成认证永远失败。
 *
 * 加密失败时保留明文写入（功能优先于保密），并记录日志。
 * API < 23 无 AndroidKeyStore AES-GCM，直接明文存取（与旧行为一致）。
 *
 * ## 为什么必须先读缓存再碰 KeyStore
 *
 * 每次解密都要 `KeyStore.load()` + `getKey(alias)` + `Cipher.init`，其中 getKey 是
 * 一次 keystore 守护进程 IPC（毫秒级），而 `webServiceToken` 会被每个 HTTP 请求
 * 读 2~3 次（口令校验 / 种 Cookie / 响应头），Web 首页几十个静态资源就等于上百次
 * IPC，且全部串行在同一把锁上。因此在 [lock] 内缓存解密结果与 SecretKey 实例。
 *
 * **注意**：绕过本对象直接 `putPrefString` 写这些键会让缓存变陈旧，敏感凭据只能
 * 通过 [write] / [remove] 修改。
 */
object SecurePrefs {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "legado.secure_prefs.v1"
    private const val PREFIX = "enc1:"
    private const val BLOB_VERSION: Byte = 1
    private const val IV_BYTES = 12

    private val lock = Any()

    /** 解密结果缓存（key → 明文）。只缓存成功结果，见 [read]。 */
    private val valueCache = HashMap<String, String?>()

    /** KeyStore 里的密钥实例，避免每次加解密都 load + getKey。仅在 [lock] 内访问。 */
    private var cachedKey: SecretKey? = null

    private val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /**
     * 读取凭据：密文解密，明文原样返回（兼容历史数据），解密失败返回 null。
     *
     * 解密失败**不进缓存**：KeyStore 偶发不可用时保留重试自愈的机会。
     */
    fun read(key: String): String? = synchronized(lock) {
        valueCache[key]?.let { return it }
        val stored = appCtx.getPrefString(key) ?: return null
        val value = when {
            !stored.startsWith(PREFIX) -> stored
            !supported -> return null
            else -> runCatching { decrypt(stored) }.getOrNull() ?: return null
        }
        valueCache[key] = value
        value
    }

    /**
     * SP 里是否存有该键（不尝试解密）。
     *
     * 用来区分「从未设置」与「设置了但本机解不开」（KeyStore 密钥丢失、恢复了
     * 其它设备的备份数据等）。后者绝不能当成「没有口令」，否则 Web 服务口令会
     * 静默失效变成无鉴权开放（fail-open）。
     */
    fun isStored(key: String): Boolean = !appCtx.getPrefString(key).isNullOrEmpty()

    /**
     * 写入凭据：null/空白视为删除；加密失败时降级为明文写入并记日志。
     */
    fun write(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            remove(key)
            return
        }
        if (!supported) {
            appCtx.putPrefString(key, value)
            synchronized(lock) { valueCache[key] = value }
            return
        }
        synchronized(lock) {
            val encrypted = runCatching { encrypt(value) }.getOrNull()
            if (encrypted != null) {
                appCtx.putPrefString(key, encrypted)
            } else {
                AppLog.put("SecurePrefs 加密 $key 失败，已按明文保存")
                appCtx.putPrefString(key, value)
            }
            // 刚写进去的就是明文，直接进缓存，省一次 KeyStore 解密
            valueCache[key] = value
        }
    }

    fun remove(key: String) {
        synchronized(lock) { valueCache.remove(key) }
        appCtx.removePref(key)
    }

    /**
     * 把历史明文凭据原地迁移为密文。重复调用安全（已是密文会跳过）。
     */
    fun migrateIfNeeded(vararg keys: String) {
        if (!supported) return
        for (key in keys) {
            runCatching {
                val stored = appCtx.getPrefString(key)
                if (!stored.isNullOrBlank() && !stored.startsWith(PREFIX)) {
                    write(key, stored)
                }
            }
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = requireNotNull(cipher.iv)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(2 + iv.size + encrypted.size)
        blob[0] = BLOB_VERSION
        blob[1] = iv.size.toByte()
        System.arraycopy(iv, 0, blob, 2, iv.size)
        System.arraycopy(encrypted, 0, blob, 2 + iv.size, encrypted.size)
        return PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    @Throws(Exception::class)
    private fun decrypt(encoded: String): String {
        val blob = Base64.decode(encoded.removePrefix(PREFIX), Base64.NO_WRAP)
        require(blob.size > 2 + IV_BYTES) { "Invalid encrypted blob" }
        require(blob[0] == BLOB_VERSION) { "Unsupported blob version" }
        val ivLength = blob[1].toInt() and 0xff
        require(ivLength == IV_BYTES && blob.size > 2 + ivLength) { "Invalid blob IV" }
        val iv = blob.copyOfRange(2, 2 + ivLength)
        val encrypted = blob.copyOfRange(2 + ivLength, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    /** 仅在 [lock] 内调用（KeyStore 实例与 [cachedKey] 都不保证线程安全）。 */
    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                generateKey()
            }
        cachedKey = key
        return key
    }

    /** 启动期一次性迁移的全部凭据 key。 */
    val migrateKeys = arrayOf(
        PreferKey.webDavPassword,
        PreferKey.s3SecretKey,
        PreferKey.s3SessionToken,
        PreferKey.aiTavilyApiKey,
        PreferKey.webServiceToken
    )
}
