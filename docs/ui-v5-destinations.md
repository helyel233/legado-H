# legado-H UI V5 页面清单与搬迁工单（P0-3）

- 状态：**P0 交付**（配合 `docs/ui-v5-skeleton-first.md` 2.3 / `docs/ui-v5-impl.md` P4）
- 日期：2026-10-11
- 代码基线：`app/src/main/java/io/legado/app/ui/` 下 **104 个 Activity**、23 个 Fragment、38 个 Screen

**这份表是 P4 的工单。** 每一行都有归属，没有漏网的页面——要么搬进新骨架，要么自动套
新外框（`LegacyScreenHost`），要么冻结不动，要么被新体系取代而删除。

## 1. 分级定义

| 级 | 含义 | 处理期 |
|---|---|---|
| **F** | 冻结（红线） | 全程不动 |
| **D** | 删除（被新体系取代） | 删除窗口 |
| **?** | 待裁决（功能保全询问制） | 需用户裁决 |
| **1** | 一级（完整搬进新骨架） | P4 第一批/第二批 |
| **2** | 二级（LegacyScreenHost → 逐步升级） | P4 第三批 |
| **3** | 三级（长尾） | P4 第四批 |

判据一句话：

- **会不会动到读书体验？** 会 → F 冻结
- **新体系有没有更好的东西取代它？** 有 → D 删除
- **用户每天 / 每周会用到吗？** 会 → 1 级，完整搬进新骨架
- **用户每月会用到吗？** 会 → 2 级，先套壳再升级
- **其余** → 3 级，套壳即可，观感照样统一

## 2. 冻结（红线）（15 个）

> 冻结 ≠ 丢弃：这些页面的导入 / 导出 / 渲染照常可用，本轮任何清理都把它们当黑名单。

| 页面 | 规模 | 说明 |
|---|---|---|
| `book/audio/AudioPlayActivity.kt` | 18 KB | 冻结红线，一行不改 |
| `book/manga/ReadMangaActivity.kt` | 36 KB | 冻结红线，一行不改 |
| `book/read/BaseReadBookActivity.kt` | 17 KB | 冻结红线，一行不改 |
| `book/read/ReadBookActivity.kt` | 266 KB | 冻结红线，一行不改 |
| `book/read/config/AiReadAloudUsageRecordActivity.kt` | 13 KB | 冻结红线，一行不改 |
| `book/read/config/HighlightRuleEditActivity.kt` | 16 KB | 冻结红线，一行不改 |
| `book/read/config/HighlightRuleManageActivity.kt` | 20 KB | 冻结红线，一行不改 |
| `book/read/config/ParagraphRuleEditActivity.kt` | 12 KB | 冻结红线，一行不改 |
| `book/read/config/ParagraphRuleManageActivity.kt` | 19 KB | 冻结红线，一行不改 |
| `book/read/config/ReadAloudBgmManageActivity.kt` | 48 KB | 冻结红线，一行不改 |
| `book/read/config/ReadMenuButtonManageActivity.kt` | 30 KB | 冻结红线，一行不改 |
| `book/read/config/ReadMenuCustomButtonEditActivity.kt` | 7 KB | 冻结红线，一行不改 |
| `book/read/config/ReaderAssetManageActivity.kt` | 25 KB | 冻结红线，一行不改 |
| `book/read/config/ReaderAssetPreviewActivity.kt` | 5 KB | 冻结红线，一行不改 |
| `video/VideoPlayerActivity.kt` | 38 KB | 冻结红线，一行不改 |

## 3. 删除（被新体系取代）（10 个）

> 删除前先从导航摘除入口、确认一版无回归，再物理删除（手册 0.3）。

