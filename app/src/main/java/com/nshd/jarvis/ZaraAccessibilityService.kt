package com.nshd.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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

    private fun label(n: AccessibilityNodeInfo): String =
        ((n.contentDescription?.toString() ?: "") + " " + (n.text?.toString() ?: "")).trim()

    private fun collect(n: AccessibilityNodeInfo?, depth: Int, pattern: Regex, skip: Regex?, out: MutableList<Pair<Int, AccessibilityNodeInfo>>) {
        if (n == null || depth > 45) return
        val l = label(n)
        if (l.isNotEmpty() && n.isVisibleToUser && pattern.containsMatchIn(l) && (skip == null || !skip.containsMatchIn(l))) {
            val r = Rect(); n.getBoundsInScreen(r); out.add(r.top to n)
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), depth + 1, pattern, skip, out)
    }

    /** Taps the top-most visible node whose label matches [pattern] (used for "play the first result"). */
    fun clickFirstMatching(pattern: Regex, skip: Regex?): A11yOutcome {
        val root = rootInActiveWindow ?: return A11yOutcome(false, "No window content is available yet.")
        if (root.packageName?.toString() in GUARD) return A11yOutcome(false, "I don't interact with system dialogs.", blocked = true)
        val found = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
        collect(root, 0, pattern, skip, found)
        val best = found.minByOrNull { it.first } ?: return A11yOutcome(false, "No matching item on screen yet.")
        var n: AccessibilityNodeInfo? = best.second
        var hops = 0
        while (n != null && !n.isClickable && hops++ < 6) n = n.parent
        if (n == null || !n.isClickable) return A11yOutcome(false, "The matching item can't be tapped.")
        return A11yOutcome(n.performAction(AccessibilityNodeInfo.ACTION_CLICK), "")
    }

    /** True if any visible node's label contains one of [words] (case-insensitive). Used to confirm playback. */
    fun hasText(words: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val re = Regex(words.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        val found = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
        collect(root, 0, re, null, found)
        return found.isNotEmpty()
    }

    // ------------------------------------------------------------------ agent: eyes
    private val snapNodes = ArrayList<AccessibilityNodeInfo>()

    /** A compact, numbered description of what is on screen. Password fields are never read. */
    fun snapshot(maxNodes: Int = 60): Pair<String, String> {
        val root = rootInActiveWindow ?: return "" to "(no window content available)"
        val pkg = root.packageName?.toString() ?: ""
        snapNodes.clear()
        var unlabeled = 0
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || snapNodes.size >= maxNodes || sb.length > 3200) return
            val cls = (n.className?.toString() ?: "").substringAfterLast('.')
            val txt = n.text?.toString().orEmpty().replace('\n', ' ').take(80)
            val desc = n.contentDescription?.toString().orEmpty().replace('\n', ' ').take(80)
            val labeled = txt.isNotEmpty() || desc.isNotEmpty()
            val icon = !labeled && n.isClickable && !n.isEditable && !n.isScrollable
            if (icon) unlabeled++
            val interesting = n.isVisibleToUser && (labeled || n.isEditable || n.isScrollable || (icon && unlabeled <= 8))
            if (interesting) {
                val r = Rect(); n.getBoundsInScreen(r)
                val id = snapNodes.size; snapNodes.add(n)
                val flags = ArrayList<String>()
                if (n.isClickable) flags.add("tap"); if (n.isEditable) flags.add("edit"); if (n.isScrollable) flags.add("scroll")
                if (n.isChecked) flags.add("checked"); if (n.isFocused) flags.add("focused")
                val shown = if (n.isPassword) "[password field - hidden]" else listOf(txt, desc).filter { it.isNotEmpty() }.distinct().joinToString(" / ")
                sb.append('[').append(id).append("] ").append(cls).append(' ').append('"').append(shown).append('"')
                if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(",")).append(')')
                sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return pkg to sb.toString().ifBlank { "(screen has no readable elements)" }
    }

    fun nodeLabel(id: Int): String = snapNodes.getOrNull(id)?.let { label(it) } ?: ""

    fun labelAt(x: Int, y: Int): String {
        val root = rootInActiveWindow ?: return ""
        var best: AccessibilityNodeInfo? = null; var bestArea = Long.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.contains(x, y) && n.isVisibleToUser && (n.isClickable || label(n).isNotEmpty())) {
                val a = r.width().toLong() * r.height(); if (a in 1 until bestArea) { bestArea = a; best = n }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return best?.let { label(it) } ?: ""
    }

    fun focusedFieldText(): String {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return ""
        return if (f.isPassword) "" else f.text?.toString().orEmpty().take(200)
    }

    // ------------------------------------------------------------------ agent: hands
    fun tapNode(id: Int, cb: (A11yOutcome) -> Unit) {
        val n = snapNodes.getOrNull(id) ?: return cb(A11yOutcome(false, "Element [$id] is unknown or stale. Call get_screen again."))
        n.refresh()
        var c: AccessibilityNodeInfo? = n; var hops = 0
        while (c != null && !c.isClickable && hops++ < 5) c = c.parent
        if (c != null && c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return cb(A11yOutcome(true, ""))
        val r = Rect(); n.getBoundsInScreen(r)
        tapPoint(r.centerX(), r.centerY(), 60, cb)
    }

    fun longPressNode(id: Int, cb: (A11yOutcome) -> Unit) {
        val n = snapNodes.getOrNull(id) ?: return cb(A11yOutcome(false, "Element [$id] is unknown or stale. Call get_screen again."))
        n.refresh()
        if (n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return cb(A11yOutcome(true, ""))
        val r = Rect(); n.getBoundsInScreen(r)
        tapPoint(r.centerX(), r.centerY(), 700, cb)
    }

    fun tapPoint(x: Int, y: Int, durationMs: Long, cb: (A11yOutcome) -> Unit) {
        val p = Path(); p.moveTo(x.toFloat(), y.toFloat()); p.lineTo(x.toFloat() + 0.1f, y.toFloat())
        gesture(p, durationMs.coerceIn(30, 1500), cb)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, cb: (A11yOutcome) -> Unit) {
        val p = Path(); p.moveTo(x1.toFloat(), y1.toFloat()); p.lineTo(x2.toFloat(), y2.toFloat())
        gesture(p, durationMs.coerceIn(80, 2500), cb)
    }

    private fun gesture(path: Path, dur: Long, cb: (A11yOutcome) -> Unit) {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { cb(A11yOutcome(true, "")) }
            override fun onCancelled(d: GestureDescription?) { cb(A11yOutcome(false, "The gesture was cancelled by Android.")) }
        }, null)
        if (!ok) cb(A11yOutcome(false, "Android refused the gesture."))
    }

    fun pressKey(key: String): A11yOutcome {
        val code = when (key.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents", "recent_apps" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return A11yOutcome(false, "Unsupported key “$key”. Use back, home, recents, notifications or quick_settings.")
        }
        return A11yOutcome(performGlobalAction(code), "")
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
