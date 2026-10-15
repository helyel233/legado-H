package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.dao.BookTagInfo
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.book.BookTagManagement
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.BookTagAssignmentUi
import io.legado.app.ui.main.bookshelf.BookshelfTagGroupUi
import io.legado.app.ui.main.bookshelf.BookshelfTagItemUi
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.components.appRowCard
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.ui.host.V5DialogPanel
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P4-3：书架标签管理 V5 化（2026-10-13）。
 * 业务逻辑自 BookshelfTagManageActivity 逐行搬运（分组位运算过滤/事务写库/配置映射
 * 原样）；UI 换 V5 零件；两个弹窗（添加标签/书籍分配）用 V5 零件重写。
 */
class HostBookshelfTagManageActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_GROUP_ID = "groupId"
        fun start(context: Context, groupId: Long) {
            context.startActivity(
                Intent(context, HostBookshelfTagManageActivity::class.java)
                    .putExtra(EXTRA_GROUP_ID, groupId)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val focusGroupId = intent.getLongExtra(EXTRA_GROUP_ID, BookGroup.IdAll)
        setV5Content { HostBookshelfTagManageScreen(focusGroupId) }
    }
}

@Composable
private fun HostBookshelfTagManageScreen(focusGroupId: Long) {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val scope = rememberCoroutineScope()

    var groups by remember { mutableStateOf<List<BookshelfTagGroupUi>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var assignment by remember { mutableStateOf<BookTagAssignmentUi?>(null) }
    var addTagsTarget by remember { mutableStateOf<BookshelfTagGroupUi?>(null) }
    var deleteTagTarget by remember { mutableStateOf<Pair<BookshelfTagGroupUi, String>?>(null) }
    var renameTagTarget by remember { mutableStateOf<Pair<BookshelfTagGroupUi, String>?>(null) }
    var renameInput by remember { mutableStateOf("") }

    fun loadTags() {
        loading = true
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                val books = appDb.bookDao.allTagInfos
                val groupList = appDb.bookGroupDao.all
                    .filter { it.groupId != BookGroup.IdRoot }
                    .sortedWith(
                        compareBy<BookGroup> { if (it.groupId == focusGroupId) 0 else 1 }
                            .thenBy { it.order }
                    )
                val userGroupMask = groupList.asSequence()
                    .filter { it.groupId > 0 }
                    .fold(0L) { acc, group -> acc or group.groupId }
                val configuredMap = AppConfig.bookshelfGroupTags.toMutableMap()
                var configuredChanged = false
                val hiddenMap = AppConfig.bookshelfHiddenTags
                groupList.mapNotNull { group ->
                    val groupBooks = booksInGroup(group, books, userGroupMask)
                    val existingTags = groupBooks.flatMap { BookTagHelper.parse(it.customTag) }
                    val configuredTags = configuredMap[group.groupId].orEmpty()
                    val tags = BookTagManagement.mergeTags(configuredTags, existingTags)
                    if (configuredTags != tags) {
                        configuredMap[group.groupId] = tags
                        configuredChanged = true
                    }
                    val hiddenTags = hiddenMap[group.groupId].orEmpty()
                    BookshelfTagGroupUi(
                        groupId = group.groupId,
                        groupName = group.groupName,
                        books = groupBooks,
                        tags = tags.map { tag ->
                            BookshelfTagItemUi(
                                name = tag,
                                assignedCount = groupBooks.count {
                                    BookTagHelper.has(it.customTag, tag)
                                },
                                visible = hiddenTags.none { it.equals(tag, ignoreCase = true) },
                            )
                        },
                    )
                }.also {
                    if (configuredChanged) AppConfig.bookshelfGroupTags = configuredMap
                }
            }
            groups = data
            loading = false
        }
    }

    // ---- 业务（自旧 Activity 逐行搬运） ----

    fun addTags(groupId: Long, tags: List<String>) {
        val newTags = BookTagManagement.mergeTags(emptyList(), tags)
        if (newTags.isEmpty()) return
        val map = AppConfig.bookshelfGroupTags.toMutableMap()
        map[groupId] = BookTagManagement.mergeTags(map[groupId].orEmpty(), newTags)
        AppConfig.bookshelfGroupTags = map
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
        loadTags()
    }

    fun setTagVisible(groupId: Long, tag: String, visible: Boolean, onLocal: () -> Unit) {
        val map = AppConfig.bookshelfHiddenTags.toMutableMap()
        val tags = map[groupId].orEmpty().toMutableSet()
        tags.removeAll { it.equals(tag, ignoreCase = true) }
        if (!visible) tags.add(tag)
        if (tags.isEmpty()) map.remove(groupId) else map[groupId] = tags
        AppConfig.bookshelfHiddenTags = map
        onLocal()
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    fun saveAssignment(target: BookTagAssignmentUi, selectedUrls: Set<String>) {
        assignment = null
        scope.launch {
            withContext(Dispatchers.IO) {
                appDb.withTransaction {
                    target.books.forEach { book ->
                        val shouldHaveTag = book.bookUrl in selectedUrls
                        val write = BookTagManagement.updateTag(
                            customTag = book.customTag,
                            tag = target.tag,
                            selected = shouldHaveTag,
                        ) ?: return@forEach
                        appDb.bookDao.updateCustomTag(book.bookUrl, write.customTag)
                    }
                }
            }
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
            loadTags()
        }
    }

    LaunchedEffect(Unit) { loadTags() }

    val listState = rememberLazyListState()

    HostV5Page(
        title = stringResource(R.string.bookshelf_tag_manage),
        onBack = { activity.finish() },
    ) {
        when {
            loading -> AppEmpty(
                title = stringResource(R.string.bookshelf_tag_manage),
                supporting = stringResource(R.string.loading),
            )

            groups.isEmpty() -> AppEmpty(
                title = stringResource(R.string.bookshelf_tag_manage),
                supporting = stringResource(R.string.bookshelf_tag_no_groups),
            )

            else -> AppFastScroller(
                listState = listState,
                topContentPadding = LocalVisualSpec.current.density.blockGap / 2,
                bottomContentPadding = LocalVisualSpec.current.density.screenPadding,
                endContentPadding = LocalVisualSpec.current.density.screenPadding,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = LocalVisualSpec.current.density.screenPadding,
                        end = LocalVisualSpec.current.density.screenPadding,
                        top = LocalVisualSpec.current.density.blockGap / 2,
                        bottom = LocalVisualSpec.current.density.screenPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(LocalVisualSpec.current.density.blockGap),
                ) {
                    itemsIndexed(groups, key = { _, g -> g.groupId }) { _, group ->
                        TagGroupCard(
                            group = group,
                            reusableTags = groups.asSequence()
                                .filter { it.groupId != group.groupId }
                                .flatMap { it.tags.asSequence().map { t -> t.name } }
                                .distinct()
                                .filterNot { candidate ->
                                    group.tags.any { it.name.equals(candidate, true) }
                                }
                                .toList(),
                            onAddTags = { addTagsTarget = group },
                            onVisibilityChange = { tag, visible ->
                                setTagVisible(group.groupId, tag, visible) {
                                    groups = groups.map { g ->
                                        if (g.groupId != group.groupId) g else g.copy(
                                            tags = g.tags.map { item ->
                                                if (item.name.equals(tag, true)) item.copy(visible = visible)
                                                else item
                                            },
                                        )
                                    }
                                }
                            },
                            onManageBooks = { tag ->
                                assignment = BookTagAssignmentUi(
                                    groupId = group.groupId,
                                    groupName = group.groupName,
                                    tag = tag,
                                    books = group.books,
                                    initiallySelectedUrls = group.books.asSequence()
                                        .filter { BookTagHelper.has(it.customTag, tag) }
                                        .mapTo(linkedSetOf()) { it.bookUrl },
                                )
                            },
                            onDeleteTag = { deleteTagTarget = group to it },
                            onRenameTag = {
                                renameTagTarget = group to it
                                renameInput = it
                            },
                        )
                    }
                }
            }
        }
    }

    // ---- 弹窗 ----

    addTagsTarget?.let { group ->
        AddTagsDialog(
            reusableTags = groups.asSequence()
                .filter { it.groupId != group.groupId }
                .flatMap { it.tags.asSequence().map { t -> t.name } }
                .distinct()
                .toList(),
            onConfirm = { tags ->
                addTags(group.groupId, tags)
                addTagsTarget = null
            },
            onDismiss = { addTagsTarget = null },
        )
    }

    assignment?.let { target ->
        BookTagAssignmentDialog(
            assignment = target,
            onSave = { urls -> saveAssignment(target, urls) },
            onDismiss = { assignment = null },
        )
    }

    deleteTagTarget?.let { (group, tag) ->
        AppConfirmDialog(
            title = stringResource(R.string.bookshelf_tag_delete_title),
            message = stringResource(R.string.bookshelf_tag_delete_message, tag, group.groupName),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        appDb.withTransaction {
                            group.books.forEach { book ->
                                val write = BookTagManagement.updateTag(
                                    customTag = book.customTag,
                                    tag = tag,
                                    selected = false,
                                ) ?: return@forEach
                                appDb.bookDao.updateCustomTag(book.bookUrl, write.customTag)
                            }
                        }
                        val hiddenMap = AppConfig.bookshelfHiddenTags.toMutableMap()
                        hiddenMap[group.groupId] = hiddenMap[group.groupId].orEmpty()
                            .filterNot { it.equals(tag, ignoreCase = true) }
                            .toSet()
                        if (hiddenMap[group.groupId].isNullOrEmpty()) hiddenMap.remove(group.groupId)
                        AppConfig.bookshelfHiddenTags = hiddenMap
                        val tagMap = AppConfig.bookshelfGroupTags.toMutableMap()
                        tagMap[group.groupId] = tagMap[group.groupId].orEmpty()
                            .filterNot { it.equals(tag, ignoreCase = true) }
                        AppConfig.bookshelfGroupTags = tagMap
                    }
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    loadTags()
                }
                deleteTagTarget = null
            },
            onDismiss = { deleteTagTarget = null },
        )
    }

    renameTagTarget?.let { (group, tag) ->
        V5DialogPanel(
            title = stringResource(R.string.bookshelf_tag_rename),
            message = null,
            content = {
                AppTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    placeholder = stringResource(R.string.bookshelf_tag_rename_hint),
                )
            },
            actions = {
                AppGhostButton(
                    text = stringResource(R.string.cancel),
                    onClick = { renameTagTarget = null },
                )
                AppFilledButton(
                    text = stringResource(R.string.ok),
                    onClick = {
                        val trimmed = renameInput.trim()
                        if (trimmed.isNotEmpty() && !trimmed.equals(tag, true)) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    appDb.withTransaction {
                                        group.books.forEach { book ->
                                            val write = BookTagManagement.renameTag(
                                                customTag = book.customTag,
                                                oldTag = tag,
                                                newTag = trimmed,
                                            ) ?: return@forEach
                                            appDb.bookDao.updateCustomTag(book.bookUrl, write.customTag)
                                        }
                                    }
                                    val tagMap = AppConfig.bookshelfGroupTags.toMutableMap()
                                    tagMap[group.groupId] = tagMap[group.groupId].orEmpty().map {
                                        if (it.equals(tag, ignoreCase = true)) trimmed else it
                                    }
                                    AppConfig.bookshelfGroupTags = tagMap
                                    val hiddenMap = AppConfig.bookshelfHiddenTags.toMutableMap()
                                    val hidden = hiddenMap[group.groupId].orEmpty()
                                    if (hidden.isNotEmpty()) {
                                        hiddenMap[group.groupId] = hidden.map {
                                            if (it.equals(tag, ignoreCase = true)) trimmed else it
                                        }.toSet()
                                        AppConfig.bookshelfHiddenTags = hiddenMap
                                    }
                                }
                                postEvent(EventBus.BOOKSHELF_REFRESH, "")
                                context.toastOnUi(R.string.bookshelf_tag_rename_done)
                                loadTags()
                            }
                        }
                        renameTagTarget = null
                    },
                )
            },
        )
    }
}

