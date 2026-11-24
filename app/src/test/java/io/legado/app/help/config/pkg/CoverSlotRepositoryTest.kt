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
        CoverCollectionManager.invalidateCache()
        context.defaultSharedPreferences.edit().clear().commit()
        AppConfig.isNightTheme = false
        FileUtils.delete(UnifiedPackageRepository.rootDir, deleteRootDir = true)
        FileUtils.delete(CoverCollectionManager.rootDir, deleteRootDir = true)
        UnifiedPackageRepository.invalidateCache()
        CoverCollectionManager.invalidateCache()
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

        // Create recommended COVER_SLOT ("art_recommended_cover_id") with its own dayDefaultCoverPath
        val recGalleryImg = tempFolder.newFile("rec_gallery_1.jpg").apply { writeBytes(byteArrayOf(51, 52, 53)) }
        val recDayDefaultImg = tempFolder.newFile("rec_day_default.jpg").apply { writeBytes(byteArrayOf(61, 62, 63)) }
        val recSlotCreated = UnifiedPackageRepository.createEmptyPackage(
            PackageKind.COVER_SLOT,
            "art_recommended_cover_id"
        )
        val recSlotSaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = recSlotCreated.manifest.id,
            newName = "art_recommended_cover_id",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = listOf(recGalleryImg.absolutePath),
                applyMode = CoverSlotApplyMode.RANDOM,
                applyTarget = CoverSlotApplyTarget.BOOKS,
                dayDefaultCoverPath = recDayDefaultImg.absolutePath
            )
        )

        // Mount an ArtPackage that carries legacy defaultCoverPath and mounts.dayCoverSlotId
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

        // Unlock (follow ArtPackage recommendation): recommended CoverSlot's dayDefaultCoverPath takes over
        AppConfig.isNightTheme = false
        UnifiedPackageRepository.setCoverSlotFollowArt(context, followArt = true)
        assertFalse(context.getPrefBoolean(PreferKey.coverSlotLocked, true))

        val followArtCover = context.getPrefString(PreferKey.defaultCover)
        assertNotNull(followArtCover)
        assertTrue(File(followArtCover!!).isFile)
        assertTrue(followArtCover != lockedDayCover)
        assertTrue(followArtCover.startsWith(recSlotSaved.dir.absolutePath))
        assertEquals(recSlotSaved.manifest.id, context.getPrefString(PreferKey.coverCollectionDay))
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

    @Test
    fun followArt_doesNotResurrectLegacyArtDefaultCoverPathWhenNoRecommendedSlot() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val lockedDefaultImg = tempFolder.newFile("locked_day_default.jpg").apply {
            writeBytes(byteArrayOf(1, 3, 5))
        }
        val slot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "锁定封面包")
        val savedSlot = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "锁定封面包",
            updatedCoverSlot = CoverSlotPackagePayload(
                applyMode = CoverSlotApplyMode.FALLBACK_ONLY,
                dayDefaultCoverPath = lockedDefaultImg.absolutePath
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, savedSlot, forNight = false)
        assertFalse(context.getPrefString(PreferKey.defaultCover).isNullOrEmpty())

        val legacyArtCoverImg = tempFolder.newFile("legacy_art_default_cover.jpg").apply {
            writeBytes(byteArrayOf(9, 7, 5))
        }
        val artBase = UnifiedPackageRepository.listArtPackages().first()
        val artEdited = UnifiedPackageRepository.saveEditedArtPackage(
            context = context,
            entryId = artBase.manifest.id,
            newName = "仅含旧defaultCover的美术包",
            updatedArt = (artBase.manifest.art ?: ArtPackagePayload()).copy(
                defaultCoverPath = legacyArtCoverImg.absolutePath,
                mounts = ArtAssetMounts(dayCoverSlotId = null, nightCoverSlotId = null)
            )
        )
        UnifiedPackageRepository.mountArtPackage(context, artEdited, forNight = false)
        UnifiedPackageRepository.applyArtPackage(context, artEdited)

        UnifiedPackageRepository.setCoverSlotFollowArt(context, followArt = true)
        assertFalse(context.getPrefBoolean(PreferKey.coverSlotLocked, true))
        assertTrue(context.getPrefString(PreferKey.defaultCover).isNullOrEmpty())
        assertTrue(context.getPrefString(PreferKey.defaultCoverDark).isNullOrEmpty())
        assertTrue(context.getPrefString(PreferKey.coverCollectionDay).isNullOrEmpty())
    }

    @Test
    fun coverSlotApplyTarget_routesBetweenGroupsBooksAndBothWithNamePriorityAndAsyncSequence() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val xuanhuanImg = tempFolder.newFile("玄幻.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val random1Img = tempFolder.newFile("random_01.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val random2Img = tempFolder.newFile("random_02.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val groupFallbackImg = tempFolder.newFile("group_fallback.png").apply { writeBytes(byteArrayOf(10, 11, 12)) }
        val customGroupCoverFile = tempFolder.newFile("custom_group_cover.jpg").apply {
            writeBytes(byteArrayOf(13, 14, 15))
        }

        val slot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "作用域路由封面包")
        val gallerySources = listOf(
            xuanhuanImg.absolutePath,
            random1Img.absolutePath,
            random2Img.absolutePath
        )

        // 1. applyTarget = GROUPS: name-matched group cover first, random gallery for other groups, books unaffected
        val groupsSaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "作用域路由封面包",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = gallerySources,
                applyMode = CoverSlotApplyMode.RANDOM,
                applyTarget = CoverSlotApplyTarget.GROUPS,
                groupCoverPath = groupFallbackImg.absolutePath
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, groupsSaved, forNight = false)

        val xuanhuanReq = BookGroup(groupId = 1L, groupName = "玄幻").toCoverRequest()
        assertNotNull(xuanhuanReq.path)
        assertEquals("玄幻.jpg", File(xuanhuanReq.path!!).name)
        assertTrue(xuanhuanReq.forcePath)

        val scifiReq = BookGroup(groupId = 2L, groupName = "科幻").toCoverRequest()
        assertNotNull(scifiReq.path)
        assertTrue(File(scifiReq.path!!).isFile)
        assertTrue(File(scifiReq.path!!).name in setOf("玄幻.jpg", "random_01.jpg", "random_02.jpg"))
        assertTrue(scifiReq.forcePath)

        assertNull(CoverCollectionManager.selectedCollectionCover("book_url_1"))

        // 2. Switch to applyTarget = BOOKS: books hit gallery, groups return null and fall back to groupCoverPath
        val booksSaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "作用域路由封面包",
            updatedCoverSlot = groupsSaved.manifest.coverSlot!!.copy(
                applyTarget = CoverSlotApplyTarget.BOOKS
            )
        )
        assertNotNull(CoverCollectionManager.selectedCollectionCover("book_url_1"))
        assertNull(CoverCollectionManager.selectedGroupCollectionCover(1L, "玄幻"))
        val booksFallbackGroupReq = BookGroup(groupId = 1L, groupName = "玄幻").toCoverRequest()
        assertEquals(resolveMountedGroupDefaultCover(), booksFallbackGroupReq.path)
        assertTrue(booksFallbackGroupReq.forcePath)

        // Legacy package with applyTarget = null behaves identically to BOOKS
        val legacyNullTargetSaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "作用域路由封面包",
            updatedCoverSlot = booksSaved.manifest.coverSlot!!.copy(applyTarget = null)
        )
        assertNull(legacyNullTargetSaved.manifest.coverSlot?.applyTarget)
        assertNotNull(CoverCollectionManager.selectedCollectionCover("book_url_1"))
        assertNull(CoverCollectionManager.selectedGroupCollectionCover(1L, "玄幻"))
        val legacyFallbackGroupReq = BookGroup(groupId = 1L, groupName = "玄幻").toCoverRequest()
        assertEquals(resolveMountedGroupDefaultCover(), legacyFallbackGroupReq.path)
        assertTrue(legacyFallbackGroupReq.forcePath)

        // 3. Switch to applyTarget = BOTH: both groups and books hit gallery
        val bothSaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "作用域路由封面包",
            updatedCoverSlot = legacyNullTargetSaved.manifest.coverSlot!!.copy(
                applyTarget = CoverSlotApplyTarget.BOTH
            )
        )
        assertNotNull(CoverCollectionManager.selectedCollectionCover("book_url_1"))
        val bothGroupReq = BookGroup(groupId = 1L, groupName = "玄幻").toCoverRequest()
        assertEquals("玄幻.jpg", File(bothGroupReq.path!!).name)
        assertTrue(bothGroupReq.forcePath)

        // 4. MODE_MIXED for groups: custom group.cover preserved, empty group.cover hits gallery
        val mixedSaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "作用域路由封面包",
            updatedCoverSlot = bothSaved.manifest.coverSlot!!.copy(
                applyMode = CoverSlotApplyMode.MIXED,
                applyTarget = CoverSlotApplyTarget.GROUPS
            )
        )
        val mixedCustomReq = BookGroup(
            groupId = 3L,
            groupName = "玄幻",
            cover = customGroupCoverFile.absolutePath
        ).toCoverRequest()
        assertEquals(customGroupCoverFile.absolutePath, mixedCustomReq.path)
        assertFalse(mixedCustomReq.forcePath)

        val mixedEmptyReq = BookGroup(groupId = 4L, groupName = "都市", cover = null).toCoverRequest()
        assertNotNull(mixedEmptyReq.path)
        assertTrue(File(mixedEmptyReq.path!!).name in setOf("玄幻.jpg", "random_01.jpg", "random_02.jpg"))
        assertTrue(mixedEmptyReq.forcePath)

        // 5. MODE_FALLBACK_ONLY for groups: skips gallery and falls back to groupCoverPath
        val fallbackOnlySaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "作用域路由封面包",
            updatedCoverSlot = mixedSaved.manifest.coverSlot!!.copy(
                applyMode = CoverSlotApplyMode.FALLBACK_ONLY,
                applyTarget = CoverSlotApplyTarget.GROUPS
            )
        )
        assertNull(CoverCollectionManager.selectedGroupCollectionCover(1L, "玄幻"))
        val fallbackOnlyReq = BookGroup(groupId = 1L, groupName = "玄幻", cover = null).toCoverRequest()
        assertEquals(resolveMountedGroupDefaultCover(), fallbackOnlyReq.path)
        assertTrue(fallbackOnlyReq.forcePath)

        // 6. MODE_SEQUENCE for groups: returns immediately on first read and flushes async assignment JSON
        val sequenceSaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "作用域路由封面包",
            updatedCoverSlot = fallbackOnlySaved.manifest.coverSlot!!.copy(
                applyMode = CoverSlotApplyMode.SEQUENCE,
                applyTarget = CoverSlotApplyTarget.GROUPS
            )
        )
        val seqReqFirst = BookGroup(groupId = 20L, groupName = "科幻").toCoverRequest()
        val seqReqSecond = BookGroup(groupId = 21L, groupName = "奇幻").toCoverRequest()
        assertNotNull(seqReqFirst.path)
        assertTrue(File(seqReqFirst.path!!).isFile)
        assertTrue(seqReqFirst.forcePath)
        assertNotNull(seqReqSecond.path)
        assertTrue(File(seqReqSecond.path!!).isFile)
        assertEquals(seqReqFirst.path, BookGroup(groupId = 20L, groupName = "科幻").toCoverRequest().path)

        CoverCollectionManager.flushPendingAssignmentWritesForTest()
        val assignmentFile = File(CoverCollectionManager.rootDir, "assignments/${sequenceSaved.manifest.id}.json")
        assertTrue(assignmentFile.isFile)
        val assignmentJson = assignmentFile.readText()
        assertTrue(assignmentJson.contains("\"group:20\""))
        assertTrue(assignmentJson.contains("\"group:21\""))
    }

    @Test
    fun createEmptyCoverSlot_defaultsToGroupsAndRespectsRememberedTarget() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val defaultCreated = UnifiedPackageRepository.createEmptyPackage(
            kind = PackageKind.COVER_SLOT,
            name = "默认目标封面包"
        )
        assertEquals(CoverSlotApplyTarget.GROUPS, defaultCreated.manifest.coverSlot?.applyTarget)

        val booksCreated = UnifiedPackageRepository.createEmptyPackage(
            kind = PackageKind.COVER_SLOT,
            name = "记住书籍目标封面包",
            initialCoverSlotApplyTarget = CoverSlotApplyTarget.BOOKS
        )
        assertEquals(CoverSlotApplyTarget.BOOKS, booksCreated.manifest.coverSlot?.applyTarget)
    }
}
