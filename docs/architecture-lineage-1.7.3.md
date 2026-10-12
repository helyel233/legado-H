# H.1.7.3 版功能与接口上下游谱系

> 整理日期：2026-10-11
> 基准提交：`08348a785`（1.7.3 版，2026-10-08）
> 用途：作为 V5 转正与旧壳退役时的功能/接口清单底册。

**基准说明**：比对确认，1.7.3 之后代码只新增了 V5 宿主壳（AppHost / HostSearch / HostRssSort / AppUiConfig / LayoutCenter / SkinCenter / ThemeCenter / MainPage 共 8 个 Activity），**功能层与对外接口层（data / service / api / web / manifest 旧组件）与当前代码完全一致**，本谱系即 1.7.3 真实谱系。

---

## 一、全景分层图

```
外部世界（桌面 / Scheme / 分享 / 耳机 / Provider / Web 客户端 / 中继服务器）
        ↓
入口层：Activity / Service / Receiver / Provider（manifest 注册）
        ↓
中游层：ViewModel → ReadBook / WebBook / CacheBook / ReadAloud / AutoTask 等单例引擎
        ↓
规则层：AnalyzeRule + AnalyzeUrl + JsExtensions（书源/订阅源/替换/字典/TTS 规则同源引擎）
        ↓
数据层：appDb(Room 40+ DAO) ｜ SharedPreferences(AppConfig/ThemeStore/AiProvider) ｜ 文件(BookHelp/主题包/备份)
```

**核心规律**：
1. 所有规则类引擎同源（`AnalyzeRule` / `AnalyzeUrl`）；
2. 对外数据暴露统一收敛在 `api/controller` 五个 Controller；
3. 播放类统一 media3 ExoPlayer（视频走 GSY 内嵌 Exo2 内核）；
4. 持久化三分：Room / SharedPreferences / 文件。

---

## 二、对外接口谱系（外部 → App）

| 接口 | 触发方式 | 入口类 | 下游处理链 |
|---|---|---|---|
| 桌面图标×8 | MAIN/LAUNCHER | WelcomeActivity、Launcher1-7 | → MainActivity；`defaultToRead` 时 → ReadBookActivity（读 appDb.bookDao.lastReadBook） |
| S-Pen 遥控 | REMOTE_ACTION 翻页键 | ReadBookActivity / ReadMangaActivity | onKeyDown → 翻页 → ReadBook（WebBook/BookHelp） |
| 分享/处理文本 | SEND、PROCESS_TEXT + text/plain | SharedReceiverActivity | 链接→MainActivity；≥300 字→TextBookImporter 文本成书→ReadBookActivity；否则→SearchActivity→WebBook；快捷方式 readAloud→朗读 |
| 一键导入 | `legado://`、`yuedu://` scheme | OnLineImportActivity | 按 path 分发 8+ 个 Import*Dialog（书源/订阅源/替换规则/主题/字典/HttpTTS/txt目录/阅读配置/段落规则包/气泡包）→ appDb / ReadBookConfig |
| 文件打开 | VIEW（txt/json/epub/pdf/mobi/azw3/zip/rar/7z/red）+ SEND zip | FileAssociationActivity | 归档解包→规则 JSON→importJson 判型；书籍文件→LocalBook.importFile→ReadBookActivity（content uri 先复制到书架目录） |
| 跳转确认 / 验证码 | 内部调用（JsExtensions.openUrl / SourceVerificationHelp） | OpenUrlConfirmActivity / VerificationCodeActivity | 确认→浏览器/WebView；验证码回填 CacheManager→书源重试 |
| ContentProvider | `content://{pkg}.readerProvider` | ReaderProvider | bookSource / rssSource / book 增删查 → 五 Controller → appDb / WebBook；onCreate 同时注册 3 个桌面快捷方式 |
| Web 服务 | 局域网 `http://ip:1122` + WS:1123 | WebService → HttpServer | POST/GET 路由→五 Controller；未匹配路径→assets 内嵌网页；WS 支撑网页端搜索/书源/订阅源调试 |
| Web 快捷磁贴 | QS_TILE | WebTileService | 启停 WebService |
| 公网 Relay | 加密隧道（BOOT_COMPLETED 自启） | RelayBootReceiver → RelayService → RelayClient | RelayReadDispatcher 白名单 7 条路由（/getBookshelf、/getChapterList、/getBookContent、/getBookContentEx、/getReadConfig、/getBookCover、/saveBookProgress）→ BookController，带内容消毒（去正文图/封面限书架内） |
| 耳机键 | MEDIA_BUTTON | MediaButtonReceiver | 翻章 / 朗读暂停续播 / 无活动时拉起 lastReadBook 朗读；有阅读页时经 EventBus 转发 |
| 小组件×2 | APPWIDGET_UPDATE + 自定义 UPDATE 广播 | ReadGoalWidgetProvider / ReadRankWidgetProvider | readRecord(Daily)Dao + ReadRecordWidgetStore；由 ReadRecordDailyHelper 写库后刷新（30 秒防抖）；点击→ReadRecordActivity |
| 缓存通知按钮 | 内部广播 cache.PAUSE/RESUME/CANCEL | CacheCoordinatorActionReceiver | CacheCoordinator → 缓存/导出任务暂停/继续/取消 |
| 自动任务时钟 | BOOT / MY_PACKAGE_REPLACED / TIME_SET / TIMEZONE_CHANGED | AutoTaskBootReceiver | AutoTaskService.restoreSchedule 重建闹钟 |
| 权限弹窗 | PermissionsCompat → Request.start() | PermissionActivity | 统一运行时权限申请（存储/通知/电池优化/悬浮窗/所有文件），回调 onGranted/onDenied |
| 系统 TTS / 文字转发 | queries 声明（TTS_SERVICE / PROCESS_TEXT） | TTSReadAloudService 等 / TextActionMenu | 系统 TTS 引擎绑定（6 处调用点）；阅读页选中文本→queryIntentActivities 转发外部 App |

