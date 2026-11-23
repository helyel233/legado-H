package io.legado.app.help.book

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class ChangeSourceTextAnchor(
    val primaryFingerprint: String,
    val secondaryFingerprint: String,
    val progressRatio: Float
)

/**
 * Pure helper for extracting and matching cross-source reading text anchors,
 * plus evaluating post-switch chapter content quality.
 */
object ChangeSourceTextAnchorHelper {

    const val FINGERPRINT_LENGTH = 20
    private const val MIN_SEMANTIC_MATCH_CHARS = 4

    fun extractAnchor(
        pageText: String,
        chapterContentLength: Int,
        durChapterPos: Int,
        chapterTitle: String? = null,
        isFirstPage: Boolean = false
    ): ChangeSourceTextAnchor {
        val safeLength = max(1, chapterContentLength)
        val clampedPos = durChapterPos.coerceIn(0, safeLength)
        val progressRatio = (clampedPos.toFloat() / safeLength.toFloat()).coerceIn(0f, 1f)
        if (pageText.isBlank()) {
            return ChangeSourceTextAnchor("", "", progressRatio)
        }
        val bodySource = stripOptionalLeadingTitle(pageText, chapterTitle, isFirstPage)
        val segments = splitNonEmptySegments(bodySource)
        if (segments.isEmpty()) {
            val cleanedAll = cleanWhitespace(bodySource)
            val primary = cleanedAll.take(FINGERPRINT_LENGTH)
            val secondary = if (cleanedAll.length > FINGERPRINT_LENGTH + 4) {
                cleanedAll.drop(FINGERPRINT_LENGTH).take(FINGERPRINT_LENGTH)
            } else {
                ""
            }
            return ChangeSourceTextAnchor(primary, secondary, progressRatio)
        }

        // Combine leading segments if the very first sentence is short so primaryFingerprint stays distinctive.
        val combinedPrimary = buildString {
            for (seg in segments) {
                append(seg)
                if (length >= FINGERPRINT_LENGTH) break
            }
        }
        val primary = combinedPrimary.take(FINGERPRINT_LENGTH)

        // Pick secondary fingerprint from the next distinct sentence/paragraph, or from a later slice if single long block.
        var secondary = ""
        if (segments.size > 1) {
            // Find the first segment that wasn't completely consumed by primary, or simply start from segment 1+.
            var consumedChars = 0
            var nextStartIndex = 1
            for (i in segments.indices) {
                consumedChars += segments[i].length
                if (consumedChars >= primary.length && i + 1 < segments.size) {
                    nextStartIndex = i + 1
                    break
                }
            }
            if (nextStartIndex < segments.size) {
                secondary = buildString {
                    for (i in nextStartIndex until segments.size) {
                        append(segments[i])
                        if (length >= FINGERPRINT_LENGTH) break
                    }
                }.take(FINGERPRINT_LENGTH)
            }
        }
        if (secondary.isEmpty()) {
            val allCleaned = segments.joinToString("")
            if (allCleaned.length >= FINGERPRINT_LENGTH + 8) {
                secondary = allCleaned.drop(FINGERPRINT_LENGTH).take(FINGERPRINT_LENGTH)
            }
        }

        return ChangeSourceTextAnchor(
            primaryFingerprint = primary,
            secondaryFingerprint = secondary,
            progressRatio = progressRatio
        )
    }

    fun resolveOffset(anchor: ChangeSourceTextAnchor, newChapterText: String): Int {
        if (newChapterText.isEmpty()) return 0
        val maxOffset = newChapterText.length - 1
        val expectedOffset = (anchor.progressRatio.coerceIn(0f, 1f) * newChapterText.length)
            .roundToInt()
            .coerceIn(0, maxOffset)

        val candidates = listOf(anchor.primaryFingerprint, anchor.secondaryFingerprint)
        for (fingerprint in candidates) {
            if (fingerprint.isBlank()) continue
            findNormalizedMatchOffset(
                text = newChapterText,
                fingerprint = fingerprint,
                expectedOffset = expectedOffset,
                stripPunctuation = false
            )?.let { return it.coerceIn(0, maxOffset) }

            findNormalizedMatchOffset(
                text = newChapterText,
                fingerprint = fingerprint,
                expectedOffset = expectedOffset,
                stripPunctuation = true
            )?.let { return it.coerceIn(0, maxOffset) }
        }
        return expectedOffset
    }

    internal fun cleanWhitespace(text: String): String {
        if (text.isEmpty()) return ""
        return buildString(text.length) {
            for (c in text) {
                if (isSignificantNonWhitespace(c)) {
                    append(c)
                }
            }
        }
    }

    private fun stripOptionalLeadingTitle(
        pageText: String,
        chapterTitle: String?,
        isFirstPage: Boolean
    ): String {
        if (!isFirstPage || chapterTitle.isNullOrBlank()) return pageText
        val cleanTitle = cleanWhitespace(chapterTitle)
        if (cleanTitle.isEmpty()) return pageText
        val normalized = normalizeWithOffsets(pageText, stripPunctuation = false)
        if (!normalized.text.startsWith(cleanTitle)) return pageText
        val afterTitleNormIndex = cleanTitle.length
        if (normalized.text.length - afterTitleNormIndex < 8) return pageText
        val originalStart = normalized.offsets[afterTitleNormIndex]
        return pageText.substring(originalStart)
    }

