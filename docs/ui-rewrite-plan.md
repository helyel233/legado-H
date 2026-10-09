# legado-H UI 重写方案

- 状态：提案（待评审）
- 日期：2026-10-09
- 基线版本：H.1.7.3
- 适用范围：`app` 模块 UI 层，以及新增的 `modules/theme`、`modules/uikit` 两个模块
- 相关文档：`docs/release-process.md`（发版规则）、`docs/visual-resource-packages.md`（资源包格式）

---

## 1. 背景与目标

### 1.1 问题陈述

用户反馈「接口很乱、各页面风格不统一」。代码排查后的结论是：**这不是某个页面画得不好看，而是缺少统一的设计地基**。项目目前是「四套 UI 栈并行 + 零 Design System 层」的状态，任何一次局部美化都会被下一次局部美化推翻。

### 1.2 目标

| 编号 | 目标 | 可验证结果 |
|---|---|---|
| G1 | 建立可复用的设计地基 | Token + 组件库 + 状态契约，新页面默认统一 |
| G2 | 视觉素材集中管理 | 背景图、配色、字体只有一处入口 |
| G3 | 界面风格集中管理 | 圆角、按钮、间距、字阶只有一套定义 |
| G4 | 阅读配置集中管理 | 阅读排版、朗读、背景/配色各自成体系且可整包导入导出 |
| G5 | 信息架构收敛 | 一级入口从 6 Tab + 侧栏 + 100 个 Activity 收敛为 3 Tab + 二级列表 |
| G6 | 降低维护成本 | 巨型 Activity 拆分，UI 与业务逻辑分离 |

### 1.3 非目标（明确不做）

- **不重写阅读排版内核**：`ui/book/read/page/`、`ui/book/read/epub/` 为自绘排版，性能敏感，冻结不动。
- **不重写书源解析 / 网络 / 数据库**：本次不涉及数据层业务逻辑。
- **不做视觉大改版**：本方案是「统一」而非「换皮」，绝大多数页面的视觉变化应当很小，用户感知是「变整齐了」而不是「变样了」。
- **不一次性全量替换**：按域分阶段发版，每阶段独立可发布。

---

## 2. 现状诊断

### 2.1 四栈并行

| 栈 | 规模 | 代表文件 |
|---|---|---|
| ① XML / ViewBinding 旧栈 | 235 个 layout、280 个 drawable（含 95 个手绘 `bg_*`） | `res/layout/**` |
| ② Compose 新栈 | 39 个 `*Screen.kt`，BOM 2025.10 + material3 1.4 | `ui/**/compose/**` |
| ③ AndroidX-Preference 遗留栈 | 16 个 `pref_config_*.xml` | `res/xml/pref_config_*.xml`、`lib/prefs/**` |
| ④ 阅读页自绘栈 | `ReadBookActivity` 265KB、`PageView` 118KB | `ui/book/read/**` |

另有 **三套视觉语言并行**：Material3、`LegadoMiuixComponents.kt`(35KB)、液态玻璃 `StableLiquidGlassView`。
（`MaterialAlertDialogBuilder` 全库仅 1 处使用，Material 规范基本未落地。）

### 2.2 病灶清单

| # | 病灶 | 证据 |
|---|---|---|
| D1 | 主题持久化 4 层并存 | `PreferKey` SP + `app_themes.xml`(ThemeStore) + `themeConfig.json` + `externalFiles/themePackages/` |
| D2 | 背景图 3 个槽 + 阅读页独立体系 | `backgroundImgPath` / `bookInfoBackgroundImgPath` / `panelBackgroundImgPath`；阅读页另有 `bgType`(0颜色/1assets/2其它) |
| D3 | 字体 3 套入口 | `help/AppFont.kt`(阅读) + `UiTypography.uiTypeface()`(界面) + `titleTypeface()`(标题) |
| D4 | 圆角无体系 | `dimens.xml` 8 个 radius dimen（`ui_panel_radius=10`、`ui_action_radius=9` 等）；25 个 drawable 硬编码圆角；90+ 个 kt 文件直接写 `RoundedCornerShape` |
| D5 | 按钮无统一控件 | 真 `<Button>` 仅 5 个布局，`MaterialButton` 0 个，Compose `Button` 4 处，**142 个布局用 TextView + bg drawable 当按钮**；高度 36/38/42/48dp 混用，字号 13/14/16/20sp 混用 |
| D6 | 间距未成网格 | `dimens.xml` 约 100 项，混入 1/3/9/11/13/27/34/42dp |
| D7 | 字阶仅 3 档 | `font_size_normal=14` / `middle=16` / `large=18`；Compose 未自定义 `Typography` |
| D8 | 弹窗 5 套写法 | `AlertDialog`+`applyTint`、`BaseDialogFragment`/`BaseBottomSheetDialogFragment`、`ui/widget/dialog/*`、`AppComposeDialogs.kt`(92KB)、PopupWindow 自绘浮层 |
| D9 | 提示 3 套 | `ToastUtils.toastOnUi`(自绘) / `toastOnUiLegacy`(系统) / `Snackbars.kt`(12 个重载且未做主题适配) |
| D10 | 列表状态无契约 | 43 个 layout 各自内嵌 empty/loading/error |
| D11 | 阅读配置 69 字段大杂烩 | `ReadBookConfig.Config`（L970-1039），颜色、排版、页眉页脚、tip 模板全塞一起 |
| D12 | 朗读配置裸散 | 40+ 个 `PreferKey` 直接读写，与排版配置不同存储、不进导出包 |
| D13 | 配置变更用数字位掩码 | `EventBus.UP_CONFIG` + `1/2/5/6/8/9/11`，被 9 个类消费 |
| D14 | 巨型 Activity | `MainActivity` 102KB、`BookInfoActivity` 142KB、`ThemeManageActivity` 110KB、`ReadAloudPlayerPanel` 197KB |
| D15 | 接口命名不统一 | `ReadBookActivity` 单类实现 16 个回调接口，后缀 `CallBack`/`Listener`/`Callback` 三种混用 |
| D16 | 功能双胞胎 | `BookInfoActivity`(142KB) 与 `BookInfoComposeActivity`；书架 3 种实现；发现页 XML/Compose 两套 |
| D17 | 无路由表 | 107 个 `<activity>`（7 个为桌面图标别名），全部显式 `Intent` 跳转，无 `NavController` |
| D18 | 硬编码遍地 | `ui/` 下 224+ 处 `.dp`/`.dpToPx`；`AppUiTokens.kt` 仅 24 行 |
| D19 | 视觉语言无规范：硬编码颜色 + 位图混用 + 图标无基准 | kt `Color(0xFF`/`Color.parseColor` 约 39 处/21 文件；xml `textColor="#"`/`backgroundTint="#"` 27 处/6 文件；自绘 vector `ic_*.xml` 158 个但无线宽/圆角基准，另有 7 个位图（`icon_read_book.png`、`ic_close_with__shadow.png`、`image_loading_error.png` 等）混入 |

