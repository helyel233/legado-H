# legado-H 加固与用户体验优化规划

> 目标：盘点当前代码库中仍可做的工程加固（安全 / 稳定性 / 并发一致性 / 性能）与用户体验优化细项，形成可排期清单。
> 范围：聚焦 app 模块（help、model、service、ui、web、utils、lib）。纯 V5 主题迁移类问题不在此列（已冻结 v5 分支、main 不再做旧页 V5 化）。
> 依据：代码静态探索（grep + 逐文件核查）+ 历史工作台账中的遗留 / 未做 / 已知问题。每条均给严重度与证据路径，便于直接排期。

---

## 0. 总览与建议优先级

| 优先级 | 方向 | 代表项 | 收益 |
|--------|------|--------|------|
| P0 安全 | 敏感凭据加密存储 | WebDav 密码 / S3 密钥 / AI Key 明文落 SP | 防设备遗失 / 越权读取 |
| P0 安全 | Web 服务默认安全 | 未设口令时仅绑回环 + 引导开启口令 | 堵局域网越权增删书源 / 读登录态 |
| P1 稳定 | CrashHandler 二次崩溃 | 已崩线程 Looper.loop() + sleep + 弹完整栈 | 防崩溃后连环崩、骚扰用户 |
| P1 性能 | Web 服务线程串行化 | runBlocking 阻塞 NanoHTTPD 单线程 | 提吞吐、防超时 |
| P1 稳定 | runBlocking 在主线程 / binder 线程 | JS 扩展、ContentProvider binder | 防 ANR |
| P2 并发 | 共享可变状态可见性 | WebService.isRun/hostAddress、SourceConfig.removeSource 前缀误删 | 防数据错乱 |
| P2 性能 | 弹幕 / 封面图片加载线程池化 | 每请求新建 Thread、高画质封面不缓存 | 防卡顿 |
| P2 UX | 设置开关碎片化 + 调试独占 | recordHeapDump 等仅调试页可达 | 让用户找得到功能 |
| P3 UX | 无障碍 / 排版细节 / 本地化 | 阅读页无 contentDescription、进度百分比本地化 | 细节打磨 |
| P3 测试 | 扩大单测 + CI 门禁 | lint 仍 continue-on-error、大量模块零测试 | 防回归 |

已处理（务必先读，避免重复劳动）：登录页 / 浏览器页 / 封面集合等 V5 换壳、系统切日夜间换肤、书架封面内存缓存（CoverMemoryCache）、RSS 文章页 ViewHolder 崩溃、Web 服务口令鉴权 + WebSocket 鉴权 + fail-closed（#58/#59）、CronetCoroutineInterceptor.waitForDone 改 IOException 兜底（#53）、崩溃落盘同步写入（LocalConfig.edit(commit=true)，#56）。以下清单不含上述已闭环项。

---

## 1. 安全加固

