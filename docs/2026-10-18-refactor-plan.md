# 2026-10-18 重构与加固计划

> 本文件是**动手前的计划**，先对齐"做什么、不做什么、为什么"，再按第 6 节的顺序执行。
> 每个工作项完成后回到这里勾选，并补一句实际结果。

## 1. 背景

前几批做完崩溃证据链、性能基线、删除撤销补全、以及一轮"性能与稳定性盘点"后，
剩余的问题分成三类：**安全缺口**、**巨型文件**、**硬编码文案**。
其中安全问题最紧迫（Web 服务无鉴权），巨型文件最影响后续维护效率。

本计划只处理**收益明确、风险可控**的部分，并把风险高的项显式排除（见第 5 节）。

## 2. 目标

1. 给 Web 服务加**可选的访问口令**，堵住"同局域网任何人可增删书源、读取登录态"的缺口。
2. 把 `PageView.kt`（2821 行）里**低耦合的纯逻辑**拆成独立文件，为后续拆分探路并建立范式。
3. 处理少量确凿的硬编码中文（通知渠道名等），不大铺开。

## 3. 工作项

### W1 Web 服务访问口令（最高优先）

**现状**（已核实）：

- `WebService` 默认关闭（`PreferKey.webService` 无默认值 = false），需用户在「我的」手动开启。
- 开启后 `HttpServer`（`app/src/main/java/io/legado/app/web/HttpServer.kt`）用 `NanoHTTPD(port)`
  绑定所有网卡，默认端口 1122。
- `serve()` 里**没有任何鉴权**：`/saveBookSource`、`/deleteBook`、`/getBookSourceLoginInfo`
  等端点对任何能连上端口的人开放。
- 底层 `nanohttpd 2.3.1` 是 2016 年停更库，HTTP 解析层无安全补丁（见第 5 节，本次不替换）。

**方案：可选口令，默认关闭，开启后三种方式任一通过即可**

| 方式 | 用途 |
| --- | --- |
| URL 查询参数 `?token=xxx` | 用户首次打开页面用；服务端验证通过后 `Set-Cookie` |
| Cookie `legado_web_token` | 之后浏览器自动带，用户无感 |
| `Authorization: Bearer xxx` | 给脚本/第三方客户端用（前端已有此通道，见下） |

**关键设计：前端零改动。**

Web 前端（`modules/web/src`）已经有 `Authorization` header 机制（原本给反向隧道 relay 用），
且浏览器对**同源请求默认携带 Cookie**。所以只要服务端在验证 query token 通过后
`Set-Cookie`，后续所有请求都自动带上，不需要改前端、不需要重新打包 `assets/web`。

**具体改动**

1. `AppConfig` 新增 `webServiceToken: String`（默认空串 = 不启用，行为与现在完全一致）。
2. `HttpServer.serve()` 开头加校验：口令非空时，从 `Authorization` / `Cookie` /
   query `token` 三处取令牌，不匹配返回 401（JSON 错误体，不泄露细节）。
3. query token 验证通过时，响应加 `Set-Cookie: legado_web_token=...; HttpOnly; SameSite=Lax; Path=/`。
4. `WebService` 的通知与地址分享里，若启用了口令则附带 `?token=xxx` 的完整地址，
   否则用户复制出来的地址打不开（会 401）。
5. 设置入口：Web 服务相关设置里加"访问口令"（留空 = 不启用），并说明风险。

**验收**

- 不设口令：所有行为与现在逐字节一致（回归重点）。
- 设口令后：无令牌访问返回 401；带 `?token=` 首次访问成功且后续免令牌；
  换浏览器/清 cookie 后需重新带令牌。

**风险**

- 低。口令为空时校验代码直接短路，不影响现有用户。
- 需注意 `OPTIONS` 预检与静态资源（`/`、JS/CSS）也要能通过校验，否则页面打不开。

---

### W2 PageView 第一轮拆分

**调研结论**（已核实，决定拆哪些）：

- `PageView.kt` 2821 行，按职责分 A–J 十个区。
- **F 区（进阶标题 Lottie 装载，617–2005）与 G 区（页脚 tip 装载，2018–2390）
  深度依赖 PageView 的十几个私有状态数组、`binding`、`markSnapDirty()`、
  `parent as? ReadView`**，硬拆接近重写，**本轮不拆**。
