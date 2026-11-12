package io.legado.app.ui.widget

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.annotation.MenuRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.GlassSurface
import io.legado.app.ui.glass.LiquidGlassSpec
import io.legado.app.ui.glass.applyLiquidGlassSpec
import io.legado.app.ui.glass.contentLiquidGlassSpec
import io.legado.app.ui.glass.overlayGlassFillYielded
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.resolvedExploreGlassMode
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.installViewTreeOwnersFrom
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx

object ModernActionPopup {

    private const val MIN_WIDTH_DP = 124
    private const val MAX_WIDTH_DP = 244
    private const val ROW_HEIGHT_DP = 44

    data class Action(
        val title: String,
        val description: String? = null,
        val iconName: String? = null,
        val checked: Boolean = false,
        val enabled: Boolean = true,
        val persistent: Boolean = false,  // true = 点击不关闭弹窗，就地更新状态
        val invoke: () -> Unit
    )

    class Handle internal constructor(
        private val visibleState: MutableState<Boolean>,
        private val overlay: ComposeView,
        private val host: ViewGroup,
        private val backCallback: OnBackPressedCallback?,
        private val anchor: View,
        private val anchorDetachListener: View.OnAttachStateChangeListener,
        private val hostDetachListener: View.OnAttachStateChangeListener
    ) {
        private var dismissed = false
        val isShowing: Boolean
            get() = !dismissed && overlay.parent != null

        fun dismiss() {
            if (dismissed) return
            dismissed = true
            visibleState.value = false
            backCallback?.remove()
            anchor.removeOnAttachStateChangeListener(anchorDetachListener)
            host.removeOnAttachStateChangeListener(hostDetachListener)
            val delay = if (AppConfig.isEInkMode) 0L else 170L
            overlay.postDelayed({
                if (overlay.parent === host) {
                    host.removeView(overlay)
                }
            }, delay)
        }
    }

    private data class AnchorSnapshot(
        val anchorLeft: Int,
        val anchorTop: Int,
        val anchorRight: Int,
        val anchorBottom: Int,
        val hostWidth: Int,
        val hostHeight: Int,
        val maxWidthPx: Int,
        val maxHeightPx: Int,
        val fallbackWidthPx: Int,
        val fallbackHeightPx: Int
    )

    /**
     * 弹层玻璃的采样相位。
     *
     * 默认值 [AUTO] 不是「另一个可选值」，而是**唯一的正确默认**：弹层必须与它所覆盖的
     * 那个页面用同一相，否则两种玻璃质感肉眼可辨（本类 1.28.1 真机缺陷的根因）。
     * 让调用点各记一次「我这页是壁纸页」正是项目反复踩的「口径靠自觉」的坑：
     * 22 个使用点漏一个就复发，新增使用点默认就错。所以判定收在
     * [resolvePopupGlass] 里，由 [GlassSurface.pageGlassAllowed] 按宿主页面自动回答。
     *
     * 显式值只留给「自动判定答不上来」的少数场景（例如将来浮在视频帧 / 图片上的弹层
     * 必须强制模糊内容），其余一律用 [AUTO]。
     */
    enum class GlassDomain {
        /**
         * 按宿主页面自动选相位：本页此刻露出 app 壁纸 → [WALLPAPER]，否则 → [CONTENT]。
         *
         * 判定口径就是 [GlassSurface.pageGlassAllowed]——它已经包含无玻璃档（solid）、
         * 无背景图、墨水屏、低内存与 `BaseActivity.showsWallpaperBehindContent`
         * 五条否决，与页面卡片 `GlassPanel(requireWallpaperHost = true)` 用的是同一个
         * 函数。因此阅读页 / 音频 / 视频 / 书籍详情（`showsWallpaperBehindContent =
         * false`）自然落到 [CONTENT]，壁纸列表页自然落到 [WALLPAPER]，不需要每个
         * 调用点各自声明。
         *
         * **前提（新增调用点必读）**：本判定回答的是「**宿主 Activity 这一页**是否露出
         * 壁纸」，不区分锚点所在的是哪个窗口。因此锚点必须落在**活动窗口**里（当前 22 个
         * 调用点全部如此）。若将来把弹层锚进**对话框 / BottomSheet 窗口**内的 ⋮，而该
         * Activity 又是壁纸页，[WALLPAPER] 会去采一张被对话框面板整块盖住的壁纸——
         * 正是 `GlassSamplingDomain.PANEL` 存在的理由。那种位置请显式传 [CONTENT]
         * （或让它仍走内容域相位）。
         */
        AUTO,

