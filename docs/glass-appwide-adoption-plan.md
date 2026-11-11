# 全 App 玻璃材质可接入面勘查与分阶段接入方案

> 勘查日期：2026-11-09。基准观感：主界面底栏（`StableLiquidGlassView` + shell overlay）。
> 本文件是接入工作的唯一方案来源；每阶段完成后的偏差应回写到这里，而不是散落在提交说明里。

## 0. 结论摘要

- 玻璃系统（材质参数、四档降级、液态 AGSL）已经稳定，**不需要新造任何一套数学**；后续所有接入只做两件事：选对入口、选对采样域。
- 决定「能不能接、怎么接」的不是页面名，而是**宿主视觉后方是什么**：
  - 后方是壁纸层 → 现有 `ExploreGlassBackdrop` 壁纸采样管线直接可用；
  - 后方是不透明卡片/面板/封面/书页/视频 → 壁纸采样会折射出「实际不存在的背景」，必须先解决采样源（兄弟子树采样）或只走实底壳。
- 按此判据重新核对后：发现页弹窗控件、套件管理预览控件、键盘工具条均**不属于**壁纸域（它们在面板内），从原 P0 名单移出；P0 收敛为选择模式底栏 `SelectActionBar`。

## 1. 现有玻璃系统（接入面）

| 入口 | 形态 | 适用宿主 | 备注 |
|---|---|---|---|
| `StableLiquidGlassView` | View，固定位置浮层 | 底栏、顶栏 `MainTopBarView` | 采样源必须是**兄弟子树**；**禁止放入 RecyclerView 行内**（1.25.9 native 崩溃） |
| `io.legado.app.ui.glass.GlassSurface`（跨包入口）/ `ExploreGlassBackdrop`（发现页内部） | Drawable，任意 View | 行、控件、底栏、面板 | 同一份 `glassDrawable`，四档降级；入口要求**显式采样域**，非壁纸域返回 null；采样源 = 壁纸位图按屏幕坐标对齐 |
| `ExploreGlassPreview` | 设置页预览 | 设置页 | 与生产同一入口 |

材质唯一来源：`GlassMaterialPreset.current(mode)`。四档降级：①液态 AGSL（API 33+，`RowGlassLiquidRenderer`）→ ②硬件高斯（API 31+）→ ③软件预模糊位图 → ④实底壳（无玻璃档 / 动图壁纸纯色底 / 设备降级）。

**档位差异（1.26.1 起）**：透明玻璃与磨砂玻璃不再只在着色上分深浅，而是同时拉开四条可见维度——模糊（透明 3–6dp / 磨砂 20–32dp）、白色着色（0.004–0.014 / 0.18–0.28）、色散（0.24–0.36 / 0.04–0.08）、折射几何（高 24–36dp、偏 46–72dp / 高 12–15dp、偏 20–32dp）。旧取值下两档切换在真机上差异不足；壳层 / 描边 / 实底公式保持 1.25.5 口径不变。所有宿主（底栏、顶栏、发现页行、各玻璃面板）自动同享这一份差异，不新增偏好键。

## 2. 决定性约束：采样域判定

### 2.1 壁纸域（可用现有壁纸采样管线）

判据（三者同时成立）：

1. 宿主所在 Activity 的背景图开关为默认开启：`BaseActivity(imageBg = true)`，`upBackgroundImage()` 会把主题背景图设置到 `window.decorView`；
2. 设备上存在可用背景图（`ThemeConfig.hasUsableBgImage`），此时 `applyRootBackgroundPolicy()` 会把根布局背景置空、透出 decorView 背景图；
3. 宿主到根布局的路径上没有不透明容器（卡片、面板、封面、书页、视频画面）遮挡壁纸。

满足时，`ExploreGlassBackdrop` 解码的壁纸位图（`MainThemeBackgroundState`，同源同裁剪）与屏幕坐标对齐，折射/模糊内容即用户实际看到的背景。
无背景图时管线回退为纯色底图，玻璃表现为「实色磨砂」，仍然自洽；动图壁纸默认走实底壳（避免「壁纸在动、玻璃静止」）。

### 2.2 面板域 / 内容域（壁纸采样不可直接使用）

宿主视觉后方是**不透明面板或非壁纸内容**：对话框卡片、Compose 面板（可带面板背景图）、封面、书页、视频帧。此时壁纸采样会画出「被面板遮住、用户根本看不到的壁纸」，观感与周围不连续，属于错误管线。

处理路径二选一：

- 面板本身玻璃化（整个面板一个玻璃层，面板内控件跟随面板材质，不单独采样）；
- 用 `StableLiquidGlassView` + 兄弟子树采样源（顶栏 1.25.10 已验证的模式），采样真实内容。

### 2.3 勘查修正记录

初版勘查把以下三处误列为壁纸域，经代码核对后归入面板域：

| 表面 | 实际结构 | 结论 |
|---|---|---|
| 发现页书源类型弹窗内控件（`ExploreFragment` 5 处） | `UiCorner.opaqueRounded(themeCardColor)` 不透明对话框面板 | 面板域，移出 P0 |
| 套件管理预览控件（`DiscoverySuiteManageActivity` 6 处） | Compose `ClassicDiscoverPreview` 面板（带 `panelImage` 背景） | 面板域，移出 P0 |
| 键盘工具条（`KeyboardToolPop` 4 处） | 弹层内 RecyclerView 显式设置不透明卡片底色 | 面板域，移出 P0 |

## 3. 候选面盘点

| 类 | 表面 | 域 | 接入方式 | 阶段 |
|---|---|---|---|---|
| A | **选择模式底栏 `SelectActionBar`**（书源/字典/RSS 源/替换规则/导入书/远程书/书架整理/书架管理/TXT 目录规则，7 个布局、8+ 宿主页面） | 壁纸域（`imageBg=true`，底栏照旧遮在列表上，与主底栏同语义） | `panelBackground(host)` drawable | **P0** |
| A | 书架收藏页多选操作栏（`BookCollectionActivity` 自绘 `book_action_bar`，与 `SelectActionBar` 同语义但未复用该控件） | 壁纸域（`imageBg=true`，底栏直接遮在书籍网格上） | `GlassSurface.panelBackground(WALLPAPER)` drawable + 圆角轮廓 | **P0 补漏** |
| A | 发现页行 / 行内控件 / 顶部浮层 | 壁纸域 | 已完成 | 已上线 |
| B | 「我的」页卡片与按钮（Compose，壁纸正上方） | 壁纸域 | `GlassPanel`（Compose 桥） | **P2 已实施** |
| B | 搜索页（Compose，`imageBg=true`） | 壁纸域 | `GlassPanel`（输入框） | **P2 已实施** |
| C | 阅读页底部菜单与顶部菜单（Compose，浮在书页上） | 内容域 | `StableLiquidGlassView` + 兄弟子树采样（`read_view`） | **P3 / P4 已实施** |
| C | 阅读页搜索菜单底部面板（`SearchMenu`，浮在书页上） | 内容域 | 同容器玻璃层（`fl_bottom_menu`）+ 兄弟子树采样（`read_view`） | **P4 已实施** |
| C | 漫画阅读菜单底部控制条（`MangaMenu`） | 内容域 | 同容器玻璃层（`fl_manga_control_pill`）+ 兄弟子树采样（`webtoon_frame`） | **P4 已实施** |
| C | 漫画阅读菜单顶部标题栏（`MangaMenu` 的 `TitleBar`） | 内容域 | 兄弟玻璃层（半径 0，约束到 `title_bar` 上下边）+ 同一采样源；与顶栏共用同一段平移动画 | **P4 已实施** |
| C | 订阅页顶部浮层（`RssFragment` 的 `MainTopBarView`） | 内容域 | `setBackdropBlur(fl_rss_content)` 兄弟子树采样；Web 渲染模式无源、经典布局维持实底 | **P4 已实施** |
| A | 订阅源卡片（`RssAdapter` 的 `item_rss`，1–3 列宫格 / 单列列表） | 壁纸域（订阅页根布局透明，卡片背后就是壁纸） | `GlassSurface.rowBackground(WALLPAPER)` drawable（与发现页书源名行同源）；墨水屏 / 低内存回退 `bg_ai_surface_panel` | **P4 已实施** |
| C | RSS 阅读页标题栏（`ReadRssActivity`） | 壁纸域（窗口背景即主题壁纸） | `GlassSurface.panelBackground(WALLPAPER, 0f)` drawable；无玻璃档不接管，墨水屏 / 低内存由 drawable 入口返回 null | **P4 已实施** |
| A | 共享搜索栏 `io.legado.app.ui.widget.SearchView`（`view_search` 与三处独立布局，覆盖 RSS / 发现 / 我的 / 搜索页 / 导入 / 搜索内容 / 书架整理 / 书源与 RSS 调试 / 高亮规则 / 阅读器资源） | 壁纸域（宿主根布局透明、`TitleBar` 透明） | 控件内按显式 `glassDomain` 取 `GlassSurface.panelBackground(WALLPAPER, TopBarSearchStyle.radius())`；默认 `PANEL` 不接玻璃 | **P4 已实施** |
| C | 阅读页 AI 浮窗 / 章节摘要浮窗（`read_ai_panel` / `read_ai_summary_panel`） | 内容域 | Compose `AndroidView` 玻璃层（同尺寸同圆角垫在 `Surface` 之下）+ `read_view` 兄弟子树采样；面板 `Surface` 让出实底 | **P4 已实施** |
| C | 音频播放控制面板、视频控制条 | 内容域 | 无合法兄弟子树采样源（背景由自身 Compose / 播放器 Surface 绘制）→ 需先把氛围层重构为真实 View 兄弟节点 | 未接入（设计见 §4） |
| C | 书籍详情封面上的信息卡 | 内容域（`imageBg=false`） | 无 `ViewGroup` 采样源（背后是 `ImageView`）+ 卡片底色 87% 不透明 | 未接入（设计见 §4） |
| C | 发现页弹窗、套件管理预览、键盘工具条、书源/RSS 调试页 | 面板域 | 发现页弹窗经 `ModernActionPopup` 统一宿主已随同接入；套件预览无内容采样源、键盘工具条是独立 `PopupWindow` 窗口 | 弹窗已实施，其余未接入（设计见 §4） |
| D | `ModernActionPopup`（发现页弹窗、行菜单、`showFromMenu` 统一宿主） | 面板域（无 scrim） | 面板整体玻璃化 + 行底色让位 | **P4 已实施** |
| D | 通用对话框、`SourceLoginDialog` | 面板域 + scrim 叠加 | 跨窗口绘制，需「窗口快照」管线 + scrim 亮度补偿 | 未接入（设计见 §4） |
| — | 主界面侧边导航面板 | 面板域（可带自有背景图） | 整体玻璃化 + scrim 补偿 | **P4 已实施** |

