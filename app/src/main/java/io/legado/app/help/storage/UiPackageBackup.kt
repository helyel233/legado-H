package io.legado.app.help.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.PreferKey
import java.io.File

/**
 * UI 美术资产包（主题包/底栏图标包/顶栏包/封面集合）的备份导出纯逻辑。
 *
 * 从 [Backup] 中抽出，不依赖 Android 运行时，便于用纯 JVM 单测锁住
 * 「备份端导出的目录名 == 恢复端读取的目录名」这一对称性。
 *
 * 背景：这些目录在恢复端（[Restore.restoreThemePackages] 等）早已实现还原，
 * 但备份端在历史上从未导出，换机时只能靠云同步兜底（而云同步默认关闭），
 * 导致用户自定义的主题包/图标包/顶栏包/封面集合静默丢失。
 */
internal object UiPackageBackup {

    /** 主题包目录名，与 Restore.restoreThemePackages 读取的名字一致。 */
    const val THEME_PACKAGES = "themePackages"
    /** 底栏图标包目录名，与 Restore.restoreNavigationIcons 读取的名字一致。 */
    const val NAVIGATION_BAR_PACKAGES = "navigationBarPackages"
    /** 顶栏包目录名，与 Restore.restoreTopBarPackages 读取的名字一致。 */
    const val TOP_BAR_PACKAGES = "topBarPackages"
    /** 封面集合目录名，与 Restore.restoreCoverCollections 读取的名字一致。 */
    const val COVER_COLLECTIONS = "coverCollections"
    /** 外观方案（kits.json 与方案预览图等）目录名，与 Restore.restoreAppearanceKits 一致。 */
    const val APP_THEME_KITS = "appThemeKits"
    /** V4 统一包（art / param / cover_slot）目录名，与 Restore.restoreUnifiedPackages 一致。 */
    const val UNIFIED_PACKAGES = "unifiedPackages"
    const val UNIFIED_ART = "art"
    const val UNIFIED_PARAM = "param"
    const val UNIFIED_COVER_SLOT = "cover_slot"
    const val UNIFIED_ACTIVE_PREFS_FILE = "active_prefs.json"

    val unifiedKindDirNames = listOf(UNIFIED_ART, UNIFIED_PARAM, UNIFIED_COVER_SLOT)

    /** 统一包槽位挂载与锁定状态的活跃偏好键，随统一包备份/恢复一并迁移。 */
    val unifiedActivePrefKeys = listOf(
        PreferKey.currentArtPackageId,
        PreferKey.activeDayArtPackageId,
        PreferKey.activeNightArtPackageId,
        PreferKey.currentParamPackageId,
        PreferKey.activeDayParamPackageId,
        PreferKey.activeNightParamPackageId,
        PreferKey.currentCoverSlotPackageId,
        PreferKey.activeDayCoverSlotPackageId,
        PreferKey.activeNightCoverSlotPackageId,
        PreferKey.coverSlotLocked,
        PreferKey.coverCollectionShuffleSeeds
    )

    val unifiedBooleanPrefKeys = setOf(PreferKey.coverSlotLocked)

    private val prefsGson = GsonBuilder().setPrettyPrinting().create()

    /** V1 历史包目录清单（供 Restore 检测旧版备份并触发自动合成迁移）。 */
    val legacyPackageDirNames = listOf(
        THEME_PACKAGES,
        NAVIGATION_BAR_PACKAGES,
        TOP_BAR_PACKAGES,
        COVER_COLLECTIONS,
        APP_THEME_KITS
    )

    /** 备份↔恢复两端共享的目录名清单（V1 + V2 双轨兼容），单一真相源。 */
    val packageDirNames = legacyPackageDirNames + UNIFIED_PACKAGES

    /** 临时目录与云端缓存，不跨设备迁移。 */
    private val excludedDirs = setOf("temp", "remote_cache", "appearanceKitTemp", "unifiedPackageTemp")

    /**
     * 美术资产包子目录是否纳入备份：排除临时/缓存/隐藏目录，
     * 以及随应用版本重建的内置包（builtin_*、default），只留用户创建/导入的包。
     */
    fun dirVisible(name: String): Boolean {
        return name.isNotBlank()
            && !name.startsWith(".")
            && name !in excludedDirs
            && !name.startsWith("builtin_")
            && name != "default"
    }

