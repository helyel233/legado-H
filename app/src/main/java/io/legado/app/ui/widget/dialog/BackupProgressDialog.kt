package io.legado.app.ui.widget.dialog

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.storage.BackupProgress
import io.legado.app.help.storage.BackupProgressHolder
import io.legado.app.help.storage.BackupStage
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

/**
 * 备份进度对话框：展示当前备份阶段与进度。
 * 右下角「后台继续」仅关闭界面，备份任务在全局协程中继续执行，完成后弹出提示。
 */
class BackupProgressDialog(private val context: Context) : ComponentDialog(context) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)
        setContentView(
            ComposeView(context).apply {
                // 唯一 id：compose retained store 以 (ViewModelStoreOwner, viewId) 为作用域，避免无 id 时共享
                id = View.generateViewId()
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val visual = LocalVisualSpec.current
                    val colors = LocalSemanticColors.current
                    val progress by BackupProgressHolder.progress.collectAsState()
                    AppCard(
                        modifier = Modifier
                            .widthIn(min = 260.dp, max = 420.dp)
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.backup),
                                style = visual.type.title,
                                color = colors.onSurface,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stageMessage(progress),
                                style = visual.type.caption,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            val fraction = progress?.fraction
                            if (fraction != null) {
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                        .height(6.dp),
                                    color = colors.primary,
                                    trackColor = colors.surfaceVariant
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                        .height(6.dp),
                                    color = colors.primary,
                                    trackColor = colors.surfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                AppGhostButton(
                                    text = stringResource(R.string.backup_continue_background),
                                    onClick = { dismiss() }
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    private fun stageMessage(progress: BackupProgress?): String {
        return when (progress?.stage) {
            BackupStage.PACKING -> {
                val fraction = progress.fraction
                if (fraction != null) {
                    stringResource(R.string.backup_stage_packing_progress, (fraction * 100).toInt())
                } else {
                    stringResource(R.string.backup_stage_packing)
                }
            }

            BackupStage.SAVING -> stringResource(R.string.backup_stage_saving)
            BackupStage.UPLOADING -> stringResource(R.string.backup_stage_uploading)
            BackupStage.FINISHED -> stringResource(R.string.backup_stage_finished)
            else -> stringResource(R.string.backup_stage_preparing)
        }
    }
}
