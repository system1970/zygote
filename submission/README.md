# orkestrate — on-device agent

A native Android app that runs a **local LLM entirely on the phone's Arm chip**,
with a **floating bubble** you can summon over any app, an expandable chat, live
tok/s telemetry, and screen-vision grounding. **No cloud, no network, no NPU.**

Built for the **Arm Create: AI Optimization Challenge — Track 3 (Mobile AI)**.

---

## The product

A small circle floats on your screen, always there. Tap it and it expands into a
minimal chat. Ask it anything — it answers using a model running **on this phone**,
and it can **see your screen** and point at things. Everything stays on-device:
private by design, works offline, zero marginal cost.

> "An AI that lives on your phone, sees your screen, and acts — on a $120 device
> with no NPU, fully offline."

---

## What's proven (screenshots in `proof/`)

| Proof | Status |
|---|---|
| Native Android APK (Kotlin + llama.cpp JNI + KleidiAI) | ✅ built from source |
| **Floating bubble overlay** above the home screen | ✅ `proof/bubble_over_home.png` |
| Expandable dark-monospace chat UI | ✅ `proof/08_2.6b_ready.png` |
| **On-device model picker** (all GGUFs on-device) | ✅ `proof/05_model_picker.png` |
| **Real on-device inference** — LFM2.5-2.6B | ✅ generated "Hello! How can I help you today?" |
| **Turbo mode** — LFM2.5-230M | ✅ 18.5+ tok/s, `proof/06_turbo_230m.png` |
| Live tok/s / ttft / mb telemetry | ✅ streaming real values |
| Screen-vision grounding panel | ✅ wired (demo grounding) |

**Device:** Samsung Galaxy M17 5G (SM-M176B) · Exynos 1330 (5nm) · 2×Cortex-A78
@2.4GHz + 6×Cortex-A55 @2.0GHz · Mali-G68 MP2 · LPDDR4X 8GB · UFS 2.2 · **no usable NPU**.

---

## Measured on-device performance

Verified on the physical phone (llama.cpp CPU backend, KleidiAI NEON):

| Model | Quant | Decode tok/s | Notes |
|---|---|---|---|
| LFM2.5-2.6B | Q4_K_M | **5.4** | hybrid conv+GQA, 128K ctx |
| LFM2.5-2.6B | Q4_0 | **5.0** | KleidiAI DOTPROD active |
| LFM2.5-230M | Q4_0 | **18.5+** | turbo mode; full response in ~0.2s |

**Key finding:** decode tok/s did *not* rise from Q4_K_M to Q4_0 despite KleidiAI
kernels being active. This device is **memory-bandwidth-bound**, not compute-bound —
a concrete, reproducible result showing why quant/kernel choice barely moves decode
speed on a budget Arm phone. The biggest speed lever is **model size**, not
quantization: the 230M runs ~3.5× faster than the 2.6B. That's why the app exposes
a **model picker** — users trade quality for speed on the same hardware.

---

## How it's built

```
orkestrate-app/
  lib/   llama.cpp via JNI (C++ ai_chat.cpp)  -> libai-chat.so + KleidiAI CPU backends
  app/   Kotlin: BubbleService, ChatActivity, BoxOverlayView (bounding boxes)
```

- **Inference:** llama.cpp built for `arm64-v8a` with KleidiAI, Flash Attention,
  fused Gated Delta Net (LFM hybrid), OpenMP.
- **Floating bubble:** `BubbleService` (foreground service + `SYSTEM_ALERT_WINDOW`),
  draggable, tap-to-open chat.
- **Chat + model picker:** streaming token Flow from the engine, live tok/s
  telemetry, and a **Spinner that lists every GGUF on-device** — switch models
  at runtime (unload previous, load selected).
- **Vision:** `MediaProjection` screen capture + `BoxOverlayView` draws grounded
  bounding boxes (real VL grounding ready to plug in).

---

## Build & run

Requirements: JDK 17+, Android SDK (platform-36, build-tools 36, NDK 29, CMake 3.31).

```bash
cd orkestrate-app
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Place a GGUF in the app's private `files/models/` (or pick via the file picker):

```bash
adb push model.gguf /data/local/tmp/
adb shell run-as com.example.llama.aichat cp /data/local/tmp/model.gguf files/models/
```

---

## Honest engineering notes (Tech-Impl value)

1. **No NPU, weak 2-core Mali** — the CPU NEON/SDOT path on the 2× A78 is the
   fastest thing this device offers; the app targets it directly.
2. **Memory-bandwidth-bound decode** — measured, not assumed (see table above).
3. **Hybrid model architecture** (LFM conv+GQA) keeps KV cache and decode latency
   low vs. a same-size dense transformer — the right model class for this silicon.
4. **KleidiAI** accelerates Q4_0/Q8_0 matmuls on A78 (DOTPROD confirmed in logs).

Future work: LFM2.5-VL-3B (mmproj, already downloaded) for real screen grounding;
higher-tok/s turbo profiles; batched vision over video frames.
