package io.legado.app.help.config

/**
 * 位图降采样倍率计算（纯函数）。
 *
 * 底栏/主题图标是用户自备图片，可能是一张几千像素的大图；
 * 直接整图解码会在主线程分配几十 MB 并卡住 UI，必须先按需降采样。
 */
internal object IconSampleSizePolicy {

    /**
     * 计算 inSampleSize，使解码后的尺寸不小于 reqWidth/reqHeight。
     *
     * 与 BitmapFactory 的常规算法一致：按 2 的幂逐级减半，直到半边长小于要求。
     */
    fun inSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        var sampleSize = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sampleSize >= reqWidth && halfHeight / sampleSize >= reqHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
