# Legado-H 第四轮 UI 底层架构与双包体系演进方案（V4.1 · 代码核验修正版）

> **V4.1 架构审查修正摘要**
> 1. **顺序倒置修正**：严格按 **P0（Token 引擎 4 档补齐与 8 路 Scope 消费方）→ P1（备份/恢复/云端 v1+v2 双轨兼容层）→ P2（双包格式 + CoW 写时复制克隆 + 内容哈希去重 + 幂等迁移不删旧目录）→ P3（美术/参数二级编辑器 + 外观中心 4 段式 + 预览快捷抽屉）→ P4（下线 6 个旧入口并通过全部门禁脚本）** 推进。
> 2. **4 项硬伤修复**：
>    - **硬伤 ①（备份/恢复/云同步不断链）**：迁移后**保留旧目录并写 `.migrated` 标记**（不递归物理删除）；`Backup / Restore / RestoreSnapshot` 新增 `unifiedPackages` v2 目录并保留 v1 读取，恢复 v1 备份后自动触发迁移器合成为 v2 包。
>    - **硬伤 ②（消费方先行）**：`ResolvedThemeTokens` 与 `ThemeTokenCompiler` 先行提供完整 4 Scope + 4 材质档位（含 `CLEAR`）+ 阴影 + 描边 + 密度消费能力。
>    - **硬伤 ③（严守 `GlassMaterialPreset` 唯一参数表 + 4 档枚举）**：材质枚举锁死 `SOLID / CLEAR / FROSTED / GLASS` 四档（保留 `CLEAR_SURFACE_ALPHA = 0.86f` 口径），参数包只存档位与调节量，绝不另建第二套材质参数表。
>    - **硬伤 ④（写时复制 CoW 克隆 + 导出物化）**：克隆包只写 `manifest.json`（记录 `basePackageId`）与增量替换文件，读取走 `overlay -> base` 解析；导出 `.zip` 或删除基底包前自动物化为 100% 自包含整包。
> 3. **内部矛盾消除（氛围层 vs 几何层显式分区）**：
>    - **随日夜切换（日/夜双槽）**：`ArtPackage` 全量（配色/壁纸/字体/底栏图标/封面集/启动图）+ `ParamPackage.atmosphere`（4 档材质/强度/表面与弹窗透明度/壁纸模糊）；
>    - **全局单份不随日夜跳变**：`ParamPackage.geometry`（全局圆角倍率/阴影/空间密度 `densityScale`/4 大区域几何覆写）与独立的「界面布局」页（底栏或侧栏/列数/搜索框显隐）。
> 4. **墨水屏正交化与目录真相源**：`AppConfig.isEInkMode` 改为独立于「跟随系统/日间/夜间」的正交渲染降级开关；废除单点故障 `registry.json`，以目录扫描 `manifest.json` 为唯一真相源。

---

## 一、 架构分层与数据契约

### 1. 双包数据模型（`ArtPackage` × `ParamPackage`）

| 包类型 | 分区 | 是否随日夜切换 | 负责字段 |
| :--- | :--- | :---: | :--- |
| **美术资源包 (`kind = "art"`)** | 感官资产全集（支持 `basePackageId` CoW 继承与导出物化） | ✅ 日/夜双槽<br>(`activeDayArtPackageId` / `activeNightArtPackageId`) | • 12 项主题色板<br>• 7 处壁纸槽（`main / topBar / bottomBar / sidebar / bookInfo / panel / welcome`）及裁剪/透明度<br>• 正文与标题字体（`fonts/`）<br>• 底栏与顶栏自定义图标集（`icons/`）<br>• 书架封面图片集与默认封面（`covers/`） |
| **参数设置包 (`kind = "param"`)** | `@atmosphere`（材质与光影氛围） | ✅ 日/夜双槽<br>(`activeDayParamPackageId` / `activeNightParamPackageId`) | • 4 档材质模式（`solid / clear / frosted / glass`，委托 `GlassMaterialPreset`）<br>• 玻璃强度与 4 项倍率（`blur / tint / shell / stroke`）、描边色<br>• 表面不透明度、弹窗不透明度、面板边框不透明度、壁纸模糊度 |
| **参数设置包 (`kind = "param"`)** | `@geometry`（人体工学几何与区域覆写） | ❌ 全局单份<br>（切日夜不跳变、换美术不丢失） | • 全局圆角倍率（`cornerBaseScale`）、卡片阴影（`cardShadow`）、空间密度（`densityScale`）<br>• 4 大核心区域覆写（`topBar / bottomBar / bookshelfCard / dialog`） |
| **界面布局（独立设置页）** | 骨架与容器分布 | ❌ 全局单份 | • 导航骨架预设（底栏/侧栏/悬浮）、发现与书架列数、顶栏搜索与筛选条显隐 |

