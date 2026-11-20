# Legado-H 第三轮 UI 配置域架构重构定稿方案（方案二 · 实测修订版）

> **版本裁决与文档替代声明（Supersedes R2-M3）**
> 1. 本方案正式采用 **方案二（一体化工作台 + UI 配置域 Compose 收口）**，**淘汰方案三**。
> 2. 本方案正式 **替代（Supersede）** `docs/ui-architecture-v2-plan.md` 中的 **R2-M3（外观快照胶囊条）**（R2-M1 的 `SurfaceHierarchyDerivation` 与 R2-M2 的 `UiLayoutConfigFragment` 布局剥离继续保留生效）。

---

## 一、精确对账后的病灶清单与范围边界（含 Non-Goal 声明）

### 1. 事实口径精确修正
- **既有单测基线**：
  - 外观套件单测 **10 条**（`AppearanceKitDiffTest` 8 条 + `AppearanceKitM1bTest` 2 条）；
  - UI 包备份单测 **6 条**（`UiPackageBackupTest` 6 条）；
  - 主题/圆角/搜索单测 **29 条**（`TopBarChromeSpecTest` 14 + `SurfaceHierarchyDerivationTest` 7 + `ThemeTokenCompilerTest` 4 + `PrefBatchTransactionTest` 2 + `SettingsSearchRoutingTest` 2）；
  - 本轮新增 **`ThemeEditDraftTest`** 与 **`LegadoSegmentedControlTest`** 纯逻辑单测。
- **`ThemeManageActivity` 待收敛草稿字段**：`ThemeManageActivity.kt:169-205` 共 **27 个** `pending*` / `editingConfigNight` 字段（当前无 `onSaveInstanceState` 保护）。
- **野生 `LegadoMiuixPalette` 调用点**：**2 个文件、4 个调用点**（`MySettingsScreen.kt:707` 1 处 + `SettingSpecScreen.kt:419, 478, 613` 3 处）。
- **0 引用死文件清理清单**：
  - R3-M1c 删除：`app/src/main/java/io/legado/app/lib/prefs/fragment/ComposePreferenceScreen.kt`（524 行，全仓 0 引用）；
  - R3-M2 删除：`PackageManageUi.kt`、`dialog_theme_package_edit.xml`、`item_theme_package_option.xml`、`item_theme_package_slider.xml`。
- **4 处日/夜初值硬编码 `false`**：`ThemeManageActivity.kt:166`、`TopBarManageActivity.kt:73`、`NavigationBarManageActivity.kt:95`、`CoverCollectionManageActivity.kt:38`（正确范式：`EpubLoadingTemplateManageActivity.kt:39` `mutableStateOf(AppConfig.isNightTheme)`）。
- **列表 `28.dp` 双重水平缩进**：`AppManagementCard` 自带 `padding(horizontal = 12.dp)`，叠加 `AdvancedTitleManageScreen.kt:110` 与 `AdvancedTipManageScreen.kt:110` 的 `contentPadding(horizontal = 16.dp)`。
- **同排按钮「前两颗无框、第三颗有框」**：`AppPackageManageComponents.kt:324-339` (`AppPackageManageMoreButton`) 与 `ThemeManageActivity.kt:2524-2574` (`ThemePackageMoreButton`) 使用 `AndroidView(TextView)` + `UiCorner.actionSelector` 强带 1dp 描边。

### 2. 顶栏统一的范围边界：严格限定于「UI 配置与外观管理域」（显式 Non-Goal）
- **In-Scope（目标页）**：
  1. `ConfigActivity.kt`（17 个设置 Fragment 的 `ConfigTopBar`）；
  2. `ui/config` 下 12 个独立管理 Activity：`AppearanceKitActivity`、`ReadingDecorCenterActivity`、`ThemeManageActivity`、`TopBarManageActivity`、`NavigationBarManageActivity`、`CoverCollectionManageActivity`、`BubbleManageActivity`、`AdvancedTitleManageActivity`、`AdvancedTipManageActivity`、`EpubLoadingTemplateManageActivity`、`ShareNoteTemplateManageActivity`、`BookInfoManageActivity`；
  3. `ui/config` 下 5 个手写 `Row` 顶栏页：`LibraryContainerManageScreen`、`S3ContainerManageScreen`、`RelaySettingsActivity`、`AiProviderManageActivity`、`AiImageProviderManageScreen`。
