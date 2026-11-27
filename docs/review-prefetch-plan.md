# 段评快照：预热窗口 / 自动切换 / 纯快照模式 施工单

> 交接文档。给执行方（另一个 harness）的施工单：写清必改点、边界、验收方式，不含完整实现代码。
> 代码基线：`main` @ `bc2b86358`（2026-11-27）。文中行号都是该提交的。
> 动手前先 `git log --oneline -3` 与 `git log -3 -- <文件>` 复核；若这些文件已有新提交，以代码为准重新定位（函数名比行号可靠）。

---

## 1. 背景

阅读页的段评/评论快照体系：评论页抓成离线 HTML 存本地（`ReviewSnapshotStore`），点段评图标时优先用快照秒开，后台再联网刷新。

用户提出的 6 条偏好（原话要点）：

1. 更希望评论预热把全部段评逐个抓齐（不是只抓几个）。
2. 后台联网拿到最新评论时，希望**无论如何立刻自动切换**（哪怕正在往下翻页，能还原滚动位置即可）。
3. 切到纯快照模式后，点到本地没有快照的段落，**严格零联网**，直接提示无快照。
4. 纯快照模式下，阅读页的后台自动预热**继续跑**。
5. 平时看的包含章评/书评或自定义段落规则（pclick）。
6. 频繁翻章 / 弱网场景不多但固定有（地铁、电梯）。

本单只解 1、2、3、4、6；第 5 条里的"章评/书评"见 §4 明确排除；pclick 已支持，无需改动。

---

## 2. 现状事实（已逐条核对，可直接采信）

| # | 事实 | 位置 |
|---|---|---|
| 1 | 预热窗口硬编码"当前章 + 下一章"，KDoc 却写"上一章/本章/下一章"，注释与代码不一致 | `help/review/ReviewWindowPrefetcher.kt:63-84`（`:69` 是 `centerIndex + 1`），KDoc 在 `:31-34` |
| 2 | 预热触发点全仓只有一处：章节内容加载完成且 `relativePosition == 0` | `ui/book/read/ReadBookActivity.kt:2308` → `:2321`；EPUB core 模式在 `:2277-2296` 直接 return，不预热 |
| 3 | 抓拍完全串行：章循环 × 按钮循环；单按钮上限 110s（解析 20s + 抓拍 90s） | `ReviewWindowPrefetcher.kt:75-83`、`:186-209`、常量 `:40-41` |
| 4 | 每次调用 `prefetch()` 先 `prefetchJob?.cancel()`，翻章即取消上一轮 | `ReviewWindowPrefetcher.kt:65` |
| 5 | 打开模式只有二值 `network` / `snapshot_first`；历史值 `snapshot_only` 被**两处**归一到 `SNAPSHOT_FIRST` | `help/config/AppConfig.kt:52-65`；`ui/config/CommentConfigFragment.kt:50-59` |
| 6 | 自动切换是手动的：内容变化只挂顶部提示条，点条才切换 | `ui/widget/dialog/BottomWebViewDialog.kt:1046-1050`、`:1397`、`:1415` |
| 7 | 切换时的滚动还原**已经写好**，不需要新写 | 同文件 `:1415-1437`、`restorePendingScroll` `:1354-1382` |
| 8 | 内容无变化时只更新保鲜期时间戳，不重绘 | `:1032-1043` → `ReviewSnapshotStore.touchSavedAt:572` |
| 9 | CONTENT_CHANGED 时**不回写快照**（全文件无 `ReviewSnapshotStore.put`） | 同上 |
| 10 | 章评/书评保留键只有常量与读取过滤，**全仓无写入方**（自 C1 移植 `1b41dda21` 起就如此） | `ReviewSnapshotStore.kt:131-139`，`legadoSupplementSection` 只出现在 `ReviewSnapshotRefreshPolicy.kt:29` |
| 11 | 抓拍失败记 5 分钟内存冷却（断网期间反复失败 → 出网后还要干等） | `ReviewWindowPrefetcher.kt:266-288`（`COOLDOWN_MS=5min`） |
| 12 | `help/cache` 里已无 review 轨道，`ReviewWindowPrefetcher` 是唯一评论调度器 | `help/cache/CacheCoordinator.kt` 无 review 引用 |
| 13 | "抓齐"的机制本来就有：data 引擎游标分页（上限 200 页）、web 引擎展开/滚到底 | `ReviewDataCapture.kt:149-176`、`ReviewPaginationPolicy.kt`、`ReviewSnapshotCapture.kt:818+` 的 `expandJs` |
| 14 | `fetchOnlineHtml` 只判断 body 非空，不校验是不是评论页 | `help/book/BookImgClick.kt:536-550` |
| 15 | 刷新判定在"当前有评论卡片、新页没有且有 script"时**判为 CONTENT_CHANGED**（语义是"无法确认相同，按变化处理"） | `ReviewSnapshotRefreshPolicy.kt:69-75` |
| 16 | `isValidCommentHtml` 只看长度/是否有 `<`，壳页也能过 → **不能**用作自动切换的门 | `ReviewSnapshotCapture.kt:132-142` |

