# Legado-H 第二轮 UI 底层架构重构方案（方案 B：单一色彩数据源 + 上游 L1/L2 收口修复）

> **定稿状态**：已确认采用 **方案 B**，拆分为 **R2-M1 → R2-M2 → R2-M3** 三个独立可验收、可回滚的里程碑。

---

## 一、核心架构原则

1. **去套娃化（外观快照胶囊）**：100% 复用第一轮 `AppearanceKitManager`（1364 行）与 `kits.json` / `appearance_kit.json (kitVersion = 2)` / `AppearanceKitDiff` / `UiPackageBackup` 数据层，不改不删存储契约；仅在 `AppearanceKitActivity` UI 层取消“应用主题 vs 主题包”的双重主题心智与常驻脏横幅，改为预览卡下方一行轻量的**「外观快照」横向胶囊条**。
2. **包职责纯化与单一色彩数据源（方案 B）**：
   - 所有布局与交互开关（底栏 `layoutMode` / `hideSearchInFloatingStyle` / `sidebarGravity`；顶栏 `style` / `floatingChrome` / `showSearchInDefaultStyle` / `expandFiltersByDefault` / `hideFilterToggleWhenExpanded`）100% 剥离至 **「界面布局与系统栏 (`UiLayoutConfigFragment`)」**；
   - 取消顶栏独立圆角系数 `cornerScale`，统一跟随全局日/夜 `uiCornerScale`；
   - **所有颜色与材质 100% 归口到「主题」**，移除 `TopBarEditDialog` 中的重复取色器（`backgroundColor`、`tagBarColor`、`tagSelectedColor`），顶栏包瘦身为纯粹的**「顶栏壁纸与遮罩透明度」**，底栏包瘦身为纯粹的**「导航图标与底栏/侧边栏壁纸」**；旧版 `.zip` 导入时自动过滤越界布局与局部颜色字段，仅提取视觉素材。
3. **核心种子色 + 算法派生 L0–L3 层级与柔和描边**：
   - 未手动覆写 `themeCardColor` / `themeMutedColor` / `themeSearchFieldBackgroundColor` / `themeTabBackgroundColor` 时，不再回落静态 `R.color.background_card` / `R.color.background_menu`，而是基于当前画布 `backgroundColor (L0)` 通过同色相明度阶梯算法自动派生 `L1_Card` 与 `L2_Slot`；
   - `UiCorner.borderLineColor` 未显式设置 `panelBorderColor` / `glassStrokeColor` 时，不再回落 100% 不透明 `accentColor`，而是自动派生 8.5%–12.5% 低透明微染同系柔和描边（墨水屏旁路为 `#FF000000` 100% 纯黑）；
   - 修复 `LegadoMiuixPalette` 在管理页（`AppSettingComponents.kt`）与弹窗（`AppComposeDialogs.kt` / `LegadoMiuixComponents.kt`）中的 L1/L2 层级错位与 `AppDialogFrame` 双重描边。
4. **同心圆角退让公式 + View/Compose 同源**：
   - 保持 `R.dimen.ui_panel_radius = 10dp` 基准不变、日夜 `ThemeRuntimeKeys.uiCornerScale()` (`coerceIn(0f, 3f)`) 不变；
   - 修复 `UiCorner.actionRadius(context)` 为：
     $$R_{\text{action}}(s) = \begin{cases} 0\text{dp}, & s = 0 \\ \max(10\text{dp} \times s - 3\text{dp},\; 4\text{dp} \times s), & s > 0 \end{cases}$$
   - View 侧与 Compose 侧均通过 `UiCorner` 共用同一公式。

---

## 二、真实代码符号对账表

