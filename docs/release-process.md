# legado-H 发布流程规范

> 本文件是 legado-H 发布版本的**唯一规范**。发布渠道为**本机构建 + GitHub API 上传**（沿用旧方案），版本号采用 H 方案三段式（`H.<major>.<minor>.<patch>` / versionCode 基线偏移）。Gitee 渠道及其相关代码已于 2026-10-07 彻底移除，不再恢复。

## 0. 关键前提

- **发布渠道只有一条**：本机构建出双包 → `.legadoh/create-release.sh` 走 GitHub API 上传到 helyel233/legado-H 的 Releases。**没有 CI 发布**（`android-fast-release.yml`、`sync-release-gitee.yml` 已删除，不要再新建发布 workflow）。
- **签名密钥在本机 `.legadoh/`**（`keystore.properties` + `legadoh-release.jks`，均已 gitignore）。release 正式包用 LegadoH 签名，debug 包用 Android Debug 签名。
- 发布凭证：无 gh CLI，脚本用 `git credential fill` 从 osxkeychain 取 helyel233 token 调 GitHub API。
- `docs/移植计划.md` 是本地私有规划文档（已 gitignore），发布提交不要 `git add` 它。

## 1. 版本规则

| 项 | 规则 | 示例 |
|---|---|---|
| `versionName` | `H.<major>.<minor>.<patch>` 三段式；相对紧邻上一版计算：重大改版 +major（`+1.0.0`）、仅新功能 +minor（`+0.1`）、仅修复 +patch（`+0.0.1`）、功能与修复并存 +minor+patch（`+0.1.1`）；历史上 `H.1.0`–`H.1.4` 视作隐含 `.0` 补丁（`H.1.4` ≡ `H.1.4.0`） | `H.1.5.1` |
| git tag | `H-<x.y.z>`（annotated，与 versionName 成对） | `H-1.5.1` |
| `versionCode` | `29860000 + git rev-list --count HEAD`（脚本自动计算，无需手填） | 2163 个提交时 = `29862163` |
| Release 标题 | `legado-H <x.y.z>` | `legado-H 1.5.1` |
| Release 正文 | 仓库根 `CHANGELOG.md`（脚本自动读取） | — |
| APK 文件名 | `legado_app_H.<x.y.z>_<CODE>_LegadoH_arm64.apk` / `legado_app_H.<x.y.z>_<CODE>_LegadoH_debug.apk` | `legado_app_H.1.5.1_29862163_LegadoH_arm64.apk` |

- versionCode 判定规则：应用内检查更新（`AppUpdate.versionInfoFromFileName`，正则 `(\d+(?:\.\d+)+)_(\d+)`）从资产文件名解析 `<x.y.z>_<CODE>`，`H.` 前缀不参与比较，**新旧判定完全靠 versionCode**。基线 29860000 高于全部旧 `legadoh-3.x` 的 epoch 分钟 code，保证旧版用户能收到更新提示；资产文件名**必须**保持上述形态，不得手工改名破坏。
- `app/build.gradle` 本地兜底（不传 `-P` 时）：versionName = `H.dev.<时间>`、versionCode = `29860000 + 提交数`，仅用于日常调试；**正式发布一律用 `.legadoh/build-signed.sh <x.y.z>`**，它显式注入版本与签名参数。

### 发布前冲突检查

```bash
git log --oneline -6        # 确认没有其他会话已发布新版本
git tag -l | tail           # 确认最新 tag，新版本号必须严格递增
git rev-list --count HEAD   # 预演本次 versionCode
```

## 2. 发布前准备（全部完成后再构建）

1. **定稿 `CHANGELOG.md`**：顶部新增 `## <x.y> 版（日期）` 小节（新增/优化/修复）。`create-release.sh` 直接以它作为 Release 正文，**必须在构建与上传前提交**。
2. **更新 `app/src/main/assets/updateLog.md`**：在「## LegadoH」标题下插入新条目，格式 `**vH.<x.y> · {YYYY/MM/DD}**` + 要点列表。该文件随 APK 打包（「我的-关于-更新日志」显示），**必须在构建前提交**，否则包内日志滞后需重建双包。其下 v3.x 历史条目保留作存档，不再新增。
3. 确认本次改动不含 `docs/移植计划.md`。
4. 功能代码按 Conventional Commits 分功能提交；共享文件（strings.xml 多 locale 变体）用临时删行、提交后再恢复的方式实现原子拆分。

## 3. 提交与打 tag

```bash
git add CHANGELOG.md app/src/main/assets/updateLog.md <其它发布相关源码>
git commit -m "release: 定稿 <x.y> 版更新日志"
git push origin main
git tag -a "H-<x.y>" -m "<x.y> 版：<一句话摘要>"
git push origin "H-<x.y>"
```

- 不要 force-push `main`；`git push` 偶发 Recv timeout，等约 20 秒重试即可。
- **先提交并推送定稿，再打 tag**：tag 将当前提交数固化为 versionCode，构建时 HEAD 必须就是 tag 指向的提交。