- **H 区（标题 JSON 样式化，2393–2558）与 I 区（图片资产解码，2560–2775）
  只依赖构造参数和同包已存在的纯逻辑类**，可安全拆出。
- 项目已有成熟范式：`read/page/` 下的 `AdvancedTitleRequestGate.kt`、
  `AdvancedTitleLottieCache.kt`、`LottieImageMemoryPolicy.kt` 都是
  "同包 `internal` 顶层类 + 纯 JVM 单测"，`PageView` 已在 import 消费它们。
- **PageView 主体当前零自动化测试覆盖**，所以必须"先补测再搬"。

**方案：先补测，再搬两块，每块独立验证**

| 新文件 | 内容（源行号） | 说明 |
| --- | --- | --- |
| `read/page/LottieTitleJsonStyler.kt` | H 区：`applyLottieTextFallbackStyle`、`normalizeFullWidthImageLayers`、`scaleLottieTextStyle`、`parseColorArray`、`advancedTitleTextSizeSp/Scale/TextLayerScale`、`clearAdvancedTitleLoadingState` | `internal object`，纯函数：样式值 + rawJson → `StyledLottieJson` |
| `read/page/LottieImageAssetDecoder.kt` | I 区：`LottieImageAssetResolver`（去掉 `inner`，改构造注入）、`ResolvedLottieAssetSource`、`LottieAssetDecodeRequest`、`resolveLottieAssetSource`、`loadLottieAssetBitmap`、`decodeBitmapFileByType`、`decodeBitmapByType`、两个 `decodeRasterBitmap` | 现虽为 `inner class` 但实际只用构造参数，去 `inner` 即可独立 |

**执行顺序（每步都要编译 + 跑单测）**

1. 在 `app/src/test/.../read/page/` 为先搬的 H/I 逻辑补纯 JVM 单测
   （照抄 `AdvancedTitleLottieCacheTest` / `LottieImageMemoryPolicyTest` 范式）。
2. 搬 H → `LottieTitleJsonStyler.kt`，`PageView` 改为调用它。
3. 搬 I → `LottieImageAssetDecoder.kt`，构造参数化。
4. 每搬一块：`./gradlew :app:compileAppDebugKotlin :app:testAppDebugUnitTest`。

**必须保留的"看似 bug 但是行为"**（来自调研，搬运时随代码一起走）：

- `commitAdvancedTitleComposition` 里 2026-07-23/24 software rendering 回归的长注释。
- `init` 里 HarmonyOS 中端机 Lottie 首帧黑屏的注释。
- `upAdvancedTitleLottie` 的"图片解不出来由 Lottie 跳过，绝不因此把有效标题降级回纯文本"。
- 标题复用（committed / keysByPage）的双重校验——是为避免快速翻页闪烁，**不是死代码**。
- `fallbackView` 的 INVISIBLE/VISIBLE 与 `hasFailed` 门控。

**验收**

- 编译通过、新增单测通过、原有 236 个单测不回归。
- 真机验证：阅读页开页、快速翻页（看标题有无闪烁/错标题）、长图章节、进阶标题与页脚动效。

**风险**

- 中。主体无测试网，所以只搬纯逻辑块、且先补测。
- R1 漏搬 `markSnapDirty()` 会导致翻页截图不刷新——搬 I 区时要逐处核对。

---

### W3 硬编码中文（只做确凿小项）

**现状**：`app/src/main/java` 里 **≥485 个 kt 文件**含中文字面量，且
`app/build.gradle` 关掉了 `MissingTranslation` 检查，缺翻译连告警都没有。
全量抽取是体力活且用户并不需要多语言，**本批只做确凿、集中的少量项**：

- `App.kt` 通知渠道名（同函数其余渠道已用 `getString`，仅此处硬编码）。
- `ReadAloudSystemFloatingWindow.kt` / `ReadAiFloatingPanel.kt` 的悬浮窗按钮与状态文案
  （用户每天看得见，且集中在一处）。

其余留待以后按目录推进，不在本计划范围。

---

## 4. 本批不做（明确排除，防范围蔓延）

