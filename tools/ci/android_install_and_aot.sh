#!/usr/bin/env bash
# 在 Android 设备上安装 APK，并强制 ART 全量 AOT 编译。
#
# 为什么必须 AOT：debuggable 的包 ART 只做 verify 不生成机器码，Compose 每帧都
# 走解释器；非 debuggable 的包安装时也只是 verify，要靠后台 dexopt 或这条命令
# 才会变成 speed。装完不 AOT，观感上仍然是「卡」。
#
# 用法:
#   tools/ci/android_install_and_aot.sh dist/app-debug.apk [包名]
#   SU=adb tools/ci/android_install_and_aot.sh ...   # 走 adb 而不是本机 su
#
# 环境变量: PKG（默认 com.inmyfeelings.ai.debug）、SU（默认 "su -c"）。
set -euo pipefail

APK="${1:?用法: $0 <apk> [包名]}"
PKG="${2:-${PKG:-com.inmyfeelings.ai.debug}}"
SU_CMD="${SU:-su -c}"

[ -f "$APK" ] || { echo "APK 不存在: $APK" >&2; exit 1; }
echo "==> 安装 $APK -> $PKG"

# 先在设备上放一份，避免 /storage 权限问题（root 读 sdcard 有时受限）
device_apk="/data/local/tmp/install-target.apk"
$SU_CMD "cp '$APK' '$device_apk' && chmod 644 '$device_apk'" || {
  echo "复制失败；如果 /storage 不可读，先手动拷贝到 /data/local/tmp" >&2
  exit 1
}

$SU_CMD "am force-stop '$PKG'" || true
# -r 覆盖安装保数据；-d 允许同 versionCode
$SU_CMD "pm install -r -d '$device_apk'"

echo "==> 强制 AOT（347MB 的包大约要 2-5 分钟）"
$SU_CMD "cmd package compile -m speed -f '$PKG'"

status=$($SU_CMD "dumpsys package '$PKG' | grep -E 'arm64: \[status'" | head -1)
echo "==> ART: $status"
case "$status" in
  *status=speed*) ;;
  *) echo "警告: 没有变成 speed，检查包是不是 debuggable（aapt2 dump xmltree … | grep debuggable）" >&2 ;;
esac

echo
echo "自检（可选）:"
echo "  su -c 'dumpsys gfxinfo $PKG reset'"
echo "  # 滑动几下"
echo "  su -c 'dumpsys gfxinfo $PKG | head -24'"
echo "  判读: GPU percentile 低但 Slow UI thread 高 => 主线程；p90 > 16ms 需要看"
