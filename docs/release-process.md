# LegadoH Release 流程规范

本文规定 LegadoH 的版本发布流程。每次发布必须同时构建并上传正式版与调试版两个安装包到同一个 GitHub Release，并保持版本号、更新日志、签名与文件命名的规范一致。

## 版本号规范

| 项目 | 规则 | 示例 |
| --- | --- | --- |
| `VERSION_NAME` | `3.{yy}{MMdd}{HHmm}`，Asia/Shanghai 时区 | `3.2610070743` |
| `VERSION_CODE` | epoch 分钟数：`date -u +%s / 60`，单调递增 | `29855583` |
| Release tag | `legadoh-$VERSION_NAME` | `legadoh-3.2610070743` |
| APK 文件名 | `legado_app_{VER}_{CODE}_LegadoH_arm64.apk` / `_LegadoH_debug.apk` | 见下方命名说明 |

- 不传 `-PVERSION_NAME/-PVERSION_CODE` 时，`app/build.gradle` 会走默认方案（`3.yyMMddHH`、`10000 + commit 数`），因此**正式版与 debug 版构建都必须显式传参**。
- APK 文件名格式必须严格保持，应用内检查更新依赖正则解析其中的 VER 与 CODE。

## 发布前检查

1. 执行 `git log --oneline -6` 并查看 `updateLog.md` 顶部条目，确认当前最新版本号。不能沿用记忆中的版本号，其他会话可能已经发布过；新版本号必须在其之上。
2. 功能代码按 Conventional Commits 规范分功能提交并推送（`feat(...)` / `fix(...)` / `chore(...)`）。共享文件（如 strings.xml 的多个 locale 变体）用临时删行、提交后再恢复的方式实现原子拆分，避免条目丢失。

## 发布固定步骤

按以下顺序执行：

1. **更新 `app/src/main/assets/updateLog.md`**：在 `## LegadoH` 标题下插入新版本条目，格式为 `**v{VERSION_NAME} · {YYYY/MM/DD}**` 加要点列表。该文件随 APK 打包，供「我的-关于-更新日志」显示。
   - ⚠️ 必须在构建之前更新，漏更会导致 APK 内缺本版条目，被迫重建双包并重传。
   - 更新时顺带做全文件错别字检查（历史条目曾检出「兕底」→「兜底」）。
2. **更新 `.legadoh/build-signed.sh`**：修改其中的 `-PVERSION_NAME/-PVERSION_CODE`。
3. **更新 `.legadoh/create-release.sh`**：修改 `VER/CODE` 与 release body JSON（更新内容 + 下载说明）。
4. **构建正式版**：运行 `.legadoh/build-signed.sh`（内部使用 `-Pabi=arm64-v8a`，自动注入 `keystore.properties` 的四个签名参数与 JAVA_HOME）。耗时约 10–25 分钟（含 R8 混淆）。
5. **`./gradlew --stop` 后再构建 debug 版**：`./gradlew :app:assembleAppDebug -PVERSION_NAME=... -PVERSION_CODE=...`。
   - 严禁 release 与 debug 并行构建，两者共享同一 Gradle daemon 堆内存，并行会 OOM。
   - `gradle.properties` 保持 `jvmargs=-Xmx4g`、`configuration-cache=false`。
6. **重命名与验签**：构建输出的文件名不含变体尾段，需手动复制并加 `_LegadoH_arm64` / `_LegadoH_debug` 后缀。注意 debug 构建传版本参数后输出文件名与 release 相同（在不同目录，复制时留意路径）。验签命令：

   ```bash
   apksigner verify --print-certs <apk>
   ```

   预期结果：release 为 `CN=LegadoH, SHA-256 ee8fe11d…`；debug 为 Android Debug 证书。
7. **包内自检**：

   ```bash
   unzip -p <apk> assets/updateLog.md | head
   ```

   确认包内含本版条目；并用 `output-metadata.json` 核对 versionCode 与 versionName。
8. **创建 Release**：运行 `.legadoh/create-release.sh`（tag `legadoh-$VERSION_NAME`，双包上传）。脚本改动后需做三处校验：`python json.load`（JSON 合法性）、`cat -e`（续行符是否被写成 `\\`）、双反斜杠计数。
9. **发布后验证**：远端 asset 状态为 `state=uploaded`，远端 SHA-256 与本地一致；可选追加 `docs(changelog)` 同步提交。

## 双包差异说明

| 项目 | 正式版 | 调试版 |
| --- | --- | --- |
| 构建 target | `:app:assembleAppRelease` | `:app:assembleAppDebug` |
| ABI | arm64-v8a（`-Pabi=arm64-v8a`） | 全 ABI |
| 签名 | LegadoH 正式签名（`keystore.properties`） | 标准 debug 签名 |
| 包名 | `io.legado.app.LegadoH` | `io.legado.app.debug`（可与正式版共存） |
| 文件名后缀 | `_LegadoH_arm64` | `_LegadoH_debug` |

## 已知陷阱速查

- **updateLog 时序**：构建之后才改 updateLog → APK 缺本版条目 → 需重建双包，且重传 asset 必须先 `DELETE` 旧 asset id 再 `POST` 新文件（同名直接 POST 返回 409）。
- **debug 版本号**：debug 构建忘传版本参数 → 走默认版本方案，与正式版不一致。构建后用 `output-metadata.json` 核对。
- **签名参数**：gradle 不会自动读 `.legadoh/keystore.properties`，必须通过 4 个 `-PRELEASE_*` 参数传入；`RELEASE_STORE_FILE` 为裸文件名，需拼接 `.legadoh/` 绝对路径传入。
- **配置缓存**：configuration-cache 与含 WeakHashMap/ReferenceQueue 的自定义 task 不兼容，会导致序列化失败，保持关闭。
- **堆内存**：Kotlin in-process 编译复用 Gradle daemon 堆空间，`-Xmx3g` 会 OOM，必须保持 `-Xmx4g`。
- **脚本转义**：用编辑工具写入 shell 续行符 `\` 或 JSON `\n` 时易被写成双反斜杠，改完脚本必须用 `cat -e` 或 `python json.load` 做字节级复核。
- **网络**：`git push` 偶发 Recv timeout，等待约 20 秒重试即可；GitHub API 直连失败时可走备用访问路径。
