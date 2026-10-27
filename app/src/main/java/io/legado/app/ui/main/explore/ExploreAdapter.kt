package io.legado.app.ui.main.explore

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatSpinner
import androidx.collection.LruCache
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.google.android.flexbox.FlexboxLayout
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.ExploreKind.Type
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.ItemFindBookBinding
import io.legado.app.databinding.ItemFilletSelectorSingleBinding
import io.legado.app.databinding.ItemFilletCompleteTextBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.filletControlBackground
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.text.AccentTextView
import io.legado.app.utils.InfoMap
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.removeLastElement
import io.legado.app.utils.setSelectionSafely
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onLongClick
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import kotlin.text.isNullOrEmpty

class ExploreAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<BookSourcePart, ItemFindBookBinding>(context) {
    companion object {
        val exploreInfoMapList = LruCache<String, InfoMap>(99)
    }
    private var modernMenuPopup: ModernActionPopup.Handle? = null
    private val recycler = arrayListOf<TextView>()
    private val textRecycler = arrayListOf<AutoCompleteTextView>()
    private val selectRecycler = arrayListOf<LinearLayout>()

    private var exIndex = -1
    /** 展开行的源 URL：exIndex 会随数据集变化漂移，锚定/恢复都以它为准 */
    var expandedSourceUrl: String? = null
        private set
    /** 视图重建后待恢复的（展开源, 滚动位置），首个非空列表落地时消费 */
    private var pendingViewRestore: Pair<String?, Int>? = null
    private var scrollTo = -1
    private var lastClickTime: Long = 0
    private val sourceKinds = ConcurrentHashMap<String, List<ExploreKind>>()
    private var saveInfoMapJob: Job? = null

