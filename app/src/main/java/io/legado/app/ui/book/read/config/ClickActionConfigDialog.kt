package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.putPrefInt

/**
 * 点击区域动作配置（V5 基座版）：全屏遮罩 + 九宫格。
 *
 * 窗口：`dialogWidth/dialogHeight` 均 MATCH_PARENT（V5 基座同名 open val 直接对应），
 * `onStart` 里额外做去背景与 setLayout(MATCH_PARENT, MATCH_PARENT)。
 * **返回键处理是这里最容易踩坑的地方**：V5 基座在 `onCreateDialog` 里已注册了 `setOnKeyListener`
 * （单监听器，会被覆盖）——本页在 `onStart` 里替换监听器时必须**自己接管 dismiss**：
 * 「先关动作选择器（Picker），否则关闭弹窗」，并保留 `isCancelable` 判定；同时 `OnBackPressedCallback`
 * 仍按原样在 `onViewCreated`/`onDestroyView` 注册与注销。
 * 其余保留：`ReadBookActivity.bottomDialog` 计数、`onDestroy` 的 `AppConfig.detectClickArea()`、
 * 九宫格 3×3 与 Picker 覆盖层的宽度/高度上限（minOf(0.9f/322dp)、minOf(0.7f/430dp)）。
 * 视觉说明：本页刻意使用固定的黑/白半透明（阅读页上的浮层，不跟随主题配色），本次**原样保留**；
 * 仅把 `LegadoMiuixCard/LegadoMiuixFloatingPanel/LegadoMiuixChoiceRow` 换成 V5 容器与 `AppSelectionRow`。
 */
internal class ClickActionConfigDialog : V5DialogFragment() {

    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.MATCH_PARENT