### 2.3 关键文件地图

```
app/src/main/java/io/legado/app/
├ base/                      基类：BaseActivity / VMBaseActivity / BaseFragment / Base*DialogFragment
├ help/config/
│  ├ ThemeConfig.kt          53KB，Config 31 字段，applyDayNight / applyConfig
│  ├ ThemePackageManager.kt  67KB，zip 导入导出、云同步、内置 day/night
│  ├ ReadBookConfig.kt       53.6KB，Config 69 字段，Gson → 4 个 JSON
│  └ AppConfig.kt            themeMode / isNightTheme / isEInkMode / uiCornerScale …
├ lib/theme/
│  ├ ThemeStore.kt           → app_themes.xml
│  ├ ThemeRuntimeKeys.kt     日夜拆键 + 旧键迁移
│  ├ ThemeUiPalette.kt / UiCorner.kt / ComposeUiCorner.kt / UiTypography.kt
│  └ LegadoComposeTheme.kt   仅 copy(fontFamily)，未自定义 Typography
├ ui/main/MainActivity.kt    102KB，ViewPager + BottomNavigationView + 自绘侧栏
├ ui/book/read/
│  ├ ReadBookActivity.kt     265KB
│  ├ page/PageView.kt        118KB（冻结）
│  ├ ReadMenu.kt             35KB，仅渲染，通过 interface CallBack 回调
│  └ config/                 17 个 Dialog + 9 个 Activity
└ ui/widget/                 97 个自定义控件
```

---

## 3. 目标架构总览

### 3.1 分层与依赖（单向，禁止反向引用）

```
        ┌──────────────────────────────────────────┐
        │  reader（阅读页包）                        │  业务配置 + 阅读 UI
        └───────────▲──────────────▲───────────────┘
                    │              │
        ┌───────────┴──────┐  ┌────┴─────────────────┐
        │ uikit（界面包）   │  │ theme（主题包）        │  组件/规范 │ 素材/数据
        └───────────▲──────┘  └────▲─────────────────┘
                    │              │
                    └──────┬───────┘
                     uikit 依赖 theme 的
                     ColorScheme / FontScheme
```

| 包 | 回答的问题 | 性质 | 谁可以依赖它 |
|---|---|---|---|
| `theme` 主题包 | 用什么素材（背景图/配色/字体） | 纯数据，不含 UI | uikit、reader、app |
| `uikit` 界面包 | 怎么画（圆角/按钮/间距/字阶/状态） | 组件 + Token | reader、app |
| `reader` 阅读页包 | 读书时怎么配（排版/朗读/分页） | 配置 + 业务 UI | app |

### 3.2 三个包的职责边界（关键决策）

**决策 1：阅读页的颜色/背景/字体「引用」主题包，不自己存一份。**

现状 `ReadBookConfig.Config` 自己存了 日/夜/E-Ink 三套颜色和背景（69 字段中一大半），导致**换 App 主题时阅读页纹丝不动**——这是「风格不统一」最直观的来源。改为引用后：

- 主题包提供 `ColorScheme`，阅读页取子集（正文色 / 背景 / 强调色 / 阅读字体）
- 保留 **`跟随主题` / `独立自定义`** 开关，不破坏老用户「阅读页要单独配色」的习惯
- 阅读包只保留排版语义字段（字号、字距、行距、段距、缩进、边距、tip 槽位），字段数从 69 降至约 25
- 日/夜/E-Ink 三套值由主题包统一切换，阅读页不再自己判 `AppConfig.isNightTheme`

---

## 4. 信息架构重排（IA）

### 4.1 现状

6 个主 Tab（首页 / 书架 / 发现 / RSS / 阅读记录 / 我的）+ 自绘侧栏 + 约 100 个 Activity 入口。

问题：`首页`与`发现`职责重叠；`RSS`、`阅读记录`低频却占一级入口；侧栏是隐藏入口，用户发现不了。

### 4.2 目标

```
┌ 书架 ────────────────────────────────────────────
│   书架本体 / 分组 / 本地导入  →  书籍详情  →  阅读
├ 发现 ────────────────────────────────────────────
│   书源发现 / 全网搜索 / 排行精选
└ 我的 ────────────────────────────────────────────
    ★ 纯设置配置中心，不放使用入口（2026-10-09 用户反馈修正）
    我的阅读   阅读统计 · 书签与笔记
    内容源     书源管理 · RSS 源管理 · 订阅源管理
    工具       替换净化 · 自动任务 · 一键导入
    外观       App UI 配置 · 界面设置 · 阅读设置   ← 三个包的用户入口
    AI         AI 设置（与外观拆分为独立分组）
    系统       备份恢复 · 缓存管理 · 关于
───────────────────────────────────────────────────
侧栏常驻入口：RSS 订阅源 · 首页聚合（Tab 收纳后仍可直达，独立宿主页）
全局入口：搜索（书架内 / 书源 / 全网 三段结果合并，单一入口）
独立页面：阅读页（全屏，不进 Tab）
```

「外观」分组让三个包在 UI 上有明确落点：主题包 → 安装包/切换配色；界面包 → 圆角/字号/密度；阅读包 → 阅读排版与朗读。

### 4.3 导航技术

- 新建 `ui/navigation/AppRoute.kt`：**sealed 路由表**，先把 100 个入口集中登记（底层可暂仍是 Intent）
- 主框架改造为 `MainScaffold + NavHost + 3 Tab`，删除自绘侧栏
- 阅读页保持独立 Activity 全屏，不进 NavHost

---

## 5. 主题包 `modules/theme`

### 5.1 职责

管理**可被用户替换与分享的视觉素材**：背景图、配色方案、字体，以及**资源类型**（封面图集、SVG 气泡、EPUB 模板，P2-c 纳入包体 `resources`）。纯数据层，不含任何 UI。

