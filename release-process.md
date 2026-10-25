# Release 发版手册（legado-H）

本文件记录 legado-H 的发版流程与约定。APK 通过 `.legadoh/build-signed.sh`
与 `.legadoh/create-release.sh` 产出并上传到 GitHub Release。

## 触发

用户说「提交改动到github并release apk」即执行完整发版流程。
**本文件只定义流程与约定，不触发发版本身。**

## 版本号四则（main 线）

- 仅特性 → `minor + 0.1`，`patch` 清零为 `0.0.0`
- 特性 + 修复 → `minor + 0.1`，`patch` 定 `0.0.1`（跳过如 `1.x.0`）
- 仅修复 / 优化 / 纯 refactor → `patch` 累加（`0.0.1` 步进）
- 重大改版 → `+1.0.0`

判断要点：看本次相对上一个 tag 的改动是「新功能」还是「修/优」。
`main` 不规避 v5 分支的命名空间，靠 `versionCode` 判定更新，直接进位覆盖即可。

## 发版步骤

1. `git status` / `git diff --stat HEAD` 确认自上一个 tag 的改动与性质，定版本号。
2. 在 `CHANGELOG.md` 顶部、`app/src/main/assets/updateLog.md` 顶部各插入当前版本小节
   （`updateLog.md` 保留全量历史，作为 App 内「更新日志」）。
3. `git add -A && git commit -m "..."` 提交（commit message 带 `定稿 X.Y.Z 日志`）。
4. 直连推送 main（代理常未运行，用 `git -c http.proxy= -c https.proxy= push`，偶发
   HTTP2/443 抖动就重试或加 `-c http.version=HTTP/1.1`）。
5. `git tag -a "H-X.Y.Z" -m "..."`，直连 `git push origin "H-X.Y.Z"`（for 循环重试 3 次确认远端存在）。
6. 构建双包：
   - 正式：` .legadoh/build-signed.sh X.Y.Z`（自动 `versionCode = 29860000 + 提交数`，
     必须严格大于上一版）。
   - Debug：`  ./gradlew --stop && ./gradlew :app:assembleAppDebug -PVERSION_NAME="H.X.Y.Z" -PVERSION_CODE=<同上> -q`，
     随后手动把产物重命名为 `legado_app_H.X.Y.Z_<code>_LegadoH_debug.apk`。
   - 把 release 产物也重命名为 `..._LegadoH_arm64.apk`，放到仓库根。
7. 校验：`apksigner verify --print-certs`、`aapt2 dump badging`（versionName/Code）、
   `unzip -p ... assets/updateLog.md | grep 版本号`。
8. 清理：按「仅留最新版」删除上一个版本的两条 APK。
9. 上传：`unset http_proxy https_proxy HTTPS_PROXY HTTP_PROXY && .legadoh/create-release.sh X.Y.Z`。
10. 比对本地与上传返回的 `sha256`，确认 `git status` 干净。

## 约定（务必遵守）

### ① 不强制重跑 Kotlin 编译

说「提交改动到github并release apk」时，**不要单独跑**
`gradlew :app:compileAppDebugKotlin --rerun-tasks` 之类的全量编译校验。
release 构建（`build-signed.sh` / `assembleAppDebug`）本身会编译，正确性由构建保证，
省下约 18 分钟。除非改动涉及危险的编译期逻辑且构建未覆盖，否则不额外编译。

### ② GitHub Release 正文只放当前版本

`create-release.sh` 已经从 `CHANGELOG.md` 中**只抽取当前版本的 `## X.Y.Z 版` 章节**
作为 Release 正文，**不再塞入历史一长串更新信息**（自下一个 release 起生效）。
`CHANGELOG.md` 仍保留全量历史（项目级变更日志），App 内 `updateLog.md` 也保留全量
（App 内「更新日志」需要）。只有 GitHub Release 页面显示单版本说明。

> 若某次需要把历史也贴进 Release，临时改脚本或手动编辑 Release 正文即可，
> 但不作为默认行为。

## 其它

- 仓库根只保留「当前最新版」的 arm64 + debug 两条 APK，发布新版本时删旧的。
- 签名：正式包 `CN=LegadoH`，debug 包 `CN=Android Debug`。
- 网络：推送 / 上传走直连；tag 推送抖动用重试 + HTTP/1.1。
