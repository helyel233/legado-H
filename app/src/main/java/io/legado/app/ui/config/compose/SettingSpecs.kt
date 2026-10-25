package io.legado.app.ui.config.compose

import androidx.annotation.StringRes

data class SettingPageSpec(
    @param:StringRes val titleRes: Int,
    val sections: List<SettingSectionSpec>
)

data class SettingSectionSpec(
    val title: CharSequence? = null,
    val items: List<SettingItemSpec>,
    /**
     * 分区标题可点击折叠。给「一次用不到但又必须存在」的进阶分区用，
     * 避免普通用户被几十项高级参数淹没。
     */
    val collapsible: Boolean = false,
    val collapsedByDefault: Boolean = false
)

sealed interface SettingItemSpec {
    val key: String
    val title: CharSequence
    val summary: CharSequence?
    val visible: Boolean
    val enabled: Boolean
    val searchKeys: List<String>
}

data class SettingSwitchSpec(
    override val key: String,
    override val title: CharSequence,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
    override val summary: CharSequence? = null,
    override val visible: Boolean = true,
    override val enabled: Boolean = true,
    override val searchKeys: List<String> = emptyList()
) : SettingItemSpec

data class SettingChoiceOption(
    val value: String,
    val label: CharSequence,
    val description: CharSequence? = null,
    val iconName: String? = null
)

data class SettingChoiceSpec(
    override val key: String,
    override val title: CharSequence,
    val options: List<SettingChoiceOption>,
    val selectedValue: String,
    val onSelected: (String) -> Unit,
    override val summary: CharSequence? = null,
    override val visible: Boolean = true,
    override val enabled: Boolean = true,
    override val searchKeys: List<String> = emptyList()
) : SettingItemSpec {
    val selectedOption: SettingChoiceOption?
        get() = options.firstOrNull { it.value == selectedValue }

    val selectedLabel: CharSequence
        get() = selectedOption?.label ?: selectedValue
}

data class SettingSliderSpec(
    override val key: String,
    override val title: CharSequence,
    val value: Int,
    val valueRange: IntRange,
    val onValueChange: (Int) -> Unit,
    val step: Int = 1,
    val valueFormatter: (Int) -> String = { it.toString() },
    val onValueChangeFinished: (() -> Unit)? = null,
    override val summary: CharSequence? = null,
    override val visible: Boolean = true,
    override val enabled: Boolean = true,
    override val searchKeys: List<String> = emptyList()
) : SettingItemSpec

data class SettingActionSpec(
    override val key: String,
    override val title: CharSequence,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    override val summary: CharSequence? = null,
    override val visible: Boolean = true,
    override val enabled: Boolean = true,
    override val searchKeys: List<String> = emptyList(),
    /**
     * true 表示点击后进入另一个设置页（行尾显示箭头），
     * false 表示就地执行动作（如清理缓存），显示箭头会让人误以为还有下一层。
     */
    val navigates: Boolean = false
) : SettingItemSpec