### 5.2 目录结构

```
modules/theme/src/main/java/io/legado/app/theme/
├ model/
│  ├ ThemePackage.kt        包体：package.json + assets/，formatVersion
│  ├ ColorScheme.kt         语义色（primary/surface/background/onSurface/outline/…）
│  ├ FontScheme.kt          字体角色：reading / ui / title
│  └ BackgroundScheme.kt    背景场景：main / bookInfo / panel / reader
├ store/
│  └ ThemeRepository.kt     ★ 4 层持久化收口为单一入口
├ asset/
│  ├ BackgroundStore.kt     3 个背景槽 + 阅读 bgType 统一为 Scene 枚举
│  └ FontStore.kt           3 套字体入口统一为 Role 枚举
├ apply/
│  └ ThemeApplier.kt        日 / 夜 / E-Ink / 动态取色(Android 12+) 应用策略
└ io/
   └ ThemePackageIO.kt      import/export/云同步（迁自 ThemePackageManager）
```

### 5.3 数据模型要点

```kotlin
// 背景：场景枚举取代 3 个独立字段 + 阅读 bgType
enum class BackgroundScene { MAIN, BOOK_INFO, PANEL, READER }
// 每个场景：颜色值 / assets 内置图 / 外部图片 三选一（沿用现有 bgType 0/1/2 语义）

// 字体：角色枚举取代 3 套入口
enum class FontRole { READING, UI, TITLE }

// 配色：语义色，日夜/E-Ink 各一份，可由主题包整体替换
data class ColorScheme(
    val primary: Color, val onPrimary: Color,
    val surface: Color, val onSurface: Color, val surfaceVariant: Color,
    val background: Color, val onBackground: Color,
    val outline: Color, val readerText: Color, val readerBackground: Color, …
)
```

### 5.4 迁移清单

| 来源 | 去向 | 说明 |
|---|---|---|
| `PreferKey` 219-273 / 437-459 / 428-431 | `ThemeRepository` | 保留旧 key 读取路径一个版本 |
| `lib/theme/ThemeStore.kt` + `app_themes.xml` | `ThemeRepository` | |
| `ThemeConfig.kt` 的 `themeConfig.json` | `ThemeRepository` | Config 31 字段按语义拆入 `ColorScheme`/`BackgroundScheme`/`FontScheme` |
| `externalFiles/themePackages/`（`ThemePackageManager`） | `ThemePackageIO` | 包格式与 `formatVersion` 保持不变 |
| `BaseActivity.upBackgroundImage()` + `bookInfoBackgroundImgPath` + `panelBackgroundImgPath` + `ReadBookConfig.bgType` | `BackgroundStore[Scene]` | 4 处合一 |
| `help/AppFont.kt` + `UiTypography.uiTypeface()` + `titleTypeface()` | `FontStore[Role]` | 3 处合一 |
| `ui/config/ThemeManageActivity.kt`(110KB) | 业务 UI 留在 app，拆为「列表页 + 编辑面板」 | 包内只留数据 |

### 5.5 兼容硬要求

0. **本节所有要求适用于 P0 起每一个发布版本**，验收基准 = 从 **H.1.7.3 直接覆盖升级**（不只是相邻版本升级），见 §10 第 0 条。
1. **旧主题包必须能继续导入**：`ThemePackageIO` 保留 `formatVersion` 旧分支与 `importRedGzip` 兼容路径。
2. **旧配置不能丢**：迁移时逐个 key 读取旧值 → 写入新结构 → 失败时 `backupCorruptFile()`（已有机制）并回退默认值。
3. **云同步不中断**：`syncThemePackages` / `themePackageSyncTasks` 数据结构不变。

---

## 6. 界面包 `modules/uikit`

### 6.1 Design Token

**圆角（4 档 + full）**

| Token | 值 | 用途 | 映射旧值 |
|---|---|---|---|
| `radius.xs` | 4dp | 标签、chip、封面角标 | `book_collection_cover_corner_radius`(4) |
| `radius.sm` | 8dp | 小按钮、输入框、列表内元素 | `ui_action_radius`(9→8)、`bookshelf_tag_item_radius`(9→8) |
| `radius.md` | 12dp | 卡片、面板、弹窗 | `ui_panel_radius`(10→12)、`bookshelf_tag_bar_radius`(10→12) |
| `radius.lg` | 24dp | 底部栏、BottomSheet、大面板 | `main_bottom_bar_corner_radius`(24)、`manga_control_bar_radius`(24) |
| `radius.full` | 圆形 | 头像、快速滚动气泡 | `fastscroll_bubble_radius`(44) |

> 现有 `uiCornerScale` 缩放系数继续生效，作为 `radius.*` 的整体乘数。

**间距（4dp 网格）**

`space.2=2` / `space.4=4` / `space.8=8` / `space.12=12` / `space.16=16` / `space.24=24` / `space.32=32`

约束：页面左右边距 16dp；卡片内边距 12/16dp；列表项水平 16dp、垂直 12dp；书架栅格间距 8dp。
现有 100 项 dimen 中的 1/3/9/11/13/27/34dp 逐步归到最近档位。

**字阶（6 档）**

| Token | 值 | 行高 | 用途 | 映射旧值 |
|---|---|---|---|---|
| `caption` | 12sp | 16 | 辅助说明、角标 | |
| `body` | 14sp | 20 | 正文、列表副标题 | `font_size_normal`(14) |
| `bodyLarge` | 16sp | 22 | 列表主标题、按钮文字 | `font_size_middle`(16) |
| `title` | 18sp | 24 | 分区标题 | `font_size_large`(18) |
| `headline` | 20sp | 28 | 页面标题 | |
| `display` | 24sp | 32 | 空状态标题、统计数值 | |

Compose 侧补齐 `Typography`（目前 `LegadoComposeTheme` 只 `copy(fontFamily=)`，沿用 M3 默认字阶）。

**尺寸**

| Token | 值 |
|---|---|
| 按钮高 | 48dp（标准）/ 40dp（紧凑）/ 32dp（mini） |
| 最小触摸目标 | 48dp |
| 列表项高 | 56dp（单行）/ 72dp（双行） |
| 顶栏 / 底栏高 | 56dp |
| 图标 | 24dp（内容）/ 20dp（密集）/ 32dp（大） |
| 分割线 | 1dp hairline |

**动效**