## 4. 构建双包（本机，严格顺序）

### 4.1 正式版（arm64，LegadoH 签名）

```bash
.legadoh/build-signed.sh 1.5.1     # 传数字版本号，不要带 H 前缀
```

脚本自动：计算 `versionCode = 29860000 + 提交数` → 注入 `.legadoh/keystore.properties` 签名参数与 JAVA_HOME → 构建 arm64-v8a release。完成后把 `app/build/outputs/apk/app/release/` 下的 APK 复制到仓库根并重命名为 `legado_app_H.<x.y>_<CODE>_LegadoH_arm64.apk`（`<CODE>` 以脚本输出为准）。

### 4.2 调试版（全 ABI，debug 签名）

```bash
./gradlew --stop                 # ⚠️ 必须：release 的 R8 跑完后重启干净 daemon，防 GC/swap 死亡行进
./gradlew :app:assembleAppDebug \
  -PVERSION_NAME="H.1.5.1" -PVERSION_CODE=<同上CODE> -q
```

复制到仓库根并重命名为 `legado_app_H.<x.y>_<CODE>_LegadoH_debug.apk`。

### 4.3 验证

```bash
apksigner verify --print-certs legado_app_H.1.5.1_29862163_LegadoH_arm64.apk
# 预期 release：CN=LegadoH, SHA-256 ee8fe11d…；debug 为 Android Debug 证书
unzip -p legado_app_H.1.5.1_29862163_LegadoH_debug.apk assets/updateLog.md | head   # 确认含本版条目
```

### 4.4 本机构建红线（历史陷阱，仍然全部有效）

- release 与 debug **严禁并行**（共享 daemon 堆 OOM），顺序执行；
- `gradle.properties` 保持 `jvmargs=-Xmx4g`（3g 会 in-process Kotlin 编译 OOM）、`configuration-cache=false`（与含 WeakHashMap 的自定义 task 序列化不兼容）、`kotlin.compiler.execution.strategy=in-process`；
- 任何变体构建都显式传 `-PVERSION_NAME/-PVERSION_CODE`（或走 build-signed.sh），不要依赖 `H.dev.` 兜底出正式包；
- daemon 卡住（CPU 满载 + 日志静默 + swap 高）先 `ps` 看 daemon、`sysctl vm.swapusage` 看 swap，然后 kill 客户端 + `./gradlew --stop` 重跑；
- 改脚本后字节级复核续行符（`cat -e` 检查是否被写成 `\\`）。

## 5. 发布（create-release.sh）

```bash
.legadoh/create-release.sh 1.5.1
```

脚本自动：

1. 校验仓库根已有两个标准命名的 APK（缺文件直接报错退出）；
2. `git credential fill` 取 helyel233 token；
3. 按 tag `H-<x.y>` 查询已有 Release：**已存在则先 DELETE 全部旧 APK asset 再上传（幂等重发，修正包直接重跑即可）；不存在则创建**，正文取仓库根 `CHANGELOG.md`（JSON 由 python 生成，无需手写 body 文件）；
4. 依次上传两个 APK，输出每个 asset 的 `name`/`state`（预期 `uploaded`）。

## 6. 发布后验证

- 两个 asset `state=uploaded`；
- 下载远端 APK 与本地比对 SHA-256（或核对文件大小）；
- `unzip -p <apk> assets/updateLog.md | head` 确认包内含本版条目；
- 包内 `output-metadata.json` 核对 versionName=`H.<x.y>`、versionCode=`29860000+提交数`；
- 应用内「检查更新」能发现新版本（依赖资产文件名 `<x.y>_<CODE>` 连续段 + versionCode 判定）。

## 7. 纠错

- 错误 tag：`git push origin --delete <tag>` 删远端 + `git tag -d <tag>` 删本地；如已产生 Release，先调 GitHub API 删除 Release 再删 tag。
- 版本号已发布但内容有误：修好后重新构建双包，直接重跑 `create-release.sh <同版本号>`（脚本幂等删旧 asset 重传）。
- 上传 409/422：确认没有同名 asset 残留，重跑脚本即可（脚本会先清旧 asset）。
- 禁止恢复任何 Gitee 渠道代码、`legadoh-3.x` 时间戳版本号或「第 N 版」编号；发现残留按本文件清理。

## 8. 已移除的能力（不要回头）

- **GitHub Actions 发布**：`android-fast-release.yml`、`sync-release-gitee.yml` 已删除；CI 不再是发布渠道。
- **Gitee 渠道**：`tools/sync_gitee_release.py`、其测试、workflow 同步步骤、应用内更新策略常量与「更新通道」UI 区块均已移除；加速管理对话框现在只管理 GitHub 加速代理。
- **debug 版 CI 构建**：`android-fast-debug*.yml` 仅产出 CI artifact 供调试，与本发布流程无关。
