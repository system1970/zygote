# ZYGOTE — On-Device ARM Benchmark (raw evidence, v2)
### Date: 2026-08-14 · Device: Samsung SM-M176B (Exynos 1330, aarch64, Android 16)
### Runtime: llama.cpp (KleidiAI=ON, OpenMP) via JNI · all measurements on-device

## THE headline finding: Q4_0 (KleidiAI) vs Q4_K_M (generic fallback)

llama.cpp's KleidiAI backend ships matmul kernels ONLY for Q4_0 and Q8_0.
Every K-quant (Q4_K_M, Q3_K_L, …) silently falls back to generic CPU kernels.
Measured on THIS device, same 2.6B model, same engine:

| Metric | LFM2.5-2.6B Q4_0 | LFM2.5-2.6B Q4_K_M | Δ |
|---|---|---|---|
| Prefill (pp128) | **28.8 t/s** | 14 t/s | **2.06×** |
| Decode (tg64) | **6.99 t/s** | 5.76 t/s | +21% |
| TTFT | **1119 ms** | 1717 ms | **35% faster** |
| Size on disk | 1520 MB | 1597 MB | smaller |
| Load time | 7.4 s | 10.1 s | faster |

Raw logcat (adb logcat -s ZygoteBench):
```
| lfm2 2.6B Q4_0 | 1.48GiB | 2.7B | CPU | pp 128 | 28.8 ± 0 |
| lfm2 2.6B Q4_0 | 1.48GiB | 2.7B | CPU | tg 64 | 6.99 ± 0 |   TTFT: 1119 ms
| lfm2 2.6B Q4_K - Medium | 1.55GiB | 2.7B | CPU | pp 128 | 14 ± 0 |
| lfm2 2.6B Q4_K - Medium | 1.55GiB | 2.7B | CPU | tg 64 | 5.76 ± 0 |  TTFT: 1717 ms
```

Context: llama.cpp PR #25701 ("warn once when a weight type has no KleidiAI
kernel") measured the same effect on a MediaTek Dimensity 7300: 43 t/s (Q3_K_L,
generic) vs 121 t/s (Q4_0, KleidiAI) — ~2.8×.

## Full model table (all default config, 4 threads)

| Model | Size | Load | Prefill | Decode | TTFT |
|---|---|---|---|---|---|
| LFM2.5-230M Q4_0 | 142 MB | 0.49 s | **322 t/s** | **63.5 t/s** | **101 ms** |
| LFM2.5-2.6B Q4_0 | 1520 MB | 7.4 s | 28.8 t/s | 6.99 t/s | 1119 ms |
| LFM2.5-2.6B Q4_K_M | 1597 MB | 10.1 s | 14 t/s | 5.76 t/s | 1717 ms |

230M vs 2.6B (Q4_0): 11× faster decode, 9× faster TTFT, ~11× lighter RAM.

## What we tried that did NOT help (honest negative result)

**A78 big-core pinning** (sched_setaffinity → cores 6-7, the 2× Cortex-A78):
- PINNED: pp 141 t/s, tg 26.5 t/s, TTFT 243 ms (230M)
- DEFAULT: pp 252 t/s, tg 44.3 t/s, TTFT 128 ms (230M)
- Pinning ALL process threads (incl. binder/GC/OpenMP workers) to 2 cores
  causes contention and even hangs on longer runs. This device is
  memory-bandwidth-bound; the default scheduler wins.
- Lesson: on this class of phone, thread-count/scheduler tuning beats naive
  big-core pinning. (The ggml threadpool API is also unavailable: this build
  uses GGML_OPENMP=ON, and attaching a threadpool to an OpenMP context
  segfaults — verified via tombstone.)

## Reproduce

```bash
JAVA_HOME=../jdk/jdk ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
bash ../scripts/setup_device.sh          # install app + restore models (fast, from /sdcard)
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am force-stop com.example.llama.aichat
adb shell am instrument -w -r -e class com.example.llama.ZygoteBenchmarkTest \
  com.example.llama.aichat.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -s ZygoteBench
```