`motion.fast=120ms`（状态切换）/ `motion.normal=200ms`（页内转场）/ `motion.slow=320ms`（BottomSheet、页面级）；缓动统一 `FastOutSlowIn`。

**建议**：夜间与 E-Ink 模式下，卡片层级优先用 1dp 描边而非阴影（阴影在纯黑白屏无效）。

### 6.2 组件清单

| 组件 | 规格 | 取代现状 |
|---|---|---|
| `AppButton` | 5 型（filled/tonal/outlined/text/icon）× 3 尺寸，48dp 标准高 | 142 个 TextView+drawable、5 个 `<Button>`、`shape_fillet_btn` |
| `AppIconButton` | 48dp 触控区 + 24dp 图标 | `bg_read_menu_icon_button` 等 7 个 |
| `AppCard` | `radius.md`，描边/阴影可选 | 25 个 `bg_homepage_card_12/16/20` |
| `AppListItem` | 56dp，leading / title / subtitle / trailing 四槽 | 各 Adapter 自建 item 布局 |
| `AppTopBar` | 56dp，标题 + 返回 + actions | `TitleBar.kt`、`MainTopBarView.kt` |
| `AppSearchBar` | `radius.sm`，24dp 图标 | `SearchView.kt`、`bg_searchview.xml` |
| `AppBottomBar` | 3 Tab，56dp | `BottomNavigationView` + 自绘指示器 |
| `AppSwitchItem` / `AppSliderItem` / `AppChoiceItem` / `AppTextItem` | 设置项 4 型，56dp | 16 个 `pref_config_*.xml`、Compose Setting Specs |
| `AppChip` / `AppTag` | `radius.xs` | `LabelsBar`、`RoundedTagBarView` |
| `AppEmptyState` / `AppLoadingState` / `AppErrorState` | 统一插画 + 文案 + 重试按钮 | 43 个内嵌空态布局 |
| `AppDialog` / `AppBottomSheet` / `AppConfirm` | 统一圆角、按钮排布、夜间适配 | 5 套弹窗实现 |
| `AppToast` / `AppSnackbar` | 单入口，带主题 | `ToastUtils` 自绘 + 系统 Toast + `Snackbars` 12 重载 |
| `AppDivider` / `AppSectionHeader` | | `VerticalDivider` |
| `AppCover` | 圆角统一 `radius.xs` | `CircleImageView` / `FilletImageView` |

### 6.3 列表状态契约

```kotlin
sealed interface AppListState<out T> {
    data object Idle
    data object Loading
    data class Content<T>(val data: T, val hasMore: Boolean = false)
    data class Empty(val title: String, val desc: String? = null, val action: (() -> Unit)? = null)
    data class Error(val throwable: Throwable, val retry: () -> Unit)
}
```

XML 侧提供 `StateLayout` 包装（替换 43 个内嵌空态），Compose 侧提供 `AppListState(content:)` 可组合函数。

### 6.4 面向旧栈的门面

`uikit` 必须能被 XML 页面使用，否则过渡期无法推进。提供：

- `AppToast.show(...)`：取代 `toastOnUi` / `toastOnUiLegacy` / `snackbar(...)` 全部调用
- `AppDialogHost`：统一 `BaseDialogFragment` 的圆角、按钮排布、夜间；逐步替换 `applyTint()` 等
- `StateLayout`：XML 版状态容器

### 6.5 视觉语言规范

**基线**：Material3 为唯一组件基线。本节定义所有页面（含新旧栈、含阅读页外壳）共同遵守的视觉语言；它是 token（6.1）与组件（6.2）之上的第三层约束——**任何视觉属性都必须能回答「值从哪来」**。

**设计原则（4 条）**

1. 内容优先：一切控件服务于阅读内容，装饰性元素克制。
2. 参数化：任何颜色、圆角、间距、字号、时长必须来自 token，禁止就地取值。
3. 明暗同构：同一组件在 日/夜/E-Ink 三种模式下用同一结构、不同取值；禁止为某个模式单独改结构。
4. 触控优先：所有可点击元素 ≥ 48dp 触控目标。

#### 6.5.1 色彩系统

**现状**：`colors.xml` 约 80 项 + `colors_material_design.xml`（`md_*` 调色板数百项）+ `values-night` 覆盖；已有扩展色板 `ThemeUiPalette`（cardColor/mutedColor/searchFieldBackgroundColor/tabBackgroundColor/shelfColor/dividerColor）与 `MaterialValueHelper`（primaryTextColor/titleTextColor/secondaryTextColor/disabled 系列）；硬编码颜色 66 处（kt 39 + xml 27）。

**目标语义色板（唯一）**：

| 语义色 | 用途 | 映射现状 |
|---|---|---|
| `primary` / `onPrimary` | 主操作、强调 | `primaryColor` |
| `secondary` / `onSecondary` | 次强调 | `accentColor` |
| `surface` / `onSurface` / `surfaceVariant` | 卡片、面板 | `cardColor` |
| `background` / `onBackground` | 页面底色 | `backgroundColor` |
| `outline` | 描边、分割线 | `dividerColor` |
| `muted` | 次要文字、占位符 | `mutedColor` / `secondaryTextColor` |
| `error` / `success` / `warning` | 状态色 | 固定值 |
| `readerText` / `readerBackground` / `readerTip` | 阅读正文（P2 打通后） | 主题包 `ColorScheme` 子集 |

**规则**：

1. 全库取色只允许两种方式：Compose 用 `MaterialTheme.colorScheme.*`；View/XML 用 `?attr/*` 或 `MaterialValueHelper` 系列方法。
2. 禁止 `Color(0xFF...)`、`Color.parseColor("#...")`、xml `#RRGGBB` 字面值（白名单：测试代码、调试日志、启动图标）。
3. `md_*` 调色板仅作为主题包配色生成源，布局与代码不得直接引用。
4. 透明度统一档位：`alpha.disabled=0.38` / `alpha.muted=0.6` / `alpha.overlay=0.72`；用户设置 `uiLayoutAlpha`/`dialogAlpha` 保留，但其输出必须映射到档位而非自由值。
5. 现有 66 处硬编码颜色纳入 P0b 清理：`ReadAloudPlayerPanel.kt`(8 处)、`widget_read_rank.xml`(11 处)、`video_layout_controller_full.xml`(6 处) 为重点。

#### 6.5.2 图标系统