结论：缺的不是"抓不到全"，而是"调度"；以及自动切换/回写缺一道**内容有效性门**。

---

## 3. 要做的：3 件事 + 1 行

按 A → B → C → D 顺序做，每项一个提交，可单独 revert。

### A. 预热窗口 + 调度（解偏好 1）

**A1 窗口**：`centerIndex .. min(centerIndex + min(AppConfig.preDownloadNum, CAP), chapterSize - 1)`，`CAP = 3`（常量放 `ReviewWindowPrefetcher` 内）。
- 为什么要有 CAP：全量预热成本 = 章内段评数 × 章数 × 单次抓拍。用户把 `preDownloadNum` 设成 10 时会一次要跑 10 章的全部段评。
- `preDownloadNum = 0`：窗口只有当前章（关闭预热仍走总闸 `syncCacheReview`，不新增开关）。
- 顺序：当前章优先，再 +1..+N。保证每次翻章至少把手上这章跑完。
- 顺手修 KDoc（`ReviewWindowPrefetcher.kt:31-34`），别让下一个人再被注释误导。

**A2 调度**：用 `Channel<Unit>(Channel.CONFLATED)` 表达"最新窗口"，一个常驻消费者惰性启动（首次 `prefetch()` 时 `launch`，`cancel()` 时退出）。
- 触发只做 `trySend(Unit)`，不再 `prefetchJob?.cancel()`。
- 消费者每轮醒来重读 `ReadBook.durChapterIndex` 与章节表，按窗口从当前章往后逐章、章内逐按钮处理；处理中若来了新窗口，**不打断**，等当前按钮跑完再进入下一轮（典型 <10s，最坏 ≤110s）。
- 保留现有全部去重与跳过逻辑，一行都别动：`inFlight` 单飞 `:225-246`、失败冷却 `:266-288`、已完成且未过期跳过 `:191-194`。
- `cancel()` 只在阅读页销毁时调用（已有 `ReadBookActivity.kt:6025`），语义从"取消本轮"变成"关闭消费者"。
- 边界：`prefetch()` 内部用的是全局 `ReadBook.chapterSize` / `durChapterIndex`，跨书切换时可能与传入 `book` 不一致 → 进循环前比对 `ReadBook.book?.bookUrl == book.bookUrl`，不一致就丢弃本轮（现有 `ReadBook.preDownload` 有同样写法，照抄）。

> 说明：翻章时正在跑的**单个**抓拍会跑完（≤110s）才切下一轮。这是刻意接受的代价——用取消逻辑换这点时效，是整个方案里最容易出竞态的地方。
> `Channel.CONFLATED` 在本仓库已有先例：`ui/widget/image/CoverImageView.kt:90`。

### B. 纯快照模式（解偏好 3，并保住偏好 4）

**B1 新常量**：`ReviewOpenMode.OFFLINE = "offline"`。
- **不要**复用历史值 `"snapshot_only"`：它在 `AppConfig.kt:62` 与 `CommentConfigFragment.kt:52-58` 两处已被映射成 `SNAPSHOT_FIRST`，复活它会让那批老用户的行为静默跳变（从"后台刷新"变成"不刷新"）。用新值 = 零迁移代码、老用户零感知。
- 老值映射保持现状，不要改。

**B2 谓词**：只在 `BookImgClick` 里加一处 `private fun isOfflineOnly() = openMode() == AppConfig.ReviewOpenMode.OFFLINE`，不要在三个调用点各写一遍判断。

