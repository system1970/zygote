package com.example.llama

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zygote.agent.PhoneTools
import com.zygote.agent.ToolContext
import com.zygote.agent.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug probe: runs read_screen + a tap INSIDE the app process, where
 * PhoneControlService is bound, and logs the real results.
 *
 * Trigger:  adb shell am broadcast -n com.example.llama.aichat/.A11yProbeReceiver
 * Watch:    adb logcat -s A11yProbe
 */
class A11yProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Debug-only: refuse to run in release builds.
        val debuggable = (context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.w(TAG, "probe ignored: not a debuggable build")
            return
        }
        Log.i(TAG, "=== A11y probe: running in app process ${android.os.Process.myPid()} ===")
        // goAsync keeps the process alive until the probe coroutine finishes.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val registry = ToolRegistry()
                PhoneTools(context).registerAll(registry)

                val read = registry.get("read_screen")
                val res = read?.handler(
                    ToolContext("a11y-probe") { _, _ -> true },
                    mapOf("max_elements" to 50),
                )
                Log.i(TAG, "[read_screen] -> $res")
                if (res is com.zygote.agent.ToolResult.Ok) {
                    Log.i(TAG, "[read_screen sample]\n${res.output.take(800)}")
                }

                val tap = registry.get("tap")
                val tapRes = tap?.handler(
                    ToolContext("a11y-probe") { _, _ -> true },
                    mapOf("x" to 540, "y" to 900),
                )
                Log.i(TAG, "[tap] -> $tapRes")
                Log.i(TAG, "=== A11y probe done ===")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "A11yProbe"
    }
}