**现状**：全自绘 vector `ic_*.xml` 158 个，未引入 Material Icons/Symbols 依赖；7 个位图混入（`ic_close_with__shadow.png`、`icon_read_book.png`、`image_cover_default.jpg`、`image_legado.png`、`image_loading_error.png`、`image_rss.jpg`、`image_rss_article.jpg`）。

**规范**：

1. **继续自绘 vector，不引入 icons 库**（避免包体膨胀）。第一步先审计现有 158 个图标，确立「基准图标集」（统一线宽 2dp、24×24dp 视口、统一端点/拐角风格），新图标向基准看齐，逐步回改偏移者。
2. 图标着色只能 `?attr/*` 语义色或运行时 `tint`，禁止在 vector path 里写死颜色。
3. 图标尺寸只用 24/20/32 三档（见 6.1）。
4. 位图清理：`ic_close_with__shadow` → `AppIconButton` + vector；`icon_read_book` → vector 化；`image_loading_error` → 重绘为 vector 空态插画（配合 `AppErrorState`）；`image_cover_default` 保留但圆角/尺寸由 `AppCover` 统一处理；`image_legado` / `image_rss*` 评估 vector 化或保留为品牌资产。

#### 6.5.3 排印

1. 字体只走 `FontStore[Role]` 三个角色（READING / UI / TITLE），禁止任何代码直接读字体文件路径。
2. 字重只用 400 / 500 / 700 三档（常规 / 强调 / 标题），禁止 600 等中间档（多数中文字体无对应字重文件，会触发伪粗渲染）。
3. 阅读正文字号是用户自由设置项，**不受** 6 档字阶约束；但 App UI 内文字必须全部走字阶。
4. 数字场景（阅读进度、统计、电量、页码）统一 `assets/font/number.ttf`，由 `FontStore` 以 tabular 等宽变体提供。

#### 6.5.4 层级与形状

1. 日间模式：卡片用 `surface` 色 + 可选 1dp `outline` 描边；阴影仅用于浮层（菜单、弹窗、FAB）。
2. 夜间与 E-Ink：**禁用阴影表达层级**，一律描边或明度差；`Style.Shadow.*` 在 E-Ink 模式下自动置空。
3. elevation 只用 0 / 1 / 3 / 6 / 12 五档；投影色统一 `black 24%`（仅日间）。
4. 形状应用对照表：chip/标签 → `radius.xs`；按钮/输入框 → `radius.sm`；卡片/弹窗/面板 → `radius.md`；底部栏/BottomSheet → `radius.lg`；头像/圆形 → `radius.full`。

#### 6.5.5 动效

1. 曲线统一 `FastOutSlowIn`；时长只用 120/200/320 三档（见 6.1）。
2. 阅读页翻页动效是用户可配置项，**不受**全局动效约束。
3. 业务组件禁用弹跳类（spring overshoot）动效；E-Ink 模式下所有动效退化为即时切换（统一走 `AppConfig.isEInkMode` 检查，封装进 `uikit`，业务不得各自判断）。

#### 6.5.6 图片与空态

1. 封面/图片圆角由 `AppCover` 统一为 `radius.xs`，内置 占位/加载/错误 三态；禁止各页面自配 `FilletImageView` 参数。
2. 空态/加载/错误统一走 `AppListState`（6.3）：加载 = 48dp 环形进度 + 文案；错误 = vector 插画 + 文案 + 重试；空态 = 同结构插画。取消现 `view_error.xml` 中 `backgroundTint=@color/accent` 这类散写。
3. 插画风格：单色线条风，跟随 `onSurfaceVariant` 着色；**不引入多色位图插画**。

#### 6.5.7 皮肤机制（miuix / 液态玻璃收敛）

**现状**：`LegadoMiuixComponents.kt`(35KB) 与液态玻璃（`com.qmdeve.liquidglass:core:1.0.3`）共 17 处引用、11 个文件（`StableLiquidGlassView`、`ExploreGlassBackdrop`、`MainTopBarView`、`MainActivity`、`activity_main.xml` 等）。

**收敛方案**：`uikit` 新增 `skin/` 层，皮肤是唯一合法的第三方视觉扩展途径：

1. 定义 `SkinProvider` 接口：由基线实现 `Material3Skin`（默认）+ 可选 `MiuixSkin` / `GlassSkin`。
2. 业务代码只允许调用 `SkinProvider`，直接引用 miuix 组件或 `StableLiquidGlassView` 视为违规（lint 检查）。
3. 现有 17 处引用逐一改为经 `SkinProvider` 调用；关闭皮肤时回退 Material3 标准渲染。
4. 新皮肤只新增 `SkinProvider` 实现 + 主题包参数，不新增第三种组件写法。

**`uikit` 完整目录结构**（含本节新增的 `skin/`）：

```
modules/uikit/src/main/java/io/legado/app/uikit/
├ token/      AppRadius  AppSpacing  AppTypeScale  AppSize  AppMotion  AppAlpha
├ theme/      AppTheme   # 把 theme 包的 ColorScheme/FontScheme 映射成 M3 ColorScheme+Typography
├ components/ AppButton  AppIconButton  AppCard  AppListItem  AppTopBar  AppSearchBar
│             AppBottomBar  AppSwitchItem  AppSliderItem  AppChoiceItem  AppTextItem
│             AppChip  AppCover  AppDivider  AppSectionHeader
├ state/      AppListState<T>  AppEmptyState  AppLoadingState  AppErrorState  StateLayout(XML)
├ facade/     AppToast  AppDialogHost  AppSheet   # 给 XML 旧栈用的桥
└ skin/       SkinProvider  Material3Skin  MiuixSkin  GlassSkin
```

---

## 7. 阅读页包 `io.legado.app.reader`

### 7.1 目录结构

```
app/src/main/java/io/legado/app/reader/
├ config/
│  ├ ReadConfig.kt              拆分为子对象（见 7.2）
│  ├ ReadConfigRepository.kt    ★ 4 个 JSON + 散落 SP 收口
│  ├ ReadConfigEvent.kt         ★ sealed class 取代位掩码
│  └ BookReadStyleSession.kt    每书独立预设（迁自 ReadBookConfig）
├ ui/
│  ├ ReadStylePanel.kt          排版总入口（迁自 ReadStyleDialog）
│  ├ BgTextPanel.kt             背景/文字（改为引用主题包）
│  ├ PaddingPanel.kt            边距
│  ├ TipPanel.kt                页眉页脚 6 槽位
│  └ MorePanel.kt               更多设置聚合
└ aloud/
   └ ReadAloudConfig.kt         ★ 40+ PreferKey 对象化，纳入同存储与导入导出
```

