package io.legado.app.ui.config

/**
 * 二级设置页的标识。
 *
 * 命名一律是「<任务域>Config」，与之对一级的一级区块名一致，
 * 这样 [ConfigActivity.breadcrumbOf] 能反推出「从哪个一级分类进来」。
 * 新增常量必须同步两处：ConfigActivity 的 when（决定跳到哪个 Fragment）、
 * breadcrumbOf（决定顶栏路径提示）；另外别忘了在 SettingsSearchIndex 里登记一行，
 * 否则该页的设置项无法从「我的」页搜索到。
 */
object ConfigTag {

    const val READING_CONFIG = "readingConfig"
    const val NETWORK_CONFIG = "networkConfig"
    const val STORAGE_CONFIG = "storageConfig"
    const val ADVANCED_CONFIG = "advancedConfig"
    const val NETWORK_DNS_CONFIG = "networkDnsConfig"
    const val THEME_CONFIG = "themeConfig"
    const val BACKUP_CONFIG = "backupConfig"
    const val AI_CONFIG = "aiConfig"
    const val COVER_CONFIG = "coverConfig"
    const val WELCOME_CONFIG = "welcomeConfig"
    const val DISCOVERY_SUBSCRIPTION_CONFIG = "discoverySubscriptionConfig"
    const val COMMENT_CONFIG = "commentConfig"
    const val BOOKSHELF_CONFIG = "bookshelfConfig"
    const val AUDIO_CONFIG = "audioConfig"
    const val MANGA_VIDEO_CONFIG = "mangaVideoConfig"
    const val PAGE_LAYOUT_CONFIG = "pageLayoutConfig"
    const val EXPLORE_GLASS_CONFIG = "exploreGlassConfig"

}
