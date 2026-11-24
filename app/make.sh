#!/bin/bash

set -e

cd "$(dirname "$0")"

GRADLE_FILE=app/build.gradle

# 每次打包把 versionCode 加一并同步 versionName（1.0.<versionCode>），
# 安装包版本号只增不减，方便区分不同构建。
code=$(sed -n -E "s/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*/\1/p" "$GRADLE_FILE" | head -n 1)
code=$(( ${code:-0} + 1 ))
name="1.0.${code}"

sed -i -E "s/^([[:space:]]*)versionCode[[:space:]]+[0-9]+/\1versionCode ${code}/" "$GRADLE_FILE"
sed -i -E "s/^([[:space:]]*)versionName[[:space:]]+'[^']*'/\1versionName '${name}'/" "$GRADLE_FILE"

echo "构建版本: ${name} (${code})"

./gradlew :app:assembleDebug
#./gradlew :app:assembleRelease