## 3.1 二级页面增量勘查（2026-11-11）

背景前提核查：`applyRootBackgroundPolicy`（`BaseActivity.kt:228`）默认全部页面 `imageBg=true`，仅 `BaseReadBookActivity:68`、`BookInfoComposeActivity:93`、`AiChatActivity:86`、`WelcomeActivity:24`、`ImageCropActivity:42`、BookCharacter 四页、`AiWorldBookManageActivity:28`、`EpubLoadingTemplatePreviewActivity:19` 显式关闭。`TitleBar`（`ui/widget/TitleBar.kt:195-199`）透明与否由「透明导航栏」偏好驱动，`opaque="true"` 仅 5 处（web_view、manga_menu[已接]、code_edit、rss_read[已叠玻璃]、web_view_login），其余约 55 个宿主为默认透明档。

| 分组 | 候选面 | 前提 / 路径 |
|---|---|---|
| 可直接接入 · 内容域 | **搜索内容页底部悬浮条**（`activity_search_content.xml:34-46` `ll_search_base_info`，代码 `SearchContentActivity.kt:60`）+ mini FAB（:89） | 现状是自绘「假玻璃」`bg_search_glass_panel`；根布局无背景、`rv_search_list` 兄弟子树可采样，零结构改动 |
| 可直接接入 · 壁纸域 | **AppManagementScaffold 宿主页顶栏/多选底栏**（`ui/widget/compose/AppManagementScaffold.kt:112-121` 顶栏、:264-278 底栏） | 根透明（:75），十余个管理页（Toc/AutoTask/DictRule/RuleSub/ThemeManage 等）共用，一处改动覆盖最大 |
| 可直接接入 · 壁纸域 | **TocActivity 底部跳转条**（`TocComposeScreen.kt:1101-1135`） | 浮在列表上、页面透明，行已按 `hasBgImage` 自适应（:963,1354） |
| 可直接接入 · 壁纸域 | **ConfigActivity 顶栏**（`ConfigActivity.kt:245-296` `ConfigTopBar`） | 程序化根布局无背景（:57-122），Compose 顶栏浮在壁纸上 |
| 需先改结构 | **设置二级页 + About 页分区卡**（`SettingSpecScreen.kt:167`、`AppSettingComponents.kt:139` 页背景铺不透明 `colors.page`；`AboutFragment:42`） | 卡片结构与「我的」页同构，页面根换 `glassPageBackgroundColor` 即解锁（`MySettingsScreen.kt:177` 现成先例）；FileManage 同理（`FileManageScreen.kt:87,193-239`） |
| 需先改结构 | **BookInfoActivity 底部操作排**（`activity_book_info.xml:579-640` `fl_action`） | `refresh_layout` 兄弟可采样，但背后是封面 ImageView + 50% scrim，需先定可读性方案 |
| 已由 TitleBar opt-in 覆盖 | 普通二级页顶栏（`ExploreShow`、缓存 / 导入 / 收藏 / 回收站 / RSS 收藏与文章 / 高亮规则 / 阅读器资源 / AI 图库 / 年度报告等） | 第四批落地 `TitleBar.applyWallpaperGlass()` 显式 opt-in，第五批扩展到 13 个调用点；书源 / 订阅源 / 替换规则页改 Compose 顶栏后 XML `TitleBar` 已隐藏，顶栏玻璃由 `AppManagementScaffold` 提供 |
| 前提缺失 | BookCharacter 系列 / AiWorldBookManageActivity 卡片 | `imageBg=false` 壁纸不可见，需先改背景策略 |
| 结构性阻塞 | AiChatActivity（输入栏/气泡全 Compose Surface + `imageBg=false`）、WebView / CodeEdit / WebViewLogin（opaque TitleBar + WebView/Surface 内容） | 无有效 View 采样源或跨窗口 |
| 不建议接 | ThemeManage 主题预览卡（预览保真）、EpubLoadingTemplatePreview、ImageCrop（取景器自绘）、HandleFile（收益低） | — |

优先级：SearchContent 悬浮条（内容域零结构改动）→ AppManagementScaffold 顶/底栏（一处覆盖十余页）→ 设置/About 页根背景换 `glassPageBackgroundColor`（解锁最大一批卡片）→ TitleBar 全局改造（单独评估项）。

**实施记录（2026-11-11）**：
- 搜索内容页悬浮条：`ll_search_base_info` 背后是壁纸而非列表（recycler 底边约束在条顶），改按**壁纸域**接入（`GlassSurface.panelBackground(WALLPAPER, 18dp)`），非内容域——避免逐帧整树录制且采样位置正确；非玻璃档回退原 `bg_search_glass_panel`。
- `AppManagementScaffold` 顶栏（含搜索行）与多选底栏：`GlassPanel(WALLPAPER, 0)`，`wallpaperVisible = rememberHasUsableBgImage() && !isEInk` 时才建玻璃宿主，否则原实底；一处改动覆盖书源/订阅源/字典/替换规则/TXT 目录规则/自动任务/书架标签等管理页。
- 设置二级页与 About（`SettingSpecScreen` 全部分区卡）、文件管理页（`FileManageScreen` 页根 + 顶栏）：页面根换 `glassPageBackgroundColor` 透出壁纸，分区卡/顶栏接 `GlassPanel(WALLPAPER)`；无背景图 / 墨水屏保持原观感。
- 验证：`compileAppDebugKotlin` 通过、glass 单测全绿、`check_settings_mapping.py` PASS、debug 包已产出；真机观感未核验（adb 设备离线）。

**实施记录（2026-11-11 第三批）**：
- `ConfigActivity` 顶栏（`ConfigTopBar`）：所有 `ComposeSettingFragment` 二级页共用。程序化根布局本身无背景，顶栏这一条背后即壁纸，按**壁纸域**接 `GlassPanel(WALLPAPER, 圆角 0)`——圆角 0 是贴边矩形，带圆角会在屏幕顶部留缺口；判定口径与 RSS 阅读标题栏一致（`rememberHasUsableBgImage() && !isEInkMode`），否则保留原来的透明顶栏（不建玻璃宿主）。一处改动覆盖页面布局 / 阅读界面 / 其它设置等约 20 个二级页。
- 目录页 `TocComposeScreen`：浮在章节列表之上的 **搜索框、目录-书签-插入三标签页、底部跳转条当前章块 + 两个箭头块、空状态卡**统一接壁纸域玻璃。新增页面局部 helper `TocGlassSurface`（不修改共享的 `AppManagementCard`，保持爆炸半径最小）：玻璃档下静止态完全交给玻璃层、按压态只叠一层主题行色保留触摸反馈；非玻璃档回退原 `appSettingPanelBackground` 链，与改造前逐点一致。章节行与书签行**刻意保持半透明衬底**不逐行接玻璃（100+ 行会各建一个 `AndroidView` 宿主，违反第 5 节性能边界）。
- solid 档行为与全 App 其余面一致：渲染不透明实底壳（仅 RSS 阅读标题栏显式跳过 solid），不新增例外。
- 验证（真机）：`compileAppDebugKotlin` / `assembleAppDebug` 通过、glass 单测全绿、`check_settings_mapping.py` PASS；debug 包装机到 vivo V2417A（Android 16）后，adb 截图确认「页面布局」二级页顶栏、「字典规则」顶部菜单页顶栏为带液态高光的玻璃条，目录页搜索框 / 三标签 / 底部跳转条 / 空状态卡均透出模糊壁纸且边缘高光与主底栏同源。无背景图 / 墨水屏回退路径由代码结构保证（不创建玻璃宿主、原背景链不变），未改动用户持久化主题做真机验证。