**B3 三处分支**（`BookImgClick.kt`）：
- `clickImg` `:350-372`、`clickImgSnapshotFirst` `:312-330`、`oldClickImg` `:380-425`。
- `SNAPSHOT_FIRST` 与 `NETWORK` 分支逻辑**不变**。
- `OFFLINE`：只尝试 `openSnapshotIfCached(..., refreshToNetwork = false, offlineOnly = true)`；未命中 → toast"无本地快照"并 return，**不得**落到 `openNetwork` `:429`。
- `clickImgSnapshotFirst` 的返回类型 `ReviewSnapshotConsumption` 需要新增一个"已提示、不再联网"的值（或让调用方按模式判断）——这是唯一需要改调用方的地方，`ReadBookActivity.kt:4755` 附近按新值分支。
- 命中但过期：**保持现状**（`offlineOnly` 分支会调 `refreshButton` 后台补抓，`:227-238`）。它先过 `canRun()` 受总闸与"仅 WiFi 预热"约束，这正是偏好 4 要的；不要把它改成"零联网"，那会把偏好 4 一起改掉。

**B4 UI**（两处都要加，否则入口不一致）：
- `ui/config/CommentConfigFragment.kt:41-49` 的 `openModeOptions` 加一项。
- `ui/book/read/ReadBookActivity.kt:2383-2400` 的 `showReviewOpenModeDialog` 加一项。
- 字符串：`res/values/strings.xml`（英文）+ `res/values-zh/strings.xml`（中文）各加一条，命名沿用 `menu_review_open_mode_*`。其余语种回退英文即可。

### C. 自动切换 + 回写（解偏好 2）

**C1 门（必须先有门，再谈自动）**：只有"新页面含评论卡片"才允许切换与回写。
- 判据：`Jsoup` 选择器 `[data-comment-id], .comment-card` 非空（与 `ReviewSnapshotRefreshPolicy.kt:69` 同一个选择器）。
- 实现建议：在 `ReviewSnapshotRefreshPolicy` 里加 `internal fun isCommentPage(html: String?): Boolean`，一处实现、两处使用（切换判断、回写判断）。
- 为什么不能用 `isValidCommentHtml`：见 §2 第 16 条，壳页能过。
- 为什么必须有：`fetchOnlineHtml` 不校验页面（§2 第 14），刷新判定把无评论的 JS 壳页当"有变化"（§2 第 15）。不加门，"无论如何立刻切换"会把好快照当场换成登录页/挑战页；若同时回写，坏页落库，之后每次打开都是坏页。弱网（地铁）下拿到半截页面的概率最高。
- 加 `shortcut:` 注释：`shortcut: 只认静态评论卡片节点，JS 驱动型源永不自动切换，出现整站壳页化时升级`。

**C2 切换**：`BottomWebViewDialog.kt:1046-1050` 的 CONTENT_CHANGED 分支改为：过门 → 直接 `applyPendingNetworkRefresh()`（`:1415`，它已含滚动还原 `:1419-1432`），不调 `showUpdateBanner()`；未过门 → 保持现状（挂条或静默）。
- 保留切完的 toast（`:1425`），去掉"等用户点条"的等待。

**C3 回写**：切换成功后把新 HTML 存回，避免"每次打开同一段评都重抓一次"直到保鲜期（默认 3 天）。
- 用现有 API，不新增存储层方法：`ReviewSnapshotStore.get(book, chapter, buttonSrc)`（`:380`）→ `copy(html = 新HTML, savedAt = now)` → `put(book, snapshot)`（`:298`）。
- 需要的三样东西在对话框里都已存在：`reviewResourceBook`、`snapshotChapterUrl`、`snapshotButtonSrc`（`:1029-1043` 已在用）。
- 必须与切换共用同一道门；写失败只记日志，不影响已切换的展示。

### D. 离线守卫（解偏好 6，1 行）

`ReviewWindowPrefetcher.canRun()` `:171-176` 第一行加：

```kotlin
if (!NetworkUtils.isNetworkAvailable()) return false
```

掉网时抓拍根本不启动 → 不会记 5 分钟冷却（§2 第 11）；复网后下一次翻章自动续。**不新增** `NetworkChangedListener`。

---

## 4. 明确不做（附理由，防止执行方自行加码）