/** 分组位运算过滤（自旧 Activity 逐行搬运）。 */
private fun booksInGroup(
    group: BookGroup,
    books: List<BookTagInfo>,
    userGroupMask: Long,
): List<BookTagInfo> {
    return when (group.groupId) {
        BookGroup.IdAll -> books
        BookGroup.IdLocal -> books.filter { it.type and BookType.local > 0 }
        BookGroup.IdAudio -> books.filter { it.type and BookType.audio > 0 }
        BookGroup.IdVideo -> books.filter { it.type and BookType.video > 0 }
        BookGroup.IdError -> books.filter { it.type and BookType.updateError > 0 }
        BookGroup.IdNetNone -> books.filter {
            it.type and BookType.audio == 0 &&
                it.type and BookType.video == 0 &&
                it.type and BookType.local == 0 &&
                (it.group and userGroupMask) == 0L
        }
        BookGroup.IdLocalNone -> books.filter {
            it.type and BookType.audio == 0 &&
                it.type and BookType.video == 0 &&
                it.type and BookType.local > 0 &&
                (it.group and userGroupMask) == 0L
        }
        else -> if (group.groupId > 0) {
            books.filter { it.group and group.groupId > 0 }
        } else {
            emptyList()
        }
    }
}

@Composable
private fun TagGroupCard(
    group: BookshelfTagGroupUi,
    reusableTags: List<String>,
    onAddTags: () -> Unit,
    onVisibilityChange: (String, Boolean) -> Unit,
    onManageBooks: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onRenameTag: (String) -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val d = visual.density
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appRowCard()
            .padding(d.cardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group.groupName, style = visual.type.title, color = colors.onSurface)
                Text(
                    text = stringResource(
                        R.string.bookshelf_tag_group_summary,
                        group.books.size,
                        group.tags.size,
                    ),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                )
            }
            AppGhostButton(text = stringResource(R.string.add), onClick = onAddTags)
        }
        Spacer(Modifier.height(d.blockGap / 2))
        group.tags.forEach { tag ->
            AppSelectionRow(
                title = tag.name,
                supporting = stringResource(R.string.bookshelf_tag_book_count, tag.assignedCount),
                selected = false,
                selectionMode = false,
                onClick = { onManageBooks(tag.name) },
                onLongClick = { onRenameTag(tag.name) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppSwitch(
                            checked = tag.visible,
                            onCheckedChange = { onVisibilityChange(tag.name, it) },
                        )
                        AppOverflowMini(onManageBooks = { onManageBooks(tag.name) }, onRename = { onRenameTag(tag.name) }, onDelete = { onDeleteTag(tag.name) })
                    }
                },
            )
        }
    }
}

