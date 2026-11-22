#!/usr/bin/env bash
# 全量验证：单元测试 + 构建 + lint，并打印结果摘要。
# 用法：  bash scripts/verify.sh
#
# ARM64 主机追加 aapt2 覆盖参数：
#   AAPT2_OVERRIDE=/opt/bin/aapt2 bash scripts/verify.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

EXTRA=()
if [ -n "${AAPT2_OVERRIDE:-}" ]; then
  EXTRA+=("-Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE")
  echo "aapt2 覆盖: $AAPT2_OVERRIDE"
fi

LOG_DIR="$ROOT/build/logs"
mkdir -p "$LOG_DIR"

run() {
  local name="$1"; shift
  echo ""
  echo "======== $name ========"
  ./gradlew "$@" --no-daemon "${EXTRA[@]}" 2>&1 | tee "$LOG_DIR/$name.log" | tail -25
  return "${PIPESTATUS[0]}"
}

FAIL=0
run tests  :app:testDebugUnitTest || FAIL=1
run build  :app:assembleDebug     || FAIL=1
run lint   :app:lintDebug         || FAIL=1

echo ""
echo "======== 摘要 ========"

# 测试统计
TR=0; TF=0; TS=0
for f in app/build/test-results/testDebugUnitTest/*.xml; do
  [ -f "$f" ] || continue
  read -r t f2 s <<<"$(grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f" \
        | head -1 | sed 's/[a-z]*="//g; s/"//g' | awk '{print $1, $3+$4, $2}')"
  TR=$((TR + ${t:-0})); TF=$((TF + ${f2:-0})); TS=$((TS + ${s:-0}))
done
echo "单元测试: $TR 个，失败/错误 $TF，跳过 $TS"
[ "$TF" -ne 0 ] && FAIL=1

# lint 统计
LR="app/build/reports/lint-results-debug.txt"
if [ -f "$LR" ]; then
  SUMMARY="$(grep -oE '^[0-9]+ errors?, [0-9]+ warnings?' "$LR" | tail -1)"
  echo "lint: ${SUMMARY:-（未找到汇总行）}"
  [ -n "$SUMMARY" ] && case "$SUMMARY" in 0\ error*) ;; *) FAIL=1 ;; esac
  echo "      报告: $LR"
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  echo "APK: $APK  ($(du -h "$APK" | cut -f1))"
  if command -v sha256sum >/dev/null 2>&1; then
    echo "sha256: $(sha256sum "$APK" | cut -d' ' -f1)"
  fi
fi

echo ""
if [ "$FAIL" -eq 0 ]; then echo "结果: PASS"; else echo "结果: FAIL"; fi
exit "$FAIL"
