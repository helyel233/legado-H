package io.legado.app.help.config

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.utils.GSON
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.isNightMode
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.removePref
import io.legado.app.utils.sysConfiguration
import io.legado.app.utils.toastOnUi
import io.legado.app.ui.main.ai.AiAgentMode
import io.legado.app.ui.main.ai.AiChatCompanionConfig
import io.legado.app.ui.main.ai.AiChatSession
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.main.ai.AiPersonaConfig
import io.legado.app.ui.main.ai.AiMcpServerConfig
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.main.ai.AiSkillConfig
import io.legado.app.ui.main.ai.AiWorldBookConfig
import io.legado.app.ui.book.read.ReadAiBookHistory
import io.legado.app.help.review.ReviewOpenModePolicy
import splitties.init.appCtx
import io.legado.app.R

@Suppress("MemberVisibilityCanBePrivate", "ConstPropertyName")
object AppConfig : SharedPreferences.OnSharedPreferenceChangeListener {

    /** 评论打开方式取值 */
    object ReviewOpenMode {
        /** 联网优先：直接联网加载，失败/超时自动用缓存兜底 */
        const val NETWORK = "network"

        /** 缓存优先：命中缓存立即秒开，后台静默 SWR 刷新 */
        const val SNAPSHOT_FIRST = "snapshot_first"

        /** 纯快照：仅使用本地快照，未命中时严格零联网并提示 */
        const val OFFLINE = "offline"
    }


    /** 段评快照默认保鲜期（天），见 [reviewSnapshotTtlDays] */
    const val DEFAULT_REVIEW_SNAPSHOT_TTL_DAYS = 3

    /** 评论头像/图片压缩阈值下限（KiB），0 或负数会让压缩失去意义。 */
    const val MIN_REVIEW_COMPRESSION_KB = 1
    const val DEFAULT_REVIEW_AVATAR_COMPRESSION_KB = 2
    const val DEFAULT_REVIEW_IMAGE_COMPRESSION_KB = 50

    const val DISCOVERY_PAGE_MODE_LEGACY = "legacy"
    const val DISCOVERY_PAGE_MODE_MODERN = "modern"
    const val DISCOVERY_PAGE_MODE_SUITE = "suite"
    const val DEFAULT_FAST_SCROLLER_TOUCH_TARGET_DP = 44
    const val MIN_FAST_SCROLLER_TOUCH_TARGET_DP = 32
    const val MAX_FAST_SCROLLER_TOUCH_TARGET_DP = 60


    val isCronet = appCtx.getPrefBoolean(PreferKey.cronet)
    var useAntiAlias = appCtx.getPrefBoolean(PreferKey.antiAlias)
    var useHighRefreshRate = appCtx.getPrefBoolean(PreferKey.highBrush, true)
    var userAgent: String = getPrefUserAgent()
    var customHosts = appCtx.getPrefString(PreferKey.customHosts)
    var editTheme = appCtx.getPrefInt(PreferKey.editTheme, 0)
    var editThemeDark = appCtx.getPrefInt(PreferKey.editThemeDark, 0)
    var editThemeAuto = appCtx.getPrefBoolean(PreferKey.editThemeAuto)
    private const val KEY_LAST_NON_EINK_THEME_MODE = "lastNonEInkThemeMode"
    private val NON_EINK_THEME_MODES = setOf("0", "1", "2")

    private var lastNonEInkThemeMode: String = (
        appCtx.getPrefString(PreferKey.themeMode, "0")?.takeIf { it in NON_EINK_THEME_MODES }
            ?: appCtx.getPrefString(KEY_LAST_NON_EINK_THEME_MODE, "0")?.takeIf { it in NON_EINK_THEME_MODES }
            ?: "0"
        )

    var isEInkMode = appCtx.getPrefBoolean(PreferKey.eInkMode, false) ||
        appCtx.getPrefString(PreferKey.themeMode) == "3"

    fun resolveDisplayThemeMode(
        rawThemeMode: String? = appCtx.getPrefString(PreferKey.themeMode, "0"),
        eInkPrefEnabled: Boolean = appCtx.getPrefBoolean(PreferKey.eInkMode, false)
    ): String {
        return if (eInkPrefEnabled || rawThemeMode == "3") "3" else (rawThemeMode ?: "0")
    }

    fun setThemeModeSelection(mode: String) {
        val currentMode = appCtx.getPrefString(PreferKey.themeMode, "0")
        if (mode == "3") {
            if (currentMode in NON_EINK_THEME_MODES) {
                lastNonEInkThemeMode = currentMode!!
            }
            themeMode = "3"
            isEInkMode = true
            appCtx.defaultSharedPreferences.edit {
                if (currentMode in NON_EINK_THEME_MODES) {
                    putString(KEY_LAST_NON_EINK_THEME_MODE, currentMode)
                }
                putString(PreferKey.themeMode, "3")
                putBoolean(PreferKey.eInkMode, true)
            }
        } else {
            val normalizedMode = mode.takeIf { it in NON_EINK_THEME_MODES } ?: "0"
            lastNonEInkThemeMode = normalizedMode
            themeMode = normalizedMode
            isEInkMode = false
            appCtx.defaultSharedPreferences.edit {
                putString(KEY_LAST_NON_EINK_THEME_MODE, normalizedMode)
                putString(PreferKey.themeMode, normalizedMode)
                putBoolean(PreferKey.eInkMode, false)
            }
        }
    }

    fun setEInkModeEnabled(enabled: Boolean) {
        val currentMode = appCtx.getPrefString(PreferKey.themeMode, "0")
        if (currentMode in NON_EINK_THEME_MODES) {
            lastNonEInkThemeMode = currentMode!!
        }
        val targetRestoredMode = if (!enabled && currentMode == "3") {
            appCtx.getPrefString(KEY_LAST_NON_EINK_THEME_MODE, lastNonEInkThemeMode)
                ?.takeIf { it in NON_EINK_THEME_MODES }
                ?: "0"
        } else {
            null
        }
        isEInkMode = enabled
        if (targetRestoredMode != null) {
            themeMode = targetRestoredMode
        }
        // 单次 SharedPreferences.Editor 提交保证内存态在首次监听回调前即已同步，
        // 并持久化 lastNonEInkThemeMode，跨进程重启也能恢复进墨水屏前的日/夜偏好。
        appCtx.defaultSharedPreferences.edit {
            if (currentMode in NON_EINK_THEME_MODES) {
                putString(KEY_LAST_NON_EINK_THEME_MODE, currentMode)
            }
            if (targetRestoredMode != null) {
                putString(PreferKey.themeMode, targetRestoredMode)
            }
            putBoolean(PreferKey.eInkMode, enabled)
        }
    }
    var clickActionTL = appCtx.getPrefInt(PreferKey.clickActionTL, 2)
    var clickActionTC = appCtx.getPrefInt(PreferKey.clickActionTC, 2)
    var clickActionTR = appCtx.getPrefInt(PreferKey.clickActionTR, 1)
    var clickActionML = appCtx.getPrefInt(PreferKey.clickActionML, 2)
    var clickActionMC = appCtx.getPrefInt(PreferKey.clickActionMC, 0)
    var clickActionMR = appCtx.getPrefInt(PreferKey.clickActionMR, 1)
    var clickActionBL = appCtx.getPrefInt(PreferKey.clickActionBL, 2)
    var clickActionBC = appCtx.getPrefInt(PreferKey.clickActionBC, 1)
    var clickActionBR = appCtx.getPrefInt(PreferKey.clickActionBR, 1)
    var themeMode = appCtx.getPrefString(PreferKey.themeMode, "0")
    var useDefaultCover = appCtx.getPrefBoolean(PreferKey.useDefaultCover, false)
    var loadCoverHighQuality = appCtx.getPrefBoolean(PreferKey.loadCoverHighQuality, false)

