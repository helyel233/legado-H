# legado-H 发布流程（release-process）

> 本文件是 legado-H 发布版本的**唯一规范**。每次 release 都必须遵循本文，不要凭记忆或照抄 archive（阅读原版）的流程。

## 0. 关键前提（务必先看）

- **legado-H 是 archive(阅读) 的独立 fork，不继承 archive 的 `3.x` 版本主线，也不延续旧「第 N 版 / legadoh-3.x」编号。** 一切版本自 `1.0` 起。
- 本地环境**没有签名密钥**（`app/key.jks` 不存在、`gradle.properties` 无 `RELEASE_STORE_*`），且**未安装/未登录 `gh`**。因此**无法在本地出 release APK**，发布必须走 GitHub Actions，并在 GitHub 网页手动 dispatch。
- `docs/移植计划.md` 是本地私有规划文档，**已在 `.gitignore` 中，发布提交不要 `git add` 它、也不要推送**。本文件（release-process.md）则要提交进仓库。

## 1. 版本规则

| 项 | 规则 | 示例 |
|---|---|---|
| `versionName` | `H.<major>.<minor>`；常规功能/修复 +minor，重大改版/破坏性变更 +major | `H.1.0`、`H.1.1`、`H.2.0` |
| git tag | `H-<major>.<minor>`（annotated，与 versionName 成对） | `H-1.0` |
| `versionCode` | `git rev-list --count HEAD`（git 提交数，单调递增；CI 自动计算） | — |
| Release 标题 | `legado-H <major>.<minor>` | `legado-H 1.0` |
| Release 正文 | `CHANGELOG.md` 最新一节 | — |

CHANGELOG 章节命名自 1.0 起为 `## <major>.<minor> 版（日期）`，如 `## 1.0 版（2026-10-07）`；1.0 之前的旧「第 N 版」章节保留作历史记录，不再新增。

`app/build.gradle` 默认 `version = "3." + releaseTime()`，仅作本地调试兜底；发布一律由 CI 传 `-PVERSION_NAME=H.x.y -PVERSION_CODE=<提交数>` 覆盖，不要让 archive 的 `3.` 出现在任何发布产物中。

## 2. 发布前准备

1. `CHANGELOG.md` 顶部新增 `## <x.y> 版（日期）` 小节并写全本次变更（新增/优化/修复）。
2. 确认本次改动不含 `docs/移植计划.md`。
3. 确认 `.github/workflows/android-fast-release.yml` 保持 H 风格（见第 5 节）。

## 3. 提交与打 tag

```bash
git add CHANGELOG.md <其它发布相关源码>
git commit -m "release: 定稿 <x.y> 版更新日志"
git tag -a "H-<x.y>" -m "<x.y> 版：<一句话摘要>"
git push origin main
git push origin "H-<x.y>"
```

> 不要 force-push `main`。

## 4. 构建并发布 APK（走 CI）

工作流 `.github/workflows/android-fast-release.yml`（H 风格，见第 5 节）：

1. 在 GitHub 仓库 **Actions → Android Fast Release → Run workflow** 手动触发，输入版本号（不含 H 前缀，如 `1.0`）。
2. 前置 secret（仓库 Settings → Secrets）：`CI_DEBUG_KEY_STORE_B64`、`CI_DEBUG_KEY_ALIAS`、`CI_DEBUG_KEY_PASSWORD`、`CI_DEBUG_STORE_PASSWORD`（用于 CI 内签名）；`GITEE_TOKEN`、`GITEE_REPO` 可选，未配置则自动跳过 Gitee 同步。
3. 工作流产出 `arm64-v8a` 与 `armeabi-v7a` 两个 release APK，并以 tag `H-1.0` 创建 GitHub Release、上传 APK、更新 `latest-arm64-release` 更新通道。
4. Release 正文使用 `CHANGELOG.md`。

## 5. `android-fast-release.yml` 的 H 风格要点

- 触发方式：`workflow_dispatch`，输入 `version`（如 `1.0`）。
- 版本与 tag：

  ```yaml
  - name: Prepare release version
    id: version
    run: |
      VERSION_NAME="H.${{ inputs.version }}"
      VERSION_CODE=$(git rev-list --count HEAD)
      TAG_NAME="H-${{ inputs.version }}"
      {
        echo "name=${VERSION_NAME}"
        echo "code=${VERSION_CODE}"
        echo "tag=${TAG_NAME}"
      } >> "$GITHUB_OUTPUT"
  ```

- Release 发布：

  ```yaml
  - name: Publish versioned release
    uses: softprops/action-gh-release@v2
    with:
      tag_name: ${{ steps.version.outputs.tag }}
      name: legado-H ${{ inputs.version }}
      prerelease: false
      make_latest: true
      generate_release_notes: false
      files: release-apks/*.apk
      body_path: CHANGELOG.md
  ```

- 更新通道：tag `latest-arm64-release`，标题「legado-H 更新通道」，说明同样取 `CHANGELOG.md`。
- Gitee 同步：由 `GITEE_TOKEN` + `GITEE_REPO` 两个 secret 驱动（`GITEE_REPO` 形如 `helyel233/legado-H`），未配置则跳过；仓库地址一律用 `${GITEE_REPO}`，不硬编码任何第三方仓库。

## 6. 纠错

- 错误 tag：`git push origin --delete <tag>` 删远端 + `git tag -d <tag>` 删本地；如已产生 Release，先在 GitHub 删除 Release 再删 tag。
- 误用了 archive 风格（`legadoh-3.` / `3.`）或旧「第 N 版」编号：按第 1 节重建为 `H-<x.y>`。

## 7. 与 Max 移植的关系

- 移植改动来自本地 `Legado_Max_compare` 仓库，按需移植；细节与待办见 `docs/移植计划.md`（不推送）。
