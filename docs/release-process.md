# legado-H 发布流程规范

> 本文件是 legado-H 发布版本的**唯一规范**（仓库内没有第二份发布文档；根目录旧版已于 2026-11-27 删除）。发布渠道为**本机构建 + GitHub API 上传**，版本号采用 H 方案三段式（`H.<major>.<minor>.<patch>` / versionCode 基线偏移）。Gitee 渠道及其相关代码已于 2026-10-07 彻底移除，不再恢复。
>
> 正文只放**可执行规则**；分支考古、实测耗时与事故复盘在文末「附录」，读规则不必翻历史。

## 0. 关键前提

- **唯一主线是 `main`**，`v5` 分支已于 2026-10-16 冻结、不再发版（经过见附录 A）。
- **发布渠道只有一条**：本机构建双包 → `.legadoh/create-release.sh` 走 GitHub API 上传到 `helyel233/legado-H` 的 Releases。没有 CI 发布（`android-fast-release.yml`、`sync-release-gitee.yml` 已删除，不要再新建发布 workflow）。
- **发版脚本纳入版本管理**：`.legadoh/build-signed.sh` 与 `.legadoh/create-release.sh` 已在 `.gitignore` 白名单内（`.legadoh/*` 忽略，这两个文件除外），改动请照常提交。
- **签名密钥只在本机**：`.legadoh/keystore.properties` + `legadoh-release.jks`，均 gitignore。**keystore 丢失后，已安装用户的升级包就再也签不出来**（只能换包名重发）——必须离线双备份（U 盘 + 密码管理器各一份），本文件不能替代备份。release 正式包用 LegadoH 签名，debug 包用 Android Debug 签名。
- 发布凭证：无 gh CLI，脚本用 `git credential fill` 从 osxkeychain 取 helyel233 token 调 GitHub API。
- `docs/移植计划.md` 是本地私有规划文档（已 gitignore），发布提交不要 `git add` 它。

## 1. 版本规则

| 项 | 规则 | 示例 |
|---|---|---|
| `versionName` | `H.<major>.<minor>.<patch>` 三段式，规则见下 | `H.1.8.0` |
| git tag | `H-<x.y.z>`（annotated，与 versionName 成对） | `H-1.8.0` |
| `versionCode` | `29860000 + git rev-list --count HEAD`（脚本自动计算，无需手填） | 2348 个提交时 = `29862348` |
| Release 标题 | `legado-H <x.y.z>` | `legado-H 1.8.0` |
| Release 正文 | 只取 `CHANGELOG.md` 中**当前版本**的小节（脚本自动抽取） | — |
| APK 文件名 | `legado_app_H.<x.y.z>_<CODE>_LegadoH_arm64.apk` / `legado_app_H.<x.y.z>_<CODE>_LegadoH_debug.apk` | `legado_app_H.1.5.1_29862163_LegadoH_arm64.apk` |

### 1.1 三条版本规则（相对紧邻上一版计算）

| 本次改动 | 版本号 | 例 |
|---|---|---|
| ① 仅新特性（无 bug 修复） | `minor +0.1`，`patch` 归零 → `x.y.0` | `H.1.7.7` → `H.1.8.0` |
| ② 新特性 **且** 修了 bug | `minor +0.1`，`patch` 定为 `1` → `x.y.1` | `H.1.10.0` → `H.1.11.1` |
| ③ 仅 bug 修复 / 优化 / 纯重构 | `major.minor` 不变，`patch +1` | `H.1.7.7` → `H.1.7.8` |

- **`.0` 只出现在规则 ①**：有新特性就必然进 `minor`，因此规则 ② 的补丁位必为 `.1`——「有了修复就不该把 patch 留在 0」，不是说 minor 进位一律跳过 `.0`。历史 `H-1.8.0`、`H-1.9.0`、`H-1.10.0`、`H-1.13.0`、`H-1.14.0`、`H-1.20.0` 都是规则 ① 的产物，不可回改成 `.1`。
- 判据：看本次相对上一个 tag 的改动是「新功能」还是「修 / 优」；两类都有走规则 ②。
- 历史上 `H.1.0`–`H.1.4` 为两段 tag、视作隐含 `.0` 补丁（`H-1.4` ≡ `H-1.4.0`），不得回改。

### 1.2 主版本冻结（2026-11-27 用户明确，最高优先级，覆盖任何历史条款）

- **不增加大版本号（`major`），除非用户明确要求。** 改动再重大也只在当前 `major` 内走 `minor` / `patch`；原「重大改版 → `+1.0.0`」条款即日起作废，不得再作为定版依据。
- 历史主版本跃迁只有两次：`H-2.0.1`（用户明确要求「从 2.0 起版」）与 `H-3.0.1`（当时误判「重大改版」擅自进位，不再援引此例）。
- 若用户明确要求跳主版本，按用户给出的数字执行；除用户明示外，「重构规模大 / 删除文件多 / 架构重写」都不是进位理由。