**实施记录（2026-11-11 第四批）**：
- 共享 `TitleBar` 新增显式 opt-in `applyWallpaperGlass()`：仅当顶栏处于透明档（`!opaque && context.transparentNavBar`）、非墨水屏、页面确有可用背景图时，把背景换成 `GlassSurface.panelBackground(this, WALLPAPER, 0f)`（贴边矩形）；否则返回 false 保留 init 时的原背景。判定抽成纯函数 `titleBarWantsWallpaperGlass`，5 例 JVM 单测锁定真值表。
- 首批 opt-in 4 页：搜索内容页（`SearchContentActivity`）、全库搜索（`LibrarySearchActivity`）、所有书签（`AllBookmarkActivity`）、阅读记录（`ReadRecordActivity`）。四页均为页面根背景被 `BaseActivity` 置空、顶栏在内容之上的普通纵向布局，壁纸确实透到顶栏。
- 为什么不做全局 TitleBar 改造：62 个布局引用 TitleBar，其中视频播放 / 音频播放 / 书籍详情（`imageBg=false`）/ 相机取景 / 代码编辑 / WebView 等页面的顶栏背后不是壁纸（根容器自绘不透明背景或整页不是壁纸模式）；而「祖先容器是否不透明」无法在共享控件内可靠探测（P1 已否决运行期祖先透明度探测）。显式 opt-in 让每个调用点自己声明「这里壁纸确实透到顶栏」，后续页面按同一方法逐个接入。
- 验证：`compileAppDebugKotlin` / `assembleAppDebug` 通过；`TitleBarGlassTest` + glass 包单测全绿；`check_settings_mapping.py` PASS；debug 包装机后 adb 截图确认「所有书签」「阅读记录」「全库搜索」三页顶栏均为透出模糊壁纸的玻璃条；搜索内容页同一调用路径未单独截图（入口需经过阅读页搜索流程），其玻璃与否由同一判定函数与同一 drawable 入口决定。

**实施记录（2026-11-11 第五批）**：
- 继续用同一 opt-in 清空「受制于 TitleBar 全局改造」分组，新增 13 个调用点：缓存管理（`CacheManageActivity`）、缓存章节（`CacheActivity`）、缓存导出（`BookCacheExportActivity`）、导入书籍基类（`BaseImportBookActivity.onCreate`，同时覆盖本地导入 / 远程书籍两页）、书架收藏（`BookCollectionActivity`）、规则回收站（`SourceRecycleBinActivity`）、RSS 收藏（`RssFavoritesActivity`）、RSS 文章（`RssSortActivity`）、高亮规则管理（`HighlightRuleManageActivity`）、阅读器资源管理（`ReaderAssetManageActivity`）、AI 图库（`AiImageGalleryActivity`）、发现页查看更多（`ExploreShowActivity`）、年度报告（`YearlyReportActivity`）。
- 逐页核对布局前提，全部满足「`TitleBar` 是纵向流里的第一个子节点、内容在其下方、根布局无自绘背景」：`activity_cache_manage` / `activity_import_book` / `activity_book_collection` / `activity_source_recycle_bin` / `activity_rss_favorites` / `activity_rss_artivles` / `activity_cache_book` / `activity_book_cache_export` / `activity_highlight_rule_manage` / `activity_reader_asset_manage` / `activity_ai_image_gallery` / `activity_explore_show` / `activity_yearly_report`。没有一页是 `opaque=true` 或 `imageBg=false`。
- 未接入的编辑类 / 调试类页（书源编辑、RSS 源编辑、替换编辑、书源调试、RSS 调试、代码编辑等）保持原状：这些页要么整页是编辑区域、要么属于后续单独评估的面，不在本批范围。
- 验证：`compileAppDebugKotlin` 通过；`TitleBarGlassTest` + glass 包单测全绿；`assembleAppDebug` 产出 debug 包并装机；adb 截图确认「缓存管理」「收藏夹（RSS 收藏）」「规则回收站」「本地导入」四页顶栏为玻璃条；书架收藏页同一调用路径生效，但其顶栏背后壁纸以黑色为主，玻璃观感在截图上不明显，未按「明显可见」计为已核验。

**实施记录（2026-11-11 第六批 · 页面内卡片）**：
- 新增共享卡片壳 `GlassSurfaceCard`：命中玻璃时用 `GlassPanel` 垫底、`LegadoMiuixCard` 填充让位透明；未命中时原样铺实底卡。只声明采样方式（页面卡片按根视图对齐、对话框按屏幕对齐），材质与降级仍由底层管线单点决定。明确约束：**不放长列表每一行**，长列表行仍走行级 drawable 管线。
- 采样前提上提为 `BaseActivity.showsWallpaperBehindContent`（与 `applyRootBackgroundPolicy` 同源）；自铺不透明内容的页面必须覆盖为 false，避免从这些页面弹出的对话框/卡片采到用户看不见的壁纸——音频播放、视频播放、书籍详情三页已覆盖。`GlassSurface` 随之新增 `dialogGlassAllowed` / `pageGlassAllowed` 两个同源判定。
- 落点：阅读记录页 4 张统计小卡（`ReadRecordOverviewCard`）、搜索页输入帮助卡（`SearchInputHelpScreen` 的书架命中 + 搜索历史两张卡）、规则回收站条目卡（`SourceRecycleBinScreen`）、书架标签管理卡（`BookshelfTagManageScreen`）。阅读记录页原有 6 个 View 面板（阅读目标 / 热力图 / 最近在读 / 日记录 / 封面 / 排行）同步接入 `GlassSurface.panelBackground(WALLPAPER, 14dp)`，失败回退原 `panelInsetStrokeDrawable`——否则同页会出现「小卡玻璃、大卡实底」的混搭。
- 刻意未接：管理类长列表（书源 / 订阅源 / 缓存 / 导出记录，一屏数十至上百行）、规则编辑表单的字段卡（每表单十余个字段卡），逐行建 `AndroidView` 宿主违反第 5 节性能边界；这些页面继续靠顶栏 / 底栏玻璃提供观感。（第八批按用户要求改由 `AppManagementCard` 的玻璃分支接管管理列表行，见下。）
- 验证（真机）：`compileAppDebugKotlin` / `assembleAppDebug` 通过；glass + widget 单测 73 例全绿；`check_settings_mapping.py` PASS。adb 截图确认：阅读记录页全部卡片（含我后补的 6 个面板）同为透壁纸玻璃；搜索历史卡透出模糊壁纸；规则回收站借临时插入的一条假数据截图确认条目卡为玻璃后已删除该行（`select count(*) = 0`）。

**实施记录（2026-11-11 第七批 · 弹窗/浮层）**：
- Compose 弹窗统一宿主 `AppDialogFrame`（46 个调用点）整体玻璃化：`GlassPanel(WALLPAPER, screenAligned = true)` 垫底、卡片填充与 14dp 阴影让位（阴影会绕玻璃外缘画黑边，与 P3 底部菜单同因）、外层按面板圆角裁剪；判定用 `dialogGlassAllowed`，阅读页 / 书籍详情等壁纸不可见页面弹出的弹窗仍为实底。
- View 弹窗两条统一入口：`BaseDialogFragment` 的窗口背景改 `GlassSurface.dialogBackground(view) ?: dialogSurfaceBackground`（覆盖全部继承它的 DialogFragment）；`AndroidAlertBuilder` 的两处 `setBackgroundDrawable` 改走 `window.decorView` 采样（系统 AlertDialog 覆盖全 App 的 `alert()` / 输入框）。
- 独立卡片式弹窗改 `GlassSurfaceCard(screenAligned = true)`：`WaitDialog`、`BackupProgressDialog`、`NumberPickerDialog`、`SourceSelectDialog`、`BookshelfConfigDialog`。
- 明确未接：`SourceLoginDialog`（WebView 登录，跨窗口 + 网页内容）、`BottomWebViewDialog`、`CodeDialog`（编辑器自绘表面）、弹窗内的列表行字段卡（沿用实底字段面，避免叠层模糊）。
- 验证：`compileAppDebugKotlin` / `assembleAppDebug` 通过、单测全绿；adb 截图确认「排列统计组件」弹窗（`AppDialogFrame`）面板透出模糊壁纸；`alert()` 系弹窗与 `BaseDialogFragment` 系由同一 `dialogBackground` 入口决定，未逐一截图。

