package io.legado.app.ui.config

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.ui.host.AppAsyncImage
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/**
 * 封面集合详情 V5 版（换壳迁移自旧 View 版，台账 #43）。
 *
 * 旧版：BaseActivity + ViewBinding + GridLayoutManager(3 列) + RecyclerView + Glide，
 *       顶栏菜单「导入图片」、长按图片确认删除。
 * 新版：AppCompatActivity + setV5Content + HostV5Page（顶栏 trailing 导入按钮）
 *       + LazyVerticalGrid(3 列) + AppAsyncImage + 长按 AppConfirmDialog 删除。
 * 类名/包不变（in-place 换壳），入口 CoverCollectionManageActivity 零切换。
 */
class CoverCollectionDetailActivity : AppCompatActivity() {

    private var isNight = false
    private var collectionId: String? = null
    private var collection: CoverCollectionManager.Collection? = null

    // ---------- Compose 状态 ----------
    var titleText by mutableStateOf("")
    var images by mutableStateOf<List<String>>(emptyList())
    var deleteTarget by mutableStateOf<String?>(null)

    private val importImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val current = collection ?: return@registerForActivityResult
        lifecycleScope.launch {
            kotlin.runCatching {
                collection = CoverCollectionManager.addImages(this@CoverCollectionDetailActivity, current, uris)
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
            bindCollection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isNight = intent.getBooleanExtra("isNight", false)
        collectionId = intent.getStringExtra("id")
        setV5Content { DetailScreen(this) }
        loadCollection()
    }

    private fun loadCollection() {
        lifecycleScope.launch {
            collection = CoverCollectionManager.get(isNight, collectionId)
            bindCollection()
        }
    }

    private fun bindCollection() {
        val current = collection ?: return
        titleText = current.name
        images = current.images
    }

    internal fun launchImportImages() {
        importImages.launch("image/*")
    }

    internal fun doDeleteImage() {
        val imagePath = deleteTarget ?: return
        val current = collection ?: return
        lifecycleScope.launch {
            kotlin.runCatching {
                collection = CoverCollectionManager.deleteImage(current, imagePath)
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
            deleteTarget = null
            bindCollection()
        }
    }
}

@Composable
private fun DetailScreen(activity: CoverCollectionDetailActivity) {
    BackHandler { activity.finish() }

    HostV5Page(
        title = activity.titleText,
        onBack = { activity.finish() },
        trailing = {
            AppFilledButton(
                text = stringResource(R.string.cover_collection_import_images),
                onClick = { activity.launchImportImages() },
            )
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(activity.images, key = { it }) { path ->
                CoverImageCell(
                    path = path,
                    onLongClick = { activity.deleteTarget = path },
                )
            }
        }
    }

    val target = activity.deleteTarget
    if (target != null) {
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.cover_collection_delete_image_confirm),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = { activity.doDeleteImage() },
            onDismiss = { activity.deleteTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverImageCell(path: String, onLongClick: () -> Unit) {
    AppAsyncImage(
        model = path,
        contentDescription = null,
        cornerRadius = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongClick,
            ),
    )
}
