package io.legado.app.ui.host

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import io.legado.app.utils.setBackgroundKeepPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.windowSize
import splitties.systemservices.windowManager

/**
 * V5 弹窗基座（旧库退役专项 P0）。
 *
 * 与旧 [io.legado.app.ui.widget.compose.ComposeDialogFragment] 的窗口行为逐行同源
 * （主题/E-Ink 降级/重力/动画/尺寸 fraction+maxWidth/返回键/show 防重），
 * 差异仅一处：内容由 [HostV5ThemeRoot] 注入 V5 取值环境
 * （LocalVisualSpec/LocalSemanticColors/LocalHazeState 与全 App 同源），
 * 子类内容直接使用 uikit.v5 组件，不再依赖 LegadoComposeTheme 主题桥。
 *
 * 子类只需实现 [Content]，窗口差异通过 open val 覆写。
 * 迁移路径：70+ 个 ComposeDialogFragment 子类按「只换壳不换内容」逐批改继承本基座。
 */
internal abstract class V5DialogFragment : DialogFragment() {

    protected open val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Center
    protected open val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    protected open val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    protected open val widthFraction: Float? = null
    protected open val maxWidthDp: Int? = null
    protected open val dialogGravity: Int = Gravity.CENTER
    protected open val dialogWindowAnimations: Int = R.style.AnimDialogCenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, if (AppConfig.isEInkMode) 0 else dialogTheme)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            (this as? ComponentDialog)?.onBackPressedDispatcher?.addCallback(
                this@V5DialogFragment,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        handleDialogBack()
                    }
                }
            )
            setOnKeyListener { _, keyCode, event ->
                keyCode == KeyEvent.KEYCODE_BACK &&
                        event.action == KeyEvent.ACTION_UP &&
                        handleDialogBack()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { HostV5ThemeRoot { V5MaterialBridge { Content() } } }
        }
    }

    /**
     * M3 桥：把 V5 语义色映射到 MaterialTheme.colorScheme，
     * 让子类内容里的 MaterialTheme.typography/colorScheme 跟随当前主题包（含夜间）。
     */
    @Composable
    private fun V5MaterialBridge(content: @Composable () -> Unit) {
        val colors = LocalSemanticColors.current
        val m3 = remember(colors) {
            val base =
                if (colors.surface.luminance() < 0.5f) darkColorScheme() else lightColorScheme()
            base.copy(
                primary = colors.primary,
                onPrimary = colors.onPrimary,
                secondary = colors.secondary,
                surface = colors.surface,
                onSurface = colors.onSurface,
                surfaceVariant = colors.surfaceVariant,
                onSurfaceVariant = colors.onSurfaceVariant,
                outline = colors.outline,
                error = colors.error,
                background = colors.background,
                onBackground = colors.onSurface,
            )
        }
        MaterialTheme(colorScheme = m3, content = content)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val attr = window.attributes
            attr.gravity = dialogGravity
            if (AppConfig.isEInkMode) {
                attr.dimAmount = 0f
                attr.windowAnimations = 0
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            } else {
                attr.windowAnimations = dialogWindowAnimations
            }
            window.attributes = attr
            window.setBackgroundDrawableResource(R.color.transparent)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            if (AppConfig.isEInkMode) {
                window.decorView.setBackgroundKeepPadding(R.color.transparent)
            }
        }
        if (AppConfig.isEInkMode) {
            applyEInkDialogBorder()
        }
        val fraction = widthFraction
        val maxDp = maxWidthDp
        if (fraction != null || maxDp != null) {
            val dm = requireContext().windowManager.windowSize
            val target = (dm.widthPixels * (fraction ?: 1f)).toInt()
            val maxWidth = maxDp?.dpToPx() ?: target
            dialog?.window?.setLayout(minOf(target, maxWidth), dialogHeight)
        } else {
            setLayout(dialogWidth, dialogHeight)
        }
    }

    private fun handleDialogBack(): Boolean {
        if (isCancelable) {
            dismissAllowingStateLoss()
        }
        return true
    }

    private fun applyEInkDialogBorder() {
        val contentView = view ?: return
        when (dialogGravity) {
            Gravity.TOP -> contentView.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            Gravity.BOTTOM -> contentView.setBackgroundResource(R.drawable.bg_eink_border_top)
            else -> {
                val padding = 2.dpToPx()
                contentView.setPadding(padding, padding, padding, padding)
                contentView.setBackgroundResource(R.drawable.bg_eink_border_dialog)
            }
        }
    }

    override fun show(manager: androidx.fragment.app.FragmentManager, tag: String?) {
        kotlin.runCatching {
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            LogUtils.e("V5DialogFragment", "show failed: ${it.stackTraceToString()}")
        }
    }

    /** 弹窗内容（V5 组件），由基座包 HostV5ThemeRoot 注入取值环境。 */
    @Composable
    protected abstract fun Content()
}
