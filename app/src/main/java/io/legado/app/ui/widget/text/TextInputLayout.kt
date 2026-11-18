package io.legado.app.ui.widget.text

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.dpToPx

class TextInputLayout(context: Context, attrs: AttributeSet?) : TextInputLayout(context, attrs) {

    init {
        if (!isInEditMode) {
            defaultHintTextColor =
                Selector.colorBuild().setDefaultColor(ThemeStore.accentColor(context)).create()
        }
    }

}

/**
 * 给书源/订阅源编辑页与登录弹窗的输入栏接上主题边框线。
 *
 * 只在这三个消费点（书源编辑、订阅源编辑、书源登录弹窗）对 inflate 出的
 * [TextInputLayout] 显式调用，不改本类的 [init]——自定义 TextInputLayout 被替换规则、
 * 高亮规则、地址参数等大量页面复用，那里有各自既有样式，全局改会误伤。
 *
 * 采用 Material outlined box，描边色取主题包「边框线色」
 * [UiCorner.borderLineColor]，与卡片 / 弹窗 / 底栏同一取色口径。
 */
fun TextInputLayout.applyThemeOutlineBorder() {
    boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
    // valueOf 让聚焦 / 未聚焦 / 悬浮各状态都是同一份主题边框色，避免未聚焦时描边淡到看不见。
    setBoxStrokeColorStateList(ColorStateList.valueOf(UiCorner.borderLineColor(context)))
    boxStrokeWidth = 1.dpToPx()
    boxStrokeWidthFocused = 1.dpToPx()
}
