package io.legado.app.ui.book.read.epub

import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.epubcore.direct.EpubDirectChapter
import io.legado.app.model.localBook.epubcore.direct.EpubDirectLayoutMode
import io.legado.app.model.localBook.epubcore.direct.EpubDirectReaderChromePolicy
import io.legado.app.model.localBook.epubcore.direct.EpubDirectReaderTypographyPolicy
import io.legado.app.model.localBook.epubcore.direct.TextReaderImageClickPolicy
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeConfig
import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeData
import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeDataPolicy
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
import io.legado.app.model.localBook.epubcore.template.EpubTemplateDocument
import org.json.JSONObject
import splitties.init.appCtx

/**
 * Manages EPUB reader chrome payload resolution, template-field filtering,
 * and runtime/chrome JavaScript script construction.
 */
internal class EpubWebTemplateInjector {

    data class ReaderChromePayload(
        val config: EpubReaderChromeConfig,
        val data: EpubReaderChromeData
    )

    private var reviewedFieldTemplate: EpubReaderTemplate? = null
    private var reviewedFieldReference: EpubReaderTemplate? = null
    var chromeTemplate: EpubReaderChromeData = EpubReaderChromeData()
        private set
    private var chromeContentRevision: Long = 0L

    fun updateChromeData(
        activeChapter: EpubDirectChapter?,
        template: EpubReaderChromeData
    ): Boolean {
        val contentChanged = renderedChromeFields(activeChapter, chromeTemplate).copy(contentRevision = 0L) !=
            renderedChromeFields(activeChapter, template).copy(contentRevision = 0L)
        chromeTemplate = if (contentChanged) {
            template
        } else {
            template.copy(contentRevision = chromeTemplate.contentRevision)
        }
        chromeContentRevision = maxOf(
            chromeContentRevision,
            chromeTemplate.contentRevision
        )
        return contentChanged
    }

    fun reviewedFrameReference(template: EpubReaderTemplate): EpubReaderTemplate? {
        if (reviewedFieldTemplate !== template) {
            reviewedFieldTemplate = template
            reviewedFieldReference = EpubReaderTemplateStore.reviewedFrameTemplate(template)
        }
        return reviewedFieldReference
    }

    fun renderedChromeFields(
        prepared: EpubDirectChapter?,
        data: EpubReaderChromeData = chromeTemplate
    ): EpubReaderChromeData {
        val template = prepared?.readerTemplate ?: return data
        if (template.javascript.isBlank()) return data
        return EpubTemplateFieldPolicy.renderedFields(template, reviewedFrameReference(template), data)
    }

    fun resolveChromePayload(
        preparedChapter: EpubDirectChapter?,
        preparedConfig: EpubCoreLayoutConfig?,
        preparedIndex: Int,
        preparedCount: Int,
        previousData: EpubReaderChromeData?
    ): ReaderChromePayload {
        if (preparedChapter == null || preparedConfig == null) {
            return ReaderChromePayload(EpubReaderChromeConfig.DISABLED, chromeTemplate)
        }
        val resolvedConfig = EpubDirectReaderChromePolicy.resolve(
            requested = preparedConfig.readerChrome,
            input = EpubDirectReaderChromePolicy.Input(
                layoutMode = preparedChapter.layoutMode,
                fullPageArtwork = preparedChapter.fullPageArtwork,
                implicitSinglePage = preparedChapter.implicitSinglePage,
                duokanGallery = preparedChapter.duokanGallery,
                scripted = preparedChapter.scripted,
                scrollMode = preparedConfig.scrollMode
            ),
            pageHeightPx = preparedConfig.pageHeightPx
        )
        val unresolvedRevisionData = EpubReaderChromeDataPolicy.resolve(
            config = resolvedConfig,
            template = renderedChromeFields(preparedChapter, chromeTemplate).copy(contentRevision = 0L),
            page = EpubReaderChromeDataPolicy.Page(
                chapterIndex = preparedChapter.chapterIndex,
                chapterTitle = preparedChapter.title,
                pageIndex = preparedIndex,
                pageCount = preparedCount
            )
        )
        val data = if (previousData?.copy(contentRevision = 0L) == unresolvedRevisionData) {
            previousData
        } else {
            unresolvedRevisionData.copy(contentRevision = ++chromeContentRevision)
        }
        return ReaderChromePayload(resolvedConfig, data)
    }

