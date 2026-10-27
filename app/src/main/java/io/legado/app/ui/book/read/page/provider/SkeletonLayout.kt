package io.legado.app.ui.book.read.page.provider

/**
 * 正文加载占位（骨架屏）的布局计算。
 *
 * 纯函数实现：只依赖入参，不读全局状态，便于单测锁定行为。
 */
internal data class SkeletonLine(
    val top: Float,
    val left: Float,
    val right: Float,
    val height: Float
)

internal object SkeletonLayout {

    /**
     * 每行的宽度比例，按固定序列模拟真实段落的参差。
     *
     * 用固定序列而不是随机数：同一页每次重绘形状一致，
     * 避免正文到达时因形状跳变造成二次闪烁。
     */
    private val widthRatios = floatArrayOf(1f, 0.94f, 0.99f, 0.86f, 0.63f)

    /**
     * 计算骨架线条
     *
     * @param top 可用区域顶部
     * @param bottom 可用区域底部
     * @param left 左侧起点
     * @param right 右侧终点
     * @param lineHeight 单条骨架高度
     * @param lineGap 行间距
     * @param maxLines 最多画多少行
     */
    fun lines(
        top: Float,
        bottom: Float,
        left: Float,
        right: Float,
        lineHeight: Float,
        lineGap: Float,
        maxLines: Int = 16
    ): List<SkeletonLine> {
        if (right <= left || bottom <= top || lineHeight <= 0f || maxLines <= 0) return emptyList()
        val step = lineHeight + lineGap
        if (step <= 0f) return emptyList()
        val result = ArrayList<SkeletonLine>()
        var y = top
        var index = 0
        while (index < maxLines && y + lineHeight <= bottom) {
            val ratio = widthRatios[index % widthRatios.size]
            result.add(
                SkeletonLine(
                    top = y,
                    left = left,
                    right = left + (right - left) * ratio,
                    height = lineHeight
                )
            )
            y += step
            index++
        }
        return result
    }
}
