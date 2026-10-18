package io.legado.app.ui.config

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.BookInfoComponentConfig
import io.legado.app.help.config.BookInfoComponentItem
import io.legado.app.help.config.BookInfoPageStyle
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BookInfoManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val styleState = mutableStateOf(BookInfoPageStyle.CLASSIC)
    private val componentsState = mutableStateOf<List<BookInfoComponentItem>>(emptyList())

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.book_info_manage)

        // Load initial state
        styleState.value = BookInfoComponentConfig.loadStyle()
        componentsState.value = BookInfoComponentConfig.load()

        // Hide legacy XML views (tab_bar, tv_summary, btn_add)
        binding.tabBar.visibility = android.view.View.GONE
        binding.tvSummary.visibility = android.view.View.GONE
        binding.btnAdd.visibility = android.view.View.GONE

        initComposeContent()
    }

    private fun initComposeContent() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                BookInfoManageScreen(
                    style = styleState.value,
                    components = componentsState.value,
                    onStyleChanged = ::onStyleChanged,
                    onComponentToggle = ::onComponentToggle,
                    onReset = ::onReset,
                    onMoveBy = ::onMoveItemBy
                )
            }
        }
        container.addView(composeView, index)
    }

    private fun onStyleChanged(style: BookInfoPageStyle) {
        BookInfoComponentConfig.saveStyle(style)
        styleState.value = style
    }

    private fun onComponentToggle(index: Int, checked: Boolean) {
        val current = componentsState.value.toMutableList()
        if (index !in current.indices) return
        if (!checked && current.count { it.enabled } <= 1) {
            toastOnUi(R.string.book_info_component_keep_one)
            return
        }
        current[index] = current[index].copy(enabled = checked)
        componentsState.value = current
        BookInfoComponentConfig.save(current)
    }

    private fun onReset() {
        BookInfoComponentConfig.reset()
        componentsState.value = BookInfoComponentConfig.load()
    }

    /**
     * 拖动排序：每次都从最新状态按条目身份解析索引，
     * 同一帧内连续多次移动也不会用到过期的列表快照。
     */
    private fun onMoveItemBy(item: BookInfoComponentItem, delta: Int) {
        val current = componentsState.value.toMutableList()
        val fromIndex = current.indexOfFirst { it.type == item.type }
        if (fromIndex < 0) return
        val toIndex = fromIndex + delta
        if (toIndex !in current.indices) return
        val moved = current.removeAt(fromIndex)
        current.add(toIndex, moved)
        componentsState.value = current
        BookInfoComponentConfig.save(current)
    }
}