---

## 三、核心功能链谱系

### 1. 阅读主链（最重）

```
ReadBookActivity → ReadBookViewModel → ReadBook 单例（真正的中游枢纽）
  ├─ 网络正文：WebBook.getContentAwait → BookContent → AnalyzeRule / AnalyzeUrl / JsExtensions
  ├─ 本地解析：LocalBook / EpubFile（epubcore 会话系）/ TextFile / MobiFile / PdfFile / UmdFile
  ├─ 正文加工：ContentProcessor（净化+替换规则）→ ParagraphRuleProcessor → ChapterProvider 排版
  ├─ 图像：ImageProvider（封面/插图缓存）
  └─ 服务联动：
      ReadAloud → TTSReadAloudService / HttpReadAloudService
      CacheBook → CacheBookService
      AudioPlay → AudioPlayService
      MediaButtonReceiver（耳机键）
      ReadRecordDailyHelper（记录时长 → 刷 Widget）
```

关键文件：`ui/book/read/ReadBookActivity.kt`、`ui/book/read/ReadBookViewModel.kt`、`model/ReadBook.kt`、`help/book/ContentProcessor.kt`、`help/book/BookHelp.kt`、`model/localBook/*`。

### 2. 书架

`ui/main/bookshelf/BaseBookshelfFragment`（style1/style2/compose 快照层）→ `BookshelfViewModel`（URL 添加书籍、书源匹配）→ `appDb.bookDao / bookGroupDao` 直读。

### 3. 书源引擎（被 10+ 功能消费）

```
WebBook 门面（searchBook / exploreBook / getBookInfo / getChapterList / getContent / preciseSearch / runPreUpdateJs）
  └─ 解析器：BookList / BookInfo / BookChapterList / BookContent
  └─ 规则引擎：AnalyzeRule / AnalyzeUrl / RuleAnalyzer / AnalyzeByJSonPath|JSoup|Regex|XPath
  └─ JS 环境：JsExtensions / RegexJsExtensions / JsWebSocketManager
  └─ 多源并发：SearchModel（搜索协程池）
消费方：搜索、发现、详情、正文、缓存、换源、书源校验(CheckSourceService)、
       书源调试(BookSourceDebugActivity)、书架添加、API 层 BookController
```

### 4. 搜索

- 在线多源：`SearchActivity → SearchViewModel → SearchModel → WebBook.searchBook`；DB：SearchBookDao、SearchKeywordDao。
- 本地全库：`LibrarySearchActivity → LibrarySearchViewModel → LibrarySearchIndexer → LibraryContentFtsDao`（FTS 索引实体 LibraryContentFts）。

### 5. 缓存与导出

