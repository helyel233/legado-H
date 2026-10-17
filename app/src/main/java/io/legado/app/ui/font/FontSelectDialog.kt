package io.legado.app.ui.font

import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogFontSelectBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.theme.filletTopBackground
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.list
import io.legado.app.utils.listFileDocs
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 字体选择对话框
 */
class FontSelectDialog : BaseDialogFragment(R.layout.dialog_font_select),
    Toolbar.OnMenuItemClickListener,
    FontAdapter.CallBack {
    private val fontRegex = Regex("(?i).*\\.[ot]tf")
    private val binding by viewBinding(DialogFontSelectBinding::bind)
    private val adapter by lazy {
        val curFontPath = callBack?.curFontPath ?: ""
        FontAdapter(requireContext(), curFontPath, this)
    }
    private val selectFontDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            io.legado.app.help.AppFont.invalidateFontList()
            if (uri.isContentScheme()) {
                putPrefString(PreferKey.fontFolder, uri.toString())
                val doc = DocumentFile.fromTreeUri(requireContext(), uri)
                if (doc != null) {
                    loadFontFiles(FileDoc.fromDocumentFile(doc))
                } else {
                    RealPathUtil.getPath(requireContext(), uri)?.let { path ->
                        loadFontFilesByPermission(path)
                    }
                }
            } else {
                uri.path?.let { path ->
                    putPrefString(PreferKey.fontFolder, path)
                    loadFontFilesByPermission(path)
                }
            }
        }
    }

    /**
     * 直接导入字体文件：绕开目录遍历，用单文件授权把字体复制进应用私有字体目录。
     * 部分 ROM 的文件夹选择器返回的 tree URI 查不到任何条目，这条路径不依赖目录授权。
     */
    private val importFontFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val context = context ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = File(FileUtils.getPath(context.externalFiles, "font"))
            var count = 0
            if (dir.isDirectory || dir.mkdirs()) {
                uris.forEach { uri ->
                    val name = DocumentFile.fromSingleUri(context, uri)?.name
                        ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
                    if (!name.matches(fontRegex)) return@forEach
                    val copied = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            File(dir, name).outputStream().use { output -> input.copyTo(output) }
                        } != null
                    }.getOrDefault(false)
                    if (copied) count++
                }
            }
            withContext(Dispatchers.Main) {
                if (count > 0) {
                    io.legado.app.help.AppFont.invalidateFontList()
                    // 刚导入完不该再报「文件夹读不出来」
                    loadInitialFontFiles(showFolderError = false)
                }
                toastOnUi(getString(R.string.import_font_success, count))
            }
        }
    }
    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.background = requireContext().filletTopBackground(
            requireContext().themeMutedColorOrDefault()
        )
        binding.toolBar.setTitle(R.string.select_font)
        binding.toolBar.inflateMenu(R.menu.font_select)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        loadInitialFontFiles()
    }

    private fun loadInitialFontFiles(showFolderError: Boolean = true) {
        val fontPath = getPrefString(PreferKey.fontFolder)
        if (fontPath.isNullOrEmpty()) {
            loadLocalFontFiles()
            return
        }
        if (fontPath.isContentScheme()) {
            val doc = DocumentFile.fromTreeUri(requireContext(), Uri.parse(fontPath))
            if (doc?.canRead() == true) {
                loadFontFiles(FileDoc.fromDocumentFile(doc))
            } else {
                // 授权失效或 ROM 的 tree URI 查不到条目时，不能只给一个空列表
                if (showFolderError) toastOnUi(R.string.font_folder_unreadable)
                loadLocalFontFiles()
            }
            return
        }
        // 部分 ROM 的选择器把字体文件夹记成 file:// 形式，取回真实路径再读
        val folder = if (fontPath.startsWith("file://", ignoreCase = true)) {
            Uri.parse(fontPath).path ?: fontPath
        } else {
            fontPath
        }
        if (File(folder).canRead()) {
            loadFontFiles(FileDoc.fromFile(File(folder)))
        } else {
            if (showFolderError) toastOnUi(R.string.font_folder_unreadable)
            loadLocalFontFiles()
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_default -> {
                val requireContext = requireContext()
                alert(titleResource = R.string.system_typeface) {
                    items(
                        requireContext.resources.getStringArray(R.array.system_typefaces).toList()
                    ) { _, i ->
                        if (callBack?.applySystemTypefaceOnDefault != false) {
                            AppConfig.systemTypefaces = i
                        }
                        onDefaultFontChange()
                        dismissAllowingStateLoss()
                    }
                }
            }
            R.id.menu_other -> {
                openFolder()
            }
            R.id.menu_import_font -> {
                importFontFiles.launch(arrayOf("*/*"))
            }
        }
        return true
    }

    private fun openFolder() {
        lifecycleScope.launch {
            val defaultPath = "SD${File.separator}Fonts"
            selectFontDir.launch {
                otherActions = arrayListOf(SelectItem(defaultPath, -1))
            }
        }
    }

    private fun getLocalFonts(): ArrayList<FileDoc> {
        val path = FileUtils.getPath(requireContext().externalFiles, "font")
        return File(path).listFileDocs {
            it.name.matches(fontRegex)
        }
    }

    private fun loadLocalFontFiles() {
        adapter.setItems(getLocalFonts().sortedWith { o1, o2 ->
            o1.name.cnCompare(o2.name)
        })
    }

    private fun loadFontFilesByPermission(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                loadFontFiles(
                    FileDoc.fromFile(File(path))
                )
            }
            .request()
    }

    private fun loadFontFiles(fileDoc: FileDoc) {
        execute {
            val fontItems = fileDoc.list {
                it.name.matches(fontRegex)
            } ?: ArrayList()
            mergeFontItems(fontItems, getLocalFonts())
        }.onSuccess {
            adapter.setItems(it)
        }.onError {
            AppLog.put("加载字体文件失败\n${it.localizedMessage}", it)
            toastOnUi("getFontFiles:${it.localizedMessage}")
        }
    }

    private fun mergeFontItems(
        items1: ArrayList<FileDoc>,
        items2: ArrayList<FileDoc>
    ): List<FileDoc> {
        val items = ArrayList(items1)
        items2.forEach { item2 ->
            var isInFirst = false
            items1.forEach for1@{ item1 ->
                if (item2.name == item1.name) {
                    isInFirst = true
                    return@for1
                }
            }
            if (!isInFirst) {
                items.add(item2)
            }
        }
        return items.sortedWith { o1, o2 ->
            o1.name.cnCompare(o2.name)
        }
    }

    override fun onFontSelect(docItem: FileDoc) {
        execute {
            callBack?.selectFont(docItem.toString())
        }.onSuccess {
            dismissAllowingStateLoss()
        }
    }

    private fun onDefaultFontChange() {
        callBack?.selectFont("")
    }

    private val callBack: CallBack?
        get() = (parentFragment as? CallBack) ?: (activity as? CallBack)

    interface CallBack {
        fun selectFont(path: String)
        val curFontPath: String
        val applySystemTypefaceOnDefault: Boolean
            get() = true
    }
}
