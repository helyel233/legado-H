# legado-H 发布流程规范

> 本文件是 legado-H 发布版本的**唯一规范**。每次 release 都必须遵循本文，不要凭记忆或照抄 archive（阅读原版）的旧流程；旧的 `legadoh-3.x` 时间戳版本方案自 1.0 起废弃。

## 0. 关键前提

- **legado-H 是 archive(阅读) 的独立 fork，不继承 archive 的 `3.x` 版本主线，也不延续旧「第 N 版 / legadoh-3.x」编号。** 一切版本自 `1.0` 起（tag `H-1.0` 已发布）。
- **正式发布一律走 GitHub Actions**（`.github/workflows/android-fast-release.yml`），在 GitHub 网页手动 dispatch 触发。
- 本地 `.legadoh/` 目录仍保留正式签名密钥（`keystore.properties` + `legadoh-release.jks`，均已 gitignore），可用于本地出正式签名包应急；但**不得以本地构建替代 CI 作为发布渠道**。
- `docs/移植计划.md` 是本地私有规划文档，已在 `.gitignore` 中（第 49 行），发布提交不要 `git add` 它、也不要推送。本文件（release-process.md）则要提交进仓库。

## 1. 版本规则

| 项 | 规则 | 示例 |
|---|---|---|
| `versionName` | `H.<major>.<minor>`；常规功能/修复 +minor，重大改版/破坏性变更 +major | `H.1.0`、`H.1.1`、`H.2.0` |
| git tag | `H-<major>.<minor>`（annotated，与 versionName 成对） | `H-1.0` |
| `versionCode` | `29860000 + git rev-list --count HEAD`（基线偏移 + 提交数，CI 自动计算） | `29862140`（2140 个提交时） |
| Release 标题 | `legado-H <major>.<minor>` | `legado-H 1.0` |
| Release 正文 | `CHANGELOG.md`（仓库根目录） | — |
| APK 文件名 | `legado-<abi>_app_H.<x.y>_<CODE>.apk`（CI 自动生成） | `legado-arm64-v8a_app_H.1.0_1337.apk` |

- CHANGELOG 章节命名自 1.0 起为 `## <major>.<minor> 版（日期）`，如 `## 1.0 版（2026-10-07）`；1.0 之前的旧「第 N 版」章节保留作历史记录，不再新增。
- `app/build.gradle` 默认 `version = "H.dev." + releaseTime()` 仅作本地调试兜底；发布一律由 CI 传 `-PVERSION_NAME=H.x.y -PVERSION_CODE=<基线+提交数>` 覆盖，不要让 archive 的 `3.` 出现在任何发布产物中。

### 版本号与包名解析约束（应用内检查更新依赖）

- 应用内检查更新从 GitHub Releases 资产文件名解析版本（`AppUpdate.versionInfoFromFileName`，正则 `(\d+(?:\.\d+)+)_(\d+)`）。**资产文件名必须保持 `<x.y>_<CODE>` 连续段**，CI 命名已满足；手工上传/改名时不得破坏。
- `H.` 前缀的 versionName 不以数字开头，不参与字符串比较；新旧版本判定**完全依赖 versionCode**（基线偏移 29860000 + 提交数，单调递增；29860000 高于全部旧 `legadoh-3.x` 的 epoch 分钟 code，保证旧版用户能收到更新提示），因此同一版本重复构建不产生新 versionCode，重复 dispatch 前必须确认版本号已 bump。
- 发布前冲突检查：`git log --oneline -6` + `git tag -l` 确认最新 tag，新版本号（minor/major）必须严格递增，不得沿用其他会话可能已发布的版本号。

## 2. 发布前准备（全部完成后再触发 CI）

1. **定稿 `CHANGELOG.md`**：顶部新增 `## <x.y> 版（日期）` 小节并写全本次变更（新增/优化/修复）。⚠️ CI 直接以仓库中的 `CHANGELOG.md` 作为 Release 正文与更新通道说明，**必须在触发 CI 前定稿并提交**，触发后再改不生效。
2. **更新 `app/src/main/assets/updateLog.md`**：在「## LegadoH」标题下插入新版本条目（格式沿用 `**vH.<x.y> · {YYYY/MM/DD}**` + 要点列表）。该文件随 APK 打包，供「我的-关于-更新日志」显示。⚠️ 必须在触发 CI 前更新并提交，否则包内更新日志滞后（历史上曾因此重建双包重传，浪费约 20 分钟）。其下方的 v3.x 历史条目保留作存档，不再新增。
3. 确认本次改动不含 `docs/移植计划.md`。
4. 确认 `.github/workflows/android-fast-release.yml` 保持 H 风格（见第 5 节）；改动 workflow 后做字节级复核（缩进、续行符是否被写成 `\\`）。
5. 功能代码按 Conventional Commits 分功能提交；共享文件（strings.xml 多 locale 变体）用临时删行、提交后再恢复的方式实现原子拆分。

## 3. 提交与打 tag

```bash
git add CHANGELOG.md app/src/main/assets/updateLog.md <其它发布相关源码>
git commit -m "release: 定稿 <x.y> 版更新日志"
git tag -a "H-<x.y>" -m "<x.y> 版：<一句话摘要>"
git push origin main
git push origin "H-<x.y>"
```

- 不要 force-push `main`。
- `git push` 偶发 Recv timeout，等待约 20 秒重试即可。
- 打 tag 会将提交数固化为 versionCode，**必须先推送含 CHANGELOG/updateLog 的最终提交，再打 tag**。