    private fun splitNonEmptySegments(text: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        for (c in text) {
            if (c == '\n' || c == '\r' || c == '。' || c == '！' || c == '？' ||
                c == '!' || c == '?' || c == '；' || c == ';'
            ) {
                if (c != '\n' && c != '\r') {
                    current.append(c)
                }
                val cleaned = cleanWhitespace(current.toString())
                if (cleaned.isNotEmpty()) {
                    result.add(cleaned)
                }
                current.clear()
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) {
            val cleaned = cleanWhitespace(current.toString())
            if (cleaned.isNotEmpty()) {
                result.add(cleaned)
            }
        }
        return result
    }

    private fun findNormalizedMatchOffset(
        text: String,
        fingerprint: String,
        expectedOffset: Int,
        stripPunctuation: Boolean
    ): Int? {
        val needle = normalize(fingerprint, stripPunctuation)
        val minRequired = if (stripPunctuation) {
            minOf(MIN_SEMANTIC_MATCH_CHARS, needle.length).coerceAtLeast(1)
        } else {
            1
        }
        if (needle.length < minRequired) return null
        if (stripPunctuation && needle.length < 4 && cleanWhitespace(fingerprint).length >= 8) {
            // Avoid matching purely punctuation-heavy tiny fragments.
            return null
        }
        val haystack = normalizeWithOffsets(text, stripPunctuation)
        if (haystack.text.isEmpty()) return null
        var occurrence = haystack.text.indexOf(needle)
        var best: Int? = null
        while (occurrence >= 0) {
            val candidateOffset = haystack.offsets[occurrence]
            if (best == null || abs(candidateOffset - expectedOffset) < abs(best - expectedOffset)) {
                best = candidateOffset
            }
            occurrence = haystack.text.indexOf(needle, occurrence + 1)
        }
        return best
    }

    private class IndexedNormalizedText(
        val text: String,
        val offsets: IntArray
    )

    private fun normalize(input: String, stripPunctuation: Boolean): String {
        if (input.isEmpty()) return ""
        return buildString(input.length) {
            for (c in input) {
                if (shouldKeepChar(c, stripPunctuation)) {
                    append(c)
                }
            }
        }
    }

    private fun normalizeWithOffsets(input: String, stripPunctuation: Boolean): IndexedNormalizedText {
        val offsets = IntArray(input.length)
        var count = 0
        val normalized = buildString(input.length) {
            input.forEachIndexed { index, c ->
                if (shouldKeepChar(c, stripPunctuation)) {
                    offsets[count++] = index
                    append(c)
                }
            }
        }
        return IndexedNormalizedText(normalized, offsets)
    }

    private fun shouldKeepChar(c: Char, stripPunctuation: Boolean): Boolean {
        if (!isSignificantNonWhitespace(c)) return false
        if (!stripPunctuation) return true
        return !isPunctuationOrSymbol(c)
    }

    private fun isSignificantNonWhitespace(c: Char): Boolean {
        return !c.isWhitespace() && c != '\u3000' && c != '\u200b' && c != '\ufeff'
    }

    private fun isPunctuationOrSymbol(c: Char): Boolean {
        return when (Character.getType(c)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt() -> true
            else -> false
        }
    }
}

object SourceSwitchQualityPolicy {

    const val MIN_EFFECTIVE_CHARS = 50

    enum class ChapterIssue {
        NONE,
        LOAD_FAILED,
        EMPTY_OR_SHORT
    }

    data class Evaluation(
        val issue: ChapterIssue,
        val effectiveCharCount: Int
    )

    fun evaluateChapter(
        rawContent: String,
        processedParagraphs: List<String>,
        isImageOrMedia: Boolean
    ): Evaluation {
        if (isExplicitFailureContent(rawContent)) {
            return Evaluation(ChapterIssue.LOAD_FAILED, 0)
        }
        if (isImageOrMedia || rawContent.contains("<img", ignoreCase = true)) {
            val count = countEffectiveChars(processedParagraphs, rawContent)
            return Evaluation(ChapterIssue.NONE, count)
        }
        val effectiveChars = countEffectiveChars(processedParagraphs, rawContent)
        return if (effectiveChars < MIN_EFFECTIVE_CHARS) {
            Evaluation(ChapterIssue.EMPTY_OR_SHORT, effectiveChars)
        } else {
            Evaluation(ChapterIssue.NONE, effectiveChars)
        }
    }

    private fun isExplicitFailureContent(rawContent: String): Boolean {
        val trimmed = rawContent.trimStart()
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("获取正文失败") ||
            trimmed.startsWith("加载正文失败") ||
            trimmed.startsWith("加载内容失败") ||
            trimmed.startsWith("Failed to load content", ignoreCase = true) ||
            trimmed.startsWith("Failed to obtain content", ignoreCase = true)
    }

    private fun countEffectiveChars(processedParagraphs: List<String>, rawContent: String): Int {
        var count = 0
        if (processedParagraphs.isNotEmpty()) {
            for (paragraph in processedParagraphs) {
                for (c in paragraph) {
                    if (!c.isWhitespace() && c != '\u3000' && c != '\u200b' && c != '\ufeff') {
                        count++
                    }
                }
            }
            return count
        }
        for (c in rawContent) {
            if (!c.isWhitespace() && c != '\u3000' && c != '\u200b' && c != '\ufeff') {
                count++
            }
        }
        return count
    }
}
