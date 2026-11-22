#!/usr/bin/env bash
# 编译 → 安装到连着 adb 的安卓手机 → 拉起 App → 开始看日志。
# 用法：
#   bash scripts/deploy.sh              # debug 包
#   bash scripts/deploy.sh logcat       # 只跟日志，不重装
#   bash scripts/deploy.sh clean        # 先 force-stop 再重装（模拟冷启动）
#
# 注意：这是一个安卓 App，此脚本做的是"装到手机上"，不产出任何桌面临时程序。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

APP_ID="com.rrlogger"
ACTIVITY="$APP_ID/.ui.MainActivity"
TAG="RRLogger"

EXTRA=()
[ -n "${AAPT2_OVERRIDE:-}" ] && EXTRA+=("-Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE")

adb_bin() {
  local a
  a="$(command -v adb || true)"
  if [ -z "$a" ] && [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    a="$ANDROID_HOME/platform-tools/adb"
  fi
  if [ -z "$a" ] && [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
    a="$ANDROID_SDK_ROOT/platform-tools/adb"
  fi
  printf '%s' "$a"
}

ADB="$(adb_bin)"
if [ -z "$ADB" ]; then
  echo "[FAIL] 找不到 adb。把 \$ANDROID_HOME/platform-tools 加进 PATH，或用 Android Studio 的 SDK Manager 装 platform-tools。"
  exit 1
fi

need_device() {
  local ok
  ok="$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l)"
  if [ "$ok" -eq 0 ]; then
    echo "[FAIL] 没有已授权的设备："
    "$ADB" devices
    echo "      手机开发者选项 → USB 调试打开，插线后在手机上点「允许」。"
    exit 1
  fi
  "$ADB" devices | awk 'NR>1 && $2=="device" {printf "  设备: %s\n", $1}'
}

case "${1:-install}" in
  logcat)
    need_device
    echo "跟日志（Ctrl+C 退出）："
    exec "$ADB" logcat -s "$TAG" AndroidRuntime
    ;;

  clean)
    need_device
    echo "1) 结束进程"
    "$ADB" shell am force-stop "$APP_ID" || true
    echo "2) 编译安装"
    ./gradlew :app:installDebug --no-daemon "${EXTRA[@]}" | tail -8 || exit 1
    echo "3) 清日志并启动"
    "$ADB" logcat -c
    "$ADB" shell am start -n "$ACTIVITY" || exit 1
    echo "4) 跟日志（Ctrl+C 退出）："
    exec "$ADB" logcat -s "$TAG" AndroidRuntime
    ;;

  install|*)
    need_device
    echo "编译 + 安装到手机 ..."
    ./gradlew :app:installDebug --no-daemon "${EXTRA[@]}" | tail -8 || exit 1
    "$ADB" logcat -c
    "$ADB" shell am start -n "$ACTIVITY" || exit 1
    echo ""
    echo "已启动 $ACTIVITY"
    echo "看日志： bash scripts/deploy.sh logcat"
    ;;
esac
