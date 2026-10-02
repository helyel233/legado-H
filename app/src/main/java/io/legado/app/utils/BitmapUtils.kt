@file:Suppress("unused")

package io.legado.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import com.google.android.renderscript.Toolkit
import java.io.*
import kotlin.math.*


@Suppress("WeakerAccess", "MemberVisibilityCanBePrivate")
object BitmapUtils {

    /**
     * 从path中获取图片信息,在通过BitmapFactory.decodeFile(String path)方法将突破转成Bitmap时，
     * 遇到大一些的图片，我们经常会遇到OOM(Out Of Memory)的问题。所以用到了我们上面提到的BitmapFactory.Options这个类。
     *
     * @param path   文件路径
     * @param width  想要显示的图片的宽度
     * @param height 想要显示的图片的高度
     * @return
     */
    @Throws(IOException::class)
    fun decodeBitmap(path: String, width: Int, height: Int? = null): Bitmap? {
        val fis = FileInputStream(path)
        return fis.use {
            val op = BitmapFactory.Options()
            // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
            op.inJustDecodeBounds = true
            BitmapFactory.decodeFileDescriptor(fis.fd, null, op)
            op.inSampleSize = calculateInSampleSize(op, width, height)
            op.inJustDecodeBounds = false
            BitmapFactory.decodeFileDescriptor(fis.fd, null, op)
        }
    }

    /**
     * 解析点九图片
     */
    @Throws(IOException::class)
    fun decodeNinePatchDrawable(path: String): Drawable? {
        val fis = FileInputStream(path)
        return fis.use {
            NinePatchDrawable.createFromStream(fis, null)
        }
    }

    /**
     *计算 InSampleSize。缺省返回1
     * @param options BitmapFactory.Options,
     * @param width  想要显示的图片的宽度
     * @param height 想要显示的图片的高度
     * @return
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        width: Int? = null,
        height: Int? = null
    ): Int {
        //获取比例大小
        val wRatio = width?.let { options.outWidth / it } ?: -1
        val hRatio = height?.let { options.outHeight / it } ?: -1
        //如果超出指定大小，则缩小相应的比例
        return when {
            wRatio > 1 && hRatio > 1 -> max(wRatio, hRatio)
            wRatio > 1 -> wRatio
            hRatio > 1 -> hRatio
            else -> 1
        }
    }

    /** 从path中获取Bitmap图片
     * @param path 图片路径
     * @return
     */
    @Throws(IOException::class)
    fun decodeBitmap(path: String): Bitmap? {
        val fis = FileInputStream(path)
        return fis.use {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true

            BitmapFactory.decodeFileDescriptor(fis.fd, null, opts)
            opts.inSampleSize = computeSampleSize(opts, -1, 128 * 128)
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeFileDescriptor(fis.fd, null, opts)
        }
    }

    /**
     * 以最省内存的方式读取本地资源的图片
     * @param context 设备上下文
     * @param resId 资源ID
     * @return
     */
    fun decodeBitmap(context: Context, resId: Int): Bitmap? {
        val opt = BitmapFactory.Options()
        opt.inPreferredConfig = Config.RGB_565
        return BitmapFactory.decodeResource(context.resources, resId, opt)
    }

    fun decodeBitmap(
        inputFactory: () -> InputStream?,
        width: Int,
        height: Int? = null,
        preferredConfig: Config? = null
    ): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        inputFactory()?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = calculateInSampleSize(options, width, height)
        options.inJustDecodeBounds = false
        preferredConfig?.let { options.inPreferredConfig = it }
        return inputFactory()?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    fun decodeBitmap(
        bytes: ByteArray,
        width: Int,
        height: Int? = null,
        preferredConfig: Config? = null
    ): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = calculateInSampleSize(options, width, height)
        options.inJustDecodeBounds = false
        preferredConfig?.let { options.inPreferredConfig = it }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * @param context 设备上下文
     * @param resId 资源ID
     * @param width
     * @param height
     * @return
     */
    fun decodeBitmap(context: Context, resId: Int, width: Int, height: Int): Bitmap? {
        val op = BitmapFactory.Options()
        // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
        op.inJustDecodeBounds = true
        BitmapFactory.decodeResource(context.resources, resId, op) //获取尺寸信息
        op.inSampleSize = calculateInSampleSize(op, width, height)
        op.inJustDecodeBounds = false
        return BitmapFactory.decodeResource(context.resources, resId, op)
    }

