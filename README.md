# Zygote

**A private, on-device agent harness for Arm phones.**

Zygote is a native Android agent that runs entirely on-device — no cloud, no server, no data leaving the phone. It combines a Kotlin agent harness (DeepSeek-Harness / opencode-inspired loop), llama.cpp inference with Arm KleidiAI kernels, a dependency-free embedded HTTP server, and a hyper-minimal PWA interface modeled on the DeepSeek Harness web UI.

Built for the **Arm Create: AI Optimization Challenge — Track 3 (Mobile AI)**, targeting a $150 Samsung Galaxy M16 (SM-M176B, Exynos 1330, 2×Cortex-A78 + 6×Cortex-A55, no NPU, 8GB RAM).

```
┌───────────────────────────────┐     ┌──────────────────────────────────────┐
│  PWA (React) in WebView      │     │  ZygoteServer (raw ServerSocket)      │
│  - DeepSeek-Harness-style UI │◄───►│  - serves PWA from assets             │
│  - sessions, todos, telemetry│     │  - /v1/agent/run  (SSE stream)        │
└───────────────────────────────┘     │  - /v1/telemetry (per-session)       │
                                      │  - /v1/sessions, /v1/session/{id}    │
                                      └──────────────┬───────────────────────┘
                                                     │
                                      ┌──────────────▼───────────────────────┐
                                      │  AgentLoop (Kotlin, com.zygote.agent) │
                                      │  - tool loop: model → tool → result   │
                                      │  - JSONL sessions (Claude-Code style)  │
                                      │  - opencode admission + resume         │
                                      └──────────────┬───────────────────────┘
                                                     │
                                      ┌──────────────▼───────────────────────┐
                                      │  NativeModelBackend → llama.cpp (.so) │
                                      │  - KleidiAI Q4_0 (DotProd, A78 path)  │
                                      │  - KV-prefix cache across turns       │
                                      │  - relevance-filtered tool schemas    │
                                      └──────────────────────────────────────┘
```

## Why it matters

- **Truly on-device.** 1.2B model, 100% local. The `ZygoteServer` binds `127.0.0.1` only — nothing leaves the device.
- **Fast on cheap hardware.** Measured on the $150 phone: ~14-15 tok/s decode, ~4s cold TTFT, **~1.7s warm TTFT** (native KV-prefix caching — 62% faster on later turns).
- **Agentic, not just chat.** 11 tools (screen reading, tap, type, swipe, open app, call, SMS, shell, todos), LFM2.5 native tool-call parsing, per-session todos.
- **Honest telemetry.** Every number in the status bar is measured on-device and scoped per session — no invented cache-hit percentages.

## Models

| Model | Role | Size | Prefill (pp128) | Decode (tg64) |
|---|---|---|---|---|
| LFM2.5-1.2B-Instruct Q4_0 | main agent | 664 MB | 45.7-50.9 tok/s | 11.4-14.2 tok/s |

The 2.6B and 230M tiers were evaluated and dropped: 2.6B was too slow for a $150 phone at ~7 tok/s and suffered template-sensitivity issues; 230M is Liquid's data-extraction model, not a chat model. **LFM2.5-1.2B-Instruct is Liquid's own "best for most use cases" pick** — verified on-device with the full agent loop (tool calling included).

Download: `hf download LiquidAI/LFM2.5-1.2B-Instruct-GGUF LFM2.5-1.2B-Instruct-Q4_0.gguf --local-dir models/`

## Build

Prerequisites: Android SDK + NDK, JDK 17, CMake ≥ 3.22, a checkout of [llama.cpp](https://github.com/ggml-org/llama.cpp) **with the local Zygote patch applied** (see below), and `GGML_OPENMP=ON` + `GGML_CPU_KLEIDIAI=ON`.

```bash
# 1. llama.cpp must live at <repo-root>/llama.cpp (CMake references it relatively)
git clone https://github.com/ggml-org/llama.cpp.git

# 2. Apply the Zygote patch (LFM2 recurrent-state rollback for KV-prefix caching)
git -C llama.cpp apply ../patches/llama-arch-lfm2-rs-rollback.patch

# 3. Build the app
cd orkestrate-app
export JAVA_HOME=<jdk17>
./gradlew :app:assembleDebug

# 4. Push models + install
adb push ../models/LFM2.5-1.2B-Instruct-Q4_0.gguf /sdcard/zygote-models/
bash ../scripts/setup_device.sh
```

> **Note on the llama.cpp fork:** Zygote enables `n_rs_seq` rollback for the LFM2 (Gated DeltaNet) architecture so the native KV-prefix cache can trim divergent tails between turns. This is a 2-case addition to `llm_arch_supports_rs_rollback()` — included as a patch in `patches/`. It is a local build change, not an upstream contribution.

## Architecture notes

- **AgentLoop** (`lib/src/main/java/com/zygote/agent/`) — the "skill picker" pattern: the model never performs actions itself, it picks a tool + args; the harness executes and feeds results back. LFM2.5's `<|tool_call_start|>[fn(args)]<|tool_call_end|>` format is parsed natively, including multi-call blocks.
- **Sessions** — append-only JSONL per session (Claude-Code style), one event per line with monotonic `seq` (opencode style). Admission-first: the user message is durably logged before the model runs, so nothing is lost on crash. Resume = replay.
- **KV-prefix cache** (`ai_chat.cpp`) — the full formatted prompt shares a byte-identical prefix every turn; only the suffix is re-prefilled, and the divergent KV tail is trimmed via `llama_memory_seq_rm` with bounded recurrent-state rollback. Warm TTFT: **~1.7s vs ~4.6s cold (−62%)**.
- **Relevance-filtered tools** — only tools relevant to the current request are injected (Liquid's own recommendation), cutting prefill tokens 5-10× on simple turns.
- **Telemetry** — per-session counters, segment-accurate tok/s (excludes tool-gap time), real RAM/battery from the device. No hardcoded values.

## Verification

Ad-hoc verification scripts live in `harness/verify/` and proofs in `harness/proof/` (SPINE, PHONETOOLS, BENCHMARK, PWA). The full `connectedDebugAndroidTest` suite is deliberately avoided — it wipes app data on this device; the project's verification model is script-driven against a live device with models in shared storage (`scripts/setup_device.sh`).

## Known issues

See [GitHub Issues](https://github.com/system1970/zygote/issues) for the live list. Highlights:

- Session API edge cases (resume/replay of tool-result turns)
- Message rendering: an aborted run can leave the turn-ref pointing at the previous assistant bubble (mitigated, regression-tested ad-hoc)
- Hybrid KV-cache rollback window (n_rs_seq=256) — trims beyond it fall back to full re-decode
- 1.2B occasionally emits malformed tool calls; parser is regex-based, no nested-quote args yet
- No vision support yet (VL model not wired into llama.cpp MTMD)

## License

MIT
