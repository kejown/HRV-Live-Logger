#!/usr/bin/env bash
# PC 端一键环境自检 + local.properties 生成。
# 用法：  bash scripts/pc-setup.sh
# 只做检查与写文件，不联网、不改全局配置。
set -u

say()  { printf '%s\n' "$*"; }
ok()   { printf '  [OK]   %s\n' "$*"; }
warn() { printf '  [WARN] %s\n' "$*"; }
die()  { printf '  [FAIL] %s\n' "$*"; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
say "工程根目录: $ROOT"
say ""

# ---------- 1. JDK 17 ----------
say "1) JDK"
JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
fi
[ -z "$JAVA_BIN" ] && die "找不到 java。安装 JDK 17（推荐 Temurin 17）后重试。"
JVER="$("$JAVA_BIN" -version 2>&1 | head -1)"
case "$JVER" in
  *'"17.'*) ok "$JVER  ($JAVA_BIN)" ;;
  *) warn "$JVER  —— AGP 8.5 要求 JDK 17，其他版本大概率构建失败" ;;
esac

# ---------- 2. Android SDK ----------
say "2) Android SDK"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" \
           /usr/lib/android-sdk /opt/android-sdk /opt/android-sdk-linux; do
    [ -d "$c" ] && SDK="$c" && break
  done
fi
[ -z "$SDK" ] && die "找不到 Android SDK。设置 ANDROID_HOME，或先装 Android Studio / cmdline-tools。"
[ -d "$SDK" ] || die "ANDROID_HOME=$SDK 目录不存在"
ok "SDK: $SDK"

[ -d "$SDK/platforms/android-34" ] \
  && ok "platforms/android-34" \
  || warn "缺 platforms;android-34 —— sdkmanager \"platforms;android-34\""

BT=""
for d in "$SDK"/build-tools/*; do [ -d "$d" ] && BT="$d"; done
[ -n "$BT" ] && ok "build-tools: $(basename "$BT")" || warn "缺 build-tools —— sdkmanager \"build-tools;34.0.0\""

# ---------- 3. aapt2 平台提醒 ----------
say "3) AAPT2"
MACHINE="$(uname -m)"
case "$MACHINE" in
  x86_64|amd64) ok "主机 $MACHINE —— 无需任何额外参数" ;;
  *) warn "主机 $MACHINE —— 需要 qemu 运行 x86-64 aapt2，见 BUILD.md「ARM64 主机」一节" ;;
esac

# ---------- 4. 写 local.properties ----------
say "4) local.properties"
LP="$ROOT/local.properties"
# Windows 盘符路径需要转义冒号和反斜杠
ESCAPED="$(printf '%s' "$SDK" | sed 's/\\/\\\\/g; s/:/\\:/g')"
if [ -f "$LP" ]; then
  ok "已存在，内容：$(grep -c . "$LP") 行，未覆盖"
else
  printf 'sdk.dir=%s\n' "$ESCAPED" > "$LP"
  ok "已写入 sdk.dir=$SDK"
fi

# ---------- 5. gradle wrapper ----------
say "5) Gradle wrapper"
if [ -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]; then
  ok "gradle-wrapper.jar 存在，Gradle 版本 $(grep -o 'gradle-[0-9.]*-bin' "$ROOT/gradle/wrapper/gradle-wrapper.properties" | head -1)"
else
  warn "缺 gradle/wrapper/gradle-wrapper.jar"
fi

say ""
say "下一步："
say "  ./gradlew :app:testDebugUnitTest     # 70 个单元测试"
say "  ./gradlew :app:assembleDebug         # 产出 app/build/outputs/apk/debug/app-debug.apk"
say "  ./gradlew :app:lintDebug             # 静态检查"
say "或直接： bash scripts/verify.sh"