    /**
     * @param context 设备上下文
     * @param fileNameInAssets Assets里面文件的名称
     * @param width 图片的宽度
     * @param height 图片的高度
     * @return Bitmap
     * @throws IOException
     */
    @Throws(IOException::class)
    fun decodeAssetsBitmap(
        context: Context,
        fileNameInAssets: String,
        width: Int,
        height: Int
    ): Bitmap? {
        context.assets.open(fileNameInAssets).use { inputStream ->
            val op = BitmapFactory.Options()
            // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
            op.inJustDecodeBounds = true
            BitmapFactory.decodeStream(inputStream, null, op) //获取尺寸信息
            op.inSampleSize = calculateInSampleSize(op, width, height)
            op.inJustDecodeBounds = false
            return context.assets.open(fileNameInAssets).use { decodeStream ->
                BitmapFactory.decodeStream(decodeStream, null, op)
            }
        }
    }

    /**
     * @param options
     * @param minSideLength
     * @param maxNumOfPixels
     * @return
     * 设置恰当的inSampleSize是解决该问题的关键之一。BitmapFactory.Options提供了另一个成员inJustDecodeBounds。
     * 设置inJustDecodeBounds为true后，decodeFile并不分配空间，但可计算出原始图片的长度和宽度，即opts.width和opts.height。
     * 有了这两个参数，再通过一定的算法，即可得到一个恰当的inSampleSize。
     * 查看Android源码，Android提供了下面这种动态计算的方法。
     */
    fun computeSampleSize(
        options: BitmapFactory.Options,
        minSideLength: Int,
        maxNumOfPixels: Int
    ): Int {
        val initialSize = computeInitialSampleSize(options, minSideLength, maxNumOfPixels)
        var roundedSize: Int
        if (initialSize <= 8) {
            roundedSize = 1
            while (roundedSize < initialSize) {
                roundedSize = roundedSize shl 1
            }
        } else {
            roundedSize = (initialSize + 7) / 8 * 8
        }
        return roundedSize
    }


    private fun computeInitialSampleSize(
        options: BitmapFactory.Options,
        minSideLength: Int,
        maxNumOfPixels: Int
    ): Int {

        val w = options.outWidth.toDouble()
        val h = options.outHeight.toDouble()

        val lowerBound = when (maxNumOfPixels) {
            -1 -> 1
            else -> ceil(sqrt(w * h / maxNumOfPixels)).toInt()
        }

        val upperBound = when (minSideLength) {
            -1 -> 128
            else -> min(
                floor(w / minSideLength),
                floor(h / minSideLength)
            ).toInt()
        }

        if (upperBound < lowerBound) {
            // return the larger one when there is no overlapping zone.
            return lowerBound
        }

        return when {
            maxNumOfPixels == -1 && minSideLength == -1 -> {
                1
            }
            minSideLength == -1 -> {
                lowerBound
            }
            else -> {
                upperBound
            }
        }
    }

    /**
     * 将Bitmap转换成InputStream
     *
     * @param bitmap
     * @return
     */
    fun toInputStream(bitmap: Bitmap): InputStream {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90 /*ignored for PNG*/, bos)
        return ByteArrayInputStream(bos.toByteArray()).also { bos.close() }
    }

}

