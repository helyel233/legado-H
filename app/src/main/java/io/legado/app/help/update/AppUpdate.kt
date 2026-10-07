package io.legado.app.help.update

import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

object AppUpdate {

    val githubUpdate: AppUpdateInterface by lazy {
        AppUpdateGitHub
    }
    val preferredUpdate: AppUpdateInterface by lazy {
        PreferredAppUpdate
    }

    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String,
        val versionCode: Long = versionCodeFromFileName(fileName),
        val requestHeaders: Map<String, String> = emptyMap(),
        val downloadCandidates: List<DownloadCandidate> = emptyList()
    )

    /**
     * 同一 Release 中的可下载包：正式包与 debug 包。
     */
    data class DownloadCandidate(
        val url: String,
        val fileName: String,
        val isDebug: Boolean
    )

    /**
     * 从已过滤、按时间降序的资源列表中定位最新版本；
     * 同一 Release 内的正式包与 debug 包均作为下载候选，
     * 默认下载链接指向正式包（无正式包时退回首个资源）。
     */
    fun resolveUpdateInfo(assets: List<AppReleaseInfo>): UpdateInfo? {
        val newest = assets.firstOrNull {
            if (it.versionCode > 0L) {
                it.versionCode > AppConst.appInfo.versionCode
            } else {
                isComparableVersionName(it.versionName) &&
                    it.versionName > AppConst.appInfo.versionName
            }
        } ?: return null
        val sameRelease = assets.filter {
            it.versionCode == newest.versionCode && it.versionName == newest.versionName
        }
        val official = sameRelease.firstOrNull {
            !it.name.contains("debug", ignoreCase = true)
        }
        val debug = sameRelease.firstOrNull {
            it.name.contains("debug", ignoreCase = true)
        }
        val preferred = official ?: newest
        val candidates = buildList {
            official?.let { add(DownloadCandidate(it.downloadUrl, it.name, isDebug = false)) }
            debug?.let { add(DownloadCandidate(it.downloadUrl, it.name, isDebug = true)) }
        }
        return UpdateInfo(
            tagName = preferred.versionName,
            updateLog = preferred.note,
            downloadUrl = preferred.downloadUrl,
            fileName = preferred.name,
            versionCode = preferred.versionCode,
            downloadCandidates = candidates
        )
    }

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

    fun isLatestVersionError(error: Throwable): Boolean {
        val message = error.message ?: return false
        return error is NoStackTraceException && message.contains("最新版本")
    }

    fun latestVersionError(): NoStackTraceException {
        return NoStackTraceException("已是最新版本")
    }

    /**
     * 从 APK 文件名解析 versionName 与 versionCode。
     * 兼容以下命名：
     * legado_app_3.26.10021031_29848471.apk
     * legado_app_3.26.10030000_29849280_LegadoH_arm64.apk
     */
    private val versionPairRegex = Regex("""(\d+(?:\.\d+)+)_(\d+)""")

    fun versionInfoFromFileName(fileName: String): Pair<String, Long>? {
        return versionPairRegex.find(fileName)?.let {
            it.groupValues[1] to (it.groupValues[2].toLongOrNull() ?: 0L)
        }
    }

    fun versionCodeFromFileName(fileName: String): Long {
        return versionInfoFromFileName(fileName)?.second ?: 0L
    }

    /**
     * versionCode 缺失时按 versionName 字符串比较，
     * 仅当其形如版本号（以数字开头）时才参与比较，
     * 避免把 ABI 等尾段误当作版本号造成误报更新。
     */
    fun isComparableVersionName(versionName: String): Boolean {
        return versionName.isNotEmpty() && versionName.first().isDigit()
    }

    fun isNewerThanCurrent(updateInfo: UpdateInfo): Boolean {
        return if (updateInfo.versionCode > 0L) {
            updateInfo.versionCode > AppConst.appInfo.versionCode
        } else {
            updateInfo.tagName > AppConst.appInfo.versionName
        }
    }

    private fun compareVersion(left: UpdateInfo, right: UpdateInfo): Int {
        return if (left.versionCode > 0L && right.versionCode > 0L) {
            left.versionCode.compareTo(right.versionCode)
        } else {
            left.tagName.compareTo(right.tagName)
        }
    }

    private object PreferredAppUpdate : AppUpdateInterface {
        override fun check(scope: CoroutineScope): Coroutine<UpdateInfo> {
            return Coroutine.async(scope) {
                checkNow()
            }.timeout(15000)
        }

        private suspend fun checkNow(): UpdateInfo = coroutineScope {
            val officialDeferred = async {
                withTimeoutOrNull(8_000) {
                    runCatching { checkOfficialNow() }
                } ?: Result.failure(NoStackTraceException("正式版更新检查超时"))
            }
            if (!AppUpdateConfig.internalBetaConfigured) {
                return@coroutineScope officialDeferred.await().getOrThrow()
            }

            val internalBetaDeferred = async {
                withTimeoutOrNull(8_000) {
                    runCatching { AppUpdateInternal.checkNow() }
                } ?: Result.failure(NoStackTraceException("内测版更新检查超时"))
            }

            val internalBetaResult = internalBetaDeferred.await()
            val officialResult = officialDeferred.await()
            val official = officialResult.getOrNull()
            val internalBeta = internalBetaResult.getOrNull()

            if (official != null && internalBeta != null) {
                return@coroutineScope if (compareVersion(official, internalBeta) >= 0) {
                    official
                } else {
                    internalBeta
                }
            }
            official?.let { return@coroutineScope it }
            internalBeta?.let { return@coroutineScope it }

            val officialError = officialResult.exceptionOrNull()
            val internalBetaError = internalBetaResult.exceptionOrNull()
            if (officialError != null && !isLatestVersionError(officialError)) {
                throw officialError
            }
            if (internalBetaError != null && !isLatestVersionError(internalBetaError)) {
                throw internalBetaError
            }
            throw officialError ?: internalBetaError ?: latestVersionError()
        }

        private suspend fun checkOfficialNow(): UpdateInfo {
            return AppUpdateGitHub.checkNow()
        }
    }

}
