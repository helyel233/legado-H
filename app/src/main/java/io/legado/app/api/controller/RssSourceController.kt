package io.legado.app.api.controller


import android.text.TextUtils
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.R
import splitties.init.appCtx

object RssSourceController {

    val sources: ReturnData
        get() {
            val source = appDb.rssSourceDao.all
            val returnData = ReturnData()
            return if (source.isEmpty()) {
                returnData.setErrorMsg(appCtx.getString(R.string.svc_source_list_empty))
            } else returnData.setData(source)
        }

    fun saveSource(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg(appCtx.getString(R.string.svc_data_cannot_be_empty))
        GSON.fromJsonObject<RssSource>(postData).onFailure {
            returnData.setErrorMsg(appCtx.getString(R.string.svc_source_conversion_failed_2, it.localizedMessage))
        }.onSuccess { source ->
            if (TextUtils.isEmpty(source.sourceName) || TextUtils.isEmpty(source.sourceUrl)) {
                returnData.setErrorMsg(appCtx.getString(R.string.svc_source_name_url_cannot_be))
            } else {
                appDb.rssSourceDao.insert(source)
                returnData.setData("")
            }
        }
        return returnData
    }

    fun saveSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg(appCtx.getString(R.string.svc_data_cannot_be_empty))
        val okSources = arrayListOf<RssSource>()
        val source = GSON.fromJsonArray<RssSource>(postData).getOrNull()
        if (source.isNullOrEmpty()) {
            return ReturnData().setErrorMsg(appCtx.getString(R.string.svc_source_conversion_failed))
        }
        for (rssSource in source) {
            if (rssSource.sourceName.isBlank() || rssSource.sourceUrl.isBlank()) {
                continue
            }
            appDb.rssSourceDao.insert(rssSource)
            okSources.add(rssSource)
        }
        return ReturnData().setData(okSources)
    }

    fun getSource(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()
        val returnData = ReturnData()
        if (url.isNullOrEmpty()) {
            return returnData.setErrorMsg(appCtx.getString(R.string.svc_url_parameter_required_please_provide_2))
        }
        val source = appDb.rssSourceDao.getByKey(url)
            ?: return returnData.setErrorMsg(appCtx.getString(R.string.svc_source_not_found_check_source))
        return returnData.setData(source)
    }

    fun deleteSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg(appCtx.getString(R.string.svc_no_data_provided))
        GSON.fromJsonArray<RssSource>(postData).onFailure {
            return ReturnData().setErrorMsg(appCtx.getString(R.string.wrong_format))
        }.onSuccess {
            SourceHelp.deleteRssSources(it)
        }
        return ReturnData().setData(appCtx.getString(R.string.svc_executed)/*okSources*/)
    }
}