fun Bitmap.hasTransparentPixels(): Boolean {
    if (!hasAlpha() || width <= 0 || height <= 0 || isRecycled) return false
    return runCatching {
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount <= TRANSPARENT_PIXEL_FULL_SCAN_LIMIT) {
            val row = IntArray(width)
            for (y in 0 until height) {
                getPixels(row, 0, width, 0, y, width, 1)
                for (pixel in row) {
                    if (Color.alpha(pixel) < 255) return true
                }
            }
            return false
        }
        val step = ceil(sqrt(pixelCount.toDouble() / TRANSPARENT_PIXEL_FULL_SCAN_LIMIT)).toInt()
            .coerceAtLeast(2)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (Color.alpha(getPixel(x, y)) < 255) return true
                x += step
            }
            y += step
        }
        false
    }.getOrDefault(hasAlpha())
}

private const val TRANSPARENT_PIXEL_FULL_SCAN_LIMIT = 512_000L

fun Bitmap.preferredCoverExtension(): String {
    return if (hasTransparentPixels()) "png" else "jpg"
}

fun Bitmap.compressPreservingAlpha(outputStream: OutputStream, jpegQuality: Int = 90): Boolean {
    val usePng = hasTransparentPixels()
    return compress(
        if (usePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
        if (usePng) 100 else jpegQuality,
        outputStream
    )
}

/**
 * 获取指定宽高的图片
 */
fun Bitmap.resizeAndRecycle(newWidth: Int, newHeight: Int): Bitmap {
    //获取新的bitmap
    val bitmap = Toolkit.resize(this, newWidth, newHeight)
    if (bitmap !== this && !isRecycled) {
        recycle()
    }
    return bitmap
}

/**
 * 高斯模糊
 */
fun Bitmap.stackBlur(radius: Int = 8): Bitmap {
    return try {
        Toolkit.blur(this, radius)
    } catch (e: Throwable) {
        // renderscript toolkit 的 native 库未适配 16KB 内核页（Android 15+ 部分设备），
        // dlopen 失败抛 UnsatisfiedLinkError；退化为纯软件 StackBlur 保证模糊生效
        softwareStackBlur(radius)
    }
}

/** 纯软件 StackBlur（Mario Klingemann 算法），Toolkit 不可用时的兑底。 */
private fun Bitmap.softwareStackBlur(radius: Int): Bitmap {
    val rad = radius.coerceIn(1, 25)
    val w = width
    val h = height
    if (isRecycled || w <= 0 || h <= 0) return this
    val pix = IntArray(w * h)
    getPixels(pix, 0, w, 0, 0, w, h)
    val wm = w - 1
    val hm = h - 1
    val wh = w * h
    val div = rad + rad + 1
    val r1 = rad + 1
    val divsum = (div + 1) shr 1
    val dv = IntArray(256 * divsum * divsum) { it / (divsum * divsum) }
    val r = IntArray(wh)
    val g = IntArray(wh)
    val b = IntArray(wh)
    val vmin = IntArray(maxOf(w, h))
    val stack = Array(div) { IntArray(3) }

    var yw = 0
    var yi = 0
    for (y in 0 until h) {
        var rsum = 0; var gsum = 0; var bsum = 0
        var rinsum = 0; var ginsum = 0; var binsum = 0
        var routsum = 0; var goutsum = 0; var boutsum = 0
        for (i in -rad..rad) {
            val p = pix[yi + minOf(wm, maxOf(i, 0))]
            val sir = stack[i + rad]
            sir[0] = (p and 0x00ff0000) shr 16
            sir[1] = (p and 0x0000ff00) shr 8
            sir[2] = p and 0x000000ff
            val rbs = r1 - abs(i)
            rsum += sir[0] * rbs
            gsum += sir[1] * rbs
            bsum += sir[2] * rbs
            if (i > 0) {
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
            } else {
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
            }
        }
        var stackpointer = rad
        for (x in 0 until w) {
            r[yi] = dv[rsum]
            g[yi] = dv[gsum]
            b[yi] = dv[bsum]
            rsum -= routsum; gsum -= goutsum; bsum -= boutsum
            val stackstart = stackpointer - rad + div
            val sirOut = stack[stackstart % div]
            routsum -= sirOut[0]; goutsum -= sirOut[1]; boutsum -= sirOut[2]
            if (y == 0) {
                vmin[x] = minOf(x + r1, wm)
            }
            val p = pix[yw + vmin[x]]
            sirOut[0] = (p and 0x00ff0000) shr 16
            sirOut[1] = (p and 0x0000ff00) shr 8
            sirOut[2] = p and 0x000000ff
            rinsum += sirOut[0]; ginsum += sirOut[1]; binsum += sirOut[2]
            rsum += rinsum; gsum += ginsum; bsum += binsum
            stackpointer = (stackpointer + 1) % div
            val sirIn = stack[stackpointer]
            routsum += sirIn[0]; goutsum += sirIn[1]; boutsum += sirIn[2]
            rinsum -= sirIn[0]; ginsum -= sirIn[1]; binsum -= sirIn[2]
            yi++
        }
        yw += w
    }

    for (x in 0 until w) {
        var rsum = 0; var gsum = 0; var bsum = 0
        var rinsum = 0; var ginsum = 0; var binsum = 0
        var routsum = 0; var goutsum = 0; var boutsum = 0
        var yp = -rad * w
        for (i in -rad..rad) {
            val yiv = maxOf(0, yp) + x
            val sir = stack[i + rad]
            sir[0] = r[yiv]
            sir[1] = g[yiv]
            sir[2] = b[yiv]
            val rbs = r1 - abs(i)
            rsum += r[yiv] * rbs
            gsum += g[yiv] * rbs
            bsum += b[yiv] * rbs
            if (i > 0) {
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
            } else {
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
            }
            if (i < hm) {
                yp += w
            }
        }
        var stackpointer = rad
        var yi = x
        for (y in 0 until h) {
            pix[yi] = (0xff000000.toInt() shl 24) or
                (dv[rsum] shl 16) or
                (dv[gsum] shl 8) or
                dv[bsum]
            rsum -= routsum; gsum -= goutsum; bsum -= boutsum
            val stackstart = stackpointer - rad + div
            val sirOut = stack[stackstart % div]
            routsum -= sirOut[0]; goutsum -= sirOut[1]; boutsum -= sirOut[2]
            if (x == 0) {
                vmin[y] = minOf(y + r1, hm) * w
            }
            val p = x + vmin[y]
            sirOut[0] = r[p]
            sirOut[1] = g[p]
            sirOut[2] = b[p]
            rinsum += sirOut[0]; ginsum += sirOut[1]; binsum += sirOut[2]
            rsum += rinsum; gsum += ginsum; bsum += binsum
            stackpointer = (stackpointer + 1) % div
            val sirIn = stack[stackpointer]
            routsum += sirIn[0]; goutsum += sirIn[1]; boutsum += sirIn[2]
            rinsum -= sirIn[0]; ginsum -= sirIn[1]; binsum -= sirIn[2]
            yi += w
        }
    }

    val result = Bitmap.createBitmap(w, h, Config.ARGB_8888)
    result.setPixels(pix, 0, w, 0, 0, w, h)
    return result
}

/**
 * 取平均色
 */
fun Bitmap.getMeanColor(): Int {
    val width: Int = this.width
    val height: Int = this.height
    if (width <= 0 || height <= 0 || isRecycled) return Color.TRANSPARENT
    var pixel: Int
    var pixelSumRed = 0
    var pixelSumBlue = 0
    var pixelSumGreen = 0
    for (i in 0..99) {
        for (j in 70..99) {
            pixel = this.getPixel(
                (i * width / 100.toFloat()).roundToInt().coerceIn(0, width - 1),
                (j * height / 100.toFloat()).roundToInt().coerceIn(0, height - 1)
            )
            pixelSumRed += Color.red(pixel)
            pixelSumGreen += Color.green(pixel)
            pixelSumBlue += Color.blue(pixel)
        }
    }
    val averagePixelRed = pixelSumRed / 3000
    val averagePixelBlue = pixelSumBlue / 3000
    val averagePixelGreen = pixelSumGreen / 3000
    return Color.rgb(
        averagePixelRed + 3,
        averagePixelGreen + 3,
        averagePixelBlue + 3
    )

}
