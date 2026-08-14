#!/bin/bash
# Deep hardware probe for Samsung SM-M176B (UserLAnd proot)
echo "=========== 1. SOC / KERNEL ==========="
cat /proc/version
echo
echo "--- /proc/cpuinfo ---"
cat /proc/cpuinfo 2>/dev/null
echo
echo "=========== 2. CPU FEATURES (NEON/SVE/etc) ==========="
grep -m1 -i "Features" /proc/cpuinfo
echo
echo "--- CPUID-ish / EL0 hw caps (from /proc/cpuinfo flags above) ---"
echo
echo "=========== 3. CACHES per core ==========="
for d in /sys/devices/system/cpu/cpu0/cache/index*; do
  echo "--- $d ---"
  grep . $d/level $d/type $d/size $d/coherency_line_size $d/ways_of_associativity 2>/dev/null
done
echo
echo "=========== 4. FREQ / GOVERNOR / TOPOLOGY ==========="
for c in /sys/devices/system/cpu/cpu[0-7]/cpufreq; do
  core=$(basename $(dirname $c))
  printf "%s: min=%s max=%s cur=%s gov=%s\n" $core \
    $(cat $c/cpuinfo_min_freq 2>/dev/null) \
    $(cat $c/cpuinfo_max_freq 2>/dev/null) \
    $(cat $c/scaling_cur_freq 2>/dev/null) \
    $(cat $c/scaling_governor 2>/dev/null)
done
echo
echo "--- CPU topology ---"
for c in /sys/devices/system/cpu/cpu[0-7]/topology; do
  core=$(basename $(dirname $c))
  printf "%s core_id=%s cluster=%s\n" $core \
    $(cat $c/core_id 2>/dev/null) $(cat $c/physical_package_id 2>/dev/null)
done
echo
echo "=========== 5. MEMORY BANDWIDTH (light test) ==========="
which bc >/dev/null 2>&1 && echo "bc available" || echo "no bc"
echo
echo "=========== 6. GPU DEVICES ==========="
ls -la /dev/mali* /dev/dri/* 2>/dev/null
echo
echo "=========== 7. VULKAN / GL LIBS ==========="
ls -la /usr/lib/aarch64-linux-gnu/ 2>/dev/null | grep -iE "vulkan|GLES|EGL|OpenCL|GL\.so|libmali" 
echo "--- vulkan info tool? ---"
which vulkaninfo glxinfo clinfo 2>/dev/null || echo "none installed"
echo
echo "=========== 8. NPU / DSP ==========="
ls -la /dev/ 2>/dev/null | grep -iE "npu|dsp|openvx|accel|ion|mtk" 
grep -iE "npu|dsp" /proc/misc 2>/dev/null
echo
echo "=========== 9. STORAGE (type + speed) ==========="
ls -la /dev/block/ 2>/dev/null | head -20
echo "--- block devices ---"
for b in /sys/class/block/*; do
  name=$(basename $b)
  ro=$(cat $b/ro 2>/dev/null)
  size=$(cat $b/size 2>/dev/null)
  echo "  $name ro=$ro sectors=$size (=$((size*512/1024/1024)) MB)"
done
echo
echo "=========== 10. THERMAL ZONES ==========="
for t in /sys/class/thermal/thermal_zone*/; do
  printf "%s type=%s temp=%s\n" $(basename $t) $(cat $t/type 2>/dev/null) $(cat $t/temp 2>/dev/null)
done 2>/dev/null | head -30
echo
echo "=========== 11. POWER / ENERGY ==========="
ls /sys/class/power_supply/ 2>/dev/null
echo
echo "=========== 12. RAM RESERVED / CMA / GPU ==========="
grep -iE "CmaTotal|GpuTotal|Rbin" /proc/meminfo
echo
echo "=========== 13. DEVICE TREE compat ==========="
cat /proc/device-tree/compatible 2>/dev/null; echo
ls /proc/device-tree/ 2>/dev/null | head -30
echo
echo "=========== 14. ELF/arch info ==========="
uname -m
file /bin/sh 2>/dev/null
echo
echo "=========== 15. CLUSTER BANDWIDTH quick est (dd memcpy 64MB) ==========="
python3 - <<'PY'
import time, mmap, os
# measure ~64MB memset+read throughput as a crude RAM bandwidth proxy
n = 64*1024*1024
buf = bytearray(n)
t=time.time()
buf[:] = b'\x00'*n  # this is more flash/page; do manual
del buf
PY
