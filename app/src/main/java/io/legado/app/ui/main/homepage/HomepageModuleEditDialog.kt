package io.legado.app.ui.main.homepage

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogHomepageModuleEditBinding
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.domain.model.ModuleDef
import io.legado.app.utils.PopupMenuAction
import io.legado.app.utils.setLayout
import io.legado.app.utils.showPopupMenu
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 主页模块新建/编辑弹窗：
 * 默认只展示标题、样式选择（附样式说明）与分类摘要，高级规则（URL / 参数 JSON）按需折叠展开。
 */
class HomepageModuleEditDialog : BaseDialogFragment(R.layout.dialog_homepage_module_edit) {

    private val binding by viewBinding(DialogHomepageModuleEditBinding::bind)

    private val viewModel: HomepageViewModel
        get() = (parentFragment as HomepageModuleManageSheet).viewModel

    private var selectedType: String = HomepageModuleType.Grid.key

    private val typeEntries: List<HomepageModuleType>
        get() = HomepageModuleType.entries.filter { it != HomepageModuleType.Unknown }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window: Window ->
            window.attributes.gravity = Gravity.CENTER
            window.attributes.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val args = arguments ?: Bundle()
        val isCreate = args.getBoolean("isCreate")
        selectedType = args.getString("type")?.takeIf { it.isNotBlank() }
            ?: HomepageModuleType.Grid.key

        val rawUrl = args.getString("url") ?: ""
        val rawArgs = args.getString("args") ?: ""
        val kindTitles = HomepageModuleBuilder.parseKindTitles(rawArgs)

        binding.run {
            tvTitle.text = if (isCreate) {
                getString(R.string.homepage_add_custom_module)
            } else {
                getString(R.string.homepage_edit_module)
            }
            etTitle.setText(args.getString("title") ?: "")
            etUrl.setText(rawUrl)
            etArgs.setText(rawArgs)

            if (!kindTitles.isNullOrEmpty()) {
                tvCategoriesSummary.isVisible = true
                tvCategoriesSummary.text = getString(
                    R.string.homepage_selected_categories_label,
                    kindTitles.joinToString("、")
                )
            }

            // 手动新建（无预填分类与 URL）时默认展开高级区，其余场景默认折叠隐藏原始规则
            val openAdvancedByDefault = isCreate && rawUrl.isBlank() && rawArgs.isBlank()
            setAdvancedExpanded(openAdvancedByDefault)
            tvToggleAdvanced.setOnClickListener {
                setAdvancedExpanded(!llAdvancedContainer.isVisible)
            }

            upTypeLabel()
            llTypeField.setOnClickListener { fieldType ->
                fieldType.showPopupMenu(typeEntries.map { entry ->
                    val label = "${getString(entry.titleRes)} · ${
                        getString(HomepageModuleBuilder.styleDescriptionRes(entry))
                    }"
                    PopupMenuAction(label) {
                        selectedType = entry.key
                        upTypeLabel()
                    }
                })
            }

            tvCancel.setOnClickListener { dismiss() }
            tvOk.setOnClickListener { submit(args, isCreate) }
        }
    }

    private fun setAdvancedExpanded(expanded: Boolean) {
        binding.llAdvancedContainer.isVisible = expanded
        val arrow = if (expanded) "▴" else "▾"
        binding.tvToggleAdvanced.text = "${getString(R.string.homepage_advanced_options)} $arrow"
    }

    private fun upTypeLabel() {
        val entry = HomepageModuleType.entries.find { it.key == selectedType }
        if (entry == null || entry == HomepageModuleType.Unknown) {
            binding.tvTypeValue.text = selectedType
            binding.tvTypeDesc.isVisible = false
        } else {
            binding.tvTypeValue.text = getString(entry.titleRes)
            binding.tvTypeDesc.isVisible = true
            binding.tvTypeDesc.setText(HomepageModuleBuilder.styleDescriptionRes(entry))
        }
    }

    private fun submit(args: Bundle, isCreate: Boolean) {
        binding.run {
            val title = etTitle.text.toString().trim()
            val url = etUrl.text.toString().trim()
            val moduleArgs = etArgs.text.toString().trim().ifBlank { null }
            val layoutConfig = args.getString("layoutConfig")?.takeIf { it.isNotBlank() }
            val sourceUrl = args.getString("sourceUrl") ?: return
            val setId = args.getString("setId")
            val sourceName = args.getString("sourceName") ?: ""
            val sourceType = args.getString("sourceType") ?: "book"

            if (title.isBlank()) {
                requireContext().toastOnUi(getString(R.string.homepage_module_title))
                return
            }

            val def = ModuleDef(
                key = args.getString("moduleKey") ?: "",
                type = selectedType,
                title = title,
                args = moduleArgs,
                layoutConfig = layoutConfig,
                url = url.ifBlank { null },
                sourceUrl = sourceUrl,
            )
            if (isCreate) {
                if (sourceType == "rss") {
                    viewModel.addRssCustomModule(sourceUrl, setId, def, sourceName)
                } else {
                    viewModel.addCustomModule(sourceUrl, setId, def)
                }
            } else {
                viewModel.updateModule(args.getString("id") ?: "", def)
            }
            requireContext().toastOnUi(getString(R.string.homepage_module_added))
            dismiss()
        }
    }
}
