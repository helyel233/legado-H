package io.legado.app.base

import android.os.Bundle
import android.view.View
import androidx.annotation.LayoutRes
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.surface.SurfaceCorners
import io.legado.app.lib.theme.surface.SurfaceDrawable
import io.legado.app.lib.theme.surface.SurfaceStyles

/** Base contract for dialogs whose owned surface is attached to the bottom edge. */
abstract class BaseBottomSheetDialogFragment(
    @LayoutRes layoutId: Int,
    adaptationSoftKeyboard: Boolean = false
) : BaseDialogFragment(layoutId, adaptationSoftKeyboard) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!AppConfig.isEInkMode) {
            // 底部弹出面板：圆角只留在顶部，覆盖父类默认的四角弹窗表面
            val style = SurfaceStyles.dialog(requireContext(), SurfaceCorners.TOP)
            val surface = view?.findViewById<View>(R.id.vw_bg) ?: view
            surface?.background = SurfaceDrawable(null, style)
            surface?.clipToOutline = false
        }
    }
}
