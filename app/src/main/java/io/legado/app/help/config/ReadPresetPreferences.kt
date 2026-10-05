package io.legado.app.help.config

import com.google.gson.JsonObject
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

/**
 * 阅读预设携带的独立偏好（移植自 joestar817/legado_NG，白名单按本仓库裁剪）。
 *
 * 只允许显式列出的阅读设置随「仅本书」预设 capture/apply，
 * 不包含共用布局或应用配置；NG 的阅读浮窗与划词工具栏偏好属其专有体系，未纳入。
 */
internal object ReadPresetPreferences {
    private val booleans = linkedMapOf(
        PreferKey.hideStatusBar to false,
        PreferKey.readBodyToLh to true,
        PreferKey.paddingDisplayCutouts to false,
        PreferKey.showBrightnessView to true,
        PreferKey.showReadTitleAddition to true,
        PreferKey.readBarStyleFollowPage to false,
        PreferKey.mouseWheelPage to true,
        PreferKey.volumeKeyPage to true,
        PreferKey.volumeKeyPageOnPlay to true,
        PreferKey.keyPageOnLongPress to false,
        PreferKey.noAnimScrollPage to false,
        PreferKey.useZhLayout to false,
        PreferKey.textFullJustify to true,
        PreferKey.textBottomJustify to true,
        PreferKey.adaptSpecialStyle to true,
        PreferKey.autoChangeSource to true,
        PreferKey.textSelectAble to true,
    )
    private val strings = linkedMapOf(
        PreferKey.screenOrientation to "0",
        PreferKey.keepLight to "0",
        PreferKey.progressBarBehavior to "page",
        PreferKey.doublePageHorizontal to "0",
        PreferKey.clickImgWay to "0",
    )
    private val integers = linkedMapOf(
        PreferKey.clickActionTL to 2, PreferKey.clickActionTC to 2, PreferKey.clickActionTR to 1,
        PreferKey.clickActionML to 2, PreferKey.clickActionMC to 0, PreferKey.clickActionMR to 1,
        PreferKey.clickActionBL to 2, PreferKey.clickActionBC to 1, PreferKey.clickActionBR to 1,
    )
    private val allowedStrings = mapOf(
        PreferKey.screenOrientation to setOf("0", "1", "2", "3", "4", "5"),
        PreferKey.keepLight to setOf("0", "60", "300", "600", "-1"),
        PreferKey.progressBarBehavior to setOf("page", "chapter"),
        PreferKey.doublePageHorizontal to setOf("0", "1", "2", "3"),
        PreferKey.clickImgWay to setOf("0", "1", "2", "3", "4"),
    )

    val preferenceKeys: Set<String> get() = booleans.keys + strings.keys + integers.keys

    fun capture(): JsonObject = JsonObject().apply {
        booleans.forEach { (key, default) -> addProperty(key, appCtx.getPrefBoolean(key, default)) }
        strings.forEach { (key, default) -> addProperty(key, appCtx.getPrefString(key, default)) }
        integers.forEach { (key, default) -> addProperty(key, appCtx.getPrefInt(key, default)) }
    }

    fun validate(settings: JsonObject) {
        booleans.keys.forEach { key -> settings.get(key)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "阅读设置类型错误: $key" }
        } }
        strings.keys.forEach { key -> settings.get(key)?.let {
            require(
                it.isJsonPrimitive && it.asJsonPrimitive.isString &&
                    it.asString in allowedStrings.getValue(key)
            ) { "阅读设置取值错误: $key" }
        } }
        integers.keys.forEach { key -> settings.get(key)?.let {
            require(
                it.isJsonPrimitive && it.asJsonPrimitive.isNumber &&
                    it.asString.toIntOrNull()?.let { n -> n in 0..100 } == true
            ) { "阅读设置数值错误: $key" }
        } }
    }

    fun apply(settings: JsonObject) {
        validate(settings)
        booleans.keys.forEach { key -> settings.get(key)?.let { appCtx.putPrefBoolean(key, it.asBoolean) } }
        strings.keys.forEach { key -> settings.get(key)?.let { appCtx.putPrefString(key, it.asString) } }
        integers.keys.forEach { key -> settings.get(key)?.let { appCtx.putPrefInt(key, it.asInt) } }
        // 这几个是 ReadBookConfig 的内存缓存字段，写 pref 不会自动刷新
        ReadBookConfig.hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar, false)
        ReadBookConfig.readBodyToLh = appCtx.getPrefBoolean(PreferKey.readBodyToLh, true)
        ReadBookConfig.useZhLayout = appCtx.getPrefBoolean(PreferKey.useZhLayout, false)
    }
}
