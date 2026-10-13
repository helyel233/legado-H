package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.data.entities.AiImageGroup
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.ai.AiImageGalleryManager.GalleryFilter
import io.legado.app.ui.main.ai.AiImagePreviewDialog
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilterChip
import io.legado.app.uikit.v5.components.AppFilterChipRow
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppSelectionAction
import io.legado.app.uikit.v5.components.AppSelectionBar
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.components.appRowCard
import io.legado.app.uikit.v5.components.rememberAppSelectionState
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 生图画廊（V5 重写）。
 * 自旧 AiImageGalleryActivity（BaseActivity + RecyclerView/GridLayout）迁来：
 * 网格换 LazyVerticalGrid + AppAsyncImage，筛选换 AppFilterChipRow，批量操作用
 * AppSelectionState + AppSelectionBar，分组/新建分组/删除确认换 V5 弹窗。
 */
class HostAiImageGalleryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostAiImageGalleryScreen() }
    }

    companion object {
        const val EXTRA_BOOK_KEY = "bookKey"
        const val EXTRA_TITLE = "title"

        fun start(context: Context, bookKey: String = "", title: String = "") {
            context.startActivity(
                Intent(context, HostAiImageGalleryActivity::class.java)
                    .putExtra(EXTRA_BOOK_KEY, bookKey)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }
}

@Composable
private fun HostAiImageGalleryScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val scope = activity.lifecycleScope

    val fixedBookKey = activity.intent.getStringExtra(HostAiImageGalleryActivity.EXTRA_BOOK_KEY).orEmpty()
    val fixedTitle = activity.intent.getStringExtra(HostAiImageGalleryActivity.EXTRA_TITLE).orEmpty()

    var query by remember { mutableStateOf("") }
    var currentFilter by remember {
        mutableStateOf<GalleryFilter>(
            if (fixedBookKey.isNotBlank()) GalleryFilter.BOOK(fixedBookKey) else GalleryFilter.ALL
        )
    }
    var groups by remember { mutableStateOf<List<AiImageGroup>>(emptyList()) }
    var images by remember { mutableStateOf<List<AiGeneratedImage>>(emptyList()) }

    val selection = rememberAppSelectionState<AiGeneratedImage> { it.id }
    var confirmDelete by remember { mutableStateOf(false) }
    var groupSheetOpen by remember { mutableStateOf(false) }
    var createGroupDialog by remember { mutableStateOf(false) }

    fun reload() {
        val q = query.trim()
        val filter = currentFilter
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                AiImageGalleryManager.cleanupExpiredTemporary()
                val gs = AiImageGalleryManager.listGroups()
                val imgs = when {
                    q.isNotBlank() -> AiImageGalleryManager.listImages(GalleryFilter.SEARCH(q))
                        .let { list -> if (fixedBookKey.isBlank()) list else list.filter { it.bookKey == fixedBookKey } }
                    else -> AiImageGalleryManager.listImages(filter)
                }
                gs to imgs
            }
            groups = data.first
            images = data.second
            selection.restoreSelected(selection.selected.intersect(data.second.map { it.id }.toSet()))
        }
    }

    fun moveSelectedToGroup(ids: List<String>, groupId: String?) {
        if (ids.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { AiImageGalleryManager.moveImagesToGroup(ids, groupId) }
            AppToast.show("已移动分组")
            selection.exit()
            reload()
        }
    }

    LaunchedEffect(query, currentFilter) { reload() }

    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    val filterOptions: List<Pair<String, GalleryFilter>> = buildList {
        add(context.getString(R.string.ai_image_gallery_all) to GalleryFilter.ALL)
        add(context.getString(R.string.ai_image_gallery_temporary) to GalleryFilter.TEMPORARY)
        add(context.getString(R.string.favorites) to GalleryFilter.FAVORITE)
        if (fixedBookKey.isNotBlank()) {
            add("本书" to GalleryFilter.BOOK(fixedBookKey))
        }
        add("角色图" to GalleryFilter.SOURCE_TYPE(AiImageGalleryManager.SOURCE_TYPE_CHARACTER_AVATAR))
        groups.forEach { add(it.name to GalleryFilter.GROUP(it.id)) }
    }

    val selectedIds = selection.selected

    HostV5Page(
        title = fixedTitle.ifBlank { context.getString(R.string.ai_image_gallery) },
        onBack = { activity.finish() },
        selectionCount = if (selection.selectionMode) selection.count else null,
        onExitSelection = { selection.exit() },
        bottomBar = {
            if (selection.selectionMode) {
                AppSelectionBar(
                    primaryActions = listOf(
                        AppSelectionAction("全选") { selection.selectAll(images) },
                        AppSelectionAction(context.getString(R.string.ai_image_group)) { groupSheetOpen = true },
                        AppSelectionAction(context.getString(R.string.delete), danger = true) { confirmDelete = true },
                        AppSelectionAction(context.getString(R.string.cancel)) { selection.exit() },
                    ),
                    moreLabel = "更多 ⋮",
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索图片",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
            )
            AppFilterChipRow(
                chips = filterOptions.map { AppFilterChip(it.first, currentFilter == it.second) },
                onChipClick = { index ->
                    filterOptions.getOrNull(index)?.let { currentFilter = it.second }
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            if (images.isEmpty()) {
                AppEmpty(
                    title = context.getString(R.string.ai_image_gallery_empty),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(images, key = { it.id }) { image ->
                        AiImageCard(
                            image = image,
                            selected = image.id in selectedIds,
                            onClick = {
                                if (selection.selectionMode) {
                                    selection.toggle(image)
                                } else {
                                    val dialog = AiImagePreviewDialog(image.id).apply {
                                        setOnDismissListener { reload() }
                                    }
                                    activity.showDialogFragment(dialog)
                                }
                            },
                            onLongClick = { selection.enter(image) },
                        )
                    }
                }
            }
        }
    }

    if (groupSheetOpen) {
        val groupActions = buildList {
            groups.forEach { group ->
                add(group.name to {
                    groupSheetOpen = false
                    moveSelectedToGroup(selection.selected.toList(), group.id)
                })
            }
            add(context.getString(R.string.ai_image_new_group) to {
                groupSheetOpen = false
                createGroupDialog = true
            })
        }
        AppMenuSheet(
            title = context.getString(R.string.ai_image_favorite_to),
            actions = groupActions,
            onDismiss = { groupSheetOpen = false },
        )
    }

    if (createGroupDialog) {
        var groupName by remember { mutableStateOf("") }
        AppInputDialog(
            title = context.getString(R.string.ai_image_new_group),
            value = groupName,
            onValueChange = { groupName = it },
            confirmLabel = context.getString(R.string.ok),
            cancelLabel = context.getString(R.string.cancel),
            placeholder = context.getString(R.string.ai_image_new_group),
            onConfirm = { text ->
                createGroupDialog = false
                val name = text.trim()
                if (name.isNotBlank()) {
                    val groupId = AiImageGalleryManager.createGroup(name).id
                    moveSelectedToGroup(selection.selected.toList(), groupId)
                }
            },
            onDismiss = { createGroupDialog = false },
        )
    }

    if (confirmDelete) {
        AppConfirmDialog(
            title = context.getString(R.string.delete),
            confirmLabel = context.getString(R.string.delete),
            cancelLabel = context.getString(R.string.cancel),
            onConfirm = {
                confirmDelete = false
                val ids = selection.selected.toList()
                scope.launch {
                    withContext(Dispatchers.IO) { AiImageGalleryManager.deleteImages(ids) }
                    selection.exit()
                    reload()
                }
            },
            onDismiss = { confirmDelete = false },
            message = "删除选中的 ${selection.count} 张图片？",
            danger = true,
        )
    }
}

@Composable
private fun AiImageCard(
    image: AiGeneratedImage,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .appRowCard()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        Box {
            AppAsyncImage(
                model = image.localPath,
                contentDescription = image.name,
                errorRes = R.drawable.image_loading_error,
                cornerRadius = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(visual.shape(ShapeKind.TAG))
                        .background(colors.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✓",
                        style = visual.type.caption,
                        color = colors.onPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = image.name,
            style = visual.type.label,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildImageSubtitle(image),
            style = visual.type.caption,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (image.favorite) "收藏中" else "未收藏",
            style = visual.type.caption,
            color = if (image.favorite) colors.primary else colors.onSurface,
            modifier = Modifier
                .clip(visual.shape(ShapeKind.TAG))
                .background(colors.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun buildImageSubtitle(item: AiGeneratedImage): String = buildList {
    if (item.bookName.isNotBlank()) add(item.bookName)
    if (item.chapterTitle.isNotBlank()) add(item.chapterTitle)
    if (item.characterName.isNotBlank()) add(item.characterName)
    add(item.prompt.replace(Regex("\\s+"), " ").take(72))
}.joinToString(" · ")
