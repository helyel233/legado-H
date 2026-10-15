package io.legado.app.ui.host

import android.content.Intent
import android.os.Bundle
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.chrisbanes.haze.HazeState
import io.legado.app.R
import io.legado.app.help.AppBootstrap
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.ui.host.pack.V5PackageStore
import io.legado.app.ui.main.MainAiFloatingBallController
import io.legado.app.ui.main.ai.AiChatActivity
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.uikit.v5.resolve.LayoutResolver
import io.legado.app.uikit.v5.resolve.ThemeResolver
import io.legado.app.uikit.v5.runtime.LocalHazeState
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

/**
 * P1-1 新宿主骨架入口。
 *
 * 与已退役的旧 MainActivity（2141 行，兼宿主 / 导航 / 玻璃 / 侧栏 /
 * 指示器 / 启动编排 / 剪贴板导入）形成对照：**这个类本体不含任何绘制逻辑**，
 * 只做三件事 —— 边缘到边缘、提供取值环境、把组合根挂上去。
 *
 * P1 阶段它与旧 MainActivity 并存，通过 debug 入口进入；P4-5 旧壳退役后成为唯一主界面。
 */
class AppHostActivity : AppCompatActivity() {

    companion object {
        /** 外部跳书架（书架即新壳默认 Tab）：见 Context.openBookshelf()。 */
        fun openBookshelf(context: android.content.Context) {
            context.startActivity(
                android.content.Intent(context, AppHostActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    private var bootstrap: AppBootstrap? = null

    // ---- AI 悬浮球（旧壳拆出的控制器，本壳重接线）----
    private var aiFloatingBallController: MainAiFloatingBallController? = null
    private var aiBallContainer: FrameLayout? = null
    private var bottomNavigationInset = 0
    private val aiBallPulseInterpolator by lazy { AccelerateDecelerateInterpolator() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边缘到边缘：背景层画满全屏，避让由 HostInsets 统一处理
        enableEdgeToEdge()
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(
            ComposeView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setContent { HostRoot() }
            }
        )
        // 悬浮球容器：不拦截自身之外的触摸，球体由控制器内部处理拖拽/点击
        aiBallContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }.also { root.addView(it) }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            bottomNavigationInset =
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            insets
        }

        // P4-5a 转正：接管旧壳的应用级启动职责
        bootstrap = AppBootstrap(this).also { it.onCreated(savedInstanceState) }
    }

    override fun onResume() {
        super.onResume()
        bootstrap?.onResumed()
        updateAiFloatingBall()
    }

    override fun onPause() {
        bootstrap?.onPaused()
        super.onPause()
    }

    override fun onDestroy() {
        aiFloatingBallController?.cancel()
        aiFloatingBallController = null
        super.onDestroy()
    }

    private fun updateAiFloatingBall() {
        val container = aiBallContainer ?: return
        (aiFloatingBallController ?: MainAiFloatingBallController(
            context = this,
            container = container,
            touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            pulseInterpolator = aiBallPulseInterpolator,
            shellDrawable = { oval ->
                android.graphics.drawable.GradientDrawable().apply {
                    shape = if (oval) android.graphics.drawable.GradientDrawable.OVAL
                    else android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(
                        ColorUtils.withAlpha(
                            bottomBackground,
                            if (AppConfig.isEInkMode) 1f else 0.92f
                        )
                    )
                    setStroke(1.dpToPx(), 0x1F888888)
                }
            },
            iconSync = { ball -> syncAiFloatingBallIcon(ball) },
            visibleProbe = { AppConfig.aiAssistantEnabled },
            bottomInset = { bottomNavigationInset },
            openAiChat = { startActivity(Intent(this, AiChatActivity::class.java)) }
        ).also { aiFloatingBallController = it }).update()
    }

    private fun syncAiFloatingBallIcon(ball: View) {
        val imageView = (ball as? ViewGroup)?.getChildAt(0) as? ImageView ?: return
        val hasCustomSearchIcon =
            NavigationBarIconConfig.hasCurrentSingleIcon(NavigationBarIconConfig.EXTRA_SEARCH)
        imageView.setImageDrawable(
            NavigationBarIconConfig.currentSingleDrawable(
                this, NavigationBarIconConfig.EXTRA_SEARCH
            ) ?: ContextCompat.getDrawable(this, R.drawable.ic_search)
        )
        imageView.imageTintList = if (hasCustomSearchIcon) {
            null
        } else {
            android.content.res.ColorStateList.valueOf(this.accentColor)
        }
    }
}

/**
 * 组合根：把解析器输出与玻璃采样状态注入 CompositionLocal。
 *
 * **这里就是"换肤不重启"的全部秘密** —— `LayoutResolver.spec` 与
 * `ThemeResolver` 的内部状态都是 Compose `mutableStateOf`，在 setContent 里读一次
 * 即订阅；改任意参数触发整棵树重组，不需要 `recreate()`（旧体系 P1 起退役）。
 */
@Composable
private fun HostRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // P3-2：读双包库并应用 active 包（同步，json 很小）
    remember { V5PackageStore.init(context) }

    // P3-1：把主题包的字体（FontFamily）并入排版规格 —— 结构来自界面包，字体来自主题包
    val baseSpec = LayoutResolver.spec
    val visual = remember(baseSpec, ThemeResolver.revision) {
        baseSpec.copy(
            type = baseSpec.type.copy(
                uiFont = ThemeResolver.uiFontFamily(),
                titleFont = ThemeResolver.titleFontFamily(),
            )
        )
    }
    val colors = ThemeResolver.colors()
    val hazeState = remember { HazeState() }

    // 进程死亡恢复：记住离开时所在的顶层 Tab（二级页不恢复，回 Tab 根；ledger #15）
    val savedTabId = rememberSaveable { mutableStateOf("shelf") }
    val state = remember { HostState(HostDestinations.registry(), savedTabId.value) }
    LaunchedEffect(state.currentId) {
        if (state.registry.byId(state.currentId)?.topLevel == true) {
            savedTabId.value = state.currentId
        }
    }
    val overlay = remember { HostOverlay() }

    CompositionLocalProvider(
        LocalVisualSpec provides visual,
        LocalSemanticColors provides colors,
        LocalHazeState provides hazeState,
        // 页面统一经 LocalHostOverlay 请求 snackbar（带 action），不再各自摸弹层
        LocalHostOverlay provides overlay,
    ) {
        HostScaffold(state = state, overlay = overlay, hazeState = hazeState)
    }
}