### 1.3 versionCode

- 应用内检查更新（`AppUpdate.versionInfoFromFileName`，正则 `(\d+(?:\.\d+)+)_(\d+)`）从资产文件名解析 `<x.y.z>_<CODE>`，`H.` 前缀不参与比较，**新旧判定完全靠 versionCode**。基线 29860000 高于全部旧 `legadoh-3.x` 的 epoch 分钟 code，保证旧版用户能收到更新提示；资产文件名**必须**保持上述形态，不得手工改名破坏。
- **本次 versionCode 必须严格大于上一已发布版本**。纠错流程（第 8 节）会 `reset --soft` 重写历史，提交数会回退——`create-release.sh` 已在取 token 后强制校验（不大于上一版直接报错退出）；手工预演：
  ```bash
  git rev-list --count HEAD                                    # 本次 code = 29860000 + 该数
  git rev-list --count $(git tag -l 'H-*' --sort=-v:refname | head -1)   # 上一版提交数
  ```
- `app/build.gradle` 本地兜底（不传 `-P` 时）：versionName = `H.dev.<时间>`、versionCode = `29860000 + 提交数`，仅用于日常调试；**正式发布一律用 `.legadoh/build-signed.sh <x.y.z>`**，它显式注入版本与签名参数。

## 2. 发布前检查（全部通过再动手）

```bash
git log --oneline -6                                     # 确认没有其他会话已发布新版本
git tag -l 'H-*' --sort=-v:refname | head -3             # 最新 tag；新版本号必须严格递增
git rev-list --count HEAD                                # 预演本次 versionCode
git status --short                                       # 必须为空：无关改动混进发布提交会毁掉可回滚性
```

- **版本号已定、tag 未打**：上一版 tag 的提交数 +29860000 必须小于本次 code（见 1.3）。
- **`git status` 非空且含无关文件**：先分笔提交或 stash，不要带着别人的改动发版。

## 3. 发布前准备（全部完成后再构建）

1. **定稿 `CHANGELOG.md`**：顶部新增 `## <x.y.z> 版（YYYY-MM-DD）` 小节（新增 / 优化 / 修复）。**标题必须严格是这个形状**——`create-release.sh` 按 `^## <x.y.z>` 抽取正文，抽取不到会直接报错退出（不会再把整份历史当成正文）。正文即该小节内容，必须在构建与上传前提交。
2. **更新 `app/src/main/assets/updateLog.md`**：在「## LegadoH」标题下插入新条目，格式 `**vH.<x.y.z> · {YYYY/MM/DD}**` + 要点列表。该文件随 APK 打包（「我的-关于-更新日志」显示），**必须在构建前提交**，否则包内日志滞后需重建双包。其下 v3.x 历史条目保留作存档，不再新增。
3. 确认本次改动不含 `docs/移植计划.md`。
4. 功能代码按 Conventional Commits 分功能提交；共享文件（strings.xml 多 locale 变体）用临时删行、提交后再恢复的方式实现原子拆分。

## 4. 提交与打 tag

```bash
git add CHANGELOG.md app/src/main/assets/updateLog.md <其它发布相关源码>
git commit -m "release: 定稿 <x.y.z> 版更新日志"
git -c http.proxy= -c https.proxy= push origin main            # 直连，代理通常没开
git tag -a "H-<x.y.z>" -m "<x.y.z> 版：<一句话摘要>"
for i in 1 2 3; do git -c http.proxy= -c https.proxy= push origin "H-<x.y.z>" && break; sleep 5; done
git ls-remote --tags origin "H-<x.y.z>"                        # 确认远端 tag 真的存在
```

- **先提交并推送定稿，再打 tag**：tag 将当前提交数固化为 versionCode，构建时 HEAD 必须就是 tag 指向的提交。
- `git push` 偶发 Recv timeout：等约 20 秒重试；持续抖动加 `-c http.version=HTTP/1.1`。
- 常规发布**不要 force-push `main`**；唯一例外是「版本号定错且已推送、尚未建 Release」的纠正场景，见第 8 节。
- **不要**额外跑 `compileAppDebugKotlin --rerun-tasks` 之类全量编译：双包构建本身会编译，另跑一遍白费约 18 分钟。

## 5. 构建双包（本机，严格顺序）

### 5.1 正式版（arm64，LegadoH 签名）

```bash
.legadoh/build-signed.sh 1.5.1     # 传三段数字版本号，不要带 H 前缀
```

