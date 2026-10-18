package com.nshd.jarvis

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class A11yOutcome(val dispatched: Boolean, val note: String = "", val blocked: Boolean = false, val ambiguous: Boolean = false)

/**
 * User-enabled accessibility service. It does nothing on its own: it only answers explicit requests
 * from the app's action executor. It never records, stores or uploads screen content.
 */
class ZaraAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: ZaraAccessibilityService? = null
        /** Never auto-click system permission or installer dialogs. */
        private val GUARD = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller")
    }
    @Volatile var lastChange = 0L

    override fun onServiceConnected() { instance = this; super.onServiceConnected() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        // Only a timestamp is kept (used to verify that a screen actually changed). No content is read here.
        if (e == null) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            lastChange = SystemClock.uptimeMillis()
    }

    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun global(kind: String): A11yOutcome {
        val code = when (kind) {
            "go_back" -> GLOBAL_ACTION_BACK; "go_home" -> GLOBAL_ACTION_HOME; "recent_apps" -> GLOBAL_ACTION_RECENTS
            else -> return A11yOutcome(false, "Unknown gesture")
        }
        return A11yOutcome(performGlobalAction(code), "")
    }

    fun scroll(down: Boolean): A11yOutcome {
        val root = rootInActiveWindow ?: return A11yOutcome(false, "No window content is available on this screen.")
        if (root.packageName?.toString() in GUARD) return A11yOutcome(false, "I don't interact with system permission or install dialogs.", blocked = true)
        val node = findScrollable(root, 0) ?: return A11yOutcome(false, "I couldn't find anything scrollable on this screen.")
        val a = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return A11yOutcome(node.performAction(a), "")
    }

    private fun findScrollable(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) findScrollable(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    fun tapText(label: String): A11yOutcome {
        val root = rootInActiveWindow ?: return A11yOutcome(false, "No window content is available on this screen.")
        if (root.packageName?.toString() in GUARD) return A11yOutcome(false, "I don't tap system permission or install dialogs — please choose yourself.", blocked = true)
        val hits = root.findAccessibilityNodeInfosByText(label).filter { it.isVisibleToUser }
        val targets = ArrayList<AccessibilityNodeInfo>()
        for (h in hits) {
            var n: AccessibilityNodeInfo? = h
            var hops = 0
            while (n != null && !n.isClickable && hops++ < 6) n = n.parent
            if (n != null && n.isClickable && targets.none { it == n }) targets.add(n)
        }
        if (targets.isEmpty()) return A11yOutcome(false, "I couldn't find a visible control labeled “$label”.")
        if (targets.size > 1) return A11yOutcome(false, "I found ${targets.size} controls matching “$label”, so I'm not guessing. Please be more specific or tap it yourself.", ambiguous = true)
        return A11yOutcome(targets[0].performAction(AccessibilityNodeInfo.ACTION_CLICK), "")
    }

    fun typeText(text: String): A11yOutcome {
        val root = rootInActiveWindow ?: return A11yOutcome(false, "No window content is available on this screen.")
        if (root.packageName?.toString() in GUARD) return A11yOutcome(false, "I don't type into system permission or install dialogs.", blocked = true)
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return A11yOutcome(false, "No text field is focused. Tap the field first, then ask again.")
        if (field.isPassword) return A11yOutcome(false, "That is a password field. I never fill those.", blocked = true)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return A11yOutcome(false, "That field didn't accept typed text.")
        field.refresh()
        val shown = field.text?.toString().orEmpty()
        return A11yOutcome(true, if (shown.contains(text)) "verified" else "")
    }
}