    /**
     * 把 [sourceRoot] 下所有可见的一级子目录整体复制到 [targetRoot]，
     * 目标目录名与源目录名一致（与恢复端按同名目录读取的约定对齐）。
     * 源根目录不存在或无可见子目录时静默跳过。
     */
    fun copyVisiblePackages(sourceRoot: File, targetRoot: File) {
        sourceRoot.listFiles()
            ?.filter { it.isDirectory && dirVisible(it.name) }
            .orEmpty()
            .forEach { dir -> copyDir(dir, File(targetRoot, dir.name)) }
    }

    /**
     * 复制根目录下的可见文件（如 `appThemeKits/kits.json`、预览图）与可见子目录。
     */
    fun copyVisiblePackagesAndFiles(sourceRoot: File, targetRoot: File) {
        if (!sourceRoot.isDirectory) return
        sourceRoot.listFiles().orEmpty().forEach { entry ->
            if (!dirVisible(entry.name)) return@forEach
            if (entry.isDirectory) {
                copyDir(entry, File(targetRoot, entry.name))
            } else if (entry.isFile) {
                val targetFile = File(targetRoot, entry.name)
                targetFile.parentFile?.mkdirs()
                entry.copyTo(targetFile, overwrite = true)
            }
        }
    }

    /**
     * 导出 V4 统一三轨包目录（`unifiedPackages/{art,param,cover_slot}/<pkgId>`）：
     * 过滤掉各目录下的临时/内置/隐藏子目录。
     */
    fun copyUnifiedPackages(
        sourceRoot: File,
        targetRoot: File,
        kindDirNames: List<String> = unifiedKindDirNames
    ) {
        if (!sourceRoot.isDirectory) return
        kindDirNames.forEach { kindDirName ->
            val kindSource = File(sourceRoot, kindDirName)
            if (kindSource.isDirectory) {
                copyVisiblePackages(kindSource, File(targetRoot, kindDirName))
            }
        }
    }

    /**
     * 导出统一包活跃槽位绑定键（含 `activeDayCoverSlotPackageId` / `activeNightCoverSlotPackageId` /
     * `currentCoverSlotPackageId` / `coverSlotLocked` 等）到 `targetRoot/active_prefs.json`。
     */
    fun exportUnifiedActivePrefs(
        targetRoot: File,
        readString: (String) -> String?,
        readBoolean: (String) -> Boolean?
    ) {
        val obj = JsonObject()
        unifiedActivePrefKeys.forEach { key ->
            if (key in unifiedBooleanPrefKeys) {
                readBoolean(key)?.let { obj.addProperty(key, it) }
            } else {
                readString(key)?.takeIf { it.isNotBlank() }?.let { obj.addProperty(key, it) }
            }
        }
        if (obj.size() == 0) return
        targetRoot.mkdirs()
        File(targetRoot, UNIFIED_ACTIVE_PREFS_FILE).writeText(prefsGson.toJson(obj))
    }

    /**
     * 从 `sourceRoot/active_prefs.json` 恢复统一包活跃槽位绑定键。
     */
    fun importUnifiedActivePrefs(
        sourceRoot: File,
        writeString: (String, String) -> Unit,
        writeBoolean: (String, Boolean) -> Unit,
        keyFilter: (String) -> Boolean = { true }
    ): Boolean {
        val file = File(sourceRoot, UNIFIED_ACTIVE_PREFS_FILE)
        if (!file.isFile) return false
        return runCatching {
            val obj = JsonParser.parseString(file.readText()).asJsonObject
            var restoredAny = false
            unifiedActivePrefKeys.forEach { key ->
                if (!keyFilter(key)) return@forEach
                val elem = obj.get(key) ?: return@forEach
                if (!elem.isJsonPrimitive) return@forEach
                val prim = elem.asJsonPrimitive
                if (key in unifiedBooleanPrefKeys && prim.isBoolean) {
                    writeBoolean(key, prim.asBoolean)
                    restoredAny = true
                } else if (prim.isString) {
                    val value = prim.asString
                    if (value.isNotBlank()) {
                        writeString(key, value)
                        restoredAny = true
                    }
                }
            }
            restoredAny
        }.getOrDefault(false)
    }

    private fun copyDir(source: File, target: File) {
        if (!target.exists()) {
            target.mkdirs()
        }
        source.listFiles()?.forEach { file ->
            val targetFile = File(target, file.name)
            if (file.isDirectory) {
                copyDir(file, targetFile)
            } else {
                targetFile.parentFile?.mkdirs()
                file.copyTo(targetFile, overwrite = true)
            }
        }
    }
}