**实施记录（2026-11-11 第八批 · 目录与资源列表行卡片）**：
- **范围修正**：用户明确「卡片玻璃化也包括目录，书源 / 订阅源等各类资源展示界面的卡片」，推翻第六批按「长列表逐行 AndroidView 违反性能边界」作出的排除。落地方式不变：每行仍是 `GlassPanel` 承载同一份 `GlassSurface.panelBackground(WALLPAPER, 圆角)`，列表可见行数由 LazyColumn / RecyclerView 的组合窗口天然有界（一屏 10–20 行），滚动期每行只做壁纸位图局部采样与材质绘制，不录制列表子树、不在行内放 `StableLiquidGlassView`。
- **统一入口**：`AppManagementCard` / `AppManagementListRow` 新增 `glassSurface`（默认 true）与 `glassSignature`；签名由 `AppManagementScaffold` 通过 `LocalAppManagementGlassSignature` 按页提供一次（`rememberGlassConfigSignature` + `wallpaperVisible`），行卡片只读不算，不新增偏好监听。无背景图 / 墨水屏、或页面未提供签名（弹窗、自绘背景的设置页）时玻璃分支不触发，外观与接入前逐点一致。
- **采样前提**：卡片走 `GlassPanel(requireWallpaperHost = true)`，即 `GlassSurface.pageGlassAllowed` 与对话框同一份「页面背后真的是壁纸」口径；玻璃 drawable 没有按压态，`palette.settings.rowPressed` 按压色叠在玻璃之上保留触摸反馈。
- **目录页**：`TocChapterRow` 用圆角 0 的玻璃替掉原半透明行底——章节行无外边距、逐行采样同一张壁纸，视觉上等价于整片玻璃，同时保持行几何与吸顶卷标题不变；当前章 / 按压高亮仍叠在玻璃之上。`TocBookmarkRow` 的书签卡片走 `AppManagementCard(glassSurface = glassSignature != null)`。
- **自动覆盖的页面**（AppManagementScaffold 宿主）：书源管理、订阅源、订阅规则、替换规则、词典规则、TXT 目录规则、定时任务、书架标签管理。
- **保持实底的两类**：①弹窗内卡片（`BubbleQuickSwitchDialog` / `ShareNoteTemplateSelectDialog`）——弹窗根视图不是屏幕，没有签名提供者；②设置内的资源列表页（主题包 / 气泡 / 封面集合 / 存储容器 / AI 提供商 / 分享模板 / 顶栏方案等直接使用 `AppManagementCard` 的页面）——本批不提供签名，外观与接入前一致。其中气泡 / 封面 / 高级提示 / 书籍信息几页已核对到根布局自铺 `palette.settings.page` 页色，壁纸被整页盖住，接玻璃前需先把页根改成透壁纸（与 P2 的「我的」页同一改法）；其余页面待逐页核对根背景后再决定是否给签名。
- **验证**：`compileAppDebugKotlin` / `assembleAppDebug` 通过；glass + widget 单测全绿；`check_settings_mapping.py` PASS；debug 包装机后真机截图待补（adb 在安装完成瞬间掉线，恢复连接后按下列清单核验：书源管理卡片透壁纸、目录章节行透壁纸且滚动无错位、弹窗卡片仍为实底）。

## 4. 分阶段方案

### P0 · 壁纸域底栏收口（本次实施）

- `ExploreGlassBackdrop` 新增公共入口 `panelBackground(host)`（圆角 `ui_panel_radius`，与主底栏 shell overlay 圆角逐值一致），`rowBackground` 委托给它，不改变现有行为。
- `SelectActionBar` 背景由 `bottomBackground` 实色替换为 `panelBackground(this)`；返回 null（墨水屏 / 低内存 / 动图壁纸纯色底）时回退原逻辑（`transparentNavBar` 保持透明，否则实色 + elevation）。
- **补漏：`BookCollectionActivity` 的自绘多选栏**。该页没有复用 `SelectActionBar`，而是自己铺了一条 72dp 的 `book_action_bar` 并写死 `surfaceCardColor` 实底，P0 逐宿主清点时被漏掉——同一 App 里书架管理的多选栏已是玻璃、收藏页内部仍是实底，属可见的不一致。改为走 `GlassSurface.panelBackground(WALLPAPER)`，回退值仍是原来的 `surfaceCardColor`；玻璃 drawable 无轮廓，补一个 `ui_panel_radius` 圆角矩形轮廓保住 XML 里的 8dp 投影（与 `SelectActionBar` 同一处理）。
- 不做：面板域控件（见 2.3）、TitleBar 全局改造（共享控件，待 P1 收口后单独评估）。

### P1 · 架构收口（已实施，不改观感）

**实现形态**：新增 `io.legado.app.ui.glass` 作为跨包唯一入口，内部仍委托探索页那条已验证的管线。本次**不做** 1.5k 行文件的物理搬迁：`ExploreGlassBackdrop` 里大量符号是发现页专属（偏好键、迁移、预览、配置签名），搬迁只会把「入口命名」问题换成「包划分是否合理」的新问题，且会在这份未提交工作区里制造大范围重命名。目标是让**调用方**不再 import 探索页包，实现位置随 P2/P3 渐进内聚。

- `GlassSamplingDomain`（`WALLPAPER` / `PANEL` / `CONTENT`）+ 纯函数 `glassBackdropSource` / `glassSurfaceDrawableAllowed`：壁纸域 → `WALLPAPER_BITMAP`；面板域 → `OPAQUE_SHELL`（入口返回 null，宿主保留原有不透明背景）；内容域 → `SIBLING_SUBTREE`（入口不提供，调用方必须用 `StableLiquidGlassView`）。JVM 单测锁死整张表（`GlassSamplingDomainTest`）。
- `GlassSurface`：`panelBackground / rowBackground / controlBackground` 均要求显式传采样域（**无默认值**），非壁纸域一律返回 null；`filletControlBackground(host, domain)` 是「玻璃 ?: 主题纯色控件背景」的唯一实现。
- 已迁移调用方：`SelectActionBar`（壁纸域底栏）、`ExploreAdapter`（行标题 / 行内控件）。面板域既有调用点（发现页弹窗、套件预览、键盘工具条、登录弹窗）继续直接用主题纯色控件背景，待 P4 面板整体玻璃化；其中发现页弹窗随 P4 `ModernActionPopup` 统一宿主一并接入。
- 为什么不做运行期祖先透明度探测：任意外层容器是否不透明取决于布局、主题与运行时状态，无法用纯函数可靠判定；把判定写成猜测会产生假阴性（该拒绝却放行）。显式声明采样域 + 纯函数表 + 单测是唯一可确定性的防线。

### P2 · Compose 桥（已实施）

**实现形态**：`io.legado.app.ui.glass.GlassPanel` 用一个 `FrameLayout` 宿主经 `AndroidView` 承载同一份 `GlassSurface.panelBackground`，Compose 内容作为同层后续节点叠在其上。宿主只声明采样域与圆角，材质 / 四档降级 / 描边 / 采样坐标全部沿用底层管线，**没有第二套参数**。

- **可用性在组合期判定**：`glassSurfaceAvailable(domain, mode, isEInk, isLowRam)` 是 `glassDrawable()` 早退分支的纯函数镜像（无玻璃档在设备降级之前判定）；判不可用直接铺 `fallback` Modifier，不创建空 View。JVM 单测锁表（`GlassSurfaceAvailabilityTest`）。
- **重绑键 = 现有配置签名**：`rememberGlassConfigSignature()` 每个页面注册一个偏好监听，把 `ExploreGlassBackdrop.configSignature()` 提为 Compose State；签名进 `GlassPanelHost.bind` 的去重键（`签名|域|圆角`），设置变化等价于发现页行重绑。不新增偏好键、不新增监听集合。
- **位置跟踪复用 TrackerBinding**：`glassDrawable` 挂 drawable 时已绑定宿主的 attach / layout / `OnPreDraw`，Compose 列表的平移（不触发子 View 布局回调）由 `OnPreDraw` 覆盖，因此桥内**不再**自写逐帧坐标检测（自写版本晚一帧生效）。
- **叠放顺序已核实**：interop `AndroidViewHolder` 的绘制由 Compose 绘制流程按布局节点顺序调用（`AndroidViewHolder` 的 draw block → `AndroidComposeView.drawAndroidView` → `AndroidViewsHandler.drawView`；`AndroidViewsHandler.dispatchDraw` 为空实现，不会在合成内容之上二次绘制）。因此 `AndroidView` 声明在内容之前即「玻璃在内容下方」。
- **页面透明前提（勘查修正）**：「我的」页与全库搜索页原先在 Compose 根上铺不透明页色，把壁纸整页盖住——此时接玻璃采样的是**看不见的壁纸**，正是 2.3 的错误管线。两页改为在「背景图可用且非墨水屏」时页面背景透明（纯函数 `glassPageBackgroundColor`，与 `BaseActivity/BaseFragment.applyRootBackgroundPolicy` 同一判定），无背景图 / 墨水屏时保持原页色。
- **落点**：「我的」页分区卡片与搜索空态框（`SettingsSectionCard` / `EmptySettingsFrame`）；全库搜索页（`LibrarySearchActivity`）输入框。搜索结果行属高数量列表行，按第 5 节性能边界**不**逐行玻璃化。
- **已知偏差**：接入玻璃后，正常设备上「我的」页卡片不再显示用户自定义面板背景图（`panelImage`），无玻璃档下卡片外观由主题卡色变为底栏同源的实底壳——与底栏 / 发现页行的「无玻璃 = 不透明底栏色」口径一致。