| 项 | 原因 |
| --- | --- |
| 替换 `nanohttpd` | 影响 Web 服务/上传/备份同步全链路，风险远大于收益；W1 的口令已覆盖主要风险面 |
| 拆 PageView 的 F/G 区 | 深度耦合十几个私有状态，硬拆接近重写；等有 UI 回归手段后再做（第 2 轮） |
| 拆 `ReadBook.kt`（2059 行 God Object） | 同上，需先建回归网 |
| 全量抽取硬编码中文 | 485 文件的体力活，收益（可本地化）与用户实际需求不匹配 |
| `RssArticlesAdapter1-4` 合并 | 四份复制粘贴但在用，合并不改行为、收益仅是维护性，优先级低于上面三项 |

## 5. 风险总表

| 风险 | 缓解 |
| --- | --- |
| W1 口令让现有用户打不开 Web 页 | 口令默认空 = 完全跳过校验；设置里明写"留空=不启用" |
| W1 Cookie 不被浏览器发送 | 同源请求默认带 Cookie；同时保留 query token 与 Authorization 两种兜底 |
| W2 搬代码碰坏阅读页渲染 | 只搬纯逻辑；先补单测锁定输入输出；每块独立编译+测试 |
| W2 真机才能发现的渲染回归 | 计划末尾列真机验收清单，发版前必须过一遍 |

## 6. 执行顺序

1. W1 Web 服务口令（安全，最优先）
2. W2 PageView 拆分（先补测 → 搬 H → 搬 I）
3. W3 硬编码中文小项
4. 全量编译 + 单测 + lint
5. 真机验收（见下）
6. 提交推送，更新本文件勾选结果

## 7. 真机验收清单（发版前必过）

- [ ] 不设口令：Web 服务开/关、浏览器打开书架、增删书源、上传，全部正常
- [ ] 设口令：无令牌 401；`?token=` 首次打开成功；刷新/跳转后免令牌；清 cookie 后重新要令牌
- [ ] 阅读页：开章、快速连续翻页（标题不闪、不错标题）、长图章节、进阶标题/页脚动效
- [ ] 朗读悬浮窗与 AI 面板文案正常（W3 抽取后无缺失）
- [ ] 日/夜切换后阅读页正常
- [ ] 翻页后进阶标题/页脚动效**继续播放**（动画冻结→恢复状态机，2026-10-20 新增）
- [ ] 滚动模式：标题跟着正文走，完全滚出视口的标题不残留
- [ ] 阅读进度：没读完时**不能显示 100%**（应为 99.9%），读完最后一页才显示 100.0%
- [ ] 换字号/行距后正文重新排版（排版键变化会触发重排）
- [ ] 快速翻章时不出现"上一章的内容盖在当前章"（章节加载竞态守卫）

## 8. 执行记录

**2026-10-18 全部完成，编译 + 单测全绿。**

- **W1 Web 服务口令 —— 已完成。** `AppConfig.webServiceToken`（默认空 = 不启用，
  旧行为逐字节不变）+ 新增 `web/WebServiceGuard.kt`（Authorization / Cookie /
  URL query 三种来源任一通过即放行，口令为空直接短路）+ `HttpServer.serve` 统一校验
  并在返回页面时种 `Set-Cookie`（**前端零改动**：同源请求自动带 Cookie）+
  `WebService.decorateAddress` 让通知与复制出来的地址自动附带 `?token=` +
  其它设置页新增「访问口令」入口。配 25 个单测。
  **测试抓到一个真 bug**：guard 原先按小写 `headers["cookie"]` 取，而浏览器发的是
  `Cookie`——口令会对不上、每次都被要求重新带参数。已改为大小写不敏感取值。
- **W2 PageView 拆分 —— 已完成（第一轮）。** 先补 45 个单测锁行为，再搬代码：
  H 区 → `LottieTitleJsonStyler.kt`（20 测），I 区 → `LottieImageAssetDecoder.kt`
  （10 测，去 `inner` 改构造注入）。PageView **2821 → 2465 行**。
  F/G 区（标题/tip 装载）按计划未动。`StyledLottieJsonCache` 随 H 区一起搬走
  （原本只有那里用）。踩坑：`TextPage` 在 `entities` 子包、`IHTTPSession` 是
  `NanoHTTPD` 嵌套接口、`ReadBookConfig.textSize` 是 Int、android 的 `org.json`
  在纯 JVM 下是 stub（需 Robolectric）。
- **W3 硬编码中文 —— 已完成（小项）。** 抽取 `App.kt` 通知渠道名「AI任务」、
  `ReadAloudSystemFloatingWindow` 13 处、`ReadAiFloatingPanel` 7 处。
  踩坑：Compose 里没有 `context` 变量要用 `LocalContext.current`；
  某些 `context` 是数据类字段而非 Context，要用 `appCtx`。
