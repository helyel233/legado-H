package io.legado.app.ui.config.compose

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt

abstract class ComposeSettingFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    @get:StringRes
    protected abstract val titleRes: Int

    protected open val applyActivityTitle: Boolean = true

    protected open val autoOpenTargetItem: Boolean = true

    protected open val drawPanelImage: Boolean = true

    private val refreshTick = mutableIntStateOf(0)
    private val scrollTargetKey = mutableStateOf<String?>(null)
    private var targetKeyHandled = false

    protected val prefs: SharedPreferences
        get() = requireContext().defaultSharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // spec 用 remember(tick) 缓存：滚动定位等其他 state 变化不再触发整页 spec 重建，
                // 只有偏好变化（refreshTick）时才重读 SP 重建
                val tick = refreshTick.intValue
                val page = remember(tick) { buildPageSpec() }
                LegadoComposeTheme {
                    SettingSpecScreen(
                        page = page,
                        scrollTargetKey = scrollTargetKey.value,
                        drawPanelImage = drawPanelImage,
                        onTargetReady = ::handleTargetReady,
                        onTargetMissing = ::consumeMissingTarget,
                        onItemClick = ::handleItemClick
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (applyActivityTitle) {
            activity?.setTitle(titleRes)
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
        if (applyActivityTitle) {
            activity?.setTitle(titleRes)
        }
        consumeTargetKey()
        refreshSettings()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        refreshSettings()
        if (key != null) {
            onSettingPreferenceChanged(key)
        }
    }

    protected abstract fun buildPageSpec(): SettingPageSpec

    protected open fun normalizeTargetKey(rawKey: String): String = rawKey

    protected open fun onSettingPreferenceChanged(key: String) = Unit

    protected fun refreshSettings() {
        refreshTick.intValue += 1
    }

    protected fun booleanSetting(
        key: String,
        defaultValue: Boolean
    ): Boolean {
        return requireContext().getPrefBoolean(key, defaultValue)
    }

    protected fun stringSetting(
        key: String,
        defaultValue: String
    ): String {
        return requireContext().getPrefString(key, defaultValue) ?: defaultValue
    }

    protected fun intSetting(
        key: String,
        defaultValue: Int
    ): Int {
        return requireContext().getPrefInt(key, defaultValue)
    }

    protected fun updateBooleanSetting(
        key: String,
        value: Boolean
    ) {
        requireContext().putPrefBoolean(key, value)
    }

    protected fun updateStringSetting(
        key: String,
        value: String
    ) {
        prefs.edit { putString(key, value) }
    }

    protected fun updateIntSetting(
        key: String,
        value: Int
    ) {
        requireContext().putPrefInt(key, value)
    }

    /**
     * 下面这组是各设置页共用的声明式写法（原来每个 Fragment 各抄一份 private 副本）。
     * 新增设置页直接继承即可，不用再复制一轮。
     */
    protected fun switch(
        key: String,
        title: String,
        summary: String? = null,
        defaultValue: Boolean,
        visible: Boolean = true
    ): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = key,
            title = title,
            summary = summary,
            checked = booleanSetting(key, defaultValue),
            visible = visible,
            onCheckedChange = { updateBooleanSetting(key, it) }
        )
    }

    protected fun choice(
        key: String,
        title: String,
        entriesRes: Int,
        valuesRes: Int,
        defaultValue: String,
        summary: String? = null
    ): SettingChoiceSpec {
        val options = choiceOptionList(entriesRes, valuesRes)
        val selectedValue = stringSetting(key, defaultValue)
        return SettingChoiceSpec(
            key = key,
            title = title,
            summary = summary ?: choiceDisplayLabel(options, selectedValue),
            options = options,
            selectedValue = selectedValue,
            onSelected = { updateStringSetting(key, it) }
        )
    }

    protected fun intChoice(
        key: String,
        title: String,
        entriesRes: Int,
        valuesRes: Int,
        defaultValue: Int,
        summary: String? = null
    ): SettingChoiceSpec {
        val options = choiceOptionList(entriesRes, valuesRes)
        val selectedValue = intSetting(key, defaultValue).toString()
        return SettingChoiceSpec(
            key = key,
            title = title,
            summary = summary ?: choiceDisplayLabel(options, selectedValue),
            options = options,
            selectedValue = selectedValue,
            onSelected = { updateIntSetting(key, it.toIntOrNull() ?: defaultValue) }
        )
    }

    /**
     * 注意这两个叫 *_list / *_label 而不是 choiceOptions / choiceLabel：
     * MoreConfigDialog、ReadAloudConfigDialog、BackupConfigFragment 里早已各有一份
     * 同名 private 实现，用通用名会触发 "hides member of supertype" 编译错误。
     */
    protected fun choiceOptionList(
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

    protected fun choiceDisplayLabel(
        options: List<SettingChoiceOption>,
        selectedValue: String
    ): String {
        return options.firstOrNull { it.value == selectedValue }
            ?.label
            ?.toString()
            ?: selectedValue
    }

    protected fun numberAction(
        key: String,
        title: String,
        summary: String,
        min: Int,
        max: Int,
        value: Int,
        visible: Boolean = true,
        onSelected: (Int) -> Unit
    ): SettingActionSpec {
        return SettingActionSpec(
            key = key,
            title = title,
            summary = summary,
            visible = visible,
            onClick = {
                showComposeNumberPickerDialog(
                    title = title,
                    value = value,
                    minValue = min,
                    maxValue = max,
                    onValue = onSelected
                )
            }
        )
    }

    private fun consumeTargetKey() {
        if (targetKeyHandled) return
        val rawTargetKey = activity?.intent?.getStringExtra("targetKey")?.trim().orEmpty()
        if (rawTargetKey.isBlank()) return
        scrollTargetKey.value = normalizeTargetKey(rawTargetKey)
    }

    private fun handleTargetReady(targetKey: String) {
        if (targetKeyHandled) return
        val item = findItem(targetKey) ?: return consumeMissingTarget()
        targetKeyHandled = true
        scrollTargetKey.value = null
        view?.post {
            if (autoOpenTargetItem) {
                handleItemClick(item)
            }
            activity?.intent?.removeExtra("targetKey")
        }
    }

    private fun consumeMissingTarget() {
        if (targetKeyHandled) return
        targetKeyHandled = true
        scrollTargetKey.value = null
        activity?.intent?.removeExtra("targetKey")
    }

    private fun findItem(targetKey: String): SettingItemSpec? {
        return buildPageSpec().sections
            .asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { it.visible && (it.key == targetKey || targetKey in it.searchKeys) }
    }

    private fun handleItemClick(item: SettingItemSpec) {
        if (!item.enabled) return
        when (item) {
            is SettingActionSpec -> item.onClick()
            is SettingSwitchSpec -> item.onCheckedChange(!item.checked)
            is SettingChoiceSpec -> showChoiceDialog(item)
            is SettingSliderSpec -> Unit
        }
    }

    private fun showChoiceDialog(item: SettingChoiceSpec) {
        showComposeChoiceListDialog(
            title = item.title,
            labels = item.options.map { it.label },
            selectedIndex = item.options.indexOfFirst { it.value == item.selectedValue },
            descriptions = item.options.map { it.description?.toString().orEmpty() },
            iconNames = item.options.map { it.iconName.orEmpty() },
            negativeText = getString(R.string.cancel),
            onSelected = { index ->
                item.options.getOrNull(index)?.value?.let(item.onSelected)
            }
        )
    }
}