| 页面 | 规模 | 说明 |
|---|---|---|
| `config/AppUiConfigActivity.kt` | 4 KB | P3 · 旧 UI 设置入口，由新 UI 设置页取代 |
| `config/AppearanceKitActivity.kt` | 28 KB | P3 · 旧外观套件，由界面包取代 |
| `config/AppearanceKitEditActivity.kt` | 15 KB | P3 · 旧套件编辑，由包内编辑器取代 |
| `config/LayoutCenterActivity.kt` | 7 KB | P3 · 旧界面中心，合并进管理中心 |
| `config/NavigationBarManageActivity.kt` | 54 KB | P1 · 旧底栏管理（54KB），由界面包 nav 旋钮取代 |
| `config/SkinCenterActivity.kt` | 15 KB | P3 · 旧管理中心外壳，用新组件重写 |
| `config/ThemeCenterActivity.kt` | 12 KB | P3 · 旧主题中心，合并进管理中心 |
| `config/ThemeManageActivity.kt` | 112 KB | P3 · 旧主题管理（111KB），由管理中心取代 |
| `config/TopBarManageActivity.kt` | 36 KB | P1 · 旧顶栏管理（36KB），由 ScreenChrome 取代 |
| `main/MainActivity.kt` | 87 KB | P4-5 · 由 AppHostActivity 取代（2141 行整体退役） |

## 4. 待裁决（功能保全询问制）（2 个）

> **需要你裁决**：现状有两套实现，新体系只允许一套。选定前两套都保留。

| 页面 | 规模 | 说明 |
|---|---|---|
| `book/explore/ExploreShowActivity.kt` | 8 KB | 发现页两套（ExploreFragment / 本页）待裁决取一 |
| `main/explore/DiscoverySuiteManageActivity.kt` | 77 KB | 发现管理两套待裁决取一 |

## 5. 一级（完整搬进新骨架）（10 个）

| 页面 | 规模 | 说明 |
|---|---|---|
| `about/ReadRecordActivity.kt` | 29 KB | 阅读记录 |
| `book/bookmark/AllBookmarkActivity.kt` | 4 KB | 书签 |
| `book/info/BookInfoComposeActivity.kt` | 42 KB | 书籍详情：大标题折叠 + 封面共享元素 + 容器变换 |
| `book/manage/BookshelfManageActivity.kt` | 18 KB | 书架批量管理：玻璃选中态 |
| `book/search/SearchActivity.kt` | 26 KB | 搜索：新搜索框 + 结果骨架屏 + 历史芯片 |
| `book/searchContent/SearchContentActivity.kt` | 10 KB | 书内搜索 |
| `book/toc/TocActivity.kt` | 6 KB | 目录 |
| `config/ConfigActivity.kt` | 8 KB | 设置宿主：P5 重写（12 个 Fragment 并入） |
| `main/bookshelf/BookCollectionActivity.kt` | 30 KB | 书架集合：5 种布局档位落地页 |
| `main/readrecord/YearlyReportActivity.kt` | 9 KB | 年度报告 |

## 6. 二级（LegacyScreenHost → 逐步升级）（53 个）

