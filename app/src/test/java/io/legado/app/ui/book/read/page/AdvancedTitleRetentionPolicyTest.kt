package io.legado.app.ui.book.read.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedTitleRetentionPolicyTest {

    private class Block(val text: String) {
        override fun equals(other: Any?): Boolean = other is Block && other.text == text
        override fun hashCode(): Int = text.hashCode()
    }

    @Test
    fun sameBlockInstanceCanBeRetained() {
        val block = Block("第一章")

        assertTrue(
            AdvancedTitleRetentionPolicy.canRetain(
                boundBlock = block,
                incomingBlock = block,
                isPresented = true,
                hasComposition = true,
                isVisible = true
            )
        )
    }

    @Test
    fun equalButDifferentInstanceIsNotRetained() {
        // 两个内容相同但不是同一个实例的 block 不能证明槽位画的就是这一页的标题。
        val bound = Block("第一章")
        val incoming = Block("第一章")

        assertFalse(
            AdvancedTitleRetentionPolicy.canRetain(
                boundBlock = bound,
                incomingBlock = incoming,
                isPresented = true,
                hasComposition = true,
                isVisible = true
            )
        )
    }

    @Test
    fun unboundOrMissingBlockIsNotRetained() {
        assertFalse(
            AdvancedTitleRetentionPolicy.canRetain(
                boundBlock = null,
                incomingBlock = Block("第一章"),
                isPresented = true,
                hasComposition = true,
                isVisible = true
            )
        )
        assertFalse(
            AdvancedTitleRetentionPolicy.canRetain(
                boundBlock = Block("第一章"),
                incomingBlock = null,
                isPresented = true,
                hasComposition = true,
                isVisible = true
            )
        )
    }

    @Test
    fun aTitleThatWasNotPresentedIsNotRetained() {
        val block = Block("第一章")

        assertFalse(
            AdvancedTitleRetentionPolicy.canRetain(
                boundBlock = block,
                incomingBlock = block,
                isPresented = false,
                hasComposition = true,
                isVisible = true
            )
        )
    }

    @Test
    fun aTitleWithoutCompositionOrVisibilityIsNotRetained() {
        val block = Block("第一章")

        assertFalse(
            AdvancedTitleRetentionPolicy.canRetain(
                boundBlock = block,
                incomingBlock = block,
                isPresented = true,
                hasComposition = false,
                isVisible = true
            )
        )
        assertFalse(
            AdvancedTitleRetentionPolicy.canRetain(
                boundBlock = block,
                incomingBlock = block,
                isPresented = true,
                hasComposition = true,
                isVisible = false
            )
        )
    }
}
