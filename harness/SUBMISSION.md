# ZYGOTE — A Private, On-Device Agent for Arm Phones

> **An agent that lives on your phone, sees its screen, controls the device,
> and runs a terminal — fully offline, powered by a sub-3B model on native Arm.
> Named after Android's `zygote`: the process every app is forked from.**

**Arm Create: AI Optimization Challenge · Track 3: Mobile AI**
**Device:** Samsung Galaxy M17 (SM-M176B · Exynos 1330 · 2×Cortex-A78 @2.4GHz + 6×Cortex-A55 @2.0GHz · Mali-G68 MP2 · 7.6GB RAM · **no NPU**)

---

## 1. What it is

Zygote is an **on-device agent harness** — not a chatbot wrapper. It is the
"call layer for AI agents" made native: an agent loop, a tool registry,
phone-native tools (screen reading, gestures, app control, shell), a session
log, and a permission gate — all in Kotlin, driving llama.cpp through JNI on
the Arm CPU. A minimal PWA (DeepSeek-Harness-style) is the UI surface.

**Model + Harness = Agent.** The model is the skill-picker; the harness is the
reliability engineering. Zygote runs a **single optimized tier: LFM2.5-1.2B-Instruct
Q4_0 (664 MB)** with KleidiAI DotProd kernels, a 4096-token context, native KV
prefix caching (turn-2+ TTFT ~1.7 s), relevance-filtered tool schemas, and
speculative decoding (230M draft model verifies in batched passes).

```
PWA (React, WebView)  ── http://127.0.0.1:8787 ──►  ZygoteServer (in-app)
                                                       ├─ /v1/telemetry · agent/run (SSE) · skills · session
                                                       └─ harness: AgentLoop + ToolRegistry + PhoneTools
                                                            └─ llama.cpp .so (KleidiAI) — all native
```

## 2. Proof on real hardware (raw evidence in `proof/`)

| Proof | Result |
|---|---|
| **Agentic spine** (`SPINE_PROOF.md`) | Full loop on-device: LFM2.5-230M → tool call → `shell` executes → final answer. Real logcat. |
| **Phone control** (`PHONETOOLS_PROOF.md`) | `read_screen` returns the real accessibility tree; `tap` dispatches a real gesture. |
| **PWA live** (`PWA_PROOF.md`) | DeepSeek-Harness UI renders in-app WebView; telemetry + skills APIs polling live. Screenshot. |
| **KleidiAI quant win** (`BENCHMARK.md`) | **Q4_0 = 2.06× Q4_K_M prefill** on the same 2.6B — KleidiAI kernels exist only for Q4_0/Q8_0. |
| **Benchmark** (`BENCHMARK.md`) | 230M: **322 t/s prefill · 63.5 t/s decode · TTFT 101ms**. 2.6B Q4_0: 28.8 / 6.99 t/s · TTFT 1.12s. |
| **Format correctness** (JVM verify) | LFM2.5 chat template + tool-call parsing match Liquid docs byte-for-byte. |

## 3. Why this is a Track 3 submission (judge-rubric mapping)

| Criterion | How Zygote scores |
|---|---|
| **Technological Implementation (40)** | Native Kotlin harness + llama.cpp JNI (KleidiAI) on arm64; in-app localhost server; SSE agent streaming; honest A/B optimization experiments (quant selection, core-pinning negative result documented). |
| **WOW (25)** | A phone controlling *itself* — agent sees the screen, taps UI, runs shell — fully offline on a $150 device with no NPU. "Claude Code for your pocket." |
| **Potential Impact (20)** | Reusable artifacts: harness core (`com.zygote.agent`), PhoneTools set, model recipe (Q4_0-for-KleidiAI), benchmark suite, migration template (below), PWA. |
| **UX / DevEx (15)** | One-command device setup (`setup_device.sh`), reproducible benchmarks, PWA UI, documented API contract, evidence logs. |

## 4. Reusable artifact: migration template

**"Bring this agent to your Arm device"** — the five-step port:

1. **Bundle llama.cpp** with `GGML_CPU_KLEIDIAI=ON` (+ OpenMP) for `arm64-v8a`
   (see `lib/src/main/cpp/CMakeLists.txt`).
2. **Pick models by quant, not just size**: prefer Q4_0/Q8_0 GGUFs so KleidiAI
   kernels engage (measured 2.06× prefill vs Q4_K_M). Small model for routing,
   bigger model for hard turns.
3. **Adopt the harness core** (`com.zygote.agent`): AgentLoop, ToolRegistry,
   Tool contract, Lfm2Format (exact LFM2.5 template), NativeModelBackend.
4. **Wire phone tools** via AccessibilityService + Intents (PhoneTools.kt)
   and enable the service in Settings → Accessibility.
5. **Serve the PWA** from bundled assets via the in-app server; point the PWA's
   `api.ts` at `127.0.0.1:8787`.

## 5. Reproduce

```bash
# Build + install + restore models (models survive wipes in /sdcard/zygote-models)
cd zygote-app
export JAVA_HOME=$PWD/../jdk/jdk          # JDK 21 toolchain
./gradlew assembleDebug
bash ../scripts/setup_device.sh

# Run the on-device agentic spine test
./gradlew :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am force-stop com.example.llama.aichat
adb shell am instrument -w -r -e class com.example.llama.ZygoteSpineTest \
  com.example.llama.aichat.test/androidx.test.runner.AndroidJUnitRunner

# Benchmark (Q4_0 vs Q4_K_M KleidiAI comparison)
adb shell am instrument -w -r -e class com.example.llama.ZygoteBenchmarkTest \
  com.example.llama.aichat.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -s ZygoteBench

# Open the PWA
adb shell am start -n com.example.llama.aichat/com.example.llama.PwaActivity
```

## 6. Artifacts tree

```
harness/
├── README.md            ← this file
├── SPEC.md              ← immutable design contract
├── proof/               ← raw evidence: SPINE / PHONETOOLS / PWA / BENCHMARK
├── lib/.../com/zygote/agent/   ← AgentLoop · ToolRegistry · PhoneTools · Lfm2Format · NativeModelBackend
├── verify/Verify.kt     ← JVM format/parse verification
zygote-pwa/              ← React PWA (DeepSeek-Harness-style), static build
zygote-app/          ← native Android app: engine (KleidiAI), harness, server, WebView
scripts/setup_device.sh  ← one-command device bring-up
```

## 7. Honest limits

- 2.6B decode is ~7 tok/s on this device (memory-bandwidth-bound, no NPU) —
  hence the 230M fast path for routing and short turns.
- A78 core-pinning was tested and **did not help** (contention) — documented
  as a negative result; the default scheduler wins here.
- Vision (LFM2.5-VL) deferred: needs a llama.cpp build with VL architecture
  support (mmproj/MTMD), which the bundled build lacks.

## License / credits

Harness core: original work (MIT). llama.cpp: MIT (ggml-org). LFM2.5: Apache-2.0
(Liquid AI). UI design language: DeepSeek Harness (MIT), Tau, opencode — design
vocabulary, not vendored code.
