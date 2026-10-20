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

- PageView 的 F/G 区（标题/tip 装载引擎）仍深度耦合十几个私有状态，属第二轮大工程，
  需先有 UI 回归手段。
- `ReadBook.kt`（2059 行 God Object）同理。
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
