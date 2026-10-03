package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 文字列
 */
@Keep
data class TextColumn(
    override var start: Float,
    override var end: Float,
    override val charData: String,
) : TextBaseColumn {

    override var textLine: TextLine = emptyTextLine

    override var selected: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
            }
            field = value
        }
    override var isSearchResult: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
                if (value) {
                    textLine.searchResultColumnCount++
                } else {
                    textLine.searchResultColumnCount--
                }
            }
            field = value
        }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val textPaint = if (textLine.isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        val textColor = if (textLine.isReadAloud || isSearchResult) {
            ReadBookConfig.textAccentColor
        } else {
            ReadBookConfig.textColor
        }
        val enablePaperInk = !textLine.isReadAloud && !isSearchResult
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        val y = textLine.lineBase - textLine.lineTop
        val x = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val letterSpacing = textPaint.letterSpacing * textPaint.textSize
            start + letterSpacing * 0.5f
        } else {
            start
        }
        view.drawTextWithPaperInk(canvas, charData, x, y, textPaint, enablePaperInk)
        // 字重<字体基准字面时：用背景色描边擦掉字心边缘；drawThinStroke 内部会还原共享画笔
        ChapterProvider.drawThinStroke(canvas, textPaint, textLine.isTitle, charData, 0, charData.length, x, y)
        if (selected) {
            view.drawSelectedRect(canvas, start, 0f, end, textLine.height)
        }
    }

}
