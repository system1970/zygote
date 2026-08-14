#!/bin/bash
echo "=== A. CPU part decoding ==="
echo "cpu0-5 part=0xd05 => Cortex-A55 | cpu6-7 part=0xd41 => Cortex-A78"
echo
echo "=== B. SIMD/ISA features (for llama.cpp) ==="
FEAT=$(grep -m1 Features /proc/cpuinfo)
echo "$FEAT"
echo "  dotprod (SDOT/UDOT int8): $(echo $FEAT | grep -q asimddp && echo YES || echo NO)"
echo "  fp16 (FPHP/ASIMDHP):      $(echo $FEAT | grep -qE 'fphp|asimdhp' && echo YES || echo NO)"
echo "  SVE:                      $(echo $FEAT | grep -q ' sve' && echo YES || echo NO)"
echo "  atomics (LSE):            $(echo $FEAT | grep -q atomics && echo YES || echo NO)"
echo "  aes/sha2 (crypto):        $(echo $FEAT | grep -qE 'aes|sha2' && echo YES || echo NO)"
echo
echo "=== C. Memory bandwidth test (python) ==="
python3 - <<'PY'
import time
size = 128*1024*1024  # 128MB
try:
    import array
    # sequential write bandwidth
    a = bytearray(size)
    t0 = time.perf_counter()
    for i in range(0, size, 4096):
        a[i] = 1  # touch pages
    a[:] = b'\xaa' * size  # bulk write
    t1 = time.perf_counter()
    wt = t1-t0
    # read bandwidth
    s = 0
    t2 = time.perf_counter()
    # sum every 4 bytes
    for i in range(0, size, 16):
        s += a[i]
    t3 = time.perf_counter()
    rt = t3-t2
    print(f"write 128MB: {wt:.3f}s => {size/wt/1e9:.2f} GB/s")
    print(f"read  128MB (stride): {rt:.3f}s => {size/rt/1e9:.2f} GB/s")
except Exception as e:
    print("bandwidth test failed:", e)
PY
echo
echo "=== D. Cache info via alternate paths ==="
for p in /sys/kernel/debug /proc/sys; do echo "--- $p ---"; ls $p 2>/dev/null | head; done
echo "--- lscpu? ---"; which lscpu && lscpu 2>/dev/null | head -40 || echo "no lscpu"
echo
echo "=== E. NPU device detail ==="
ls -la /dev/ 2>/dev/null | grep -iE "npu|dsp"
echo "--- /dev input/npu dirs ---"
ls -la /dev/input 2>/dev/null | head
echo
echo "=== F. Available memory for apps (ActivityManager view) ==="
echo "MemAvailable: $(grep MemAvailable /proc/meminfo)"
echo "SwapFree:     $(grep SwapFree /proc/meminfo)"
echo "CmaTotal/CmaFree: $(grep -E 'CmaTotal|CmaFree' /proc/meminfo)"
echo
echo "=== G. CPU clock under no-load vs governor ==="
echo "governor: $(cat /sys/devices/system/cpu/cpu6/cpufreq/scaling_governor 2>/dev/null)"
echo "A78 current: $(cat /sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq 2>/dev/null)"
echo "A78 avail freqs: $(cat /sys/devices/system/cpu/cpu6/cpufreq/scaling_available_frequencies 2>/dev/null)"
echo "A55 current: $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null)"
echo "A55 avail freqs: $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies 2>/dev/null)"
echo
echo "=== H. dm partition sizes (storage) ==="
for p in /sys/devices/virtual/block/dm-*; do
  name=$(basename $p)
  size=$(cat $p/size 2>/dev/null)
  [ -n "$size" ] && echo "  $name: $((size*512/1024/1024)) MB"
done 2>/dev/null | head -20
echo "--- filesystems ---"
df -h / /data /root 2>/dev/null | tail -5
