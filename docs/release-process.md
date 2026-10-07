# legado-H 发布流程（release-process）

> 本文件是 legado-H 发布版本的**唯一规范**。每次 release 都必须遵循本文，不要凭记忆或照抄 archive（阅读原版）的流程。

## 0. 关键前提（务必先看）

- **legado-H 是 archive(阅读) 的独立 fork，不继承 archive 的 `3.x` 版本主线。** H 使用自己的版本号，绝不出现 `3.` 前缀或与 archive 对齐的版本。
- 本地环境**没有签名密钥**（`app/key.jks` 不存在、`gradle.properties` 无 `RELEASE_STORE_*`），且**未登录 `gh`**。因此**无法在本地出 release APK**，发布必须走 GitHub Actions。
- `docs/移植计划.md` 是本地私有规划文档，**已在 `.gitignore` 中，发布提交不要 `git add` 它、也不要推送**。本文件（release-process.md）则要提交进仓库。

## 1. 版本规则（H 专属）

| 项 | 规则 | 示例（2026-10-07 13:11 上海时区） |
|---|---|---|
| `versionName` | `H.<yy.MMddHH>` | `H.26.100713` |
| git tag | `H-<yyMMddHHmm>`（annotated） | `H-2610071311` |
| `versionCode` | 沿用 `app/build.gradle`：`10000 + git commit 数`（单调递增即可） | — |

`app/build.gradle` 中默认 `version = "3." + releaseTime()`，**发布前必须改为 H 前缀**，或在构建时传 `-PVERSION_NAME=H.26.100713` 覆盖。不要保留 archive 的 `3.`。

## 2. 发布前准备

1. `CHANGELOG.md` 顶部「第 N 版（日期，进行中）」去掉「进行中」，定稿。
2. 确认本次改动不含 `docs/移植计划.md`。

## 3. 提交与打 tag

```bash
git add CHANGELOG.md <其它发布相关源码>
git commit -m "docs: 定稿第 N 版更新日志（发布 H-<yyMMddHHmm>）"
git tag -a "H-<yyMMddHHmm>" -m "第 N 版：<一句话摘要>"
git push origin main
git push origin "H-<yyMMddHHmm>"
```

> 不要 force-push `main`。

## 4. 构建并发布 APK（走 CI）

工作流 `.github/workflows/android-fast-release.yml` 需按 H 适配（见第 5 节）。适配后：

1. 在 GitHub 仓库 **Actions → Android Fast Release → Run workflow** 手动触发。
2. 前置 secret（仓库 Settings → Secrets）：`CI_DEBUG_KEY_STORE_B64`、`CI_DEBUG_KEY_ALIAS`、`CI_DEBUG_KEY_PASSWORD`、`CI_DEBUG_STORE_PASSWORD`（用于 CI 内签名）。
3. 工作流产出 `arm64-v8a` 与 `armeabi-v7a` 两个 release APK，并以 tag `H-<时间戳>` 创建 GitHub Release、上传 APK、更新 `latest-arm64-release` 更新通道。
4. Release 正文使用 `CHANGELOG.md`（见第 5 节 `body_path`）。

## 5. `android-fast-release.yml` 的 H 适配要点

> 该文件目前是 archive 风格（版本名 `3.`、tag `archive-v3-`、gitee 目标 `zziji/legado`、Release 正文为硬编码 archive 说明）。发布前须改为如下：

- 版本与 tag（约 line 38-48）：

  ```yaml
  - name: Prepare release version
    id: version
    run: |
      TS="$(TZ=Asia/Shanghai date +%y%m%d%H%M)"
      VERSION_NAME="H.${TS:0:2}.${TS:2}"
      VERSION_CODE=$(($(date -u +%s) / 60))
      TAG_NAME="H-${TS}"
      {
        echo "name=${VERSION_NAME}"
        echo "code=${VERSION_CODE}"
        echo "tag=${TAG_NAME}"
      } >> "$GITHUB_OUTPUT"
  ```

- 删除原来的 `release-notes.md` 生成 heredoc（约 line 50-87），Release 正文改读 CHANGELOG：

  ```yaml
  - name: Publish versioned release
    uses: softprops/action-gh-release@v2
    with:
      tag_name: ${{ steps.version.outputs.tag }}
      name: legado-H ${{ steps.version.outputs.name }}
      prerelease: false
      make_latest: true
      generate_release_notes: false
      files: release-apks/*.apk
      body_path: CHANGELOG.md
  ```

- gitee 同步（约 line 164-263）：H 不用 `zziji/legado`。整段开头加守卫，未配置则跳过：

  ```bash
  if [ -z "$GITEE_TOKEN" ] || [ -z "$GITEE_REPO" ]; then
    echo "GITEE 未配置，跳过 Gitee 同步。"
    exit 0
  fi
  ```

  并把其中的 `zziji/legado` 全部替换为 `${GITEE_REPO}`（由 secret 提供，例如 `helyel233/legado-H`）。

## 6. 纠错

- 错误 tag：`git push origin --delete <tag>` 删远端 + `git tag -d <tag>` 删本地。
- 误用了 archive 风格（`legadoh-3.` / `3.`）：按第 1 节重建为 `H-<时间戳>`。

## 7. 与 Max 移植的关系

- 移植改动来自本地 `Legado_Max_compare` 仓库，按需移植；细节与待办见 `docs/移植计划.md`（不推送）。