    fun readerChromeApplyScript(token: Long, payload: ReaderChromePayload): String {
        return "(function(){var api=window.__legadoEpub;" +
            "if(!api||api.token!==$token)return false;" +
            "api.setReaderChrome(${readerChromeConfigJson(payload.config)});" +
            "api.setReaderChromeData(${readerChromeDataJson(payload.data)});" +
            "return true;})()"
    }

    fun commitPageAndReaderChromeScript(
        token: Long,
        safePageIndex: Int,
        behavior: String,
        payload: ReaderChromePayload,
        boundary: String?
    ): String {
        val pageCommand = boundary?.let {
            "api.setActivationPage(${JSONObject.quote(it)},$safePageIndex);"
        } ?: "api.setPage($safePageIndex,0,'$behavior');"
        return "(function(){var api=window.__legadoEpub;" +
            "if(!api||api.token!==$token)return null;" +
            "api.setReaderChrome(${readerChromeConfigJson(payload.config)});" +
            "if(api.commitPage){api.commitPage($safePageIndex,'$behavior'," +
            "${readerChromeDataJson(payload.data)},${boundary?.let(JSONObject::quote) ?: "null"});}" +
            "else{api.setReaderChromeData(${readerChromeDataJson(payload.data)});$pageCommand}" +
            "return JSON.stringify(api.metrics(true));})()"
    }

    fun runtimeInstallScript(
        token: Long,
        chapter: EpubDirectChapter,
        config: EpubCoreLayoutConfig,
        templateSecret: String,
        payload: ReaderChromePayload
    ): String {
        val runtime = if (chapter.readerTemplate != null) {
            EpubTemplateDocument.runtimeScript(
                token,
                chapter,
                config,
                templateSecret,
                readerChromeDataJson(payload.data),
                TEMPLATE_STABLE_TIMEOUT_MS
            )
        } else {
            runtimeScript(token, chapter, config)
        }
        return runtime +
            "\n;${readerChromeApplyScript(token, payload)};" +
            "\n;!!window.__legadoEpub;"
    }

    fun readerChromeConfigJson(config: EpubReaderChromeConfig): String {
        return JSONObject().apply {
            put("enabled", config.enabled)
            put("headerEnabled", config.headerEnabled)
            put("footerEnabled", config.footerEnabled)
            put("hideHeaderOnChapterFirstPage", config.hideHeaderOnChapterFirstPage)
            put("headerHeightPx", config.headerHeightPx)
            put("footerHeightPx", config.footerHeightPx)
            put("headerPaddingLeftPx", config.headerPaddingLeftPx)
            put("headerPaddingTopPx", config.headerPaddingTopPx)
            put("headerPaddingRightPx", config.headerPaddingRightPx)
            put("headerPaddingBottomPx", config.headerPaddingBottomPx)
            put("footerPaddingLeftPx", config.footerPaddingLeftPx)
            put("footerPaddingTopPx", config.footerPaddingTopPx)
            put("footerPaddingRightPx", config.footerPaddingRightPx)
            put("footerPaddingBottomPx", config.footerPaddingBottomPx)
            put("textSizePx", config.textSizePx.toDouble())
            put("textColor", config.textColor)
            put("dividerColor", config.dividerColor)
            put("headerDividerEnabled", config.headerDividerEnabled)
            put("footerDividerEnabled", config.footerDividerEnabled)
        }.toString()
    }

    fun readerChromeDataJson(data: EpubReaderChromeData): String {
        return JSONObject().apply {
            put("bookName", data.bookName)
            put("chapterTitle", data.chapterTitle)
            put("page", data.pageLabel)
            put("progress", data.progressLabel)
            put("chapterProgress", data.chapterProgressLabel)
            put("time", data.timeLabel)
            put("battery", data.batteryLabel)
            put("batteryPercentage", data.batteryPercentageLabel)
            put("chapterCount", data.chapterCount)
            put("headerLeft", data.headerLeft)
            put("headerCenter", data.headerCenter)
            put("headerRight", data.headerRight)
            put("footerLeft", data.footerLeft)
            put("footerCenter", data.footerCenter)
            put("footerRight", data.footerRight)
            put("chapterFirstPage", data.chapterFirstPage)
            put("contentRevision", data.contentRevision)
        }.toString()
    }