- 缓存：`CacheActivity → CacheViewModel（统计）→ CacheBookService → CacheBook → WebBook.getContent + BookHelp 落盘 + CacheManifestHelper`。
- 缓存管理：`CacheManageActivity`（删文件、WebDav 任务入口）→ `WebDavTaskService → AppWebDav`。
- 导出：`BookCacheExportActivity → BookCacheZip`；`ExportBookService`（TXT 导出，用 ContentProcessor/LocalBook）；`BookmarkMarkdownExporter`；`ReviewCacheService`（评论快照 ReviewSnapshotManager）。

### 6. 备份恢复

- `ui/config/BackupConfigFragment → help/storage/Backup.kt`（AES 加密 BackupAES、打包 BackupPrefsFile）+ `Restore.kt`（+ RestoreSnapshot）。
- 云端通道：`AppCloudStorage`（统一门面）→ `WebDavCloudStorageBackend` / `S3Container`（S3 容器加密备份还原）；WebDav 直连 `AppWebDav` + `lib/webdav/WebDav`。
- 远程书架：`RemoteBookActivity → RemoteBookViewModel → RemoteBook / RemoteBookWebDav → AppWebDav → LocalBook` 导入。

### 7. 换源

`ChangeBookSourceDialog / ChangeChapterSourceDialog → ChangeBookSourceViewModel → WebBook.preciseSearch（候选）+ getChapterList/getContent（校验）→ appDb.bookDao/bookChapterDao 更新 → BookHelp 清旧缓存`。

### 8. RSS / 订阅

- 上游：`RssFragment / RssSortActivity / ReadRssActivity / RssFavoritesActivity / RssSourceActivity|Edit|Debug / RuleSubActivity`。
- 引擎：`model/rss/Rss.kt`（正文获取编排）+ `RssParserByRule`（复用 AnalyzeRule 体系）+ `RssParserDefault`；JS 桥 `RssJsExtensions` / `WebJsExtensions`。
- 渲染：`ReadRssActivity → WebViewPool / PooledWebView`（loadDataWithBaseURL）。
- DB：RssSource、RssArticle、RssStar（收藏）、RssReadRecord、RuleSub 各自 Dao；调试走 `RssSourceDebugWebSocket`。

### 9. 媒体播放

- 音频：`AudioPlayActivity → AudioPlayViewModel → AudioPlay → AudioPlayService`；内核 media3 ExoPlayer（`ExoPlayerHelper.createHttpExoPlayer`）；预加载 VideoBookPreloader、MediaCacheTaskManager。
- 视频：`VideoPlayerActivity → VideoPlayerViewModel → VideoPlay → VideoPlayService`；内核 GSYVideoPlayer（内嵌 Exo2）；画中画/悬浮窗 `FloatingPlayer`。

### 10. TTS 朗读

```
阅读页（ReadAloudDialog / PlayerPanel / 系统悬浮窗）
  → ReadAloud 门面（IntentAction 指令，startForegroundService）
  → BaseReadAloudService（MediaSessionCompat + 通知 + MEDIA_BUTTON）
      ├─ TTSReadAloudService（系统 TTS）
      └─ HttpReadAloudService（media3 ExoPlayer + SimpleCache + SSE 按句队列 TtsQueueWindow）
辅助：help/readaloud/*（SpeechPlanner / LoudnessManager / BGM）、AiReadAloudRoleService（AI 分角色）
规则：HttpTTS 实体（书源式 URL 规则）+ HttpTTSDao；导入 ImportHttpTtsDialog
```

### 11. AI 体系

- 入口：`AiChatActivity / AiImageGalleryActivity / MainAiFloatingBall`；配置 `AiProviderManage|Edit / AiImageProviderManage|Edit / AiWorldBookManage / AiConfigFragment`；阅读内用量记录。
- 引擎：`AiChatService`（OkHttp SSE 流式，兼容 `/chat/completions` 与 `/responses`，fetchModels）；Agent 运行时 `AiAgentRuntime / AiAgentPlanner / AiToolExecutor / AiToolRegistry`（工具：书架/书源/世界书/Tavily 搜索/MCP `AiMcpClient`）；生图 `AiImageService / AiImageGalleryManager`；朗读 `AiReadAloudRoleService / AiReadAloudBgmService`。
- 存储：Provider 配置不进 Room，JSON 存 SharedPreferences（`PreferKey.aiProviderList`）；会话/记忆(AiMemoryItem+Fts)/生图/用量/Agent 任务存 Room；常驻 `AiTaskKeepAliveService`。

### 12. 自动化任务

