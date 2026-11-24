package io.legado.app.help.config.pkg

import android.app.Application
import android.content.Context
import android.os.Looper
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.UiPackageBackup
import io.legado.app.ui.main.bookshelf.compose.resolveMountedGroupDefaultCover
import io.legado.app.ui.main.bookshelf.compose.toCoverRequest
import io.legado.app.utils.FileUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        setNightTheme(false)
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

    private fun setNightTheme(isNight: Boolean) {
        val mode = if (isNight) "2" else "1"
        context.putPrefString(PreferKey.themeMode, mode)
        AppConfig.themeMode = mode
    }

    private fun createTempImages(prefix: String, count: Int): List<String> {
        return (1..count).map { idx ->
            tempFolder.newFile("${prefix}_%02d.jpg".format(idx)).apply {
                writeBytes(byteArrayOf(idx.toByte(), (idx * 3).toByte(), (idx * 7).toByte()))
            }.absolutePath
        }
    }

    private fun createBitmaskGroups(count: Int, namePrefix: String = "分组"): List<BookGroup> {
        return (0 until count).map { idx ->
            BookGroup(groupId = 1L shl idx, groupName = "${namePrefix}_${idx + 1}")
        }
    }

    @Test
    fun symmetricDayNightGroupCoverFallback_resolvesDayNightAndLegacyCompat() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        // 1. Only dayGroupCoverPath configured -> both day and night fall back to it
        val dayOnlyBytes = byteArrayOf(11, 22, 33, 44)
        val dayOnlyImg = tempFolder.newFile("only_day_group.png").apply { writeBytes(dayOnlyBytes) }
        val dayOnlySlot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "仅日间分组兜底")
        val dayOnlySaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = dayOnlySlot.manifest.id,
            newName = "仅日间分组兜底",
            updatedCoverSlot = CoverSlotPackagePayload(
                applyMode = CoverSlotApplyMode.FALLBACK_ONLY,
                applyTarget = CoverSlotApplyTarget.GROUPS,
                dayGroupCoverPath = dayOnlyImg.absolutePath
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, dayOnlySaved, forNight = false)
        UnifiedPackageRepository.mountCoverSlotPackage(context, dayOnlySaved, forNight = true)

        setNightTheme(false)
        val dayOnlyDayResolved = resolveMountedGroupDefaultCover()
        assertNotNull(dayOnlyDayResolved)
        assertTrue(File(dayOnlyDayResolved!!).isFile)
        assertTrue(File(dayOnlyDayResolved).readBytes().contentEquals(dayOnlyBytes))
        assertEquals(dayOnlyDayResolved, BookGroup(1L, "武侠").toCoverRequest().path)

        setNightTheme(true)
        val dayOnlyNightResolved = resolveMountedGroupDefaultCover()
        assertNotNull(dayOnlyNightResolved)
        assertEquals(dayOnlyDayResolved, dayOnlyNightResolved)
        assertTrue(File(dayOnlyNightResolved!!).readBytes().contentEquals(dayOnlyBytes))
        assertEquals(dayOnlyNightResolved, BookGroup(1L, "武侠").toCoverRequest().path)

        // 2. Both dayGroupCoverPath and nightGroupCoverPath configured -> day uses day, night uses night
        val bothDayBytes = byteArrayOf(51, 52, 53, 54)
        val bothNightBytes = byteArrayOf(61, 62, 63, 64)
        val bothDayImg = tempFolder.newFile("both_day_group.png").apply { writeBytes(bothDayBytes) }
        val bothNightImg = tempFolder.newFile("both_night_group.png").apply { writeBytes(bothNightBytes) }
        val bothSlot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "日夜独立分组兜底")
        val bothSaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = bothSlot.manifest.id,
            newName = "日夜独立分组兜底",
            updatedCoverSlot = CoverSlotPackagePayload(
                applyMode = CoverSlotApplyMode.FALLBACK_ONLY,
                applyTarget = CoverSlotApplyTarget.GROUPS,
                dayGroupCoverPath = bothDayImg.absolutePath,
                nightGroupCoverPath = bothNightImg.absolutePath
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, bothSaved, forNight = false)
        UnifiedPackageRepository.mountCoverSlotPackage(context, bothSaved, forNight = true)

        setNightTheme(false)
        val resolvedDay = resolveMountedGroupDefaultCover()
        assertNotNull(resolvedDay)
        assertTrue(File(resolvedDay!!).readBytes().contentEquals(bothDayBytes))
        assertEquals(resolvedDay, BookGroup(2L, "科幻").toCoverRequest().path)

        setNightTheme(true)
        val resolvedNight = resolveMountedGroupDefaultCover()
        assertNotNull(resolvedNight)
        assertNotEquals(resolvedDay, resolvedNight)
        assertTrue(File(resolvedNight!!).readBytes().contentEquals(bothNightBytes))
        assertEquals(resolvedNight, BookGroup(2L, "科幻").toCoverRequest().path)

        // 3. Legacy package with only groupCoverPath -> both day and night fall back to groupCoverPath
        val legacyBytes = byteArrayOf(71, 72, 73, 74)
        val legacyImg = tempFolder.newFile("legacy_group_only.png").apply { writeBytes(legacyBytes) }
        val legacySlot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "旧版单分组兜底")
        val legacySaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = legacySlot.manifest.id,
            newName = "旧版单分组兜底",
            updatedCoverSlot = CoverSlotPackagePayload(
                applyMode = CoverSlotApplyMode.FALLBACK_ONLY,
                applyTarget = CoverSlotApplyTarget.GROUPS,
                groupCoverPath = legacyImg.absolutePath
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, legacySaved, forNight = false)
        UnifiedPackageRepository.mountCoverSlotPackage(context, legacySaved, forNight = true)

        setNightTheme(false)
        val legacyDayResolved = resolveMountedGroupDefaultCover()
        assertNotNull(legacyDayResolved)
        assertTrue(File(legacyDayResolved!!).readBytes().contentEquals(legacyBytes))
        assertEquals(legacyDayResolved, BookGroup(3L, "历史").toCoverRequest().path)

        setNightTheme(true)
        val legacyNightResolved = resolveMountedGroupDefaultCover()
        assertNotNull(legacyNightResolved)
        assertEquals(legacyDayResolved, legacyNightResolved)
        assertTrue(File(legacyNightResolved!!).readBytes().contentEquals(legacyBytes))
        assertEquals(legacyNightResolved, BookGroup(3L, "历史").toCoverRequest().path)
    }

    @Test
    fun shuffleBagGroupAssignment_zeroDuplicatesFor12BitmaskGroupsAndBalancedFor25Groups() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val images20 = createTempImages("bag_cover", 20)
        val groups12 = createBitmaskGroups(12)

        // MODE_RANDOM: 12 real bitmask groups -> 12 distinct covers
        val randomSlot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "洗牌随机包")
        val randomSaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = randomSlot.manifest.id,
            newName = "洗牌随机包",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = images20,
                applyMode = CoverSlotApplyMode.RANDOM,
                applyTarget = CoverSlotApplyTarget.GROUPS
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, randomSaved, forNight = false)

        val randomPaths = groups12.map { it.toCoverRequest().path }
        assertTrue(randomPaths.all { !it.isNullOrBlank() && File(it).isFile })
        assertEquals(12, randomPaths.distinct().size)
        // Sticky on repeated reads
        assertEquals(randomPaths, groups12.map { it.toCoverRequest().path })

        // MODE_MIXED: 12 real bitmask groups without custom covers -> 12 distinct covers
        val mixedSlot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "洗牌混合包")
        val mixedSaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = mixedSlot.manifest.id,
            newName = "洗牌混合包",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = images20,
                applyMode = CoverSlotApplyMode.MIXED,
                applyTarget = CoverSlotApplyTarget.GROUPS
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, mixedSaved, forNight = false)

        val mixedPaths = groups12.map { it.toCoverRequest().path }
        assertTrue(mixedPaths.all { !it.isNullOrBlank() && File(it).isFile })
        assertEquals(12, mixedPaths.distinct().size)

        // 25 groups with 20 images: first 20 exhaust all 20 images, max usage across 25 is 2
        val overflowSlot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "超额轮次包")
        val overflowSaved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = overflowSlot.manifest.id,
            newName = "超额轮次包",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = images20,
                applyMode = CoverSlotApplyMode.RANDOM,
                applyTarget = CoverSlotApplyTarget.GROUPS
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, overflowSaved, forNight = false)

        val groups25 = createBitmaskGroups(25, namePrefix = "超额分组")
        val paths25 = groups25.map { it.toCoverRequest().path!! }
        assertEquals(20, paths25.take(20).distinct().size)
        val counts = paths25.groupingBy { it }.eachCount()
        assertEquals(20, counts.size)
        assertTrue((counts.values.maxOrNull() ?: 0) <= 2)
    }

    @Test
    fun preAssignGroupNameMatches_guaranteesOrderIndependentNameHitAndZeroDuplicates() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val xuanhuanImg = tempFolder.newFile("玄幻.jpg").apply { writeBytes(byteArrayOf(1, 1, 1)) }.absolutePath
        val scifiImg = tempFolder.newFile("科幻.png").apply { writeBytes(byteArrayOf(2, 2, 2)) }.absolutePath
        val normalImgs = createTempImages("normal", 10)

        val slot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "同名预占包")
        val saved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "同名预占包",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = listOf(xuanhuanImg, scifiImg) + normalImgs,
                applyMode = CoverSlotApplyMode.RANDOM,
                applyTarget = CoverSlotApplyTarget.GROUPS
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, saved, forNight = false)

        // Intentionally put "都市" (which has no name-matched image) BEFORE "玄幻" and "科幻"
        val groups = listOf(
            BookGroup(groupId = 1L, groupName = "都市"),
            BookGroup(groupId = 2L, groupName = "玄幻"),
            BookGroup(groupId = 4L, groupName = "科幻"),
            BookGroup(groupId = 8L, groupName = "历史"),
            BookGroup(groupId = 16L, groupName = "武侠"),
            BookGroup(groupId = 32L, groupName = "仙侠")
        )

        CoverCollectionManager.preAssignGroupNameMatches(groups)

        val assignedByGroup = groups.associate { group ->
            group.groupName to group.toCoverRequest().path!!
        }
        assertEquals("玄幻.jpg", File(assignedByGroup.getValue("玄幻")).name)
        assertEquals("科幻.png", File(assignedByGroup.getValue("科幻")).name)

        val allAssignedPaths = assignedByGroup.values.toList()
        assertEquals(groups.size, allAssignedPaths.distinct().size)

        val nonNameMatchedNames = listOf("都市", "历史", "武侠", "仙侠").map { name ->
            File(assignedByGroup.getValue(name)).name
        }
        assertFalse("玄幻.jpg" in nonNameMatchedNames)
        assertFalse("科幻.png" in nonNameMatchedNames)
    }

    @Test
    fun shuffleBagBothTarget_isolatesBookAndGroupNamespaces() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val images20 = createTempImages("both_ns", 20)
        val slot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "双端隔离包")
        val saved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "双端隔离包",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = images20,
                applyMode = CoverSlotApplyMode.RANDOM,
                applyTarget = CoverSlotApplyTarget.BOTH
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, saved, forNight = false)

        val bookCovers = (1..30).map { idx ->
            CoverCollectionManager.selectedCollectionCover("https://book.example/$idx")
        }
        assertTrue(bookCovers.all { !it.isNullOrBlank() && File(it).isFile })
        assertEquals(20, bookCovers.take(20).distinct().size)

        val groups12 = createBitmaskGroups(12, namePrefix = "双端分组")
        val groupCovers = groups12.map { it.toCoverRequest().path }
        assertTrue(groupCovers.all { !it.isNullOrBlank() && File(it).isFile })
        assertEquals(12, groupCovers.distinct().size)
    }

    @Test
    fun reshuffle_incrementsSeedClearsAssignmentsAndProducesNewZeroDupOrder() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val images20 = createTempImages("reshuffle_cover", 20)
        val slot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "重新洗牌包")
        val saved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "重新洗牌包",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = images20,
                applyMode = CoverSlotApplyMode.RANDOM,
                applyTarget = CoverSlotApplyTarget.GROUPS
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, saved, forNight = false)

        val collectionId = saved.manifest.id
        val seedBefore = CoverCollectionManager.getShuffleSeed(collectionId)
        val groups12 = createBitmaskGroups(12, namePrefix = "洗牌分组")
        val beforePaths = groups12.map { it.toCoverRequest().path!! }
        assertEquals(12, beforePaths.distinct().size)

        CoverCollectionManager.flushPendingAssignmentWritesForTest()
        val assignmentFile = File(CoverCollectionManager.rootDir, "assignments/$collectionId.json")
        assertTrue(assignmentFile.isFile)

        val seedAfter = CoverCollectionManager.reshuffle(collectionId)
        assertEquals(seedBefore + 1L, seedAfter)
        assertEquals(seedAfter, CoverCollectionManager.getShuffleSeed(collectionId))
        assertFalse(assignmentFile.exists())

        val afterPaths = groups12.map { it.toCoverRequest().path!! }
        assertEquals(12, afterPaths.distinct().size)
        assertNotEquals(beforePaths, afterPaths)
    }

    @Test
    fun mainThreadColdStart_doesNotBlockDiskReadAndMergesAsyncLoad() {
        val images20 = createTempImages("cold_start", 20)
        val saved = runWithMainPump {
            UnifiedPackageRepository.ensureInitialized(context)
            val slot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "冷启动测试包")
            val edited = UnifiedPackageRepository.saveEditedCoverSlotPackage(
                context = context,
                entryId = slot.manifest.id,
                newName = "冷启动测试包",
                updatedCoverSlot = CoverSlotPackagePayload(
                    covers = images20,
                    applyMode = CoverSlotApplyMode.RANDOM,
                    applyTarget = CoverSlotApplyTarget.GROUPS
                )
            )
            UnifiedPackageRepository.mountCoverSlotPackage(context, edited, forNight = false)
            CoverCollectionManager.awaitAssignmentIoForTest()
            edited
        }
        val collectionId = saved.manifest.id
        val activeImages = runWithMainPump {
            CoverCollectionManager.get(false, collectionId)!!.images
        }
        assertEquals(20, activeImages.size)

        val assignmentsDir = File(CoverCollectionManager.rootDir, "assignments").apply { mkdirs() }
        val json = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("bookUrl", "group:1")
                addProperty("imagePath", activeImages[5])
            })
            add(JsonObject().apply {
                addProperty("bookUrl", "group:2")
                addProperty("imagePath", activeImages[6])
            })
        }
        File(assignmentsDir, "$collectionId.json").writeText(json.toString())

        // Reset in-memory assignment cache and warm only collection index
        CoverCollectionManager.invalidateCache()
        runWithMainPump {
            CoverCollectionManager.get(false, collectionId)
        }

        // Execute on Android main thread: must return immediately without synchronous disk read,
        // then merge disk baseline once awaitAssignmentIoForTest() finishes.
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val revBefore = CoverCollectionManager.selectionKey()
        val tentativeGroup1 = CoverCollectionManager.selectedGroupCollectionCover(1L, "组1")
        val tentativeGroup3 = CoverCollectionManager.selectedGroupCollectionCover(3L, "新分组")
        assertNotNull(tentativeGroup1)
        assertNotNull(tentativeGroup3)

        CoverCollectionManager.awaitAssignmentIoForTest()

        val mergedGroup1 = CoverCollectionManager.selectedGroupCollectionCover(1L, "组1")
        val mergedGroup2 = CoverCollectionManager.selectedGroupCollectionCover(2L, "组2")
        val mergedGroup3 = CoverCollectionManager.selectedGroupCollectionCover(3L, "新分组")
        assertEquals(activeImages[5], mergedGroup1)
        assertEquals(activeImages[6], mergedGroup2)
        assertNotNull(mergedGroup3)
        assertEquals(3, listOf(mergedGroup1, mergedGroup2, mergedGroup3).distinct().size)
        if (tentativeGroup1 != activeImages[5]) {
            assertNotEquals(revBefore, CoverCollectionManager.selectionKey())
        }
    }

    @Test
    fun assignFromDeck_2500BooksAcross20ImagesIsFastBalancedAndWritesSingleJson() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val images20 = createTempImages("perf_2500", 20)
        val slot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "性能均衡包")
        val saved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "性能均衡包",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = images20,
                applyMode = CoverSlotApplyMode.RANDOM,
                applyTarget = CoverSlotApplyTarget.BOOKS
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, saved, forNight = false)
        CoverCollectionManager.awaitAssignmentIoForTest()

        val startNs = System.nanoTime()
        val assigned = (1..2500).map { idx ->
            CoverCollectionManager.selectedCollectionCover("https://book.example/perf/$idx")!!
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        assertTrue("2500 assignments took too long: ${elapsedMs}ms", elapsedMs < 3000L)
        assertEquals(2500, assigned.size)

        val counts = assigned.groupingBy { it }.eachCount()
        assertEquals(20, counts.size)
        val maxCount = counts.values.maxOrNull()!!
        val minCount = counts.values.minOrNull()!!
        assertTrue("Expected balanced distribution (max=$maxCount, min=$minCount)", maxCount - minCount <= 1)

        CoverCollectionManager.flushPendingAssignmentWritesForTest()
        val assignmentsDir = File(CoverCollectionManager.rootDir, "assignments")
        val files = assignmentsDir.listFiles()?.toList().orEmpty()
        assertEquals(1, files.size)
        val assignmentFile = files.single()
        assertEquals("${saved.manifest.id}.json", assignmentFile.name)
        val jsonText = assignmentFile.readText()
        // P3 #3: no dead "index" field serialized
        assertFalse(jsonText.contains("\"index\""))
    }

    @Test
    fun legacyIndexMigrationAndAvoidableDuplicates_autoHealOnLoadWithoutReshuffle() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val images10 = createTempImages("heal_cover", 10)
        val slot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "自愈测试包")
        val saved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "自愈测试包",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = images10,
                applyMode = CoverSlotApplyMode.RANDOM,
                applyTarget = CoverSlotApplyTarget.GROUPS
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, saved, forNight = false)
        CoverCollectionManager.awaitAssignmentIoForTest()

        val collectionId = saved.manifest.id
        val activeImages = CoverCollectionManager.get(false, collectionId)!!.images
        val assignmentsDir = File(CoverCollectionManager.rootDir, "assignments").apply { mkdirs() }
        val legacyJson = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("bookUrl", "group:1")
                addProperty("imagePath", "")
                addProperty("index", 0)
            })
            add(JsonObject().apply {
                addProperty("bookUrl", "group:2")
                addProperty("imagePath", activeImages[0])
            })
            add(JsonObject().apply {
                addProperty("bookUrl", "group:3")
                addProperty("imagePath", activeImages[1])
            })
            add(JsonObject().apply {
                addProperty("bookUrl", "group:4")
                addProperty("imagePath", "/nonexistent/deleted_cover.jpg")
            })
        }
        File(assignmentsDir, "$collectionId.json").writeText(legacyJson.toString())

        CoverCollectionManager.invalidateCache()
        CoverCollectionManager.preloadAssignmentsAsync(collectionId)
        CoverCollectionManager.awaitAssignmentIoForTest()

        val group1Path = CoverCollectionManager.selectedGroupCollectionCover(1L, "组1")
        val group2Path = CoverCollectionManager.selectedGroupCollectionCover(2L, "组2")
        val group3Path = CoverCollectionManager.selectedGroupCollectionCover(3L, "组3")
        val group4Path = CoverCollectionManager.selectedGroupCollectionCover(4L, "组4")

        assertEquals(activeImages[0], group1Path)
        assertEquals(activeImages[1], group3Path)
        val allPaths = listOf(group1Path, group2Path, group3Path, group4Path)
        assertTrue(allPaths.all { !it.isNullOrBlank() && File(it).isFile })
        assertEquals(4, allPaths.distinct().size)

        CoverCollectionManager.flushPendingAssignmentWritesForTest()
        val healedText = File(assignmentsDir, "$collectionId.json").readText()
        assertFalse(healedText.contains("\"index\""))
    }

    @Test
    fun groupRenameExactMatch_evictsPreviousRandomHolderAndReassignsWithoutDuplicates() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val xuanhuanImg = tempFolder.newFile("玄幻.jpg").apply { writeBytes(byteArrayOf(7, 7, 7)) }.absolutePath
        val normalImgs = createTempImages("rename_pool", 9)
        val slot = UnifiedPackageRepository.createEmptyPackage(PackageKind.COVER_SLOT, "改名驱逐包")
        val saved = UnifiedPackageRepository.saveEditedCoverSlotPackage(
            context = context,
            entryId = slot.manifest.id,
            newName = "改名驱逐包",
            updatedCoverSlot = CoverSlotPackagePayload(
                covers = listOf(xuanhuanImg) + normalImgs,
                applyMode = CoverSlotApplyMode.RANDOM,
                applyTarget = CoverSlotApplyTarget.GROUPS
            )
        )
        UnifiedPackageRepository.mountCoverSlotPackage(context, saved, forNight = false)
        CoverCollectionManager.awaitAssignmentIoForTest()

        val initialGroups = (0 until 10).map { idx ->
            BookGroup(groupId = (1L shl idx), groupName = "普通分组$idx")
        }
        val initialAssignments = initialGroups.associate { group ->
            group.groupId to CoverCollectionManager.selectedGroupCollectionCover(group.groupId, group.groupName)!!
        }
        assertEquals(10, initialAssignments.values.distinct().size)

        val victimGroup = initialGroups.first { File(initialAssignments.getValue(it.groupId)).name == "玄幻.jpg" }
        val renamedTarget = initialGroups.first { it.groupId != victimGroup.groupId }
        val updatedGroups = initialGroups.map { group ->
            if (group.groupId == renamedTarget.groupId) group.copy(groupName = "玄幻") else group
        }

        val revBefore = CoverCollectionManager.selectionKey()
        CoverCollectionManager.preAssignGroupNameMatches(updatedGroups)
        assertNotEquals(revBefore, CoverCollectionManager.selectionKey())

        val finalAssignments = updatedGroups.associate { group ->
            group.groupId to CoverCollectionManager.selectedGroupCollectionCover(group.groupId, group.groupName)!!
        }
        assertEquals("玄幻.jpg", File(finalAssignments.getValue(renamedTarget.groupId)).name)
        assertNotEquals("玄幻.jpg", File(finalAssignments.getValue(victimGroup.groupId)).name)
        assertEquals(10, finalAssignments.values.distinct().size)
    }

    @Test
    fun shuffleSeeds_inBackupWhitelistAndPersistAcrossCacheInvalidationAndLegacyMigration() = runWithMainPump {
        UnifiedPackageRepository.ensureInitialized(context)

        val prevIgnoreCover = BackupConfig.ignoreConfig["coverConfig"]
        try {
            BackupConfig.ignoreConfig["coverConfig"] = false
            assertTrue(BackupConfig.keyIsNotIgnore(PreferKey.coverCollectionShuffleSeeds))
            BackupConfig.ignoreConfig["coverConfig"] = true
            assertFalse(BackupConfig.keyIsNotIgnore(PreferKey.coverCollectionShuffleSeeds))
        } finally {
            if (prevIgnoreCover == null) {
                BackupConfig.ignoreConfig.remove("coverConfig")
            } else {
                BackupConfig.ignoreConfig["coverConfig"] = prevIgnoreCover
            }
        }
        assertTrue(PreferKey.coverCollectionShuffleSeeds in UiPackageBackup.unifiedActivePrefKeys)

        val collectionId = "seed_persist_col"
        context.putPrefLong("coverCollectionShuffleSeed_$collectionId", 4L)
        CoverCollectionManager.invalidateCache()
        assertEquals(4L, CoverCollectionManager.getShuffleSeed(collectionId))

        val nextSeed = CoverCollectionManager.reshuffle(collectionId)
        assertEquals(5L, nextSeed)
        assertFalse(context.defaultSharedPreferences.contains("coverCollectionShuffleSeed_$collectionId"))
        val rawSeeds = context.getPrefString(PreferKey.coverCollectionShuffleSeeds)
        assertNotNull(rawSeeds)
        assertTrue(rawSeeds!!.contains(collectionId))

        CoverCollectionManager.invalidateCache()
        assertEquals(5L, CoverCollectionManager.getShuffleSeed(collectionId))
    }
}
