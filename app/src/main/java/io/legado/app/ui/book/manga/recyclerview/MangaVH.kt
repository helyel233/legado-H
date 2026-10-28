package io.legado.app.ui.book.manga.recyclerview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.legado.app.help.glide.progress.OnProgressListener
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.model.BookCover
import io.legado.app.model.ReadManga
import io.legado.app.utils.printOnDebug

open class MangaVH<VB : ViewBinding>(val binding: VB, private val context: Context) :
    RecyclerView.ViewHolder(binding.root) {

    protected lateinit var mLoading: ProgressBar
    protected lateinit var mImage: AppCompatImageView
    protected lateinit var mProgress: TextView
    protected lateinit var mFlProgress: FrameLayout
    protected var mRetry: Button? = null

    private val minHeight = context.resources.displayMetrics.heightPixels * 2 / 3

    fun initComponent(
        loading: ProgressBar,
        image: AppCompatImageView,
        progress: TextView,
        button: Button? = null,
        flProgress: FrameLayout,
    ) {
        mLoading = loading
        mImage = image
        mRetry = button
        mProgress = progress
        mFlProgress = flProgress
    }

    /** 当前进度监听器引用，用于失败时按身份移除 */
    private var progressListener: OnProgressListener? = null

    /** 当前正在加载的图片地址，与 [progressListener] 配对用于回收时按身份移除 */
    private var loadingImageUrl: String? = null

    /**
     * 回收/失败时按身份移除本 VH 注册的进度监听。
     * 按 URL 无条件移除会误删同一地址在其它 VH（或重试后新注册）的监听。
     */
    fun releaseProgressListener() {
        val url = loadingImageUrl
        val listener = progressListener
        if (url != null && listener != null) {
            ProgressManager.removeListener(url, listener)
        }
        progressListener = null
        loadingImageUrl = null
    }

    @SuppressLint("CheckResult")
    fun loadImageWithRetry(
        imageUrl: String,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?
    ) {
        mFlProgress.isVisible = true
        mLoading.isVisible = true
        mRetry?.isGone = true
        mProgress.isVisible = true
        ProgressManager.removeListener(imageUrl)
        // 保存引用：失败回调晚到时按身份移除，避免误删重试注册的新 listener
        val listener: OnProgressListener = { _, percentage, _, _ ->
            @SuppressLint("SetTextI18n")
            mProgress.text = "$percentage%"
        }
        progressListener = listener
        loadingImageUrl = imageUrl
        ProgressManager.addListener(imageUrl, listener)
        try {
            mImage.tag = imageUrl
            BookCover.loadManga(
                context,
                imageUrl,
                sourceOrigin = ReadManga.book?.origin,
                transformation = transformation
            ).addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    // 加载失败时进度回调不会再走到 100%，listener（持有本 VH 的引用链）
                    // 会一直留在 ProgressManager 里；VH 回收路径只在 onViewRecycled 触发，
                    // 这里必须主动移除（按身份，防止误删重试注册的新 listener）
                    progressListener?.let { ProgressManager.removeListener(imageUrl, it) }
                    mFlProgress.isVisible = true
                    mLoading.isGone = true
                    mRetry?.isVisible = true
                    mProgress.isGone = true
                    itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    mFlProgress.isGone = true
                    if (!isHorizontal) {
                        itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                            gravity = Gravity.NO_GRAVITY
                        }
                        if (isLastImage) {
                            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                            itemView.minimumHeight = minHeight
                        } else {
                            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                                height = ViewGroup.LayoutParams.MATCH_PARENT
                            }
                            itemView.minimumHeight = 0
                        }
                        mImage.scaleType = ImageView.ScaleType.FIT_XY
                    } else {
                        itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                            height = ViewGroup.LayoutParams.MATCH_PARENT
                        }
                        itemView.minimumHeight = 0
                        mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                            height = ViewGroup.LayoutParams.MATCH_PARENT
                            gravity = Gravity.CENTER
                        }
                        mImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    return false
                }
            }).into(mImage)
        } catch (e: Exception) {
            e.printOnDebug()
        }

    }
}