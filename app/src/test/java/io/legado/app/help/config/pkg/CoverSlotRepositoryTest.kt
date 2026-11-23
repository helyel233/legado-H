package io.legado.app.help.config.pkg

import android.app.Application
import android.content.Context
import android.os.Looper
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.ui.main.bookshelf.compose.resolveMountedGroupDefaultCover
import io.legado.app.ui.main.bookshelf.compose.toCoverRequest
import io.legado.app.utils.FileUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CoverSlotRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        resetState()
    }

    @After
    fun tearDown() {
        resetState()
    }

    private fun resetState() {
        UnifiedPackageRepository.invalidateCache()
        context.defaultSharedPreferences.edit().clear().commit()
        AppConfig.isNightTheme = false
        FileUtils.delete(UnifiedPackageRepository.rootDir, deleteRootDir = true)
        FileUtils.delete(CoverCollectionManager.rootDir, deleteRootDir = true)
        UnifiedPackageRepository.invalidateCache()
    }

    @Test
    fun ensureInitialized_registersBuiltinDefaultAndBridgesLegacyCoverCollections() = runWithMainPump {
        val legacyImg = tempFolder.newFile("legacy_cover.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val legacyCollection = CoverCollectionManager.create("旧版水墨封面集", isNight = false)
        val collectionDir = CoverCollectionManager.collectionDir(legacyCollection).apply { mkdirs() }
        val storedImg = File(collectionDir, "legacy_cover.jpg")
        legacyImg.copyTo(storedImg, overwrite = true)
        val updatedLegacy = CoverCollectionManager.update(
            legacyCollection.copy(
                mode = CoverCollectionManager.MODE_SEQUENCE,
                images = listOf(storedImg.absolutePath)
            )
        )
        CoverCollectionManager.setSelected(false, updatedLegacy.id)

        UnifiedPackageRepository.ensureInitialized(context)

        val entries = UnifiedPackageRepository.listCoverSlotPackages()
        val builtin = entries.firstOrNull {
            it.manifest.id == UnifiedPackageRepository.BUILTIN_DEFAULT_COVER_SLOT_ID
        }
        assertNotNull(builtin)
        assertEquals("原生书架封面", builtin!!.manifest.name)

        val migrated = entries.firstOrNull { it.manifest.name == "旧版水墨封面集" }
        assertNotNull(migrated)
        assertEquals(CoverSlotApplyMode.SEQUENCE, migrated!!.manifest.coverSlot?.applyMode)
        assertEquals(1, migrated.manifest.coverSlot?.covers?.size)
        val migratedRel = migrated.manifest.coverSlot!!.covers.first()
        assertTrue(migratedRel.startsWith("assets/"))
        assertTrue(File(migrated.dir, migratedRel).isFile)

        assertEquals(
            migrated.manifest.id,
            UnifiedPackageRepository.activeCoverSlotPackageId(context, isNight = false)
        )
        assertTrue(UnifiedPackageRepository.isCoverSlotLocked(context))
    }

    @Test
    fun precedenceRule_lockedCoverSlotProtectsShelfCoversDuringArtSwitchAndDayNightSync() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        // Create and configure a custom COVER_SLOT with dayDefaultCoverPath (night omitted -> fallback to day)
        // and groupCoverPath.
        val galleryImg = tempFolder.newFile("slot_gallery_1.jpg").apply { writeBytes(byteArrayOf(11, 12, 13)) }
        val dayDefaultImg = tempFolder.newFile("slot_day_default.jpg").apply { writeBytes(byteArrayOf(21, 22, 23)) }
        val groupImg = tempFolder.newFile("slot_group_default.png").apply { writeBytes(byteArrayOf(31, 32, 33)) }

        val createdSlot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "青瓷书架封面")
        val savedSlot = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = createdSlot.manifest.id,
            newName = "青瓷书架封面",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = listOf(galleryImg.absolutePath),
                applyMode = CoverSlotApplyMode.MIXED,
                dayDefaultCoverPath = dayDefaultImg.absolutePath,
                nightDefaultCoverPath = null,
                groupCoverPath = groupImg.absolutePath
            )
        )

        UnifiedPackageRepository.mountCoverSlotPackage(context, savedSlot, forNight = false)
        UnifiedPackageRepository.mountCoverSlotPackage(context, savedSlot, forNight = true)

        assertTrue(context.getPrefBoolean(PreferKey.coverSlotLocked, false))
        val lockedDayCover = context.getPrefString(PreferKey.defaultCover)
        val lockedGroupCover = context.getPrefString(PreferKey.defaultGroupCover)
        assertNotNull(lockedDayCover)
        assertTrue(File(lockedDayCover!!).isFile)
        assertNotNull(lockedGroupCover)
        assertTrue(File(lockedGroupCover!!).isFile)
        assertEquals(savedSlot.manifest.id, context.getPrefString(PreferKey.coverCollectionDay))
        assertEquals(CoverCollectionManager.MODE_MIXED, context.getPrefString(PreferKey.coverCollectionModeDay))

        // Mount an ArtPackage that carries its own defaultCoverPath and mounts.dayCoverSlotId
        val artCoverImg = tempFolder.newFile("art_embedded_cover.jpg").apply { writeBytes(byteArrayOf(99, 98, 97)) }
        val artBase = UnifiedPackageRepository.listArtPackages().first()
        val artEdited = UnifiedPackageRepository.saveEditedArtPackage(
            context = context,
            entryId = artBase.manifest.id,
            newName = "冲突测试美术包",
            updatedArt = (artBase.manifest.art ?: ArtPackagePayload()).copy(
                defaultCoverPath = artCoverImg.absolutePath,
                mounts = ArtAssetMounts(
                    dayCoverSlotId = "art_recommended_cover_id",
                    nightCoverSlotId = "art_recommended_night_cover_id"
                )
            )
        )

        UnifiedPackageRepository.mountArtPackage(context, artEdited, forNight = false)
        UnifiedPackageRepository.applyArtPackage(context, artEdited)

        // Independent locked COVER_SLOT must NOT be overwritten by ArtPackage
        assertEquals(lockedDayCover, context.getPrefString(PreferKey.defaultCover))
        assertEquals(lockedGroupCover, context.getPrefString(PreferKey.defaultGroupCover))
        assertEquals(savedSlot.manifest.id, context.getPrefString(PreferKey.coverCollectionDay))
        assertEquals(CoverCollectionManager.MODE_MIXED, context.getPrefString(PreferKey.coverCollectionModeDay))

        // Day -> Night sync keeps the mounted COVER_SLOT active (including day->night single-side fallback)
        AppConfig.isNightTheme = true
        UnifiedPackageRepository.applyDayNightMountedSync(context, isNight = true)
        val nightDefault = context.getPrefString(PreferKey.defaultCoverDark)
        assertNotNull(nightDefault)
        assertTrue(File(nightDefault!!).isFile)
        assertFalse(nightDefault.contains("art_"))
        assertEquals(savedSlot.manifest.id, context.getPrefString(PreferKey.coverCollectionNight))

        // Unlock (follow ArtPackage recommendation): ArtPackage cover now takes effect
        AppConfig.isNightTheme = false
        UnifiedPackageRepository.setCoverSlotFollowArt(context, followArt = true)
        assertFalse(context.getPrefBoolean(PreferKey.coverSlotLocked, true))

        val followArtCover = context.getPrefString(PreferKey.defaultCover)
        assertNotNull(followArtCover)
        assertTrue(followArtCover != lockedDayCover)
        assertEquals("art_recommended_cover_id", context.getPrefString(PreferKey.coverCollectionDay))
    }

    /**
     * 仓库里的 withContext(IO) 内部会再 withContext(Main.immediate)。若测试直接在 Robolectric 主线程
     * runBlocking，主线程被阻塞，IO 线程派发回主线程的任务永远无法执行而死锁。
     * 这里把协程放到工作线程跑，并让主线程循环泵送 Main Looper。
     */
    private fun <T> runWithMainPump(block: suspend () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<T> { runBlocking { block() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (!future.isDone) {
                shadowOf(Looper.getMainLooper()).idle()
                check(System.nanoTime() < deadline) { "runWithMainPump timed out" }
                Thread.sleep(5)
            }
            return future.get()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun coverSlotCrud_cloneExportImportDeleteFallsBackSafely() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val img = tempFolder.newFile("crud_cover.jpg").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }
        val created = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "原始封面包")
        val saved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = created.manifest.id,
            newName = "原始封面包",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = listOf(img.absolutePath),
                applyMode = CoverSlotApplyMode.RANDOM
            )
        )

        val cloned = UnifiedPackageRepository.clonePackage(PackageKind.COVER_SLOT, saved.manifest.id, "派生封面包")
        assertEquals(saved.manifest.id, cloned.manifest.basePackageId)

        val exportedZip = UnifiedPackageRepository.exportPackage(cloned)
        assertTrue(exportedZip.isFile && exportedZip.length() > 0L)
        assertEquals(PackageKind.COVER_SLOT, UnifiedPackageRepository.peekKind(exportedZip))

        // Re-import with a different name to confirm materialized ZIP contains assets cleanly
        val importedRes = UnifiedPackageRepository.importPackage(
            context = context,
            file = exportedZip,
            customNameOverride = "导入测试封面包"
        )
        // Same contentHash -> deduplicates against existing `saved` / `cloned`
        assertNotNull(importedRes.duplicateCoverSlot)
        exportedZip.delete()

        UnifiedPackageRepository.mountCoverSlotPackage(context, cloned, forNight = false)
        assertEquals(cloned.manifest.id, UnifiedPackageRepository.activeCoverSlotPackageId(context, false))

        val deleteResult = UnifiedPackageRepository.deletePackage(context, PackageKind.COVER_SLOT, cloned.manifest.id)
        assertEquals(cloned.manifest.id, deleteResult.deletedId)
        assertTrue(deleteResult.affectedSlots.isNotEmpty())
        assertEquals(
            UnifiedPackageRepository.BUILTIN_DEFAULT_COVER_SLOT_ID,
            UnifiedPackageRepository.activeCoverSlotPackageId(context, false)
        )
    }

    @Test
    fun bookshelfGroupCoverFallback_usesCoverSlotGroupCoverOnlyWhenGroupCoverEmpty() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val groupImg = tempFolder.newFile("mounted_group_cover.png").apply {
            writeBytes(byteArrayOf(7, 8, 9, 10))
        }
        val slot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "分组兜底封面包")
        val saved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "分组兜底封面包",
            updatedCoverSlot = CoverSlotPackagePayload(
                applyMode = CoverSlotApplyMode.FALLBACK_ONLY,
                groupCoverPath = groupImg.absolutePath
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, saved, forNight = false)

        val mountedGroupCover = resolveMountedGroupDefaultCover()
        assertNotNull(mountedGroupCover)
        assertTrue(File(mountedGroupCover!!).isFile)

        // Group without custom cover falls back to COVER_SLOT.groupCoverPath
        val blankGroup = BookGroup(groupId = 101L, groupName = "武侠", cover = null)
        val fallbackReq = blankGroup.toCoverRequest()
        assertEquals(mountedGroupCover, fallbackReq.path)
        assertTrue(fallbackReq.forcePath)
        assertEquals(true, fallbackReq.allowNameOverlay)

        // Group with explicit custom cover preserves its own cover
        val customGroup = BookGroup(groupId = 102L, groupName = "科幻", cover = "/custom/scifi_group.jpg")
        val customReq = customGroup.toCoverRequest()
        assertEquals("/custom/scifi_group.jpg", customReq.path)
        assertFalse(customReq.forcePath)
    }
}