### P3 · 内容域折射（复制顶栏先例）

**实现形态**：阅读页底部菜单（`ReadMenu` 的 Compose `Surface`）改为「真实玻璃层 + 透明面板」，玻璃层是 `ReadMenu` 的**子 View、在 Compose 内容之前**，采样源为阅读页正文 `read_view`（与 `read_menu` 平级的兄弟子树，与顶栏 1.25.10 同一守卫 `selfOrAncestorContains`）。

- **单一参数表**：新增 `io.legado.app.ui.glass.LiquidGlassSpec` + `liquidGlassSpec(params, density)`，顶栏原来的 `TopBarGlassSpec/topBarGlassSpec` 删除并改为同一函数；材质、倍率与四档降级仍只来自 `GlassMaterialPreset.current(mode)`，没有第二份公式。
- **启用判定纯函数**：`contentLiquidGlassSpec(params, density, sdkInt, sourceAvailable, isLowRam, isEInk)`，任一否决返回 null = 宿主保持原有实底表面。否决条件分别是「无合法采样源」「API<33 无 RuntimeShader」「低内存 / 墨水屏每帧整树录制不可接受」；solid 档由 `liquidGlassSpec` 自身返回 null。JVM 单测锁表（`LiquidGlassSpecTest`）。
- **绑定时机 = 菜单可见期**：底层库把 `OnPreDrawListener` 挂在**采样源**的 ViewTreeObserver 上，常驻绑定会让人在阅读时每帧把整页录制进 `RenderNode`。因此 `runMenuIn()` 绑定、`runMenuOut()` 动画结束（300ms 后清理块）解绑；菜单收起时不采样。
- **几何**：面板矩形来自 Compose 已有的 `onGloballyPositioned`（同一次回调顺带更新 `bottomMenuBounds`），玻璃层逐帧跟随，滑入/滑出动画不需要另写一套动画。底边额外向下延伸一个圆角（纯函数 `glassPanelRect`），把下方两个圆角推到父容器裁剪区之外——玻璃层只有单一半径，否则底部两角会挖出缺口。
- **EPUB 直读不接**：直读模式的正文是 WebView（`EpubDirectWebLayer`），`RenderNode` 录制 WebView 不可靠；且该模式下 `read_view` 本身已 `GONE`（`isShown=false` 是无源否决）。双重门禁，保持实底菜单。
- **宿主测量（真机修复）**：玻璃层以 `LayoutParams(0, 0)` 加入、位置由面板矩形决定，父容器因此测不到有效尺寸，内部玻璃子 View 停在 0×0、整层空绘制——真机表现是玻璃 / 磨砂档下阅读页底部菜单背景整体消失（页面文字原样透出、无模糊无着色）。修复两条：①定位时把 `LayoutParams` 同步为目标尺寸并按 `EXACTLY` 测量一次（尺寸只随面板变化，滑入滑出期间只改位置，不触发重新测量）；②面板只在「玻璃已激活且已完成一次定位」时让出底色（纯函数 `glassPanelFillYielded` + 单测），未定位时保持实底——即使玻璃链路在个别设备上失败，菜单也不会变成透明空面板。
- **导航栏区域与玻璃层的关系（真机修复，第六轮 e2e 定稿）**：磨砂档下底部菜单下方 33px（导航栏高度）是平涂色带。前五轮全部在「给够不到的区域配色」上打转：第一轮沉浸式让玻璃铺到屏底、第二轮隐藏实色条、第三轮实色条平涂合成色、第四轮局部采样渐变、第五轮折射区整段离屏 + 条带让位——真机均无效。**第六轮像素取证定案根因**：阅读窗口的 decor 内容被布局在导航栏**上方**（`ActivityExtensions.fullScreen()` 的 `setDecorFitsSystemWindows(true)` + `upSystemUiVisibilityO` 只有 `LAYOUT_FULLSCREEN` 没有 `LAYOUT_HIDE_NAVIGATION`），面板底边 = 内容底边 = 导航栏顶边，屏幕底部导航栏高度的一段根本不在应用视图树内，唯一画手是窗口级 navColor 撕膜（真机实测 (42,46,49) = 菜单关闭态的非沉浸 navColor）——面板、玻璃层、App 内条带无论怎么配色都够不到那一段。**定稿方案（与朗读全面板 `applyReadAloudPlayerSystemBars` 同一先例）**：玻璃菜单激活且非分屏时窗口切 edge-to-edge（`WindowCompat.setDecorFitsSystemWindows(window, false)` + `LAYOUT_HIDE_NAVIGATION` 加入旗标 + `navigationBarColor` 透明，纯函数 `readerGlassMenuWantsE2e(isInMultiWindow, anyGlassMenuActive)` 判定 + 单测），玻璃层与面板随之延伸到屏幕底，导航栏区域由真实磨砂覆盖；菜单关闭走常规路径重设旗标并重涂 navColor（窗口保持非收合，`PageView.vw_navigation_bar` 内边距已处理内容避让，与朗读全面板使用后的持久 e2e 状态一致）。前五轮产物全部保留并降级为该窗口结构下的配套件：`glassBottomEdgeExtensionPx`（折射区离屏，e2e 后玻璃底边贴屏底仍需要）、`readerNavStripYieldsToGlass`（条带让位）、`readerGlassNavBarColor`（分屏 / 多窗口回退配色）。第五轮遗留的「玻璃合成 navColor 未落地」之谜同轮解开：`runMenuIn` 里 `upNavigationBarColor` 先于玻璃激活回调重跑，条带停在菜单关闭态色值——e2e 方案使该配色路径在玻璃激活时不再参与显示，谜底不再有观察面。JVM 单测：e2e 判定真值表（激活/非激活 × 分屏/非分屏）、外推量、让位判定、`readerGlassNavBarColor`（保留）。

**顺序修正（勘查后）**：原计划先做音频播放控制面板，但核对代码后该面**没有可采样的兄弟子树**——展开态面板的背景是面板自身 `ComposeView` 内绘制的封面氛围层（`CoverAtmosphereBackdrop`），崩溃态胶囊是 `TYPE_APPLICATION_OVERLAY` 独立窗口（跨窗口无法录制 RenderNode）。要接折射必须把氛围层重构为真实 View 兄弟节点，属于「面板整体玻璃化」，归 P4。视频控制条同理（画面由 GSY `VideoPlayer` 内部的 Surface 渲染，控制条由该库自有布局承载）。书籍详情信息卡是滚动内容面板、底色 87% 不透明（`book_info_frost` 为 `#DD…`），换玻璃会直接降低正文可读性且需与 `vw_bg` 的 50% scrim 做叠加补偿，也归 P4。故 P3 首个（也是本阶段唯一）落点为阅读页底部菜单，其余内容域表面在 P4 统一评估。

**已知边界**：P3 当轮只做了底部菜单，顶部菜单随后在 P4 补齐（同一采样源与同一材质入口，见下）；EPUB 直读、solid 档、API<33、低内存与墨水屏完全保持原观感。

- **菜单面板四周黑边（真机修复，第八轮定稿）**：磨砂档打开菜单后，面板外缘与圆角周围有一圈黑边。第八轮 adb 像素取证 + 实验开关逐一证伪了折射位移、模糊边缘侵蚀、AGSL 着色器分支、宿主 `clipToOutline` 四个假设（none 档折射分支与 interior 分支算术恒等、亮度台阶对折射/模糊参数不变、关闭 clip 后台阶与圆角均不变——圆角实际由底层库 `LiquidGlassView.dispatchDraw` 的 clipPath 提供）。**根因**：Compose `Surface` 的 `shadowElevation`（8dp）**独立于填充色绘制**——玻璃激活让出底色后 `Surface` 变 `Color.Transparent`，阴影照画，在玻璃外缘留下一圈黑边。修复：新增纯函数 `glassPanelShadowElevationDp(fillYielded)`（让出→0，否则→8），底部菜单 Surface 的 `shadowElevation` 接入该判定，补 2 例单测（透明/磨砂让位档归零、solid/降级/未定位首帧保持 8dp）。经验：排查「让出底色」类缺陷时，Surface 阴影与填充色是两个独立绘制层，须分别归零验证。