@Composable
private fun AppOverflowMini(
    onManageBooks: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppOverflowMenuButton(
            contentDescription = stringResource(R.string.v5_more),
            glyph = "⋯",
        ) { open = true }
        if (open) {
            AppMenuSheet(
                title = "",
                actions = listOf(
                    stringResource(R.string.bookshelf_tag_manage_books) to {
                        open = false
                        onManageBooks()
                    },
                    stringResource(R.string.bookshelf_tag_rename) to {
                        open = false
                        onRename()
                    },
                    stringResource(R.string.delete) to {
                        open = false
                        onDelete()
                    },
                ),
                onDismiss = { open = false },
            )
        }
    }
}

/** 添加标签弹窗（输入 + 可复用标签多选）。 */
@Composable
private fun AddTagsDialog(
    reusableTags: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val parsed = BookTagHelper.parse(input)
    V5DialogPanel(
        title = stringResource(R.string.add),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.bookshelf_tag_add_hint),
                )
                if (reusableTags.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.bookshelf_tag_reusable),
                        style = LocalVisualSpec.current.type.caption,
                        color = LocalSemanticColors.current.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(reusableTags, key = { it }) { tag ->
                            AppChip(
                                text = tag,
                                selected = tag in picked,
                                onClick = {
                                    picked = if (tag in picked) picked - tag else picked + tag
                                },
                            )
                        }
                    }
                }
            }
        },
        actions = {
            AppGhostButton(text = stringResource(R.string.cancel), onClick = onDismiss)
            AppFilledButton(
                text = stringResource(R.string.add),
                enabled = parsed.isNotEmpty() || picked.isNotEmpty(),
                onClick = { onConfirm(parsed + picked) },
            )
        },
    )
}

