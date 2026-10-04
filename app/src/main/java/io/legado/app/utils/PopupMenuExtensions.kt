package io.legado.app.utils

import android.view.Menu
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.PopupMenu

/**
 * legado-H 简化版弹出菜单：以 androidx PopupMenu 实现 legadoC 的 actions 式调用。
 */
data class PopupMenuAction(
    val title: CharSequence,
    @param:DrawableRes val iconRes: Int? = null,
    val onClick: () -> Unit
)

fun View.showPopupMenu(actions: List<PopupMenuAction>): Boolean {
    if (actions.isEmpty()) return false
    val popup = PopupMenu(context, this)
    actions.forEachIndexed { index, action ->
        popup.menu.add(Menu.NONE, index, index, action.title).apply {
            action.iconRes?.let(::setIcon)
        }
    }
    popup.setOnMenuItemClickListener { item ->
        actions.getOrNull(item.itemId)?.onClick?.invoke()
        true
    }
    popup.show()
    return true
}
