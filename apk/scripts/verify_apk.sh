#!/bin/bash
# verify_apk.sh — 四层APK验证: 安装→启动→崩溃→存活
# 用法: bash verify_apk.sh <apk_path>
# Exit: 0=通过 10=安装失败 11/12=启动失败 13=崩溃

APK="$1"
PKG="com.nls.selfbalancing"
ACT="com.nls.selfbalancing.MainActivity"
WAIT=4

if [ ! -f "$APK" ]; then echo "❌ APK不存在: $APK"; exit 1; fi

# ---------- L1: 安装 ----------
echo "📦 [L1] 安装 $APK ..."
adb install -r -t "$APK" 2>&1 | tee install.log
if grep -q "Failure\|INSTALL_FAILED" install.log; then
    echo "❌ [L1] 安装失败"
    cat install.log > verify_fail_install.txt
    exit 10
fi
echo "✅ [L1] 安装通过"

# 清旧日志
adb logcat -c

# ---------- L2: 启动 ----------
echo "🚀 [L2] 启动 $PKG/$ACT ..."
OUT=$(adb shell am start -W -n "$PKG/$ACT" 2>&1)
echo "$OUT" > am_start.log
if echo "$OUT" | grep -q "Error"; then
    echo "❌ [L2] am start 报 Error"
    cat am_start.log > verify_fail_start.txt
    exit 11
fi
STATUS=$(echo "$OUT" | grep "Status:" | awk '{print $2}')
if [ "$STATUS" != "ok" ]; then
    echo "❌ [L2] am start Status=$STATUS"
    cat am_start.log > verify_fail_start.txt
    exit 12
fi
echo "✅ [L2] 启动通过 (Status=$STATUS)"

sleep "$WAIT"

# ---------- L3: 崩溃检测 ----------
echo "🔍 [L3] 崩溃检测 (等待${WAIT}s)..."
CRASH_LOG=$(adb logcat -b crash -d -v brief 2>&1 | grep -A 40 "FATAL.*$PKG")
if [ -n "$CRASH_LOG" ]; then
    echo "❌ [L3] 抓到崩溃:"
    echo "$CRASH_LOG"
    echo "$CRASH_LOG" > verify_crash.txt
    PID=$(echo "$CRASH_LOG" | grep "PID:" | head -1 | grep -oP 'PID: \K\d+')
    if [ -n "$PID" ]; then
        adb logcat -b main -d 2>&1 | grep -B 20 -A 40 "$PID" > verify_crash_main.txt
    fi
    exit 13
fi
echo "✅ [L3] 崩溃检测通过"

# ---------- L4: 存活 ----------
echo "👁 [L4] 界面存活检测..."
FRONT=$(adb shell dumpsys window windows 2>/dev/null | grep -o "mCurrentFocus=.*$PKG[^)]*" | head -1)
if [ -z "$FRONT" ]; then
    echo "⚠️ [L4] ${WAIT}s后Activity不在前台"
    echo "Activity left foreground after ${WAIT}s" > verify_warn_left.txt
else
    echo "✅ [L4] 当前焦点: $FRONT"
fi

echo ""
echo "════════════════════════════════"
echo "✅ 验证通过 — APK能装能启未崩"
echo "════════════════════════════════"
exit 0
