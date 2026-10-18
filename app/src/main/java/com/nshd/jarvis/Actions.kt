package com.nshd.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.net.URLEncoder

enum class TaskStatus { COMPLETED, PARTIALLY_COMPLETED, FAILED, BLOCKED_BY_PERMISSION, NEEDS_USER_ACTION, COULD_NOT_VERIFY }
enum class Risk { LOW, CONSEQUENTIAL }

data class ActionResult(val status: TaskStatus, val message: String, val hint: String = "", val declined: Boolean = false)
class Preflight(val risk: Risk, val preview: String, val title: String, val blocked: ActionResult?)

/**
 * Typed action executor. The model (or a local command) can only request actions from [Tools.ACTIONS];
 * each request is validated, policy-checked, optionally confirmed by the user, executed, then verified.
 */
class ActionExecutor(private val ctx: Context, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private val needsA11y = setOf("go_back", "go_home", "recent_apps", "scroll_down", "scroll_up", "tap_text", "type_text")

    fun fromJson(o: JSONObject?): ActionRequest? {
        if (o == null) return null
        val a = o.optString("action")
        if (a !in Tools.ACTIONS) return null
        return ActionRequest(a, o.optString("target").take(120), o.optString("text").take(600))
    }

    private fun blocked(s: TaskStatus, msg: String, hint: String = "") = ActionResult(s, msg, hint)

    fun title(r: ActionRequest): String = when (r.action) {
        "open_app" -> "Open ${r.target}"
        "search_youtube" -> "Search YouTube"
        "open_settings" -> "Open ${r.target.ifBlank { "general" }} settings"
        "whatsapp_draft" -> "Draft WhatsApp message"
        "go_back" -> "Go back"; "go_home" -> "Go to home screen"; "recent_apps" -> "Open recent apps"
        "scroll_down" -> "Scroll down"; "scroll_up" -> "Scroll up"
        "tap_text" -> "Tap “${r.target}”"
        "type_text" -> "Type text"
        else -> r.action
    }

    fun preflight(r: ActionRequest): Preflight {
        val ttl = title(r)
        fun block(res: ActionResult) = Preflight(Risk.LOW, ttl, ttl, res)
        if (r.action !in Tools.ACTIONS) return block(blocked(TaskStatus.FAILED, "That action isn't on my allow-list."))
        if (!prefs.b("app_control", false))
            return block(blocked(TaskStatus.BLOCKED_BY_PERMISSION, "App control is turned off.", "Turn it on in Settings → App Control & Hands-Free."))
        when (r.action) {
            "open_app" -> if (r.target.isBlank()) return block(blocked(TaskStatus.FAILED, "Which app should I open?"))
            "search_youtube", "whatsapp_draft", "type_text" -> if (r.text.isBlank()) return block(blocked(TaskStatus.FAILED, "I need some text for that action."))
            "tap_text" -> if (r.target.isBlank()) return block(blocked(TaskStatus.FAILED, "What should I tap?"))
        }
        if (r.action in needsA11y && ZaraAccessibilityService.instance == null)
            return block(blocked(TaskStatus.BLOCKED_BY_PERMISSION, "Accessibility access isn't enabled.", "Enable Zara's accessibility service in Settings → App Control & Hands-Free."))
        val classKey = when (r.action) {
            "go_back", "go_home", "recent_apps", "scroll_down", "scroll_up" -> "allow_nav"
            "tap_text" -> "allow_tap"; "type_text" -> "allow_type"; else -> ""
        }
        if (classKey.isNotEmpty() && !prefs.b(classKey, true))
            return block(blocked(TaskStatus.BLOCKED_BY_PERMISSION, "That kind of action is switched off in your settings.", "Change it in Settings → App Control & Hands-Free."))

        val risk = when (r.action) {
            "whatsapp_draft", "type_text" -> Risk.CONSEQUENTIAL
            "tap_text" -> if (Commands.RISKY.containsMatchIn(r.target)) Risk.CONSEQUENTIAL else Risk.LOW
            else -> Risk.LOW
        }
        val preview = when (r.action) {
            "open_app" -> "Open the app “${r.target}”."
            "search_youtube" -> "Search YouTube for:\n“${r.text}”\n\nYou choose which result to play."
            "open_settings" -> "Open the Android “${r.target.ifBlank { "general" }}” settings screen. You make any changes yourself."
            "whatsapp_draft" -> (if (r.target.isNotBlank()) "Recipient you mentioned: ${r.target} (you pick the chat yourself).\n" else "") +
                "Prepare a WhatsApp message with this exact text. You choose the recipient and press Send yourself:\n\n“${r.text}”"
            "go_back" -> "Press Back on the app that is currently open."
            "go_home" -> "Go to the Android home screen."
            "recent_apps" -> "Open Android's recent apps."
            "scroll_down", "scroll_up" -> "Scroll the app that is currently open."
            "tap_text" -> "Tap the on-screen control labeled “${r.target}” in the app that is currently open."
            "type_text" -> "Type this exact text into the focused field (password fields are never filled):\n\n“${r.text}”"
            else -> ttl
        }
        return Preflight(risk, preview, ttl, null)
    }

    fun execute(r: ActionRequest, done: (ActionResult) -> Unit) {
        main.post {
            try { run(r, done) } catch (e: Exception) {
                done(ActionResult(TaskStatus.FAILED, "That didn't work on this device.", "Reason: " + e.javaClass.simpleName))
            }
        }
    }

    private fun run(r: ActionRequest, done: (ActionResult) -> Unit) {
        when (r.action) {
            "open_app" -> openApp(r.target, done)
            "search_youtube" -> {
                val uri = Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(r.text, "UTF-8"))
                val yt = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(yt) } catch (_: Exception) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                done(ActionResult(TaskStatus.NEEDS_USER_ACTION, "YouTube search for “${r.text}” is open. I don't pick a video for you — choose one, or tell me which.", "Auto-picking a result isn't supported yet."))
            }
            "open_settings" -> {
                val action = when (r.target) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS; "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS; "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS; "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "apps" -> Settings.ACTION_APPLICATION_SETTINGS; "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                verifyForeground({ it.contains("settings", true) }, "Opened Android settings.", "Settings launch was requested.", done)
            }
            "whatsapp_draft" -> {
                val send = { pkg: String -> Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.text).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                val ok = try { ctx.startActivity(send("com.whatsapp")); true } catch (_: Exception) {
                    try { ctx.startActivity(send("com.whatsapp.w4b")); true } catch (_: Exception) { false }
                }
                if (ok) done(ActionResult(TaskStatus.NEEDS_USER_ACTION, "The draft is ready in WhatsApp. Choose the recipient and press Send yourself — I won't send it.", "Sending is always your final tap."))
                else done(ActionResult(TaskStatus.FAILED, "WhatsApp doesn't appear to be installed."))
            }
            "go_back", "go_home", "recent_apps" -> {
                val svc = ZaraAccessibilityService.instance ?: return done(ActionResult(TaskStatus.BLOCKED_BY_PERMISSION, "Accessibility access isn't enabled."))
                val t0 = SystemClock.uptimeMillis()
                val o = svc.global(r.action)
                if (!o.dispatched) return done(ActionResult(TaskStatus.FAILED, "Android didn't accept that gesture."))
                verifyChange(svc, t0, title(r) + " — done.", title(r) + " was sent.", done)
            }
            "scroll_down", "scroll_up", "tap_text", "type_text" -> {
                val svc = ZaraAccessibilityService.instance ?: return done(ActionResult(TaskStatus.BLOCKED_BY_PERMISSION, "Accessibility access isn't enabled."))
                val t0 = SystemClock.uptimeMillis()
                val o = when (r.action) {
                    "scroll_down" -> svc.scroll(true); "scroll_up" -> svc.scroll(false)
                    "tap_text" -> svc.tapText(r.target); else -> svc.typeText(r.text)
                }
                if (!o.dispatched) {
                    val st = if (o.blocked) TaskStatus.BLOCKED_BY_PERMISSION else if (o.ambiguous) TaskStatus.NEEDS_USER_ACTION else TaskStatus.FAILED
                    return done(ActionResult(st, o.note.ifBlank { "I couldn't do that on this screen." }, "If the screen can't be controlled, please do this step yourself."))
                }
                if (r.action == "type_text") {
                    if (o.note == "verified") done(ActionResult(TaskStatus.COMPLETED, "Typed the text and confirmed it appears in the field."))
                    else done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "I sent the text, but the field didn't confirm it.", "Some apps hide field contents from accessibility."))
                } else verifyChange(svc, t0, title(r) + " — the screen changed as expected.", title(r) + " was sent.", done)
            }
            else -> done(ActionResult(TaskStatus.FAILED, "Unknown action."))
        }
    }

    private fun openApp(name: String, done: (ActionResult) -> Unit) {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
        val q = name.trim().lowercase()
        var hits = apps.filter { it.first.lowercase() == q }
        if (hits.isEmpty()) hits = apps.filter { it.first.lowercase().startsWith(q) }
        if (hits.isEmpty()) hits = apps.filter { it.first.lowercase().contains(q) }
        if (hits.isEmpty()) return done(ActionResult(TaskStatus.FAILED, "I couldn't find an installed app called “$name”."))
        if (hits.size > 1) return done(ActionResult(TaskStatus.NEEDS_USER_ACTION,
            "More than one app matches “$name”: " + hits.take(5).joinToString(", ") { it.first } + ". Which one do you mean?"))
        val (label, pkg) = hits[0]
        val i = pm.getLaunchIntentForPackage(pkg) ?: return done(ActionResult(TaskStatus.FAILED, "$label can't be launched."))
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        verifyForeground({ it == pkg }, "Opened $label and confirmed it's on screen.", "Asked Android to open $label.", done)
    }

    private fun verifyForeground(pred: (String) -> Boolean, ok: String, fallback: String, done: (ActionResult) -> Unit) {
        main.postDelayed({
            val svc = ZaraAccessibilityService.instance
            val fg = svc?.foregroundPackage()
            when {
                svc == null -> done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "$fallback I can't confirm what's on screen because Accessibility access is off.", "Enable it under App Control for verified results."))
                fg != null && pred(fg) -> done(ActionResult(TaskStatus.COMPLETED, ok))
                else -> done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "$fallback I didn't see it come to the front.", "It may still be loading or the app may have blocked it."))
            }
        }, 1600)
    }

    private fun verifyChange(svc: ZaraAccessibilityService, t0: Long, ok: String, fallback: String, done: (ActionResult) -> Unit) {
        main.postDelayed({
            if (svc.lastChange >= t0) done(ActionResult(TaskStatus.COMPLETED, ok))
            else done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "$fallback I didn't observe the screen change.", "The app may not report changes."))
        }, 1100)
    }
}