| 职责模块 | 真实文件路径 | 关键符号 |
|---|---|---|
| 上游色彩与签名缓存 | `app/src/main/java/io/legado/app/lib/theme/ThemeUiPalette.kt` | `ThemeUiPalette`、`themeCardColorOrDefault`、`themeMutedColorOrDefault`、`themeSearchFieldBackgroundColorOrDefault`、`themeTabBackgroundColorOrDefault`、`themeDividerColorOrDefault` |
| 上游圆角与描边 | `app/src/main/java/io/legado/app/lib/theme/UiCorner.kt` | `scale()`、`panelRadius()`、`actionRadius()`、`borderLineColor()` |
| Token 编译器 | `app/src/main/java/io/legado/app/lib/theme/ThemeTokenCompiler.kt` | `compileRaw`、`compileFromContext` |
| 管理页调色板 | `app/src/main/java/io/legado/app/ui/widget/compose/AppSettingComponents.kt` | `AppSettingPalette`、`rememberAppSettingPalette`、`rememberAppManagementPalette` |
| 弹窗调色板与框架 | `app/src/main/java/io/legado/app/ui/widget/compose/AppComposeDialogs.kt` | `AppDialogStyle`、`rememberAppDialogStyle`、`AppDialogFrame` |
| Miuix 组件库 | `app/src/main/java/io/legado/app/ui/widget/compose/LegadoMiuixComponents.kt` | `LegadoMiuixPalette`、`toMiuixPalette`、`LegadoMiuixSelectField`、`LegadoMiuixSection` |
| 外观快照数据层 | `app/src/main/java/io/legado/app/help/config/AppearanceKitManager.kt` | `AppearanceKitManager`、`KitBinding` (`kitVersion = 2`) |
| 顶/底栏包与布局 | `TopBarConfig.kt` / `TopBarEditDialog.kt` / `NavigationBarIconConfig.kt` / `NavigationBarManageActivity.kt` / `UiLayoutConfigFragment.kt` | — |
| 外观中心与主题编辑 | `AppearanceKitActivity.kt` / `ThemeManageActivity.kt` | — |

---

## 三、三大特殊分支处理契约

1. **壁纸 + 玻璃分支（含 `SDK < 31` 回退）**：
   - `AppManagementCard` / `AppDialogFrame` 在玻璃态下外壳由 `GlassPanel` 接管（`SDK < 31` 或无采样源时自动退回 `fallback` 实色）；
   - 当宿主处于玻璃态时，内部 `L2_Slot`（输入框、次级按钮、开关未选中轨、选择框）若叠在透明玻璃上，需将 alpha 收敛至 `translucentRowSurfaceAlpha(layoutAlpha)`（`[0.34f, 0.60f]`），既有独立槽感又不遮死玻璃。
2. **墨水屏分支（`AppConfig.isEInkMode == true`）**：
   - 整条明度混色与半透明算法 100% 旁路：`L0 = #FFFFFF`、`L1 = #FFFFFF`、`L2 = #F2F2F2`、`borderLineColor = #FF000000`（1dp 纯黑实线）、`layoutAlpha = 1f`、`glassMode = SOLID`。
3. **View 体系分支**：
   - View 侧通过 `ThemeUiPalette` 与 `UiCorner` 与 Compose 共享同一上游，同步获得 L1/L2 派生色、柔和描边与 `10dp/7dp` 同心圆角。

---

## 四、里程碑规划

- **R2-M1（上游 L1/L2 派生、柔和描边、同心圆角与 Miuix 错位修复）**：
  - `ThemeUiPalette.kt`、`UiCorner.kt`、`ThemeTokenCompiler.kt`、`AppSettingComponents.kt`、`AppComposeDialogs.kt`、`LegadoMiuixComponents.kt`
  - 单测：`SurfaceHierarchyDerivationTest`（锁定日/夜/有色底/墨水屏/同心圆角公式 $s \in [0, 3]$ / Miuix L1-L2 语义）
- **R2-M2（顶/底栏包越界布局与重复颜色剥离，归位 `UiLayoutConfigFragment`）**：
  - `NavigationBarManageActivity.kt`、`NavigationBarIconConfig.kt`、`TopBarEditDialog.kt`、`TopBarConfig.kt`、`UiLayoutConfigFragment.kt`、`SettingsSearchIndex.kt`
- **R2-M3（外观中心「快照胶囊条」与主题编辑器「核心种子色 + 高级覆写折叠」）**：
  - `AppearanceKitActivity.kt`、`ThemeManageActivity.kt`
