package io.legado.app.ui.config

import io.legado.app.help.http.dns.DnsScope
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.imageHttpClient as okHttpClient
import io.legado.app.model.BookCover
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSliderSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.FileOutputStream

class WelcomeConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.welcome_style

    private val requestWelcomeImage = 221
    private val requestWelcomeImageDark = 222
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestWelcomeImage -> setCoverFromUri(PreferKey.welcomeImage, uri)
                requestWelcomeImageDark -> setCoverFromUri(PreferKey.welcomeImageDark, uri)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val welcomeShowTime = intSetting(PreferKey.welcomeShowTime, 0)
        val safeWelcomeShowTime = welcomeShowTime.coerceIn(0, 800)
        if (welcomeShowTime != safeWelcomeShowTime) {
            updateIntSetting(PreferKey.welcomeShowTime, safeWelcomeShowTime)
        }
    }

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingSliderSpec(
                            key = PreferKey.welcomeShowTime,
                            title = str(R.string.welcome_show_time),
                            summary = str(R.string.welcome_show_time_summary),
                            value = intSetting(PreferKey.welcomeShowTime, 0).coerceIn(0, 800),
                            valueRange = 0..800,
                            onValueChange = {
                                updateIntSetting(PreferKey.welcomeShowTime, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.customWelcome,
                            title = str(R.string.custom_welcome),
                            summary = str(R.string.custom_welcome_summary),
                            checked = booleanSetting(PreferKey.customWelcome, false),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.customWelcome, it)
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.day),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.welcomeImage,
                            title = str(R.string.background_image),
                            summary = imageSummary(AppConfig.welcomeImage),
                            onClick = {
                                showImageActions(
                                    key = PreferKey.welcomeImage,
                                    requestCode = requestWelcomeImage
                                )
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowText,
                            title = str(R.string.show_welcome_text),
                            summary = str(R.string.welcome_text),
                            checked = booleanSetting(PreferKey.welcomeShowText, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowText, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowIcon,
                            title = str(R.string.show_icon),
                            summary = str(R.string.show_default_book_icon),
                            checked = booleanSetting(PreferKey.welcomeShowIcon, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowIcon, it)
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.night),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.welcomeImageDark,
                            title = str(R.string.background_image),
                            summary = imageSummary(AppConfig.welcomeImageDark),
                            onClick = {
                                showImageActions(
                                    key = PreferKey.welcomeImageDark,
                                    requestCode = requestWelcomeImageDark
                                )
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowTextDark,
                            title = str(R.string.show_welcome_text),
                            summary = str(R.string.welcome_text),
                            checked = booleanSetting(PreferKey.welcomeShowTextDark, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowTextDark, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowIconDark,
                            title = str(R.string.show_icon),
                            summary = str(R.string.show_default_book_icon),
                            checked = booleanSetting(PreferKey.welcomeShowIconDark, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowIconDark, it)
                            }
                        )
                    )
                )
            )
        )
    }

    private fun imageSummary(value: String?): String {
        return if (value.isNullOrBlank()) {
            str(R.string.select_image)
        } else {
            value
        }
    }

    private fun showImageActions(
        key: String,
        requestCode: Int
    ) {
        if (stringSetting(key, "").isBlank()) {
            launchImagePicker(requestCode)
            return
        }
        showComposeActionListDialog(
            title = str(R.string.select_image),
            labels = listOf(
                str(R.string.delete),
                str(R.string.select_image)
            ),
            dangerIndices = setOf(0),
            negativeText = str(R.string.cancel),
            onSelected = { index ->
                if (index == 0) {
                    removePref(key)
                    BookCover.upDefaultCover()
                } else {
                    launchImagePicker(requestCode)
                }
            }
        )
    }

    private fun launchImagePicker(requestCode: Int) {
        selectImage.launch {
            this.requestCode = requestCode
            mode = HandleFileContract.IMAGE
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        ctx.putPrefBoolean(PreferKey.customWelcome, true)
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi(R.string.welcome_toast_downloading)
                    val analyzeUrl = AnalyzeUrl(uri.toString(), dnsScope = DnsScope.IMAGE)
                    val url = analyzeUrl.urlNoQuery
                    var file = ctx.externalFiles
                    val res = okHttpClient.newCallResponse(0) {
                        addHeaders(analyzeUrl.headerMap)
                        url(url)
                    }
                    val contentType = res.header("Content-Type") ?: "image/jpeg"
                    val imageType = when {
                        contentType.contains("png", ignoreCase = true) -> "png"
                        contentType.contains("gif", ignoreCase = true) -> "gif"
                        contentType.contains("webp", ignoreCase = true) -> "webp"
                        else -> "jpg"
                    }
                    val suffix = if (url.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        ".$imageType"
                    }
                    val fileName = MD5Utils.md5Encode(url) + suffix
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    ctx.putPrefString(preferenceKey, file.absolutePath)
                }.onSuccess {
                    appCtx.toastOnUi(R.string.welcome_toast_applied)
                }.onFailure {
                    appCtx.toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = ctx.externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(ctx).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                ctx.putPrefString(preferenceKey, file.absolutePath)
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}