        /** 强制壁纸域；本页判否时弹层保持主题实底（不会退到内容域）。 */
        WALLPAPER,

        /** 强制内容域（采样弹层压着的页面内容），用于背后不是壁纸的宿主。 */
        CONTENT
    }

    /**
     * 弹层玻璃的两条相位（[GlassDomain] 解析后的落地形态）。
     *
     * - [Content]：内容域，`StableLiquidGlassView` 绑定 `android.R.id.content` 兄弟子树。
     *   采样「菜单此刻压着的页面内容」，用于**背后不是壁纸**的宿主（阅读页菜单、
     *   音频 / 视频 / 书籍详情浮层等），这些页面的卡片本身也不是壁纸玻璃，二者同源。
     * - [Wallpaper]：壁纸域，直接铺 [GlassSurface.panelBackground] 的同一份 drawable。
     *   用于**页面卡片本身就是壁纸玻璃**的宿主（`AppManagementCard` /
     *   `GlassSurfaceCard` 的所有列表页）。必须选这一相，否则弹层会去采样「页面卡片
     *   叠在壁纸上的合成结果」，与卡片自己的 `FrostedGlassDrawable` 是两条不同的渲染
     *   链路，真机上表现为弹层比卡片更糊、更白（内容里的文字与卡片边缘被一起糊掉，
     *   而卡片的玻璃只采样壁纸）。
     *
     * 两相共用 [GlassMaterialPreset.current] 的同一组材质参数（模糊 / 着色 / 色散 /
     * 折射），差别只在**采样源与渲染器**，不存在第二份参数表。
     */
    private sealed interface PopupGlass {

        /** 内容域：材质 + 兄弟子树采样源（见 [resolvePopupGlass]）。 */
        data class Content(
            val spec: LiquidGlassSpec,
            val source: ViewGroup
        ) : PopupGlass

        /** 壁纸域：与页面卡片同一份 drawable，圆角在组合期由 `style.panelRadius` 给出。 */
        data object Wallpaper : PopupGlass
    }

    /**
     * 解析弹层玻璃。返回 null 表示弹层保持主题实底 Surface（不铺玻璃）。
     *
     * **[GlassDomain.AUTO]**（默认）：先问 [GlassSurface.pageGlassAllowed]——与页面卡片
     * `GlassPanel(requireWallpaperHost = true)` **同一个函数**——判真走壁纸域、判假落到
     * 内容域。于是「卡片是玻璃的页面弹层必然玻璃、卡片实底的页面弹层必然实底或内容域」，
     * 口径只有一个来源，新增调用点不必各自声明。
     *
     * **[GlassDomain.WALLPAPER]**：同上判定，但判否时**直接返回 null**（保持实底），
     * 不退到内容域——显式要求壁纸域的调用方要的是「与卡片同一份 drawable」，不是
     * 「随便给点玻璃」。
     *
     * **[GlassDomain.CONTENT]**：采样源取 `android.R.id.content` 子树——覆盖视图挂在
     * `rootView` 上，与该子树是兄弟分支，既不是玻璃宿主的祖先，也不会把弹层自己录进
     * 采样。源不存在（锚点在独立 `PopupWindow` / 弹窗窗口里、或内容尚未布局）时返回
     * null。solid 档 / 墨水屏 / 低内存由 [contentLiquidGlassSpec] 内部
     * （`liquidGlassSpec` 的 `SOLID -> null` 与三条设备否决）判否，同样返回 null。
     *
     * 这张判定表一次性在 `show()` 时刻取值，与内容域分支里 `isLowRam` / `isEInk` 的
     * 既有取值方式一致；弹层生命周期只有数秒，不值得为它挂偏好监听。
     *
     * 判「壁纸域可用」后 [GlassSurface.panelBackground] 一定非空：这是该入口的既有契约
     * （`GlassSurface.isAvailable` 与 drawable 早退分支同源，见 GlassSurface.kt:194-204）。
     */
    private fun resolvePopupGlass(host: ViewGroup, domain: GlassDomain): PopupGlass? {
        val wallpaperRequested = when (domain) {
            GlassDomain.AUTO, GlassDomain.WALLPAPER -> true
            GlassDomain.CONTENT -> false
        }
        if (wallpaperRequested) {
            if (GlassSurface.pageGlassAllowed(host.context)) return PopupGlass.Wallpaper
            // AUTO 落到内容域继续判；显式要求壁纸域则保持主题实底。
            if (domain == GlassDomain.WALLPAPER) return null
        }
        val source = host.findViewById<ViewGroup>(android.R.id.content)?.takeIf {
            it !== host && it.isShown && it.width > 0 && it.height > 0
        } ?: return null
        val spec = contentLiquidGlassSpec(
            params = GlassMaterialPreset.current(resolvedExploreGlassMode()),
            density = host.resources.displayMetrics.density,
            sdkInt = Build.VERSION.SDK_INT,
            sourceAvailable = true,
            isLowRam = ExploreGlassBackdrop.isLowRamDevice(host.context),
            isEInk = AppConfig.isEInkMode
        ) ?: return null
        return PopupGlass.Content(spec, source)
    }

