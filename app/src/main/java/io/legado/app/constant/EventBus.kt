package io.legado.app.constant

object EventBus {
    const val MEDIA_BUTTON = "mediaButton"
    const val RECREATE = "RECREATE"
    const val UP_BOOKSHELF = "upBookToc"
    const val BOOKSHELF_REFRESH = "bookshelfRefresh"
    const val BOOKSHELF_STRUCTURE_CHANGED = "bookshelfStructureChanged"
    const val ALOUD_STATE = "aloud_state"
    const val TTS_PROGRESS = "ttsStart"
    const val READ_ALOUD_PROGRESS = "readAloudProgress"
    const val AUDIO_DS = "audioDs"
    const val READ_ALOUD_DS = "readAloudDs"
    const val READ_ALOUD_PLAYBACK_STATE = "readAloudPlaybackState"
    const val READ_ALOUD_PANEL_ACTIVE = "readAloudPanelActive"
    const val APP_FOREGROUND_CHANGED = "appForegroundChanged"
    const val BATTERY_CHANGED = "batteryChanged"
    const val TIME_CHANGED = "timeChanged"
    const val UP_CONFIG = "upConfig"
    const val VIDEO_SUB_TITLE = "VideoSubTitle"
    const val AUDIO_SUB_TITLE = "audioSubTitle"
    const val AUDIO_STATE = "audioState"
    const val AUDIO_PROGRESS = "audioProgress"
    const val AUDIO_BUFFER_PROGRESS = "audioBufferProgress"
    const val AUDIO_SIZE = "audioSize"
    const val AUDIO_SPEED = "audioSpeed"
    /**
     * 主页需要重新读取配置并刷新（布局 / 顶栏 / 主题 / 页签等）。
     *
     * 发布方：`PageLayoutConfigFragment`、`DiscoverySubscriptionConfigFragment`、
     * `ThemeConfigFragment`、`MainLayoutPresetConfig`、`BaseBookshelfFragment`、
     * `NavigationBarManageActivity`、`AiConfigFragment` 等设置入口。
     * 订阅方：`MainActivity.observeLiveBus`（按 payload 触发重读/重建）。
     * payload：`Boolean`（不同发布方语义略异，订阅方仅作为「重新读取当前偏好」的信号）。
     */
    const val NOTIFY_MAIN = "notifyMain"
    const val MAIN_APPEARANCE_KIT_CHANGED = "mainAppearanceKitChanged"
    const val MAIN_THEME_BACKGROUND_CHANGED = "mainThemeBackgroundChanged"
    const val NAVIGATION_BAR_CHANGED = "navigationBarChanged"
    const val TOP_BAR_CHANGED = "topBarChanged"
    /**
     * Web 服务（内置 HTTP 服务）运行状态变化。
     *
     * 发布方：`WebService`（启动/停止/端口或地址变化时）。
     * 订阅方：`NetworkConfigFragment.onViewCreated`（刷新设置项状态）。
     * payload：`String`（当前 hostAddress；停止时为空串）。
     */
    const val WEB_SERVICE = "webService"
    const val UP_DOWNLOAD = "upDownload"
    const val UP_DOWNLOAD_STATE = "upDownloadState"
    const val SAVE_CONTENT = "saveContent"
    const val CHECK_SOURCE = "checkSource"
    const val CHECK_SOURCE_DONE = "checkSourceDone"
    const val TIP_COLOR = "tipColor"
    const val SOURCE_CHANGED = "sourceChanged"
    const val SEARCH_RESULT = "searchResult"
    const val UPDATE_READ_ACTION_BAR = "updateReadActionBar"
    const val READ_MENU_BUTTON_CHANGED = "readMenuButtonChanged"
    const val CONTENT_SELECT_MENU_CONFIG_CHANGED = "contentSelectMenuConfigChanged"
    const val UP_SEEK_BAR = "upSeekBar"
    const val READ_ALOUD_PLAY = "readAloudPlay"
    const val READ_ALOUD_CONFIG_CHANGED = "readAloudConfigChanged"
    const val READ_ALOUD_CONFIG_SCOPE = "scope"
    const val READ_ALOUD_CONFIG_SCOPE_AUDIO = "audio"
    const val READ_ALOUD_CONFIG_SCOPE_SPEECH = "speech"
    const val READ_ALOUD_CONFIG_SCOPE_ENGINE = "engine"
    const val AI_READ_ALOUD_ROLE_STATE = "aiReadAloudRoleState"
    const val AI_CONFIG_CHANGED = "aiConfigChanged"
    const val EXPORT_BOOK = "exportBook"
    const val UP_MANGA_CONFIG = "upMangaConfig"

    /**
     * 漫画显示方式（墨纸 / 灰度 / 隐藏标题 / 控制栏）在别处被改动。
     * 与 [UP_MANGA_CONFIG] 分开：那个事件带页脚配置对象，这个只表示「显示参数变了」，
     * 阅读页收到后按 AppConfig 当前值重刷，不再解析 payload。
     *
     * 发布方：`MangaVideoConfigFragment`（改墨纸/灰度/隐藏标题等显示偏好）。
     * 订阅方：`ReadMangaActivity.observeLiveBus`（重刷阅读页显示参数）。
     * payload：`String`（发生变化的 PreferKey）。
     */
    const val UP_MANGA_DISPLAY = "upMangaDisplay"
    const val PLAY_MODE_CHANGED = "playModeChanged"
    const val REFRESH_BOOK_INFO = "refreshBookInfo"
    const val REFRESH_BOOK_CONTENT = "refreshBookContent"
    const val REFRESH_BOOK_TOC = "refreshBookToc"
    const val LIBRARY_CONTAINER_CHANGED = "libraryContainerChanged"
    const val UP_VIDEO_INFO = "upVideoInfo"
    const val AUTO_TASK_SERVICE = "autoTaskService"
    const val REVIEW_CACHE_SAVED = "reviewCacheSaved"
    const val LOCAL_NETWORK_PERMISSION_REQUIRED = "localNetworkPermissionRequired"

    /**
     * 页面布局（发现页布局 / 订阅列数 / 首页布局）在「页面布局」设置页被改动。
     * payload 是发生变化的 PreferKey；订阅页与首页收到后按 AppConfig 当前值重排，
     * 避免「设置页改了、切回 Tab 仍是旧布局」。
     *
     * 发布方：`PageLayoutConfigFragment.onSettingPreferenceChanged`（`UP_PAGE_LAYOUT` + `NOTIFY_MAIN` 双发）。
     * 订阅方：`RssFragment.observeLiveBus`（重排订阅列数）、`HomepageFragment.observeLiveBus`（重读首页布局）、
     * `ExploreFragment`（发现页布局联动，本轮补齐订阅/onResume 双保险）。
     * payload：`String`（发生变化的 PreferKey）。
     */
    const val UP_PAGE_LAYOUT = "upPageLayout"
}