    /** 订阅源图片样式正文提取不到图片时,直接加载原网页由站点JS渲染图片 */
    var rssNoImgIframe: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.rssNoImgIframe, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.rssNoImgIframe, value)
        }

    val immersiveManageBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.immersiveManageBar, true)
    var optimizeRender = CanvasRecorderFactory.isSupport
            && appCtx.getPrefBoolean(PreferKey.optimizeRender, true)
    var pageTurnPrewarm = appCtx.getPrefBoolean(PreferKey.pageTurnPrewarm, true)
    var recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)
    var editFontScale = appCtx.getPrefInt(PreferKey.editFontScale, 16)
    var editNonPrintable = appCtx.getPrefInt(PreferKey.editNonPrintable, 0)
    var editAutoWrap = appCtx.getPrefBoolean(PreferKey.editAutoWrap, true)
    var editAutoComplete = appCtx.getPrefBoolean(PreferKey.editAutoComplete, true)
    var showBoardLine = appCtx.getPrefInt(PreferKey.showBoardLine, 1)
    var adaptSpecialStyle = appCtx.getPrefBoolean(PreferKey.adaptSpecialStyle, true)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.editFontScale -> editFontScale = appCtx.getPrefInt(PreferKey.editFontScale, 16)
            PreferKey.editNonPrintable -> editNonPrintable = appCtx.getPrefInt(PreferKey.editNonPrintable, 0)
            PreferKey.editAutoWrap -> editAutoWrap = appCtx.getPrefBoolean(PreferKey.editAutoWrap, true)
            PreferKey.editAutoComplete -> editAutoComplete = appCtx.getPrefBoolean(PreferKey.editAutoComplete, true)
            PreferKey.showBoardLine -> showBoardLine = appCtx.getPrefInt(PreferKey.showBoardLine, 1)
            PreferKey.adaptSpecialStyle -> adaptSpecialStyle = appCtx.getPrefBoolean(PreferKey.adaptSpecialStyle, true)

            PreferKey.eInkMode -> {
                isEInkMode = appCtx.getPrefBoolean(PreferKey.eInkMode, false) ||
                    appCtx.getPrefString(PreferKey.themeMode) == "3"
            }

            PreferKey.themeMode -> {
                val nextMode = appCtx.getPrefString(PreferKey.themeMode, "0")
                if (nextMode in NON_EINK_THEME_MODES) {
                    lastNonEInkThemeMode = nextMode!!
                    appCtx.defaultSharedPreferences.edit {
                        putString(KEY_LAST_NON_EINK_THEME_MODE, nextMode)
                    }
                }
                themeMode = nextMode
                isEInkMode = appCtx.getPrefBoolean(PreferKey.eInkMode, false) || themeMode == "3"
                val expectedNight = isNightTheme
                if (appCtx.getPrefBoolean(PreferKey.useLegacyPackageManager, false)) {
                    Coroutine.async(Coroutine.defaultScope) {
                        runCatching {
                            // 调用方在写入 themeMode 后会立刻同步 applyDayNight 重建界面，
                            // 套件投影异步完成时界面可能已用旧偏好重建，需补发刷新事件；
                            // 投影未改变本模式主题时返回 false，此时无需（也不能）再重建。
                            if (AppearanceKitManager.applyCurrentModeTheme(appCtx, expectedNight)) {
                                postEvent(EventBus.MAIN_THEME_BACKGROUND_CHANGED, expectedNight)
                                postEvent(EventBus.RECREATE, "")
                            }
                        }.onFailure {
                            AppLog.put("apply current appearance kit theme failed\n${it.localizedMessage}", it)
                        }
                    }
                }
            }

            PreferKey.clickActionTL -> clickActionTL =
                appCtx.getPrefInt(PreferKey.clickActionTL, 2)

            PreferKey.clickActionTC -> clickActionTC =
                appCtx.getPrefInt(PreferKey.clickActionTC, 2)

            PreferKey.clickActionTR -> clickActionTR =
                appCtx.getPrefInt(PreferKey.clickActionTR, 1)

            PreferKey.clickActionML -> clickActionML =
                appCtx.getPrefInt(PreferKey.clickActionML, 2)

            PreferKey.clickActionMC -> clickActionMC =
                appCtx.getPrefInt(PreferKey.clickActionMC, 0)

            PreferKey.clickActionMR -> clickActionMR =
                appCtx.getPrefInt(PreferKey.clickActionMR, 1)

            PreferKey.clickActionBL -> clickActionBL =
                appCtx.getPrefInt(PreferKey.clickActionBL, 2)

            PreferKey.clickActionBC -> clickActionBC =
                appCtx.getPrefInt(PreferKey.clickActionBC, 1)

            PreferKey.clickActionBR -> clickActionBR =
                appCtx.getPrefInt(PreferKey.clickActionBR, 1)

            PreferKey.readBodyToLh -> ReadBookConfig.readBodyToLh =
                appCtx.getPrefBoolean(PreferKey.readBodyToLh, true)

            PreferKey.useZhLayout -> ReadBookConfig.useZhLayout =
                appCtx.getPrefBoolean(PreferKey.useZhLayout)

            PreferKey.userAgent -> userAgent = getPrefUserAgent()

            PreferKey.customHosts -> {
                customHosts = appCtx.getPrefString(PreferKey.customHosts)
                _hostMap = null
            }

            PreferKey.editTheme -> editTheme = appCtx.getPrefInt(PreferKey.editTheme, 0)

            PreferKey.editThemeDark -> editThemeDark = appCtx.getPrefInt(PreferKey.editThemeDark, 0)

            PreferKey.editThemeAuto -> editThemeAuto = appCtx.getPrefBoolean(PreferKey.editThemeAuto)

            PreferKey.antiAlias -> useAntiAlias = appCtx.getPrefBoolean(PreferKey.antiAlias)

            PreferKey.highBrush -> {
                useHighRefreshRate = appCtx.getPrefBoolean(PreferKey.highBrush, true)
            }

            PreferKey.useDefaultCover -> useDefaultCover =
                appCtx.getPrefBoolean(PreferKey.useDefaultCover, false)

            PreferKey.loadCoverHighQuality -> loadCoverHighQuality =
                appCtx.getPrefBoolean(PreferKey.loadCoverHighQuality, false)

            PreferKey.optimizeRender -> optimizeRender = CanvasRecorderFactory.isSupport
                    && appCtx.getPrefBoolean(PreferKey.optimizeRender, true)

            PreferKey.pageTurnPrewarm -> pageTurnPrewarm =
                appCtx.getPrefBoolean(PreferKey.pageTurnPrewarm, true)

            PreferKey.recordLog -> recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)

        }
    }

    fun disableOptimizeRender(error: Throwable) {
        if (!optimizeRender) return
        optimizeRender = false
        runCatching {
            appCtx.putPrefBoolean(PreferKey.optimizeRender, false)
        }
        runCatching {
            AppLog.put("正文优化绘制失败，已回退为直接绘制\n${error.localizedMessage}", error)
        }
    }

    fun disablePageTurnPrewarm(error: Throwable) {
        if (!pageTurnPrewarm) return
        pageTurnPrewarm = false
        runCatching {
            appCtx.putPrefBoolean(PreferKey.pageTurnPrewarm, false)
        }
        runCatching {
            AppLog.put("翻页离屏预热失败，已自动关闭预热\n${error.localizedMessage}", error)
        }
    }

    //dns配置
    private var _hostMap: Map<String, Any?>? = null
    val hostMap: Map<String, Any?>
        get() = _hostMap ?: run {
            val cache = GSON.fromJsonObject<Map<String, Any?>>(customHosts).getOrNull() ?: emptyMap()
            _hostMap = cache
            cache
        }

    var isNightTheme: Boolean
        get() = when (themeMode) {
            "1" -> false
            "2" -> true
            "3" -> false
            else -> sysConfiguration.isNightMode
        }
        set(value) {
            if (isNightTheme != value) {
                if (value) {
                    appCtx.putPrefString(PreferKey.themeMode, "2")
                } else {
                    appCtx.putPrefString(PreferKey.themeMode, "1")
                }
            }
        }
    var showBookname: Int
        get() = appCtx.getPrefInt(PreferKey.showBooknameLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.showBooknameLayout, value)
        }
    var bookshelfMargin: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfMargin, 12)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfMargin, value)
        }

    var bookshelfListItemStyle: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfListItemStyle, 0).let {
            if (it > 1) 1 else it.coerceAtLeast(0)
        }
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfListItemStyle, value.coerceIn(0, 1))
        }

    var bookshelfListIntroLines: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfListIntroLines, 2).coerceIn(0, 3)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfListIntroLines, value.coerceIn(0, 3))
        }

    var bookshelfReturnToTopAfterRead: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.bookshelfReturnToTopAfterRead, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.bookshelfReturnToTopAfterRead, value)
        }

    var forceSoftwareParagraphBubble: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.forceSoftwareParagraphBubble, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.forceSoftwareParagraphBubble, value)
        }

    var showUnread: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showUnread, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showUnread, value)
        }

    /** 书架书籍行显示阅读进度百分比，默认关：默认观感不变。 */
    var showReadProgress: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showReadProgress, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showReadProgress, value)
        }

    var showLastUpdateTime: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showLastUpdateTime, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showLastUpdateTime, value)
        }

    var showWaitUpCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showWaitUpCount, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showWaitUpCount, value)
        }

    var readBrightness: Int
        get() = if (isNightTheme) {
            appCtx.getPrefInt(PreferKey.nightBrightness, 100)
        } else {
            appCtx.getPrefInt(PreferKey.brightness, 100)
        }
        set(value) {
            if (isNightTheme) {
                appCtx.putPrefInt(PreferKey.nightBrightness, value)
            } else {
                appCtx.putPrefInt(PreferKey.brightness, value)
            }
        }

    val textSelectAble: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.textSelectAble, true)

    /**
     * 全局「覆盖状态栏」：开启后页面内容（壁纸 / 顶栏）延伸到手机状态栏，
     * 状态栏透明；关闭（默认）时内容避开状态栏、状态栏为不透明主题色，
     * 顶栏边框也就不会包住状态栏。
     *
     * 这是控制「app 是否覆盖状态栏」的唯一全局开关，由 BaseActivity 在窗口层统一落地。
     * 与 `isMainTransparentStatusBar`（仅主界面的沉浸开关，语义为隐藏系统条）相互独立：
     * 前者决定内容是否铺进状态栏，后者决定系统条是否显示。
     */
    val overlapStatusBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.overlapStatusBar, false)

    /** 阅读页等场景沿用的旧语义：状态栏是否透明取色。跟随全局「覆盖状态栏」开关。 */
    val isTransparentStatusBar: Boolean
        get() = overlapStatusBar

    val isMainTransparentStatusBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.mainTransparentStatusBar, false)

    val immNavigationBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.immNavigationBar, true)

    val screenOrientation: String?
        get() = appCtx.getPrefString(PreferKey.screenOrientation)

    var bookGroupStyle: Int
        get() = appCtx.getPrefInt(PreferKey.bookGroupStyle, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookGroupStyle, value)
        }

    var bookshelfLayout: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfLayout, value)
        }

    var rssSourceLayout: Int
        get() = appCtx.getPrefInt(PreferKey.rssSourceLayout, 1).let { if (it in 1..3) it else 1 }
        set(value) {
            appCtx.putPrefInt(PreferKey.rssSourceLayout, if (value in 1..3) value else 1)
        }

    var saveTabPosition: Int
        get() = appCtx.getPrefInt(PreferKey.saveTabPosition, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.saveTabPosition, value)
        }

    var bookExportFileName: String?
        get() = appCtx.getPrefString(PreferKey.bookExportFileName)
        set(value) {
            appCtx.putPrefString(PreferKey.bookExportFileName, value)
        }

    // 保存 自定义导出章节模式 文件名js表达式
    var episodeExportFileName: String?
        get() = appCtx.getPrefString(PreferKey.episodeExportFileName, "")
        set(value) {
            appCtx.putPrefString(PreferKey.episodeExportFileName, value)
        }

    var bookImportFileName: String?
        get() = appCtx.getPrefString(PreferKey.bookImportFileName)
        set(value) {
            appCtx.putPrefString(PreferKey.bookImportFileName, value)
        }

    var backupPath: String?
        get() = appCtx.getPrefString(PreferKey.backupPath)
        set(value) {
            if (value.isNullOrEmpty()) {
                appCtx.removePref(PreferKey.backupPath)
            } else {
                appCtx.putPrefString(PreferKey.backupPath, value)
            }
        }

    // 书籍保存位置
    var defaultBookTreeUri: String?
        get() = appCtx.getPrefString(PreferKey.defaultBookTreeUri)
        set(value) {
            if (value.isNullOrEmpty()) {
                appCtx.removePref(PreferKey.defaultBookTreeUri)
            } else {
                appCtx.putPrefString(PreferKey.defaultBookTreeUri, value)
            }
        }

    val showDiscovery: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showDiscovery, true)

    val modernDiscoveryPage: Boolean
        get() = discoveryPageMode != DISCOVERY_PAGE_MODE_LEGACY

    var discoveryPageMode: String
        get() {
            val stored = appCtx.getPrefString(PreferKey.discoveryPageMode)
            return when (stored) {
                DISCOVERY_PAGE_MODE_LEGACY,
                DISCOVERY_PAGE_MODE_MODERN,
                DISCOVERY_PAGE_MODE_SUITE -> stored
                else -> if (appCtx.getPrefBoolean(PreferKey.modernDiscoveryPage, true)) {
                    DISCOVERY_PAGE_MODE_MODERN
                } else {
                    DISCOVERY_PAGE_MODE_LEGACY
                }
            }
        }
        set(value) {
            val normalized = when (value) {
                DISCOVERY_PAGE_MODE_LEGACY,
                DISCOVERY_PAGE_MODE_MODERN,
                DISCOVERY_PAGE_MODE_SUITE -> value
                else -> DISCOVERY_PAGE_MODE_MODERN
            }
            appCtx.putPrefString(PreferKey.discoveryPageMode, normalized)
            appCtx.putPrefBoolean(
                PreferKey.modernDiscoveryPage,
                normalized != DISCOVERY_PAGE_MODE_LEGACY
            )
        }

    val modernRssPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.modernRssPage, true)

    var discoveryPageLayout: Int
        get() = appCtx.getPrefInt(PreferKey.discoveryPageLayout, 1).coerceIn(1, 3)
        set(value) {
            appCtx.putPrefInt(PreferKey.discoveryPageLayout, value.coerceIn(1, 3))
        }

    val mergeDiscoveryRss: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.mergeDiscoveryRss, false)

    var mergedDiscoveryRssTarget: String
        get() = appCtx.getPrefString(PreferKey.mergedDiscoveryRssTarget, "explore") ?: "explore"
        set(value) = appCtx.putPrefString(PreferKey.mergedDiscoveryRssTarget, value)

    var modernDiscoverySourceUrl: String?
        get() = appCtx.getPrefString(PreferKey.modernDiscoverySourceUrl)
        set(value) {
            if (value.isNullOrBlank()) {
                appCtx.removePref(PreferKey.modernDiscoverySourceUrl)
            } else {
                appCtx.putPrefString(PreferKey.modernDiscoverySourceUrl, value)
            }
        }

    fun modernDiscoveryTagUrl(sourceUrl: String?): String? {
        val sourceKey = sourceUrl?.takeIf { it.isNotBlank() } ?: return null
        return modernDiscoveryTagUrlMap()[sourceKey]?.takeIf { it.isNotBlank() }
    }

    fun rememberModernDiscoveryTagUrl(sourceUrl: String?, tagUrl: String?) {
        val sourceKey = sourceUrl?.takeIf { it.isNotBlank() } ?: return
        val current = modernDiscoveryTagUrlMap().toMutableMap()
        val normalizedTag = tagUrl?.takeIf { it.isNotBlank() }
        if (normalizedTag == null) {
            current.remove(sourceKey)
        } else {
            current[sourceKey] = normalizedTag
        }
        if (current.isEmpty()) {
            appCtx.removePref(PreferKey.modernDiscoveryTagUrls)
        } else {
            appCtx.putPrefString(PreferKey.modernDiscoveryTagUrls, GSON.toJson(current))
        }
    }

    private fun modernDiscoveryTagUrlMap(): Map<String, String> {
        return GSON.fromJsonObject<Map<String, String>>(
            appCtx.getPrefString(PreferKey.modernDiscoveryTagUrls)
        ).getOrDefault(emptyMap())
            .filterKeys { it.isNotBlank() }
            .filterValues { it.isNotBlank() }
    }

    var modernRssSourceUrl: String?
        get() = appCtx.getPrefString(PreferKey.modernRssSourceUrl)
        set(value) {
            if (value.isNullOrBlank()) {
                appCtx.removePref(PreferKey.modernRssSourceUrl)
            } else {
                appCtx.putPrefString(PreferKey.modernRssSourceUrl, value)
            }
        }

    val showReadRecord: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showReadRecord, true)

    var bookshelfHiddenTags: Map<Long, Set<String>>
        get() {
            val rawMap = GSON.fromJsonObject<Map<String, List<String>>>(
                appCtx.getPrefString(PreferKey.bookshelfHiddenTags)
            ).getOrDefault(emptyMap())
            return rawMap.mapNotNull { (key, value) ->
                key.toLongOrNull()?.let { groupId ->
                    groupId to value.map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toSet()
                }
            }.toMap()
        }
        set(value) {
            val normalized = value
                .mapValues { (_, tags) -> tags.map { it.trim() }.filter { it.isNotBlank() }.distinct() }
                .filterValues { it.isNotEmpty() }
                .mapKeys { it.key.toString() }
            if (normalized.isEmpty()) {
                appCtx.removePref(PreferKey.bookshelfHiddenTags)
            } else {
                appCtx.putPrefString(PreferKey.bookshelfHiddenTags, GSON.toJson(normalized))
            }
        }

    var bookshelfGroupTags: Map<Long, List<String>>
        get() {
            val rawMap = GSON.fromJsonObject<Map<String, List<String>>>(
                appCtx.getPrefString(PreferKey.bookshelfGroupTags)
            ).getOrDefault(emptyMap())
            return rawMap.mapNotNull { (key, value) ->
                key.toLongOrNull()?.let { groupId ->
                    groupId to value.map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                }
            }.filter { it.second.isNotEmpty() }.toMap()
        }
        set(value) {
            val normalized = value
                .mapValues { (_, tags) -> tags.map { it.trim() }.filter { it.isNotBlank() }.distinct() }
                .filterValues { it.isNotEmpty() }
                .mapKeys { it.key.toString() }
            if (normalized.isEmpty()) {
                appCtx.removePref(PreferKey.bookshelfGroupTags)
            } else {
                appCtx.putPrefString(PreferKey.bookshelfGroupTags, GSON.toJson(normalized))
            }
        }

    //region AI Config (delegated to AppAiConfig)
    const val DEFAULT_AI_SYSTEM_PROMPT = AppAiConfig.DEFAULT_AI_SYSTEM_PROMPT
    var aiAssistantEnabled: Boolean
        get() = AppAiConfig.aiAssistantEnabled
        set(value) { AppAiConfig.aiAssistantEnabled = value }
    var aiProviderList: List<AiProviderConfig>
        get() = AppAiConfig.aiProviderList
        set(value) { AppAiConfig.aiProviderList = value }
    var aiCurrentProviderId: String?
        get() = AppAiConfig.aiCurrentProviderId
        set(value) { AppAiConfig.aiCurrentProviderId = value }
    val aiCurrentProvider: AiProviderConfig?
        get() = AppAiConfig.aiCurrentProvider
    var aiModelConfigList: List<AiModelConfig>
        get() = AppAiConfig.aiModelConfigList
        set(value) { AppAiConfig.aiModelConfigList = value }
    var aiCurrentModelId: String?
        get() = AppAiConfig.aiCurrentModelId
        set(value) { AppAiConfig.aiCurrentModelId = value }
    val aiCurrentModelConfig: AiModelConfig?
        get() = AppAiConfig.aiCurrentModelConfig
    var aiAskModelId: String?
        get() = AppAiConfig.aiAskModelId
        set(value) { AppAiConfig.aiAskModelId = value }
    val aiAskModelConfig: AiModelConfig?
        get() = AppAiConfig.aiAskModelConfig
    var aiSummaryModelId: String?
        get() = AppAiConfig.aiSummaryModelId
        set(value) { AppAiConfig.aiSummaryModelId = value }
    val aiSummaryModelConfig: AiModelConfig?
        get() = AppAiConfig.aiSummaryModelConfig
    var aiReadAloudRoleModelId: String?
        get() = AppAiConfig.aiReadAloudRoleModelId
        set(value) { AppAiConfig.aiReadAloudRoleModelId = value }
    val aiReadAloudRoleModelConfig: AiModelConfig?
        get() = AppAiConfig.aiReadAloudRoleModelConfig
    var aiReadAloudRoleBackupModelId: String?
        get() = AppAiConfig.aiReadAloudRoleBackupModelId
        set(value) { AppAiConfig.aiReadAloudRoleBackupModelId = value }
    val aiReadAloudRoleBackupModelConfig: AiModelConfig?
        get() = AppAiConfig.aiReadAloudRoleBackupModelConfig
    var aiReadAloudRoleFirstResponseTimeoutSeconds: Int
        get() = AppAiConfig.aiReadAloudRoleFirstResponseTimeoutSeconds
        set(value) { AppAiConfig.aiReadAloudRoleFirstResponseTimeoutSeconds = value }
    val aiReadAloudRoleFirstResponseTimeoutMillis: Long
        get() = AppAiConfig.aiReadAloudRoleFirstResponseTimeoutMillis
    var aiReadAloudAudioModelId: String?
        get() = AppAiConfig.aiReadAloudAudioModelId
        set(value) { AppAiConfig.aiReadAloudAudioModelId = value }
    val aiReadAloudAudioModelConfig: AiModelConfig?
        get() = AppAiConfig.aiReadAloudAudioModelConfig
    var aiReadAloudAudioBackupModelId: String?
        get() = AppAiConfig.aiReadAloudAudioBackupModelId
        set(value) { AppAiConfig.aiReadAloudAudioBackupModelId = value }
    val aiReadAloudAudioBackupModelConfig: AiModelConfig?
        get() = AppAiConfig.aiReadAloudAudioBackupModelConfig
    fun aiProviderForModel(model: AiModelConfig?): AiProviderConfig? = AppAiConfig.aiProviderForModel(model)
    var aiMcpServerList: List<AiMcpServerConfig>
        get() = AppAiConfig.aiMcpServerList
        set(value) { AppAiConfig.aiMcpServerList = value }
    val aiEnabledMcpServers: List<AiMcpServerConfig>
        get() = AppAiConfig.aiEnabledMcpServers
    const val AI_CONTENT_CONSENT_ASK = AppAiConfig.AI_CONTENT_CONSENT_ASK
    const val AI_CONTENT_CONSENT_ALLOW = AppAiConfig.AI_CONTENT_CONSENT_ALLOW
    const val AI_CONTENT_CONSENT_DENY = AppAiConfig.AI_CONTENT_CONSENT_DENY
    var aiExternalContentConsent: String
        get() = AppAiConfig.aiExternalContentConsent
        set(value) { AppAiConfig.aiExternalContentConsent = value }
    var aiChatSessionList: List<AiChatSession>
        get() = AppAiConfig.aiChatSessionList
        set(value) { AppAiConfig.aiChatSessionList = value }
    var aiReadHistoryList: List<ReadAiBookHistory>
        get() = AppAiConfig.aiReadHistoryList
        set(value) { AppAiConfig.aiReadHistoryList = value }
    var aiCurrentChatSessionId: String?
        get() = AppAiConfig.aiCurrentChatSessionId
        set(value) { AppAiConfig.aiCurrentChatSessionId = value }
    var aiChatCompanionList: List<AiChatCompanionConfig>
        get() = AppAiConfig.aiChatCompanionList
        set(value) { AppAiConfig.aiChatCompanionList = value }
    var aiCurrentChatCompanionId: String
        get() = AppAiConfig.aiCurrentChatCompanionId
        set(value) { AppAiConfig.aiCurrentChatCompanionId = value }
    val aiCurrentChatCompanion: AiChatCompanionConfig
        get() = AppAiConfig.aiCurrentChatCompanion
    var aiChatAutoSpeakEnabled: Boolean
        get() = AppAiConfig.aiChatAutoSpeakEnabled
        set(value) { AppAiConfig.aiChatAutoSpeakEnabled = value }
    var aiThinkingToolbarEnabled: Boolean
        get() = AppAiConfig.aiThinkingToolbarEnabled
        set(value) { AppAiConfig.aiThinkingToolbarEnabled = value }
    fun upsertAiChatCompanion(config: AiChatCompanionConfig) = AppAiConfig.upsertAiChatCompanion(config)
    fun removeAiChatCompanion(id: String) = AppAiConfig.removeAiChatCompanion(id)
    var aiSystemPrompt: String
        get() = AppAiConfig.aiSystemPrompt
        set(value) { AppAiConfig.aiSystemPrompt = value }
    var aiSkillPrompt: String
        get() = AppAiConfig.aiSkillPrompt
        set(value) { AppAiConfig.aiSkillPrompt = value }
    var aiSkillList: List<AiSkillConfig>
        get() = AppAiConfig.aiSkillList
        set(value) { AppAiConfig.aiSkillList = value }
    var aiWorldBookList: List<AiWorldBookConfig>
        get() = AppAiConfig.aiWorldBookList
        set(value) { AppAiConfig.aiWorldBookList = value }
    var aiPersonaList: List<AiPersonaConfig>
        get() = AppAiConfig.aiPersonaList
        set(value) { AppAiConfig.aiPersonaList = value }
    var aiCurrentPersonaId: String?
        get() = AppAiConfig.aiCurrentPersonaId
        set(value) { AppAiConfig.aiCurrentPersonaId = value }
    val aiCurrentPersona: AiPersonaConfig?
        get() = AppAiConfig.aiCurrentPersona
    var aiImageProviderList: List<AiImageProviderConfig>
        get() = AppAiConfig.aiImageProviderList
        set(value) { AppAiConfig.aiImageProviderList = value }
    val aiEnabledImageProviders: List<AiImageProviderConfig>
        get() = AppAiConfig.aiEnabledImageProviders
    var aiCurrentImageProviderId: String?
        get() = AppAiConfig.aiCurrentImageProviderId
        set(value) { AppAiConfig.aiCurrentImageProviderId = value }
    val aiCurrentImageProvider: AiImageProviderConfig?
        get() = AppAiConfig.aiCurrentImageProvider
    fun findEnabledImageProvider(id: String?): AiImageProviderConfig? = AppAiConfig.findEnabledImageProvider(id)
    fun ensureCurrentImageProvider(preferredId: String? = null) = AppAiConfig.ensureCurrentImageProvider(preferredId)
    const val AI_READ_ALOUD_ROLE_MODE_FULL = AppAiConfig.AI_READ_ALOUD_ROLE_MODE_FULL
    const val AI_READ_ALOUD_ROLE_MODE_CHUNK = AppAiConfig.AI_READ_ALOUD_ROLE_MODE_CHUNK
    val DEFAULT_AI_READ_ALOUD_ROLE_PROMPT: String get() = AppAiConfig.DEFAULT_AI_READ_ALOUD_ROLE_PROMPT
    val DEFAULT_AI_READ_ALOUD_AUTO_CREATE_CHARACTER_PROMPT: String get() = AppAiConfig.DEFAULT_AI_READ_ALOUD_AUTO_CREATE_CHARACTER_PROMPT
    val DEFAULT_AI_READ_ALOUD_BGM_PROMPT: String get() = AppAiConfig.DEFAULT_AI_READ_ALOUD_BGM_PROMPT
    val DEFAULT_AI_READ_ALOUD_SOUND_EFFECT_PROMPT: String get() = AppAiConfig.DEFAULT_AI_READ_ALOUD_SOUND_EFFECT_PROMPT
    val DEFAULT_AI_READ_ALOUD_PREPROCESS_RULES: String get() = AppAiConfig.DEFAULT_AI_READ_ALOUD_PREPROCESS_RULES
    var aiReadAloudRoleEnabled: Boolean
        get() = AppAiConfig.aiReadAloudRoleEnabled
        set(value) { AppAiConfig.aiReadAloudRoleEnabled = value }
    var aiReadAloudRoleThreadCount: Int
        get() = AppAiConfig.aiReadAloudRoleThreadCount
        set(value) { AppAiConfig.aiReadAloudRoleThreadCount = value }
    var aiReadAloudRoleContextParagraphs: Int
        get() = AppAiConfig.aiReadAloudRoleContextParagraphs
        set(value) { AppAiConfig.aiReadAloudRoleContextParagraphs = value }
    var aiReadAloudRoleMergeGapParagraphs: Int
        get() = AppAiConfig.aiReadAloudRoleMergeGapParagraphs
        set(value) { AppAiConfig.aiReadAloudRoleMergeGapParagraphs = value }
    var aiReadAloudRolePrompt: String
        get() = AppAiConfig.aiReadAloudRolePrompt
        set(value) { AppAiConfig.aiReadAloudRolePrompt = value }
    val aiReadAloudRoleUsingDefaultPrompt: Boolean
        get() = AppAiConfig.aiReadAloudRoleUsingDefaultPrompt
    var aiReadAloudRolePreprocessRules: String
        get() = AppAiConfig.aiReadAloudRolePreprocessRules
        set(value) { AppAiConfig.aiReadAloudRolePreprocessRules = value }
    val aiReadAloudRoleUsingDefaultPreprocessRules: Boolean
        get() = AppAiConfig.aiReadAloudRoleUsingDefaultPreprocessRules
    var aiReadAloudRoleMode: String
        get() = AppAiConfig.aiReadAloudRoleMode
        set(value) { AppAiConfig.aiReadAloudRoleMode = value }
    var aiReadAloudAutoCreateCharacters: Boolean
        get() = AppAiConfig.aiReadAloudAutoCreateCharacters
        set(value) { AppAiConfig.aiReadAloudAutoCreateCharacters = value }
    var aiReadAloudAutoCreateCharacterPrompt: String
        get() = AppAiConfig.aiReadAloudAutoCreateCharacterPrompt
        set(value) { AppAiConfig.aiReadAloudAutoCreateCharacterPrompt = value }
    val aiReadAloudUsingDefaultAutoCreateCharacterPrompt: Boolean
        get() = AppAiConfig.aiReadAloudUsingDefaultAutoCreateCharacterPrompt
    var aiReadAloudAutoCreateAvatar: Boolean
        get() = AppAiConfig.aiReadAloudAutoCreateAvatar
        set(value) { AppAiConfig.aiReadAloudAutoCreateAvatar = value }
    var aiReadAloudBgmEnabled: Boolean
        get() = AppAiConfig.aiReadAloudBgmEnabled
        set(value) { AppAiConfig.aiReadAloudBgmEnabled = value }
    var aiReadAloudBgmPrompt: String
        get() = AppAiConfig.aiReadAloudBgmPrompt
        set(value) { AppAiConfig.aiReadAloudBgmPrompt = value }
    val aiReadAloudUsingDefaultBgmPrompt: Boolean
        get() = AppAiConfig.aiReadAloudUsingDefaultBgmPrompt
    var aiReadAloudSoundEffectPrompt: String
        get() = AppAiConfig.aiReadAloudSoundEffectPrompt
        set(value) { AppAiConfig.aiReadAloudSoundEffectPrompt = value }
    val aiReadAloudUsingDefaultSoundEffectPrompt: Boolean
        get() = AppAiConfig.aiReadAloudUsingDefaultSoundEffectPrompt
    var aiReadAloudBgmVolume: Int
        get() = AppAiConfig.aiReadAloudBgmVolume
        set(value) { AppAiConfig.aiReadAloudBgmVolume = value }
    val aiReadAloudBgmVolumeScale: Float
        get() = AppAiConfig.aiReadAloudBgmVolumeScale
    var aiReadAloudSfxVolume: Int
        get() = AppAiConfig.aiReadAloudSfxVolume
        set(value) { AppAiConfig.aiReadAloudSfxVolume = value }
    val aiReadAloudSfxVolumeScale: Float
        get() = AppAiConfig.aiReadAloudSfxVolumeScale
    var readAloudSpeakerLoudnessEnabled: Boolean
        get() = AppAiConfig.readAloudSpeakerLoudnessEnabled
        set(value) { AppAiConfig.readAloudSpeakerLoudnessEnabled = value }
    var readAloudTargetVoiceVolume: Int
        get() = AppAiConfig.readAloudTargetVoiceVolume
        set(value) { AppAiConfig.readAloudTargetVoiceVolume = value }
    var readAloudMaxSpeakerGain: Int
        get() = AppAiConfig.readAloudMaxSpeakerGain
        set(value) { AppAiConfig.readAloudMaxSpeakerGain = value }
    var readAloudNarratorBaseGain: Int
        get() = AppAiConfig.readAloudNarratorBaseGain
        set(value) { AppAiConfig.readAloudNarratorBaseGain = value }
    var readAloudSpeakerLoudnessStats: String
        get() = AppAiConfig.readAloudSpeakerLoudnessStats
        set(value) { AppAiConfig.readAloudSpeakerLoudnessStats = value }
    var aiContextCompressionEnabled: Boolean
        get() = AppAiConfig.aiContextCompressionEnabled
        set(value) { AppAiConfig.aiContextCompressionEnabled = value }
    var aiContextWindowTokens: Int
        get() = AppAiConfig.aiContextWindowTokens
        set(value) { AppAiConfig.aiContextWindowTokens = value }
    var aiThinkingContextTokens: Int
        get() = AppAiConfig.aiThinkingContextTokens
        set(value) { AppAiConfig.aiThinkingContextTokens = value }
    var aiDailyTokenBudget: Long
        get() = AppAiConfig.aiDailyTokenBudget
        set(value) { AppAiConfig.aiDailyTokenBudget = value }
    var aiTavilyEnabled: Boolean
        get() = AppAiConfig.aiTavilyEnabled
        set(value) { AppAiConfig.aiTavilyEnabled = value }
    var aiEnterToSend: Boolean
        get() = AppAiConfig.aiEnterToSend
        set(value) { AppAiConfig.aiEnterToSend = value }
    var aiEnabledToolNames: Set<String>
        get() = AppAiConfig.aiEnabledToolNames
        set(value) { AppAiConfig.aiEnabledToolNames = value }
    var aiEnabledToolNamesVersion: Int
        get() = AppAiConfig.aiEnabledToolNamesVersion
        set(value) { AppAiConfig.aiEnabledToolNamesVersion = value }
    const val AI_READ_TOOL_MODE_ENABLED = AppAiConfig.AI_READ_TOOL_MODE_ENABLED
    const val AI_READ_TOOL_MODE_SAFE = AppAiConfig.AI_READ_TOOL_MODE_SAFE
    const val AI_READ_TOOL_MODE_ALL = AppAiConfig.AI_READ_TOOL_MODE_ALL
    var aiReadToolMode: String
        get() = AppAiConfig.aiReadToolMode
        set(value) { AppAiConfig.aiReadToolMode = value }
    var aiChatAgentMode: AiAgentMode
        get() = AppAiConfig.aiChatAgentMode
        set(value) { AppAiConfig.aiChatAgentMode = value }
    var aiAgentToolMaxAttempts: Int
        get() = AppAiConfig.aiAgentToolMaxAttempts
        set(value) { AppAiConfig.aiAgentToolMaxAttempts = value }
    var aiAgentMaxToolRounds: Int
        get() = AppAiConfig.aiAgentMaxToolRounds
        set(value) { AppAiConfig.aiAgentMaxToolRounds = value }
    var aiAgentToolRetryBackoffMillis: Int
        get() = AppAiConfig.aiAgentToolRetryBackoffMillis
        set(value) { AppAiConfig.aiAgentToolRetryBackoffMillis = value }
    var aiTavilyApiKey: String
        get() = AppAiConfig.aiTavilyApiKey
        set(value) { AppAiConfig.aiTavilyApiKey = value }
    var aiTavilyBaseUrl: String
        get() = AppAiConfig.aiTavilyBaseUrl
        set(value) { AppAiConfig.aiTavilyBaseUrl = value }
    var aiTavilySearchDepth: String
        get() = AppAiConfig.aiTavilySearchDepth
        set(value) { AppAiConfig.aiTavilySearchDepth = value }
    var aiTavilyTopic: String
        get() = AppAiConfig.aiTavilyTopic
        set(value) { AppAiConfig.aiTavilyTopic = value }
    var aiTavilyMaxResults: Int
        get() = AppAiConfig.aiTavilyMaxResults
        set(value) { AppAiConfig.aiTavilyMaxResults = value }
    val aiEnabledSkills: List<AiSkillConfig>
        get() = AppAiConfig.aiEnabledSkills
    //endregion


    val autoRefreshBook: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoRefresh)

    val onlyUpdateRead: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.onlyUpdateRead)

    var enableReview: Boolean
        get() = BuildConfig.DEBUG && appCtx.getPrefBoolean(PreferKey.enableReview, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableReview, value)
        }

    var threadCount: Int
        // 直接作为并发数传给 flatMapMerge、作 Semaphore 许可数：0 会抛异常、负数会死锁，
        // 设置页的 min/max 只护 UI，坏偏好（旧备份/手工改 SP）必须在这里兜住
        get() = appCtx.getPrefInt(PreferKey.threadCount, 16).coerceIn(1, 999)
        set(value) {
            appCtx.putPrefInt(PreferKey.threadCount, value.coerceIn(1, 999))
        }

    /** Minimum spacing between starts of Coordinator-owned primary chapter downloads. */
    var downloadChapterIntervalMillis: Long
        // 坏值降级而不是抛异常：getter 在章节下载热路径被读，require 会把坏偏好变成崩溃
        get() = appCtx.getPrefLong(PreferKey.downloadChapterIntervalMillis, 0L).coerceAtLeast(0L)
        set(value) {
            appCtx.putPrefLong(PreferKey.downloadChapterIntervalMillis, value.coerceAtLeast(0L))
        }

    /** Extra attempts after a primary chapter download failure. */
    var downloadChapterRetryCount: Int
        get() = appCtx.getPrefInt(PreferKey.downloadChapterRetryCount, 2).coerceAtLeast(0)
        set(value) {
            appCtx.putPrefInt(PreferKey.downloadChapterRetryCount, value.coerceAtLeast(0))
        }

    /** 聚合主页中隐藏的集 URL 列表（GSON 序列化的 Set<String>） */
    var homepageSourceHidden: String
        get() = appCtx.getPrefString(PreferKey.homepageSourceHidden, "") ?: ""
        set(value) = appCtx.putPrefString(PreferKey.homepageSourceHidden, value)

    /** 首页模块布局模式：0 默认列表，1 紧凑 */
    var homepageLayoutMode: Int
        get() = appCtx.getPrefInt(PreferKey.homepageLayoutMode, 0)
        set(value) = appCtx.putPrefInt(PreferKey.homepageLayoutMode, value)

    /** 书架封面显示本地书籍角标（自 legadoC 移植） */
    val showLocalBookIcon: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showLocalBookIcon, false)

    /** 首页分页预加载页数 */
    var homepagePreload: Int
        get() = appCtx.getPrefInt(PreferKey.homepagePreload, 0)
        set(value) = appCtx.putPrefInt(PreferKey.homepagePreload, value)

    var remoteServerId: Long
        get() = appCtx.getPrefLong(PreferKey.remoteServerId)
        set(value) {
            appCtx.putPrefLong(PreferKey.remoteServerId, value)
        }

    // 添加本地选择的目录
    var importBookPath: String?
        get() = appCtx.getPrefString(PreferKey.importBookPath)
        set(value) {
            if (value == null) {
                appCtx.removePref(PreferKey.importBookPath)
            } else {
                appCtx.putPrefString(PreferKey.importBookPath, value)
            }
        }

    var ttsFlowSys: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.ttsFollowSys, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.ttsFollowSys, value)
        }

    val noAnimScrollPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.noAnimScrollPage, false)

    const val defaultSpeechRate = 5

    var ttsSpeechRate: Int
        get() = appCtx.getPrefInt(PreferKey.ttsSpeechRate, defaultSpeechRate)
        set(value) {
            appCtx.putPrefInt(PreferKey.ttsSpeechRate, value)
        }

    var ttsTimer: Int
        get() = appCtx.getPrefInt(PreferKey.ttsTimer, 0).coerceAtLeast(0)
        set(value) {
            appCtx.putPrefInt(PreferKey.ttsTimer, value)
        }

    val speechRatePlay: Int get() = if (ttsFlowSys) defaultSpeechRate else ttsSpeechRate

    var chineseConverterType: Int
        // 0 不转换 / 1 繁转简 / 2 简转繁；坏值回退到 0（不转换）
        get() = appCtx.getPrefInt(PreferKey.chineseConverterType).coerceIn(0, 2)
        set(value) {
            appCtx.putPrefInt(PreferKey.chineseConverterType, value)
        }

    var systemTypefaces: Int
        // 0 无衬线 / 1 衬线 / 2 等宽；坏值回退到 0
        get() = appCtx.getPrefInt(PreferKey.systemTypefaces).coerceIn(0, 2)
        set(value) {
            appCtx.putPrefInt(PreferKey.systemTypefaces, value)
        }

    var uiFontPath: String
        get() = appCtx.getPrefString(ThemeRuntimeKeys.uiFontPath(isNightTheme)).orEmpty()
        set(value) {
            appCtx.putPrefString(ThemeRuntimeKeys.uiFontPath(isNightTheme), value)
        }

    var titleFontPath: String
        get() = appCtx.getPrefString(ThemeRuntimeKeys.titleFontPath(isNightTheme)).orEmpty()
        set(value) {
            appCtx.putPrefString(ThemeRuntimeKeys.titleFontPath(isNightTheme), value)
        }

    var uiFontColor: String
        get() = appCtx.getPrefString(ThemeRuntimeKeys.uiFontColor(isNightTheme)).orEmpty()
        set(value) {
            appCtx.putPrefString(ThemeRuntimeKeys.uiFontColor(isNightTheme), value)
        }

    var titleFontColor: String
        get() = appCtx.getPrefString(ThemeRuntimeKeys.titleFontColor(isNightTheme)).orEmpty()
        set(value) {
            appCtx.putPrefString(ThemeRuntimeKeys.titleFontColor(isNightTheme), value)
        }

    var bookCoverShadow: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.bookCoverShadow, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.bookCoverShadow, value)
        }

    var elevation: Int
        get() = if (isEInkMode) 0 else appCtx.getPrefInt(
            PreferKey.barElevation,
            AppConst.sysElevation
        )
        set(value) {
            appCtx.putPrefInt(PreferKey.barElevation, value)
        }

    var frostedGlassLevel: Int
        get() = appCtx.getPrefInt(PreferKey.frostedGlassLevel, 70).coerceIn(0, 100)
        set(value) {
            appCtx.putPrefInt(PreferKey.frostedGlassLevel, value.coerceIn(0, 100))
        }

    var uiCornerScale: Float
        get() = appCtx.getPrefString(ThemeRuntimeKeys.uiCornerScale(isNightTheme), "1")
            ?.toFloatOrNull()
            ?.coerceIn(0f, 3f)
            ?: 1f
        set(value) {
            appCtx.putPrefString(ThemeRuntimeKeys.uiCornerScale(isNightTheme), value.coerceIn(0f, 3f).toPlainScale())
        }

    var uiLayoutAlpha: Int
        get() = appCtx.getPrefInt(
            ThemeRuntimeKeys.uiLayoutAlpha(isNightTheme),
            appCtx.getPrefInt(PreferKey.uiCornerEffectLevel, 100)
        ).coerceIn(0, 100)
        set(value) {
            appCtx.putPrefInt(ThemeRuntimeKeys.uiLayoutAlpha(isNightTheme), value.coerceIn(0, 100))
        }

    var dialogAlpha: Int
        get() = appCtx.getPrefInt(ThemeRuntimeKeys.dialogAlpha(isNightTheme), 100).coerceIn(0, 100)
        set(value) {
            appCtx.putPrefInt(ThemeRuntimeKeys.dialogAlpha(isNightTheme), value.coerceIn(0, 100))
        }

    @Deprecated("Use uiLayoutAlpha")
    var uiCornerEffectMode: String
        get() = "solid"
        set(value) {
            appCtx.putPrefString(PreferKey.uiCornerEffectMode, value)
        }

    @Deprecated("Use uiLayoutAlpha")
    var uiCornerEffectLevel: Int
        get() = uiLayoutAlpha
        set(value) {
            uiLayoutAlpha = value
        }

    val uiCornerSearchFollow: Boolean
        get() = appCtx.getPrefBoolean(ThemeRuntimeKeys.uiCornerSearchFollow(isNightTheme), false)

    val uiCornerReplyFollow: Boolean
        get() = appCtx.getPrefBoolean(ThemeRuntimeKeys.uiCornerReplyFollow(isNightTheme), false)

    var liquidGlassLevel: Int
        get() = appCtx.getPrefInt(PreferKey.liquidGlassLevel, 68).coerceIn(0, 100)
        set(value) {
            appCtx.putPrefInt(PreferKey.liquidGlassLevel, value.coerceIn(0, 100))
        }

    /*
     * 全局毛玻璃的四个倍率（0-200，100 = 预设原值）。
     * 1.26.1 起由 GlassMaterialPreset.current() 统一生效：底栏、顶栏、发现页行
     * 读同一份「预设 × 倍率」结果。此前只有发现页行消费它们，所以同一档参数下
     * 底栏与行必然不一致——倍率本身就是不一致的来源之一。
     */
    var exploreGlassBlurScale: Int
        get() = appCtx.getPrefInt(PreferKey.exploreGlassBlurScale, 100).coerceIn(0, 200)
        set(value) {
            appCtx.putPrefInt(PreferKey.exploreGlassBlurScale, value.coerceIn(0, 200))
        }

    var exploreGlassTintScale: Int
        get() = appCtx.getPrefInt(PreferKey.exploreGlassTintScale, 100).coerceIn(0, 200)
        set(value) {
            appCtx.putPrefInt(PreferKey.exploreGlassTintScale, value.coerceIn(0, 200))
        }

    var exploreGlassShellScale: Int
        get() = appCtx.getPrefInt(PreferKey.exploreGlassShellScale, 100).coerceIn(0, 200)
        set(value) {
            appCtx.putPrefInt(PreferKey.exploreGlassShellScale, value.coerceIn(0, 200))
        }

    var exploreGlassStrokeScale: Int
        get() = appCtx.getPrefInt(PreferKey.exploreGlassStrokeScale, 100).coerceIn(0, 200)
        set(value) {
            appCtx.putPrefInt(PreferKey.exploreGlassStrokeScale, value.coerceIn(0, 200))
        }

    /**
     * 已废弃（1.25.8 起）：材质档位收拢为全局单一来源 PreferKey.exploreGlassEffectMode，
     * 渲染层经 resolveExploreGlassMode / resolvedExploreGlassMode 读取，不再读本键。
     * getter 仅保留给一次性迁移（migrateLegacyExploreGlassFollow 固化旧档位），
     * setter 已无生产调用方，禁止新增业务引用。
     */
    @Deprecated("材质档位已收拢为全局 exploreGlassEffectMode，仅迁移链路可读")
    var bottomBarEffectMode: String
        get() = appCtx.getPrefString(PreferKey.bottomBarEffectMode, "glass")
            ?.takeIf { it in setOf("glass", "frosted", "solid") }
            ?: "glass"
        set(value) {
            appCtx.putPrefString(
                PreferKey.bottomBarEffectMode,
                value.takeIf { it in setOf("glass", "frosted", "solid") } ?: "glass"
            )
        }

    var bottomBarLayoutMode: String
        get() = appCtx.getPrefString(PreferKey.bottomBarLayoutMode, "floating")
            ?.takeIf { it in setOf("floating", "sidebar", "standard") }
            ?: "floating"
        set(value) {
            appCtx.putPrefString(
                PreferKey.bottomBarLayoutMode,
                value.takeIf { it in setOf("floating", "sidebar", "standard") } ?: "floating"
            )
        }

    var bottomBarSidebarGravity: String
        get() = appCtx.getPrefString(PreferKey.bottomBarSidebarGravity, "start")
            ?.takeIf { it in setOf("start", "end") }
            ?: "start"
        set(value) {
            appCtx.putPrefString(
                PreferKey.bottomBarSidebarGravity,
                value.takeIf { it in setOf("start", "end") } ?: "start"
            )
        }

    var defaultTopBarShowSearch: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.defaultTopBarShowSearch, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.defaultTopBarShowSearch, value)

    var floatingBottomBarHideSearch: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.floatingBottomBarHideSearch, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.floatingBottomBarHideSearch, value)

    var topBarFloatingChrome: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.topBarFloatingChrome, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.topBarFloatingChrome, value)

    var topBarExpandFilters: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.topBarExpandFilters, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.topBarExpandFilters, value)

    var topBarHideFilterToggle: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.topBarHideFilterToggle, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.topBarHideFilterToggle, value)

    var readUrlInBrowser: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readUrlOpenInBrowser)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.readUrlOpenInBrowser, value)
        }

    private fun Float.toPlainScale(): String {
        return if (this % 1f == 0f) {
            this.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
        }
    }

    var exportCharset: String
        get() {
            val c = appCtx.getPrefString(PreferKey.exportCharset)
            if (c.isNullOrBlank()) {
                return "UTF-8"
            }
            return c
        }
        set(value) {
            appCtx.putPrefString(PreferKey.exportCharset, value)
        }

    var exportUseReplace: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportUseReplace, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportUseReplace, value)
        }

    var exportToWebDav: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportToWebDav)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportToWebDav, value)
        }
    var exportNoChapterName: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportNoChapterName)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportNoChapterName, value)
        }

    // 是否启用自定义导出 default->false
    var enableCustomExport: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.enableCustomExport, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableCustomExport, value)
        }

    var exportType: Int
        get() = appCtx.getPrefInt(PreferKey.exportType)
        set(value) {
            appCtx.putPrefInt(PreferKey.exportType, value)
        }
    var exportPictureFile: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportPictureFile, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportPictureFile, value)
        }

    var parallelExportBook: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.parallelExportBook, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.parallelExportBook, value)
        }

    var changeSourceCheckAuthor: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceCheckAuthor)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceCheckAuthor, value)
        }

    var ttsEngine: String?
        get() = appCtx.getPrefString(PreferKey.ttsEngine)
        set(value) {
            appCtx.putPrefString(PreferKey.ttsEngine, value)
        }

    var webPort: Int
        // 端口 0/负数/超范围会让 HttpServer 绑定失败，夹到可用端口区间；
        // 上限对齐 WebService.getPort 的 65530（WebSocketServer 需要 port+1），
        // 否则备份还原出 65531-65535 时 UI 显示该值而服务端静默回退 1122
        get() = appCtx.getPrefInt(PreferKey.webPort, 1122).coerceIn(1024, 65530)
        set(value) {
            appCtx.putPrefInt(PreferKey.webPort, value.coerceIn(1024, 65530))
        }

    /**
     * Web 服务访问口令。
     *
     * Web 服务本身没有任何鉴权：开启后同一局域网内任何人都能增删书源、删书、
     * 读取书源登录态。这里给一个可选的访问口令堵住这个缺口。
     *
     * **留空（默认）= 不启用**，行为与旧版完全一致。
     * 启用后浏览器首次访问需带 `?token=口令`，服务端验证通过会种下 Cookie，
     * 之后同一浏览器不再需要带参数（前端无需任何改动）。
     */
    var webServiceToken: String
        get() = SecurePrefs.read(PreferKey.webServiceToken) ?: ""
        set(value) {
            SecurePrefs.write(PreferKey.webServiceToken, value.trim().ifBlank { null })
        }

    var tocUiUseReplace: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocUiUseReplace)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocUiUseReplace, value)
        }

    var tocCountWords: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocCountWords, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocCountWords, value)
        }

    var enableReadRecord: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.enableReadRecord, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableReadRecord, value)
        }

    val autoChangeSource: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoChangeSource, true)

    var changeSourceLoadInfo: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadInfo)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadInfo, value)
        }

    var changeSourceLoadToc: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadToc)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadToc, value)
        }

    var changeSourceLoadWordCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadWordCount)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadWordCount, value)
        }

    var openBookInfoByClickTitle: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.openBookInfoByClickTitle, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.openBookInfoByClickTitle, value)
        }

    var autoRefreshMediaToc: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoRefreshMediaToc, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.autoRefreshMediaToc, value)
        }

    var showBookshelfFastScroller: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showBookshelfFastScroller, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showBookshelfFastScroller, value)
        }

    var fastScrollerTouchTargetDp: Int
        get() = appCtx.getPrefInt(
            PreferKey.fastScrollerTouchTargetDp,
            DEFAULT_FAST_SCROLLER_TOUCH_TARGET_DP
        ).coerceIn(MIN_FAST_SCROLLER_TOUCH_TARGET_DP, MAX_FAST_SCROLLER_TOUCH_TARGET_DP)
        set(value) {
            appCtx.putPrefInt(
                PreferKey.fastScrollerTouchTargetDp,
                value.coerceIn(MIN_FAST_SCROLLER_TOUCH_TARGET_DP, MAX_FAST_SCROLLER_TOUCH_TARGET_DP)
            )
        }

    var contentSelectSpeakMod: Int
        // 0 关闭 / 1 开启；坏值回退到 0
        get() = appCtx.getPrefInt(PreferKey.contentSelectSpeakMod).coerceIn(0, 1)
        set(value) {
            appCtx.putPrefInt(PreferKey.contentSelectSpeakMod, value.coerceIn(0, 1))
        }

    var batchChangeSourceDelay: Int
        get() = appCtx.getPrefInt(PreferKey.batchChangeSourceDelay).coerceAtLeast(0)
        set(value) {
            appCtx.putPrefInt(PreferKey.batchChangeSourceDelay, value.coerceAtLeast(0))
        }

    var importKeepName: Boolean
            get() = appCtx.getPrefBoolean(PreferKey.importKeepName)
            set(value) {
                appCtx.putPrefBoolean(PreferKey.importKeepName, value)
            }
    var importKeepGroup: Boolean
            get() = appCtx.getPrefBoolean(PreferKey.importKeepGroup)
            set(value) {
                appCtx.putPrefBoolean(PreferKey.importKeepGroup, value)
            }
    var importKeepEnable: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.importKeepEnable, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.importKeepEnable, value)
        }
    var importShowComment: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.importShowComment, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.importShowComment, value)
        }

    val clickImgWay: String?
        get() = appCtx.getPrefString(PreferKey.clickImgWay)

    var preDownloadNum
        // 负数会生成反向/越界章节区间，下限夹到 0
        get() = appCtx.getPrefInt(PreferKey.preDownloadNum, 2).coerceAtLeast(0)
        set(value) {
            appCtx.putPrefInt(PreferKey.preDownloadNum, value.coerceAtLeast(0))
        }

    /**
     * 预取下一章：当前章正文就绪后后台抓下一章并落盘，
     * 翻到下一章时直接读本地缓存，不再等网络。
     */
    var prefetchNextChapter
        get() = appCtx.getPrefBoolean(PreferKey.prefetchNextChapter, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.prefetchNextChapter, value)
        }

    /**
     * 预下载仅在 WiFi 下进行：移动数据下不自动预下载整本，避免与首屏抢带宽。
     */
    var preDownloadWifiOnly
        get() = appCtx.getPrefBoolean(PreferKey.preDownloadWifiOnly, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.preDownloadWifiOnly, value)
        }

    val syncBookProgress get() = appCtx.getPrefBoolean(PreferKey.syncBookProgress, true)

    val syncBookProgressPlus get() = appCtx.getPrefBoolean(PreferKey.syncBookProgressPlus, false)

    val mediaButtonOnExit get() = appCtx.getPrefBoolean(PreferKey.mediaButtonOnExit, true)

    val readAloudByMediaButton
        get() = appCtx.getPrefBoolean(PreferKey.readAloudByMediaButton, false)

    var showReadAloudFloatingBall: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showReadAloudFloatingBall, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.showReadAloudFloatingBall, value)

    var readAloudFloatingFontSize: Int
        get() = appCtx.getPrefInt(PreferKey.readAloudFloatingFontSize, 20).coerceIn(14, 34)
        set(value) = appCtx.putPrefInt(PreferKey.readAloudFloatingFontSize, value.coerceIn(14, 34))

    var readAloudFloatingBackgroundAlpha: Int
        get() = appCtx.getPrefInt(PreferKey.readAloudFloatingBackgroundAlpha, 92).coerceIn(0, 100)
        set(value) = appCtx.putPrefInt(
            PreferKey.readAloudFloatingBackgroundAlpha,
            value.coerceIn(0, 100)
        )

    var readAloudFloatingHeightPercent: Int
        get() = appCtx.getPrefInt(PreferKey.readAloudFloatingHeightPercent, 58).coerceIn(15, 90)
        set(value) = appCtx.putPrefInt(
            PreferKey.readAloudFloatingHeightPercent,
            value.coerceIn(15, 90)
        )

    val replaceEnableDefault get() = appCtx.getPrefBoolean(PreferKey.replaceEnableDefault, true)

    val webDavDir get() = appCtx.getPrefString(PreferKey.webDavDir, "legado")

    val cloudStorageType get() = appCtx.getPrefString(PreferKey.cloudStorageType, "WEBDAV")

    val s3Endpoint get() = appCtx.getPrefString(PreferKey.s3Endpoint)

    val s3Region get() = appCtx.getPrefString(PreferKey.s3Region, "us-east-1")

    val s3Bucket get() = appCtx.getPrefString(PreferKey.s3Bucket)

    val s3Prefix get() = appCtx.getPrefString(PreferKey.s3Prefix, "legado")

    val s3AccessKey get() = appCtx.getPrefString(PreferKey.s3AccessKey)

    val s3SecretKey get() = SecurePrefs.read(PreferKey.s3SecretKey)

    val s3SessionToken get() = SecurePrefs.read(PreferKey.s3SessionToken)

    val s3Containers get() = appCtx.getPrefString(PreferKey.s3Containers)

    val s3ContainerSelections get() = appCtx.getPrefString(PreferKey.s3ContainerSelections)

    var autoSwitchS3Container
        get() = appCtx.getPrefBoolean(PreferKey.autoSwitchS3Container, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.autoSwitchS3Container, value)

    val webDavDeviceName get() = appCtx.getPrefString(PreferKey.webDavDeviceName, Build.MODEL)

    var syncThemePackages
        get() = appCtx.getPrefBoolean(PreferKey.syncThemePackages, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.syncThemePackages, value)

    val recordHeapDump get() = appCtx.getPrefBoolean(PreferKey.recordHeapDump, false)

    /** 调试悬浮球：常驻 debug 日志入口（辞晨版移植，默认关）。 */
    val debugLogFloatingBall get() = appCtx.getPrefBoolean(PreferKey.debugLogFloatingBall, false)

    val loadCoverOnlyWifi get() = appCtx.getPrefBoolean(PreferKey.loadCoverOnlyWifi, false)

    val showAddToShelfAlert get() = appCtx.getPrefBoolean(PreferKey.showAddToShelfAlert, true)

    val ignoreAudioFocus get() = appCtx.getPrefBoolean(PreferKey.ignoreAudioFocus, false)

    var pauseReadAloudWhilePhoneCalls
        get() = appCtx.getPrefBoolean(PreferKey.pauseReadAloudWhilePhoneCalls, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.pauseReadAloudWhilePhoneCalls, value)

    val onlyLatestBackup get() = appCtx.getPrefBoolean(PreferKey.onlyLatestBackup, true)

    /**
     * 书架增删时自动备份。
     *
     * ⚠️ 默认**关**：自动备份会联网上传 WebDAV 并写盘，属侵入性行为，
     * 不能替用户默认打开。
     */
    val autoBackupOnShelfChange get() =
        appCtx.getPrefBoolean(PreferKey.autoBackupOnShelfChange, false)

    /** 备份/恢复已下载书籍本体与正文缓存（体积较大，默认关闭） */
    val backupBookFiles get() = appCtx.getPrefBoolean(PreferKey.backupBookFiles, false)

    /** 参数包与美术/封面等资源文件拆分为独立备份包异步同步（默认开启，主包保持轻量） */
    val backupAssetsSeparately get() = appCtx.getPrefBoolean(PreferKey.backupAssetsSeparately, true)

    val webDavDeleteOldBackup get() = appCtx.getPrefBoolean(PreferKey.webDavDeleteOldBackup, false)

    val webDavBackupCover get() = appCtx.getPrefBoolean(PreferKey.webDavBackupCover, false)

    val autoCheckNewBackup get() = appCtx.getPrefBoolean(PreferKey.autoCheckNewBackup, true)

    val defaultHomePage get() = appCtx.getPrefString(PreferKey.defaultHomePage, "bookshelf")

    val updateToVariant get() = appCtx.getPrefString(PreferKey.updateToVariant, "default_version")

    val streamReadAloudAudio get() = appCtx.getPrefBoolean(PreferKey.streamReadAloudAudio, false)

    val doublePageHorizontal: String?
        get() = appCtx.getPrefString(PreferKey.doublePageHorizontal)

    var epubReadEngine: String
        get() = EpubReadEnginePolicy.normalize(
            appCtx.getPrefString(PreferKey.epubReadEngine, EpubReadEnginePolicy.DEFAULT)
        )
        set(value) = appCtx.putPrefString(
            PreferKey.epubReadEngine,
            EpubReadEnginePolicy.normalize(value)
        )

    val useEpubCore: Boolean
        get() = EpubReadEnginePolicy.usesCore(epubReadEngine)

    var textReadEngine: String
        get() = TextReadEnginePolicy.normalize(appCtx.getPrefString(PreferKey.textReadEngine))
        set(value) = appCtx.putPrefString(PreferKey.textReadEngine, TextReadEnginePolicy.normalize(value))

    var epubCoreScheduleMode: String
        get() = appCtx.getPrefString(PreferKey.epubCoreScheduleMode, "smart") ?: "smart"
        set(value) = appCtx.putPrefString(PreferKey.epubCoreScheduleMode, value)

    val progressBarBehavior: String?
        get() = appCtx.getPrefString(PreferKey.progressBarBehavior, "page")

    val keyPageOnLongPress
        get() = appCtx.getPrefBoolean(PreferKey.keyPageOnLongPress, false)

    val volumeKeyPage
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPage, true)

    val volumeKeyPageOnPlay
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPageOnPlay, true)

    val illustrationAudioVolumeKey
        get() = appCtx.getPrefBoolean(PreferKey.illustrationAudioVolumeKey, true)

    val mouseWheelPage
        get() = appCtx.getPrefBoolean(PreferKey.mouseWheelPage, true)

    val paddingDisplayCutouts
        get() = appCtx.getPrefBoolean(PreferKey.paddingDisplayCutouts, false)

    var searchScope: String
        get() = appCtx.getPrefString(PreferKey.searchScope) ?: ""
        set(value) {
            appCtx.putPrefString(PreferKey.searchScope, value)
        }

    var searchGroup: String
        get() = appCtx.getPrefString(PreferKey.searchGroup) ?: ""
        set(value) {
            appCtx.putPrefString(PreferKey.searchGroup, value)
        }

    var pageTouchSlop: Int
        get() = appCtx.getPrefInt(PreferKey.pageTouchSlop, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.pageTouchSlop, value)
        }

    var pageTouchClick: Int
        get() = appCtx.getPrefInt(PreferKey.pageTouchClick, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.pageTouchClick, value)
        }

    var bookshelfSort: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfSort, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfSort, value)
        }

    fun getBookSortByGroupId(groupId: Long): Int {
        return appDb.bookGroupDao.getByID(groupId)?.getRealBookSort()
            ?: bookshelfSort
    }

    private fun getPrefUserAgent(): String {
        val ua = appCtx.getPrefString(PreferKey.userAgent)
        if (ua.isNullOrBlank()) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + BuildConfig.Cronet_Main_Version + " Safari/537.36"
        }
        return ua
    }

    var bitmapCacheSize: Int
        get() = appCtx.getPrefInt(PreferKey.bitmapCacheSize, 50)
        set(value) {
            appCtx.putPrefInt(PreferKey.bitmapCacheSize, value)
        }

    var imageRetainNum: Int
        get() = appCtx.getPrefInt(PreferKey.imageRetainNum, 0).coerceAtLeast(0)
        set(value) {
            appCtx.putPrefInt(PreferKey.imageRetainNum, value.coerceAtLeast(0))
        }

    var showReadTitleBarAddition: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showReadTitleAddition, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showReadTitleAddition, value)
        }
    var readBarStyleFollowPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readBarStyleFollowPage, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.readBarStyleFollowPage, value)
        }
    var readScrollFollowBackground: Boolean
        get() = ReadBookConfig.durConfig.curReadScrollFollowBackground()
        set(value) {
            ReadBookConfig.durConfig.setCurReadScrollFollowBackground(value)
        }
    var readMenuAlpha: Int
        get() = appCtx.getPrefInt(PreferKey.readMenuAlpha, 100).coerceIn(35, 100)
        set(value) {
            appCtx.putPrefInt(PreferKey.readMenuAlpha, value.coerceIn(35, 100))
        }

    var sourceEditMaxLine: Int
        get() {
            val maxLine = appCtx.getPrefInt(PreferKey.sourceEditMaxLine, Int.MAX_VALUE)
            if (maxLine < 10) {
                return Int.MAX_VALUE
            }
            return maxLine
        }
        set(value) {
            appCtx.putPrefInt(PreferKey.sourceEditMaxLine, value)
        }

    var audioPlayUseWakeLock: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.audioPlayWakeLock)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.audioPlayWakeLock, value)
        }

    var brightnessVwPos: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.brightnessVwPos)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.brightnessVwPos, value)
        }

    fun detectClickArea() {
        if (clickActionTL * clickActionTC * clickActionTR
            * clickActionML * clickActionMC * clickActionMR
            * clickActionBL * clickActionBC * clickActionBR != 0
        ) {
            appCtx.putPrefInt(PreferKey.clickActionMC, 0)
            appCtx.toastOnUi(appCtx.getString(R.string.svc_no_menu_area_configured_middle))
        }
    }

    //跳转到漫画界面不使用富文本模式
    val showMangaUi: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showMangaUi, true)

    //禁用漫画缩放
    var disableMangaScale: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.disableMangaScale, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableMangaScale, value)
        }

    var disableMangaPageAnim: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.disableMangaPageAnim, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableMangaPageAnim, value)
        }

    //漫画预加载数量
    var mangaPreDownloadNum
        get() = appCtx.getPrefInt(PreferKey.mangaPreDownloadNum, 10).coerceAtLeast(0)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaPreDownloadNum, value.coerceAtLeast(0))
        }

    //点击翻页
    var disableClickScroll
        get() = appCtx.getPrefBoolean(PreferKey.disableClickScroll, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableClickScroll, value)
        }

    //漫画滚动速度
    var mangaAutoPageSpeed
        // 0 会让自动翻页间隔计算失去意义，下限夹到 1
        get() = appCtx.getPrefInt(PreferKey.mangaAutoPageSpeed, 3).coerceAtLeast(1)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaAutoPageSpeed, value.coerceAtLeast(1))
        }

    //漫画页脚配置
    var mangaFooterConfig
        get() = appCtx.getPrefString(PreferKey.mangaFooterConfig, "")
        set(value) {
            appCtx.putPrefString(PreferKey.mangaFooterConfig, value)
        }

    //漫画水平滚动
    var enableMangaHorizontalScroll
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaHorizontalScroll, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaHorizontalScroll, value)
        }

    var mangaColorFilter
        get() = appCtx.getPrefString(PreferKey.mangaColorFilter, "")
        set(value) {
            appCtx.putPrefString(PreferKey.mangaColorFilter, value)
        }

    //禁用漫画内标题
    var hideMangaTitle
        get() = appCtx.getPrefBoolean(PreferKey.hideMangaTitle, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.hideMangaTitle, value)
        }

    //开启墨水屏模式
    var enableMangaEInk
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaEInk, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaEInk, value)
        }

    var mangaEInkThreshold
        // 阈值语义是 0..255 的灰度值（对齐 EpaperTransformation 的 @IntRange），越界已无意义
        get() = appCtx.getPrefInt(PreferKey.mangaEInkThreshold, 150).coerceIn(0, 255)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaEInkThreshold, value.coerceIn(0, 255))
        }

    var disableHorizontalPageSnap
        get() = appCtx.getPrefBoolean(PreferKey.disableHorizontalPageSnap, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableHorizontalPageSnap, value)
        }

    var enableMangaGray
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaGray, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaGray, value)
        }

    var welcomeImage
        get() = appCtx.getPrefString(PreferKey.welcomeImage)
        set(value) {
            appCtx.putPrefString(PreferKey.welcomeImage, value)
        }

    var welcomeShowText
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowText, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowText, value)
        }

    var welcomeShowIcon
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowIcon, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowIcon, value)
        }

    var welcomeImageDark
        get() = appCtx.getPrefString(PreferKey.welcomeImageDark)
        set(value) {
            appCtx.putPrefString(PreferKey.welcomeImageDark, value)
        }

    var welcomeShowTextDark
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowTextDark, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowTextDark, value)
        }

    var welcomeShowIconDark
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowIconDark, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowIconDark, value)
        }

    val autoUpdateVariant get() = appCtx.getPrefBoolean(PreferKey.autoUpdateVariant, true)

    /** 规则回收站开关（移植自 Legado_Max，默认开启，页内可关闭） */
    var sourceRecycleBinEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.sourceRecycleBinEnabled, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.sourceRecycleBinEnabled, value)
        }

    /** 缓存评论时抓取评论页快照 */
    var syncCacheReview: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.syncCacheReview, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.syncCacheReview, value)
        }

    /**
     * 缓存评论快照时保存楼中楼/回复内容。
     * 关闭后所有评论快照（段评、章评、书评）都不包含回复层；
     * 章评按章、书评整本各只缓存一份，不受此开关影响。
     */
    var cacheReviewReplies: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.cacheReviewReplies, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.cacheReviewReplies, value)
        }

    /**
     * 评论打开方式：network（联网优先） / snapshot_first（缓存优先） / offline（纯快照）。
     * 图标直点与选中文字后的段评菜单共用此值（见 [BookImgClick] 的三模式语义）。
     * 历史偏好值 snapshot_only / network_only 自动归一到对应取值。
     */
    var reviewOpenMode: String
        get() = ReviewOpenModePolicy.normalize(
            appCtx.getPrefString(PreferKey.reviewOpenMode, ReviewOpenMode.NETWORK)
        )
        set(value) {
            appCtx.putPrefString(
                PreferKey.reviewOpenMode,
                ReviewOpenModePolicy.normalize(value)
            )
        }

    /**
     * 段评预热是否仅限 WiFi/以太网（默认关闭）。
     * 只影响「阅读页提前抓取段评快照」的后台流量，不影响用户主动点击时的打开方式。
     */
    var reviewPreheatWifiOnly: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.reviewPreheatWifiOnly, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.reviewPreheatWifiOnly, value)
        }

    /**
     * 段评快照保鲜期（天，默认 3 天）。
     * 过期不删除：点击时仍先用旧快照保证 0 秒可见，同时后台刷新覆盖；
     * 预热时对过期快照按「需重抓」处理。设为 0 表示不保鲜（永不过期）。
     */
    var reviewSnapshotTtlDays: Int
        get() = appCtx.getPrefInt(
            PreferKey.reviewSnapshotTtlDays,
            DEFAULT_REVIEW_SNAPSHOT_TTL_DAYS
        ).coerceAtLeast(0)
        set(value) {
            appCtx.putPrefInt(PreferKey.reviewSnapshotTtlDays, value.coerceAtLeast(0))
        }

    /**
     * 书籍缓存包（缓存管理 → 导出/导入）是否附带评论页快照。
     * 关闭后 `reviews/` 子目录不打包；导入侧始终还原包内已有的评论目录。
     */
    var exportReviews: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportReviews, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportReviews, value)
        }
}

/**
 * 压缩阈值归一化：0/负数会让压缩失去意义，也会让抓取侧的资源判定失效，
 * 因此读写两侧都收敛到 [AppConfig.MIN_REVIEW_COMPRESSION_KB]。
 *
 * 放在文件级而非 AppConfig 内：设置页与单测都能直接用，不必初始化整个配置对象。
 */
internal fun normalizeReviewCompressionKb(value: Int): Int =
    value.coerceAtLeast(AppConfig.MIN_REVIEW_COMPRESSION_KB)
