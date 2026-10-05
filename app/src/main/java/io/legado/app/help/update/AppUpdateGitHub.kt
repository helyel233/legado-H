package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    private val checkVariant: AppVariant
        get() = AppVariant.OFFICIAL

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        val lastReleaseUrl = if (checkVariant.isBeta()) {
            "https://api.github.com/repos/helyel233/legado-H/releases?per_page=10"
        } else {
            "https://api.github.com/repos/helyel233/legado-H/releases?per_page=10"
        }
        val res = okHttpClient.newCallResponse {
            url(AppUpdateConfig.applyGithubProxy(lastReleaseUrl))
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        if (!checkVariant.isBeta()) {
            return GSON.fromJsonArray<GithubRelease>(body)
                .getOrElse {
                    throw NoStackTraceException("获取新版本出错" + it.localizedMessage)
                }
                .filterNot { it.isPreRelease }
                .flatMap { it.gitReleaseToAppReleaseInfo() }
                .sortedByDescending { it.createdAt }
        }
        return GSON.fromJsonObject<GithubRelease>(body)
            .getOrElse {
                throw NoStackTraceException("获取新版本出错" + it.localizedMessage)
            }
            .gitReleaseToAppReleaseInfo()
            .sortedByDescending { it.createdAt }
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            checkNow()
        }.timeout(10000)
    }

    suspend fun checkNow(): AppUpdate.UpdateInfo {
        val info = AppUpdate.resolveUpdateInfo(
            getLatestRelease()
                .filter { it.appVariant == checkVariant }
                .filter { it.supportsDeviceAbi() }
        ) ?: throw AppUpdate.latestVersionError()
        return info.copy(
            downloadUrl = AppUpdateConfig.applyGithubProxy(info.downloadUrl),
            downloadCandidates = info.downloadCandidates.map {
                it.copy(url = AppUpdateConfig.applyGithubProxy(it.url))
            }
        )
    }
}