### P4 · 面板与弹层

**已实施：主界面侧边导航面板（`MainActivity`）**

- **采样源**：`side_navigation_panel` 与 `content_container` 是同一窗口内的兄弟子树，面板底色换成玻璃后直接复用底栏那棵 `content_container`，不新增第二套采样源判定；面板与底栏一样走 `StableLiquidGlassView`。
- **面板整体玻璃化**：玻璃层是面板的**第一个子 View**（`MATCH_PARENT`）；玻璃激活时面板底色改透明、1dp 描边改挂玻璃层 `foreground`，标题卡与选中行改半透明白/黑（与自定义侧边背景图同一分支），面板内控件不再单独采样。
- **scrim 叠加补偿**：面板外被 `side_navigation_scrim` 压暗 0.42，而玻璃采样的是未压暗的 `content_container`，所以玻璃之上加一层纯黑 0.42 的补偿 View，`alpha` 与 scrim 同动画同步（开 180ms / 关 160ms），面板内外亮度一致。常量 `SIDEBAR_SCRIM_ALPHA` 同时供 scrim 与补偿层，避免两份数值漂移。
- **否决条件（纯函数）**：`sidebarGlassSourceAllowed(hasCustomBackground, sourceAvailable)`——配置自定义侧边背景图时面板最终外观由那张图决定、玻璃层会被整块盖住，直接不接；其余仍交给 `contentLiquidGlassSpec`（solid 档 / API<33 / 低内存 / 墨水屏 / 无采样源）。JVM 单测 3 例锁表。
- **绑定时机**：只在抽屉打开期间绑定（`openSideNavigation` 的 `doOnLayout`），收起动画结束（220ms）后解绑——关闭期间不付每帧整树录制开销，与「菜单可见期绑定」同一纪律。
- **测量安全**：面板走常规布局，玻璃层随面板一起测量，不存在 P3 的 `LayoutParams(0,0)` 场景；但「让出底色」仍加同一道闸——纯函数 `sidebarGlassFillYielded(active, glassWidth, glassHeight)`，只有玻璃层量到有效尺寸后面板才转透明（由玻璃层的布局回调触发一次重算），玻璃链路失败也不会出现透明空面板。JVM 单测 3 例锁表。

**已实施：`ModernActionPopup` 弹层整体玻璃化**

- **覆盖面**：`ModernActionPopup` 是弹层统一宿主——发现页 `discoverMorePopup` / `tagFilterPopup` / `modernMenuPopup`、各页行菜单与 `showFromMenu` 全部经它出图，因此这一处接入同时落掉 P4 的「发现页弹窗」一项。
- **采样源**：弹层是挂在 `anchor.rootView`（DecorView）上的全屏覆盖 `ComposeView`，`android.R.id.content` 子树与它是同一窗口内的兄弟分支，取它作采样源；不用 `host` 自身，也不含弹层自己，不存在自录制。源按 `host.findViewById(android.R.id.content)` 解析并校验 `isShown && width/height > 0`——独立 `PopupWindow` / 弹窗窗口里找不到该 id 时自然退回 null，弹层保持实底。
- **无 scrim，因此无需补偿层**：与侧边面板 / 对话框不同，弹层背后没有压暗层，玻璃采样亮度即最终亮度；这也说明「先定 scrim 补偿再接入」的约束只约束带 scrim 的宿主，不构成对弹层的阻塞。
- **面板整体玻璃化**：玻璃层与面板 `Surface` 共享同一套 `offset` / 宽高 / `graphicsLayer`（`alpha`、`transformOrigin`、0.96→1 缩放、±6px 位移），逐帧同形；玻璃就位后面板 `color` 改 `Transparent`，圆角由玻璃层 `setCornerRadius(panelRadius)` 承担。菜单行经 `LegadoMiuixChoiceRow(translucentSurface = true)` 让出行底色（仅选中态保留 0.14 强调色），否则默认 `surfaceVariant` 在默认 `dialogAlpha=100` 下近似不透明、玻璃只剩行间缝隙可见。
- **测量安全（纯函数）**：玻璃层是面板首帧测量回填尺寸后才挂上的，所以「让出底色」必须同时满足「玻璃可用」与「面板已测量」——纯函数 `overlayGlassFillYielded(glassAvailable, panelWidthPx, panelHeightPx)`，JVM 单测 3 例锁表（含首帧 0×0 回归锁）。未满足时面板保持实底、不挂玻璃层，避免弹出透明空面板（与阅读页菜单 1.25.11 同一失败形态）。
- **绑定时机**：玻璃层随 `AndroidView` 组合进树时 `bind`，弹层移除即 `onDetachedFromWindow` → `StableLiquidGlassView.removeGlass()` 自动解绑；收起动画期间（150ms / 墨水屏 0ms）玻璃与面板一起淡出，不存在「面板已移除仍在录制」的窗口。
- **降级**：`contentLiquidGlassSpec` 统一判定 solid 档 / API<33 / 低内存 / 墨水屏 / 无采样源，命中任一则 `glass == null`，弹层维持原有实底表面（含行色）；自定义主题、日间夜间均不新增分支。

**已实施：阅读页顶部菜单玻璃化（`ReadMenu`）**

- **采样源复用**：顶部菜单与底部菜单同属 `read_menu`，采样源仍是阅读页正文 `read_view`（同一兄弟子树、同一 `selfOrAncestorContains` 守卫）。两个玻璃层（`topGlass` / `bottomGlass`）在同一份 `refreshMenuGlass()` 里解析一次源与 spec，任一为 null 时两层一起退回实底，不会出现「下半截玻璃、上半截实底」的材质分叉。
- **方形而非圆角**：`ReadMenuTitleBar` / `ReadMenuActionBar` 本身就是贴边全宽的矩形 `Surface`；玻璃层只有单一半径 API，加圆角会在屏幕顶边两角挖出缺口。因此顶部玻璃 `setCornerRadius(0f)`（不裁剪），`glassPanelRect(..., cornerRadiusPx = 0f)` 不向下延伸底边——与既有矩形顶栏形状逐像素一致。
- **几何与让出底色**：Compose 顶栏 `onGloballyPositioned` 把窗口坐标交给 `updateTopGlassGeometry()`，玻璃层按目标尺寸 `EXACTLY` 测量并布局；`glassPanelFillYielded(topGlassActive, topGlassGeometried)` 未满足时顶栏保持 `style.surface`，玻璃链路失败不会得到透明空顶栏（与底部菜单 1.25.11 修复同一闸）。
- **绑定时机**：与底部菜单同步——`runMenuIn()` 绑定、`runMenuOut()` 动画结束后解绑，阅读过程中不付每帧整树录制的开销。

**已实施：阅读页搜索菜单底部面板玻璃化（`SearchMenu`）**

- **结构改动**：`view_search_menu.xml` 里把 `ll_bottom_menu` 包进新容器 `fl_bottom_menu`（外边距 16/16/10dp 移到容器上），玻璃层是该容器的第一个子 View（`MATCH_PARENT`），面板本体留在容器内第二层。滑入 / 滑出动画改在容器上执行，玻璃与面板同属一个被 `translate` 的子树，位移逐帧一致——不需要坐标换算，也没有「玻璃停在终位、面板还在滑」的错位窗口。
- **采样源**：与阅读页主菜单同一个正文 `read_view` 兄弟子树；EPUB 直读时 `read_view` 为 `GONE`，按「不可见即无源」退回实底。玻璃层在本视图子树内，采样源不是其自身也不是祖先，`StableLiquidGlassView` 的 `selfOrAncestorContains` 守卫不触发。
- **几何**：面板走常规布局，玻璃层随容器一起测量，没有 P3 的 `LayoutParams(0,0)` 场景；圆角由玻璃层 `setCornerRadius(18dp)` 承担，矩形不做底边延伸（面板离屏幕底边 10dp，圆角本来就该露出来）。
- **让出底色**：沿用 `glassPanelFillYielded(active, glassWidth/Height > 0)`——玻璃层由 `GONE` 转 `VISIBLE` 的那一帧还没有尺寸，面板保持 `panelBackground`，由玻璃层布局回调触发一次重算，避免一帧透明空面板。
- **绑定时机**：`runMenuIn()` 绑定、收起动画结束后 `hideGlass()` 解绑并 `clearAnimation()`；面板不可见（`onVisibilityChanged`）时兜底解绑，搜索态切走不会遗留每帧整树录制。