    private var bottomDialogRegistered = false
    private var closeActionPicker: (() -> Boolean)? = null
    private var actionPickerBackCallback: OnBackPressedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!bottomDialogRegistered) {
            (activity as? ReadBookActivity)?.let { readActivity ->
                readActivity.bottomDialog++
                bottomDialogRegistered = true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // 覆盖基座的 key listener 后必须自行处理 BACK 的兜底关闭
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (closeActionPicker?.invoke() == true) {
                    true
                } else if (isCancelable) {
                    dismissAllowingStateLoss()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        actionPickerBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeActionPicker?.invoke()
            }
        }.also { callback ->
            activity?.onBackPressedDispatcher?.addCallback(viewLifecycleOwner, callback)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (bottomDialogRegistered) {
            (activity as? ReadBookActivity)?.let { readActivity ->
                readActivity.bottomDialog--
            }
            bottomDialogRegistered = false
        }
    }

    override fun onDestroyView() {
        dialog?.setOnKeyListener(null)
        actionPickerBackCallback?.remove()
        actionPickerBackCallback = null
        closeActionPicker = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppConfig.detectClickArea()
    }

    @Composable
    override fun Content() {
        val optionLabels = actionOptions.associate { it.id to stringResource(it.titleRes) }
        var values by rememberSaveable {
            mutableStateOf(clickSlots.map { it.currentValue() })
        }
        var activeSlotIndex by remember { mutableStateOf<Int?>(null) }
        SideEffect {
            closeActionPicker = {
                if (activeSlotIndex == null) {
                    false
                } else {
                    activeSlotIndex = null
                    true
                }
            }
            actionPickerBackCallback?.isEnabled = activeSlotIndex != null
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.54f))
                .padding(6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ClickActionHeader(
                    onClose = { dismissAllowingStateLoss() }
                )
                ClickActionGrid(
                    values = values,
                    optionLabels = optionLabels,
                    onSlotClick = { activeSlotIndex = it },
                    modifier = Modifier.weight(1f)
                )
            }

            activeSlotIndex?.let { slotIndex ->
                ActionPickerOverlay(
                    selectedAction = values.getOrNull(slotIndex) ?: -1,
                    optionLabels = optionLabels,
                    onDismiss = { activeSlotIndex = null },
                    onSelected = { action ->
                        val slot = clickSlots.getOrNull(slotIndex) ?: return@ActionPickerOverlay
                        putPrefInt(slot.prefKey, action)
                        values = values.toMutableList().apply {
                            if (slotIndex in indices) {
                                this[slotIndex] = action
                            }
                        }
                        activeSlotIndex = null
                    }
                )
            }
        }
    }

    @Composable
    private fun ClickActionHeader(
        onClose: () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(visual.shape(ShapeKind.CARD))
                .background(Color.White.copy(alpha = 0.16f))
                .padding(PaddingValues(start = 16.dp, top = 5.dp, end = 6.dp, bottom = 5.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.click_regional_config),
                color = Color.White,
                style = visual.type.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_close),
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

    @Composable
    private fun ClickActionGrid(
        values: List<Int>,
        optionLabels: Map<Int, String>,
        onSlotClick: (Int) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(3) { rowIndex ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(3) { columnIndex ->
                        val slotIndex = rowIndex * 3 + columnIndex
                        ClickActionCell(
                            actionTitle = optionLabels[values.getOrNull(slotIndex)] ?: "",
                            onClick = { onSlotClick(slotIndex) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ClickActionCell(
        actionTitle: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val visual = LocalVisualSpec.current
        Column(
            modifier = modifier
                .fillMaxSize()
                .clip(visual.shape(ShapeKind.CARD))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = actionTitle,
                color = Color.White,
                style = visual.type.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun ActionPickerOverlay(
        selectedAction: Int,
        optionLabels: Map<Int, String>,
        onDismiss: () -> Unit,
        onSelected: (Int) -> Unit
    ) {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.26f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val panelWidth = minOf(maxWidth * 0.9f, 322.dp)
            val listMaxHeight = minOf(maxHeight * 0.7f, 430.dp)
            Column(
                modifier = Modifier
                    .size(width = panelWidth, height = listMaxHeight)
                    .clip(visual.shape(ShapeKind.BAR))
                    .background(colors.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Text(
                    text = stringResource(R.string.select_action),
                    style = visual.type.title,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = listMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    actionOptions.forEach { option ->
                        AppSelectionRow(
                            title = optionLabels[option.id].orEmpty(),
                            selected = option.id == selectedAction,
                            selectionMode = true,
                            onClick = { onSelected(option.id) },
                            onLongClick = {},
                        )
                    }
                }
            }
        }
    }

    private data class ActionOption(
        val id: Int,
        val titleRes: Int
    )

    private data class ClickSlot(
        val prefKey: String,
        val currentValue: () -> Int
    )

    companion object {
        private val actionOptions = listOf(
            ActionOption(-1, R.string.non_action),
            ActionOption(0, R.string.menu),
            ActionOption(1, R.string.next_page),
            ActionOption(2, R.string.prev_page),
            ActionOption(3, R.string.next_chapter),
            ActionOption(4, R.string.previous_chapter),
            ActionOption(5, R.string.read_aloud_prev_paragraph),
            ActionOption(6, R.string.read_aloud_next_paragraph),
            ActionOption(7, R.string.bookmark_add),
            ActionOption(8, R.string.edit_content),
            ActionOption(9, R.string.replace_state_change),
            ActionOption(10, R.string.chapter_list),
            ActionOption(11, R.string.search_content),
            ActionOption(12, R.string.sync_book_progress_t),
            ActionOption(13, R.string.read_aloud_pause_resume)
        )

        private val clickSlots = listOf(
            ClickSlot(PreferKey.clickActionTL) { AppConfig.clickActionTL },
            ClickSlot(PreferKey.clickActionTC) { AppConfig.clickActionTC },
            ClickSlot(PreferKey.clickActionTR) { AppConfig.clickActionTR },
            ClickSlot(PreferKey.clickActionML) { AppConfig.clickActionML },
            ClickSlot(PreferKey.clickActionMC) { AppConfig.clickActionMC },
            ClickSlot(PreferKey.clickActionMR) { AppConfig.clickActionMR },
            ClickSlot(PreferKey.clickActionBL) { AppConfig.clickActionBL },
            ClickSlot(PreferKey.clickActionBC) { AppConfig.clickActionBC },
            ClickSlot(PreferKey.clickActionBR) { AppConfig.clickActionBR }
        )
    }
}
