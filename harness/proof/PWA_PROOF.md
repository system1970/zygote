# ZYGOTE — PWA On-Device Proof (evidence log)
### Date: 2026-08-14 · Device: Samsung SM-M176B (Exynos 1330, aarch64, Android 16)

## What was proven

The DeepSeek-Harness-style PWA runs **on the phone**, served by the app's
embedded local server (ZygoteServer, 127.0.0.1:8787) from bundled assets, with
the /v1 API contract live:

- `GET /` + `/index.html` → PWA shell served
- `GET /manifest.webmanifest`, `/icons/zygote.svg` → PWA installable assets
- `GET /v1/skills` → "1 skills loaded" shown in UI
- `GET /v1/telemetry` → polled every 3 s by the footer (live tok/s/TTFT strip)
- WebView renders the full DeepSeek-Harness layout: nav rail, header
  ("Greetings from the user"), Chat|Trajectory tabs, composer, to-dos panel,
  "Full access" permissions chip, model selector (LFM2.5-2.6B · Max)

## Evidence

- `screenshots/pwa_live.png` — screencap of the running PWA (1080×2340)
- Raw server log (adb logcat -s ZygoteServer):
```
I ZygoteServer: GET /icons/zygote.svg
I ZygoteServer: GET /v1/skills
I ZygoteServer: GET /
I ZygoteServer: GET /index.html
I ZygoteServer: GET /manifest.webmanifest
I ZygoteServer: GET /v1/telemetry     (…every 3 s…)
```

## Architecture recap (the seam)

```
PwaActivity (WebView)
   └─ http://127.0.0.1:8787 ── ZygoteServer (in-app, localhost-only)
        ├─ static: assets/zygote-pwa/*  (React build, 198K)
        └─ /v1/*: telemetry · agent/run (SSE) · skills · session
              └─ harness: AgentLoop + NativeModelBackend + PhoneTools
                   └─ llama.cpp .so (KleidiAI) — all native, on-device
```

## How to reproduce

```bash
bash scripts/setup_device.sh   # install APK + restore models from /sdcard
adb shell am start -n com.example.llama.aichat/com.example.llama.PwaActivity
adb logcat -s ZygoteServer     # watch requests roll in
adb exec-out screencap -p > pwa_live.png
```
