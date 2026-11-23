package io.legado.app.help.glide

import android.graphics.Bitmap
import androidx.annotation.IntRange
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import io.legado.app.utils.stackBlur
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 模糊
 * @radius: 0..25
 */
class BlurTransformation(
    @param:IntRange(from = 0, to = 25) private val radius: Int
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        return toTransform.stackBlur(radius)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
        messageDigest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(radius).array())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BlurTransformation
        return radius == other.radius
    }

    override fun hashCode(): Int {
        return 31 * ID.hashCode() + radius
    }

    companion object {
        private const val ID = "io.legado.app.help.glide.BlurTransformation"
        private val ID_BYTES = ID.toByteArray(Key.CHARSET)
    }
}