## 4. 构建并发布 APK（走 CI）

工作流 `.github/workflows/android-fast-release.yml`：

1. 在 GitHub 仓库 **Actions → Android Fast Release → Run workflow** 手动触发，输入版本号（不含 H 前缀，如 `1.0`）。
2. 前置 secret（仓库 Settings → Secrets and variables → Actions）：
   - `CI_DEBUG_KEY_STORE_B64`、`CI_DEBUG_KEY_ALIAS`、`CI_DEBUG_KEY_PASSWORD`、`CI_DEBUG_STORE_PASSWORD`（CI 内签名 keystore，base64 解码为 `app/key.jks`）；
   - `GITEE_TOKEN`、`GITEE_REPO` 可选，未配置则自动跳过 Gitee 同步。
3. 工作流依次产出 `arm64-v8a` 与 `armeabi-v7a` 两个 release APK，以 tag `H-<x.y>` 创建 GitHub Release 并上传 APK。
4. **重跑幂等**：同名 tag 重复 dispatch 时，工作流会先 DELETE 该 Release 的旧 APK asset 再上传（旧流程中「同名 POST 409、需先 DELETE」的经验已固化进 workflow），因此修正包重发只需重新 dispatch，无需手工删 asset。
5. 如需修正 Release 正文：改 `CHANGELOG.md` 后可用 `gh release edit H-<x.y> --notes-file CHANGELOG.md`（更新通道同理）。

### 发布后验证

- Release 资产 `state=uploaded`，两个 ABI 的 APK 均在；
- 下载远端 APK 与本地比对 SHA-256（或核对文件大小）；
- 安装包内自检：`unzip -p <apk> assets/updateLog.md | head` 确认含本版条目；
- 用包内 `output-metadata.json` 核对 versionName=`H.<x.y>`、versionCode=29860000+提交数。

## 5. `android-fast-release.yml` 的 H 风格要点

- 触发方式：`workflow_dispatch`，输入 `version`（如 `1.0`）。
- 版本与 tag：

  ```yaml
  - name: Prepare release version
    id: version
    run: |
      VERSION_NAME="H.${{ inputs.version }}"
      # 基线偏移：29860000 高于全部旧版（epoch 分钟数）versionCode，保证应用内检查更新能识别为更新
      VERSION_CODE=$((29860000 + $(git rev-list --count HEAD)))
      TAG_NAME="H-${{ inputs.version }}"
      {
        echo "name=${VERSION_NAME}"
        echo "code=${VERSION_CODE}"
        echo "tag=${TAG_NAME}"
      } >> "$GITHUB_OUTPUT"
  ```

- 构建矩阵：先 `:app:clean :app:assembleAppRelease -Pabi=arm64-v8a`，再以 `-Pabi=armeabi-v7a` 重复构建，产物复制到 `release-apks/`。
- Release 发布（`softprops/action-gh-release@v2`）：`tag_name=H-<x.y>`、`name=legado-H <x.y>`、`body_path: CHANGELOG.md`、`make_latest: true`、`generate_release_notes: false`。
- 更新通道：tag `latest-arm64-release`，标题「legado-H 更新通道」，说明同样取 `CHANGELOG.md`；每次发布 DELETE 旧 APK asset 后 `--clobber` 重传。
- Gitee 同步：由 `GITEE_TOKEN` + `GITEE_REPO` 两个 secret 驱动（`GITEE_REPO` 形如 `helyel233/legado-H`），未配置则跳过；同步失败不影响已发布的 GitHub Release（脚本显式 `exit 0`）。仓库地址一律用 `${GITEE_REPO}`，不硬编码任何第三方仓库。

## 6. 本地构建（应急/调试用）

- 正式签名包：`.legadoh/build-signed.sh`（自动注入 `keystore.properties` 四个 `-PRELEASE_*` 参数与 JAVA_HOME，输出 arm64 release）。
- 历史陷阱仍适用于本地构建：
  - release 与 debug **严禁并行构建**（共享 Gradle daemon 堆内存会 OOM）；release 跑完先 `./gradlew --stop` 再构建 debug。
  - `gradle.properties` 保持 `jvmargs=-Xmx4g`、`configuration-cache=false`（configuration-cache 与含 WeakHashMap 的自定义 task 不兼容）。
  - 任何变体构建都**显式传 `-PVERSION_NAME/-PVERSION_CODE`**，否则走 `H.dev.` 默认方案。
  - 构建输出文件名不含变体尾段，需手动重命名并 `apksigner verify --print-certs` 验签。
- 本地产物仅供自测，**不作为发布渠道**。

## 7. 纠错

- 错误 tag：`git push origin --delete <tag>` 删远端 + `git tag -d <tag>` 删本地；如已产生 Release，先在 GitHub 删除 Release 再删 tag。
- 误用了 archive 风格（`legadoh-3.` / `3.`）或旧「第 N 版」编号：按第 1 节重建为 `H-<x.y>`；已上传的 `3.` 产物从 Release 中删除。
- CI 签名失败：优先核对四个 `CI_DEBUG_*` secrets 是否齐全、base64 是否为完整 keystore。

## 8. 与 Max 移植的关系

- 移植改动来自本地 `Legado_Max_compare` 仓库，按需移植；细节与待办见 `docs/移植计划.md`（不推送）。