脚本自动：计算 `versionCode = 29860000 + 提交数` → 注入 `.legadoh/keystore.properties` 签名参数与 JAVA_HOME → 构建 arm64-v8a release。完成后把 `app/build/outputs/apk/app/release/` 下的 APK 复制到仓库根并重命名为 `legado_app_H.<x.y.z>_<CODE>_LegadoH_arm64.apk`（`<CODE>` 以脚本输出为准）。

### 5.2 调试版（全 ABI，debug 签名）

```bash
./gradlew --stop                 # ⚠️ 必须：release 的 R8 跑完后重启干净 daemon，防 GC/swap 死亡行进
./gradlew :app:assembleAppDebug \
  -PVERSION_NAME="H.1.5.1" -PVERSION_CODE=<同上CODE> --max-workers=6 -q
```

复制到仓库根并重命名为 `legado_app_H.<x.y.z>_<CODE>_LegadoH_debug.apk`。仓库根只保留当前版本这两条 APK（`/legado_app_*.apk` 已 gitignore，旧版删掉即可；远端旧 Release 的 asset 不动）。

### 5.3 验证（不依赖 PATH 里的构建工具）

```bash
SDK=$(sed -n 's/^sdk.dir=//p' local.properties)
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
"$BT/apksigner" verify --print-certs legado_app_H.1.5.1_29862163_LegadoH_arm64.apk
# 预期 release：CN=LegadoH；debug 为 Android Debug 证书
"$BT/aapt2" dump badging legado_app_H.1.5.1_29862163_LegadoH_debug.apk | head -1   # 核对 versionName/versionCode
unzip -p legado_app_H.1.5.1_29862163_LegadoH_debug.apk assets/updateLog.md | head   # 确认含本版条目
```

### 5.4 本机构建红线（历史陷阱，仍然全部有效）

- release 与 debug **严禁并行**（共享 daemon 堆 OOM），顺序执行；
- `gradle.properties` 保持 `jvmargs=-Xmx4g`（3g 会 in-process Kotlin 编译 OOM）、不设置配置缓存属性（默认关闭，原因见附录 B，勿反复尝试启用）、`kotlin.compiler.execution.strategy=in-process`；
- 任何变体构建都显式传 `-PVERSION_NAME/-PVERSION_CODE`（或走 build-signed.sh），不要依赖 `H.dev.` 兜底出正式包；
- daemon 卡住（CPU 满载 + 日志静默 + swap 高）先 `ps` 看 daemon、`sysctl vm.swapusage` 看 swap，然后 kill 客户端 + `./gradlew --stop` 重跑；
- 改脚本后字节级复核续行符（`cat -e` 检查是否被写成 `\\`），并跑一遍 `zsh -n` 语法检查。

## 6. 发布（create-release.sh）

```bash
.legadoh/create-release.sh 1.5.1     # 三段数字版本号，不带 H 前缀
```

脚本自动：

1. 校验仓库根已有两个标准命名的 APK（缺文件直接报错退出）；
2. `git credential fill` 取 helyel233 token；
3. **校验 versionCode 严格大于上一已发布版本**（读最近 10 个 Release 的 asset 名，读不到则告警跳过）；
4. 从 `CHANGELOG.md` 抽取**当前版本小节**生成正文 JSON；标题不符合 `## <x.y.z> 版` 直接报错退出；
5. 按 tag `H-<x.y.z>` 查询已有 Release：**已存在则先 PATCH 更新正文、再 DELETE 旧 APK asset**，然后重传（幂等重发，改了 CHANGELOG 直接重跑即可）；不存在则创建；
6. 依次上传两个 APK，输出每个 asset 的 `name`/`size`/`digest`。

## 7. 发布后验证

- 两个 asset `state=uploaded`；
- 下载远端 APK 与本地比对 SHA-256（或核对文件大小）；
- `unzip -p <apk> assets/updateLog.md | head` 确认包内含本版条目；
- 包内 `output-metadata.json` 核对 versionName=`H.<x.y.z>`、versionCode=`29860000+提交数`；
- Release 正文只显示本版内容（不是一整串历史）；
- 应用内「检查更新」能发现新版本（依赖资产文件名 `<x.y.z>_<CODE>` 连续段 + versionCode 判定）；
- `git status` 干净，工作区没留下构建中间产物。

## 8. 纠错

