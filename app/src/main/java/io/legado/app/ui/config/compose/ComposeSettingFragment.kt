package io.legado.app.ui.config.compose

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
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

    /**
     * 宿主页面是否真的露出 App 壁纸，交给 `LocalGlassWallpaperAvailable`。
     *
     * `null`（默认）= 不声明，由 App 级「有没有背景图」判定 —— 所有常规设置页宿主
     * 走这条，行为与引入本属性之前一字不变。
     *
     * 目前唯一的覆写点是 `MoreConfigDialog.ReadPreferenceFragment`（阅读页「更多设置」面板）：
     * 它背后是自绘的正文而不是 App 壁纸，且该面板自己会铺「当前正文」的磨砂表面 ——
     * 按照 `LocalGlassWallpaperAvailable` 的约定，声明 false 同时意味着分区卡改用薄底。
     *
     * `ReadAloudConfigDialog.ReadAloudPreferenceFragment` **不覆写**（保持 null）：那个面板根
     * 仍是不透明的 `dialogSurfaceBackground`，卡片一旦改薄底就会变成「半透明卡套实底面板」。
     * 等它的面板表面也改成正文磨砂之后，才应该跟进声明。
     */
    protected open val wallpaperGlassAvailable: Boolean? = null

    /**
     * 构建 spec 时使用的上下文。
     *
     * 正常页面里为 null，[ctx] 退回 requireContext()；构建搜索索引时由 [buildSpec]
     * 注入 Application context。搜索索引和页面 spec 因此共用同一份数据来源：
     * 新增或改名设置项后，「我的」页搜索立刻能命中，不再需要同步维护一份影子 XML。
     */
    private var specContext: Context? = null

    protected val ctx: Context
        get() = specContext ?: requireContext()

    protected fun str(@StringRes resId: Int): String = ctx.getString(resId)

    protected fun str(@StringRes resId: Int, vararg formatArgs: Any): String =
        ctx.getString(resId, *formatArgs)

    /**
     * 不依赖宿主生命周期地构建一次 spec，供设置搜索索引使用。
     * 只在主线程调用（spec 构建会读 SharedPreferences），构建完立刻清掉注入的 context，
     * 避免这个未 attach 的裸实例被后续误当成正常页面使用。
     */
    fun buildSpec(context: Context): SettingPageSpec {
        specContext = context
        return try {
            buildPageSpec()
        } finally {
            specContext = null
        }
    }

    private val refreshTick = mutableIntStateOf(0)
    private val scrollTargetKey = mutableStateOf<String?>(null)
    private var targetKeyHandled = false

    /** 带着进来的原始 targetKey，定位失败时要原样交给 [onTargetKeyUnresolved] */
    private var pendingRawTargetKey: String? = null

    protected val prefs: SharedPreferences
        get() = ctx.defaultSharedPreferences

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
                CompositionLocalProvider(
                    LocalGlassWallpaperAvailable provides wallpaperGlassAvailable
                ) {
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
        return ctx.getPrefBoolean(key, defaultValue)
    }

    protected fun stringSetting(
        key: String,
        defaultValue: String
    ): String {
        return ctx.getPrefString(key, defaultValue) ?: defaultValue
    }

    protected fun intSetting(
        key: String,
        defaultValue: Int
    ): Int {
        return ctx.getPrefInt(key, defaultValue)
    }

    protected fun updateBooleanSetting(
        key: String,
        value: Boolean
    ) {
        ctx.putPrefBoolean(key, value)
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
        ctx.putPrefInt(key, value)
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
        visible: Boolean = true,
        searchKeys: List<String> = emptyList()
    ): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = key,
            title = title,
            summary = summary,
            checked = booleanSetting(key, defaultValue),
            visible = visible,
            searchKeys = searchKeys,
            onCheckedChange = { updateBooleanSetting(key, it) }
        )
    }

    protected fun choice(
        key: String,
        title: String,
        entriesRes: Int,
        valuesRes: Int,
        defaultValue: String,
        summary: String? = null,
        searchKeys: List<String> = emptyList()
    ): SettingChoiceSpec {
        val options = choiceOptionList(entriesRes, valuesRes)
        val selectedValue = stringSetting(key, defaultValue)
        return SettingChoiceSpec(
            key = key,
            title = title,
            summary = summary ?: choiceDisplayLabel(options, selectedValue),
            options = options,
            selectedValue = selectedValue,
            searchKeys = searchKeys,
            onSelected = { updateStringSetting(key, it) }
        )
    }

    protected fun intChoice(
        key: String,
        title: String,
        entriesRes: Int,
        valuesRes: Int,
        defaultValue: Int,
        summary: String? = null,
        searchKeys: List<String> = emptyList()
    ): SettingChoiceSpec {
        val options = choiceOptionList(entriesRes, valuesRes)
        val selectedValue = intSetting(key, defaultValue).toString()
        return SettingChoiceSpec(
            key = key,
            title = title,
            summary = summary ?: choiceDisplayLabel(options, selectedValue),
            options = options,
            selectedValue = selectedValue,
            searchKeys = searchKeys,
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
        val entries = ctx.resources.getStringArray(entriesRes)
        val values = ctx.resources.getStringArray(valuesRes)
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
        searchKeys: List<String> = emptyList(),
        onSelected: (Int) -> Unit
    ): SettingActionSpec {
        return SettingActionSpec(
            key = key,
            title = title,
            summary = summary,
            visible = visible,
            searchKeys = searchKeys,
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
        pendingRawTargetKey = rawTargetKey
        scrollTargetKey.value = normalizeTargetKey(rawTargetKey)
    }

    /**
     * 让嵌套在本页里的配置（例如某个 action 打开的弹窗）也能定位到具体某一项。
     *
     * 搜索索引是平铺的：弹窗里的项和页面上的项一样都是一条搜索结果，点进来却可能
     * 落在「页面里根本没有这个 key」的页面上。子类接管了 [onTargetKeyUnresolved]
     * 之后，用这个方法把 key 透传给弹窗自己的 spec。
     */
    protected fun requestScrollTo(targetKey: String) {
        if (targetKey.isBlank()) return
        scrollTargetKey.value = targetKey
    }

    /**
     * 目标项不在本页的 spec 里。
     *
     * 默认什么都不做（等于「点到一条定位不到的结果」）。把一批设置收在弹窗里的页面
     * 重写它，把 key 转交给弹窗，搜索命中这些项才不会落到一个毫无高亮的页面上。
     */
    protected open fun onTargetKeyUnresolved(rawKey: String) = Unit

    private fun handleTargetReady(targetKey: String) {
        if (targetKeyHandled) return
        val item = findItem(targetKey) ?: return consumeMissingTarget()
        targetKeyHandled = true
        pendingRawTargetKey = null
        scrollTargetKey.value = null
        view?.post {
            // 开关项不自动执行：handleItemClick 对开关就是「取反」，
            // 从搜索点进来时会静默改掉用户的设置。开关只需要定位 + 高亮，
            // 由用户自己决定要不要切换。动作项/选择项则自动展开，因为那才是「找到它」的终点。
            if (autoOpenTargetItem && item !is SettingSwitchSpec) {
                handleItemClick(item)
            }
            activity?.intent?.removeExtra("targetKey")
        }
    }

    private fun consumeMissingTarget() {
        if (targetKeyHandled) return
        targetKeyHandled = true
        val unresolvedKey = pendingRawTargetKey
        pendingRawTargetKey = null
        scrollTargetKey.value = null
        activity?.intent?.removeExtra("targetKey")
        if (unresolvedKey != null) onTargetKeyUnresolved(unresolvedKey)
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
