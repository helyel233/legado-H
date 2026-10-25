package io.legado.app.ui.config

/**
 * 二级设置页的标识。
 *
 * 命名一律是「<任务域>Config」，与之对一级的一级区块名一致，
 * 这样 [ConfigActivity.breadcrumbOf] 能反推出「从哪个一级分类进来」。
 * 新增常量必须同步三处：ConfigActivity 的 when、breadcrumbOf、MyFragment 的搜索索引映射。
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
    const val DISCOVERY_CONFIG = "discoveryConfig"
    const val SUBSCRIPTION_CONFIG = "subscriptionConfig"

}
