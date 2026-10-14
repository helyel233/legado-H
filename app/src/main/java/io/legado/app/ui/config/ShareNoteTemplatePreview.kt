package io.legado.app.ui.config

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import java.io.File

/**
 * 摘录分享模板预览图（V5 版）。
 *
 * 旧版依赖 `AppManagementPalette`（管理页旧主题桥）取 `actionRadius/surfaceVariant/secondaryText`；
 * 新版改读 V5 令牌（`visual.shape(ShapeKind.COVER)`、`colors.surfaceVariant/onSurfaceVariant`、`visual.type.caption`）。
 * 注意：本地预览文件是固定路径 + 内容随样式重渲染，必须用 Glide signature 失效缓存（原逻辑保留）。
 */
@Composable
internal fun ShareNoteTemplatePreview(
    previewFile: File?,
    modifier: Modifier = Modifier,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Box(
        modifier = modifier
            .size(width = 78.dp, height = 110.dp)
            .clip(visual.shape(ShapeKind.COVER))
            .background(colors.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (previewFile != null && previewFile.exists() && previewFile.length() > 0L) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { imageView ->
                    Glide.with(imageView)
                        .load(previewFile)
                        .signature(
                            ObjectKey("${previewFile.absolutePath}:${previewFile.length()}:${previewFile.lastModified()}")
                        )
                        .centerCrop()
                        .into(imageView)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "预览",
                color = colors.onSurfaceVariant,
                style = visual.type.caption,
                textAlign = TextAlign.Center
            )
        }
    }
}