| 不做 | 理由 |
|---|---|
| 有界并发（2~3 路） | 没有任何测量证明"当前章都抓不完"；建 WebView 本身是 50~200ms 主线程成本（`ReadBookActivity.kt:2331` 自述），并发是拿阅读流畅度换预热速度。先按 §6 量一次再谈。 |
| "滑动窗口只取消滑出窗口的任务" | 需要任务注册表 + 窗口差集取消，是本方案里代码最多、竞态最密的部分；被 A2 的 CONFLATED 通道整体取代。 |
| `NetworkChangedListener` 挂起/续抓 | 被 D 的一行取代（掉网不启动 = 不冷却），复网由下一次翻章自然触发。 |
| 回写历史版本 / 保留旧快照对照 | 用户没要求；增加存储与淘汰逻辑。 |
| 胶囊"快照 · 同步中"状态 | 自动切换是毫秒级动作，状态几乎看不到，白增一套 UI 状态机。 |
| 弹窗内一键切模式 | 低频动作，三点菜单已有入口；不要在 WebView 弹窗里加交互面。 |
| 章评/书评快照 | 保留键从未有写入方（§2 第 10），属新功能不是回归；需要先确定抓取身份（哪段 URL 当 chapter、哪个 buttonSrc），单独立项。 |
| Direct EPUB 接入预热 | EPUB core 模式走的不是 `BookImgClick` 评论按钮链路，能不能"补一行触发"未经验证；先不动。 |
| 备选方案 H：不做预热、点击即抓 + 骨架屏 | 工程上比本单小一个数量级（`captureFromClick` 链路与 `warmUpCommentWebView` 都已存在），但违背偏好 1。若将来"省流量/省电"比"抓齐"更重要，应整体切到 H 而不是在本单上叠。 |

---

## 5. 测试（新增非平凡逻辑必须有）

遵循仓库现状：纯函数单测放 `app/src/test/java/io/legado/app/help/review/`，参考 `ReviewWindowPrefetcherPolicyTest.kt`（已用 `AtomicInteger` + `CompletableDeferred` 写法验证单飞）。

必须覆盖：

1. **窗口边界**（纯函数化后测）：首章、末章、`preDownloadNum = 0`、`preDownloadNum = 10`（应被 CAP 夹到 3）、`chapterSize = 1`。
2. **不重复抓**：同一窗口连续触发两次，同一 key 的抓拍次数 = 1。
3. **纯快照未命中**：断言走"提示 + 返回"，不进入任何网络路径（把门做成纯函数后直接断言分支结果，不要起 WebView）。
4. **内容门**：空串 false、`<html><body>error</body></html>` false、含 `.comment-card` true、含 `data-comment-id` true。

命令（**不要跑全量**）：

```bash
./gradlew :app:testAppDebugUnitTest --tests "io.legado.app.help.review.*"
```

全量 `:app:testAppDebugUnitTest` 在本仓库有 ~48 例既有基线失败（Robolectric / `appCtx` 未初始化，19 个类），不是回归。若必须跑全量，只比对"失败类集合"是否与基线一致。

若改了设置项，另跑仓库自带的设置映射检查：

```bash
python3 tools/check_settings_mapping.py
```

---

## 6. 先做的一次测量（20 分钟，决定 A 够不够）

在现有"窗口预取"日志旁（`ReviewWindowPrefetcher.kt:196`，`AppLog.putDebug`）加一行：窗口章数、按钮数、单章总耗时。真实源读 2 章后判读：

- 单章 ≤ 60s → A 足够，并发永久不做。
- 单章 > 3min → A 只是"不再反复推倒重来"，不等于"抓得齐"；此时需要另开议题降低**单按钮**抓拍成本（例如复用已展开页面的 HTML），在本单里加并发只是把慢分摊到两个 WebView。

---

## 7. 验收清单

- [ ] 连翻 3 章：日志显示每章窗口跑完；同一按钮不重复抓。
- [ ] `preDownloadNum = 10`：日志里窗口仍是 3 章。
- [ ] 纯快照模式 + 飞行模式：点无快照段落 → 只有提示，无 WebView 网络请求（抓包或网络日志确认）。
- [ ] 纯快照模式 + WiFi：预热仍在跑（`syncCacheReview` 开）。
- [ ] 缓存优先打开过期快照、内容确有新评论 → 自动切换，滚动位置基本不动。
- [ ] 构造坏页（改 host / 断源 / 返回空 body）→ 不自动切换，旧快照没被覆盖。
- [ ] 单测：新增用例全过；若跑全量，失败类集合与基线一致。
- [ ] 4 个提交各自可单独 revert，revert 后无残留半状态。

---

## 8. 术语

| 术语 | 含义 |
|---|---|
| 段评 | 正文里带评论的段落/气泡（`ReviewButton`） |
| 章评 / 书评 | 评论页内的分级 tab；本仓库目前只有保留键、无抓取链路 |
| 快照 | 评论页抓下来的离线 HTML（`ReviewSnapshot`） |
| 预热 | 后台把快照提前抓好，让点击零等待 |
| 窗口 | 一轮预热覆盖的章节区间：当前章 .. 当前章+N |
| 门 | 判断"新抓到的页面是不是真的评论页"的校验 |
| 回写 | 自动切换后把新页面存回快照库 |