`AutoTaskActivity / Edit / Debug → model/AutoTask（Cron 协议 AutoTaskProtocol/Schedule/Execution）→ AutoTaskService（API 35+ AlarmManager 精确闹钟；旧版协程轮询）→ AutoTaskRule 实体 + AutoTaskRuleDao`；导入迁移 AutoTaskImport / AutoTaskLegacyMigration；开机经 AutoTaskBootReceiver。

### 13. 统计报表

`ReadRecordActivity / ReadRecordFragment / YearlyReportActivity → YearlyReportViewModel → YearlyReportBuilder / YearlyReportImageRenderer（分享图）→ ReadRecordDao / ReadRecordDailyDao`；写库统一收敛 `ReadRecordDailyHelper`（写库即刷两个 Widget）；RSS 记录另有 RssReadRecordDao。

### 14. 字典 / 角色卡

- 字典：`DictRuleActivity / DictDialog`；引擎内嵌于 `DictRule` 实体（`search(word)` 用 AnalyzeUrl 请求 urlRule、AnalyzeRule 解析 showRule）→ dictRuleDao。
- 角色卡：`BookCharacterManage / Edit / Card / Relation` 四页 → BookCharacterIdentityMigrator / AiBookCharacterTool → BookCharacter、BookCharacterRelation 实体。

### 15. 主题 / 外观

- 入口：`ThemeManageActivity / SkinCenterActivity / ThemeConfigFragment`。
- 管理：`ThemeConfig / ThemePackageManager / ThemePackageStore / ThemeAssetStore`。
- 运行：`lib/theme/`（ThemeStore / ThemeRepositoryImpl / ThemeWriterImpl / SpThemePersistence / ThemeReaderBridge / ThemeUiPalette）+ 独立模块 `modules/theme`（ThemeApplier / SeedPalette / ThemePackageSpec / ThemePersistence）+ `modules/uikit`（AppTheme/AppColorScheme）。
- 持久化：SharedPreferences + 文件（主题包目录）；导入复用 ImportThemeDialog / ImportRedThemeDialog。

---

## 四、数据层总表

| 层 | 内容 |
|---|---|
| **Room**（`data/AppDatabase`，全局单例 appDb） | 核心实体：Book、BookGroup、BookSource(Part)、BookChapter、Bookmark、SearchBook、ReplaceRule、RssSource/Article/Star/ReadRecord、HttpTTS、TxtTocRule、DictRule、ReadRecord(Daily)、SearchKeyword、Cache、Cookie、LibraryContentFts、RuleSub、SourceRecycleBin、AutoTaskRule、AI 系列（AiMemoryItem/Fts、AiGeneratedImage、AiAgentJob 等）、角色卡系列（BookCharacter/Relation）等 65 个实体文件、40+ DAO（`data/dao/`） |
| **SharedPreferences** | AppConfig（全局偏好）、ThemeStore / SpThemePersistence（主题）、AiProviderConfig（AI 配置）、ReadBookConfig（阅读样式） |
| **文件** | BookHelp（正文/图片缓存目录）、CacheManifestHelper（缓存清单）、CacheManager（KV 缓存）、主题包 / AI 世界书 / 备份包、scheme_import_cache.json |

**主要帮助/引擎类**：BookHelp、ContentProcessor、ParagraphRuleProcessor、ReplaceAnalyzer、AnalyzeUrl、AnalyzeRule、JsExtensions、AppCloudStorage、AppWebDav、Backup、Restore、LocalBook 系。

---

## 五、谱系特征（供 V5 迁移/转正参考）

1. **对外暴露收敛层**：ReaderProvider、HttpServer、Relay 三条对外通道共享 `api/controller` 五 Controller——但 Relay 有白名单+内容消毒，Provider/HttpServer 近乎全量暴露。转正/安全审查时是重点。
2. **V5 已接管部分**：1.7.3 后新增的 HostSearch / HostRssSort / AppHost 只替换呈现层，全部直连同一批 ViewModel/引擎，谱系中游未动。
3. **单例枢纽**：ReadBook、WebBook、ReadAloud、CacheBook、AutoTask 五个 model 层单例是几乎所有 UI 的中游枢纽，旧壳退役时必须原样保留（V5 也依赖它们）。
4. **备份兼容铁律对应点**：1.7.3 的 SP 键（ThemeStore/PreferKey）+ Room schema + 文件布局（BookHelp 目录结构）正是覆盖升级必须保留的三份"真相"。