### 7.2 配置模型拆分（69 → 约 25 字段）

| 子对象 | 字段 | 说明 |
|---|---|---|
| `Typography` | 字号、字距、行距、段距、段首缩进、字重、下划线 | 纯粹排版语义 |
| `Page` | 正文四向 padding、翻页动画 | |
| `HeaderFooter` | 页眉/页脚四向 padding、显示分割线、模式 | |
| `Tip` | 6 个槽位 + 模板引用、颜色 | 模板文件仍走 `EpubReaderTemplateStore` |
| `ThemeRef` | **引用主题包** `ColorScheme` + `FontRole.READING`，附 `followTheme: Boolean` | 替代原有三套颜色/背景字段 |

### 7.3 事件协议改造

```kotlin
// 现状：EventBus.UP_CONFIG + 位掩码 1/2/5/6/8/9/11，被 9 个类消费
sealed interface ReadConfigEvent {
    data object Relayout                    // 原 5
    data class BackgroundChanged(...)       // 原 1
    data class TipChanged(...)              // 原 2
    data object TemplateChanged             // 原 8
    …
}
```

过渡期新旧双写（同时 post 位掩码与 sealed 事件），一个版本后移除旧协议。

### 7.4 朗读配置对象化

`ReadAloudConfigDialog` 中 40+ 个 `PreferKey`（`ttsEngine`、`ttsSpeechRate`、`readAloudByPage`、`aiReadAloud*`、bgm/sfx 音量…）收为 `ReadAloudConfig` 对象：

- 与排版配置同存储、同导入导出（现状导出 zip **不含**朗读配置，属功能缺陷）
- 复用现有 `ReadAloudConfigChangeNotifier` 与 `READ_ALOUD_CONFIG_CHANGED` 事件

### 7.5 迁移清单

| 来源 | 去向 | 备注 |
|---|---|---|
| `ReadBookConfig.kt`(53.6KB) | `ReadConfig*` 拆分 | 保留 `repairAndReload` / `atomicWrite` / `backupCorruptFile` 机制 |
| `ui/book/read/config/` 17 个 Dialog | `reader/ui/*Panel` | 面板化，用 `uikit` 设置项组件 |
| `ReadMenu.kt` `interface CallBack` | 保留但收敛 | 265KB 的 `ReadBookActivity` 借机瘦身 |
| `ui/book/read/page/**`、`epub/**` | **不动** | 排版内核冻结 |
| 朗读 40+ PreferKey | `ReadAloudConfig` | |

---

## 8. 通用基础设施

### 8.1 设置 DSL 唯一化

将 `ComposeSettingFragment` 的 `SettingPageSpec` / `SectionSpec` / `SwitchSpec` / `ChoiceSpec` 提升为 `uikit` 公开 API，**成为唯一设置实现方式**：

- 删除 16 个 `pref_config_*.xml` 与 `lib/prefs/**`
- 阅读页 30+ 个配置 Dialog 也改用同一 DSL（否则阅读页仍是另一套观感）
- 同步做**设置项三级分级**（常用 / 高级 / 实验性）+ 设置内搜索——配置字段合计约 100 个，不分级仍会显得乱

### 8.2 路由表

`ui/navigation/AppRoute.kt`：sealed 路由表集中登记全部入口，为 P1 的 NavHost 换壳铺路。

### 8.3 巨型类拆分目标

| 文件 | 现状 | 目标 |
|---|---|---|
| `MainActivity` | 102KB | `MainScaffold` + 各域 Screen，≤ 30KB |
| `BookInfoActivity` | 142KB | ViewModel + 若干 Section 组件 |
| `ThemeManageActivity` | 110KB | 列表页 + 编辑面板（业务留在 app） |
| `ReadBookActivity` | 265KB | 借阅读包重构同步瘦身；`page/**` 不动 |

---

## 9. 迁移路线图

| 阶段 | 名称 | 主要内容 | 建议版本增量 |
|---|---|---|---|
| **P0a** | 主题包收口 | 建 `modules/theme`；`ThemeRepository` 收口 4 层持久化；`BackgroundStore[Scene]`、`FontStore[Role]` | +0.0.1（纯重构，视觉无变化） |
| **P0b** | 界面包地基 | 建 `modules/uikit`；Token 与视觉语言规范（6.5）落地；`SkinProvider` 收敛 miuix/液态玻璃；`AppButton` / `AppToast` / `AppListState` 三项优先 | +0.0.1 |
| **P1** | 主框架收敛 | IA 收敛为 3 Tab；建 `AppRoute`；RSS/首页独立宿主与入口（P1-a/b，已发 H.1.8.5/1.8.6）。**P1-c 修订（2026-10-09 用户确认）**：NavHost 换壳推迟到 P4 末尾（各域 Compose 化后一次性完成，避免 AndroidFragment 包旧 Fragment 的两次改造与滚动状态迁移风险）；自绘侧栏**保留**——它是 sidebar 布局预设的载体（floating/standard/sidebar 三态），删除属功能砍除而非重构 | 已发版 |
| **P2** | 主题 × 阅读打通 | 阅读配色/字体引用 `ColorScheme`；新增「跟随主题」开关；「应用主题」更名为 **App UI 配置**，整合管理 主题包/界面包/阅读页包 三包（需求 R2） | +0.1.1 |
| **P3** | 阅读页包重构 | `ReadConfig` 拆分、事件 sealed 化、朗读配置对象化、面板化 | +0.1.1 |
| **P4** | 分域迁移 | 发现 → 书架（三实现合一）→ 书籍详情（删双胞胎）→ 书源/RSS → 配置中心；**随域清理硬编码颜色、位图与离线图标**；配置中心按新 IA 重组（需求 R1/R3）：外观与 AI 拆分、「我的」仅设置条目、界面设置二级收敛（≤8 个一级条目） | 每域 +0.1.1，独立发版 |
| **P5** | 清场 | 删 Preference 遗留栈、95 个 `bg_*`、被替代旧控件；巨型类收尾 | +0.0.1 |

