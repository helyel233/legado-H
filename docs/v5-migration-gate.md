# V5 页面迁移验收门（Migration Gate）

> 目的：把「用户测出问题」变成「迁移时拦住」。每一个页面/弹窗/二级页声称「迁移完成」前，
> 必须逐项过完本门。2026-10-12 的三起事故（Manifest 漏登记、选择操作条 0 高度、
> 批量操作横滑溢出）没有一起是编译能拦住的——全靠这道门。

适用对象：所有 `ui/host/` 下的页面与 Activity，以及任何「V5 原生」声明。

---

## A. 功能完整性（对照 1.7.3）

基准：`origin/archive/pre-1.7.3` 分支对应文件。逐项提取并打勾：

1. **菜单**：旧实现的 `res/menu/*.xml`（该页面引用的每个菜单）+ `onCompatOptionsItemSelected`
   每个分支 → V5 里有等价入口（顶栏动作/⋮ 溢出/选择操作条），且行为一致。
2. **弹窗**：旧实现的 `showDialogFragment(...)` / `showComposeXxxDialog(...)` 全部调用点
   → V5 有等价弹层（AppBottomSheet/AppDialog/AppMenuSheet/旧 ComposeDialogFragment 复用）。
3. **跳转**：旧实现的 `startActivity` / `AppRoute` 调用点 → V5 有同等跳转。
4. **状态与筛选**：旧实现的搜索/分组/排序/筛选状态 → V5 全保留。
5. **数据层**：确认复用旧 ViewModel/DAO Flow，无重新实现（重新实现 = 双重真相，红灯）。

输出：在 `docs/ui-v5-destinations.md` 对应行补「功能对照」结论（✅/⚠️差什么/❌缺什么）。

## B. 可发现性（UX 红线）

1. **批量/选择模式**：底部操作条 = 常用 ≤5 项固定 + 「更多 ⋮」溢出（竖排全量）。
   禁止把 >6 个操作平铺进横滑条（2026-10-12 事故：后半段滑出屏幕用户找不到）。
2. **隐藏操作必须有提示**：长按多选、溢出菜单、破坏性操作（删除/清空）至少满足其一：
   首次进入时一条 toast/ Snackbar 引导，或行内可见入口。
3. **破坏性操作要确认**：删除/清空类必须有确认弹窗，危险按钮用 error 色。
4. **危险操作不在主操作位**：删除不放最左、不用最醒目色块。

## C. 注册与构建（机械检查，必须全过）

```bash
# 1) 每个 ui/host 下的 Activity 必须在 manifest 登记
grep -oP '(?<=class )\w+Activity' app/src/main/java/io/legado/app/ui/host/*.kt | sort -u \
  | while read a; do grep -q "$a" app/src/main/AndroidManifest.xml || echo "MISSING: $a"; done
# 2) V5 页面不得 import 旧 View 弹层体系（DialogFragment/BaseDialogFragment）
grep -rn "BaseDialogFragment\|androidx.fragment.app.DialogFragment" app/src/main/java/io/legado/app/ui/host/ | grep -v "supportFragmentManager" || true
# 3) V5 页面不得有硬编码中文（白名单：debug 面板/组件博物馆/占位演示）
grep -rn '"[^"]*[一-龥][^"]*"' app/src/main/java/io/legado/app/ui/host/*.kt | grep -v HostDebugPanel | grep -v HostMuseumContent | grep -v HostDestinations || true
```

## D. 布局结构（防 0 高度/挤出类 bug）

1. `HostV5Page`/`AppPage` 根节点是 `fillMaxSize` —— **放在 Column 里必须包 `Box(Modifier.weight(1f))`**，
   否则后面的兄弟节点（底部操作条等）分到 0 高度直接消失。
2. 底部固定操作区用插槽（bottomBar）或 weight 让位，禁止依赖滚动容器底部 padding 硬撑。
3. 列表 `contentPadding.bottom` 要为底部操作条预留（操作条存在时）。

## E. 状态与兼容

1. 关键状态（当前 Tab、搜索词、选择集、排序）进程死后可恢复：`rememberSaveable` 或
   `savedInstanceState`。
2. 从 Application 上下文启动 Activity 必须带 `FLAG_ACTIVITY_NEW_TASK`（Android 16 直接崩）。
3. 系统返回键语义：选择模式下返回 = 退出选择模式，不是退出页面。

## F. 国际化

1. 用户可见文案一律 `strings.xml`（`v5_` 前缀），含插值用格式串 + `stringResource(args)`。
2. 非 Composable 上下文用 `context.getString()`。
3. 新增字符串同步考虑 10 个语言变体的翻译债（记入 `docs/v5-retirement.md`）。

---

## 签署格式（迁移完成时贴到提交信息或 destinations 表）

```
迁移验收：[页面名] A1-A5 ✅ / B1-B4 ✅ / C1-C3 ✅ / D1-D3 ✅ / E1-E3 ✅ / F1-F3 ✅
遗留：[⚠️ 项及原因]
```
