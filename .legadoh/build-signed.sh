#!/bin/zsh
# 使用 .legadoh/keystore.properties 中的签名属性构建正式版 APK（不回显密钥）
# 用法: ./build-signed.sh <x.y.z>    （如 1.5.1，对应 versionName=H.1.5.1，tag=H-1.5.1）
# versionCode 自动计算为 29860000 + git 提交数，与 docs/release-process.md 保持一致
set -e
cd "$(dirname "$0")/.."
VER_INPUT="${1:?用法: build-signed.sh <x.y.z>，如 1.5.1}"
case "$VER_INPUT" in
  H.*) echo "错误：直接传数字版本号（如 1.5.1），不要带 H 前缀" >&2; exit 1 ;;
  *[!0-9.]*) echo "错误：版本号只允许数字与点（如 1.5.1）" >&2; exit 1 ;;
esac
VERSION_NAME="H.${VER_INPUT}"
VERSION_CODE=$((29860000 + $(git rev-list --count HEAD)))
echo "versionName=${VERSION_NAME} versionCode=${VERSION_CODE}"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
PROP=.legadoh/keystore.properties
get() { grep "^$1=" "$PROP" | cut -d= -f2-; }
STORE_ABS="$(pwd)/.legadoh/$(get RELEASE_STORE_FILE | xargs basename)"
./gradlew :app:assembleAppRelease -Pabi=arm64-v8a \
  -PVERSION_NAME="$VERSION_NAME" -PVERSION_CODE="$VERSION_CODE" \
  -PRELEASE_STORE_FILE="$STORE_ABS" \
  -PRELEASE_STORE_PASSWORD="$(get RELEASE_STORE_PASSWORD)" \
  -PRELEASE_KEY_ALIAS="$(get RELEASE_KEY_ALIAS)" \
  -PRELEASE_KEY_PASSWORD="$(get RELEASE_KEY_PASSWORD)" \
  --max-workers=6 -q
echo "SIGNED_RELEASE_OK versionName=${VERSION_NAME} versionCode=${VERSION_CODE}"