### 1.1 [P0] 敏感凭据明文存储 → 迁移加密存储
- 现状：webDavPassword、s3SecretKey/s3SessionToken、aiApiKey/aiTavilyApiKey 等直接存 SharedPreferences 明文，仅备份时才加密。设备遗失或越权读取可拿到全部云盘 / AI 凭据。
- 证据：help/config/AppConfig.kt 行 2498、lib/cloud/WebDavCloudStorageBackend.kt 行 61、AppConfig.kt 行 2682、lib/cloud/S3ContainerManager.kt 行 216、AppConfig.kt 行 2114 与 1455。
- 建议：复用已落地的 service/relay/RelaySecurity.kt（AndroidKeyStore + AES-GCM）做加密层，或迁移 EncryptedSharedPreferences。先梳理所有 *-password/*-secret/*-key 类 PreferKey，统一收口到加密 KeyStore 读写封装。

### 1.2 [P0] Web 服务未设口令时仍绑全网卡
- 现状：WebServiceGuard.isAuthorized 在 expected 为空时直接 return true；服务默认绑 0.0.0.0。用户开了 Web 服务但没设口令，同局域网可读书源、getBookSourceLoginInfo、删书 / 删源 / 存进度。
- 证据：web/WebServiceGuard.kt 行 47、web/HttpServer.kt 行 45。
- 建议：未设口令时监听地址约束为 127.0.0.1（回环），并在通知 / 设置页明确提示未设口令仅本机可访问；或开启 Web 服务时强制要求先设口令。

### 1.3 [P2] WebView 混合内容 / 文件·内容访问放宽（2026-10-28 已部分收紧）
- 现状：多处 MIXED_CONTENT_ALWAYS_ALLOW，以及 allowFileAccess=true/allowContentAccess=true 配合用户可控 HTML（书源 / 笔记渲染）。
- **已做**：allowContentAccess 已在 6 处收紧为 false（WebViewPool、BookInfoUseWebHost、BookInfoComposeRoute、ShareNoteImageRenderer、CommentWebViewSession、SelectionWebSearchDialog 显式设置）——content:// 是页面 JS 读取其它应用 Provider（通讯录/媒体）的真实隐私面，书源/快照/笔记场景不存在合法 content:// 资源。
- **刻意保留（看似 bug 但是行为，勿再收紧）**：a) MIXED_CONTENT_ALWAYS_ALLOW——书源/EPUB 直读的图片与 CSS 大量是 http 资源，NEVER_ALLOW 会直接打断用户可见功能；b) allowFileAccess=true（BookInfoUseWebHost/ShareNoteImageRenderer）——本地封面、分享笔记本地资源必需；c) EpubDirectWebLayer 的 sandbox 有 nonce 门控，content 访问已按模板条件收敛，属刻意设计。

### 1.4 [P2] 外部 Intent / DeepLink 校验专项审计
- 现状：处理外部 intent 的入口 74+ 处（OnLineImportActivity、FileAssociationActivity、OpenUrlConfirmActivity、ReadRssActivity 等），部分有二次确认，但 URL/file URI 校验需集中审计，避免 getData() 未校验即加载。
- 建议：抽一个统一入口做 URL 白名单 + 危险 scheme（file:、content:、javascript:）拦截，各落地页复用。

### 1.5 [P3] 401 响应体结构对齐前端
- 现状：unauthorizedResponse 返回 {"msg":...}，与前端期望的 ReturnData 结构不一致（axios 走 rejected，仅提示不友好）。#59 已修协商 HTML，但 JSON 体未对齐。
- 建议：返回体与正常响应同构（带 code/msg/data），前端零改动即可友好提示。

---

## 2. 崩溃 / 稳定性风险

### 2.1 [P1] CrashHandler 二次崩溃风险
- 现状：handleException 在已崩溃线程上做 longToastOnUiLegacy(stackTraceStr) + Thread.sleep(3000)，并对后台线程调用 Looper.loop() 试图吸住崩溃——若该线程本无 Looper 或主线程状态已损坏，会二次崩溃；且把完整堆栈弹给普通用户不友好。
- 证据：help/CrashHandler.kt 行 57-66 与 106-107。
- 建议：崩溃后只发本地通知 / 落盘，不要对已崩线程 Looper.loop()；主线程崩溃走系统默认机制或受控重启；用户提示改为已记录崩溃日志，可在关于页查看 / 复制诊断信息（#53 已加诊断导出，正好衔接）。

### 2.2 [P1] runBlocking 阻塞 HTTP 服务 / ContentProvider / JS 扩展线程（已取证，维持现状）
- 现状：HttpServer POST 分支整体 runBlocking；ReaderProvider（ContentProvider binder）runBlocking；JsExtensions.ajaxAll/BackstageWebView 等 runBlocking(context)。
- 证据：web/HttpServer.kt 行 65-87、api/ReaderProvider.kt 行 80、help/JsExtensions.kt 行 200/219/291/324/359、data/entities/BaseSource.kt 行 295-316。
- **取证结论（2026-10-28，勿重复评估）**：
  1. NanoHTTPD（maven 依赖 fi.iki.elonen）的 DefaultAsyncRunner 是**每连接一线程**模型，serve 里的 runBlocking 只阻塞该连接自己的线程，不存在「单线程串行」问题；仅剩无界建线程的次要开销，与上游 legado 一致，不改。
  2. ReaderProvider 仅服务于外部 app 导入（Manifest exported），binder 线程池阻塞可容忍；改成 fire-and-forget 会破坏调用方「insert 返回即完成」的语义，不改。
  3. JsExtensions 的 runBlocking 是同步 JS API 的契约（书源规则本身就是阻塞式求值）；真正的防线是「JS 求值不发生在主线程」，BaseSource.refreshExplore/refreshJSLib 已有 isMainThread error() 防御。不改。

### 2.3 [P2] 非空断言 NPE 风险
- 现状：HttpReadAloudService.kt 行 636 与 644 用 lifecycleScope.coroutineContext[Job]!!，理论上可 NPE。
- 建议：改安全调用 ?. + 早返回，或直接用 lifecycleScope 避免取 Job。

### 2.4 [P3] 匿名 Handler 内存泄漏面
- 现状：全仓 59 处 Handler(，部分在 Activity/Fragment 内未 removeCallbacksAndMessages(null) 即销毁，可能泄漏页面。
- 证据：base/adapter/RecyclerAdapter.kt、help/AppFreezeMonitor.kt、help/TTS.kt、ui/book/read/ReadBookActivity.kt 等。
- 建议：用 LeakCanary 跑一轮，对泄漏项改静态 Handler + WeakReference，或在 onDestroy 统一清消息。

---

## 3. 并发与数据一致性

### 3.1 [P2] WebService 共享可变状态无可见性保证
- 现状：companion object { var isRun=false; var hostAddress="" } 被服务线程、网络回调、UI 多线程读写，普通 var 无 @Volatile/锁。
- 证据：service/WebService.kt 行 42-43。
- 建议：加 @Volatile 或用 AtomicBoolean/AtomicReference，关键切换加锁。

### 3.2 [P2] SourceConfig.removeSource 前缀匹配误删评分
- 现状：sp.all.keys.filter { it.startsWith(origin) }.forEach { remove(it) } —— 若源 URL 是另一个源 URL 的前缀（如 a.com 与 a.com.cn），会误删对方评分。
- 证据：help/config/SourceConfig.kt 行 32-42（已逐行确认当前仍为前缀匹配）。
- 建议：评分 key 改为带分隔符（如 origin 加空格）再 startsWith，或在删除时校验完整 key 与带分隔符前缀两种匹配，避免跨源误伤。（历史台账 #55 已标注为已知遗留。）

### 3.3 [P3] AtomicFile 使用不一致
- 现状：仅 review/*、config/* 共 9 处用 AtomicFile，其余文本 / 缓存写入（如 CacheManager、部分 config 写盘）直接覆盖，崩在中途会留半写文件。
- 建议：抽象一个统一安全写盘通道（基于 AtomicFile），全仓文本落盘复用，消除半写风险。

### 3.4 [P3] PreferencesExtensions 反射改写 mPreferencesDir 的窗口（已取证，维持现状）
- 现状：已用 prefsDirMutationLock 包裹 set/还原，但窗口期内其它线程对未缓存 SP 的首次访问会被重定向到备份目录。
- 证据：utils/PreferencesExtensions.kt 行 27-62。
- **取证结论（2026-10-28，勿重复评估）**：改目录的 set/还原已整体在全局锁内，操作自身不会交叠；残余窗口是框架级限制——普通线程的 getSharedPreferences 不经过这把锁，无法无侵入关闭。彻底解法（moveSharedPreferencesFrom / 自定义 SP 实现）成本高、收益仅限备份瞬间的边缘场景，不改。

### 3.5 [P3] Room 多步写入事务审计（已取证，通过）
- 现状：关键位置已用 @Transaction（如 BookChapterDao.replaceByBook 先删后插）。
- **取证结论（2026-10-28，勿重复评估）**：抽查 saveBook/saveBookProgress（BookController）均为单实体写入 + 云端上传；章节列表的先删后插多步写已有 @Transaction 保护。未发现「书+章节+进度」跨表多步写入缺事务的点，审计通过，无改动。

---

## 4. 性能优化

### 4.1 [P1] Web 服务请求串行化（已取证，不成立）
- 现状：NanoHTTPD serve 里 runBlocking。
- **取证结论（2026-10-28，勿重复评估）**：NanoHTTPD DefaultAsyncRunner 是每连接一线程模型，不存在串行化（同 2.2 结论 1）。

### 4.2 [P2] /cover 接口重复解码 / 落盘（已取证，维持现状）
- 现状：每次请求 ImageProvider.cacheImage(book,src) + getImage(book,src,width)。
- 证据：api/controller/BookController.kt 行 122-124、model/ImageProvider.kt。
- **取证结论（2026-10-28，勿重复评估）**：getImage 已有按文件路径为 key 的 BitmapLruCache + cacheImageAsync 的 inflight 去重，同一 (src,width) 重复请求命中内存缓存，「每次重复解码」不成立；cacheImage 只是文件存在性 stat，开销毫秒级。唯一小缺口是 cacheKey 不含 width（不同 width 复用同一 bitmap），但这是与阅读页共享的既有行为，改 key 会按 width 放大缓存条目、增加内存压力，不改。

### 4.3 [P2] 弹幕远程图片每请求新建 Thread
- 现状：DanmakuAdapter 注释自承每次新建线程加载远程 url 图片，请用异步线程池 + 缓存池。
- 证据：help/gsyVideo/DanmakuAdapter.kt 行 34-35。
- 建议：用 CoroutineScope(Dispatchers.IO) + LruCache 替换裸 Thread。

### 4.4 [P2] 封面缓存覆盖不全（高画质 + 冷启动预热）
- 现状：CoverMemoryCache 已缓存，但 useThumb=false（高画质模式）不缓存（全尺寸位图太大）；冷启动前 N 本封面预热只发生在书架 collect 时，非首帧前。
- 背景：历史台账 #50（封面内存缓存）、#54（CoverWarmup 仅书架 collect 触发）。
- 建议：高画质模式可缓存缩略代理；把 loadKey 计算抽成共享函数，在 BooksFragment/BookshelfFragment2 首帧 collect 前预热前 N 本。

### 4.5 [P3] 大 JSON 同步 GSON 解析
- 现状：全仓 182+ 处 GSON.fromJson/toJson。
- **取证结论（2026-10-28，勿重复评估）**：导入解析均在 IO——ImportBookSourceViewModel 的 importSource/importSelect 走 BaseViewModel.execute（默认 Dispatchers.IO），RuleUpdate 在 IO 协程，BookSourceController 的解析发生在 Web 服务 serve 线程（本就后台）。无需修改；超大列表（Web 服务 toJson 大于 3000 条）已维持分块。

### 4.6 [P3] nanohttpd 替换（长期方向）
- 现状：HttpServer 基于 nanohttpd 2.3.1（2016 停更），单线程模型是性能 / 安全天花板。
- 背景：历史台账 #57 标注为需替换的长期方向。
- 建议：评估 Ktor/Javalin 嵌入式方案，或至少给 nanohttpd 加线程池 + 鉴权中间件。

---

## 5. 用户体验 / 功能缺口

### 5.1 [P2] 设置开关碎片化、部分仅调试入口可达
- 现状：recordHeapDump 仅在 AboutFragment.kt 行 284 被读（调试页）；internalBetaUpdateEnabled/updateToVariant 为内部 / 半内部开关；30+ 开关散落各 Fragment/菜单。
- 建议：集中高级 / 实验功能页，内部项加标注；保证每个 PreferKey 在设置页有可读入口（历史已对评论体系 10 个开关做过一轮 UI 补齐，#81/#83）。

### 5.2 [P2] 崩溃提示对普通用户不友好
- 现状：崩溃直接 longToast 完整堆栈（同 2.1）。
- 建议：正式版只提示已记录，可在关于页查看 / 复制诊断信息，堆栈仅开发者可见（#53 已落地诊断导出，正好衔接）。

### 5.3 [P3] 对话模式排版第 3 次循环未实现
- 现状：ContentHelp.kt 行 316 注释（第3次循环，匹配并插入换行。未实现）—— 对话段落换行合并缺失，用户可见排版差异。
- 建议：补对话段落换行合并逻辑（注意与现有规则的正则顺序兼容）。

### 5.4 [P3] 弹幕字体 / 动画缓冲未实现
- 现状：BiliDanmukuParser.kt 行 231 与 234 两处 TODO 字体 / TODO 是否有动画缓冲(easing)。
- 建议：按需补字体数组解析与缓动缓冲，无弹幕则忽略。

### 5.5 [P3] 阅读页无障碍覆盖缺失
- 现状：PageView/ReadView/EpubVirtual*/ui/widget/* 大量自定义绘制 View 无 contentDescription/语义节点。
- 建议：用 Accessibility Scanner 走查阅读页与菜单，给关键控件补语义。

### 5.6 [P3] 阅读进度百分比非英语地区本地化
- 现状：DecimalFormat("0.0%") 用默认语言环境，非英语地区变 99,9%，导致 percent == "100.0%" 失配、99.9% 特判不生效。
- 背景：历史台账 #61 标注为已知遗留（行为变更，留待单独处理）。
- 建议：改用 DecimalFormatSymbols(Locale.US) 或改用数值比较而非字符串比较。

### 5.7 [P3] EPUB hsl 颜色支持漂移
- 现状：EpubLayoutEngine.toCssColor 支持 hsl，TextChapterLayout 的 EpubCssValues.parseColor 不支持 —— 两套副本行为不一致（历史台账 #75 已固化为暂不统一）。
- 建议：先决策是否给所有 Epub 颜色解析补 hsl，再统一，避免部分书颜色错乱。

### 5.8 [P3] 段评快照存储无总量上限
- 现状：快照目录无容量上限看板（历史台账 #80 标注 P3 遗留）。
- **已闭环（2026-10-28 取证，勿重复评估）**：ReviewSnapshotStore.prune() 已实现 30 天保留期（RETENTION_DAYS）+ 单书 32MB 上限（MAX_CACHE_BYTES）+ 最旧优先淘汰，put 后经 maybePrune 触发（每书 10 分钟节流）；资源 blob 孤儿由 ReviewSnapshotResourceStore.gc 按引用回收。台账 #80 的「无上限」结论已过时。

### 5.9 [P3] oldClickImg 的 js 分支未接快照消费（已闭环）
- 现状：最老形态（旧源 click/js 分支）。
- **已闭环（2026-10-28 取证，勿重复评估）**：BookImgClick.oldClickImg 已完整接入三模式快照消费——SNAPSHOT_ONLY（无快照提示）、SNAPSHOT_FIRST（回落网络 openNetwork）、NETWORK（快照作失败兜底），并覆盖了「选项 JSON 损坏」「无 click/js 字段」两个边界的离线兜底。台账 #80 的「未接」结论已过时。

---

## 6. 测试与 CI 增强

### 6.1 [P3] 扩大单测覆盖
- 现状：已有约 1885 单测（264 类），但 help/、service/、web/、lib/ 大量模块零测试。可优先给纯逻辑（字符串 / URL / 配置解析、加密、网络鉴权判定）补黑盒测试（历史台账已验证纯函数硬化路线零风险、高 ROI）。

### 6.2 [P3] lintAppDebug 提升为门禁
- 现状：verify.yml 里 lint 仍 continue-on-error 只出报告不拦人（历史台账 #53）。
- 建议：存量 lint 清零后改为阻塞，防新告警流入。

### 6.3 [P3] 崩溃远程收集
- 现状：仅有本地 CrashHandler 落盘 + 关于页导出，无远程收集 SDK（历史台账 #25409898）。用户不主动反馈则开发者看不到崩溃。
- 建议：评估轻量自托管收集（如自建 ingest + 关于页隐私开关），不引入第三方追踪。

### 6.4 [P3] 真机验收矩阵自动化
- 现状：验收靠人工（ROM × 深浅色 × 字体缩放 × 屏幕形态）。历史台账 #55 给出过 7 维矩阵但未固化成脚本。
- 建议：把关键回归（换肤一致性、书架不闪、段评弹出）写成 instrumentation 测试或 Fastlane 截图脚本。

---

## 7. 已处理的正面样例（勿重复评估）

- 崩溃落盘同步写入：CrashHandler.recordCrashSummary 用 LocalConfig.edit(commit=true)（行 137），避免 apply() 异步丢标记（#56）。
- WebView 复用池：WebViewPool 的 @Synchronized + resetToken 防串台、闲置 / 超时销毁、scope 级销毁不拆使用中实例（注释详尽）。
- Relay 安全范式：RelaySecurity.kt 用 AndroidKeyStore + AES-GCM、挑战码时效 / 时钟偏移校验、路径白名单严格校验（RelayReadAllowlist.validate），是良好的安全模板，可直接复用到 1.1 的凭据存储。
- Web 服务鉴权链路：WebServiceGuard 三种来源（Bearer/Cookie/?token=）任一通过即放行 + fail-closed + WebSocket 握手鉴权（#58/#59）。
- 书架封面内存缓存：CoverMemoryCache（LruCache 16MB）+ 首屏不回落默认封面（#50）。
- 单测基建：verify.yml 已接入 compileAppDebugKotlin + testAppDebugUnitTest 作为 push/PR 门禁（#53）。

---

## 8. 收尾批执行记录与剩余项判定（2026-10-28）

### 8.1 已实施（三轮合计）

| 项 | 改动 |
|----|------|
| 1.1 凭据加密 | SecurePrefs（KeyStore AES-GCM）+ 5 key 收口 + 备份/恢复闭环 + 启动迁移 |
| 1.2 Web 服务默认安全 | 未设口令仅绑 127.0.0.1，通知提示；设口令后才绑全网卡 |
| 1.3 WebView 收紧 | allowContentAccess=false ×6 处（content:// 隐私面）；混合内容/文件访问刻意保留 |
| 2.1 + 5.2 崩溃加固 | 主线程才重入 Looper；后台线程放行；用户提示友好化（不弹堆栈） |
| 2.3 NPE | runBlockingWithLifecycleJob 去除 !! 断言 |
| 3.1 可见性 | WebService.isRun/hostAddress 加 @Volatile |
| 3.2 前缀误删 | SourceConfig.removeSource 最长源 URL 匹配判归属 |
| 4.3 弹幕图片 | 裸 Thread → 共享 IO 协程 + LruCache + 在途去重 |
| 5.6 本地化 | 进度百分比与 formatter 本地化格式比较，99.9% 特判在逗号地区恢复生效 |

### 8.2 已取证关闭（不成立 / 已闭环，勿重复评估）

- 2.2 / 4.1 runBlocking 三处：NanoHTTPD 每连接一线程；ReaderProvider 契约；JS 同步 API 契约。
- 3.4 SP 反射窗口：全局锁已在，残余是框架级限制。
- 3.5 Room 事务审计：通过（关键多步写已有 @Transaction）。
- 4.2 /cover：已有 LruCache + inflight 去重。
- 4.5 导入解析 IO：BaseViewModel.execute 默认 Dispatchers.IO，全部已在后台。
- 5.8 快照上限：prune() 已有 30 天 TTL + 32MB 上限（#80 台账过时）。
- 5.9 oldClickImg：三模式快照消费已完整接入（#80 台账过时）。

### 8.3 剩余项判定（需用户决策或专项，不并入收尾批）

- **1.4 DeepLink / 外部 Intent 校验**：74+ 入口，属专项审计。已确认 OpenUrlConfirmActivity 有二次确认，透明分发器（FileAssociation/OnLineImport）走弹窗确认链路；全面白名单化建议单独排期。
- **3.3 AtomicFile 统一写盘通道**：触点几十处，属重构专项；当前关键路径（快照/配置）已用 AtomicFile。
- **4.4 封面预热/高画质缓存**：书架 collect 预热已存在（#54），高画质不缓存是刻意设计（全尺寸位图过大）；「首帧前预热」属性能专项，需先抽共享 loadKey。
- **4.6 nanohttpd 替换**：长期方向（2016 停更），替换涉及全 API 面，需专项。
- **5.1 设置碎片化**：产品决策项（「高级/实验功能」聚合页的信息架构），非纯工程改动。
- **5.3 对话模式第 3 次循环**：上游 legado 原生未实现的既有现状，实现会改变全部文本书的排版行为且无验收手段，维持现状。
- **5.4 弹幕字体/缓动 TODO**：GSY 示例继承的 TODO，无真实内容可验证，暂缓。
- **5.5 无障碍**：需 Accessibility Scanner 走查 + 逐控件语义标注，属专项。
- **5.7 EPUB hsl 漂移**：统一前需先决策「是否给全部 Epub 颜色解析补 hsl」（行为变更），待拍板。
- **6.1/6.2 测试与 lint 门禁**：lint 升门禁前需先清零存量告警（单独一轮）；单测扩大沿「纯函数硬化」既有路线持续做。
- **6.3 崩溃远程收集 / 6.4 验收自动化**：均需基建投入，属专项立项。