- **版本号定错但已推送（尚未建 Release）**：先停掉进行中的构建；`git reset --soft <上一版定稿提交>` 回退 HEAD（保留改动到暂存区），修正 `CHANGELOG.md` / `updateLog.md` 的版本号与定版说明，按第 3 节重新分笔提交功能与日志；用 `git push --force-with-lease=refs/heads/main:<远端当前 SHA> origin main` 覆盖错误提交，`git push origin :refs/tags/H-<错误版本>` 删远端错误 tag，再按正确版本号重建并推送 tag。**收尾必须跑 `git tag -l 'H-*'` 复核**，错误 tag 不能留在本地/远端。
- 错误 tag：`git push origin --delete <tag>` 删远端 + `git tag -d <tag>` 删本地；如已产生 Release，先调 GitHub API 删除 Release 再删 tag。
- 版本号已发布但内容有误：修好后重新构建双包，直接重跑 `create-release.sh <同版本号>`（幂等：正文更新 + asset 重传）。
- 上传 409/422：确认没有同名 asset 残留，重跑脚本即可。
- 禁止恢复任何 Gitee 渠道代码、`legadoh-3.x` 时间戳版本号或「第 N 版」编号；发现残留按本文件清理。

## 9. 已移除的能力（不要回头）

- **GitHub Actions 发布**：`android-fast-release.yml`、`sync-release-gitee.yml` 已删除；CI 不再是发布渠道。
- **Gitee 渠道**：`tools/sync_gitee_release.py`、其测试、workflow 同步步骤、应用内更新策略常量与「更新通道」UI 区块均已移除；加速管理对话框现在只管理 GitHub 加速代理。
- **debug 版 CI 构建**：`android-fast-debug*.yml` 仅产出 CI artifact 供调试，与本发布流程无关。

## 附录 A：v5 分支与 versionCode 脱钩（历史）

- `v5` 是 2026-10-08 从 `main`（1.7.3 / `08348a785`）分叉出的 UI 架构重写尝试（Host 换壳、`uikit`/`theme` 模块、组件库），**已于 2026-10-16（`876e8781e`）停止更新并冻结**，不再合并回 main、不再发版、不再在其上开发。两分支分叉后零 merge、零功能互通；main 的 SecurePrefs、玻璃四档、评论快照、预取、状态栏修复等能力 v5 全无，把 v5 扶正等于全量重移植，不划算。v5 保留为归档参考（含 uikit/theme 的 UI 重构素材），仅此而已。
- 历史上 `main` 与 `v5` 共用 `29860000 + 提交数` 命名空间（v5 曾发过 `H-1.20.0`，code 29862282；main 的 `H-1.30.1` 为 29862283，仅高 1）。v5 冻结后不再产生新提交，其最大 code 停在 29862337，不会再冲击 main 的进位；main 后续按提交数正常递增即可，无需改基线。

## 附录 B：构建提速实测（2026-11-26，勿回退）

| 项 | 当前状态 | 说明 |
|---|---|---|
| 并行度 | `build-signed.sh` 与 debug 命令均 `--max-workers=6` | 本机 8 核，原先 `2` 浪费并行度 |
| lintVital | `app/build.gradle` `checkReleaseBuilds = project.hasProperty("enableLintVital")`，本地默认关；CI 的 `lint-vital` 作业传 `-PenableLintVital=true` 单独执行（`verify.yml`） | 本地构建省 3–6 分钟，安全网不丢 |
| 构建缓存 | `gradle.properties` `org.gradle.caching=true` | 无改动重跑 23s 全命中；跨版本复用 rhino/book 等模块任务 |
| 配置缓存 | **不启用**（Gradle 默认关闭），仅在 `gradle.properties` 留注释说明原因 | 实测 44 个独有问题（配置期 `git rev-list`、`copyAppReleaseApkToCurrentDir` 闭包捕获变体对象、Kotlin 插件 `StoredPropertyStorage`），启用即 BUILD FAILED。勿反复尝试 |

- 实测耗时（版本号变更的真实发版负载）：正式包约 9m37s、debug 约 5m03s，双包合计约 14m46s；无改动重跑约 23s。冷路径瓶颈是 1726 个 Kotlin 文件的单一编译任务与 R8（compat 模式，为 Rhino 兼容不换 fullMode），workers 与缓存影响不到，不要再为冷路径调参。
- 已知风险：`org.gradle.caching=true` 曾触发一次 `modules/rhino/.../classes.jar` 被并发重写导致 `newPosition > limit`（debug 首跑失败、重跑成功）。复发先重跑；若持续出现再回退该开关，不要盲改编译参数。

## 附录 C：事故复盘——4.0.1 → 3.1.1（2026-11-27）

当时把一次「架构重写 + 新特性」误判为「重大改版」，按已作废的 `+1.0.0` 条款定了 `4.0.1` 并推送；用户纠正后按第 8 节流程处理：`reset --soft` 回退、改定 `3.1.1`（新特性 + 修复 → rule ②）、force-with-lease 覆盖、删错误 tag 重建。**教训**：`major` 进位只能由用户明说；既然那次 `reset --soft` 会让提交数回退，versionCode 递增校验（1.3）必须存在，否则老用户永远收不到更新。
