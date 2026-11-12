package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadBook
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.glass.GlassSurface
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.resolvedExploreGlassMode
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.lib.theme.dialogSurfaceColor
import io.legado.app.lib.theme.surface.SurfaceCorners
import io.legado.app.lib.theme.surface.SurfaceDrawable
import io.legado.app.lib.theme.surface.SurfaceStyle
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.removePref

/**
 * 本面板该不该铺「当前读书界面」的磨砂。**外层面板与内层分区卡必须同源**，所以抽成文件级函数。
 *
 * 四条否决与全 App 玻璃的降级矩阵对齐：
 * - 无可用背景图：`ThemeConfig.hasUsableBgImage`（读 `bgImage` / `bgImageN` 偏好并校验文件，
 *   也就是 `rememberHasUsableBgImage` 与 `BaseActivity.showsWallpaperBehindContent` 用的同一个
 *   判定）→ 用户此刻整个 UI 都没有壁纸/玻璃语义，本面板也不该是全场唯一的磨砂面；
 * - solid 档 / 墨水屏 / 低内存：`GlassSurface.wallpaperGlassEnabled`。
 *
 * 任一条命中都回 `dialogSurfaceBackground` 实底，逐值回到改动前。
 *
 * **不能改用 `GlassSurface.pageGlassAllowed`**：它含 `showsWallpaperBehindContent`，而阅读页
 * 正是被声明为 false 的那一类——本面板的底图是正文而不是壁纸，判定必须独立成立。
 * 同页弹层（`ModernActionPopup`）走 AUTO 落内容域是对的，因为它是同窗口覆盖视图，
 * 与本面板的独立 Dialog 窗口不是一回事。
 */
private fun isReaderPanelFrosted(context: Context): Boolean =
    ThemeConfig.hasUsableBgImage(context) && GlassSurface.wallpaperGlassEnabled(context)

class MoreConfigDialog : BasePrefDialogFragment() {
    private val readPreferTag = "readPreferenceFragment"

    /**
     * 本次面板截出的正文底图（截图 + 软件模糊路径的唯一产物）。持有引用唯一目的是在
     * `onDestroyView` 里显式回收——`SurfaceDrawable` 内部有 `isRecycled` 守卫
     * （`SurfaceDrawable.kt:46`），所以即便退出动画期间位图已被回收也只是不画底图，不会崩。
     */
    private var panelBackdropBitmap: Bitmap? = null

    /**
     * 本次面板**实际**铺了磨砂表面（正文位图拿到且未回退实底）。
     *
     * 与 [isReaderPanelFrosted] 的区别是「判定」与「事实」：判定回答「用户想不想要磨砂」，
     * 事实回答「这一次到底铺了什么」。两者在降级路径上会分叉——判定为真但正文位图截失败
     * （视图尚未布局 / OOM / 模糊返回 null）时，事实为假：面板落 [dialogSurfaceBackground]
     * 不透明实底。此时分区卡若仍按判定改薄底，就得到「不透明底色上盖一排 45% 的行」，
     * 比改动前更灰——且用户什么都没改、只赶上一次截图失败就会随机出现。所以分区卡只认
     * 这个**事实位**，不重算判定。
     *
     * `onCreateView` 先于 `onViewCreated`，子 Fragment 的组合必然晚于它，因此本字段
     * 在分区卡读取时一定已是本次的值。
     */
    private var panelSurfaceFrosted = false