**已实施：漫画阅读菜单底部控制条玻璃化（`MangaMenu`）**

- **结构**：`ll_manga_control_pill` 外新增容器 `fl_manga_control_pill`（16/14dp 外边距上移到容器），玻璃层是容器第一个子 View（`MATCH_PARENT`），随 `bottom_menu` 既有的滑入 / 滑出平移动画一起移动，与控制条逐帧同形。
- **采样源**：漫画正文 `webtoon_frame`（`manga_menu` 的兄弟子树）。菜单打开期间全屏遮罩 `vw_menu_bg` 接管触摸，正文静止，不存在「滚动中逐帧录制大图列表」的场景——这也是本项从「维持实底」转为可实施的原因：成本只发生菜单可见期，与主底栏常驻采样的量级一致，而不是与滚动中的列表叠加。
- **形状与让出底色**：圆角取 `manga_control_bar_radius`（24dp，恰好是 48dp 条高的一半，玻璃层与胶囊同形）；`glassPanelFillYielded(active, 玻璃实测尺寸 > 0)` 满足后控制条底色与 1px 描边一并让给玻璃层，由玻璃层布局回调触发一次重算。
- **顶部标题栏同源接入**：`titleGlass` 是 `ConstraintLayout` 的兄弟节点，约束到 `title_bar` 的上下边（`0dp` × `0dp` 双向约束 = 与标题栏逐像素同尺寸），半径取 0（贴边全宽矩形，加圆角会在屏幕顶部两角挖缺口）。它与标题栏共用同一段 `anim_readbook_top_in/out`（`-100%` 垂直平移按时高百分比解析，两者高度相同 → 像素位移一致），滑动期间不出现「玻璃停住、标题栏先走」或反过来的错位。让出底色沿用 `glassPanelFillYielded(active, 玻璃实测尺寸 > 0)`：满足后把 `title_bar` 背景置空（原背景——主题主色或墨水屏描边——保存在 `titleBarBackground`，条件不满足时原样还原），所以玻璃链路失败时标题栏仍是实底主色，不会变成透明空条。
- **共享控件的边界**：这里只为 `MangaMenu` 内的**这一个实例**接管背景，`TitleBar` 控件本身与其它页面的实例不变；墨水屏 `contentLiquidGlassSpec` 直接否决，描边分支保持原样。
- **绑定时机**：`runMenuIn()` 绑定、收起动画结束 `hideGlass()` 解绑并 `clearAnimation()`；`onVisibilityChanged` 兜底，离开漫画页不会遗留采样。

**已实施：订阅页顶部浮层玻璃化（`RssFragment` 的 `MainTopBarView`）**

- **补齐的是同型宿主的缺口**：书架（`BookshelfFragment1`）与发现页（`ExploreFragment`）早已在 `updateTopBarOverlay` / `updateModernTopBarOverlay` 里调用 `setBackdropBlur`，订阅页同样使用 `MainTopBarView`、同样把列表滚到顶栏下方（`recycler.paddingTop = topBar.height` + `clipToPadding`），却从未接线，导致同一个顶栏控件只在订阅页是实底。
- **采样源**：`fl_rss_content`——与 `top_bar` 同在 `swipeRefreshLayout` 内那个 `FrameLayout` 下，是兄弟子树。传 `swipe_refresh_layout` / `content_container` 一律会被 `topBarLiquidSourceAllowed` 拒绝（祖先采样 = RenderNode 重入录制）。
- **Web 渲染模式视为无源**：订阅源可走 `renderWebSource` 用 WebView 渲染正文，此时内容由 WebView 独立绘制、不进父容器 RenderNode，采样只会得到空白。因此 `sourceAvailable` 取 `!rssWebContainer.isVisible`；容器切换（Web 渲染 / 空态 / 列表）后都会 `scheduleModernRssTopBarOverlayUpdate()` 重算。
- **判定与降级复用既有纯函数**：`shouldEnableTopBarBackdrop(overlayContent, sdkInt, sourceAvailable)` 三条件缺一不可（已有 JVM 单测锁表）。其中 `overlayContent` 取自 `topBar.isOverlayMode()`，即顶栏配置为 `STYLE_REGULAR`（覆盖式）——默认样式下顶栏本就平铺在顶部、列表不会滚到其下，与书架 / 发现页的门槛完全一致；实底档由 `MainTopBarView` 的 `overlayOpaqueBackground` 实底分支兜住，API<33 保持原观感。
- **经典布局显式解绑**：经典布局顶栏在列表上方、背后没有内容，`initClassicRecycler()` 里解绑玻璃层，避免它留在 `GONE` 子树里持有采样源。

**已实施：订阅页订阅源卡片玻璃化（`RssAdapter` 的 `item_rss`）**

- **为什么是壁纸域而不是内容域**：订阅页根布局透明、顶部搜索栏也已接入同一壁纸域玻璃，卡片下方直接就是主界面壁纸层（`decorView` 背景），没有需要采样的兄弟子树；与发现页书源名行同样是「卡片直接压在壁纸上」，因此走 `GlassSurface.rowBackground(host, WALLPAPER)`——同一 `FrostedGlassDrawable`、同一 `GlassPositionTracker` 绝对坐标对齐、同一四档降级，不新增任何材质参数。
- **形状**：圆角取 `ui_panel_radius`，与 XML `bg_ai_surface_panel` 的 corners 同值；卡片原有的 1dp `divider` 描边改由玻璃的全局描边键（`glassStrokeColor`）接管，未设置时不描边。
- **两种排布共用一套**：`RssAdapter` 的 1/2/3 列只改约束、内边距与字号，卡片根始终是同一个 `ConstraintLayout`；玻璃在 `convert()` 里统一挂一次，列数切换走 `notifyDataSetChanged` 时逐行重挂。
- **位置刷新**：`GlassPositionTracker` 自动查找最近的 `RecyclerView` 注册滚动回调，并在 `OnPreDraw` 逐帧校正采样坐标——宫格滚动、上方增删造成的整体位移都不会留下「玻璃停在旧位置」。
- **降级与回退**：墨水屏 / 低内存由 drawable 入口返回 null，`setBackgroundResource(bg_ai_surface_panel)` 回到原底色；无玻璃档由管线返回实底壳（底栏底色 + 1dp 描边），与发现页行同档观感。
- **设置变更重绑**：卡片 drawable 在 `convert()` 时按当时档位 / 强度 / 描边创建，设置页是独立 Activity，返回时 RecyclerView 不会自己重绑。`RssFragment.onResume` 比较 `GlassSurface.configSignature`，签名变化就整表重绑一次；现代布局不显示订阅源宫格，直接跳过。
- **不做**：文章列表条目（`item_rss_article*`）以封面图为主导、没有卡片底，叠玻璃只会压暗图片，维持现状。

**已实施：RSS 阅读页标题栏壁纸域玻璃（`ReadRssActivity`）**

- **为什么不是内容域**：正文整页由 WebView 独立绘制，不进父容器 `RenderNode`，把 `web_view_container` 当采样源只会录到空白；但这页**不需要采样正文**——`BaseActivity.imageBg` 默认为真，`decorView` 背景就是主题壁纸，标题栏这条区域背后正是壁纸。
- **同一份壁纸管线**：`GlassSurface.panelBackground(titleBar, GlassSamplingDomain.WALLPAPER, 0f)`，与 `SelectActionBar` / 书源调试页底栏完全同源（同一 `ExploreGlassBackdrop` drawable、同一 `GlassPositionTracker` 绝对坐标对齐、同一四档降级）。圆角 0：贴边全宽矩形。
- **不接管的两个早退**：无玻璃档（`resolvedExploreGlassMode() == SOLID`）直接返回，因为该档的实底壳取底栏底色，会让标题栏偏离全 App 统一的主题主色；墨水屏 / 低内存由 drawable 入口返回 null，保留 `TitleBar` 原有背景（含墨水屏描边）。

**已实施：阅读页 AI 浮窗 / 章节摘要浮窗玻璃化（`ReadAiFloatingPanel` / `ReadAiSummaryPanel`）**

