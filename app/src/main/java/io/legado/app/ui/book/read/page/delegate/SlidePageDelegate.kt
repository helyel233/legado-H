package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.withTranslation
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection

class SlidePageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    private val easeOutInterpolator by lazy { PathInterpolator(0.25f, 0.1f, 0.25f, 1f) }
    private val linearInterpolator by lazy { LinearInterpolator() }

    override fun scrollInterpolator(): Interpolator =
        if (AppConfig.isEInkMode) linearInterpolator else easeOutInterpolator

    override fun onAnimStart(animationSpeed: Int) {
        val distanceX: Float
        when (mDirection) {
            PageDirection.NEXT -> distanceX =
                if (isCancel) {
                    var dis = viewWidth - startX + touchX
                    if (dis > viewWidth) {
                        dis = viewWidth.toFloat()
                    }
                    viewWidth - dis
                } else {
                    -(touchX + (viewWidth - startX))
                }

            else -> distanceX =
                if (isCancel) {
                    -(touchX - startX)
                } else {
                    viewWidth - (touchX - startX)
                }
        }
        startScroll(touchX, 0f, distanceX, 0f, animationSpeed)
    }

    override fun onDraw(canvas: Canvas) {
        val offsetX = touchX - startX

        if ((mDirection == PageDirection.NEXT && offsetX > 0)
            || (mDirection == PageDirection.PREV && offsetX < 0)
        ) return
        val distanceX = if (offsetX > 0) offsetX - viewWidth else offsetX + viewWidth
        if (!isRunning) return
        if (mDirection == PageDirection.PREV) {
            canvas.withTranslation(distanceX + viewWidth) {
                curRecorder.draw(this)
            }
            canvas.withTranslation(distanceX) {
                prevRecorder.draw(this)
            }
        } else if (mDirection == PageDirection.NEXT) {
            canvas.withTranslation(distanceX) {
                nextRecorder.draw(this)
            }
            canvas.withTranslation(distanceX - viewWidth) {
                curRecorder.draw(this)
            }
        }
    }

    override fun onAnimStop() {
        if (!isCancel) {
            readView.fillPage(mDirection)
        }
    }
}