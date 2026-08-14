package com.example.llama

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.zygote.agent.PhoneTools

/**
 * The accessibility bridge that gives the Zygote harness real control of this
 * device. When enabled, it publishes itself to [PhoneTools.accessibility] so
 * the read_screen / tap / type_text / swipe tools can dispatch real gestures
 * and read the real view hierarchy.
 *
 * This is the capability a proot/Node harness structurally cannot have:
 * OS-level agency over the phone.
 */
class PhoneControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        PhoneTools.accessibility = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: events are pulled on demand via read_screen.
    }

    override fun onInterrupt() {
        // Required override; nothing to do.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        PhoneTools.accessibility = null
        return super.onUnbind(intent)
    }
}