- **结构**：两个面板都改为在自身 Compose 根 `Surface` 内部、正文 `Column` 之前放一个 `AndroidView` 承载 `StableLiquidGlassView`，用 `matchParentSize()` 与面板同尺寸、`Surface` 的 `clip(panelShape)` 负责圆角裁剪。`Surface` 只在玻璃就位时把 `color` 让成 `Transparent`，其余情况保持 `style.colors.background` 实底——与 `ModernActionPopup` 的「玻璃层 + 透明面板」同一接法，不需要再算一套面板矩形。
- **采样源**：`read_view`（阅读页正文），与面板同在本窗口的兄弟分支；面板/Compose 子树都不是源的祖先或自身，`selfOrAncestorContains` 守卫不触发。源解析复用 `contentLiquidGlassSpec`，solid 档 / API<33 / 低内存 / 墨水屏任一命中即 `panelGlass == null`，面板外观完全回到接入前。
- **绑定时机**：`open()` 时解析并进组合，`close()` 置空即从组合移除 → `onDetachedFromWindow` → `removeGlass()`；面板 `GONE` 期间也不绘制、不采样，不存在「收起后仍在每帧录制整页正文」。
- **可读性口径**：不再额外叠不透明底（叠了就看不见玻璃），文字直接压在材质自带的 tint 上——与主底栏「图标 / 文字压在玻璃上」同一条口径；如果观感上仍嫌透，调整的是全局档位预设，而不是给面板单独加一层遮罩（那会出现第二套材质参数）。

**已实施：共享搜索栏玻璃化（`io.legado.app.ui.widget.SearchView`）**

- **阻塞点的解法**：原阻塞点是 `SearchView.onLayout` 每次都调 `TopBarSearchStyle.apply(this)` 用 `GradientDrawable` 重设背景，任何外部设置的玻璃都会被下一次布局冲掉。现在背景解析收进控件内部：`updateSearchBackground()` 先问 `resolveGlassBackground()`，拿到玻璃就设玻璃、否则回退原药丸——`onLayout` 再频繁也只会在同一实例上做身份比较，不会重建管线。
- **显式采样域**：新增属性 `glassDomain`，默认 `GlassSamplingDomain.PANEL`（不接玻璃）。共享控件同时被壁纸页与弹窗使用（`dialog_cache_chapters` 里也是同一份 `view_search`），运行期无法判定背后是壁纸还是弹窗面板，因此由每个宿主显式声明；声明入口是 `applyGlassDomain(domain)`（对布局引用的 `androidx.appcompat.widget.SearchView` 做一次安全收窄，不是本控件则忽略）。采样域合法性仍由既有 `glassSurfaceDrawableAllowed` 纯函数表决定，不新增判定分支。
- **几何只换材质不换形状**：圆角直接取 `TopBarSearchStyle.radius()`（`UiCorner.searchRadius(18f)`，跟随全局圆角缩放），与被替换的药丸逐像素同形。
- **重绑**：`onLayout` 按 `GlassSurface.configSignature` 缓存 drawable；从设置页返回时窗口重新可见（`onWindowVisibilityChanged == VISIBLE`）再比一次签名，档位 / 强度 / 描边变化后自动换新 drawable 并重挂位置追踪器。
- **降级**：墨水屏 / 低内存由 drawable 入口返回 null，回退原半透明药丸；无玻璃档返回与全 App 行、底栏同档的实底壳（底栏底色 + 1dp 描边）。
- **已接入宿主（壁纸域）**：`RssFragment`（经典订阅页）、`ExploreFragment`（经典发现页）、`MyFragment`（设置搜索）、`RssSearchActivity`、`SearchActivity`（书搜索，根布局无 `TitleBar`）、`BaseImportBookActivity`、`SearchContentActivity`、`BookshelfManageActivity`、`BookSourceDebugActivity`、`RssSourceDebugActivity`、`HighlightRuleManageActivity`、`ReaderAssetManageActivity`。共同前提是根布局无底色、`TitleBar` 透明——`ThemeConfig.applyTheme` 每次应用主题都会把 `transparentNavBar` 置真，`TitleBar` 因此走 `Color.TRANSPARENT` 分支。
- **明确排除**：`CacheChapterDialog`（弹窗自身窗口没有 Activity 壁纸，保持默认 `PANEL`）；`BookSourceActivity` / `RssSourceActivity` / `ReplaceRuleActivity` 已改为 Compose 顶栏并 `titleBar.visibility = View.GONE`，XML 搜索栏不可见，也保持默认 `PANEL`。

**未接入项的接入设计（结构前置条件未满足，前置条件可做，单独成项）**

以下每一项都已定位到**具体阻塞点**与**接入路径**，不是「维持现状」的空结论：

- **键盘工具条 `KeyboardToolPop`**：阻塞点是**跨窗口**——它是独立 `PopupWindow`，窗口内找不到 Activity 的 `android.R.id.content`，`RenderNode` 录制无法跨窗口。两条路径：①（推荐）把它改成同窗口的覆盖层（挂在 DecorView 上、跟随输入法 inset），采样 `android.R.id.content`，代价是重做输入法弹层生命周期；②保留 `PopupWindow`，为它单独建一条「窗口快照」管线（`PixelCopy` 定时抓取下层窗口 + 硬件模糊），属于新的降级链，不与现有四档共用。
- **套件管理预览（`DiscoverySuiteManageActivity`）**：阻塞点是**没有可采样层**——预览面板的外观由自身 Compose 绘制（`panelImage` 可换成任意图），其下方没有任何真实内容子树，接折射只会画出被面板盖住的壁纸。接入路径：把预览的背景拆成「真实 View 兄弟节点」——面板自身负责内容，背后放一个承载实际选图的 `View`/`ImageView` 容器作为采样源（与书籍详情同一思路）；在此之前接入等于画错采样源。
- **音频 / 视频播放器面板**：阻塞点是**背景由自身绘制 + 跨窗口**——展开态背景是面板 Compose 内的 `CoverAtmosphereBackdrop`，收起态胶囊是 `TYPE_APPLICATION_OVERLAY` 独立窗口。接入路径：①展开态把氛围层从 Compose 内部搬到面板层的真实 `View` 兄弟节点，玻璃即可采样它；②收起态胶囊只能走「窗口快照」管线（同 `KeyboardToolPop` 路径②）。视频控制条另有播放器库自带 Surface，需在库布局外包一层同窗口容器。
- **书籍详情信息卡（`ll_detail_panel` / `ll_info_page` / `ll_detail_content_panel`）**：阻塞点是**没有合法 `ViewGroup` 采样源 + 可读性**——背后是 `bg_book`（`ImageView`，`bind` 只接受 `ViewGroup`），唯一同级容器 `vw_bg` 是面板的祖先（会被拒绝）。接入路径：把 `bg_book` 与 `vw_bg` 的 `#50000000` scrim 包进一个 `FrameLayout(@id/book_backdrop)`，三张卡片各挂一层玻璃采样它（与面板一起滚动、采样源静止）；同时要把卡片底色 `book_info_frost`（`#DDF1F2F6` / `#DD252528`，87% 不透明）让出，否则玻璃整块被盖住，而文字直接压在封面图上会明显降低可读性——这一步需要先定「封面压暗 + 文字描边 / 加厚 tint」的观感方案，属于独立的可读性设计项，不是接线工作量。
- **带 scrim 的通用对话框（含 `SourceLoginDialog`）**：阻塞点是**跨窗口 + 亮度不一致**——对话框在自己的窗口绘制，activity 侧无法采样；且玻璃会采到未压暗的内容，需要与侧边面板 0.42 补偿层同类的亮度补偿。接入路径：先做「窗口快照」管线（`PixelCopy` 抓取宿主窗口可见区域 + 复用硬件模糊），再按对话框 `dimAmount` 叠加补偿层；在没有这条管线前，把对话框做成玻璃只会显示为「不模糊的壁纸色块」。

## 5. 约束与风险

- **无新增用户可见偏好键**：所有阶段消费现有档位键与 `GlassMaterialPreset`；`tools/check_settings_mapping.py` 必须 PASS。
- **行内禁令不变**：RecyclerView 行内一律 drawable 路径；`StableLiquidGlassView` 只用于固定位置浮层。
- **降级链不变**：新接入面自动继承四档降级与设备判定，不写第二套判断。
- **性能边界**：单表面面积与底栏同量级可接受。列表行按「可见数量有界、滚动期采样成本为每行一条位图 shader 矩形」的口径走逐行玻璃——发现页书源名行、订阅源卡片（1–3 列宫格 / 单列列表）、书源 / 订阅源 / 规则 / 定时任务等管理列表、目录章节与书签行均由 LazyColumn / RecyclerView 只组合可见行（一屏 10–20 行），不录制列表子树；书架封面卡片行因封面解码与行高成本，仍不逐行玻璃化。搜索栏是每页一个、屏幕位置固定的药丸，位置追踪器只在真正位移时重录，列表滚动期间不产生逐帧重绘，成本远低于一行列表。
- **验证协议**：`./gradlew :app:testAppDebugUnitTest --tests "io.legado.app.ui.main.*" --tests "io.legado.app.ui.config.*" --tests "io.legado.app.ui.glass.*" --tests "io.legado.app.ui.widget.*"`、`:app:assembleAppDebug`、`python3 tools/check_settings_mapping.py`；真机观感由安装 debug 包核验。