- **第 5 节"不做"清单全部遵守**：未替换 nanohttpd、未动 F/G 区、未拆 ReadBook.kt、
  未全量抽中文、未合并 RssArticlesAdapter。

### 遗留（后续批次）

- PageView 的 F/G 区（标题/tip 装载引擎）仍深度耦合十几个私有状态，属第二轮大工程。
  **2026-10-20 已把其中可纯逻辑化的判定全部搬进可测对象**（页面键、LRU、动画冻结/恢复、
  tip 请求门禁、标题保留判定、几何计算、滚动落位），阅读页包 105 个单测全绿，
  回归手段已具备，可以开始真正拆 F/G 区。
- `ReadBook.kt`（2059 行 God Object）同理：已先抽出章节加载竞态守卫
  （`model/ChapterLoadGuard.kt`）建好回归网，其余部分仍待拆。
- Web 服务口令只做了"可选开启"，未做"强制开启"——若认为默认就该有口令，
  可把 `webServiceToken` 改为首次开启服务时自动生成并提示用户。

### 2026-10-20 阅读页回归手段（F/G 区第二轮，铺垫批）

**目标**：给拆 F/G 区铺路——先把其中"纯逻辑"的策略抽成可测对象并用单测锁住，
只搬不改，行为保持逐条等价。

- **新增 `AdvancedTitlePageKeys.kt`**：`AdvancedTitlePageKeys.of(bookUrl, chapterIndex,
  pageIndex)`（页面位置键，必须带书名，章节名会重复）、`AccessOrderedLruCache`
  （访问顺序 LRU，读取即算使用）、`AdvancedTitlePageKeyCache`（页面位置 → 组合键，
  最多 24 条）。PageView 里的两个匿名 `LinkedHashMap` 子类（页面键表 24 条、
  已提交组合表 6 条）换成这两个类，淘汰语义不变。
- **新增 `AdvancedTitleAnimationFreeze.kt`**：翻页冻结/恢复动画的状态机
  （`beginFreeze` / `endFreeze` / `markTitleToResume` / `markTipToResume` /
  `consumeTitleResume` / `consumeTipResume` / `clearTipResume` / `clearResumePlan` /
  `reset`）。PageView 原来用「一个 Boolean + 两个 BooleanArray」表达的同一状态，
  现在收口成一个对象，"取计划即作废"的规则由单测锁住。
- **键格式规则收口到 `AdvancedTitleLottieKeys`**：新增 `rawComposition(w, h)` 与
  `matchesViewSize(key, w, h)`，替换 PageView 里手写的
  `"advanced_title:raw:$w:$h"` 与 `key.endsWith(":$w:$h")`。
- **单测 18 个**（`AdvancedTitlePageKeysTest` 10 + `AdvancedTitleAnimationFreezeTest` 8），
  阅读页包合计 **79 个单测全绿**。覆盖：跨书/跨章/跨页键不撞、LRU 淘汰与"读取即保活"、
  恢复计划用一次即作废、尺寸必须匹配才能复用组合。
- 踩坑：`AdvancedTitlePageKeyCache` 没有 `set` 操作符，PageView 里
  `map[k] = v` 写法编译不过，改成 `.put(k, v)`；自己写的第一条单测把
  "先读取 page0 再淘汰"的结果断言成淘汰 page0，实际 LRU 语义下该淘汰 page1，
  断言写错（不是实现错），已改成两条用例分别覆盖"满则淘汰最老"和"读过就保活"。

### 2026-10-20 阅读页回归手段（续：G 区 tip 门禁）

- **新增 `AdvancedTipRequestGate.kt`**：把 G 区原先内联在 PageView 里的
  "当前请求令牌 + 世代号"机制收口成类（`begin` / `accepts` / `hasCurrent` /
  `clear` / `clearAll`），`AdvancedTipRequestToken` 一并搬出（PageView 里的
  `private data class`）。PageView 的 `currentAdvancedTipRequests` 数组与
  `advancedTipRequestGeneration` 计数器随之删除。
- 保持等价的关键点：接受条件拆成"纯状态"（门禁内判定）与"环境条件"
  （View 的 tag、tip 是否启用、`AdvancedTipConfig.compositionCacheKey` 是否仍一致，
  仍在 PageView 里判定），**判定顺序不变**，因此"旧回调不得上屏"的语义不变。
