package io.legado.app.help.config

import android.content.Context
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.theme.palette.PaletteRole
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.theme.pack.resolvePalette
import io.legado.app.uikit.theme.Applicator

/**
 * P4-1 V5 主题引擎 ↔ 旧栈 的单向同步（新 → 旧）。
 *
 * 背景：搬迁期（P4）旧 View 体系的页面（含"我的"页复用的 MySettingsScreen、
 * 大量未搬迁的旧 Activity）通过 UiKitBridge → AppUiColors 消费颜色，
 * 而窗口底色/状态栏消费 ThemeStore。V5 包库成为主题唯一真相后，
 * 用这个同步器把 V5 的 active 包下推给旧栈，保证全 App 观感一致。
 *
 * 方向只有"新 → 旧"：主题切换的入口已归 V5 包库（管理中心）；
 * 旧壳的旧主题管理页属 D 级删除目标（P4-5），不做反向同步。
 * 防重入：旧栈写回不触发任何 V5 调用，天然无环；syncing 标志防御未来改动。
 */
object V5ThemeSync {

    @Volatile
    private var syncing = false

    /** 把 V5 的 active 主题包推给旧栈（View 内容色 + runtime 记账 + 窗口色）。 */
    fun pushToLegacy(context: Context, spec: ThemePackageSpec) {
        if (syncing) return
        if (!spec.isValid()) return
        syncing = true
        try {
            val app = context.applicationContext
            val active = spec

            // ① 旧 Applicator：所有旧 View 的内容色（UiKitBridge 之外的直接消费者）
            runCatching { Applicator.applyTheme(active) }
            // ② 旧 runtime 记账：旧壳重启时恢复到同一个包
            runCatching { ThemePackageStore.persistActive(app) }
            // ③ 窗口底色 / 状态栏（ThemeStore 消费者）
            runCatching {
                val palette = active.resolvePalette(dark = AppConfig.isNightTheme)
                val primary = palette[PaletteRole.PRIMARY] ?: return@runCatching
                val accent = palette[PaletteRole.TERTIARY] ?: primary
                val bg = palette[PaletteRole.BACKGROUND] ?: primary
                ThemeStore.editTheme(app)
                    .primaryColor(primary)
                    .accentColor(accent)
                    .backgroundColor(bg)
                    .apply()
            }
        } finally {
            syncing = false
        }
    }
}
