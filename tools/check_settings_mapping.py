#!/usr/bin/env python3
"""设置体系「四处映射」一致性静态校验（可重复执行，零依赖）。

检查的是 docs/settings-optimization-plan.html 第 5 节列出的四处映射：

  1. ConfigTag        -> ConfigActivity.when(configTag) 的 Fragment 分支
  2. ConfigTag        -> ConfigActivity.breadcrumbOf()   的父级分支
  3. 「我的」页一级行  -> MyFragment.handleRowClick()     的跳转分支
  4. SettingsSearchIndex.PageSource.ownerKey -> 真实存在的一级行 key

额外检查：
  5. breadcrumbOf 声明的父级分区 == ownerKey 所在分区（避免「搜索挂在 A 区、
     顶栏却显示 B 区」的割裂）
  6. 一级行 key 唯一、PageSource 的 configTag 都是已定义的 ConfigTag

运行时只做只读解析；解析不到某个文件时直接报错退出，不静默放行。
用法：python3 tools/check_settings_mapping.py [--root /path/to/repo]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

BASE = "app/src/main/java/io/legado/app"
FILES = {
    "tag": f"{BASE}/ui/config/ConfigTag.kt",
    "activity": f"{BASE}/ui/config/ConfigActivity.kt",
    "my": f"{BASE}/ui/main/my/MyFragment.kt",
    "index": f"{BASE}/ui/config/SettingsSearchIndex.kt",
    "prefer": f"{BASE}/constant/PreferKey.kt",
}

# 形如 "web_dav_setting" / ConfigTag.AI_CONFIG / PreferKey.themeMode
KEY_TOKEN = r'(?:"[A-Za-z_][A-Za-z0-9_.]*"|(?:ConfigTag|PreferKey)\.[A-Za-z_][A-Za-z0-9_]*)'


def read(root: Path, rel: str) -> str:
    p = root / rel
    if not p.is_file():
        raise SystemExit(f"[FATAL] 找不到源码文件：{p}")
    return p.read_text(encoding="utf-8")


def parse_consts(src: str, prefix: str) -> dict[str, str]:
    """const val NAME = "value" -> {prefix.NAME: value}"""
    out = {}
    for name, value in re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]+)"', src):
        out[f"{prefix}.{name}"] = value
    return out


def resolve(token: str, consts: dict[str, str]) -> str:
    token = token.strip()
    if token.startswith('"'):
        return token.strip('"')
    if token in consts:
        return consts[token]
    raise SystemExit(f"[FATAL] 无法解析 key 表达式：{token}（常量未在 ConfigTag/PreferKey 定义）")


def slice_between(src: str, start_pat: str, end_pat: str | None) -> str:
    m = re.search(start_pat, src)
    if not m:
        raise SystemExit(f"[FATAL] 找不到代码段起点：{start_pat}")
    if end_pat:
        e = re.search(end_pat, src[m.end():])
        return src[m.end(): m.end() + e.start()] if e else src[m.end():]
    return src[m.end():]


def strip_line_comments(src: str) -> str:
    return "\n".join(re.sub(r"//.*$", "", line) for line in src.splitlines())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    args = ap.parse_args()
    root = Path(args.root)
    src = {k: read(root, v) for k, v in FILES.items()}

    consts = {}
    consts.update(parse_consts(src["tag"], "ConfigTag"))
    consts.update(parse_consts(src["prefer"], "PreferKey"))
    tags = {k: v for k, v in consts.items() if k.startswith("ConfigTag.")}
    tag_values = set(tags.values())
    problems: list[str] = []
    notices: list[str] = []

    # ---- 1. ConfigTag -> when(configTag) 的 Fragment 分支 ----
    when_src = slice_between(src["activity"], r"when\s*\(configTag\)\s*\{", r"else\s*->\s*finish\(\)")
    when_tags = set(re.findall(r"ConfigTag\.(\w+)", strip_line_comments(when_src)))
    when_names = {f"ConfigTag.{n}" for n in when_tags}

    # ---- 2. ConfigTag -> breadcrumbOf 父级分支（支持多行合并分支）----
    crumb_src = slice_between(src["activity"], r"private\s+fun\s+breadcrumbOf", r"fun\s*<T\s*:\s*Fragment>")
    crumb_src = strip_line_comments(crumb_src)
    crumb_tags: set[str] = set()
    crumb_parent: dict[str, str] = {}   # ConfigTag.NAME -> config_category_xxx
    pending: list[str] = []
    for line in crumb_src.splitlines():
        found = re.findall(r"ConfigTag\.(\w+)", line)
        if found:
            pending.extend(f"ConfigTag.{n}" for n in found)
        m = re.search(r"listOf\(([^)]*)\)", line)
        if m and pending:
            parents = re.findall(r"R\.string\.(config_category_\w+)", m.group(1))
            parent = parents[0] if parents else ""
            for name in pending:
                crumb_tags.add(name)
                crumb_parent[name] = parent
            pending = []
    # 上面的逐行解析只覆盖单行 listOf；DNS 分支是换行后接 listOf，故再补一遍兜底：
    # 在整个 breadcrumbOf 区间里，用「branch -> listOf(...)」的跨行模式重扫一次
    for m in re.finditer(r"((?:ConfigTag\.\w+\s*,?\s*)+)->\s*listOf\(([^)]*)\)", crumb_src, re.S):
        names = [f"ConfigTag.{n}" for n in re.findall(r"ConfigTag\.(\w+)", m.group(1))]
        parents = re.findall(r"R\.string\.(config_category_\w+)", m.group(2))
        parent = parents[0] if parents else ""
        for name in names:
            crumb_tags.add(name)
            crumb_parent[name] = parent

    # ---- 3. 一级行 key / 分区 / handleRowClick ----
    sections_src = slice_between(src["my"], r"private\s+fun\s+buildSections", r"private\s+fun\s+actionRow")
    sections_src = strip_line_comments(sections_src)
    row_keys: list[str] = []
    key_section: dict[str, str] = {}
    current_section = ""
    # 按出现顺序遍历：分区标题行 + actionRow(...) / key = ...
    for m in re.finditer(
        r"getString\(R\.string\.(config_category_\w+)\)|actionRow\(\s*(" + KEY_TOKEN + r")|key\s*=\s*(" + KEY_TOKEN + r")",
        sections_src,
    ):
        if m.group(1):
            current_section = m.group(1)
        else:
            token = m.group(2) or m.group(3)
            value = resolve(token, consts)
            row_keys.append(value)
            key_section[value] = current_section

    click_src = slice_between(src["my"], r"private\s+fun\s+handleRowClick", r"/\*\*")
    click_src = strip_line_comments(click_src)
    click_keys = set()
    for token in re.findall(KEY_TOKEN, click_src):
        click_keys.add(resolve(token, consts))
    # 特殊 kind 行不走 handleRowClick，由 MySettingsScreen 的独立回调承接
    special_rows = {"themeMode", "autoTaskService"}

    # ---- 4. PageSource(ownerKey, configTag) ----
    index_src = strip_line_comments(src["index"])
    page_sources: list[tuple[str, str]] = []
    for m in re.finditer(r"PageSource\(\s*(" + KEY_TOKEN + r")\s*,\s*(" + KEY_TOKEN + r")\s*\)", index_src):
        page_sources.append((resolve(m.group(1), consts), resolve(m.group(2), consts)))

    # ---------- 判定 ----------
    missing_when = sorted(set(tags) - when_names)
    extra_when = sorted(when_names - set(tags))
    missing_crumb = sorted(set(tags) - crumb_tags)
    extra_crumb = sorted(crumb_tags - set(tags))
    if missing_when:
        problems.append(f"ConfigTag 缺 ConfigActivity.when 分支：{missing_when}")
    if extra_when:
        problems.append(f"when 分支引用了未定义的 ConfigTag：{extra_when}")
    if missing_crumb:
        problems.append(f"ConfigTag 缺 breadcrumbOf 父级：{missing_crumb}")
    if extra_crumb:
        problems.append(f"breadcrumbOf 引用了未定义的 ConfigTag：{extra_crumb}")

    dup_rows = sorted({k for k in row_keys if row_keys.count(k) > 1})
    if dup_rows:
        problems.append(f"一级行 key 重复：{dup_rows}")
    row_set = set(row_keys)
    missing_click = sorted(row_set - click_keys - special_rows)
    if missing_click:
        problems.append(f"一级行缺 handleRowClick 跳转：{missing_click}")

    orphan_owners = sorted({o for o, _ in page_sources} - row_set)
    if orphan_owners:
        problems.append(f"搜索 ownerKey 悬空（无对应一级行）：{orphan_owners}")
    bad_tags = sorted({t for _, t in page_sources} - tag_values)
    if bad_tags:
        problems.append(f"PageSource.configTag 未定义：{bad_tags}")
    missing_index = sorted(tag_values - {t for _, t in page_sources})
    if missing_index:
        problems.append(f"ConfigTag 未进入搜索索引：{missing_index}")

    # 5. 面包屑父级分区 vs ownerKey 所在分区
    for owner, tag in page_sources:
        parent = crumb_parent.get(f"ConfigTag.{next((n for n, v in tags.items() if v == tag), '')}", "")
        section = key_section.get(owner, "")
        if parent and section and parent != section:
            problems.append(f"分区不一致：ownerKey={owner} 在 {section}，但 {tag} 的面包屑父级是 {parent}")

    # 提示项（非阻断）
    unique_owners = {o for o, _ in page_sources}
    if len(unique_owners) != len(page_sources):
        notices.append(f"{len(page_sources)} 个 PageSource 只有 {len(unique_owners)} 个唯一 ownerKey（多处复用同一一级行）")
    notices.append(f"breadcrumbOf 用 {len(re.findall(r'->', crumb_src)) - 1} 个 case 覆盖 {len(crumb_tags)} 个 Tag（含合并分支）")

    # ---------- 输出 ----------
    print("=" * 64)
    print("设置体系四处映射一致性校验")
    print("=" * 64)
    print(f"  ConfigTag 常量         : {len(tags)}")
    print(f"  when 覆盖              : {len(when_names & set(tags))}/{len(tags)}")
    print(f"  breadcrumbOf 覆盖      : {len(crumb_tags & set(tags))}/{len(tags)}")
    print(f"  「我的」页一级行        : {len(row_keys)}（唯一 {len(row_set)}）")
    print(f"  handleRowClick 覆盖    : {len(row_set & click_keys)}/{len(row_set - special_rows)} 个 Action 行（另有 {len(special_rows)} 个特殊 kind 行走独立回调）")
    print(f"  搜索 PageSource        : {len(page_sources)}（唯一 ownerKey {len(unique_owners)}）")
    print(f"  悬空 ownerKey          : {len(orphan_owners)}")
    print("-" * 64)
    for n in notices:
        print(f"  [提示] {n}")
    if problems:
        for p in problems:
            print(f"  [FAIL] {p}")
        print(f"\n结论：不一致，共 {len(problems)} 处问题")
        return 1
    print("  [PASS] 四处映射一致：Tag↔Fragment / Tag↔面包屑 / 一级行↔跳转 / ownerKey↔一级行 / 分区归属")
    print("\n结论：一致，零断链")
    return 0


if __name__ == "__main__":
    sys.exit(main())