- **单测 7 个**（`AdvancedTipRequestGateTest`），覆盖：旧世代令牌被拒、
  被替换的令牌被拒、两个槽位互不干扰、清空后全失效、请求号必须递增。
- 阅读页包合计 **86 个单测全绿**。

### 2026-10-20 阅读页回归手段（续：保留判定 + 几何计算）

- **新增 `AdvancedTitleRetentionPolicy.kt`**：把"翻页时这个标题槽位能不能留着不重建"
  的判定抽成纯策略。关键点是**身份比较（`!==`）而不是相等比较**：内容相同但不是同一
  个实例的两个 block 不能证明槽位画的就是这一页的标题，误保留会让一个页面的标题压在
  另一个页面上——比闪一下严重得多。条件仍要求"已呈现 + 有组合 + 可见"，顺序不变。
- **新增 `AdvancedTitleGeometry.kt`**：把标题的尺寸与位置计算抽成纯函数
  （`viewSize` / `translationX` / `translationY` / `scrollTranslationY`）。
  原来的三个私有方法要读 `binding.contentTextView` 的宽高，无法单测；抽出来后
  只需传数字。覆盖的规则：尺寸不塌缩到 0、居中偏移要减掉、双页右半页要加页偏移、
  纵向位置要夹在内容内、标题比内容还高时只能停在 0、滚动模式跟随内容不夹取。
- **单测 13 个**（`AdvancedTitleRetentionPolicyTest` 5 + `AdvancedTitleGeometryTest` 8），
  阅读页包合计 **99 个单测全绿**。

### 2026-10-20 阅读页回归手段（续：滚动模式标题落位）

- **新增 `AdvancedTitleScrollGeometry.kt`**：把滚动模式下高级标题的"落位 + 是否可见"
  规则抽成纯函数（`place` / `id`）。原逻辑内联在 `collectScrollAdvancedTitles` 里，
  规则是：位置 = 页面相对偏移 + 页内偏移，横向要减去居中偏移，
  **只有还与内容视口相交（哪怕只露出一像素）才显示**，与正文一致。
- **单测 6 个**（`AdvancedTitleScrollGeometryTest`），阅读页包合计 **105 个单测全绿**。
- 这一轮"铺垫批"到此把 F/G 区里能纯逻辑化的判定都搬进了可测对象：
  页面键、LRU 淘汰、动画冻结/恢复、tip 请求门禁、标题保留判定、几何计算、滚动落位。
  剩下的是与 View/协程强耦合的绑定流程本身，属于"真正拆 F/G 区"的大工程，
  现在已具备回归手段，可以开始动。

### 2026-10-20 ReadBook 回归网（章节加载竞态守卫）

- **新增 `model/ChapterLoadGuard.kt`**：把 `ReadBook.kt` 里"这次章节加载的结果还能不能用"
  的三条判定抽成纯规则（`isTokenCurrent` / `isRequestCurrent` / `isSlotCurrent`，
  另含章节窗口 `isInChapterWindow`）。原实现内嵌在 `ReadBook` 的三个私有方法里，
  直接读 `book` / `durChapterIndex` / `chapterLoadGenerations` /
  `chapterLoadingLayoutKeys` 等状态，无法单测。
- 抽出来后仍然读同一批状态、判定顺序不变，**语义逐条等价**：
  书不同 / 世代号不同 / 排版键不同 / 章节不在前后一页窗口内 → 一律判为过期；
  槽位还要额外满足"相对偏移正确"与"槽位里正是期望的 TextChapter"。
- **单测 9 个**（`ChapterLoadGuardTest`），覆盖换书、重复加载、排版变更、
  章节超出窗口、状态缺失、槽位偏移与槽位内容不匹配。
- 这一块是阅读器最经典的竞态（"翻页后旧章节内容盖上来"），现在有网可依。

### 2026-10-21 G 区拆分（PageView 2465 → 2106 行）

**第一次真正动 F/G 区。** 先拆 G 区（页眉页脚进阶动效装载），因为它只依赖两个 Lottie
视图和一批阅读器状态，不碰标题那十几个私有数组。