/** 书籍分配弹窗（搜索 + 筛选 chips + 书籍勾选）。 */
@Composable
private fun BookTagAssignmentDialog(
    assignment: BookTagAssignmentUi,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) } // 0 全部 1 已选 2 未选
    val selected = remember {
        mutableStateOf(assignment.initiallySelectedUrls.toMutableSet())
    }
    val books = remember(assignment, query, filter, selected.value) {
        assignment.books.filter { book ->
            val matchQuery = query.isBlank() ||
                book.name.contains(query, true) ||
                book.author.contains(query, true)
            val has = book.bookUrl in selected.value
            val matchFilter = when (filter) {
                1 -> has
                2 -> !has
                else -> true
            }
            matchQuery && matchFilter
        }
    }
    V5DialogPanel(
        title = stringResource(R.string.bookshelf_tag_assign_title, assignment.tag, assignment.groupName),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSearchField(value = query, onValueChange = { query = it })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        stringResource(R.string.bookshelf_tag_filter_all),
                        stringResource(R.string.bookshelf_tag_filter_selected),
                        stringResource(R.string.bookshelf_tag_filter_unselected),
                    ).forEachIndexed { index, label ->
                        AppChip(text = label, selected = filter == index, onClick = { filter = index })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppGhostButton(
                        text = stringResource(R.string.bookshelf_tag_select_current),
                        onClick = { books.forEach { selected.value.add(it.bookUrl) } },
                    )
                    AppGhostButton(
                        text = stringResource(R.string.bookshelf_tag_unselect_current),
                        onClick = { books.forEach { selected.value.remove(it.bookUrl) } },
                    )
                }
                Text(
                    text = stringResource(
                        R.string.bookshelf_tag_selected_count,
                        selected.value.size,
                        assignment.books.size,
                    ),
                    style = LocalVisualSpec.current.type.caption,
                    color = LocalSemanticColors.current.onSurfaceVariant,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(books, key = { it.bookUrl }) { book ->
                        val checked = book.bookUrl in selected.value
                        AppSelectionRow(
                            title = book.name,
                            supporting = buildString {
                                append(book.author)
                                val tags = BookTagHelper.parse(book.customTag)
                                if (tags.isNotEmpty()) {
                                    append(" · ")
                                    append(tags.joinToString("、"))
                                }
                            },
                            selected = checked,
                            selectionMode = false,
                            onClick = {
                                if (checked) selected.value.remove(book.bookUrl)
                                else selected.value.add(book.bookUrl)
                            },
                            onLongClick = {},
                            trailing = {
                                AppSwitch(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        if (on) selected.value.add(book.bookUrl)
                                        else selected.value.remove(book.bookUrl)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        },
        actions = {
            AppGhostButton(text = stringResource(R.string.cancel), onClick = onDismiss)
            AppFilledButton(
                text = stringResource(R.string.ok),
                onClick = { onSave(selected.value.toSet()) },
            )
        },
    )
}
