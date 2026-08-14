# ZYGOTE — PhoneTools On-Device Proof (evidence log)
### Date: 2026-08-13 · Device: Samsung SM-M176B (Exynos 1330, aarch64, Android 16)

## What was proven

The accessibility bridge (`PhoneControlService`) is live on the physical device.
The Zygote harness can now **see the real phone screen** and **dispatch real
gestures** — the capability a proot/Node harness structurally cannot have.

- `read_screen` returned the REAL accessibility tree of the app's launcher:
  every element with type, pixel bounds, and content.
- `tap(540, 900)` dispatched a real gesture via `dispatchGesture` — success.

## Raw logcat (adb logcat -s A11yProbe)

```
I A11yProbe: === A11y probe: running in app process 14077 ===
I A11yProbe: [read_screen] -> Ok(output=[text 391,842 298x91]: orkestrate
I A11yProbe: [text 410,944 260x51]: on-device agent
I A11yProbe: [text 204,1085 672x53]: press start to launch the floating agent
I A11yProbe: [button 394,1194 291x135]: Start
I A11yProbe: [text 90,1419 900x79]: fully local · no cloud · all inference on this arm chip)
I A11yProbe: [tap] -> Ok(output=Gesture dispatched)
I A11yProbe: === A11y probe done ===
```

## Key files

- `app/src/main/java/com/example/llama/PhoneControlService.kt` — the
  AccessibilityService; publishes itself to `PhoneTools.accessibility`.
- `app/src/main/res/xml/accessibility_service_config.xml` — grants
  `canRetrieveWindowContent` + `canPerformGestures`.
- `app/src/main/java/com/example/llama/A11yProbeReceiver.kt` — ADB-triggered
  in-process probe (`goAsync()` keeps the process alive).
- `lib/src/main/java/com/zygote/agent/PhoneTools.kt` — the 9-tool set.

## How to reproduce

```bash
# build + install (reinstall wipes data → re-push model + re-enable a11y)
JAVA_HOME=../jdk/jdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell run-as com.example.llama.aichat sh -c 'mkdir -p files/models && \
  cp /data/local/tmp/LFM2.5-230M-Q4_0.gguf files/models/'
adb shell settings put secure enabled_accessibility_services \
  com.example.llama.aichat/com.example.llama.PhoneControlService
adb shell settings put secure accessibility_enabled 1

# fire the in-process probe (needs an explicit action + fully-qualified name)
adb shell am start -n com.example.llama.aichat/.MainActivity
adb shell am broadcast -n com.example.llama.aichat/com.example.llama.A11yProbeReceiver \
  -a com.zygote.PROBE
adb logcat -s A11yProbe
```

## Notes / gotchas

- Instrumented tests run in the TEST process; `PhoneTools.accessibility` is a
  per-process static, so the a11y bridge must be exercised from the APP process
  (hence the broadcast receiver probe).
- `am broadcast` with only `-n` and no explicit `-a` action did NOT deliver;
  an explicit action + fully-qualified component works.
- Reinstalling the APK wipes app data (`files/models`) — re-push models after
  every `adb install`.
