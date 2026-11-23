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

internal enum class StatusPageMode {
    SKELETON,
    ERROR_MESSAGE
}

internal object SkeletonLayout {

    /**
     * 判断当前阅读器提示文案是否属于过渡加载态（保持骨架屏+底部阶段文案，不跳变为错误白屏）。
     */
    fun isTransitionalLoadingMessage(msg: String?): Boolean {
        val text = msg?.trim() ?: return false
        if (text.isEmpty()) return false
        if (looksLikeErrorMessage(text)) return false
        val lower = text.lowercase()
        return text.startsWith("正在") ||
            text.startsWith("加载中") ||
            text.startsWith("加载数据") ||
            text.startsWith("更新目录") ||
            text.startsWith("读取云端") ||
            text.startsWith("删除云端") ||
            text.startsWith("缓冲") ||
            text.startsWith("连接中") ||
            lower.startsWith("loading") ||
            lower.startsWith("updating chapters") ||
            lower.startsWith("parsing contents") ||
            lower.startsWith("changing source") ||
            lower.startsWith("reading cloud chapter") ||
            lower.startsWith("deleting cloud cache")
    }

    private fun looksLikeErrorMessage(text: String): Boolean {
        val lower = text.lowercase()
        if (text.contains("失败") ||
            text.contains("出错") ||
            text.contains("异常") ||
            text.contains("错误") ||
            text.contains("找不到") ||
            text.contains("没有权限") ||
            text.contains("没有书籍") ||
            text.contains("没有书源") ||
            text.contains("无网络连接") ||
            text.contains("切换为本地缓存")
        ) {
            return true
        }
        return lower.contains("failed") ||
            lower.contains("error") ||
            lower.contains("exception") ||
            lower.contains("no book") ||
            lower.contains("no source") ||
            lower.contains("no network") ||
            lower.contains("not found") ||
            lower.contains("no access permission") ||
            lower.contains("outside the chapter list") ||
            lower.contains("canceled")
    }

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
