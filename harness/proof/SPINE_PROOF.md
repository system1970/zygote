# ZYGOTE — Spine Proof (evidence log)
### Date: 2026-08-13 · Device: Samsung SM-M176B (Exynos 1330, aarch64, Android 16)

## What was proven

The complete on-device agentic loop ran on the physical phone:

1. **Native inference**: LFM2.5-230M-Q4_0.gguf (149 MB) loaded via llama.cpp `.so`
   through JNI (KleidiAI build).
2. **LFM2.5 tool-call generation**: the model emitted a tool call in the exact
   documented format `<|tool_call_start|>[shell(command="echo on-device-agent-alive")]<|tool_call_end|>`.
3. **Harness parsing + dispatch**: `AgentLoop` parsed the call, `ToolRegistry`
   resolved `shell`, the permission gate passed.
4. **Tool execution on device**: the shell tool ran `echo on-device-agent-alive`
   via Runtime.exec — exit=0, output captured.
5. **Result fed back**: the model read the tool result and produced a final
   natural-language answer.

No cloud. No Node. No proot. Pure native ARM + Kotlin harness.

## Raw logcat (adb logcat -s ZygoteSpine)

```
08-13 23:02:37.188 I ZygoteSpine: using model: /data/user/0/com.example.llama.aichat/files/models/LFM2.5-230M-Q4_0.gguf (149 MB)
08-13 23:02:38.449 I ZygoteSpine: model loaded + system prompt set
08-13 23:02:41.327 I ZygoteSpine: [tool-start] shell {command=echo on-device-agent-alive}
08-13 23:02:41.358 I ZygoteSpine: [tool-result] shell -> Ok(output=exit=0
08-13 23:02:41.358 I ZygoteSpine: on-device-agent-alive)
08-13 23:02:45.886 I ZygoteSpine: [complete] The shell command `echo on-device-agent-alive` has been executed successfully. The output is:
08-13 23:02:45.886 I ZygoteSpine: echo on-device-agent-alive
08-13 23:02:45.886 I ZygoteSpine: === SPINE SUMMARY ===
08-13 23:02:45.886 I ZygoteSpine: events: 3 | tool-calls: 1 | final: The shell command `echo on-device-agent-alive` has been executed successfully.
```

## How to reproduce

```bash
# build + install app with the harness module
cd orkestrate-app
JAVA_HOME=../jdk/jdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# run the on-device spine test
JAVA_HOME=../jdk/jdk ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.llama.ZygoteSpineTest

# watch the trajectory
adb logcat -s ZygoteSpine
```

Test: `app/src/androidTest/java/com/example/llama/ZygoteSpineTest.kt`
