package io.legado.app

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.update.GithubRelease
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {

    private val lastReleaseUrl =
        "https://api.github.com/repos/helyel233/legado-H/releases?per_page=10"

    @Test
    fun updateApp() {
        val body = okHttpClient.newCall(Request.Builder().url(lastReleaseUrl).build()).execute()
            .body.string()

        val releaseList = GSON.fromJsonArray<GithubRelease>(body)
            .getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
            }
            .filterNot { it.isPreRelease }
            .flatMap { it.gitReleaseToAppReleaseInfo() }
            .sortedByDescending { it.createdAt }

        assertTrue(releaseList.isNotEmpty())
        assertTrue(releaseList.all { it.downloadUrl.isNotBlank() })
        assertTrue(releaseList.all { it.versionName.isNotBlank() })
    }

}
