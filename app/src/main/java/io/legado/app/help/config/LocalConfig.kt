package io.legado.app.help.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.utils.getBoolean
import io.legado.app.utils.putBoolean
import io.legado.app.utils.putInt
import io.legado.app.utils.putLong
import io.legado.app.utils.putString
import io.legado.app.utils.remove
import splitties.init.appCtx

@Suppress("ConstPropertyName")
object LocalConfig : SharedPreferences
by appCtx.getSharedPreferences("local", Context.MODE_PRIVATE) {

    private const val versionCodeKey = "appVersionCode"

    /**
     * 本地密码,用来对需要备份的敏感信息加密,如 webdav 配置等
     */
    var password: String?
        get() = getString("password", null)
        set(value) {
            if (value != null) {
                putString("password", value)
            } else {
                remove("password")
            }
        }

    var lastBackup: Long
        get() = getLong("lastBackup", 0)
        set(value) {
            putLong("lastBackup", value)
        }

    /**
     * 上次记账时书架的**在线书身份键集合**（「书架变动时自动备份」的增删判据）。
     *
     * ⚠️ 必须放在 `LocalConfig`（独立的 "local" pref 文件），不能放进 `AppConfig`：
     * `AppConfig` 走 `defaultSharedPreferences`，会被 `Backup` 全量写进 config.xml
     * 并在恢复时带回本机——那会把本机的判据基线"校准"成另一台设备的状态，
     * 使自动备份的增删判定静默失准。这是本机运行态，不是用户配置。
     *
     * ⚠️ 写入时点必须在**备份成功之后**（见 `Backup.autoBackupOnShelfChangeIfNeeded`）。
     */
    var lastShelfKeys: String?
        get() = getString("lastShelfKeys", null)
        set(value) {
            if (value != null) {
                putString("lastShelfKeys", value)
            } else {
                remove("lastShelfKeys")
            }
        }

    /** 上次成功备份的资源源文件指纹（路径+大小+mtime），未变化时跳过打包与上传 */
    var lastAssetsFingerprint: String?
        get() = getString("lastAssetsFingerprint", null)
        set(value) {
            if (value != null) {
                putString("lastAssetsFingerprint", value)
            } else {
                remove("lastAssetsFingerprint")
            }
        }

    /** 上次资源包备份目标（云类型+文件名），与指纹配套判定是否可跳过 */
    var lastAssetsTarget: String?
        get() = getString("lastAssetsTarget", null)
        set(value) {
            if (value != null) {
                putString("lastAssetsTarget", value)
            } else {
                remove("lastAssetsTarget")
            }
        }

    var privacyPolicyOk: Boolean
        get() = getBoolean("privacyPolicyOk")
        set(value) {
            putBoolean("privacyPolicyOk", value)
        }

    val readHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readHelpVersion", "firstRead")

    val backupHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "backupHelpVersion", "firstBackup")

    val readMenuHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readMenuHelpVersion", "firstReadMenu")

    val bookSourcesHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "bookSourceHelpVersion", "firstOpenBookSources")

    val webDavBookHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "webDavBookHelpVersion", "firstOpenWebDavBook")

    val ruleHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "ruleHelpVersion")

    val needUpHttpTTS: Boolean
        get() = !isLastVersion(6, "httpTtsVersion")

    val needUpTxtTocRule: Boolean
        get() = !isLastVersion(3, "txtTocRuleVersion")

    val needUpRssSources: Boolean
        get() = !isLastVersion(6, "rssSourceVersion")

    val needUpDictRule: Boolean
        get() = !isLastVersion(2, "needUpDictRule")

    var versionCode
        get() = getLong(versionCodeKey, 0)
        set(value) {
            edit { putLong(versionCodeKey, value) }
        }
    var lastCheckUpdate: Long
        get() = getLong("lastCheckUpdate", 0)
        set(value) {
            putLong("lastCheckUpdate", value)
        }

    val isFirstOpenApp: Boolean
        get() {
            val value = getBoolean("firstOpen", true)
            if (value) {
                edit { putBoolean("firstOpen", false) }
            }
            return value
        }

    @Suppress("SameParameterValue")
    private fun isLastVersion(
        lastVersion: Int,
        versionKey: String,
        firstOpenKey: String? = null
    ): Boolean {
        var version = getInt(versionKey, 0)
        if (version == 0 && firstOpenKey != null) {
            if (!getBoolean(firstOpenKey, true)) {
                version = 1
            }
        }
        if (version < lastVersion) {
            edit { putInt(versionKey, lastVersion) }
            return false
        }
        return true
    }

    var bookInfoDeleteAlert: Boolean
        get() = getBoolean("bookInfoDeleteAlert", true)
        set(value) {
            putBoolean("bookInfoDeleteAlert", value)
        }

    var deleteBookOriginal: Boolean
        get() = getBoolean("deleteBookOriginal")
        set(value) {
            putBoolean("deleteBookOriginal", value)
        }

    /** Last interval used by the per-book scheduled update dialog. */
    var bookAutoTaskIntervalHours: Int
        get() = getInt("bookAutoTaskIntervalHours", 1)
        set(value) {
            putInt("bookAutoTaskIntervalHours", value)
        }

    var appCrash: Boolean
        get() = getBoolean("appCrash")
        set(value) {
            putBoolean("appCrash", value)
        }

}