    override fun onStart() {
        super.onStart()
        // 窗口只负责几何：磨砂表面完全由 onCreateView 里的 readerPanelBackground() 铺，
        // 窗口本体保持全透明（不再挂系统级背景模糊，理由见 applyReaderBottomSheetWindow）。
        dialog?.window?.applyReaderBottomSheetWindow(height = panelHeightPx())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        (activity as ReadBookActivity).bottomDialog++
        return FrameLayout(requireContext()).apply {
            background = readerPanelBackground()
            clipChildren = true
            clipToPadding = true
            clipToOutline = true
            id = R.id.tag1
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        panelBackdropBitmap?.recycle()
        panelBackdropBitmap = null
    }

    /**
     * 面板高度：屏幕高 × [PANEL_HEIGHT_FRACTION]，夹在 [PANEL_MIN_HEIGHT_DP]..[PANEL_MAX_HEIGHT_DP]。
     *
     * 抽成函数的理由是两个调用点**必须取同一个值**：`onStart` 用它设窗口高度，
     * `onCreateView` 用它决定底图要截「屏幕底部多高的一段」。两处各算一遍就会出现
     * 底图取样区间与面板实际高度不一致，底图被 center-crop 二次裁切、只显示出半屏正文。
     */
    private fun panelHeightPx(): Int = minOf(
        (resources.displayMetrics.heightPixels * PANEL_HEIGHT_FRACTION).toInt(),
        PANEL_MAX_HEIGHT_DP.dpToPx()
    ).coerceAtLeast(PANEL_MIN_HEIGHT_DP.dpToPx())

    /**
     * 面板表面：**当前读书界面**的磨砂，唯一实现是「打开瞬间截一张正文 → 降采样 →
     * 软件模糊 → 当 [SurfaceDrawable] 的 backdrop」。降级时逐值回落
     * [dialogSurfaceBackground]（同一份 drawable、同一个颜色、同一个圆角）。
     *
     * 为什么不再用系统级背景模糊（`FLAG_BLUR_BEHIND` + `setBackgroundBlurRadius`）：
     * `isCrossWindowBlurEnabled()` 在定制 ROM（真机为小米 HyperOS/MIUI）上返回 true，但
     * 窗口模糊本身并不生效，于是表面只剩一层半透明着色 —— 真机现象正是「面板半透明、
     * 背后正文完全清晰、一点不糊」。Android 没有任何 API 能回读「窗口模糊是否真的生效」，
     * 这条路径在运行期不可验证，误判代价比实底更差，因此整条删除。现在糊不糊只取决于
     * 我们自己产出的位图。
     *
     * 为什么不用内容域液态玻璃（`StableLiquidGlassView`）：它按**兄弟子树**录 `RenderNode`
     * 取实时采样源，要求采样源与宿主处于同一个窗口。本面板是独立 Dialog 窗口，拿不到
     * Activity 窗口里的 `read_view`，这条路径在本场景结构上不可用。
     *
     * 为什么不用 [GlassSurface.dialogBackground]（壁纸域）：它采的是 App 壁纸位图，
     * 与「面板压着的那块内容」无关——真机反馈正是「面板里透出的是 app 壁纸的模糊版」。
     */
    private fun readerPanelBackground(): Drawable {
        val context = requireContext()
        // 降级口径与全 App 玻璃一致：无背景图 / solid 档 / 墨水屏 / 低内存 → 今天的实底，
        // 逐值回到改动前（同一份 dialogSurfaceBackground）。
        if (!isReaderPanelFrosted(context)) {
            panelSurfaceFrosted = false
            return context.dialogSurfaceBackground
        }
        val tint = ColorUtils.withAlpha(readerPanelTintColor(context), PANEL_TINT_ALPHA)
        val radius = UiCorner.panelRadius(context)
        val backdrop = readerContentBackdrop()
        if (backdrop == null) {
            // 判定为真要磨砂，但截图/模糊这步失败了。事实位必须同步落回 false，
            // 否则分区卡会按判定改薄底，压在这块不透明实底上。
            panelSurfaceFrosted = false
            return context.dialogSurfaceBackground
        }
        panelBackdropBitmap = backdrop
        panelSurfaceFrosted = true
        return SurfaceDrawable(
            backdrop = backdrop,
            style = SurfaceStyle(
                tintColor = tint,
                cornerRadiusPx = radius,
                corners = SurfaceCorners.ALL
            )
        )
    }

    /**
     * 面板着色层的底色：**阅读页背景色**向本 App 的弹窗表面色混合。
     *
     * 为什么不能直接用阅读页背景色：面板里的 Compose 设置列表沿用 App 主题的前景色
     * （`rememberAppSettingPalette` 取 `titleTextColor` / `secondaryTextColor`），而「阅读主题」
     * 与「App 主题」不必一致（夜间阅读 + 日间 App 就是常见组合），纯取阅读色会出现深底深字。
     * 以 [READER_TINT_THEME_WEIGHT] 的权重压向弹窗表面色：面板带上「刚才那一页」的色调
     * （用户能认出这是当前读书界面），同时保住前景色对比度。
     *
     * 阅读页底色的取法与阅读页自己的底部菜单同源（`ReadMenu.rememberReadMenuStyle`）：只有
     * 「菜单跟随页面」且当前页是纯色底时才取页面色，否则回退主题底栏色 —— 背景图页面上
     * `curBgStr()` 是一个文件路径，拿它当颜色会得到噪声；`usesPageTemplate()` 的页面底色由
     * 模板绘制，`curBgStr()` 同样不代表眼前那一页。
     */
    private fun readerPanelTintColor(context: Context): Int {
        val readingBg = if (AppConfig.readBarStyleFollowPage &&
            !ReadBook.usesPageTemplate() &&
            ReadBookConfig.durConfig.curBgType() == 0
        ) {
            runCatching { ReadBookConfig.durConfig.curBgStr().toColorInt() }
                .getOrDefault(context.bottomBackground)
        } else {
            context.bottomBackground
        }
        return ColorUtils.blendColors(readingBg, context.dialogSurfaceColor, READER_TINT_THEME_WEIGHT)
    }

    /**
     * 当前正文的模糊位图（本面板唯一一条磨砂路径）；截图失败时返回 null，调用方回退实底。
     *
     * 降级门槛（无背景图 / solid / 墨水屏 / 低内存）已由 [isReaderPanelFrosted] 在入口处判过，
     * 这里不再重复。
     *
     * 磨砂强度跟随全局玻璃档位与强度（`GlassMaterialPreset.current(...).blurDp`），半径换算
     * 交给 [ExploreGlassBackdrop.exploreSoftwareBlurRadius]（内部已含降采样折算与 1..25 限幅）。
     */
    private fun readerContentBackdrop(): Bitmap? {
        val metrics = resources.displayMetrics
        val targetWidth = (metrics.widthPixels / BACKDROP_DOWNSCALE).toInt().coerceAtLeast(1)
        val targetHeight = (panelHeightPx() / BACKDROP_DOWNSCALE).toInt().coerceAtLeast(1)
        // 模糊半径与底栏 / 分区卡同一条口径（exploreSoftwareBlurRadius 内部有 ×1.4÷DOWNSCALE
        // 与 coerceIn(1,25)）。视觉半径 ≈ min(半径, 25) × 3 屏幕像素：磨砂档（20~32dp）恒顶到
        // 25（≈25dp 视觉），透明玻璃档（3~6dp）落在 9~18dp。
        // 不要在这里加人工下限——那会把两档抹平。
        // 用户把模糊滑杆拖到 0 时（blurDp=0 → 半径 0），captureReaderContentBackdrop 直接判否，
        // 面板回落实底，与全 App「模糊=0 时玻璃就是平的」一致；不要改成「交回未模糊快照」，
        // 那会得到「半透明 + 清晰正文」＝本缺陷同款观感。
        val blurRadius = ExploreGlassBackdrop.exploreSoftwareBlurRadius(
            GlassMaterialPreset.current(resolvedExploreGlassMode()).blurDp,
            metrics.density
        )
        return (activity as? ReadBookActivity)?.captureReaderContentBackdrop(
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            blurRadius = blurRadius
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readPreferTag)
            .commit()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
    }

    private companion object {

        /** 面板高度 = 屏幕高 × 本比例，再夹到 [PANEL_MIN_HEIGHT_DP]..[PANEL_MAX_HEIGHT_DP]。 */
        const val PANEL_HEIGHT_FRACTION = 0.68f

        /** 面板高度上限（dp），矮屏手机上避免面板吃满整页。 */
        const val PANEL_MAX_HEIGHT_DP = 520

        /** 面板高度下限（dp），保证首屏至少能看到几行设置项。 */
        const val PANEL_MIN_HEIGHT_DP = 360

        /**
         * 正文底图的降采样比例。
         *
         * **必须与 `ExploreGlassBackdrop` 内部的 `DOWNSCALE` 同值**：模糊半径由
         * [ExploreGlassBackdrop.exploreSoftwareBlurRadius] 按那个比例把屏幕像素折算成位图
         * 半径，两处比例不一致会让面板磨砂的糊度与底栏 / 设置分区卡对不上。
         * 取 1/3 同时把整张位图控制在 ~0.1M 像素量级，主线程截图 + 软件模糊不会造成卡顿。
         */
        const val BACKDROP_DOWNSCALE = 3f

        /**
         * 磨砂着色层的不透明度。
         *
         * 0.82 时底图只贡献 18% 的可见成分，真机上「糊了也看不出糊」（用户反馈「没有磨砂
         * 玻璃效果」——上一轮真机失败的直接原因之一）。0.74 把底图贡献提到 26%：磨砂的
         * 色块变化肉眼可辨，而面板前景仍来自 App 主题、14~16sp 的设置列表在 0.74 着色层
         * + 模糊底图上不会「字压字」。
         *
         * 上限不能再高（更透会让底下正文的笔画透出来），下限不建议再降（低于 0.7 时，在
         * 深色阅读背景上白色正文会与面板前景打架）。
         */
        const val PANEL_TINT_ALPHA = 0.74f

        /**
         * 着色层底色里「App 弹窗表面色」的权重（见 [readerPanelTintColor]）。
         *
         * 与 `ReaderSheetStyle.resolve` 对阅读页面板的配比同量级（夜间 0.64）：面板主体仍
         * 由 App 弹窗表面色决定（前景色是按它算的），阅读页背景色只贡献色调。
         */
        const val READER_TINT_THEME_WEIGHT = 0.68f
    }

    class ReadPreferenceFragment : ComposeSettingFragment() {

        private val slopSquare by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }

        override val titleRes: Int = R.string.setting

        override val applyActivityTitle: Boolean = false

        override val autoOpenTargetItem: Boolean = false

        override val drawPanelImage: Boolean = false

        /**
         * 阅读页不露 App 壁纸：面板背后是自绘正文。
         *
         * 取值是**带条件的**，这是「无背景图 / solid / 墨水屏 / 低内存逐值回到改动前」的关键：
         * - 面板这一次**真的**铺了磨砂（[MoreConfigDialog.panelSurfaceFrosted] 为真）→ 声明
         *   `false`，分区卡改用薄底（`CARD_FILL_YIELD_ALPHA`），透出面板底下的当前阅读内容；
         * - 面板这一次回落到不透明实底 → 声明 `null`，分区卡保持原来那个不透明 `colors.row`
         *   实底卡。若这里无条件声明 `false`，就会变成「半透明卡套在实底面板上」——卡面被
         *   实底衬成一块比改动前更暗的色块，属于改了不该改的观感。
         *
         * **认「事实」不认「判定」**：这里读宿主对话框实际铺了什么，而不是重算
         * [isReaderPanelFrosted]。后者只回答「用户想不想要磨砂」，在「正文位图截失败」
         * 那一档为真而事实为假，重算会让分区卡改薄底压在不透明实底上。
         */
        override val wallpaperGlassAvailable: Boolean?
            get() {
                // 用可空 `context` 而不是 `requireContext()`：搜索索引会实例化本 Fragment 并在
                // attach 之前读 spec，属性虽然只在 attach 后的组合里读，但别留一个会抛的取值路径。
                context ?: return null
                // 宿主对话框在 `wallpaperGlassAvailable` 被读之前一定已完成 attach（本 Fragment
                // 由它的 childFragmentManager 提交），但搜索索引那条路上 parentFragment 可能为空：
                // 取不到就按「未声明」处理，与改动前逐值相同。
                val host = parentFragment as? MoreConfigDialog ?: return null
                return if (host.panelSurfaceFrosted) false else null
            }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            if (!CanvasRecorderFactory.isSupport) {
                removePref(PreferKey.optimizeRender)
            }
        }

