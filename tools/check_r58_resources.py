#!/usr/bin/env python3
"""R58+R59 regression checker: default-resource Englishization.

Verifies, from a reproducible baseline (tools/r58_baseline.json):

  1. all 609 <string> keys exist in values/ and values-zh/ (full coverage)
  2. the 334 pre-existing values-zh values are byte-identical to before, and
     the 275 newly added keys carry the original Chinese raw inner verbatim
  3. FULL text-node scan of EVERY values/ XML (<string> / <item> / <plurals>):
     Chinese hits are exactly the 10 enumerated explicit exemptions (each with
     a reason); non-exempt residue is 0; <item> and <plurals> Chinese hits are 0
  4. the 20 default-side Chinese <item> nodes are Englishized to the exact
     expected English, values-zh/ gained 5 same-named arrays / 17 items with
     the original Chinese verbatim, the 3 pre-existing zh items are untouched,
     array names / item counts / order are unchanged, all 40 non-target arrays
     and both pure-value arrays are byte-identical to HEAD
  5. format placeholders are identical (count + numbering) between zh and en
  6. the 10 exempt values/ entries were left byte-identical; every other one
     of the 609 actually changed
  7. zero duplicate <string> keys within values/ and within values-zh/
  8. every touched XML parses; HTML tags are balanced; Android string escapes
     (quotes/apostrophes/ampersands) are well formed
  9. no Chinese-locale behaviour drift:
     (a) no getString(R.string.*) result is compared against a Chinese literal
         (== / != / .equals / when / setOf / listOf / arrayOf)
     (b) no getStringArray/getTextArray result is compared against any of the
         20 original Chinese item literals (same line or +-4 line window)

Coverage note (R59, replaces the R58 out-of-scope statement):
  values/arrays.xml and values/text_reader.xml contained 20 Chinese <item>
  nodes in 6 string-arrays. They were UI option labels that the English locale
  also read, i.e. the same defect class as the 609 <string> nodes. R59
  Englishized all 20 and completed values-zh/. The "English resources contain
  zero Chinese" assertion now covers ALL text-node types in ALL values/ XML.

Usage:  python3 tools/check_r58_resources.py
Exit:   0 = PASS, 1 = FAIL
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
V = os.path.join(ROOT, "app/src/main/res/values")
VZ = os.path.join(ROOT, "app/src/main/res/values-zh")
JAVA = os.path.join(ROOT, "app/src/main/java")
BASELINE = os.path.join(ROOT, "tools/r58_baseline.json")

STR_TAG = re.compile(r'<string\s+name="([^"]+)"([^>]*)>(.*?)</string>', re.S)
HAN = re.compile(r"[\u4e00-\u9fff]")
FMT = re.compile(r"%(?:\d+\$)?[-+ #0]*(?:\d+)?(?:\.\d+)?[sdfgeExXoc%]")
TAG = re.compile(r"<(/?)([a-zA-Z][\w-]*)(?:\s[^<>]*)?/?>")
ARRAY_TAGS = ("string-array", "array", "plurals", "integer-array")
TEXT_TAGS = ("string", "item", "plurals")

failures: list[str] = []
notes: list[str] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    status = "PASS" if ok else "FAIL"
    print(f"[{status}] {label}" + (f" — {detail}" if detail else ""))
    if not ok:
        failures.append(label + (f": {detail}" if detail else ""))


def parse_inner(inner: str) -> str:
    """Decode Android XML inner text into comparable plain text."""
    try:
        return "".join(ET.fromstring("<x>" + inner + "</x>").itertext())
    except ET.ParseError:
        return inner


def scan(folder: str) -> dict[str, dict]:
    """key -> {file, attrs, raw, parsed} for every <string> in folder."""
    out: dict[str, dict] = {}
    dups: list[str] = []
    for fn in sorted(os.listdir(folder)):
        if not fn.endswith(".xml"):
            continue
        path = os.path.join(folder, fn)
        txt = open(path, encoding="utf-8").read()
        for m in STR_TAG.finditer(txt):
            k = m.group(1)
            rec = {"file": fn, "attrs": m.group(2).strip(),
                   "raw": m.group(3), "parsed": parse_inner(m.group(3))}
            if k in out:
                dups.append(k)
            out.setdefault(k, rec)
    return {"map": out, "dups": dups}


def text_nodes(folder: str) -> list[dict]:
    """Every Chinese text node in EVERY XML of folder, by text-node type.

    Comments and attribute values are not text nodes and are intentionally
    excluded (ElementTree does not surface them as element .text).
    """
    hits: list[dict] = []
    for fn in sorted(os.listdir(folder)):
        if not fn.endswith(".xml"):
            continue
        path = os.path.join(folder, fn)
        root = ET.parse(path).getroot()
        parent: dict[ET.Element, ET.Element] = {
            child: elem for elem in root.iter() for child in elem
        }
        for elem in root.iter():
            if elem.tag not in TEXT_TAGS:
                continue
            text = elem.text or ""
            if not HAN.search(text):
                continue
            name = elem.get("name")
            if name is None and elem in parent:
                name = parent[elem].get("name")
            hits.append({
                "file": fn, "tag": elem.tag, "name": name,
                "text": text, "parsed": parse_inner(text),
            })
    return hits


def read_arrays(path: str) -> dict[str, list[str]]:
    """array name -> ordered list of <item> text values (ET-normalized)."""
    root = ET.parse(path).getroot()
    out: dict[str, list[str]] = {}
    for elem in root:
        if elem.tag in ARRAY_TAGS and elem.get("name"):
            items: list[str] = []
            for child in elem:
                if child.tag == "item":
                    items.append("".join(child.itertext()))
            out[elem.get("name")] = items
    return out


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main() -> int:
    baseline = json.load(open(BASELINE, encoding="utf-8"))
    keys: list[str] = baseline["keys"]
    zh_sha: dict[str, str] = baseline["zh_sha256"]
    en_sha_before: dict[str, str] = baseline["en_sha256"]
    exemptions: dict[str, str] = baseline["exemptions"]
    exempt_keys = set(exemptions)
    items: list[dict] = baseline["items"]
    array_meta: dict[str, dict] = baseline["array_meta"]
    untouched: dict[str, list[str]] = baseline["untouched_arrays"]
    pure_values: dict[str, list[str]] = baseline["pure_value_arrays"]
    r59: dict[str, int] = baseline["r59_meta"]

    en = scan(V)
    zh = scan(VZ)
    en_map, zh_map = en["map"], zh["map"]

    print("=" * 78)
    print("R58+R59 默认资源英化 — 统一回归校验（609 <string> + 20 <item> = 629）")
    print("=" * 78)

    # 1) coverage -----------------------------------------------------------
    missing_en = [k for k in keys if k not in en_map]
    missing_zh = [k for k in keys if k not in zh_map]
    check(not missing_en and not missing_zh,
          f"609 个 key 在 values/ 与 values-zh/ 全覆盖",
          f"values/ 缺 {len(missing_en)}，values-zh/ 缺 {len(missing_zh)}")
    if missing_en:
        print("   values/ missing:", missing_en[:20])
    if missing_zh:
        print("   values-zh/ missing:", missing_zh[:20])

    # 2) Chinese side byte-identical ---------------------------------------
    zh_bad = [k for k in keys if k in zh_map and sha(zh_map[k]["raw"]) != zh_sha.get(k)]
    check(not zh_bad, "中文侧 609 条值与改前逐字节一致（334 已存在 + 275 新增）",
          f"不一致 {len(zh_bad)} 条")
    if zh_bad:
        print("   mismatch:", zh_bad[:20])
    zh_no_han = [k for k in keys if k in zh_map and not HAN.search(zh_map[k]["parsed"])
                 and k != "separator"]
    check(not zh_no_han, "中文侧新增/保留值均含中文（中文用户所见不变）",
          f"异常 {len(zh_no_han)} 条: {zh_no_han[:10]}")

    # 3) FULL text-node scan: zero Chinese except the 10 exemptions --------
    all_hits = text_nodes(V)
    by_tag: Counter[str] = Counter(h["tag"] for h in all_hits)
    exempt_hits = [h for h in all_hits
                   if h["tag"] == "string" and h["name"] in exempt_keys]
    non_exempt = [h for h in all_hits
                  if not (h["tag"] == "string" and h["name"] in exempt_keys)]
    check(len(all_hits) == len(exempt_keys) and len(exempt_hits) == len(exempt_keys)
          and not non_exempt,
          "全量文本节点扫描：values/ 全部 XML 的 <string>/<item>/<plurals> 中文命中恰为 10 条显式豁免，非豁免残留 0",
          f"总命中 {len(all_hits)}，豁免命中 {len(exempt_hits)}，非豁免 {len(non_exempt)}")
    for h in non_exempt[:20]:
        print("   非豁免残留:", h["file"], h["tag"], h["name"], repr(h["parsed"][:70]))
    found_keys = [h["name"] for h in exempt_hits]
    check(sorted(found_keys) == sorted(exempt_keys),
          "10 条显式豁免在全量扫描中逐条命中（无重复 / 无缺失）",
          f"命中 {len(found_keys)}/{len(exempt_keys)}")
    check(by_tag["item"] == 0 and by_tag["plurals"] == 0,
          "<item> 与 <plurals> 文本节点中文命中 0（R59 已收口）",
          f"item {by_tag['item']} / plurals {by_tag['plurals']}")
    print(f"   按标签统计: string={by_tag['string']} item={by_tag['item']} "
          f"plurals={by_tag['plurals']}")

    # keep the per-key 609 assertion as a second, independent guard
    unexempt_chinese: list[str] = []
    exempt_found: list[str] = []
    for k in keys:
        if k not in en_map:
            continue
        if HAN.search(en_map[k]["parsed"]):
            if k in exempt_keys:
                exempt_found.append(k)
            else:
                unexempt_chinese.append(k)
    check(not unexempt_chinese, "609 条英文默认资源零中文（除显式豁免清单）",
          f"非豁免残留 {len(unexempt_chinese)} 条: {unexempt_chinese[:20]}")
    check(len(exempt_found) == len(exempt_keys),
          "显式豁免清单逐条命中（609 key 视角）",
          f"命中 {len(exempt_found)}/{len(exempt_keys)}")
    print("\n   —— 显式豁免清单（逐条列出，含理由与保留的中文片段）——")
    for k in sorted(exempt_keys):
        cur = en_map.get(k, {}).get("parsed", "<missing>")
        frags = re.findall(r"[\u4e00-\u9fff]+", cur)
        print(f"   • {k}")
        print(f"       中文片段: {' / '.join(frags) or '(无 Han 字符，仅视觉分隔符)'}")
        print(f"       值: {cur[:110]}")
        print(f"       理由: {exemptions[k]}")
    print()

    # also scan every other <string> in values/ (outside the 609)
    other_chinese = [k for k, rec in en_map.items()
                     if k not in keys and HAN.search(rec["parsed"])]
    check(not other_chinese, "values/ 其余 <string> 亦零中文",
          f"异常 {len(other_chinese)} 条: {other_chinese[:10]}")
    notes.append(
        "范围说明（R59 已收口）：values/arrays.xml 与 values/text_reader.xml 的 20 处中文 <item> "
        "属 UI 选项标签，英文 locale 同样会读到中文，与 609 条 <string> 是同一缺口。"
        "R59 已将 20 项全部英化、在 values-zh/ 补齐 5 个同名数组（17 项中文原样），"
        "既有 bottom_bar_effect_mode_entries（3 项）与其它数组完全未动；纯值数组未本地化。"
        "本检查器的「英文资源零中文」口径现覆盖 values/ 下全部 XML 的全部文本节点类型"
        "（<string> / <item> / <plurals>），最终中文命中仅剩 10 条显式豁免。"
    )

    # 4) R59 array items: English side, Chinese side, counts, order --------
    v_arrays_flat: dict[str, list[str]] = {}
    zh_arrays_flat: dict[str, list[str]] = {}
    for fn in ("arrays.xml", "text_reader.xml"):
        for name, vals in read_arrays(os.path.join(V, fn)).items():
            v_arrays_flat[f"{fn}::{name}"] = vals
        for name, vals in read_arrays(os.path.join(VZ, fn)).items():
            zh_arrays_flat[f"{fn}::{name}"] = vals

    check(len(items) == r59["total_items"] and len(array_meta) == r59["arrays"],
          "R59 基线：20 条 <item> / 6 个数组元数据完整",
          f"items {len(items)} / arrays {len(array_meta)}")

    en_bad: list[str] = []
    for it in items:
        key = f"{it['file']}::{it['array']}"
        got = v_arrays_flat.get(key, [])
        idx = it["index"]
        if idx >= len(got) or got[idx] != it["en_expected"] or HAN.search(got[idx]):
            en_bad.append(f"{it['id']}: expected {it['en_expected']!r}, "
                          f"got {got[idx] if idx < len(got) else '<missing>'!r}")
    check(not en_bad, "20 条默认侧 <item> 与期望英文逐条一致、零中文残留（数组名/项数/顺序不变）",
          f"不一致 {len(en_bad)} 条")
    for x in en_bad[:20]:
        print("   ", x)

    zh_bad_items: list[str] = []
    for it in items:
        key = f"{it['zh_file']}::{it['array']}"
        got = zh_arrays_flat.get(key, [])
        idx = it["index"]
        want = parse_inner(it["zh_raw"])
        if idx >= len(got) or got[idx] != want:
            zh_bad_items.append(f"{it['id']}: expected {want!r}, "
                                f"got {got[idx] if idx < len(got) else '<missing>'!r}")
    check(not zh_bad_items, "中文侧 20 项与 R59 原中文逐字节一致（17 新增 + 3 既有未动）",
          f"不一致 {len(zh_bad_items)} 条")
    for x in zh_bad_items[:20]:
        print("   ", x)

    count_bad: list[str] = []
    for name, meta in array_meta.items():
        vk = f"{meta['file']}::{name}"
        zk = f"{meta['zh_file']}::{name}"
        if len(v_arrays_flat.get(vk, [])) != meta["count"] or \
           len(zh_arrays_flat.get(zk, [])) != meta["count"]:
            count_bad.append(f"{name}: values {len(v_arrays_flat.get(vk, []))} / "
                             f"zh {len(zh_arrays_flat.get(zk, []))} / expect {meta['count']}")
    check(not count_bad, "6 个数组在 values/ 与 values-zh/ 项数一致且与基线一致",
          f"异常 {count_bad}")

    new_arrays = [n for n, m in array_meta.items() if not m["zh_existing"]]
    existing_arrays = [n for n, m in array_meta.items() if m["zh_existing"]]
    new_items = sum(1 for it in items if not it["zh_existing"])
    existing_items = sum(1 for it in items if it["zh_existing"])
    check(len(new_arrays) == r59["zh_new_arrays"]
          and len(existing_arrays) == r59["zh_existing_arrays"]
          and new_items == r59["zh_new_items"]
          and existing_items == r59["zh_existing_items"],
          "values-zh 补入 5 个数组 / 17 项，既有 1 个数组 / 3 项完全未动",
          f"新数组 {len(new_arrays)} / 新项 {new_items}；既有数组 {len(existing_arrays)} / 既有项 {existing_items}")
    print("   补入数组:", ", ".join(new_arrays))
    print("   既有未动:", ", ".join(existing_arrays))

    # non-target arrays and pure-value arrays are byte-identical to HEAD
    cur_untouched = {k: v for k, v in v_arrays_flat.items()
                     if k.split("::", 1)[1] not in array_meta}
    check(cur_untouched == untouched,
          f"{len(untouched)} 个非目标数组与 HEAD 逐项一致（未被 R59 改动）",
          f"当前 {len(cur_untouched)} / 基线 {len(untouched)}")
    if cur_untouched != untouched:
        for k in sorted(set(cur_untouched) | set(untouched)):
            if cur_untouched.get(k) != untouched.get(k):
                print("   diff:", k, untouched.get(k), "->", cur_untouched.get(k))
    cur_pure = {k: v_arrays_flat.get(k) for k in pure_values}
    check(cur_pure == pure_values,
          "纯值数组（ai_read_aloud_role_mode_value / text_read_engine_values）未动、未本地化",
          f"{cur_pure}")
    # entries/values index alignment (option indices must stay parallel)
    align_bad: list[str] = []
    for pairs in (("ai_read_aloud_role_mode_title", "ai_read_aloud_role_mode_value", "arrays.xml"),
                  ("text_read_engine_entries", "text_read_engine_values", "text_reader.xml")):
        a, b, fn = pairs
        la = len(v_arrays_flat.get(f"{fn}::{a}", []))
        lb = len(v_arrays_flat.get(f"{fn}::{b}", []))
        if la != lb:
            align_bad.append(f"{fn}: {a} {la} != {b} {lb}")
    check(not align_bad, "entries / values 数组项数对齐（选项索引未错位）",
          f"{align_bad}")

    reused = sum(1 for it in items if it["reuse"])
    check(reused == r59["reused"] and len(items) - reused == r59["new_translations"],
          "译文来源统计：7 条复用既有双语资源 / 13 条逐条新译",
          f"复用 {reused} / 新译 {len(items) - reused}")

    # 5) placeholder parity -------------------------------------------------
    ph_bad: list[str] = []
    for k in keys:
        if k not in en_map or k not in zh_map:
            continue
        a = sorted(FMT.findall(parse_inner(zh_map[k]["raw"])))
        b = sorted(FMT.findall(parse_inner(en_map[k]["raw"])))
        if a != b:
            ph_bad.append(f"{k}:{a}!={b}")
    check(not ph_bad, "中英占位符逐条一致（数量与编号）",
          f"不一致 {len(ph_bad)} 条")
    if ph_bad:
        print("   mismatch:", ph_bad[:20])

    # 6) exempt unchanged / others changed ---------------------------------
    en_unchanged_bad = [k for k in keys
                        if k in en_map and sha(en_map[k]["raw"]) == en_sha_before.get(k)
                        and k not in exempt_keys]
    exempt_changed = [k for k in exempt_keys
                      if k in en_map and sha(en_map[k]["raw"]) != en_sha_before.get(k)]
    check(not en_unchanged_bad and not exempt_changed,
          "10 条豁免值原样保留；其余 599 条确已英化",
          f"漏改 {len(en_unchanged_bad)}，豁免被改 {len(exempt_changed)}")
    if en_unchanged_bad:
        print("   unchanged:", en_unchanged_bad[:20])

    # 7) duplicate keys -----------------------------------------------------
    check(not en["dups"] and not zh["dups"], "全局重复 key 0",
          f"values/ {len(en['dups'])}，values-zh/ {len(zh['dups'])}")

    # 8) XML parse + HTML balance + Android escapes -------------------------
    parse_bad: list[str] = []
    for folder in (V, VZ):
        for fn in sorted(os.listdir(folder)):
            if not fn.endswith(".xml"):
                continue
            p = os.path.join(folder, fn)
            try:
                ET.parse(p)
            except ET.ParseError as e:
                parse_bad.append(f"{folder.split('/')[-1]}/{fn}: {e}")
    check(not parse_bad, "全部 values/ 与 values-zh/ XML 可解析",
          f"异常 {len(parse_bad)}: {parse_bad[:5]}")

    html_bad: list[str] = []
    escape_bad: list[str] = []
    for k in keys:
        if k not in en_map:
            continue
        raw = en_map[k]["raw"]
        if "CDATA" not in raw:
            stack: list[str] = []
            for m in TAG.finditer(raw):
                closing, name = m.group(1), m.group(2)
                if name.lower() in ("br", "hr", "img", "input"):
                    continue
                if closing:
                    if not stack or stack[-1] != name:
                        html_bad.append(k)
                        break
                    stack.pop()
                elif not m.group(0).endswith("/>"):
                    stack.append(name)
            else:
                if stack:
                    html_bad.append(k)
            # unescaped quotes / apostrophes / bare ampersands
            stripped = re.sub(r"&\w+;|&#\d+;", "", raw)
            if re.search(r'(?<!\\)["\']', stripped) or "&" in stripped:
                escape_bad.append(k)
    check(not html_bad, "HTML 标签 0 未闭合 / 错配", f"异常 {html_bad[:10]}")
    check(not escape_bad, "Android 字符串转义（引号 / & / < >）0 异常",
          f"异常 {escape_bad[:10]}")

    # 9) comparison-value drift (strings + arrays) --------------------------
    drift: list[str] = []
    ch_lit = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')
    strict_cmp = re.compile(r"==|!=|\.equals\s*\(")
    res_ref = re.compile(r"getString\s*\(|R\.string\.|str\s*\(")
    key_ref = {k: re.compile(r"R\.string\." + re.escape(k) + r"\b") for k in keys}
    src_files: list[str] = []
    for root, _dirs, fs in os.walk(JAVA):
        for fn in fs:
            if fn.endswith((".kt", ".java")):
                src_files.append(os.path.join(root, fn))
    for p in src_files:
        lines = open(p, encoding="utf-8").read().split("\n")
        for n, line in enumerate(lines, 1):
            if not HAN.search(line):
                continue
            # (a) resource call compared to a Chinese literal on the same line
            if strict_cmp.search(line) and res_ref.search(line):
                for m in ch_lit.finditer(line):
                    if HAN.search(m.group(1)):
                        drift.append(f"{p}:{n} {line.strip()[:120]}")
                        break
            # (b) when(...) over a resource call with Chinese branch literals
            if re.search(r"when\s*\(\s*(?:appCtx\.)?(?:getString|str)\s*\(", line):
                ctx = "\n".join(lines[n - 1: min(len(lines), n + 25)])
                for m in ch_lit.finditer(ctx):
                    if HAN.search(m.group(1)):
                        drift.append(f"{p}:{n} when-over-resource {m.group(1)[:40]}")
                        break
            # (c) resource value tested against a Chinese set/list
            if "in setOf(" in line or "in listOf(" in line or "in arrayOf(" in line:
                if any(r.search(line) for r in key_ref.values()):
                    for m in ch_lit.finditer(line):
                        if HAN.search(m.group(1)):
                            drift.append(f"{p}:{n} in-collection {line.strip()[:120]}")
                            break
    check(not drift, "比较值检查：无 getString 结果与中文字面量比较（含 when/.equals/setOf）",
          f"命中 {len(drift)} 处")
    for d in drift[:20]:
        print("   ", d)

    # (d) array retrieval compared to any of the 20 original Chinese items
    arr_call = re.compile(r"getStringArray|getTextArray|R\.array\.|obtainTypedArray")
    cmp_any = re.compile(
        r"==|!=|\.equals\s*\(|when\s*\(|contains\(|startsWith\(|endsWith\("
        r"|setOf|listOf|arrayOf|indexOf\(")
    item_literals = {parse_inner(it["zh_raw"]) for it in items}
    arr_drift: list[str] = []
    for p in src_files:
        lines = open(p, encoding="utf-8").read().split("\n")
        for n, line in enumerate(lines, 1):
            if arr_call.search(line) and HAN.search(line):
                arr_drift.append(f"{p}:{n} array-call+Chinese {line.strip()[:120]}")
            if HAN.search(line) and cmp_any.search(line):
                for m in ch_lit.finditer(line):
                    if m.group(1) in item_literals:
                        arr_drift.append(f"{p}:{n} item-literal cmp {m.group(1)[:40]}")
                        break
            if arr_call.search(line):
                win = "\n".join(lines[max(0, n - 4): min(len(lines), n + 4)])
                if cmp_any.search(win):
                    for m in ch_lit.finditer(win):
                        if HAN.search(m.group(1)) and m.group(1) in item_literals:
                            arr_drift.append(f"{p}:{n} window {m.group(1)[:40]}")
                            break
    check(not arr_drift,
          "比较值检查：无 getStringArray/getTextArray 结果与 20 条中文字面量比较（同行 / ±4 行窗口）",
          f"命中 {len(arr_drift)} 处")
    for d in arr_drift[:20]:
        print("   ", d)

    # notes / summary --------------------------------------------------------
    print()
    for note in notes:
        print("   NOTE:", note)
    print()
    print("=" * 78)
    if failures:
        print(f"RESULT: FAIL — {len(failures)} 项未通过")
        for f in failures:
            print("   -", f)
        return 1
    print("RESULT: PASS — 全部校验通过（609 <string> + 20 <item> = 629 条默认资源文本节点；"
          "中文命中 10/10 显式豁免、非豁免 0；275 条补中文键 / 17 项补中文数组）")
    print("=" * 78)
    return 0


if __name__ == "__main__":
    sys.exit(main())
