# ZYGOTE — On-Device Agent Harness for Arm Phones

> *A private, offline agent that lives on your phone and can act on it —
> seeing the screen, controlling the device, running commands — powered by a
> sub-3B model running natively on Arm. No cloud. No data leaves the device.*

**Track 3 · Mobile AI · Arm Create: AI Optimization Challenge**
**Device target:** Samsung SM-M176B (Exynos 1330 · 2×Cortex-A78 @2.4GHz + 6×Cortex-A55 @2.0GHz · Mali-G68 MP2 · 7.6GB RAM · no NPU)

---

## What this is

Zygote is an **agent harness that runs fully on-device** — not a chatbot wrapper.
It is the "call layer for AI agents" made native: an agent loop, a tool registry,
phone-native tools (screen reading, gestures, app control, shell), a durable
session log, and a permission gate — all in Kotlin, driving llama.cpp through
JNI on the Arm CPU. A minimal PWA (DeepSeek-Harness-style) is the UI surface.

**Model + Harness = Agent.** The model is the skill-picker; the harness is the
reliability engineering. Zygote runs LFM2.5 (230M fast path / 2.6B heavy path),
both quantized for this device, and routes between them.

## Proven on real hardware (evidence in `proof/`)

| Proof | Result |
|---|---|
| **Agentic spine** (`SPINE_PROOF.md`) | Full loop on-device: model → LFM2.5 tool call → `shell` executes → final answer. Real logcat. |
| **Phone control** (`PHONETOOLS_PROOF.md`) | `read_screen` returns the real accessibility tree (elements + bounds); `tap` dispatches a real gesture. |
| **KleidiAI quant win** (`BENCHMARK.md`) | **Q4_0 is 2.06× faster prefill than Q4_K_M** on the same 2.6B model — KleidiAI kernels exist only for Q4_0/Q8_0; K-quants fall back to generic kernels. |
| **Benchmark** (`BENCHMARK.md`) | 230M: **322 t/s prefill, 63.5 t/s decode, TTFT 101ms**, 142MB. 2.6B Q4_0: 28.8 / 6.99 t/s, TTFT 1.12s, 1.5GB. Honest negative: A78 core-pinning hurts (contention). |
| **Format correctness** (JVM verify) | LFM2.5 chat template + tool-call parsing match Liquid docs byte-for-byte. |

## Why this is a Track 3 submission

- **Runs fully on-device on Arm** — llama.cpp .so (KleidiAI), no cloud, no Node, no proot.
- **Real agentic capability** — the agent can see the screen and act on the device,
  the thing a proot/Node harness structurally cannot do.
- **Privacy + offline + battery** — the Mobile AI pillars, engineered in.
- **Reusable artifacts** — harness core, PhoneTools set, model recipe, migration
  template, benchmark suite, PWA.

## Reproduce

```bash
# Build + install (uses bundled JDK)
cd zygote-app
export JAVA_HOME=$PWD/../jdk/jdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Models into app storage
adb shell run-as com.example.llama.aichat sh -c \
  'mkdir -p files/models && cp /data/local/tmp/LFM2.5-230M-Q4_0.gguf files/models/'

# Enable phone control (Settings → Accessibility → Zygote phone control)
adb shell settings put secure enabled_accessibility_services \
  com.example.llama.aichat/com.example.llama.PhoneControlService
adb shell settings put secure accessibility_enabled 1

# Run the on-device spine test
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.llama.ZygoteSpineTest
adb logcat -s ZygoteSpine
```

## Artifacts

- `harness/lib/.../com/zygote/agent/` — AgentLoop, ToolRegistry, PhoneTools (9 tools), Lfm2Format, NativeModelBackend
- `zygote-pwa/` — React PWA UI (DeepSeek-Harness-style), talks to `127.0.0.1:8787`
- `proof/` — SPINE_PROOF.md, PHONETOOLS_PROOF.md, BENCHMARK.md (raw logcat evidence)
- `SPEC.md` — the immutable contract

## License / notes

Harness core: original work (MIT). llama.cpp: MIT. LFM2.5: Apache-2.0 (Liquid AI).
DeepSeek Harness / Tau / opencode: inspiration for the design vocabulary (not vendored).
