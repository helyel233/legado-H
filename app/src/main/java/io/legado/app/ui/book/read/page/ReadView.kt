package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import io.legado.app.R
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.SelectionEdgeAutoPager
import io.legado.app.ui.book.read.page.api.DataSource
import io.legado.app.ui.book.read.page.delegate.CoverPageDelegate
import io.legado.app.ui.book.read.page.delegate.DoublePageSimulationPageDelegate
import io.legado.app.ui.book.read.page.delegate.HorizontalPageDelegate
import io.legado.app.ui.book.read.page.delegate.LinkedCoverPageDelegate
import io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegate
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate
import io.legado.app.ui.book.read.page.delegate.SlidePageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.ReadSelectionPosition
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.utils.dpToPx
import android.graphics.Paint
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.utils.activity
import io.legado.app.utils.invisible
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.throttle
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 阅读视图
 */
class ReadView(context: Context, attrs: AttributeSet) :
    FrameLayout(context, attrs),
    DataSource, LayoutProgressListener {

    val callBack: CallBack get() = activity as CallBack
    var pageFactory: TextPageFactory = TextPageFactory(this)
    var pageDelegate: PageDelegate? = null
        private set(value) {
            field?.onDestroy()
            field = null
            field = value
            // Binding content from the constructor is both pointless and dangerous: the
            // view has no size, is not attached, and the hosting activity's binding is
            // still being inflated — a content bind reaching back into it re-enters that
            // inflation and rebuilds the reader recursively. Real content arrives once the
            // book loads; later delegate swaps (page-animation changes) still rebind.
            if (constructed) upContent()
        }
    private var constructed = false
    override var isScroll = false
    val prevPage by lazy { PageView(context) }
    val curPage by lazy { PageView(context) }
    val nextPage by lazy { PageView(context) }
    val defaultAnimationSpeed: Int
        get() = ReadBookConfig.pageAnimationSpeed.durationMillis
    private var pressDown = false
    private var isMove = false
    private var ignoreMandatoryGestureTouch = false
    private var audioDragging = false

    //起始点
    var startX: Float = 0f
    var startY: Float = 0f

    //上一个触碰点
    var lastX: Float = 0f
    var lastY: Float = 0f

    //触碰点
    var touchX: Float = 0f
    var touchY: Float = 0f

    //是否停止动画动作
    var isAbortAnim = false

    //长按
    private var longPressed = false
    private val longPressTimeout = 600L
    private val longPressRunnable = Runnable {
        longPressed = true
        onLongPress()
    }
    var isTextSelected = false
    private var pressOnTextSelected = false
    private val initialTextPos = TextPos(0, 0, 0)
    private var selectionHandle: Boolean? = null
    private var selectionTurning = false
    private val selectionAutoPager = SelectionEdgeAutoPager(this) { direction, x, y, complete ->
        complete(turnSelectionPage(direction, x, y))
    }

    fun beginSelectionHandleDrag(start: Boolean) {
        if (!isTextSelected || curPage.hasNativeSelection()) return
        selectionHandle = start
        selectionAutoPager.begin()
    }

    fun moveSelectionHandle(x: Float, y: Float) {
        if (!isTextSelected || selectionHandle == null) return
        // 拖动端受 reverse cursor 影响，实际生效端点要在移动前判定
        val effectiveStart = if (selectionHandle == true) {
            !curPage.getReverseStartCursor()
        } else {
            curPage.getReverseEndCursor()
        }
        updateSelectionAt(x, y)
        selectionAutoPager.update(x, y, curPage.selectionTop, curPage.selectionBottom)
        // 放大镜对准手指正在拖的那一端：本次拖动实际生效了端点移动时用其记录，
        // 移动被吸附早退（端点没变）时回退到当前选区该端的锚点
        val anchor = curPage.takeLastMovedEndpointAnchor()
            ?: curPage.getSelectionEndpointAnchor(effectiveStart)
        anchor?.let { showSelectionMagnifier(it.x, it.y) }
    }

    fun endSelectionHandleDrag() {
        // 手柄拖动的触摸由 Activity 消费，不会走到本视图的 ACTION_UP，
        // 必须在这里收掉放大镜，否则气泡会一直残留在页面上
        dismissSelectionMagnifier()
        selectionAutoPager.cancel()
        selectionHandle = null
        curPage.resetReverseCursor()
        if (isTextSelected) curPage.refreshSelectionHandles()
    }

    private fun updateSelectionAt(x: Float, y: Float) {
        when (selectionHandle) {
            true -> if (curPage.getReverseStartCursor()) curPage.selectEndMove(x, y) else curPage.selectStartMove(x, y)
            false -> if (curPage.getReverseEndCursor()) curPage.selectStartMove(x, y) else curPage.selectEndMove(x, y)
            null -> selectText(x, y)
        }
    }

    private fun turnSelectionPage(direction: Int, x: Float, y: Float): Boolean {
        if (!isTextSelected || curPage.hasNativeSelection() || selectionTurning ||
            pageDelegate?.isRunning == true || direction !in listOf(-1, 1)
        ) return false
        val chapter = currentChapter ?: return false
        if (curPage.textPage.textChapter !== chapter) return false
        val before = pageIndex
        val step = if (ChapterProvider.doublePage && !isScroll) 2 else 1
        val target = before + direction * step
        // Never fetch a chapter or await progressive layout while a finger is held.
        if (chapter.getPage(target) == null) return false
        selectionTurning = true
        try {
            ReadBook.setPageIndex(target)
            initialTextPos.relativePagePos -= target - before
            upContent(0, true)
            updateSelectionAt(x, y)
            invalidateTextPage()
            invalidate()
        } finally {
            selectionTurning = false
        }
        return pageIndex == target && currentChapter === chapter
    }

    private val slopSquare by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private var pageSlopSquare: Int = slopSquare
    var pageSlopSquare2: Int = pageSlopSquare * pageSlopSquare
    private var pageTouchClick: Int = 0
    private val tlRect = RectF()
    private val tcRect = RectF()
    private val trRect = RectF()
    private val mlRect = RectF()
    private val mcRect = RectF()
    private val mrRect = RectF()
    private val blRect = RectF()
    private val bcRect = RectF()
    private val brRect = RectF()
    private val boundary by lazy { BreakIterator.getWordInstance(Locale.getDefault()) }
    private val upProgressThrottle = throttle(200) { post { upProgress() } }
    /** 选区放大镜浮层，由 Activity 在布局里放在选择手柄之上 */
    var magnifierOverlay: SelectionMagnifierView? = null

    /** 放大镜是否显示、取景锚点（本视图坐标，取选择端点的选区边界与行中线） */
    private var magnifierVisible = false
    private var magnifierAnchorX = 0f
    private var magnifierAnchorY = 0f

    /** 放大镜绘制参数：圆形气泡、与文字的间隙、放大倍数、锚点在气泡内的偏移占比 */
    private val magnifierRect = RectF()
    private val magnifierPath = Path()
    private val magnifierBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val magnifierBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.dpToPx().toFloat()
    }
    private val magnifierZoom = 1.6f
    private val magnifierAnchorInsideRatio = 0.45f

    /** 气泡直径按屏幕取，保证不同字号下都能看到几个字；与行的间隙按行高取 */
    private val magnifierSizeRatio = 0.45f
    private val magnifierGapRatio = 0.25f
    val autoPager = AutoPager(this)
    val isAutoPage get() = autoPager.isRunning
    private var pageTurnPrewarmGeneration = 0L
    private val pageTurnPrewarmCurRunnable = Runnable { runPageTurnPrewarmStep(0) }
    private val pageTurnPrewarmNextRunnable = Runnable { runPageTurnPrewarmStep(1) }
    private val pageTurnPrewarmPrevRunnable = Runnable { runPageTurnPrewarmStep(2) }
    private val pageTurnPrewarmAllRunnable = Runnable { runPageTurnPrewarmStep(3) }

    private fun runPageTurnPrewarmStep(step: Int) {
        if (isScroll) return
        val delegate = pageDelegate ?: return
        if (delegate.isRunning || delegate.isStarted) return
        // One page per frame-ish step: avoids multi-page screenshot hitch on open.
        delegate.prewarmPageSnapshots(step)
    }

    internal val isHorizontalPageTurnActive: Boolean
        get() = (pageDelegate as? HorizontalPageDelegate)?.let {
            pressDown || it.isRunning || it.isStarted
        } == true

    internal fun freezeAdvancedTitleAnimationsForPageTurn() {
        curPage.freezeAdvancedTitleAnimationsForPageTurn()
        nextPage.freezeAdvancedTitleAnimationsForPageTurn()
        prevPage.freezeAdvancedTitleAnimationsForPageTurn()
    }

    internal fun resumeAdvancedTitleAnimationsAfterPageTurn() {
        curPage.resumeAdvancedTitleAnimationsAfterPageTurn()
        nextPage.resumeAdvancedTitleAnimationsAfterPageTurn()
        prevPage.resumeAdvancedTitleAnimationsAfterPageTurn()
        postInvalidateOnAnimation()
    }

    internal fun flushPendingAdvancedTitleCompositions() {
        if (isHorizontalPageTurnActive) return
        curPage.flushPendingAdvancedTitleCompositions()
        nextPage.flushPendingAdvancedTitleCompositions()
        prevPage.flushPendingAdvancedTitleCompositions()
        postInvalidateOnAnimation()
    }

    private fun disposeAdvancedTitleRequests() {
        curPage.disposeAdvancedTitleRequests()
        nextPage.disposeAdvancedTitleRequests()
        prevPage.disposeAdvancedTitleRequests()
    }

    init {
        if (!isInEditMode) {
            upBg()
            setWillNotDraw(false)
            upPageAnim()
            upPageSlopSquare()
        }
        addView(nextPage)
        addView(curPage)
        addView(prevPage)
        prevPage.invisible()
        nextPage.invisible()
        curPage.markAsMainView()
        upPageTouchClick()
        constructed = true
    }

    private fun setRect9x() {
        tlRect.set(0f + pageTouchClick, 0f, width * 0.33f, height * 0.33f)
        tcRect.set(width * 0.33f, 0f, width * 0.66f, height * 0.33f)
        trRect.set(width * 0.36f, 0f, width.toFloat() - pageTouchClick, height * 0.33f)
        mlRect.set(0f + pageTouchClick, height * 0.33f, width * 0.33f, height * 0.66f)
        mcRect.set(width * 0.33f, height * 0.33f, width * 0.66f, height * 0.66f)
        mrRect.set(width * 0.66f, height * 0.33f, width.toFloat() - pageTouchClick, height * 0.66f)
        blRect.set(0f + pageTouchClick, height * 0.66f, width * 0.33f, height.toFloat())
        bcRect.set(width * 0.33f, height * 0.66f, width * 0.66f, height.toFloat())
        brRect.set(width * 0.66f, height * 0.66f, width.toFloat() - pageTouchClick, height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (constructed && (w != oldw || h != oldh)) cancelSelect()
        setRect9x()
        prevPage.x = -w.toFloat()
        pageDelegate?.setViewSize(w, h)
        if (w > 0 && h > 0) {
            upBg()
            callBack.upSystemUiVisibility()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        pageDelegate?.onDraw(canvas)
        autoPager.onDraw(canvas)
        // 放大镜是自绘的（气泡里画的是当前正文），内容滚动时跟着重画，保持和页面同一帧
        if (magnifierVisible) magnifierOverlay?.invalidate()
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager.computeOffset()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }

    /**
     * 触摸事件
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val bottomInset = rootWindowInsets
                    ?.getInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures())
                    ?.bottom
                    ?: 0
                ignoreMandatoryGestureTouch = bottomInset > 0 && event.y > height - bottomInset
                if (ignoreMandatoryGestureTouch) return true
            } else if (ignoreMandatoryGestureTouch) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    ignoreMandatoryGestureTouch = false
                }
                return true
            }
        }

        //在多点触控时，事件不走ACTION_DOWN分支而产生的特殊事件处理
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            selectionAutoPager.cancel()
            pageDelegate?.onTouch(event)
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 音频块进度条拖动/点击优先级最高：按下即 seek，不触发翻页/选区；
                // 长按计时照常启动：音频块长按需要弹出菜单（如查看备注），拖动或抬手时再取消。
                // setStartPoint 必须调用：长按回调按 startX/startY 定位，否则会命中上一次手势的旧位置
                val trackHit = curPage.hitAudioTrack(event.x, event.y)
                if (trackHit != null) {
                    audioDragging = true
                    curPage.audioTrackSeek(trackHit, event.x)
                    setStartPoint(event.x, event.y, false)
                    postDelayed(longPressRunnable, longPressTimeout)
                    return true
                }
                callBack.screenOffTimerStart()
                if (isTextSelected) {
                    curPage.cancelSelect()
                    isTextSelected = false
                    // 选中态被新的按下清掉，放大镜同步收起，
                    // 否则留下 overlay VISIBLE 但不再绘制的脏状态
                    dismissSelectionMagnifier()
                    pressOnTextSelected = true
                } else {
                    pressOnTextSelected = false
                }
                longPressed = false
                postDelayed(longPressRunnable, longPressTimeout)
                pressDown = true
                isMove = false
                pageDelegate?.onTouch(event)
                pageDelegate?.onDown()
                setStartPoint(event.x, event.y, false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (audioDragging) {
                    // 拖动即取消长按：音频块长按只在按住不动时生效
                    removeCallbacks(longPressRunnable)
                    curPage.hitAudioTrack(event.x, event.y)?.let {
                        curPage.audioTrackSeek(it, event.x)
                    }
                    return true
                }
                if (!pressDown) return true
                val absX = abs(startX - event.x)
                val absY = abs(startY - event.y)
                if (!isMove) {
                    isMove = absX > slopSquare || absY > slopSquare
                }
                if (isMove) {
                    longPressed = false
                    removeCallbacks(longPressRunnable)
                    if (isTextSelected) {
                        selectText(event.x, event.y)
                        selectionAutoPager.update(event.x, event.y, curPage.selectionTop, curPage.selectionBottom)
                    } else {
                        pageDelegate?.onTouch(event)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                selectionAutoPager.cancel()
                dismissSelectionMagnifier()
                if (audioDragging) {
                    audioDragging = false
                    removeCallbacks(longPressRunnable)
                    curPage.hitAudioTrack(event.x, event.y)?.let {
                        curPage.audioTrackSeek(it, event.x)
                    }
                    return true
                }
                callBack.screenOffTimerStart()
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (!pageDelegate!!.isMoved && !isMove) {
                    if (!longPressed && !pressOnTextSelected) {
                        if (!curPage.onClick(startX, startY)) {
                            onSingleTapUp()
                        }
                        // A tap may synchronously start a page turn. This only commits when
                        // release really left the horizontal delegate idle.
                        flushPendingAdvancedTitleCompositions()
                        return true
                    }
                }
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
                flushPendingAdvancedTitleCompositions()
            }

            MotionEvent.ACTION_CANCEL -> {
                selectionAutoPager.cancel()
                dismissSelectionMagnifier()
                audioDragging = false
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
                autoPager.resume()
                flushPendingAdvancedTitleCompositions()
            }
        }
        return true
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        selectionAutoPager.cancel()
        selectionHandle = null
        if (isTextSelected) {
            dismissSelectionMagnifier()
            curPage.cancelSelect(clearSearchResult)
            isTextSelected = false
        }
    }

    /**
     * 更新状态栏
     */
    fun upStatusBar() {
        curPage.upStatusBar()
        prevPage.upStatusBar()
        nextPage.upStatusBar()
    }

    /**
     * 保存开始位置
     */
    fun setStartPoint(x: Float, y: Float, invalidate: Boolean = true) {
        startX = x
        startY = y
        lastX = x
        lastY = y
        touchX = x
        touchY = y

        if (invalidate) {
            invalidate()
        }
    }

    /**
     * 保存当前位置
     */
    fun setTouchPoint(x: Float, y: Float, invalidate: Boolean = true) {
        lastX = touchX
        lastY = touchY
        touchX = x
        touchY = y
        if (invalidate) {
            postInvalidateOnAnimation()
        }
        pageDelegate?.onScroll()
        val offset = touchY - lastY
        touchY -= offset - offset.toInt()
    }

    /**
     * 长按选择
     */
    private fun onLongPress() {
        kotlin.runCatching {
            val handled = curPage.longPress(startX, startY) { textPos: TextPos ->
                isTextSelected = true
                pressOnTextSelected = true
                initialTextPos.upData(textPos)
                val startPos = textPos.copy()
                val endPos = textPos.copy()
                val page = curPage.relativePage(textPos.relativePagePos)
                val stringBuilder = StringBuilder()
                var cIndex = textPos.columnIndex
                var lineStart = textPos.lineIndex
                var lineEnd = textPos.lineIndex
                for (index in textPos.lineIndex - 1 downTo 0) {
                    val textLine = page.getLine(index)
                    if (textLine.isParagraphEnd) {
                        break
                    } else {
                        stringBuilder.insert(0, textLine.text)
                        lineStart -= 1
                        cIndex += textLine.charSize
                    }
                }
                for (index in textPos.lineIndex until page.lineSize) {
                    val textLine = page.getLine(index)
                    stringBuilder.append(textLine.text)
                    lineEnd += 1
                    if (textLine.isParagraphEnd) {
                        break
                    }
                }
                var start: Int
                var end: Int
                boundary.setText(stringBuilder.toString())
                start = boundary.first()
                end = boundary.next()
                while (end != BreakIterator.DONE) {
                    if (cIndex in start until end) {
                        break
                    }
                    start = end
                    end = boundary.next()
                }
                kotlin.run {
                    var ci = 0
                    for (index in lineStart..lineEnd) {
                        val textLine = page.getLine(index)
                        for (j in textLine.columns.indices) {
                            if (ci == start) {
                                startPos.lineIndex = index
                                startPos.columnIndex = j
                            } else if (ci == end - 1) {
                                endPos.lineIndex = index
                                endPos.columnIndex = j
                                return@run
                            }
                            val column = textLine.getColumn(j)
                            if (column is TextBaseColumn) {
                                ci += column.charData.length
                            } else {
                                ci++
                            }
                        }
                    }
                }
                curPage.selectStartMoveIndex(startPos)
                curPage.selectEndMoveIndex(endPos)
                selectionHandle = null
                selectionAutoPager.begin()
                // 放大镜按选择端点取景（选区边界 + 所在行中线），不按手指落点
                val anchor = curPage.getSelectEndpointAnchor(startPos, true)
                showSelectionMagnifier(anchor.x, anchor.y)
            }
            if (handled && curPage.hasNativeSelection()) {
                isTextSelected = true
                pressOnTextSelected = true
                post { callBack.showTextActionMenu() }
            }
        }
    }

    /**
     * 显示选区放大镜
     *
     * @param anchorX 取景锚点 x（选择端点的选区边界）
     * @param anchorY 取景锚点 y（选择端点所在行的中线）
     */
    fun showSelectionMagnifier(anchorX: Float, anchorY: Float) {
        magnifierAnchorX = anchorX.coerceIn(0f, width.toFloat())
        magnifierAnchorY = anchorY.coerceIn(0f, height.toFloat())
        val overlay = magnifierOverlay ?: return
        if (!magnifierVisible) {
            magnifierVisible = true
            overlay.visibility = View.VISIBLE
        }
        overlay.invalidate()
    }

    /**
     * 隐藏选区放大镜
     */
    private fun dismissSelectionMagnifier() {
        if (!magnifierVisible) return
        magnifierVisible = false
        magnifierOverlay?.visibility = View.GONE
    }

    /**
     * 画选区放大镜（由 [SelectionMagnifierView] 调用）
     *
     * 圆形气泡，整体浮在端点所在行的上方（手指在行上、气泡在行上方，不会挡住），
     * 上方放不下时才放到该行下方。气泡里直接画当前页正文，和屏幕同一帧同一份选中状态。
     */
    fun drawSelectionMagnifier(canvas: Canvas) {
        if (!magnifierVisible || !isTextSelected) return
        val lineHeight = curPage.textPage.lines.firstOrNull()?.height ?: 0f
        if (lineHeight <= 0f) return
        val diameter = min(width * magnifierSizeRatio, height * 0.26f)
        if (diameter <= 0f) return
        val gap = lineHeight * magnifierGapRatio
        val radius = diameter / 2f
        val centerX = (magnifierAnchorX - radius).coerceIn(0f, max(0f, width - diameter)) + radius
        val lineTop = magnifierAnchorY - lineHeight / 2f
        val lineBottom = magnifierAnchorY + lineHeight / 2f
        val topIfAbove = lineTop - gap - diameter
        val above = topIfAbove >= 0f
        val top = if (above) {
            topIfAbove
        } else {
            (lineBottom + gap).coerceIn(0f, max(0f, height - diameter))
        }
        magnifierRect.set(centerX - radius, top, centerX + radius, top + diameter)
        magnifierPath.reset()
        magnifierPath.addCircle(centerX, top + radius, radius, Path.Direction.CW)
        // 锚点映射到圆心偏下（气泡在行上方）/偏上（气泡在行下方），文字朝远离手指的方向铺开
        val anchorInsideY = top + radius + if (above) {
            radius * magnifierAnchorInsideRatio
        } else {
            -radius * magnifierAnchorInsideRatio
        }
        canvas.withClip(magnifierPath) {
            magnifierBgPaint.color = ReadBookConfig.bgMeanColor
            drawCircle(centerX, top + radius, radius, magnifierBgPaint)
            withTranslation(centerX, anchorInsideY) {
                scale(magnifierZoom, magnifierZoom)
                translate(-magnifierAnchorX, -magnifierAnchorY)
                curPage.drawContentText(this)
            }
        }
        magnifierBorderPaint.color = ReadBookConfig.textColor
        magnifierBorderPaint.alpha = 0x40
        canvas.drawPath(magnifierPath, magnifierBorderPaint)
    }

    /**
     * 单击
     */
    private fun onSingleTapUp() {
        when {
            isTextSelected -> Unit
            mcRect.contains(startX, startY) -> if (!isAbortAnim) {
                click(AppConfig.clickActionMC)
            }

            bcRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBC)
            }

            blRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBL)
            }

            brRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBR)
            }

            mlRect.contains(startX, startY) -> {
                click(AppConfig.clickActionML)
            }

            mrRect.contains(startX, startY) -> {
                click(AppConfig.clickActionMR)
            }

            tlRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTL)
            }

            tcRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTC)
            }

            trRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTR)
            }
        }
    }

    /**
     * 点击
     */
    private fun click(action: Int) {
        when (action) {
            0 -> {
                pageDelegate?.dismissSnackBar()
                callBack.showActionMenu()
            }

            1 -> pageDelegate?.nextPageByAnim(defaultAnimationSpeed)
            2 -> pageDelegate?.prevPageByAnim(defaultAnimationSpeed)
            3 -> ReadBook.moveToNextChapter(true)
            4 -> ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            5 -> ReadAloud.prevParagraph(context)
            6 -> ReadAloud.nextParagraph(context)
            7 -> callBack.addBookmark()
            8 -> activity?.showDialogFragment(ContentEditDialog())
            9 -> callBack.changeReplaceRuleState()
            10 -> callBack.openChapterList()
            11 -> callBack.openSearchActivity(null)
            12 -> ReadBook.syncProgress(
                { progress -> callBack.sureNewProgress(progress) },
                { context.longToastOnUi(context.getString(R.string.upload_book_success)) },
                { context.longToastOnUi(context.getString(R.string.sync_book_progress_success)) })

            13 -> {
                if (BaseReadAloudService.isPlay()) {
                    ReadAloud.pause(context)
                } else {
                    ReadAloud.resume(context)
                }
            }
        }
    }

    /**
     * 选择文本
     */
    private fun selectText(x: Float, y: Float) {
        curPage.selectText(x, y) { textPos ->
            val compare = initialTextPos.compare(textPos)
            val dragStartPoint = compare > 0
            when {
                compare > 0 -> {
                    curPage.selectStartMoveIndex(textPos)
                    curPage.selectEndMoveIndex(
                        initialTextPos.relativePagePos,
                        initialTextPos.lineIndex,
                        initialTextPos.columnIndex - 1
                    )
                }

                else -> {
                    curPage.selectStartMoveIndex(initialTextPos)
                    curPage.selectEndMoveIndex(textPos)
                }
            }
            // 放大镜按端点锚点取景（手指落在行间空隙时端点会吸附到相邻行/列），与高亮位置严格一致
            val anchor = curPage.getSelectEndpointAnchor(textPos, dragStartPoint)
            showSelectionMagnifier(anchor.x, anchor.y)
        }
    }

    /**
     * 销毁事件
     */
    fun onDestroy() {
        selectionAutoPager.cancel()
        removeCallbacks(longPressRunnable)
        dismissSelectionMagnifier()
        disposeAdvancedTitleRequests()
        pageDelegate?.onDestroy()
        curPage.cancelSelect()
        invalidateTextPage()
    }

    /**
     * 翻页动画完成后事件
     * @param direction 翻页方向
     */
    fun fillPage(direction: PageDirection): Boolean {
        return when (direction) {
            PageDirection.PREV -> {
                pageFactory.moveToPrev(true)
            }

            PageDirection.NEXT -> {
                pageFactory.moveToNext(true)
            }

            else -> false
        }
    }

    /**
     * 更新翻页动画
     */
    fun upPageAnim(upRecorder: Boolean = false) {
        isScroll = ReadBook.pageAnim() == 3
        ChapterProvider.upLayout()
        // pageDelegate's setter immediately rebinds content. Propagate the new coordinate
        // mode first so synchronous advanced-title fallbacks use the matching layout.
        curPage.setIsScroll(isScroll)
        when (ReadBook.pageAnim()) {
            PageAnim.coverPageAnim -> if (pageDelegate !is CoverPageDelegate) {
                pageDelegate = CoverPageDelegate(this)
            }

            PageAnim.linkedCoverPageAnim -> if (pageDelegate !is LinkedCoverPageDelegate) {
                pageDelegate = LinkedCoverPageDelegate(this)
            }

            PageAnim.slidePageAnim -> if (pageDelegate !is SlidePageDelegate) {
                pageDelegate = SlidePageDelegate(this)
            }

            PageAnim.simulationPageAnim -> {
                val useDoublePageSimulation = ChapterProvider.doublePage && !isScroll
                if (useDoublePageSimulation) {
                    if (pageDelegate !is DoublePageSimulationPageDelegate) {
                        pageDelegate = DoublePageSimulationPageDelegate(this)
                    }
                } else if (pageDelegate !is SimulationPageDelegate) {
                    pageDelegate = SimulationPageDelegate(this)
                }
            }

            PageAnim.scrollPageAnim -> if (pageDelegate !is ScrollPageDelegate) {
                pageDelegate = ScrollPageDelegate(this)
            }

            else -> if (pageDelegate !is NoAnimPageDelegate) {
                pageDelegate = NoAnimPageDelegate(this)
            }
        }
        (pageDelegate as? ScrollPageDelegate)?.noAnim = AppConfig.noAnimScrollPage
        if (upRecorder) {
            (pageDelegate as? HorizontalPageDelegate)?.upRecorder()
            autoPager.upRecorder()
        }
        pageDelegate?.setViewSize(width, height)
        if (isScroll) {
            curPage.setAutoPager(autoPager)
        } else {
            curPage.setAutoPager(null)
        }
    }

    /**
     * 更新阅读内容
     * @param relativePosition 相对位置 -1 上一页 0 当前页 1 下一页
     * @param resetPageOffset 滚动阅读是是否重置位置
     */
    /**
     * Pre-capture cover/slide/simulation page bitmaps after Lottie binds, so the first
     * finger flip does not stall on full-page Lottie hierarchy draw.
     */
    /**
     * Disabled automatic full-page ARGB prewarm.
     * Open/chapter-switch already constructs 3 live PageViews; eagerly screenshot-ing them
     * caused black ReadBook screens and process kills (no Java crash dialog) on mid-range
     * Android 12 / HarmonyOS devices. Page-turn still screenshots on demand in setBitmap().
     */
    fun schedulePageTurnPrewarm() {
        pageTurnPrewarmGeneration++
        removeCallbacks(pageTurnPrewarmCurRunnable)
        removeCallbacks(pageTurnPrewarmNextRunnable)
        removeCallbacks(pageTurnPrewarmPrevRunnable)
        removeCallbacks(pageTurnPrewarmAllRunnable)
    }

    fun ensurePageTurnSnapshotsForGesture() = Unit

    override fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
        if (relativePosition == 0 && isTextSelected && !selectionTurning &&
            curPage.textPage !== pageFactory.curPage
        ) cancelSelect()
        post {
            curPage.setContentDescription(pageFactory.curPage.text)
        }
        if (isScroll && !isAutoPage) {
            if (relativePosition == 0) {
                curPage.setContent(pageFactory.curPage, pageFactory.curPairPage, resetPageOffset)
            } else {
                curPage.invalidateContentView()
            }
        } else {
            when (relativePosition) {
                -1 -> prevPage.setContent(pageFactory.prevPage, pageFactory.prevPairPage)
                1 -> nextPage.setContent(pageFactory.nextPage, pageFactory.nextPairPage)
                else -> {
                    // Main first for body text paint; advanced Lottie binds idle on each PageView.
                    curPage.setContent(pageFactory.curPage, pageFactory.curPairPage, resetPageOffset)
                    nextPage.setContent(pageFactory.nextPage, pageFactory.nextPairPage)
                    prevPage.setContent(pageFactory.prevPage, pageFactory.prevPairPage)
                }
            }
        }
        // Keep the first frame on the UI draw path. Starting recorder work here races the
        // initial draw against the same Picture/RenderNode objects when optimizeRender is on.
        // The established post-page-change path below still warms subsequent pages.
        callBack.screenOffTimerStart()
    }

    private fun upProgress() {
        curPage.setProgress(pageFactory.curPage)
    }

    /**
     * 更新滑动距离
     */
    fun upPageSlopSquare() {
        val pageTouchSlop = AppConfig.pageTouchSlop
        this.pageSlopSquare = if (pageTouchSlop == 0) slopSquare else pageTouchSlop
        pageSlopSquare2 = this.pageSlopSquare * this.pageSlopSquare
    }

    /**
     * 更新边缘点击阈值
     */
    fun upPageTouchClick() {
        this.pageTouchClick = AppConfig.pageTouchClick
        setRect9x()
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        if (constructed) cancelSelect()
        ChapterProvider.upStyle()
        curPage.upStyle()
        prevPage.upStyle()
        nextPage.upStyle()
        if (ReadBookConfig.isNineBgImg) {
            upBg()
        }
    }

    /**
     * 更新背景
     */
    fun upBg() {
        ReadBookConfig.upBg(width, height)
        curPage.upBg()
        prevPage.upBg()
        nextPage.upBg()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) selectionAutoPager.cancel()
    }

    override fun onDetachedFromWindow() {
        selectionAutoPager.cancel()
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    /**
     * 更新背景透明度
     */
    fun upBgAlpha() {
        curPage.upBgAlpha()
        prevPage.upBgAlpha()
        nextPage.upBgAlpha()
    }

    fun refreshVisualStyle() {
        upBg()
        upStyle()
        invalidateTextPage()
        submitRenderTask()
    }

    /**
     * 更新时间信息
     */
    fun upTime() {
        curPage.upTime()
        prevPage.upTime()
        nextPage.upTime()
    }

    /**
     * 更新电量信息
     */
    fun upBattery(battery: Int) {
        curPage.upBattery(battery)
        prevPage.upBattery(battery)
        nextPage.upBattery(battery)
    }

    /**
     * 从选择位置开始朗读
     */
    fun getSelectedReadPosition(): ReadSelectionPosition? =
        curPage.getSelectedReadPosition()

    /**
     * @return 选择的文本
     */
    fun getSelectText(): String {
        return curPage.selectedText
    }

    fun getCurVisiblePage(): TextPage {
        return curPage.getCurVisiblePage()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        return curPage.getReadAloudPos()
    }

    fun invalidateTextPage() {
        if (AppConfig.optimizeRender) {
            pageFactory.run {
                prevPage.invalidateAll()
                curPage.invalidateAll()
                nextPage.invalidateAll()
                nextPlusPage.invalidateAll()
            }
        }
        // Style-only changes such as underline width/dash length must repaint even when the
        // recorder optimization is disabled. Previously this method returned before invalidating
        // any View, so the new values appeared only after recreating the reader.
        prevPage.invalidateContentView()
        curPage.invalidateContentView()
        nextPage.invalidateContentView()
    }

    fun onScrollAnimStart() {
        autoPager.pause()
    }

    fun onScrollAnimStop() {
        autoPager.resume()
    }

    fun onPageChange() {
        autoPager.reset()
        submitRenderTask()
    }

    fun submitRenderTask() {
        if (!AppConfig.optimizeRender) {
            return
        }
        curPage.submitRenderTask()
    }

    fun isLongScreenShot(): Boolean {
        return curPage.isLongScreenShot()
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upProgressThrottle.invoke()
    }

    override val currentChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(0) else null
        }

    override val nextChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(1) else null
        }

    override val prevChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(-1) else null
        }

    override fun hasNextChapter(): Boolean {
        return ReadBook.durChapterIndex < ReadBook.simulatedChapterSize - 1
    }

    override fun hasPrevChapter(): Boolean {
        return ReadBook.durChapterIndex > 0
    }

    interface CallBack {
        val isInitFinish: Boolean
        fun showActionMenu()
        fun screenOffTimerStart()
        fun showTextActionMenu()
        fun autoPageStop()
        fun openChapterList()
        fun addBookmark()
        fun changeReplaceRuleState()
        fun openSearchActivity(searchWord: String?)
        fun upSystemUiVisibility()
        fun sureNewProgress(progress: BookProgress)
    }
}
