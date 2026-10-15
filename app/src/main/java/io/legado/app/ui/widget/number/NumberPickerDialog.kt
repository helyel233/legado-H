package io.legado.app.ui.widget.number

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.dpToPx
import io.legado.app.utils.windowSize
import splitties.systemservices.windowManager
import kotlin.math.roundToInt

class NumberPickerDialog(private val context: Context, private val isDecimalMode: Boolean = false) {

    private var title: String = ""
    private var maxValue: Int? = null
    private var minValue: Int? = null
    private var value: Int? = null
    private var customButtonTextId: Int? = null
    private var customButtonListener: (() -> Unit)? = null

    fun setTitle(title: String): NumberPickerDialog {
        this.title = title
        return this
    }

    fun setMaxValue(value: Int): NumberPickerDialog {
        maxValue = value
        return this
    }

    fun setMinValue(value: Int): NumberPickerDialog {
        minValue = value
        return this
    }

    fun setValue(value: Int): NumberPickerDialog {
        this.value = value
        return this
    }

    fun setCustomButton(textId: Int, listener: (() -> Unit)?): NumberPickerDialog {
        customButtonTextId = textId
        customButtonListener = listener
        return this
    }

    fun show(callBack: ((value: Int) -> Unit)?) {
        val dialog = ComponentDialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setContentView(
            ComposeView(context).apply {
                // 唯一 id：compose retained store 以 (ViewModelStoreOwner, viewId) 为作用域，避免无 id 时共享
                id = View.generateViewId()
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    NumberPickerContent(
                        title = title,
                        minValue = minValue ?: 0,
                        maxValue = maxValue ?: 999,
                        initialValue = value ?: minValue ?: 0,
                        isDecimalMode = isDecimalMode,
                        customButtonText = customButtonTextId?.let(context::getString),
                        onCustomClick = {
                            customButtonListener?.invoke()
                            dialog.dismiss()
                        },
                        onCancel = { dialog.dismiss() },
                        onConfirm = {
                            callBack?.invoke(it)
                            dialog.dismiss()
                        }
                    )
                }
            }
        )
        dialog.setOnShowListener {
            val width = minOf(
                (context.windowManager.windowSize.widthPixels * 0.92f).toInt(),
                420.dpToPx()
            )
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
    }
}

@Composable
private fun NumberPickerContent(
    title: String,
    minValue: Int,
    maxValue: Int,
    initialValue: Int,
    isDecimalMode: Boolean,
    customButtonText: String?,
    onCustomClick: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val safeMin = minOf(minValue, maxValue)
    val safeMax = maxOf(minValue, maxValue)
    var currentValue by remember {
        mutableIntStateOf(initialValue.coerceIn(safeMin, safeMax))
    }
    var inputText by remember(currentValue, isDecimalMode) {
        mutableStateOf(formatPickerValue(currentValue, isDecimalMode))
    }
    val usePercentSlider = !isDecimalMode &&
        safeMax == 100 &&
        safeMin in 0..100 &&
        safeMin < safeMax
    fun inputValueOrCurrent(): Int {
        return parsePickerValue(inputText, isDecimalMode)
            ?.coerceIn(safeMin, safeMax)
            ?: currentValue
    }
    fun committedValue(): Int {
        val committed = inputValueOrCurrent()
        currentValue = committed
        inputText = formatPickerValue(committed, isDecimalMode)
        return committed
    }
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                text = title,
                style = visual.type.title,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (usePercentSlider) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(visual.shape(ShapeKind.BUTTON))
                        .background(colors.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "${currentValue.coerceIn(safeMin, safeMax)}%",
                        style = visual.type.title,
                        color = colors.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AppSlider(
                        value = currentValue.coerceIn(safeMin, safeMax).toFloat(),
                        onValueChange = {
                            currentValue = it.roundToInt().coerceIn(safeMin, safeMax)
                            inputText = formatPickerValue(currentValue, isDecimalMode)
                        },
                        valueRange = safeMin.toFloat()..safeMax.toFloat(),
                        steps = (safeMax - safeMin - 1).coerceAtLeast(0)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StepButton(
                        text = "-",
                        enabled = inputValueOrCurrent() > safeMin,
                        onClick = {
                            currentValue = (inputValueOrCurrent() - 1).coerceAtLeast(safeMin)
                            inputText = formatPickerValue(currentValue, isDecimalMode)
                        }
                    )
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when {
                                safeMin < 0 -> KeyboardType.Text
                                isDecimalMode -> KeyboardType.Decimal
                                else -> KeyboardType.Number
                            }
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            color = colors.onSurface,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.onSurface,
                            unfocusedTextColor = colors.onSurface,
                            focusedContainerColor = colors.surfaceVariant,
                            unfocusedContainerColor = colors.surfaceVariant,
                            focusedBorderColor = colors.primary.copy(alpha = 0.55f),
                            unfocusedBorderColor = colors.outline,
                            cursorColor = colors.primary
                        )
                    )
                    StepButton(
                        text = "+",
                        enabled = inputValueOrCurrent() < safeMax,
                        onClick = {
                            currentValue = (inputValueOrCurrent() + 1).coerceAtMost(safeMax)
                            inputText = formatPickerValue(currentValue, isDecimalMode)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (usePercentSlider) {
                    "$safeMin% - $safeMax%"
                } else {
                    "${formatPickerValue(safeMin, isDecimalMode)} - ${formatPickerValue(safeMax, isDecimalMode)}"
                },
                color = colors.onSurfaceVariant,
                style = visual.type.caption,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                customButtonText?.let {
                    AppGhostButton(
                        text = it,
                        onClick = onCustomClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                AppGhostButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                )
                AppFilledButton(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        onConfirm(committedValue())
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StepButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(52.dp)
            .clip(visual.shape(ShapeKind.BUTTON))
            .background(if (enabled) colors.surfaceVariant else colors.surfaceVariant.copy(alpha = 0.42f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.52f),
            style = visual.type.title,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatPickerValue(value: Int, decimalMode: Boolean): String {
    return if (decimalMode) {
        (value / 10.0).toString()
    } else {
        value.toString()
    }
}

private fun parsePickerValue(value: String, decimalMode: Boolean): Int? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    return if (decimalMode) {
        normalized.toDoubleOrNull()
            ?.takeIf { java.lang.Double.isFinite(it) }
            ?.let { (it * 10).roundToInt() }
    } else {
        normalized.toIntOrNull()
    }
}