        override fun buildPageSpec(): SettingPageSpec {
            return SettingPageSpec(
                titleRes = titleRes,
                sections = listOf(
                    SettingSectionSpec(
                        items = listOf(
                            choice(
                                key = PreferKey.screenOrientation,
                                title = getString(R.string.screen_direction),
                                entriesRes = R.array.screen_direction_title,
                                valuesRes = R.array.screen_direction_value,
                                defaultValue = "0"
                            ),
                            choice(
                                key = PreferKey.keepLight,
                                title = getString(R.string.keep_light),
                                entriesRes = R.array.screen_time_out,
                                valuesRes = R.array.screen_time_out_value,
                                defaultValue = "0"
                            ),
                            switch(
                                key = PreferKey.hideStatusBar,
                                title = getString(R.string.pt_hide_status_bar),
                                defaultValue = false
                            ),
                            switch(
                                key = PreferKey.hideNavigationBar,
                                title = getString(R.string.pt_hide_navigation_bar),
                                defaultValue = false
                            ),
                            switch(
                                key = PreferKey.readBodyToLh,
                                title = getString(R.string.read_body_to_lh),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.paddingDisplayCutouts,
                                title = getString(R.string.padding_display_cutouts),
                                defaultValue = false
                            ),
                            choice(
                                key = PreferKey.doublePageHorizontal,
                                title = getString(R.string.double_page_horizontal),
                                entriesRes = R.array.double_page_title,
                                valuesRes = R.array.double_page_value,
                                defaultValue = "0"
                            ),
                            choice(
                                key = PreferKey.progressBarBehavior,
                                title = getString(R.string.progress_bar_behavior),
                                entriesRes = R.array.progress_bar_behavior_title,
                                valuesRes = R.array.progress_bar_behavior_value,
                                defaultValue = "page"
                            ),
                            switch(
                                key = PreferKey.useZhLayout,
                                title = getString(R.string.use_zh_layout),
                                defaultValue = false
                            ),
                            switch(
                                key = ReadBookConfig.layoutPreferenceKey(PreferKey.textFullJustify),
                                title = getString(R.string.text_full_justify),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.textBottomJustify,
                                title = getString(R.string.text_bottom_justify),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.adaptSpecialStyle,
                                title = getString(R.string.adapt_special_style),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.mouseWheelPage,
                                title = getString(R.string.mouse_wheel_page),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.volumeKeyPage,
                                title = getString(R.string.volume_key_page),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.volumeKeyPageOnPlay,
                                title = getString(R.string.volume_key_page_on_play),
                                defaultValue = false
                            ),
                            switch(
                                key = PreferKey.keyPageOnLongPress,
                                title = getString(R.string.key_page_on_long_press),
                                defaultValue = false
                            ),
                            SettingActionSpec(
                                key = PreferKey.pageTouchSlop,
                                title = getString(R.string.page_touch_slop_title),
                                summary = getString(R.string.page_touch_slop_summary, slopSquare.toString()),
                                onClick = ::showPageTouchSlopDialog
                            ),
                            SettingActionSpec(
                                key = PreferKey.pageTouchClick,
                                title = getString(R.string.page_touch_click_title),
                                summary = getString(R.string.page_touch_click_summary),
                                onClick = ::showPageTouchClickDialog
                            ),
                            SettingActionSpec(
                                key = PreferKey.readMenuAlpha,
                                title = getString(R.string.read_menu_alpha),
                                summary = getString(R.string.ui_layout_alpha_value, AppConfig.readMenuAlpha),
                                onClick = ::showReadMenuAlphaDialog
                            ),
                            switch(
                                key = PreferKey.autoChangeSource,
                                title = getString(R.string.auto_change_source),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.textSelectAble,
                                title = getString(R.string.selectText),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.showBrightnessView,
                                title = getString(R.string.show_brightness_view),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.noAnimScrollPage,
                                title = getString(R.string.no_anim_scroll_page),
                                defaultValue = false
                            ),
                            choice(
                                key = PreferKey.clickImgWay,
                                title = getString(R.string.click_image_way),
                                entriesRes = R.array.click_image_way_title,
                                valuesRes = R.array.click_image_way_value,
                                defaultValue = "0"
                            ),
                            switch(
                                key = PreferKey.optimizeRender,
                                title = getString(R.string.enable_optimize_render),
                                defaultValue = true,
                                visible = CanvasRecorderFactory.isSupport
                            ),
                            SettingActionSpec(
                                key = KEY_CLICK_REGIONAL_CONFIG,
                                title = getString(R.string.click_regional_config),
                                onClick = {
                                    (activity as? ReadBookActivity)?.showClickRegionalConfig()
                                }
                            ),
                            switch(
                                key = KEY_DISABLE_RETURN_KEY,
                                title = getString(R.string.disable_return_key),
                                defaultValue = false
                            ),
                            SettingActionSpec(
                                key = KEY_CUSTOM_PAGE_KEY,
                                title = getString(R.string.custom_page_key),
                                onClick = {
                                    PageKeyDialog(requireContext()).show()
                                }
                            ),
                            switch(
                                key = PreferKey.expandTextMenu,
                                title = getString(R.string.expand_text_menu),
                                defaultValue = false
                            ),
                            SettingActionSpec(
                                key = PreferKey.contentSelectMenuConfig,
                                title = getString(R.string.content_select_menu_config),
                                summary = getString(R.string.content_select_menu_config_summary),
                                onClick = {
                                    ContentSelectMenuConfigDialog()
                                        .show(parentFragmentManager, "contentSelectMenuConfig")
                                }
                            ),
                            switch(
                                key = PreferKey.showReadTitleAddition,
                                title = getString(R.string.show_read_title_addition),
                                defaultValue = true
                            )
                        ).filterNot { ReadBookConfig.usingEpubLayout && it.key in setOf(
                            PreferKey.doublePageHorizontal, PreferKey.useZhLayout, PreferKey.textBottomJustify,
                            PreferKey.adaptSpecialStyle, PreferKey.optimizeRender
                        ) }.filterNot { ReadBook.usesPageTemplate() && it.key in setOf(
                            PreferKey.textFullJustify, PreferKey.epubLayoutPrefix + PreferKey.textFullJustify,
                            PreferKey.showReadTitleAddition, PreferKey.paddingDisplayCutouts
                        ) }
                    )
                )
            )
        }