| 页面 | 规模 | 说明 |
|---|---|---|
| `about/AboutActivity.kt` | 2 KB | 关于 |
| `autoTask/AutoTaskActivity.kt` | 13 KB | 自动任务 |
| `autoTask/AutoTaskDebugActivity.kt` | 4 KB | 任务调试 |
| `autoTask/AutoTaskEditActivity.kt` | 14 KB | 任务编辑 |
| `book/cache/BookCacheExportActivity.kt` | 3 KB | 缓存导出 |
| `book/cache/CacheActivity.kt` | 24 KB | 缓存 |
| `book/cache/CacheManageActivity.kt` | 26 KB | 缓存管理 |
| `book/character/BookCharacterCardActivity.kt` | 4 KB | 角色卡 |
| `book/character/BookCharacterEditActivity.kt` | 20 KB | 角色编辑 |
| `book/character/BookCharacterManageActivity.kt` | 5 KB | 角色管理 |
| `book/character/BookCharacterRelationActivity.kt` | 8 KB | 角色关系 |
| `book/import/local/ImportBookActivity.kt` | 13 KB | 本地导入 |
| `book/import/remote/RemoteBookActivity.kt` | 8 KB | 远程导入 |
| `book/info/edit/BookInfoEditActivity.kt` | 10 KB | 书籍信息编辑 |
| `book/source/debug/BookSourceDebugActivity.kt` | 7 KB | 书源调试 |
| `book/source/edit/BookSourceEditActivity.kt` | 36 KB | 书源编辑（36KB 纯 XML） |
| `book/source/manage/BookSourceActivity.kt` | 51 KB | 书源管理 |
| `book/toc/rule/TxtTocRuleActivity.kt` | 15 KB | TXT 目录规则 |
| `config/AdvancedTipManageActivity.kt` | 19 KB | 气泡包（资产冻结，页面套新壳） |
| `config/AdvancedTitleManageActivity.kt` | 16 KB | 高级标题包（同上） |
| `config/AiImageProviderEditActivity.kt` | 10 KB | AI 图床编辑 |
| `config/AiImageProviderManageActivity.kt` | 18 KB | AI 图床 |
| `config/AiProviderEditActivity.kt` | 19 KB | AI 服务商编辑 |
| `config/AiProviderManageActivity.kt` | 14 KB | AI 服务商 |
| `config/AiWorldBookManageActivity.kt` | 4 KB | AI 世界书 |
| `config/BubbleManageActivity.kt` | 28 KB | 气泡管理 |
| `config/CoverCollectionDetailActivity.kt` | 5 KB | 封面集详情 |
| `config/CoverCollectionManageActivity.kt` | 12 KB | 封面集 |
| `config/EpubLoadingTemplateManageActivity.kt` | 9 KB | EPUB 模板 |
| `config/EpubLoadingTemplatePreviewActivity.kt` | 3 KB | EPUB 模板预览 |
| `config/LibraryContainerManageActivity.kt` | 27 KB | 备份容器 |
| `config/RelaySettingsActivity.kt` | 34 KB | 中转设置 |
| `config/S3ContainerManageActivity.kt` | 12 KB | S3 容器 |
| `config/ShareNoteTemplateManageActivity.kt` | 14 KB | 分享模板 |
| `dict/rule/DictRuleActivity.kt` | 14 KB | 字典规则 |
| `file/FileManageActivity.kt` | 5 KB | 文件管理 |
| `main/ai/AiChatActivity.kt` | 49 KB | AI 对话（51KB） |
| `main/ai/AiImageGalleryActivity.kt` | 14 KB | AI 图集 |
| `main/bookshelf/BookshelfTagManageActivity.kt` | 13 KB | 书架标签 |
| `main/bookshelf/smartgroup/SmartGroupRuleActivity.kt` | 9 KB | 智能分组 |
| `main/librarysearch/LibrarySearchActivity.kt` | 10 KB | 书库搜索 |
| `replace/ReplaceRuleActivity.kt` | 21 KB | 替换净化 |
| `replace/edit/ReplaceEditActivity.kt` | 8 KB | 替换编辑 |
| `rss/article/RssSearchActivity.kt` | 4 KB | RSS 搜索 |
| `rss/article/RssSortActivity.kt` | 21 KB | RSS 排序 |
| `rss/favorites/RssFavoritesActivity.kt` | 6 KB | RSS 收藏 |
| `rss/read/ReadRssActivity.kt` | 34 KB | RSS 阅读（34KB 纯 XML） |
| `rss/source/debug/RssSourceDebugActivity.kt` | 6 KB | RSS 调试 |
| `rss/source/edit/RssSourceEditActivity.kt` | 24 KB | RSS 源编辑 |
| `rss/source/manage/RssSourceActivity.kt` | 23 KB | RSS 源管理 |
| `rss/subscription/RuleSubActivity.kt` | 11 KB | 订阅规则 |
| `source/recycle/SourceRecycleBinActivity.kt` | 1 KB | 书源回收站 |
| `welcome/WelcomeActivity.kt` | 3 KB | 欢迎 / 启动页 |

