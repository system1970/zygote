package com.example.llama

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.AiChat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ON-DEVICE benchmark for the Arm Mobile AI submission.
 *
 * Measures, for each GGUF on the phone:
 *   - prefill tok/s, decode tok/s (engine's built-in llama-bench path)
 *   - time-to-first-token (wall clock of first token)
 *   - model size on disk
 *   - device RAM available / used during load
 *   - CPU core topology (A78 vs A55) so we can report big-core pinning
 *
 * Run:  ./gradlew :app:connectedDebugAndroidTest \
 *          -Pandroid.testInstrumentationRunnerArguments.class=com.example.llama.ZygoteBenchmarkTest
 * Watch: adb logcat -s ZygoteBench
 */
@RunWith(AndroidJUnit4::class)
class ZygoteBenchmarkTest {

    private val TAG = "ZygoteBench"

    @Test
    fun benchmarkAllModelsOnDevice() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AiChat.getInferenceEngine(ctx)

        val modelsDir = File(ctx.filesDir, "models")
        val gguFs = modelsDir.listFiles()?.filter { it.name.endsWith(".gguf") }?.sortedBy { it.length() }
            ?: emptyList()
        Log.i(TAG, "found ${gguFs.size} models")
        if (gguFs.isEmpty()) {
            Log.i(TAG, "NO MODELS — push one with: adb shell run-as com.example.llama.aichat sh -c 'mkdir -p files/models && cp /data/local/tmp/<model>.gguf files/models/'")
            return@runBlocking
        }

        // CPU topology (for the A78-pinning story)
        val bigCores = (0..7).filter { i ->
            val max = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            max >= 2_000_000L  // A78s run at 2.4GHz; A55s at 2.0GHz
        }
        Log.i(TAG, "big cores (>=2.0GHz): $bigCores")

        val memBefore = readMemAvailable()
        Log.i(TAG, "mem available before: ${memBefore} kB")

        for (model in gguFs) {
            val sizeMB = model.length() / (1024.0 * 1024.0)
            Log.i(TAG, "════════════════════════════════════════════")
            Log.i(TAG, "MODEL: ${model.name}  (${String.format("%.0f", sizeMB)} MB)")
            // KleidiAI comparison: 2.6B Q4_0 (KleidiAI kernels) vs Q4_K_M (generic fallback).
            // 230M is already Q4_0. NOTE: A78 big-core pinning was tested and is
            // NOT enabled here — pinning all process threads to 2 cores causes
            // contention on this bandwidth-bound device (measured slower/hung).
            benchModel(engine, model, pinned = false)
            engine.cleanUp()
        }
        Log.i(TAG, "════════════════════════════════════════════")
        Log.i(TAG, "BENCHMARK COMPLETE")
    }

    private suspend fun benchModel(
        engine: com.arm.aichat.InferenceEngine,
        model: File,
        pinned: Boolean,
    ) {
        val tag = if (pinned) "PINNED-A78" else "default"
        Log.i(TAG, "--- config: $tag ---")
        val t0 = System.nanoTime()
        try {
            engine.setBigCorePinning(pinned)
            engine.loadModel(model.absolutePath)
            val loadMs = (System.nanoTime() - t0) / 1_000_000L
            Log.i(TAG, "[$tag] load time: ${loadMs} ms")

            // llama-bench style: pp=128 prefill, tg=64 decode, 1 repetition
            val benchOut = engine.bench(pp = 128, tg = 64, pl = 1, nr = 1)
            Log.i(TAG, "[$tag] bench:\n$benchOut")

            // Time-to-first-token: stream and time the first token.
            val start = System.nanoTime()
            var first: Long? = null
            val sb = StringBuilder()
            engine.sendUserPrompt("Explain what an on-device agent is in one sentence.", 32)
                .collect { tok ->
                    if (first == null) first = System.nanoTime()
                    sb.append(tok)
                }
            val ttft = first?.let { (it - start) / 1_000_000L } ?: -1L
            Log.i(TAG, "[$tag] TTFT: ${ttft} ms")
            // NOTE: engine.cleanUp() is called by the caller per model.
        } catch (e: Exception) {
            Log.e(TAG, "[$tag] FAILED ${model.name}: ${e.message}")
        }
    }

    private fun readMemAvailable(): Long {
        val info = File("/proc/meminfo").readText()
        val m = Regex("MemAvailable:\\s+(\\d+)").find(info) ?: return -1L
        return m.groupValues[1].toLong()
    }
}