> 版本号按 `docs/release-process.md` 的三段式规则，在紧邻上一版上累加；上表为建议量级，实际以各阶段改动内容（是否有新功能）为准。
> **发版门槛**：每阶段发布前必须执行 §10 第 0 条的升级兼容验证（H.1.7.3 直接覆盖安装 + 主题设置逐项核对），不通过不发布。

**顺序不可颠倒**：先建地基（P0）再迁页面（P1/P4），否则每迁一个页面都要重复踩一次「没有 token、状态自己写」的坑。

---

## 10. 兼容与灰度策略

0. **升级兼容硬约束（全阶段强制，发版门槛）**：自本计划实施起，**每一个阶段发布的包都必须支持从 H.1.7.3 直接覆盖升级**。升级后以下主题设置必须**全部保留、立即生效**，不允许出现「需要重新设置」或「回退默认值」：
   - 主题包与当前主题（内置/自定义、当前激活项）
   - 配色：primaryColor、accentColor、cardColor、mutedColor 等全部自定义色
   - 背景图：主背景 / 书籍详情 / 面板 三槽及模糊、裁剪、边框参数
   - 字体：阅读字体、界面字体、标题字体（`FontStore` 三角色）
   - 形态：`uiCornerScale`、`uiLayoutAlpha`、`dialogAlpha`、`themeMode`（日/夜/E-Ink）
   - 阅读页：配色、背景、字体、排版（字号/字距/行距/段距/边距）、tip 槽位、每书独立预设
   - 朗读配置：TTS 引擎、语速、角色分组等全部设置
   
   落地要求：P0a 主题包收口、P3 阅读配置拆分等**所有持久化迁移**，必须内置「旧 key（H.1.7.3 时代的 PreferKey / `app_themes.xml` / `readConfig.json` 等）→ 新结构」的一次性迁移逻辑，迁移失败走 `backupCorruptFile()` 回退且不得静默丢弃。**验收方式：每阶段发版前，用 H.1.7.3 正式包直接覆盖安装新包，逐项核对本清单。**
1. **旧数据迁移**：所有持久化收口必须保留旧 key 读取路径至少一个版本；失败走 `backupCorruptFile()` 回退默认。
2. **旧资源包兼容**：主题包 `formatVersion` 只增不改，旧分支保留。
3. **事件协议双写**：`UP_CONFIG` 位掩码与 `ReadConfigEvent` 并存一个版本。
4. **逐域发版**：每完成一个业务域打一个 tag，出问题可回退单域。
5. **Deprecated 过渡**：被替代的旧实现先标 `@Deprecated` 保留一个版本，确认无 upstream 引用后再删。

---

## 10A. 追加需求登记（2026-10-09 用户反馈）

| 编号 | 需求 | 落位 |
|---|---|---|
| R1 | 「我的」仅保留设置配置类条目，使用入口（RSS/首页聚合等）走侧栏常驻入口；外观与 AI 拆成独立两块 | P1-b 修正（已做）+ P4 配置中心重组 |
| R2 | 「应用主题」最终版更名为「App UI 配置」，统一管理三包。**包定义修订（2026-10-09 用户澄清）**：① **主题包** = 配色 + 背景图 + 字体 + **资源类型**（封面图集、SVG 气泡、EPUB 模板纳入主题包管理）；② **界面包** = 底栏/顶栏/按钮/边框/侧边栏等界面元素**统一为「界面」不再单独区分**，做成可配置、可导入导出、可选用的包（外观套件/界面设置等散卡不再单独存在于枢纽页）；③ **阅读页包** = 阅读排版/模板/资源 | P2 拆解：P2-a 枢纽页（已完成）→ P2-b 界面包管理器（散配置收口 + 打包/导入/选用，外观套件演化为其载体）→ P2-c 主题包资源扩容（zip 包 `resources` 扩展 + formatVersion 递增 + 旧包兼容）→ P2-d 阅读配色跟随主题 |
| R3 | 「界面设置」更名为 **UI 其他设置**，定位 = 三种包之外的 UI 杂项设置（2026-10-09 追加，已更名）；二级条目按新 UI 重新整理收敛（现状 14+ 项平铺） | 更名已完成；收敛在 P4 配置中心域迁移 |

---

## 11. fork 同步约束下的实施原则

本仓库需持续从 `Legado_Max` 同步移植，UI 大改会显著抬高合并冲突成本，因此：

1. **新代码一律进新模块/新包**：`modules/theme`、`modules/uikit`、`io.legado.app.reader`、`ui/navigation`——全新文件，零冲突。
2. **少改 upstream 已有文件**：必须改的（`MainActivity`、`ThemeConfig`、`ReadBookConfig`）只做**薄适配**，逻辑搬到新包。
3. **冻结高冲突区**：`ui/book/read/page/**`、`ui/book/read/epub/**`、`model/**` 一律不动。
4. **按 tag 二分**：每阶段结束打 tag，移植冲突时按 tag 定位归属。
5. **配置层迁移谨慎**：`ui/book/read/config/**` 是 upstream 高频改动区，只搬配置模型与面板，不重排文件内部结构。

---

## 12. 风险登记

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 阅读排版内核改动导致排版回归 | 高 | `page/**`、`epub/**` 冻结，只改配置层与面板 |
| 持久化收口导致老用户配置丢失 | 高 | 旧 key 保留读取 + 一次性迁移 + corrupt 备份回退；**发版前执行 §10 第 0 条 H.1.7.3 覆盖升级逐项核对（门槛）** |
| 主题包格式变更导致旧包无法导入 | 中 | `formatVersion` 递增 + 旧格式分支保留 |
| 与 upstream 合并冲突激增 | 中 | 见 §11 |
| Compose / XML 混编影响启动性能 | 中 | 监控冷启动 P95；阅读页排版不引入 Compose |
| 约 100 个配置字段导致设置页膨胀 | 中 | 设置项三级分级 + 设置内搜索 |
| 大范围替换引入回归 | 中 | 逐域发版、每域一个 tag |

---

## 13. 验收标准（可量化）

