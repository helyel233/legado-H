package io.legado.app.help.config.appearance

import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.TopBarConfig

/**
 * 全局外观内置默认值单一定义（纯内存常量，零冷启动磁盘 IO）。
 */
object BuiltinAppearanceDefaults {
    const val BUILTIN_DEFAULT_KIT_ID = "builtin_default"
    const val DEFAULT_PACKAGE_DIR = TopBarConfig.DEFAULT_DIR_NAME
    const val DEFAULT_COVER_MODE = CoverCollectionManager.MODE_RANDOM
    const val DEFAULT_GLASS_MODE = "glass"
    const val DEFAULT_GLASS_LEVEL = 70
    const val DEFAULT_GLASS_SCALE = 100
    const val DEFAULT_WELCOME_SHOW_TIME = 0
    const val DEFAULT_WELCOME_SHOW_TEXT = true
    const val DEFAULT_WELCOME_SHOW_ICON = true
}