- **新增 `AdvancedTipLottieController.kt`（549 行）**：G 区整体搬出，含
  `upAdvancedTipLottie`（装载主流程）、`commitOrDefer`/`commit`（翻页期间延迟提交）、
  `prewarm`（首帧前预热）、`clear`/`clearAllSlots`/`dispose`、`applyChromeVisibility`、
  `refreshFieldsIfBound`、`viewsBound`、`accepts`（陈旧判定）、
  `PendingAdvancedTipComposition`/`PreparedAdvancedTipComposition`/`TipFieldTextDelegate`
  三个原私有类型。
- **宿主接口 `AdvancedTipHost`（15 个方法）**：控制器只通过它接触外部——
  `tipView`/`contentWidth`/`contentGeneration`/`isMainView`/`isEpubBook`/`batteryLevel`/
  `currentTextPage`/`bookName`/`bookAuthor`/`isHorizontalPageTurnActive`/`markSnapDirty`/
  `animationFreeze`/`clearAdvancedTitleLoadingState`/`setClassicTipFallbackVisible`/
  `defaultFontAssetDelegate`，外加一个共享状态 `var tipContext`。
- **关键取舍：`tipContext`（阅读信息：书名/章节/时间/电量/作者）仍留在 PageView。**
  经典文本 tip 与进阶动效包都从它渲染，是双向共享状态，搬进控制器会让
  `upTime`/`upBattery`/`applyProgressTexts` 反向依赖控制器。控制器通过
  `host.tipContext` 读写，单一来源不变。
- **`PageView` 侧**：删掉 5 个状态字段 + 12 个私有方法，`TipHost` 内部类实现宿主接口；
  翻页冻结/恢复动画（`freezeAdvancedTitleAnimationsForPageTurn` /
  `resumeAdvancedTitleAnimationsAfterPageTurn`）仍留在 PageView——它同时管标题和 tip，
  通过 `advancedTip.viewOf(slot)` / `advancedTip.hasKey(slot)` 访问 tip 侧。
- **只搬不改**：逐字复制，唯一改动是把环境访问换成 host 调用。判定顺序、`runCatching`
  边界、日志文案（含 "PageView advanced tip ..." 前缀）全部保持原样。
- 阅读页包 **111 个单测全绿**（新增的回归网在拆搬后立刻起了作用：漏掉
  `tipView`/`advancedTipKey` 两个访问器时，是编译错误而不是运行时 NPE 暴露的）。

### 2026-10-21 F 区状态收拢（AdvancedTitleSlotState）

**F 区主体还没搬，但先把"十几个私有状态数组"这个拆不动的根因消掉。**

- **新增 `AdvancedTitleSlotState.kt`**：把 F 区的 6 个数组 + 1 个 LRU + 3 个私有数据类
  收进一个对象——每槽位的待提交组合、图片解码任务、已应用的键/组合/渲染配置、
  绑定 block，以及按键保留的已提交组合（上限仍是 6）。三个原私有类型
  （`RenderConfig`/`PendingComposition`/`CommittedComposition`）随之一并搬出。
- **PageView 2106 → 2074 行**，28 处 `xxx[slot.ordinal]` 索引访问全部换成具名方法
  （`appliedKey(slot)` / `setApplied(slot, …)` / `boundBlock(slot)` / `committed(key)` …）。
  装载逻辑不再"按 ordinal 摸数组"，这是后续把 F 区函数搬出去的前提。
- 纯机械改写：先用脚本按出现次数断言替换（有一处 `= block` 出现 2 次、
  `= null` 因 `clearApplied` 要先整块替换否则计数对不上，都靠断言挡下来了），
  再编译验证。阅读页包 **111 个单测全绿**。

### 2026-10-20 阅读进度百分比（可测化）

- **新增 `entities/ReadProgressCalculator.kt`**：把 `TextPage.readProgress` 的计算搬到
  纯函数里（含"书没读完不许显示 100%"的那条 99.9% 特判），格式化仍用原来的
  `DecimalFormat("0.0%")`，行为不变。
- **单测 6 个**（`ReadProgressCalculatorTest`），覆盖空书、无分页、章节+页号混合、
  真正读完显示 100.0%、**没读完却四舍五入到 100% 时必须压回 99.9%**。
- 顺带记录一个**既有隐患**（本轮未改）：`DecimalFormat("0.0%")` 用的是默认语言环境，
  非英语地区小数点/百分号可能变成 "99,9%"，那么 `percent == "100.0%"` 的比较会失配、
  99.9% 的特判不生效。改它属于行为变更，留到有明确需求时再单独处理。