    companion object {
        internal const val BRIDGE_NAME = "LegadoEpubBridge"
        internal const val TEMPLATE_STABLE_TIMEOUT_MS = EpubRenderTimeoutPolicy.TEMPLATE_STARTUP_MS
        private const val LAYOUT_REFRESH_MIN_INTERVAL_MS = 80
        private const val LAYOUT_REFRESH_IMMEDIATE_MIN_INTERVAL_MS = 40
        private const val LAYOUT_REFRESH_MAX_INTERVAL_MS = 500
        private const val LAYOUT_REFRESH_COST_MULTIPLIER = 10

        private val RUNTIME_SCRIPT_TEMPLATE by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            appCtx.assets.open("epub/page-alignment.js").bufferedReader().use { it.readText() } + "\n" +
                appCtx.assets.open("epub/direct-runtime.js").bufferedReader().use { it.readText() }
        }

        internal fun isRtlLayout(chapter: EpubDirectChapter?): Boolean {
            return (chapter?.pageLayoutDirection ?: chapter?.pageProgressionDirection).equals("rtl", true)
        }

        fun runtimeScript(
            token: Long,
            chapter: EpubDirectChapter,
            config: EpubCoreLayoutConfig
        ): String {
            val vertical = config.scrollMode && !chapter.layoutMode.scalesPublisherViewport
            val fixed = chapter.layoutMode.singlePage
            val scaled = chapter.layoutMode.scalesPublisherViewport
            val publisherStyled = chapter.layoutMode == EpubDirectLayoutMode.PUBLISHER_STYLED
            val readerTypography = EpubDirectReaderTypographyPolicy.isSupported(
                EpubDirectReaderTypographyPolicy.Input(
                    layoutMode = chapter.layoutMode,
                    fullPageArtwork = chapter.fullPageArtwork,
                    implicitSinglePage = chapter.implicitSinglePage,
                    duokanGallery = chapter.duokanGallery,
                    scripted = chapter.scripted
                )
            )
            val rtl = isRtlLayout(chapter)
            val startId = chapter.startFragmentId?.let(JSONObject::quote) ?: "null"
            val endId = chapter.endFragmentId?.let(JSONObject::quote) ?: "null"
            val sourceWidth = chapter.viewportWidth ?: 0f
            val sourceHeight = chapter.viewportHeight ?: 0f
            val readerChrome = EpubDirectReaderChromePolicy.resolve(
                requested = config.readerChrome,
                input = EpubDirectReaderChromePolicy.Input(
                    layoutMode = chapter.layoutMode,
                    fullPageArtwork = chapter.fullPageArtwork,
                    implicitSinglePage = chapter.implicitSinglePage,
                    duokanGallery = chapter.duokanGallery,
                    scripted = chapter.scripted,
                    scrollMode = config.scrollMode
                ),
                pageHeightPx = config.pageHeightPx
            )
            val replacements = mapOf(
                "__LEGADO_TOKEN__" to token.toString(),
                "__LEGADO_TEXT_IMAGE_MODE__" to JSONObject.quote(TextReaderImageClickPolicy.mode(AppConfig.clickImgWay, chapter.sourceImages)),
                "__LEGADO_VERTICAL__" to vertical.toString(),
                "__LEGADO_FIXED__" to fixed.toString(),
                "__LEGADO_SCALED__" to scaled.toString(),
                "__LEGADO_PUBLISHER_STYLED__" to publisherStyled.toString(),
                "__LEGADO_READER_TYPOGRAPHY__" to readerTypography.toString(),
                "__LEGADO_BOTTOM_JUSTIFY__" to config.textBottomJustify.toString(),
                "__LEGADO_PUBLISHER_FULLSCREEN__" to chapter.publisherFullscreen.toString(),
                "__LEGADO_PADDING_LEFT__" to config.readerPaddingLeftPx.toString(),
                "__LEGADO_PADDING_TOP__" to config.readerPaddingTopPx.toString(),
                "__LEGADO_PADDING_RIGHT__" to config.readerPaddingRightPx.toString(),
                "__LEGADO_PADDING_BOTTOM__" to config.readerPaddingBottomPx.toString(),
                "__LEGADO_SAFE_INSET_LEFT__" to config.readerSafeInsetLeftPx.toString(),
                "__LEGADO_SAFE_INSET_TOP__" to config.readerSafeInsetTopPx.toString(),
                "__LEGADO_SAFE_INSET_RIGHT__" to config.readerSafeInsetRightPx.toString(),
                "__LEGADO_SAFE_INSET_BOTTOM__" to config.readerSafeInsetBottomPx.toString(),
                "__LEGADO_RTL__" to rtl.toString(),
                "__LEGADO_START_ID__" to startId,
                "__LEGADO_END_ID__" to endId,
                "__LEGADO_ANNOTATION_TITLE__" to JSONObject.quote(appCtx.getString(R.string.epub_annotation_title)),
                "__LEGADO_ANNOTATION_CLOSE__" to JSONObject.quote(appCtx.getString(R.string.epub_annotation_close)),
                "__LEGADO_ANNOTATION_BACK__" to JSONObject.quote(appCtx.getString(R.string.epub_annotation_back)),
                "__LEGADO_READER_FONT__" to (
                    !config.readerFontUrl.isNullOrBlank() &&
                        !config.readerFontPath.isNullOrBlank() &&
                        !config.readerFontRevision.isNullOrBlank()
                    ).toString(),
                "__LEGADO_CHROME_ENABLED__" to readerChrome.enabled.toString(),
                "__LEGADO_CHROME_HEADER_ENABLED__" to readerChrome.headerEnabled.toString(),
                "__LEGADO_CHROME_FOOTER_ENABLED__" to readerChrome.footerEnabled.toString(),
                "__LEGADO_CHROME_HIDE_HEADER_FIRST__" to
                    readerChrome.hideHeaderOnChapterFirstPage.toString(),
                "__LEGADO_CHROME_HEADER_HEIGHT__" to readerChrome.headerHeightPx.toString(),
                "__LEGADO_CHROME_FOOTER_HEIGHT__" to readerChrome.footerHeightPx.toString(),
                "__LEGADO_CHROME_HEADER_PADDING_LEFT__" to readerChrome.headerPaddingLeftPx.toString(),
                "__LEGADO_CHROME_HEADER_PADDING_TOP__" to readerChrome.headerPaddingTopPx.toString(),
                "__LEGADO_CHROME_HEADER_PADDING_RIGHT__" to readerChrome.headerPaddingRightPx.toString(),
                "__LEGADO_CHROME_HEADER_PADDING_BOTTOM__" to readerChrome.headerPaddingBottomPx.toString(),
                "__LEGADO_CHROME_FOOTER_PADDING_LEFT__" to readerChrome.footerPaddingLeftPx.toString(),
                "__LEGADO_CHROME_FOOTER_PADDING_TOP__" to readerChrome.footerPaddingTopPx.toString(),
                "__LEGADO_CHROME_FOOTER_PADDING_RIGHT__" to readerChrome.footerPaddingRightPx.toString(),
                "__LEGADO_CHROME_FOOTER_PADDING_BOTTOM__" to readerChrome.footerPaddingBottomPx.toString(),
                "__LEGADO_CHROME_TEXT_SIZE__" to readerChrome.textSizePx.toString(),
                "__LEGADO_CHROME_TEXT_COLOR__" to readerChrome.textColor.toString(),
                "__LEGADO_CHROME_DIVIDER_COLOR__" to readerChrome.dividerColor.toString(),
                "__LEGADO_CHROME_HEADER_DIVIDER__" to readerChrome.headerDividerEnabled.toString(),
                "__LEGADO_CHROME_FOOTER_DIVIDER__" to readerChrome.footerDividerEnabled.toString(),
                "__LEGADO_REFRESH_MIN__" to LAYOUT_REFRESH_MIN_INTERVAL_MS.toString(),
                "__LEGADO_REFRESH_IMMEDIATE__" to LAYOUT_REFRESH_IMMEDIATE_MIN_INTERVAL_MS.toString(),
                "__LEGADO_REFRESH_MAX__" to LAYOUT_REFRESH_MAX_INTERVAL_MS.toString(),
                "__LEGADO_REFRESH_COST_MULTIPLIER__" to LAYOUT_REFRESH_COST_MULTIPLIER.toString(),
                "__LEGADO_BRIDGE__" to BRIDGE_NAME,
                "__LEGADO_SOURCE_WIDTH__" to sourceWidth.toString(),
                "__LEGADO_SOURCE_HEIGHT__" to sourceHeight.toString()
            )
            return replacements.entries.fold(RUNTIME_SCRIPT_TEMPLATE) { script, (placeholder, value) ->
                script.replace(placeholder, value)
            }.also { script ->
                check(!script.contains("__LEGADO_")) {
                    "Unresolved EPUB runtime placeholder"
                }
            }
        }
    }
}