        override fun onSettingPreferenceChanged(key: String) {
            when (key) {
                PreferKey.readBodyToLh -> activity?.recreate()
                PreferKey.hideStatusBar -> {
                    ReadBookConfig.hideStatusBar = booleanSetting(PreferKey.hideStatusBar, false)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }

                PreferKey.hideNavigationBar -> {
                    ReadBookConfig.hideNavigationBar = booleanSetting(PreferKey.hideNavigationBar, false)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }

                PreferKey.keepLight -> postEvent(key, true)
                PreferKey.textSelectAble -> postEvent(key, booleanSetting(key, true))
                PreferKey.screenOrientation -> {
                    (activity as? ReadBookActivity)?.setOrientation()
                }

                PreferKey.textFullJustify,
                PreferKey.epubLayoutPrefix + PreferKey.textFullJustify,
                PreferKey.textBottomJustify,
                PreferKey.useZhLayout,
                PreferKey.adaptSpecialStyle-> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                }

                PreferKey.showBrightnessView -> {
                    postEvent(PreferKey.showBrightnessView, "")
                }

                PreferKey.expandTextMenu -> {
                    (activity as? ReadBookActivity)?.textActionMenu?.upMenu()
                }
                PreferKey.contentSelectActions,
                PreferKey.contentSelectDefaultOpen -> {
                    (activity as? ReadBookActivity)?.textActionMenu?.upMenu()
                }

                PreferKey.doublePageHorizontal -> {
                    ChapterProvider.upLayout()
                    ReadBook.loadContent(false)
                }

                PreferKey.showReadTitleAddition,
                PreferKey.readMenuAlpha -> {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }

                PreferKey.progressBarBehavior -> {
                    postEvent(EventBus.UP_SEEK_BAR, true)
                }

                PreferKey.noAnimScrollPage -> {
                    ReadBook.callBack?.upPageAnim()
                }

                PreferKey.optimizeRender -> {
                    ChapterProvider.upStyle()
                    ReadBook.callBack?.upPageAnim(true)
                    ReadBook.loadContent(false)
                }

                PreferKey.paddingDisplayCutouts -> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                }
            }
        }

        private fun switch(
            key: String,
            title: String,
            defaultValue: Boolean,
            visible: Boolean = true
        ): SettingSwitchSpec {
            return SettingSwitchSpec(
                key = key,
                title = title,
                checked = booleanSetting(key, defaultValue),
                visible = visible,
                onCheckedChange = { updateBooleanSetting(key, it) }
            )
        }

        private fun choice(
            key: String,
            title: String,
            entriesRes: Int,
            valuesRes: Int,
            defaultValue: String
        ): SettingChoiceSpec {
            val options = choiceOptions(entriesRes, valuesRes)
            val selectedValue = stringSetting(key, defaultValue)
            return SettingChoiceSpec(
                key = key,
                title = title,
                summary = choiceLabel(options, selectedValue),
                options = options,
                selectedValue = selectedValue,
                onSelected = { updateStringSetting(key, it) }
            )
        }

        private fun choiceOptions(
            entriesRes: Int,
            valuesRes: Int
        ): List<SettingChoiceOption> {
            val entries = resources.getStringArray(entriesRes)
            val values = resources.getStringArray(valuesRes)
            return values.mapIndexed { index, value ->
                SettingChoiceOption(
                    value = value,
                    label = entries.getOrElse(index) { value }
                )
            }
        }

        private fun choiceLabel(
            options: List<SettingChoiceOption>,
            selectedValue: String
        ): String {
            return options.firstOrNull { it.value == selectedValue }
                ?.label
                ?.toString()
                ?: selectedValue
        }

        private fun showPageTouchSlopDialog() {
            showComposeNumberPickerDialog(
                title = getString(R.string.page_touch_slop_dialog_title),
                value = AppConfig.pageTouchSlop,
                minValue = 0,
                maxValue = 9999,
                onValue = {
                    AppConfig.pageTouchSlop = it
                    postEvent(EventBus.UP_CONFIG, arrayListOf(4))
                }
            )
        }

        private fun showPageTouchClickDialog() {
            showComposeNumberPickerDialog(
                title = getString(R.string.page_touch_click_dialog_title),
                value = AppConfig.pageTouchClick,
                minValue = 0,
                maxValue = 399,
                onValue = {
                    AppConfig.pageTouchClick = it
                    postEvent(EventBus.UP_CONFIG, arrayListOf(12))
                }
            )
        }

        private fun showReadMenuAlphaDialog() {
            showComposeNumberPickerDialog(
                title = getString(R.string.read_menu_alpha),
                value = AppConfig.readMenuAlpha,
                minValue = 35,
                maxValue = 100,
                customText = getString(R.string.btn_default_s),
                onCustom = {
                    AppConfig.readMenuAlpha = 100
                    refreshSettings()
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                },
                onValue = {
                    AppConfig.readMenuAlpha = it.coerceIn(35, 100)
                    refreshSettings()
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            )
        }

        companion object {
            private const val KEY_CLICK_REGIONAL_CONFIG = "clickRegionalConfig"
            private const val KEY_CUSTOM_PAGE_KEY = "customPageKey"
            private const val KEY_DISABLE_RETURN_KEY = PreferKey.disableReturnKey
        }
    }
}