- **Explicit Non-Goals（显式非目标页）**：
  - 阅读器、书籍详情 (`BookInfoActivity`)、WebView (`WebViewActivity`)、RSS 阅读 (`ReadRssActivity`)、代码/规则编辑器 (`CodeEditActivity`、`SourceEditActivity`)、音视频页等 40+ 个依赖 `AppBarLayout` 滚动联动或业务 Toolbar 的非配置域页面；
  - 已使用 `AppManagementScaffold` 的书源/订阅/替换规则等带搜索多选栏的页面。

---

## 二、三项核心架构裁决

1. **单一写入路径 `ThemePackageManager.updateActiveRuntimeOverride(context, isNight, mutator)`**：
   - 统一负责写入运行时偏好键（`ThemeRuntimeKeys`）并同步更新当前激活的本地自定义主题包（`theme.json` 与 `ThemeConfig.configList`）；
   - 当激活主题为内置默认主题（`source == BUILTIN`，其 `Config.uiCornerScale == null`）时，显式跳过文件写回、仅保留运行时 pref（`applyConfig` 的 `?.let` 不会覆盖 pref）。
2. **主屏直调卡的「当前生效模式唯一作用域」与「拖动期零 SP 写入」**：
   - 直调卡永远只作用于当前生效模式 (`AppConfig.isNightTheme`)，跨模式编辑走主题包格子的 `ThemePackageEditDialog`；
   - 拖动期 (`onValueChange`) 仅更新本地 Compose State 驱动预览卡，松手 (`onValueChangeFinished`) 才写 pref 与主题包，避免每帧触发 `ThemeUiSignatureCache` 重算与全页重组。
3. **子页面日/夜初值修复与跨模式提示**：
   - 4 个子页面初值直接用 `mutableStateOf(AppConfig.isNightTheme)` 1 行修复，与跨页 Intent 传参解耦。

---

## 三、细粒度里程碑规划（R3-M1a → R3-M1b → R3-M1c → R3-M2 → R3-M3）

- **R3-M1a（控件层试点）**：落地 `LegadoSegmentedControl` + 纯 Compose 更多按钮，先试点 `MySettingsScreen` + `ThemeManageActivity` + `AppPackageManageComponents`，补 `LegadoSegmentedControlTest`。
- **R3-M1b（顶栏层 UI 配置域收口）**：落地 `AppComposeTopBar`，先试点 `ConfigActivity` + `AppearanceKitActivity` 验证几何，再铺开至 `ui/config` 其余管理页。
- **R3-M1c（接线清理）**：收口其余分段条与卡片按钮、修 `28.dp` 双重缩进、修 2 文件 4 处野生调色板、补 `glassPageBackgroundColor`、删除死文件 `ComposePreferenceScreen.kt`。
- **R3-M2（编辑弹窗 Compose 化）**：落地 27 字段 `ThemeEditDraft`（含 `onSaveInstanceState` 与 `ThemeEditDraftTest`）+ `ThemePackageEditDialog` + `NavigationBarEditDialog`，清理 `PackageManageUi.kt` 与 3 个死 XML。
- **R3-M3（外观中心工作台与日/夜闭环）**：先单独修复 4 处日夜初值 `false` → 外观中心移除快照条、内置 4 档亮暗模式切换器与主题速切条 → 落地 `ThemePackageManager.updateActiveRuntimeOverride` 与主屏材质直调卡。