| # | 指标 | 现状 | 目标 |
|---|---|---|---|
| A1 | 裸 `AlertDialog` / `Toast.makeText` / `Snackbar.make` 调用 | 三套并存 | **0** |
| A2 | drawable 中硬编码 `<corners android:radius>` | 25 个 | **0**（全部走 token 或 `?attr`） |
| A3 | Compose 中硬编码 `RoundedCornerShape(数字)` | 90+ 处 | **0** |
| A4 | 按钮走 `AppButton` 覆盖率 | ~0（142 个 TextView 当按钮） | **≥ 95%** |
| A5 | 内嵌 empty/loading/error 的 layout | 43 个 | **0** |
| A6 | 设置页实现方式 | 3 种（Compose DSL / Preference XML / 阅读页 Dialog） | **1 种** |
| A7 | 主题持久化入口 | 4 层 | **1 个**（`ThemeRepository`） |
| A8 | 字体切换入口 | 3 套 | **1 个**（`FontStore`，3 个角色） |
| A9 | 背景图槽位结构 | 3 个字段 + 阅读独立体系 | **1 个**（`Scene` 枚举） |
| A10 | `UP_CONFIG` 位掩码常量 | 6 个魔法数，9 个消费方 | **0**，`ReadConfigEvent` 覆盖 100% |
| A11 | 主 Tab 数 | 6 + 侧栏 | **3** |
| A12 | `MainActivity` 体积 | 102KB | **≤ 30KB** |
| A13 | 冷启动 P95 / 内存 | 基线 | 劣化 **≤ 5%** |
| A14 | 阅读页「跟随主题」开关 | 无 | 有，且默认关闭以保证向后兼容 |
| A15 | 硬编码颜色（kt `Color(0xFF`/`Color.parseColor`、xml `#` 字面值） | 66 处 | 白名单外 **0** |
| A16 | drawable 位图 | 7 个 | 仅品牌资产保留（≤ 3 个），UI 图标 **100% vector** |
| A17 | 液态玻璃 / miuix 业务直引 | 17 处 / 11 文件 | **0**（仅经 `SkinProvider`） |
| A18 | E-Ink 动效退化与阴影禁用 | 各页面自行判断 | 统一封装在 `uikit`，业务侧判断 **0** 处 |
| A19 | 从 H.1.7.3 直接覆盖升级后的主题设置保留率 | — | **100%**（清单见 §10 第 0 条），**每阶段发版前逐项验证** |
| A20 | 「我的」页面构成 | 混有使用入口（RSS/首页聚合曾误放） | **0 使用入口**，仅设置配置类条目（R1，侧栏承担使用入口） |
| A21 | 外观与 AI 分组 | 合并在「外观与 AI」一组 | 拆分为「外观」「AI」**两个独立分组**（R1） |
| A22 | 三包体系 | 枢纽页曾是入口聚合（外观套件/界面设置散卡）；主题包不含资源类型；界面元素分散管理 | 枢纽页**仅三卡**（主题包/界面包/阅读页包）；主题包可携带 封面图集/SVG 气泡/EPUB 模板；界面元素（顶栏/底栏/按钮/边框/侧边栏）可整体打包、导入导出、选用；散卡不再单独存在（R2） |
| A23 | UI 其他设置（原界面设置） | 更名前混含三包相关入口；14+ 项平铺 | 名称=「UI 其他设置」且**只含三包外杂项**；按新 UI 分组收敛，**一级条目 ≤ 8**（R3） |

---

## 附录 A：关键文件清单

**主题相关**
- `app/src/main/java/io/legado/app/help/config/ThemeConfig.kt`（53KB，Config 31 字段）
- `app/src/main/java/io/legado/app/help/config/ThemePackageManager.kt`（67KB）
- `app/src/main/java/io/legado/app/ui/config/ThemeManageActivity.kt`（110KB）
- `app/src/main/java/io/legado/app/lib/theme/ThemeStore.kt` / `ThemeRuntimeKeys.kt` / `ThemeUiPalette.kt` / `UiCorner.kt` / `UiTypography.kt` / `ComposeUiCorner.kt` / `LegadoComposeTheme.kt`
- `app/src/main/java/io/legado/app/base/BaseActivity.kt`（`upBackgroundImage()` L235-266）
- `app/src/main/java/io/legado/app/help/AppFont.kt`（18KB）
- `app/src/main/java/io/legado/app/constant/PreferKey.kt`（L219-273 字体色、L428-459 主题/背景图）

**界面风格相关**
- `app/src/main/res/values/dimens.xml`（8 个 radius、约 100 项间距、3 档字阶）
- `app/src/main/res/values/styles.xml`（现有全部 style 清单）
- `app/src/main/res/values/colors.xml` / `colors_material_design.xml` / `values-night/colors.xml`
- `app/src/main/res/drawable/`（95 个 `bg_*`，其中 25 个硬编码圆角）
- `app/src/main/java/io/legado/app/ui/widget/`（97 个自定义控件）
- `app/src/main/java/io/legado/app/ui/widget/compose/AppUiTokens.kt`（仅 24 行）
- `app/src/main/java/io/legado/app/utils/ToastUtils.kt` / `Snackbars.kt` / `DialogExtensions.kt`

**阅读页相关**
- `app/src/main/java/io/legado/app/help/config/ReadBookConfig.kt`（53.6KB，Config 69 字段，L970-1039）
- `app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt`（265KB）
- `app/src/main/java/io/legado/app/ui/book/read/ReadMenu.kt`（35KB，`interface CallBack` L799-870）
- `app/src/main/java/io/legado/app/ui/book/read/config/`（17 个 Dialog + 9 个 Activity）
- `app/src/main/java/io/legado/app/ui/book/read/page/PageView.kt`（118KB，**冻结**）

**导航相关**
- `app/src/main/AndroidManifest.xml`（107 个 `<activity>`）
- `app/src/main/java/io/legado/app/ui/main/MainActivity.kt`（102KB，ViewPager + BottomNavigationView + 自绘侧栏）

---

## 附录 B：术语

| 术语 | 含义 |
|---|---|
| 主题包 `theme` | 视觉素材数据层：背景图 / 配色 / 字体 |
| 界面包 `uikit` | 设计系统：Token / 组件 / 状态契约 / 提示与弹窗门面 |
| 阅读页包 `reader` | 阅读排版与朗读配置 + 阅读设置面板 |
| Scene | 背景图场景枚举（main / bookInfo / panel / reader） |
| Role | 字体角色枚举（reading / ui / title） |
| 跟随主题 | 阅读页配色是否取用主题包 `ColorScheme` 的开关 |
| 视觉语言规范 | §6.5：色彩 / 图标 / 排印 / 层级 / 动效 / 图片空态的统一约束 |
| SkinProvider | `uikit` 皮肤接口；miuix 与液态玻璃的唯一合法暴露途径 |
