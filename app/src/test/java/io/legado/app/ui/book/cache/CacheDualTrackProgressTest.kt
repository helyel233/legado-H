package io.legado.app.ui.book.cache

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CacheDualTrackProgressTest {

    @Test
    fun `CacheAdapter formatter hides review track when count is zero and shows dual track when positive`() {
        val context = RuntimeEnvironment.getApplication()
        val concise = formatCacheProgressText(
            context = context,
            bodyCount = 12,
            totalCount = 100,
            reviewCount = 0
        )
        assertTrue(concise.contains("12/100"))
        assertFalse(concise.contains("·"))

        val dual = formatCacheProgressText(
            context = context,
            bodyCount = 12,
            totalCount = 100,
            reviewCount = 5
        )
        assertTrue(dual.contains("12/100"))
        assertTrue(dual.contains("5/100"))
        assertTrue(dual.contains("·"))
    }

    @Test
    fun `CacheBookCard formatter shows dual track only when review count is positive`() {
        val context = RuntimeEnvironment.getApplication()
        val concise = buildCacheCountText(
            context = context,
            localCount = 20,
            remoteCount = 0,
            totalCount = 100,
            remoteAvailable = false,
            reviewCount = 0
        )
        assertTrue(concise.contains("20"))
        assertFalse(concise.contains("100"))

        val dual = buildCacheCountText(
            context = context,
            localCount = 20,
            remoteCount = 0,
            totalCount = 100,
            remoteAvailable = false,
            reviewCount = 7
        )
        assertTrue(dual.contains("20/100"))
        assertTrue(dual.contains("7/100"))
        assertTrue(dual.contains("·"))
    }
}