    /**
     * 以 [anchor] 为基准显示弹层。
     *
     * @param glassDomain 弹层玻璃的相位，见 [GlassDomain]。默认 [GlassDomain.AUTO]：
     *   由宿主页面自动决定（壁纸页 → 与卡片同一份 drawable，阅读页等内容宿主 → 模糊内容），
     *   调用点不必声明自己是哪一类页面。
     */
    fun show(
        anchor: View,
        actions: List<Action>,
        previousPopup: Handle? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8,
        glassDomain: GlassDomain = GlassDomain.AUTO
    ): Handle? {
        if (actions.isEmpty()) return previousPopup
        val host = anchor.rootView as? ViewGroup
            ?: anchor.activity?.window?.decorView as? ViewGroup
            ?: return previousPopup
        val snapshot = calculateAnchorSnapshot(anchor, host, actions, maxHeightRatio, bottomGapDp)
        previousPopup?.dismiss()
        val visibleState = mutableStateOf(false)
        var handle: Handle? = null
        val overlay = ComposeView(host.context).apply {
            // 唯一 id：compose retained store 以 (ViewModelStoreOwner, viewId) 为作用域，
            // 无 id 时所有 overlay 共享同一 store，快速切换会触发重复进入 composition 崩溃
            id = View.generateViewId()
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            installViewTreeOwnersFrom(anchor, host.context)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    handle?.dismiss()
                    true
                } else {
                    false
                }
            }
        }
        val anchorDetachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                handle?.dismiss()
            }
        }
        val hostDetachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                handle?.dismiss()
            }
        }
        anchor.addOnAttachStateChangeListener(anchorDetachListener)
        host.addOnAttachStateChangeListener(hostDetachListener)
        val backCallback = anchor.activity?.let { activity ->
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handle?.dismiss()
                }
            }.also { activity.onBackPressedDispatcher.addCallback(it) }
        }
        handle = Handle(
            visibleState = visibleState,
            overlay = overlay,
            host = host,
            backCallback = backCallback,
            anchor = anchor,
            anchorDetachListener = anchorDetachListener,
            hostDetachListener = hostDetachListener
        )
        val glass = resolvePopupGlass(host, glassDomain)
        overlay.setContent {
            ModernActionPopupOverlay(
                snapshot = snapshot,
                actions = actions,
                visible = visibleState.value,
                glass = glass,
                onDismiss = handle::dismiss
            )
        }
        host.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.requestFocus()
        overlay.post {
            visibleState.value = true
        }
        return handle
    }

    /**
     * 从菜单资源显示弹层。
     *
     * `glassDomain` 排在 `prepare` / `onClick` **之前**：`onClick` 必须保持在末位，
     * 否则现有的 `showFromMenu(...) { ... }` 尾随 lambda 写法（如
     * `BaseBookshelfFragment.showModernBookshelfMenu`）会绑到错误的形参上。
     * 调用方全部用 `prepare = ...` 具名传参，因此这个位置不会影响任何既有调用。
     */
    fun showFromMenu(
        anchor: View,
        @MenuRes menuRes: Int,
        previousPopup: Handle? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8,
        glassDomain: GlassDomain = GlassDomain.AUTO,
        prepare: (Menu.() -> Unit)? = null,
        onClick: (MenuItem) -> Boolean
    ): Handle? {
        val popupMenu = PopupMenu(anchor.context, anchor)
        popupMenu.inflate(menuRes)
        prepare?.invoke(popupMenu.menu)
        val actions = mutableListOf<Action>()
        for (index in 0 until popupMenu.menu.size()) {
            val item = popupMenu.menu.getItem(index)
            if (item.isVisible) {
                actions.add(
                    Action(
                        title = item.title.toString(),
                        checked = item.isChecked,
                        enabled = item.isEnabled
                    ) {
                        onClick(item)
                    }
                )
            }
        }
        return show(
            anchor = anchor,
            actions = actions,
            previousPopup = previousPopup,
            maxHeightRatio = maxHeightRatio,
            bottomGapDp = bottomGapDp,
            glassDomain = glassDomain
        )
    }

    /**
     * 多态重载：通过 Compose 坐标显示弹出菜单，不需要 View 锚点
     * @param context 上下文
     * @param anchorLeft 锚点左边界（相对于 host 的像素坐标）
     * @param anchorTop 锚点上边界
     * @param anchorRight 锚点右边界
     * @param anchorBottom 锚点下边界
     * @param actions 菜单项列表
     * @param glassDomain 弹层玻璃的相位，见 [GlassDomain]。默认 [GlassDomain.AUTO]。
     */
    fun show(
        context: Context,
        anchorLeft: Int,
        anchorTop: Int,
        anchorRight: Int,
        anchorBottom: Int,
        actions: List<Action>,
        previousPopup: Handle? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8,
        glassDomain: GlassDomain = GlassDomain.AUTO
    ): Handle? {
        if (actions.isEmpty()) return previousPopup
        val host = (context as? android.app.Activity)
            ?.window?.decorView as? ViewGroup
            ?: return previousPopup
        val snapshot = calculateAnchorSnapshotFromCoords(
            anchorLeft, anchorTop, anchorRight, anchorBottom,
            host, actions, maxHeightRatio, bottomGapDp
        )
        previousPopup?.dismiss()
        val visibleState = mutableStateOf(false)
        var handle: Handle? = null
        val overlay = ComposeView(context).apply {
            // 同上：唯一 id 避免与其它 overlay 共享 retained store 作用域
            id = View.generateViewId()
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            installViewTreeOwnersFrom(host, context)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    handle?.dismiss()
                    true
                } else {
                    false
                }
            }
        }
        val hostDetachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                handle?.dismiss()
            }
        }
        host.addOnAttachStateChangeListener(hostDetachListener)
        val backCallback = (context as? androidx.activity.ComponentActivity)?.let { activity ->
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handle?.dismiss()
                }
            }.also { activity.onBackPressedDispatcher.addCallback(it) }
        }
        // 创建一个不可见的锚点 View 用于生命周期管理
        val dummyAnchor = View(context)
        handle = Handle(
            visibleState = visibleState,
            overlay = overlay,
            host = host,
            backCallback = backCallback,
            anchor = dummyAnchor,
            anchorDetachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) = Unit
            },
            hostDetachListener = hostDetachListener
        )
        val glass = resolvePopupGlass(host, glassDomain)
        overlay.setContent {
            ModernActionPopupOverlay(
                snapshot = snapshot,
                actions = actions,
                visible = visibleState.value,
                glass = glass,
                onDismiss = handle::dismiss
            )
        }
        host.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.requestFocus()
        overlay.post {
            visibleState.value = true
        }
        return handle
    }

    /**
     * 多态重载：通过 Compose 坐标显示菜单（从菜单资源）
     */
    fun showFromMenu(
        context: Context,
        anchorLeft: Int,
        anchorTop: Int,
        anchorRight: Int,
        anchorBottom: Int,
        @MenuRes menuRes: Int,
        previousPopup: Handle? = null,
        maxHeightRatio: Float = 0.62f,
        bottomGapDp: Int = 8,
        glassDomain: GlassDomain = GlassDomain.AUTO,
        prepare: (Menu.() -> Unit)? = null,
        onClick: (MenuItem) -> Boolean
    ): Handle? {
        val popupMenu = PopupMenu(context, null)
        popupMenu.inflate(menuRes)
        prepare?.invoke(popupMenu.menu)
        val actions = mutableListOf<Action>()
        for (index in 0 until popupMenu.menu.size()) {
            val item = popupMenu.menu.getItem(index)
            if (item.isVisible) {
                actions.add(
                    Action(
                        title = item.title.toString(),
                        checked = item.isChecked,
                        enabled = item.isEnabled
                    ) {
                        onClick(item)
                    }
                )
            }
        }
        return show(
            context = context,
            anchorLeft = anchorLeft,
            anchorTop = anchorTop,
            anchorRight = anchorRight,
            anchorBottom = anchorBottom,
            actions = actions,
            previousPopup = previousPopup,
            maxHeightRatio = maxHeightRatio,
            bottomGapDp = bottomGapDp,
            glassDomain = glassDomain
        )
    }

    private fun calculateAnchorSnapshotFromCoords(
        anchorLeft: Int,
        anchorTop: Int,
        anchorRight: Int,
        anchorBottom: Int,
        host: ViewGroup,
        actions: List<Action>,
        maxHeightRatio: Float,
        bottomGapDp: Int
    ): AnchorSnapshot {
        val gap = 8.dpToPx()
        val bottomGap = bottomGapDp.dpToPx()
        val hostWidth = host.width.takeIf { it > 0 } ?: host.rootView.width
        val hostHeight = host.height.takeIf { it > 0 } ?: host.rootView.height
        val safeHostHeight = host.safePopupHostHeight(hostHeight)
        val belowSpace = safeHostHeight - anchorBottom - gap - bottomGap
        val aboveSpace = anchorTop - gap
        val usableHeight = (safeHostHeight - gap * 2 - bottomGap).coerceAtLeast(1)
        val minimumHeight = minOf(72.dpToPx(), usableHeight).coerceAtLeast(1)
        val anchorSpace = maxOf(belowSpace, aboveSpace).coerceIn(
            minOf(48.dpToPx(), usableHeight).coerceAtLeast(1),
            usableHeight
        )
        val ratio = maxHeightRatio.coerceIn(0.35f, 0.9f)
        val ratioHeight = (usableHeight * ratio).toInt().coerceAtLeast(minimumHeight)
        val maxHeight = minOf(anchorSpace, ratioHeight)
            .coerceAtLeast(minimumHeight)
            .coerceAtMost(usableHeight)
        val maxWidth = (hostWidth - gap * 2).coerceAtLeast(1)
        val fallbackWidth = estimatePanelWidthPx(actions, maxWidth)
        val rowHeight = ROW_HEIGHT_DP.dpToPx()
        val fallbackHeight = minOf(
            maxHeight,
            (actions.size * rowHeight + 12.dpToPx()).coerceAtLeast(minimumHeight)
        )
        return AnchorSnapshot(
            anchorLeft = anchorLeft,
            anchorTop = anchorTop,
            anchorRight = anchorRight,
            anchorBottom = anchorBottom,
            hostWidth = hostWidth,
            hostHeight = safeHostHeight,
            maxWidthPx = maxWidth,
            maxHeightPx = maxHeight,
            fallbackWidthPx = fallbackWidth,
            fallbackHeightPx = fallbackHeight
        )
    }

    @Composable
    private fun ModernActionPopupOverlay(
        snapshot: AnchorSnapshot,
        actions: List<Action>,
        visible: Boolean,
        glass: PopupGlass?,
        onDismiss: () -> Unit
    ) {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val panelShape = RoundedCornerShape(style.panelRadius)
        val context = LocalContext.current
        // 仅当主题确实设置了面板边框色才画边框；无边框主题不应再显描边线。
        val hasPanelBorder = UiCorner.panelBorderColor(context) != null
        // 跟踪 persistent 项的 checked 状态
        var checkedStates by remember { mutableStateOf(actions.map { it.checked }) }
        val density = LocalDensity.current
        val maxHeightDp = with(density) { snapshot.maxHeightPx.toDp() }
        var panelSize by remember { mutableStateOf(IntSize.Zero) }
        var panelBounds by remember { mutableStateOf<ComposeRect?>(null) }
        val panelWidth = panelSize.width.takeIf { it > 0 } ?: snapshot.fallbackWidthPx
        val panelHeight = panelSize.height.takeIf { it > 0 } ?: snapshot.fallbackHeightPx
        val panelOffset = calculatePanelOffset(snapshot, panelWidth, panelHeight)
        val panelTransformOrigin = calculateTransformOrigin(snapshot, panelOffset, panelWidth, panelHeight)
        // 玻璃层只在面板量到真实尺寸后出现；未测量前 Surface 保持实底，与阅读页菜单同一条纪律。
        val glassLayer = if (
            overlayGlassFillYielded(glass != null, panelSize.width, panelSize.height)
        ) {
            glass
        } else {
            null
        }
        val glassRadiusPx = with(density) { style.panelRadius.toPx() }
        val progress by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (AppConfig.isEInkMode) 0 else 150,
                easing = FastOutSlowInEasing
            ),
            label = "modernActionPopup"
        )
        // 玻璃层与面板共用同一套 offset / 尺寸 / 弹出动画，两层必须逐帧同形，
        // 否则会在缩放与淡入过程中出现玻璃与面板错位。两条玻璃相位复用同一个 Modifier。
        val panelLayerModifier = Modifier
            .offset { panelOffset }
            .width(with(density) { snapshot.fallbackWidthPx.toDp() })
            .height(with(density) { panelHeight.toDp() })
            .graphicsLayer {
                alpha = progress
                transformOrigin = panelTransformOrigin
                scaleX = 0.96f + 0.04f * progress
                scaleY = 0.96f + 0.04f * progress
                translationY = (if (panelOffset.y >= snapshot.anchorBottom) -6f else 6f) *
                    (1f - progress)
            }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(panelBounds) {
                    detectTapGestures { tap ->
                        val bounds = panelBounds
                        if (bounds == null || !bounds.contains(tap)) {
                            onDismiss()
                        }
                    }
                }
        ) {
            when (glassLayer) {
                is PopupGlass.Content -> AndroidView(
                    factory = { context ->
                        StableLiquidGlassView(context).apply {
                            isClickable = false
                            isFocusable = false
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            setCornerRadius(glassRadiusPx)
                            applyLiquidGlassSpec(glassLayer.spec)
                            bind(glassLayer.source)
                        }
                    },
                    modifier = panelLayerModifier
                )

                // 壁纸域：与页面卡片（AppManagementCard / GlassSurfaceCard）铺同一份
                // drawable、走同一个 GlassPositionTracker，因此同档位下弹层与卡片的
                // 模糊、着色、壳层与描边逐值相同——这正是「两种玻璃质感肉眼可辨」的修法。
                is PopupGlass.Wallpaper -> AndroidView(
                    factory = { context -> WallpaperGlassHost(context) },
                    update = { host -> host.bind(glassRadiusPx) },
                    modifier = panelLayerModifier
                )

                null -> Unit
            }
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
            ) {
                Surface(
                    modifier = Modifier
                        .offset { panelOffset }
                        .width(with(density) { snapshot.fallbackWidthPx.toDp() })
                        .heightIn(max = maxHeightDp)
                        .onSizeChanged { panelSize = it }
                        .onGloballyPositioned { panelBounds = it.boundsInRoot() }
                        .graphicsLayer {
                            alpha = progress
                            transformOrigin = panelTransformOrigin
                            scaleX = 0.96f + 0.04f * progress
                            scaleY = 0.96f + 0.04f * progress
                            translationY = (if (panelOffset.y >= snapshot.anchorBottom) -6f else 6f) *
                                (1f - progress)
                        }
                        // 边框放在 graphicsLayer 之后，随弹出动画一起淡入，不再先于动画整条显示。
                        .then(
                            if (hasPanelBorder) Modifier.border(1.dp, style.stroke, panelShape)
                            else Modifier
                        ),
                    shape = panelShape,
                    color = if (glassLayer != null) Color.Transparent else style.surface,
                    contentColor = style.primaryText,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = maxHeightDp)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(
                            items = actions,
                            key = { index, action -> "${action.title}#$index" }
                        ) { index, action ->
                            val isChecked = if (action.persistent) {
                                checkedStates.getOrElse(index) { action.checked }
                            } else {
                                action.checked
                            }
                            LegadoMiuixChoiceRow(
                                text = action.title,
                                selected = isChecked,
                                palette = palette,
                                onClick = {
                                    if (action.persistent) {
                                        // 不关闭弹窗，就地更新状态
                                        checkedStates = checkedStates.toMutableList().apply {
                                            set(index, !isChecked)
                                        }
                                        anchorPostAction(action.invoke)
                                    } else {
                                        onDismiss()
                                        anchorPostAction(action.invoke)
                                    }
                                },
                                minHeight = 42.dp,
                                compact = true,
                                showSelectedMark = action.checked || action.persistent,
                                enabled = action.enabled,
                                description = action.description,
                                leadingIconName = action.iconName,
                                textAlign = TextAlign.Start,
                                translucentSurface = glassLayer != null,
                                // 行边界不能只挂在「这一档恰好有玻璃」上：同一条菜单在不同
                                // 页面会因为能否采到玻璃源而在「有框 / 没框」之间跳变，
                                // 真机反馈就是「部分页面选项没有背景框」。浮层菜单一律描边。
                                outlined = true
                            )
                        }
                    }
                }
            }
        }
    }

    private fun anchorPostAction(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    private fun calculateAnchorSnapshot(
        anchor: View,
        host: ViewGroup,
        actions: List<Action>,
        maxHeightRatio: Float,
        bottomGapDp: Int
    ): AnchorSnapshot {
        val gap = 8.dpToPx()
        val bottomGap = bottomGapDp.dpToPx()
        val hostLocation = IntArray(2)
        val anchorLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        anchor.getLocationOnScreen(anchorLocation)
        val hostWidth = host.width.takeIf { it > 0 } ?: anchor.rootView.width
        val hostHeight = host.height.takeIf { it > 0 } ?: anchor.rootView.height
        val safeHostHeight = host.safePopupHostHeight(hostHeight)
        val anchorLeft = anchorLocation[0] - hostLocation[0]
        val anchorTop = anchorLocation[1] - hostLocation[1]
        val anchorRight = anchorLeft + anchor.width
        val anchorBottom = anchorTop + anchor.height
        val belowSpace = safeHostHeight - anchorBottom - gap - bottomGap
        val aboveSpace = anchorTop - gap
        val usableHeight = (safeHostHeight - gap * 2 - bottomGap).coerceAtLeast(1)
        val minimumHeight = minOf(72.dpToPx(), usableHeight).coerceAtLeast(1)
        val anchorSpace = maxOf(belowSpace, aboveSpace).coerceIn(
            minOf(48.dpToPx(), usableHeight).coerceAtLeast(1),
            usableHeight
        )
        val ratio = maxHeightRatio.coerceIn(0.35f, 0.9f)
        val ratioHeight = (usableHeight * ratio).toInt().coerceAtLeast(minimumHeight)
        val maxHeight = minOf(anchorSpace, ratioHeight)
            .coerceAtLeast(minimumHeight)
            .coerceAtMost(usableHeight)
        val maxWidth = (hostWidth - gap * 2).coerceAtLeast(1)
        val fallbackWidth = estimatePanelWidthPx(actions, maxWidth)
        val rowHeight = ROW_HEIGHT_DP.dpToPx()
        val fallbackHeight = minOf(
            maxHeight,
            (actions.size * rowHeight + 12.dpToPx()).coerceAtLeast(minimumHeight)
        )
        return AnchorSnapshot(
            anchorLeft = anchorLeft,
            anchorTop = anchorTop,
            anchorRight = anchorRight,
            anchorBottom = anchorBottom,
            hostWidth = hostWidth,
            hostHeight = safeHostHeight,
            maxWidthPx = maxWidth,
            maxHeightPx = maxHeight,
            fallbackWidthPx = fallbackWidth,
            fallbackHeightPx = fallbackHeight
        )
    }

    private fun calculatePanelOffset(
        snapshot: AnchorSnapshot,
        panelWidth: Int,
        panelHeight: Int
    ): IntOffset {
        val gap = 4.dpToPx()
        val desiredX = snapshot.anchorRight - panelWidth
        val x = desiredX.coerceIn(
            gap,
            (snapshot.hostWidth - panelWidth - gap).coerceAtLeast(gap)
        )
        val belowY = snapshot.anchorBottom + gap
        val aboveY = snapshot.anchorTop - panelHeight - gap
        val hasRoomBelow = belowY + panelHeight <= snapshot.hostHeight - gap
        val y = if (hasRoomBelow || aboveY < gap) {
            belowY
        } else {
            aboveY
        }.coerceIn(
            gap,
            (snapshot.hostHeight - panelHeight - gap).coerceAtLeast(gap)
        )
        return IntOffset(x, y)
    }

    private fun calculateTransformOrigin(
        snapshot: AnchorSnapshot,
        panelOffset: IntOffset,
        panelWidth: Int,
        panelHeight: Int
    ): TransformOrigin {
        val anchorCenterX = (snapshot.anchorLeft + snapshot.anchorRight) / 2f
        val anchorCenterY = (snapshot.anchorTop + snapshot.anchorBottom) / 2f
        val pivotX = ((anchorCenterX - panelOffset.x) / panelWidth.coerceAtLeast(1))
            .coerceIn(0f, 1f)
        val pivotY = ((anchorCenterY - panelOffset.y) / panelHeight.coerceAtLeast(1))
            .coerceIn(0f, 1f)
        return TransformOrigin(pivotX, pivotY)
    }

    private fun estimatePanelWidthPx(actions: List<Action>, maxWidthPx: Int): Int {
        val longest = actions.maxOfOrNull { it.title.length } ?: 0
        val estimatedDp = (56 + longest.coerceAtMost(14) * 13)
            .coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)
        return minOf(estimatedDp.dpToPx(), maxWidthPx).coerceAtLeast(1)
    }

    private fun View.safePopupHostHeight(hostHeight: Int): Int {
        val navigationBottom = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())
            ?.bottom ?: 0
        return (hostHeight - navigationBottom).coerceAtLeast(1)
    }

    /**
     * 壁纸域玻璃宿主（[PopupGlass.Wallpaper] 的落地形态）。
     *
     * 只负责把 [GlassSurface.panelBackground] 的 drawable 挂到自身背景上——与页面卡片
     * （`AppManagementCard` 的 `glassSurface` 分支 → `GlassPanel` → `GlassPanelHost`，
     * 与 `GlassSurfaceCard`）**完全同一条**入口，因此材质、四档降级、设备判定与描边
     * 不可能与卡片漂移；本类不携带任何观感参数。
     *
     * 不参与触摸与焦点：弹层的点击由上层 Compose 处理（点空白关闭），玻璃层若可点击
     * 会把面板外的手势吞掉。
     *
     * **采样对齐方式与卡片一致（`screenAligned = false`）**：本宿主挂在
     * `anchor.rootView` 下的覆盖视图里，与页面卡片同属活动窗口，根视图就是屏幕
     * （多窗口下是窗口），按根视图换算即可；`screenAligned = true` 只用于独立
     * `PopupWindow` / 对话框窗口（见 `ExploreGlassBackdrop.panelBackground`），
     * 在这里用反而会把整张壁纸缩进面板。
     *
     * **不做签名去重**：弹层生命周期只有数秒，且此时唯一会变的参数是圆角
     * （`style.panelRadius`，组合期一次定值）。卡片那套「签名 + 壁纸可用性」双键
     * 是为常驻复用 View 准备的，这里按圆角去重即可，避免在弹出动画的每一帧里
     * 重复读偏好。
     */
    private class WallpaperGlassHost(context: Context) : FrameLayout(context) {

        /** 已铺设的圆角（像素）；null 表示尚未铺过。 */
        private var boundRadiusPx: Float? = null

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun bind(cornerRadiusPx: Float) {
            if (boundRadiusPx == cornerRadiusPx) return
            boundRadiusPx = cornerRadiusPx
            // 判「可用」（resolvePopupGlass → GlassSurface.pageGlassAllowed）后这里一定
            // 非空，这是 GlassSurface 的既有契约；万一为 null，background 置空会得到一块
            // 透明面板，因此保留 nullable 语义并在此写明前提，而不是造第二份实底兜底。
            background = GlassSurface.panelBackground(
                this,
                GlassSamplingDomain.WALLPAPER,
                cornerRadiusPx
            )
        }
    }
}
