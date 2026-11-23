package io.legado.app.ui.book.read.page.provider

import kotlin.math.abs

/**
 * Pure-JVM typography justification and punctuation-compression engine for reader lines.
 */
object TypographyJustification {

    private const val RESIDUAL_EPSILON = 0.1f
    internal const val HIDDEN_REVIEW_CHAR_CODE = '\u2060'
    internal const val SRC_REPLACE_CHAR_CODE = '袮'
    internal const val REVIEW_CHAR_CODE = '꧁'
    internal const val HIDDEN_REVIEW_CHAR = "$HIDDEN_REVIEW_CHAR_CODE"
    internal const val SRC_REPLACE_CHAR = "$SRC_REPLACE_CHAR_CODE"
    internal const val REVIEW_CHAR = "$REVIEW_CHAR_CODE"

    private val compressiblePostPunctuation = hashSetOf(
        "，", "。", "？", "！", "；", "：", "、", "”", "’",
        "）", "》", "】", "』", "」", "〕", "〗", "〉"
    )

    private val compressiblePrePunctuation = hashSetOf(
        "“", "‘", "（", "《", "【", "『", "「", "〔", "〖", "〈"
    )

    data class LineJustificationResult(
        val columnStarts: FloatArray,
        val columnEnds: FloatArray,
        val extraLetterSpacing: Float,
        val extraLetterSpacingOffsetX: Float,
        val wordSpacing: Float,
        val hasCustomColumnLayout: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LineJustificationResult) return false
            return columnStarts.contentEquals(other.columnStarts) &&
                columnEnds.contentEquals(other.columnEnds) &&
                extraLetterSpacing == other.extraLetterSpacing &&
                extraLetterSpacingOffsetX == other.extraLetterSpacingOffsetX &&
                wordSpacing == other.wordSpacing &&
                hasCustomColumnLayout == other.hasCustomColumnLayout
        }

        override fun hashCode(): Int {
            var result = columnStarts.contentHashCode()
            result = 31 * result + columnEnds.contentHashCode()
            result = 31 * result + extraLetterSpacing.hashCode()
            result = 31 * result + extraLetterSpacingOffsetX.hashCode()
            result = 31 * result + wordSpacing.hashCode()
            result = 31 * result + hasCustomColumnLayout.hashCode()
            return result
        }
    }

    data class HtmlJustificationResult(
        val starts: FloatArray,
        val ends: FloatArray,
        val wordSpacing: Float,
        val extraLetterSpacing: Float
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HtmlJustificationResult) return false
            return starts.contentEquals(other.starts) &&
                ends.contentEquals(other.ends) &&
                wordSpacing == other.wordSpacing &&
                extraLetterSpacing == other.extraLetterSpacing
        }

        override fun hashCode(): Int {
            var result = starts.contentHashCode()
            result = 31 * result + ends.contentHashCode()
            result = 31 * result + wordSpacing.hashCode()
            result = 31 * result + extraLetterSpacing.hashCode()
            return result
        }
    }

    fun justifyLine(
        words: List<String>,
        widths: List<Float>,
        startX: Float,
        residualWidth: Float,
        textSize: Float,
        cnCharWidth: Float
    ): LineJustificationResult {
        val n = words.size
        if (n == 0 || widths.size != n) {
            return LineJustificationResult(
                columnStarts = FloatArray(0),
                columnEnds = FloatArray(0),
                extraLetterSpacing = 0f,
                extraLetterSpacingOffsetX = 0f,
                wordSpacing = 0f,
                hasCustomColumnLayout = false
            )
        }

        val safeTextSize = if (textSize > 0f) textSize else 1f
        val safeCnCharWidth = if (cnCharWidth > 0f) cnCharWidth else safeTextSize
        val columnStarts = FloatArray(n)
        val columnEnds = FloatArray(n)

        if (residualWidth < -RESIDUAL_EPSILON) {
            return compressOverflowLine(
                words = words,
                widths = widths,
                startX = startX,
                overflow = -residualWidth,
                safeCnCharWidth = safeCnCharWidth,
                columnStarts = columnStarts,
                columnEnds = columnEnds
            )
        }

        var lastVisibleIndex = -1
        for (i in n - 1 downTo 0) {
            if (words[i] != HIDDEN_REVIEW_CHAR) {
                lastVisibleIndex = i
                break
            }
        }

        var gapCount = 0
        var effectiveSpaceCount = 0
        for (i in 0 until lastVisibleIndex) {
            if (words[i] != HIDDEN_REVIEW_CHAR) {
                gapCount++
                if (words[i] == " ") effectiveSpaceCount++
            }
        }

        if (abs(residualWidth) <= RESIDUAL_EPSILON || gapCount <= 0) {
            placeSequential(words, widths, startX, 0f, 0f, lastVisibleIndex, columnStarts, columnEnds)
            return LineJustificationResult(
                columnStarts = columnStarts,
                columnEnds = columnEnds,
                extraLetterSpacing = 0f,
                extraLetterSpacingOffsetX = 0f,
                wordSpacing = 0f,
                hasCustomColumnLayout = false
            )
        }

        val dChar: Float
        val dSpace: Float
        if (effectiveSpaceCount > 1) {
            val maxExtraSpace = (safeCnCharWidth * 0.55f).coerceAtLeast(safeTextSize * 0.45f)
            dChar = ((residualWidth - effectiveSpaceCount * maxExtraSpace) / gapCount).coerceAtLeast(0f)
            dSpace = ((residualWidth - gapCount * dChar) / effectiveSpaceCount).coerceAtLeast(0f)
        } else {
            dChar = residualWidth / gapCount
            dSpace = 0f
        }

        placeSequential(words, widths, startX, dChar, dSpace, lastVisibleIndex, columnStarts, columnEnds)
        return LineJustificationResult(
            columnStarts = columnStarts,
            columnEnds = columnEnds,
            extraLetterSpacing = dChar / safeTextSize,
            extraLetterSpacingOffsetX = -dChar / 2f,
            wordSpacing = dSpace,
            hasCustomColumnLayout = false
        )
    }

    fun justifyHtmlSpans(
        starts: FloatArray,
        ends: FloatArray,
        isSpace: BooleanArray,
        isZeroWidthAnchor: BooleanArray,
        isFixedWidth: BooleanArray = BooleanArray(starts.size),
        lineWidth: Int,
        textSize: Float,
        cnCharWidth: Float
    ): HtmlJustificationResult {
        val n = starts.size
        if (n == 0 || ends.size != n || isSpace.size != n || isZeroWidthAnchor.size != n || isFixedWidth.size != n) {
            return HtmlJustificationResult(
                starts = FloatArray(0),
                ends = FloatArray(0),
                wordSpacing = 0f,
                extraLetterSpacing = 0f
            )
        }

        val newStarts = FloatArray(n)
        val newEnds = FloatArray(n)
        if (n == 1) {
            newStarts[0] = starts[0]
            newEnds[0] = if (isZeroWidthAnchor[0]) starts[0] else ends[0].coerceAtLeast(starts[0])
            return HtmlJustificationResult(newStarts, newEnds, 0f, 0f)
        }

        val slotWidths = FloatArray(n) { i ->
            if (isZeroWidthAnchor[i]) 0f else (ends[i] - starts[i]).coerceAtLeast(0f)
        }
        val origGaps = FloatArray(n - 1) { i ->
            (starts[i + 1] - ends[i]).coerceAtLeast(0f)
        }
        var currentWidth = 0f
        for (i in 0 until n) {
            currentWidth += slotWidths[i]
            if (i < n - 1) currentWidth += origGaps[i]
        }

        val residualWidth = lineWidth - currentWidth
        if (residualWidth <= RESIDUAL_EPSILON) {
            var x = starts[0]
            for (i in 0 until n) {
                newStarts[i] = x
                newEnds[i] = x + slotWidths[i]
                x = newEnds[i] + if (i < n - 1) origGaps[i] else 0f
            }
            return HtmlJustificationResult(newStarts, newEnds, 0f, 0f)
        }

        // Only distribute gaps after non-zero-width columns that are followed by another visible column.
        var lastVisibleIndex = -1
        for (i in n - 1 downTo 0) {
            if (!isZeroWidthAnchor[i]) {
                lastVisibleIndex = i
                break
            }
        }
        val hasGapAfter = BooleanArray(n)
        var visibleGapCount = 0
        var effectiveSpaceCount = 0
        for (i in 0 until lastVisibleIndex) {
            if (!isZeroWidthAnchor[i]) {
                hasGapAfter[i] = true
                visibleGapCount++
                if (isSpace[i] && !isFixedWidth[i]) {
                    effectiveSpaceCount++
                }
            }
        }

        if (visibleGapCount <= 0) {
            var x = starts[0]
            for (i in 0 until n) {
                newStarts[i] = x
                newEnds[i] = x + slotWidths[i]
                x = newEnds[i] + if (i < n - 1) origGaps[i] else 0f
            }
            return HtmlJustificationResult(newStarts, newEnds, 0f, 0f)
        }

        val safeTextSize = if (textSize > 0f) textSize else 1f
        val safeCnCharWidth = if (cnCharWidth > 0f) cnCharWidth else safeTextSize
        val dChar: Float
        val dSpace: Float
        if (effectiveSpaceCount > 1) {
            val maxExtraSpace = (safeCnCharWidth * 0.55f).coerceAtLeast(safeTextSize * 0.45f)
            dChar = ((residualWidth - effectiveSpaceCount * maxExtraSpace) / visibleGapCount).coerceAtLeast(0f)
            dSpace = ((residualWidth - visibleGapCount * dChar) / effectiveSpaceCount).coerceAtLeast(0f)
        } else {
            dChar = residualWidth / visibleGapCount
            dSpace = 0f
        }

        var x = starts[0]
        for (i in 0 until n) {
            newStarts[i] = x
            val baseGap = if (i < n - 1) origGaps[i] else 0f
            val extraCharGap = if (hasGapAfter[i]) dChar else 0f
            val extraSpaceGap = if (hasGapAfter[i] && isSpace[i] && !isFixedWidth[i]) dSpace else 0f
            when {
                isZeroWidthAnchor[i] -> {
                    newEnds[i] = x
                    x += baseGap
                }
                isFixedWidth[i] -> {
                    newEnds[i] = x + slotWidths[i]
                    x = newEnds[i] + baseGap + extraCharGap
                }
                else -> {
                    newEnds[i] = x + slotWidths[i] + extraCharGap + extraSpaceGap
                    x = newEnds[i] + baseGap
                }
            }
        }

        return HtmlJustificationResult(
            starts = newStarts,
            ends = newEnds,
            wordSpacing = dSpace,
            extraLetterSpacing = dChar / safeTextSize
        )
    }

    private fun compressOverflowLine(
        words: List<String>,
        widths: List<Float>,
        startX: Float,
        overflow: Float,
        safeCnCharWidth: Float,
        columnStarts: FloatArray,
        columnEnds: FloatArray
    ): LineJustificationResult {
        val n = words.size
        val slotWidths = FloatArray(n) { i ->
            if (words[i] == HIDDEN_REVIEW_CHAR) 0f else widths[i].coerceAtLeast(0f)
        }

        val minPunctWidth = (safeCnCharWidth * 0.55f).coerceAtLeast(0.01f)
        val isCompressiblePunct = BooleanArray(n) { i ->
            isCompressiblePunctuation(words[i]) &&
                slotWidths[i] >= safeCnCharWidth * 0.75f &&
                slotWidths[i] > 0f
        }
        val maxPunctCompress = FloatArray(n) { i ->
            if (!isCompressiblePunct[i]) {
                0f
            } else {
                minOf(
                    slotWidths[i] * 0.35f,
                    (slotWidths[i] - minPunctWidth).coerceAtLeast(0f)
                )
            }
        }

        val trailingStart = maxOf(0, n - 3)
        val tier1Trailing = ArrayList<Int>(3)
        val tier2AdjacentPairs = ArrayList<Int>()
        val tier3Remaining = ArrayList<Int>()
        for (i in n - 1 downTo 0) {
            if (maxPunctCompress[i] <= 0f) continue
            val isTrailing = i >= trailingStart
            val hasAdjacent = (i > 0 && isCompressiblePunct[i - 1]) ||
                (i < n - 1 && isCompressiblePunct[i + 1])
            when {
                isTrailing -> tier1Trailing.add(i)
                hasAdjacent -> tier2AdjacentPairs.add(i)
                else -> tier3Remaining.add(i)
            }
        }

        var remaining = overflow
        var punctCompressed = false
        for (tier in listOf(tier1Trailing, tier2AdjacentPairs, tier3Remaining)) {
            if (remaining <= 1e-4f || tier.isEmpty()) continue
            val absorbed = absorbAcrossSlots(remaining, tier, maxPunctCompress, slotWidths)
            if (absorbed > 0f) {
                remaining -= absorbed
                punctCompressed = true
            }
        }

        var x = startX
        for (i in 0 until n) {
            columnStarts[i] = x
            columnEnds[i] = x + slotWidths[i]
            x = columnEnds[i]
        }

        return LineJustificationResult(
            columnStarts = columnStarts,
            columnEnds = columnEnds,
            extraLetterSpacing = 0f,
            extraLetterSpacingOffsetX = 0f,
            wordSpacing = 0f,
            hasCustomColumnLayout = punctCompressed
        )
    }

    private fun absorbAcrossSlots(
        target: Float,
        indices: List<Int>,
        maxCompress: FloatArray,
        slotWidths: FloatArray
    ): Float {
        if (target <= 0f || indices.isEmpty()) return 0f
        val remainingCap = FloatArray(indices.size) { idx -> maxCompress[indices[idx]].coerceAtLeast(0f) }
        var totalAbsorbed = 0f
        var left = target

        while (left > 1e-4f) {
            var activeCount = 0
            for (c in remainingCap) {
                if (c > 1e-4f) activeCount++
            }
            if (activeCount == 0) break

            val share = left / activeCount
            var roundAbsorbed = 0f
            for (idx in indices.indices) {
                val cap = remainingCap[idx]
                if (cap <= 1e-4f) continue
                val take = minOf(share, cap)
                val slotIdx = indices[idx]
                val clampedTake = minOf(take, slotWidths[slotIdx])
                if (clampedTake > 0f) {
                    slotWidths[slotIdx] -= clampedTake
                    remainingCap[idx] -= clampedTake
                    roundAbsorbed += clampedTake
                }
            }
            if (roundAbsorbed <= 1e-5f) break
            totalAbsorbed += roundAbsorbed
            left -= roundAbsorbed
        }
        return totalAbsorbed
    }

    private fun placeSequential(
        words: List<String>,
        widths: List<Float>,
        startX: Float,
        dChar: Float,
        dSpace: Float,
        lastVisibleIndex: Int,
        columnStarts: FloatArray,
        columnEnds: FloatArray
    ) {
        var x = startX
        for (i in words.indices) {
            val word = words[i]
            val naturalWidth = if (word == HIDDEN_REVIEW_CHAR) 0f else widths[i].coerceAtLeast(0f)
            columnStarts[i] = x
            if (i < lastVisibleIndex && word != HIDDEN_REVIEW_CHAR) {
                val gapAfter = dChar + if (word == " ") dSpace else 0f
                if (isFixedWidthSymbol(word)) {
                    columnEnds[i] = x + naturalWidth
                    x = columnEnds[i] + gapAfter
                } else {
                    columnEnds[i] = x + naturalWidth + gapAfter
                    x = columnEnds[i]
                }
            } else {
                columnEnds[i] = x + naturalWidth
                x = columnEnds[i]
            }
        }
    }

    internal fun isHtmlJustificationSpace(charData: String?): Boolean {
        return charData == " " || charData == "\u00A0"
    }

    private fun isCompressiblePunctuation(word: String): Boolean {
        return word in compressiblePostPunctuation || word in compressiblePrePunctuation
    }

    private fun isFixedWidthSymbol(word: String): Boolean {
        return word == HIDDEN_REVIEW_CHAR || word == SRC_REPLACE_CHAR || word == REVIEW_CHAR
    }
}
