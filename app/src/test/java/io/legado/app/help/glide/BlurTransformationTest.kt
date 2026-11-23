package io.legado.app.help.glide

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

class BlurTransformationTest {

    private fun digestOf(transformation: BlurTransformation): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        transformation.updateDiskCacheKey(digest)
        return digest.digest()
    }

    @Test
    fun `different radius values produce distinct disk cache keys`() {
        val digest5 = digestOf(BlurTransformation(5))
        val digest12 = digestOf(BlurTransformation(12))
        val digest25 = digestOf(BlurTransformation(25))

        assertFalse(digest5.contentEquals(digest12))
        assertFalse(digest12.contentEquals(digest25))
        assertFalse(digest5.contentEquals(digest25))
    }

    @Test
    fun `same radius values produce identical disk cache keys`() {
        val first = BlurTransformation(12)
        val second = BlurTransformation(12)

        assertArrayEquals(digestOf(first), digestOf(second))
    }

    @Test
    fun `equals and hashCode reflect radius contract`() {
        val a1 = BlurTransformation(12)
        val a2 = BlurTransformation(12)
        val b = BlurTransformation(20)

        assertEquals(a1, a1)
        assertEquals(a1, a2)
        assertEquals(a2, a1)
        assertEquals(a1.hashCode(), a2.hashCode())

        assertNotEquals(a1, b)
        assertNotEquals(a1.hashCode(), b.hashCode())
        assertFalse(a1.equals(null))
        assertFalse(a1.equals("not a transformation"))
    }
}
