package io.legado.app.ui.config

import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.help.config.EpubLoadingTemplate
import io.legado.app.ui.book.read.epub.EpubLoadingPreviewView
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppPackageManageItemCard
import io.legado.app.ui.widget.compose.AppPackageManageScreen

@Composable
internal fun EpubLoadingTemplateManageScreen(
    templates: List<EpubLoadingTemplate>, selectedId: String, night: Boolean,
    onBack: () -> Unit,
    onNightChanged: (Boolean) -> Unit, onApply: (EpubLoadingTemplate) -> Unit,
    onPreview: (EpubLoadingTemplate) -> Unit,
    onMoreActions: (EpubLoadingTemplate) -> List<AppManagementMenuAction>, onAdd: () -> Unit
) {
    AppPackageManageScreen(
        title = stringResource(R.string.epub_loading_templates),
        onBack = onBack,
        isNightMode = night,
        summaryText = stringResource(R.string.epub_loading_templates_help),
        addText = stringResource(R.string.epub_loading_add),
        onSwitchDayNight = onNightChanged, onAdd = onAdd
    ) { palette ->
        items(templates, key = { it.id }) { template ->
            val active = template.id == selectedId
            AppPackageManageItemCard(
                title = template.name,
                info = stringResource(if (template.builtIn) R.string.epub_loading_builtin else R.string.epub_loading_custom) +
                    " · " + template.description,
                isActive = active, canEdit = true,
                applyText = stringResource(if (active) R.string.theme_applied_state else R.string.theme_apply),
                editText = stringResource(R.string.epub_loading_preview),
                moreActions = onMoreActions(template), palette = palette,
                onApply = { onApply(template) }, onEdit = { onPreview(template) },
                leadingContent = {
                    AndroidView(
                        factory = { context -> EpubLoadingPreviewView(context).apply {
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        } },
                        update = { it.template = template; it.night = night },
                        modifier = Modifier.width(102.dp).height(210.dp)
                            .clip(RoundedCornerShape(12.dp)).clickable { onPreview(template) }
                    )
                }
            )
        }
    }
}
