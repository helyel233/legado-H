package io.legado.app.ui.book.read.config

import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import io.legado.app.R
import io.legado.app.help.config.AppConfig

/**
 * 把窗口配置成阅读页的底部弹层。
 *
 * 只做「窗口几何 + 去遮罩 + 去窗口自带背景」这几件事，**不参与任何模糊**：
 * 面板的磨砂表面由调用方自己铺（阅读页「更多设置」走
 * `MoreConfigDialog.readerPanelBackground` 的截图 + 软件模糊路径），窗口本体始终是
 * 全透明的。
 *
 * 为什么不在这里挂系统级背景模糊（`FLAG_BLUR_BEHIND` + `setBackgroundBlurRadius`）：
 * 那条路径在定制 ROM（真机为小米 HyperOS/MIUI）上**普遍不生效**，而
 * `WindowManager.isCrossWindowBlurEnabled()` 仍然返回 true —— 判定为真、系统没糊，
 * 调用方于是只铺一层半透明着色，得到「半透明但零模糊」的表面。Android 没有任何 API
 * 能回读「窗口模糊是否真的生效」，所以这条路径在运行期不可验证，误判代价比实底更差。
 * 统一走应用内截图 + 软件模糊后，糊不糊只取决于我们自己的位图，与系统合成器无关。
 *
 * @param height 窗口高度（px），默认 `wrap_content`。
 */
fun Window.applyReaderBottomSheetWindow(
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT
) {
    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    setBackgroundDrawableResource(android.R.color.transparent)
    decorView.setPadding(0, 0, 0, 0)
    val attr = attributes
    attr.dimAmount = 0f
    attr.gravity = Gravity.BOTTOM
    attr.windowAnimations = if (AppConfig.isEInkMode) 0 else R.style.AnimDialogBottom
    attributes = attr
    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
}