### 2. 防重名、内容去重与安全删除契约
1. **同域唯一名称（`PackageNameValidator`）**：在 `art` 与 `param` 各自域内，新建、克隆、重命名、导入（不同内容但同名）时强制大小写不敏感查重，重名直接拦截。
2. **同内容 SHA-256 指纹去重**：导入包时计算内容哈希 `contentHash`；若本地已存在相同 `contentHash` 的包，直接提示「已存在相同包」并挂载，不逼用户改名制造重复垃圾包。
3. **写时复制（CoW）与安全删除**：
   - 克隆包仅写入 `manifest.json (basePackageId = source.id)`，替换单个图标/壁纸时只把新文件写入克隆包目录；
   - 删除某个被其他克隆包引用的基底包前，先将基底包文件自动物化（materialize）到派生包目录；
   - 删除正被日/夜槽位挂载的包时，弹窗提示「当前正被日间/夜间槽位使用」，确认删除后自动回退挂载至内置默认包。

---

## 二、 五阶段执行切片（P0 → P4）

| 阶段 | 核心交付内容 | 验证与门禁标准 |
| :--- | :--- | :--- |
| **P0：Token 引擎 4 档补齐 + 8 路 Scope 消费方闭环** | 1. 补齐 `ThemeTokenCompiler` 与 `ResolvedScopeTokens` 对 `GlassMaterialPreset` 4 档（`SOLID / CLEAR / FROSTED / GLASS`）的完整支持，严守 `GlassMaterialPreset` 单一参数表；<br>2. 确认 4 Scope（`TOP_BAR / BOTTOM_BAR / BOOKSHELF_CARD / DIALOG`）× Compose/XML 8 条渲染路径对 `CLEAR`、圆角、透明度、阴影、描边与 `densityScale` 的真实消费；<br>3. 补齐 4 档材质与 Scope 求值的 JVM Golden Tests。 | • `ThemeTokenCompilerTest` Golden Tests 100% 通过；<br>• `python3 tools/check_glass_governance.py` 通过。 |
| **P1：备份 / 恢复 / 云同步 v2+v1 兼容层** | 1. `UiPackageBackup`、`Backup`、`Restore`、`RestoreSnapshot` 新增 `unifiedPackages` v2 目录支持；<br>2. `Restore` 保留 v1 5 大旧目录还原能力，并在 v1 目录恢复后自动触发 `LegacyPackageMigrator.migrateLocalPackagesIfNeeded(context, forceReconcile = true)` 转入 v2；<br>3. `WebDavCloudStorageBackend` 与 `AppCloudStorage` 兼容旧远端目录与新 `uiPackages/` 目录。 | • `UiPackageBackupCompatibilityTest` 单测锁死 v1/v2 备份与恢复对称性；<br>• 旧备份 `.zip` 恢复后自动转入新包体系，零黑洞。 |
| **P2：双包 V2 模型 + CoW 克隆 + 氛围/几何分区 + 幂等迁移** | 1. 升级 `UnifiedPackageModels` / `UnifiedPackageCodec` 支持 `basePackageId`（CoW 继承）、`contentHash`（去重）、自包含 `icons/covers` 映射、以及参数包 `atmosphere`（随日夜）与 `geometry`（全局单份）分区；<br>2. 实现 `UnifiedPackageRepository` 的 CoW 文件解析、导出物化、唯一包名校验、日/夜 4 指针挂载与 `ThemeConfig.applyDayNight` 自动重投影；<br>3. `LegacyPackageMigrator` 幂等校验合成旧包（合并主题+顶栏+底栏图标+封面+启动图），迁移后只写 `.migrated` 标记，**绝不递归删除旧目录**。 | • `UnifiedPackageCodecTest`、`UnifiedPackageCoWTest`、`LegacyPackageMigratorTest` 100% 通过；<br>• 切日夜时材质氛围自动切换，圆角与 `densityScale` 不跳变。 |
| **P3：二级全功能编辑器 + 外观中心 4 段式 + 预览微调抽屉** | 1. 落地《美术包编辑器》（名称唯一校验、12 色、壁纸与启动图、字体、底栏图标、封面集）与《参数包编辑器》（名称唯一校验、4 档材质氛围、全局圆角/阴影/密度、4 大区域覆写）；<br>2. 重构 `AppearanceKitActivity` 为 4 段式：**① 主题模式（跟随/日/夜）+ 独立正交墨水屏开关** → **② 实时预览卡（内嵌圆角/透明度/玻璃强度 3 项快捷微调抽屉）** → **③ 美术资源包（日/夜双槽 + 选择/新建/克隆/编辑/导入/导出/删除）** → **④ 参数设置包（日/夜双槽 + 选择/新建/克隆/编辑/导入/导出/删除）**。 | • 外观中心主页结构清晰，快捷抽屉实时预览，克隆/重命名重名拦截生效，删除挂载包自动回退默认包。 |
| **P4：安全下线散装旧入口 + 全量门禁脚本校验** | 1. 从 `MyFragment`、`ThemeConfigFragment`、`ConfigActivity`、`ConfigTag`、`SettingsSearchIndex` 收拢下线独立旧包管理入口（保留封面网络/规则设置与启动页停留时长等行为开关，保留回滚开关）；<br>2. 执行仓库全部静态门禁脚本与 JVM 单测套件。 | • `check_settings_mapping.py`、`check_glass_governance.py`、`check_ui_layout_tokens.py`、`check_compose_theme_coverage.py` 全部 PASS；<br>• 全套 JVM 单元测试 0 failure。 |
