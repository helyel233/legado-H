#!/bin/zsh
# 创建/更新 GitHub Release（tag: H-<x.y.z>）并上传 release + debug 双包
# 用法: ./create-release.sh <x.y.z>    （如 1.5.1，对应 versionName=H.1.5.1）
# 正文只取 CHANGELOG.md 中「当前版本」的章节（## x.y.z 版 …），标题不符直接报错；
# Release 已存在时先更新正文、再删旧 APK asset 重传（幂等重发）
set -e
set -o pipefail
cd "$(dirname "$0")/.."
VER_INPUT="${1:?用法: create-release.sh <x.y.z>，如 1.5.1}"
case "$VER_INPUT" in
  H.*) echo "错误：直接传数字版本号（如 1.5.1），不要带 H 前缀" >&2; exit 1 ;;
  *[!0-9.]*) echo "错误：版本号只允许数字与点（如 1.5.1）" >&2; exit 1 ;;
esac
VER="H.${VER_INPUT}"
CODE=$((29860000 + $(git rev-list --count HEAD)))
TAG="H-${VER_INPUT}"
REPO="helyel233/legado-H"
ASSET_ARM64="legado_app_${VER}_${CODE}_LegadoH_arm64.apk"
ASSET_DEBUG="legado_app_${VER}_${CODE}_LegadoH_debug.apk"
for f in "$ASSET_ARM64" "$ASSET_DEBUG"; do
  [ -f "$f" ] || { echo "缺少 $f（请先构建并重命名到仓库根目录）" >&2; exit 1; }
done
TOKEN=$(printf "protocol=https\nhost=github.com\n" | git credential fill | grep "^password=" | cut -d= -f2-)
API="https://api.github.com/repos/${REPO}"
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json")

# versionCode 必须严格大于上一已发布版本（纠错重写历史时提交数会回退）
PUBLISHED_CODE=$(curl -sf "${AUTH[@]}" "$API/releases?per_page=10" | python3 -c '
import json, re, sys
codes = [int(m.group(1)) for r in json.load(sys.stdin) for a in r.get("assets", [])
         if (m := re.search(r"_(\d+)_LegadoH", a["name"]))]
print(max(codes) if codes else 0)' 2>/dev/null || true)
if [ -z "$PUBLISHED_CODE" ]; then
  echo "警告：读取已发布版本 versionCode 失败，跳过递增校验" >&2
elif [ "$CODE" -le "$PUBLISHED_CODE" ]; then
  echo "错误：本次 versionCode=$CODE 未大于上一已发布版本 $PUBLISHED_CODE（提交数回退？）" >&2
  exit 1
fi

# 正文：只取当前版本章节；标题格式不符直接报错，避免把整份历史当成正文
BODY_JSON=".legadoh/release-body-${TAG}.json"
python3 - "$VER" "$TAG" "$BODY_JSON" "$VER_INPUT" <<'PYEOF'
import json, sys, re, pathlib
ver, tag, out, ver_input = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
text = pathlib.Path("CHANGELOG.md").read_text(encoding="utf-8")
m = re.search(r'^## ' + re.escape(ver_input) + r'(?:\s|$)', text, re.MULTILINE)
if not m:
    sys.exit("错误：CHANGELOG.md 缺少 '## %s 版' 标题（正文只取当前版本小节）" % ver_input)
rest = text[m.end():]
nxt = re.search(r'^## ', rest, re.MULTILINE)
end = m.end() + nxt.start() if nxt else len(text)
body = text[m.start():end].strip()
payload = {
    "tag_name": tag,
    "target_commitish": "main",
    "name": "legado-H " + ver.split(".", 1)[1],
    "body": body,
    "draft": False,
    "prerelease": False,
}
pathlib.Path(out).write_text(
    json.dumps(payload, ensure_ascii=False), encoding="utf-8"
)
PYEOF

# 查已有 Release（同名 tag 重发场景）
RELEASE_ID=$(curl -sf "${AUTH[@]}" "$API/releases/tags/$TAG" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)
if [ -n "$RELEASE_ID" ]; then
  echo "Release 已存在(id=$RELEASE_ID)，更新正文并删除旧 APK asset 后重传"
  curl -sf -X PATCH "${AUTH[@]}" "$API/releases/$RELEASE_ID" -d @"$BODY_JSON" > /dev/null
  curl -sf "${AUTH[@]}" "$API/releases/$RELEASE_ID/assets" \
    | python3 -c 'import json,sys
for a in json.load(sys.stdin):
    if a["name"].endswith(".apk"):
        print(a["id"])' \
    | while read -r AID; do
        curl -sf -X DELETE "${AUTH[@]}" "$API/releases/assets/$AID"
      done
else
  RELEASE_ID=$(curl -sf -X POST "${AUTH[@]}" "$API/releases" -d @"$BODY_JSON" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  echo "RELEASE_ID=$RELEASE_ID"
fi
for f in "$ASSET_ARM64" "$ASSET_DEBUG"; do
  echo "上传 $f"
  HTTP=$(curl -s -o /tmp/upload-resp-"$f".json -w "%{http_code}" \
    -X POST "${AUTH[@]}" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"$f" \
    "https://uploads.github.com/repos/${REPO}/releases/$RELEASE_ID/assets?name=$f")
  if [ "$HTTP" != "201" ]; then
    echo "错误：上传 $f 失败（HTTP=$HTTP）" >&2
    cat /tmp/upload-resp-"$f".json 2>/dev/null | head -c 400 >&2
    exit 1
  fi
  python3 -c "import json,sys; d=json.load(open('/tmp/upload-resp-$f.json')); print('  已上传:', d.get('name'), 'size=', d.get('size'), 'digest=', d.get('digest'))" 2>/dev/null || echo "  上传响应异常"
done
echo UPLOAD_DONE