    override fun getViewBinding(parent: ViewGroup): ItemFindBookBinding {
        return ItemFindBookBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemFindBookBinding,
        item: BookSourcePart,
        payloads: MutableList<Any>
    ) {
        binding.run {
            // 书源名行毛玻璃背景：强度由主题「发现页毛玻璃效果」控制，0 为透明原样
            llTitle.background = ExploreGlassBackdrop.rowBackground(
                llTitle,
                ExploreGlassBackdrop.level(context)
            ) ?: ContextCompat.getDrawable(context, R.drawable.bg_find_book_group)
            if (holder.layoutPosition == itemCount - 1) {
                root.setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            } else {
                root.setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 0)
            }
            if (payloads.isEmpty()) {
                tvName.text = item.bookSourceName
            }
            //展开判定须校验该位置的源仍是当初展开的源：
            //exIndex 是位置，数据集变化（diff/置顶/删除）后可能已漂移到别的源
            val expandedHere = exIndex == holder.layoutPosition &&
                getItemByLayoutPosition(exIndex)?.bookSourceUrl == item.bookSourceUrl
            if (expandedHere) {
                ivStatus.setImageResource(R.drawable.ic_arrow_down)
                rotateLoading.loadingColor = context.accentColor
                rotateLoading.visible()
                Coroutine.async(callBack.scope) {
                    loadExploreKinds(item)
                }.onSuccess { kindList ->
                    //异步期间行可能被复用为其它源，校验位置与源后再注入，避免错行与错误点击
                    if (holder.layoutPosition == exIndex &&
                        getItemByLayoutPosition(exIndex)?.bookSourceUrl == item.bookSourceUrl
                    ) {
                        upKindList(this@run, item, kindList, exIndex)
                    }
                }.onFinally {
                    rotateLoading.gone()
                    if (scrollTo >= 0) {
                        callBack.scrollTo(scrollTo)
                        scrollTo = -1
                    }
                }
            } else kotlin.runCatching {
                ivStatus.setImageResource(R.drawable.ic_arrow_right)
                rotateLoading.gone()
                recyclerFlexbox(flexbox)
                flexbox.gone()
            }
        }
    }

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    private fun upKindList(binding: ItemFindBookBinding, item: BookSourcePart, kinds: List<ExploreKind>, exIndex: Int) {
        val flexbox = binding.flexbox
        if (kinds.isEmpty()) {
            //解析不到分类时给一行提示，而不是无声的空白展开行（下拉刷新可重试）
            kotlin.runCatching {
                recyclerFlexbox(flexbox)
                val tv = ItemFilletTextBinding.inflate(inflater, flexbox, false).root
                flexbox.addView(tv)
                flexbox.visible()
                tv.text = context.getString(R.string.explore_kinds_empty)
                tv.gravity = Gravity.CENTER
                tv.isClickable = false
                tv.setOnTouchListener(null)
                tv.alpha = 0.65f
            }
            return
        }
        val sourceUrl = item.bookSourceUrl
        kotlin.runCatching {
            recyclerFlexbox(flexbox)
            flexbox.visible()
            val source by lazy { appDb.bookSourceDao.getBookSource(sourceUrl) }
            val infoMap by lazy {
                exploreInfoMapList[sourceUrl] ?:  InfoMap(sourceUrl).also {
                    exploreInfoMapList.put(sourceUrl, it)
                }
            }
            val sourceJsExtensions by lazy {
                SourceLoginJsExtensions(context as? AppCompatActivity, source,
                    callback = object : SourceLoginJsExtensions.Callback {
                        override fun upUiData(data: Map<String, Any?>?) {
                        }

                        override fun reUiView(deltaUp: Boolean) {
                            refreshExplore(item, exIndex, binding)
                        }
                    })
            }
            kinds.forEach { kind ->
                val type = kind.type
                val title = kind.title
                val viewName = kind.viewName
                when (type) {
                    Type.url -> {
                        val tv = getFlexboxChild(flexbox)
                        flexbox.addView(tv)
                        kind.style().apply {
                            when (this.layout_justifySelf) {
                                "flex_start" -> tv.gravity = Gravity.START
                                "flex_end" -> tv.gravity = Gravity.END
                                else -> tv.gravity = Gravity.CENTER
                            }
                            apply(tv)
                        }
                        if (viewName == null) {
                            tv.text = title
                        } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
                            val n = viewName.substring(1, viewName.length - 1)
                            tv.text = n
                        } else {
                            tv.text = title
                            Coroutine.async(callBack.scope, IO) {
                                evalUiJs(viewName, source, infoMap)
                            }.onSuccess { n ->
                                if (n.isNullOrEmpty()) {
                                    tv.text = "null"
                                } else {
                                    tv.text = n
                                }
                            }.onError { _ ->
                                tv.text = "err"
                            }
                        }
                        tv.setOnClickListener {// 辅助触发无障碍功能正常
                            val url = kind.url ?: return@setOnClickListener
                            if (kind.title.startsWith("ERROR:")) {
                                it.activity?.showDialogFragment(TextDialog("ERROR", url))
                            } else {
                                callBack.openExplore(sourceUrl, kind.title, url)
                            }
                        }
                        tv.setOnTouchListener { view, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    view.isSelected = true
                                }
                                MotionEvent.ACTION_UP -> {
                                    view.isSelected = false
                                    val upTime = System.currentTimeMillis()
                                    if (upTime - lastClickTime < 200) {
                                        return@setOnTouchListener true
                                    }
                                    lastClickTime = upTime
                                    val url = kind.url?.takeIf { it.isNotBlank() } ?: return@setOnTouchListener true
                                    if (kind.title.startsWith("ERROR:")) {
                                        view.activity?.showDialogFragment(TextDialog("ERROR", url))
                                    } else {
                                        callBack.openExplore(sourceUrl, kind.title, url)
                                    }
                                }
                                MotionEvent.ACTION_CANCEL -> {
                                    view.isSelected = false
                                }
                            }
                            return@setOnTouchListener true
                        }
                    }

                    Type.button -> {
                        val tv = getFlexboxChild(flexbox)
                        flexbox.addView(tv)
                        kind.style().apply {
                            when (this.layout_justifySelf) {
                                "flex_start" -> tv.gravity = Gravity.START
                                "flex_end" -> tv.gravity = Gravity.END
                                else -> tv.gravity = Gravity.CENTER
                            }
                            apply(tv)
                        }
                        if (viewName == null) {
                            tv.text = title
                        } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
                            val n = viewName.substring(1, viewName.length - 1)
                            tv.text = n
                        } else {
                            tv.text = title
                            Coroutine.async(callBack.scope, IO) {
                                evalUiJs(viewName, source, infoMap)
                            }.onSuccess { n ->
                                if (n.isNullOrEmpty()) {
                                    tv.text = "null"
                                } else {
                                    tv.text = n
                                }
                            }.onError{ _ ->
                                tv.text = "err"
                            }
                        }
                        tv.setOnClickListener {
                            val action = kind.action?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
                            callBack.scope.launch(IO) {
                                evalButtonClick(action, source, infoMap, title, sourceJsExtensions)
                            }
                        }
                        tv.setOnTouchListener { view, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    view.isSelected = true
                                }
                                MotionEvent.ACTION_UP -> {
                                    view.isSelected = false
                                    val upTime = System.currentTimeMillis()
                                    if (upTime - lastClickTime < 200) {
                                        return@setOnTouchListener true
                                    }
                                    lastClickTime = upTime
                                    val action = kind.action?.takeIf { it.isNotBlank() } ?: return@setOnTouchListener true
                                    callBack.scope.launch(IO) {
                                        evalButtonClick(action, source, infoMap, title, sourceJsExtensions)
                                    }
                                }
                                MotionEvent.ACTION_CANCEL -> {
                                    view.isSelected = false
                                }
                            }
                            return@setOnTouchListener true
                        }
                    }

                    Type.text -> {
                        val ti = getFlexboxChildText(flexbox)
                        flexbox.addView(ti)
                        kind.style().apply {
                            when (this.layout_justifySelf) {
                                "center" -> ti.gravity = Gravity.CENTER
                                "flex_end" -> ti.gravity = Gravity.END
                                else -> ti.gravity = Gravity.START
                            }
                            apply(ti)
                        }
                        if (viewName == null) {
                            ti.hint = title
                        } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
                            val n = viewName.substring(1, viewName.length - 1)
                            ti.hint = n
                        } else {
                            ti.hint = title
                            Coroutine.async(callBack.scope, IO) {
                                evalUiJs(viewName, source, infoMap)
                            }.onSuccess { n ->
                                if (n.isNullOrEmpty()) {
                                    ti.hint = "null"
                                } else {
                                    ti.hint = n
                                }
                            }.onError{ _ ->
                                ti.hint = "err"
                            }
                        }
                        //先移除可能残留的旧 watcher，防止异步回收未完成时旧闭包被 setText 误触发
                        (ti.getTag(R.id.text_watcher) as? TextWatcher)?.let {
                            ti.removeTextChangedListener(it)
                        }
                        ti.setText(infoMap[title])
                        var actionJob: Job? = null
                        val watcher = object : TextWatcher {
                            var content: String? = null
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                                content = s.toString()
                            }

                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                            override fun afterTextChanged(s: Editable?) {
                                val reContent = s.toString()
                                infoMap[title] = reContent
                                if (kind.action != null && reContent != content) {
                                    actionJob?.cancel()
                                    actionJob = callBack.scope.launch(IO) {
                                        delay(600) //防抖
                                        evalButtonClick(kind.action, source, infoMap, title, sourceJsExtensions)
                                        content = reContent
                                    }
                                }
                            }
                        }
                        ti.setTag(R.id.text_watcher, watcher)
                        ti.addTextChangedListener(watcher)
                    }

                    Type.toggle -> {
                        var newName = title
                        var left = true
                        val tv = getFlexboxChild(flexbox)
                        flexbox.addView(tv)
                        kind.style().apply {
                            when (this.layout_justifySelf) {
                                "flex_start" -> tv.gravity = Gravity.START
                                "flex_end" -> tv.gravity = Gravity.END
                                "right" -> left = false
                                else -> tv.gravity = Gravity.CENTER
                            }
                            apply(tv)
                        }
                        val chars = kind.chars?.filterNotNull() ?: listOf("chars","is null")
                        val infoV = infoMap[title]
                        var char = if (infoV.isNullOrEmpty()) {
                            (kind.default ?: chars[0]).also {
                                infoMap[title] = it
                            }
                        } else {
                            infoV
                        }
                        if (viewName == null) {
                            tv.text = if (left) char + title else title + char
                        } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
                            val n = viewName.substring(1, viewName.length - 1)
                            newName = n
                            tv.text = if (left) char + n else n + char
                        } else {
                            tv.text = if (left) char + title else title + char
                            Coroutine.async(callBack.scope, IO) {
                                evalUiJs(viewName, source, infoMap)
                            }.onSuccess { n ->
                                if (n.isNullOrEmpty()) {
                                    tv.text = char + "null"
                                } else {
                                    newName = n
                                    tv.text = if (left) char + n else n + char
                                }
                            }.onError{ _ ->
                                tv.text = char + "err"
                            }
                        }
                        tv.setOnClickListener {
                            val currentIndex = chars.indexOf(char)
                            val nextIndex = (currentIndex + 1) % chars.size
                            char = chars.getOrNull(nextIndex) ?: ""
                            infoMap[title] = char
                            tv.text = if (left) char + newName else newName + char
                            val action = kind.action?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
                            callBack.scope.launch(IO) {
                                evalButtonClick(action, source, infoMap, title, sourceJsExtensions)
                            }
                        }
                        tv.setOnTouchListener { view, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    view.isSelected = true
                                }
                                MotionEvent.ACTION_UP -> {
                                    view.isSelected = false
                                    val upTime = System.currentTimeMillis()
                                    if (upTime - lastClickTime < 200) {
                                        return@setOnTouchListener true
                                    }
                                    lastClickTime = upTime
                                    val currentIndex = chars.indexOf(char)
                                    val nextIndex = (currentIndex + 1) % chars.size
                                    char = chars.getOrNull(nextIndex) ?: ""
                                    infoMap[title] = char
                                    tv.text = if (left) char + newName else newName + char
                                    val action = kind.action?.takeIf { it.isNotBlank() } ?: return@setOnTouchListener true
                                    callBack.scope.launch(IO) {
                                        evalButtonClick(action, source, infoMap, title, sourceJsExtensions)
                                    }
                                }
                                MotionEvent.ACTION_CANCEL -> {
                                    view.isSelected = false
                                }
                            }
                            return@setOnTouchListener true
                        }
                    }

                    Type.select -> {
                        val sl = getFlexboxChildSelect(flexbox)
                        flexbox.addView(sl)
                        kind.style().apply {
                            when (this.layout_justifySelf) {
                                "flex_start" -> sl.gravity = Gravity.START
                                "flex_end" -> sl.gravity = Gravity.END
                                else -> sl.gravity = Gravity.CENTER
                            }
                            apply(sl)
                        }
                        val spName = sl.findViewById<AccentTextView>(R.id.sp_name)
                        if (viewName == null) {
                            spName.text = title
                        } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
                            val n = viewName.substring(1, viewName.length - 1)
                            spName.text = n
                        } else {
                            spName.text = title
                            Coroutine.async(callBack.scope, IO) {
                                evalUiJs(viewName, source, infoMap)
                            }.onSuccess { n ->
                                if (n.isNullOrEmpty()) {
                                    spName.text = "null"
                                } else {
                                    spName.text = n
                                }
                            }.onError{ _ ->
                                spName.text = "err"
                            }
                        }
                        val chars = kind.chars?.filterNotNull() ?: listOf("chars","is null")
                        val adapter = ArrayAdapter(
                            context,
                            R.layout.item_text_common,
                            chars
                        )
                        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
                        val selector = sl.findViewById<AppCompatSpinner>(R.id.sp_type)
                        selector.adapter = adapter
                        val infoV = infoMap[title]
                        val char = if (infoV.isNullOrEmpty()) {
                            (kind.default ?: chars[0]).also {
                                infoMap[title] = it
                            }
                        } else {
                            infoV
                        }
                        val i = chars.indexOf(char)
                        selector.setSelectionSafely(i)
                        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            var isInitializing = true
                            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                                if (isInitializing) { //忽略初始化选择
                                    isInitializing = false
                                    return
                                }
                                infoMap[title] = chars[position]
                                if (kind.action != null) {
                                    callBack.scope.launch(IO) {
                                        evalButtonClick(kind.action, source, infoMap, title, sourceJsExtensions)
                                    }
                                }
                            }
                            override fun onNothingSelected(parent: AdapterView<*>?) {
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun evalUiJs(jsStr: String, source: BookSource?, infoMap: InfoMap): String? {
        val source = source ?: return null
        return try {
            runScriptWithContext {
                source.evalJS(jsStr) {
                    put("infoMap", infoMap)
                }.toString()
            }
        } catch (e: Exception) {
            AppLog.put(source.getTag() + " exploreUi err:" + (e.localizedMessage ?: e.toString()), e)
            null
        }
    }

    private suspend fun evalButtonClick(jsStr: String, source: BaseSource?, infoMap: InfoMap, name: String, java: SourceLoginJsExtensions) {
        val source = source ?: return
        try {
            runScriptWithContext {
                source.evalJS(jsStr) {
                    put("java", java)
                    put("infoMap", infoMap)
                }
            }
        } catch (e: Exception) {
            AppLog.put("ExploreUI Button $name JavaScript error", e)
        }
    }

    @Synchronized
    private fun getFlexboxChild(flexbox: FlexboxLayout): TextView {
        // 回收复用的控件也重新套用主题背景，保证主题/透明度变化后同步
        return (if (recycler.isEmpty()) {
            ItemFilletTextBinding.inflate(inflater, flexbox, false).root
        } else {
            recycler.removeLastElement()
        }).apply {
            background = context.filletControlBackground()
        }
    }

    @Synchronized
    private fun getFlexboxChildText(flexbox: FlexboxLayout): AutoCompleteTextView {
        return (if (textRecycler.isEmpty()) {
            ItemFilletCompleteTextBinding.inflate(inflater, flexbox, false).root
        } else {
            textRecycler.removeLastElement()
        }).apply {
            background = context.filletControlBackground()
        }
    }

    @Synchronized
    private fun getFlexboxChildSelect(flexbox: FlexboxLayout): LinearLayout {
        return (if (selectRecycler.isEmpty()) {
            ItemFilletSelectorSingleBinding.inflate(inflater, flexbox, false).root
        } else {
            selectRecycler.removeLastElement()
        }).apply {
            background = context.filletControlBackground()
        }
    }

    @Synchronized
    private fun recyclerFlexbox(flexbox: FlexboxLayout) {
        val children = flexbox.children.toList()
        if (children.isEmpty()) return
        flexbox.removeAllViews()
        //同步搬入回收池：若放入协程，锁在真正修改池之前就已释放，
        //与 getFlexboxChild*/upKindList 交叉执行会重复入池/错位
        for (child in children) {
            when (child) {
                is AutoCompleteTextView -> {
                    val watcher = child.getTag(R.id.text_watcher) as? TextWatcher
                    if (watcher != null) {
                        child.removeTextChangedListener(watcher)
                        child.setTag(R.id.text_watcher, null)
                    }
                    textRecycler.add(child)
                }
                is TextView -> {
                    child.setOnTouchListener(null)
                    child.setOnClickListener(null)
                    recycler.add(child)
                }
                is LinearLayout -> {
                    child.findViewById<AppCompatSpinner>(R.id.sp_type)?.onItemSelectedListener = null
                    selectRecycler.add(child)
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemFindBookBinding) {
        binding.apply {
            llTitle.setOnClickListener {
                val position = holder.layoutPosition
                val oldEx = exIndex
                exIndex = if (exIndex == position) -1 else position
                expandedSourceUrl = if (exIndex == -1) null else getItem(position)?.bookSourceUrl
                notifyItemChanged(oldEx, false)
                if (exIndex != -1) {
                    scrollTo = position
                    callBack.scrollTo(position)
                    notifyItemChanged(position, false)
                }
            }
            llTitle.onLongClick {
                showMenu(binding, holder.layoutPosition)
            }
        }
    }

    fun compressExplore(): Boolean {
        return if (exIndex < 0) {
            false
        } else {
            val oldExIndex = exIndex
            exIndex = -1
            expandedSourceUrl = null
            notifyItemChanged(oldExIndex)
            true
        }
    }

    /**
     * 数据集变化（diff/排序/增删）落地后，把展开锚点按源 URL 重新定位，
     * 防止 exIndex 停在旧位置造成展开行错行或丢失。由 onCurrentListChanged 调用。
     */
    private fun reanchorExpanded() {
        val url = expandedSourceUrl ?: return
        val oldIndex = exIndex
        if (oldIndex >= 0 && getItem(oldIndex)?.bookSourceUrl == url) return
        val newIndex = getItems().indexOfFirst { it.bookSourceUrl == url }
        if (newIndex < 0) {
            exIndex = -1
            expandedSourceUrl = null
            if (oldIndex >= 0) notifyItemChanged(oldIndex, false)
            return
        }
        exIndex = newIndex
        if (oldIndex >= 0 && oldIndex != newIndex) notifyItemChanged(oldIndex, false)
        notifyItemChanged(newIndex, false)
    }

    /**
     * 记住视图重建前的状态（展开的源、滚动位置），首个非空列表落地时恢复。
     * 展开源恢复成功则滚动到该行（与手动展开行为一致），否则退回滚动位置。
     */
    fun setPendingViewRestore(expandedSourceUrl: String?, scrollPosition: Int) {
        pendingViewRestore = expandedSourceUrl to scrollPosition
    }

    private fun consumePendingViewRestore() {
        val pending = pendingViewRestore ?: return
        if (isEmpty()) return
        pendingViewRestore = null
        if (pending.first == null || !restoreExpanded(pending.first)) {
            val saved = pending.second
            if (saved >= 0) {
                val target = saved.coerceAtMost(itemCount - 1)
                if (target >= 0) callBack.scrollTo(target)
            }
        }
    }

    /**
     * 视图重建后恢复记忆的展开源；找不到返回 false。
     * 需当前没有已展开行（exIndex < 0）才生效。
     */
    private fun restoreExpanded(sourceUrl: String?): Boolean {
        if (sourceUrl == null || exIndex >= 0) return false
        val index = getItems().indexOfFirst { it.bookSourceUrl == sourceUrl }
        if (index < 0) return false
        exIndex = index
        expandedSourceUrl = sourceUrl
        notifyItemChanged(index, false)
        callBack.scrollTo(index)
        return true
    }

    override fun onCurrentListChanged() {
        super.onCurrentListChanged()
        //在 items 真正落地（diff 派发完成）后执行，此时按位置查询才是新数据
        reanchorExpanded()
        consumePendingViewRestore()
    }

    fun refreshExpandedIfNoKinds(): Boolean {
        val position = exIndex
        if (position < 0) return false
        val source = getItem(position) ?: return false
        if (!sourceKinds[source.bookSourceUrl].isNullOrEmpty()) return false
        refreshExplore(source, position, null)
        return true
    }

    fun onPause() {
        sourceKinds.clear()
        saveInfoMapJob?.cancel()
        saveInfoMapJob = callBack.scope.launch {
            exploreInfoMapList.snapshot().filter { (_, infoMap) -> infoMap.needSave }.map { (_, infoMap) ->
                launch {
                    infoMap.saveNow()
                }
            }.joinAll()
        }
    }

    private suspend fun loadExploreKinds(source: BookSourcePart): List<ExploreKind> {
        sourceKinds[source.bookSourceUrl]?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        val kinds = source.exploreKinds()
        if (kinds.isNotEmpty()) {
            sourceKinds[source.bookSourceUrl] = kinds
            return kinds
        }
        source.clearExploreKindsCache()
        return source.exploreKinds().also {
            sourceKinds[source.bookSourceUrl] = it
        }
    }

    private fun refreshExplore(source: BookSourcePart, position: Int, binding: ItemFindBookBinding?) {
        binding?.rotateLoading?.visible()
        Coroutine.async(callBack.scope) {
            source.clearExploreKindsCache()
            sourceKinds[source.bookSourceUrl] = source.exploreKinds()
        }.onSuccess {
            notifyItemChanged(position, false)
        }.onFinally {
            binding?.rotateLoading?.gone()
        }
    }

    private fun showMenu(binding: ItemFindBookBinding, position: Int): Boolean {
        val source = getItem(position) ?: return true
        val actions = buildList {
            add(ModernActionPopup.Action(context.getString(R.string.edit)) {
                callBack.editSource(source.bookSourceUrl)
            })
            add(ModernActionPopup.Action(context.getString(R.string.to_top)) {
                callBack.toTop(source)
            })
            if (source.hasLoginUrl) {
                add(ModernActionPopup.Action(context.getString(R.string.login)) {
                    context.startActivity<SourceLoginActivity> {
                        putExtra("type", "bookSource")
                        putExtra("key", source.bookSourceUrl)
                    }
                })
            }
            add(ModernActionPopup.Action(context.getString(R.string.search)) {
                callBack.searchBook(source)
            })
            add(ModernActionPopup.Action(context.getString(R.string.refresh)) {
                refreshExplore(source, position, binding)
            })
            add(ModernActionPopup.Action(context.getString(R.string.delete)) {
                callBack.deleteSource(source)
            })
        }
        modernMenuPopup = ModernActionPopup.show(binding.llTitle, actions, modernMenuPopup)
        return true
    }

    interface CallBack {
        val scope: CoroutineScope
        fun scrollTo(pos: Int)
        fun openExplore(sourceUrl: String, title: String, exploreUrl: String?)
        fun editSource(sourceUrl: String)
        fun toTop(source: BookSourcePart)
        fun deleteSource(source: BookSourcePart)
        fun searchBook(bookSource: BookSourcePart)
    }
}