## 7. 三级（长尾）（14 个）

| 页面 | 规模 | 说明 |
|---|---|---|
| `association/FileAssociationActivity.kt` | 11 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `association/OnLineImportActivity.kt` | 15 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `association/OpenUrlConfirmActivity.kt` | 1 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `association/VerificationCodeActivity.kt` | 1 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `book/import/BaseImportBookActivity.kt` | 5 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `browser/WebViewActivity.kt` | 26 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `code/CodeEditActivity.kt` | 14 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `config/coverhtml/CoverHtmlActivity.kt` | 14 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `debug/RegexTestActivity.kt` | 2 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `file/HandleFileActivity.kt` | 14 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `image/ImageCropActivity.kt` | 14 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `login/SourceLoginActivity.kt` | 1 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `main/MainPageActivity.kt` | 1 KB | 长尾：走 LegacyScreenHost 自动套新外框 |
| `qrcode/QrCodeActivity.kt` | 2 KB | 长尾：走 LegacyScreenHost 自动套新外框 |

## 8. 主界面 6 Tab 登记表（AppDestination）

现状问题：Tab 清单有三处真相 —— `MainBottomNavConfig.specs`、MainActivity 手写的
id→Fragment 映射、侧栏是底栏菜单的镜像。新骨架改为**一张表**，宿主从它生成一切。

| # | id | 标题 | 来源 Fragment | 默认可见 | 侧栏分组 | 备注 |
|---|---|---|---|---|---|---|
| 1 | `shelf` | 书架 | `main/bookshelf/style2/BookshelfFragment2.kt` | 是（锁定） | MAIN | 书架三实现取一；5 种布局档位 + 进度条 + 继续阅读卡 |
| 2 | `home` | 主页 | `main/homepage/HomepageFragment.kt` | 否 | MAIN |  |
| 3 | `explore` | 发现 | `main/explore/ExploreFragment.kt`（158KB，最大） | 是 | MAIN | 发现页两套待裁决（见第 4 节） |
| 4 | `rss` | 订阅 | `main/rss/RssFragment.kt` | 否 | MAIN |  |
| 5 | `stats` | 统计 | `main/readrecord/ReadRecordFragment.kt` | 否 | MAIN |  |
| 6 | `my` | 我的 | `main/my/MyFragment.kt` | 是（锁定） | MAIN | UI 设置入口挂这里：我的 → UI 设置 → 管理中心 |

现状默认可见 3 项（书架 / 发现 / 我的），对应 `MainBottomNavConfig.NAV_3TAB_DEFAULT`；
其余 3 项在新骨架里走 `hiddenTabs()` 作为"我的"页二级入口 —— 这是注册表自带能力，不需要额外代码。

## 9. P4 搬迁批次

```text
P4-0  LegacyScreenHost     → 所有未搬迁页面自动套新外框（第一天就统一观感）
P4-1  第一批：6 个 Tab      → 书架 / 主页 / 我的 / 发现 / 订阅 / 统计
P4-2  第二批：详情 + 搜索   → 书籍详情 / 搜索 / 目录 / 书签 / 阅读记录 / 年度报告
P4-3  第三批：管理中频      → 书源 / RSS / 备份 / 替换净化 / AI / 自动任务 / 角色
P4-4  第四批：长尾          → 逐个升级；未升级的继续走 LegacyScreenHost
P4-5  旧壳退役              → MainActivity / activity_main.xml / pref_config_*.xml 物理删除
```

每批一个提交、一次可回归，**随时可停**：停在任意一批，App 的观感都是统一的。

## 10. 统计

| 级 | 数量 |
|---|---|
| F 冻结（红线） | 15 |
| D 删除（被新体系取代） | 10 |
| ? 待裁决（功能保全询问制） | 2 |
| 1 一级（完整搬进新骨架） | 10 |
| 2 二级（LegacyScreenHost → 逐步升级） | 53 |
| 3 三级（长尾） | 14 |
| **合计** | **104** |
