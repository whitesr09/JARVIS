package com.nshd.jarvis

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONObject
import java.net.URLEncoder

enum class TaskStatus { COMPLETED, PARTIALLY_COMPLETED, FAILED, BLOCKED_BY_PERMISSION, NEEDS_USER_ACTION, COULD_NOT_VERIFY }
enum class Risk { LOW, CONSEQUENTIAL }

data class ActionResult(val status: TaskStatus, val message: String, val hint: String = "", val declined: Boolean = false, val data: String = "")

/** Shared kill switch + step budget for agentic runs (one user request ≈ one budget). */
object AgentGuard {
    const val MAX_STEPS = 40
    @Volatile var steps = 0
    @Volatile var cancelled = false
    fun reset() { steps = 0; cancelled = false }
    fun cancel() { cancelled = true }
}

/** Apps the agent never reads or operates (payments, banking, password managers). Editable in Settings. */
object ProtectedApps {
    const val DEFAULT = "com.google.android.apps.nbu.paisa.user,com.phonepe.app,net.one97.paytm,in.org.npci.upiapp,com.sbi.lotusintouch," +
        "com.snapwork.hdfc,com.csam.icici.bank.imobile,com.axis.mobile,com.msf.kbank.mobile,com.x8bit.bitwarden,com.agilebits.onepassword," +
        "com.lastpass.lpandroid,com.kunzisoft.keepass.free,com.android.vending"
    fun list(p: Prefs): Set<String> = p.s("protected_apps", DEFAULT).split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
class Preflight(val risk: Risk, val preview: String, val title: String, val blocked: ActionResult?)

/**
 * Typed action executor. The model (or a local command) can only request actions from [Tools.ACTIONS];
 * each request is validated, policy-checked, optionally confirmed by the user, executed, then verified.
 */
class ActionExecutor(private val ctx: Context, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private val needsA11y = setOf("go_back", "go_home", "recent_apps", "scroll_down", "scroll_up", "tap_text", "type_text",
        "get_screen", "tap_node", "tap_point", "long_press_node", "swipe", "press_key")
    private val needsAgentMode = setOf("get_screen", "tap_node", "tap_point", "long_press_node", "swipe")
    private val touchesScreen = setOf("get_screen", "tap_node", "tap_point", "long_press_node", "swipe", "tap_text", "type_text", "scroll_down", "scroll_up")
    private val readOnly = setOf("get_screen", "wait")
    private val ALWAYS = Regex("(?i)\\b(pay|payment|purchase|buy|order|transfer|send money|delete|remove|uninstall|erase|reset|sign out|log out|logout|factory)\\b")

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // From the background only an accessibility service (or a visible app) may start other apps.
        (if (MainActivity.visible != null) ctx else (ZaraAccessibilityService.instance ?: ctx)).startActivity(i)
    }

    fun fromJson(o: JSONObject?, allowed: Set<String> = Tools.ACTIONS.toSet()): ActionRequest? {
        if (o == null) return null
        val m = HashMap<String, String>()
        for (k in o.keys()) m[k] = o.opt(k)?.toString().orEmpty()
        return ToolArgs.normalize(o.optString("action"), m, allowed)
    }

    private fun blocked(s: TaskStatus, msg: String, hint: String = "") = ActionResult(s, msg, hint)

    private fun mediaLabel(t: String) = when (t.lowercase()) { "spotify" -> "Spotify"; "youtube" -> "YouTube"; "ytmusic" -> "YouTube Music"; else -> t }

    fun title(r: ActionRequest): String = when (r.action) {
        "open_app" -> "Open ${r.target}"
        "play_media" -> "Play on " + mediaLabel(r.target)
        "search_youtube" -> "Search YouTube"
        "web_search" -> "Search the web"
        "get_screen" -> "Read the screen"
        "tap_node" -> "Tap element " + r.extra["id"]
        "tap_point" -> "Tap at " + r.extra["x"] + "," + r.extra["y"]
        "long_press_node" -> "Long-press element " + r.extra["id"]
        "swipe" -> "Swipe"
        "press_key" -> "Press " + (r.extra["key"] ?: r.target)
        "wait" -> "Wait"
        "set_timer" -> "Set a timer"
        "set_alarm" -> "Set an alarm"
        "dial_number" -> "Open the dialer"
        "navigate_to" -> "Navigate"
        "sms_draft" -> "Draft a text message"
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
            "play_media" -> {
                if (r.target.isBlank()) return block(blocked(TaskStatus.NEEDS_USER_ACTION, "Which app should I play it on — Spotify, YouTube, YouTube Music?"))
                if (r.target.lowercase() == "youtube" && r.text.isBlank()) return block(blocked(TaskStatus.NEEDS_USER_ACTION, "What should I play on YouTube?"))
            }
            "set_timer" -> if ((r.text.toIntOrNull() ?: 0) !in 1..86400) return block(blocked(TaskStatus.FAILED, "I need a timer length between 1 second and 24 hours."))
            "set_alarm" -> if (!Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(r.text)) return block(blocked(TaskStatus.FAILED, "I need an alarm time like 06:30."))
            "dial_number" -> if (r.text.count { it.isDigit() } < 5) return block(blocked(TaskStatus.FAILED, "That doesn't look like a phone number."))
            "sms_draft" -> if (r.target.count { it.isDigit() } < 5 || r.text.isBlank()) return block(blocked(TaskStatus.FAILED, "I need a phone number and the message text."))
            "search_youtube", "whatsapp_draft", "type_text", "web_search", "navigate_to" -> if (r.text.isBlank()) return block(blocked(TaskStatus.FAILED, "I need some text for that action."))
            "tap_text" -> if (r.target.isBlank()) return block(blocked(TaskStatus.FAILED, "What should I tap?"))
        }
        if (AgentGuard.cancelled) return block(blocked(TaskStatus.FAILED, "Stopped — you cancelled the task."))
        if (AgentGuard.steps >= AgentGuard.MAX_STEPS) return block(blocked(TaskStatus.FAILED, "I've reached the ${AgentGuard.MAX_STEPS}-step limit for one request, so I stopped.", "Ask me to continue if you want more."))
        if (r.action in needsAgentMode && !prefs.b("agent_mode", false))
            return block(blocked(TaskStatus.BLOCKED_BY_PERMISSION, "Agent mode is off, so I can't look at the screen or tap by position.", "Turn on Agent mode in Settings → App Control & Hands-Free."))
        when (r.action) {
            "tap_node", "long_press_node" -> if (r.extra["id"]?.toIntOrNull() == null) return block(blocked(TaskStatus.FAILED, "I need the element number from get_screen."))
            "tap_point" -> if (r.extra["x"]?.toIntOrNull() == null || r.extra["y"]?.toIntOrNull() == null) return block(blocked(TaskStatus.FAILED, "I need x and y for that tap."))
            "swipe" -> if (listOf("x", "y", "x2", "y2").any { r.extra[it]?.toIntOrNull() == null }) return block(blocked(TaskStatus.FAILED, "I need x, y, x2 and y2 for a swipe."))
            "press_key" -> if ((r.extra["key"] ?: r.target).isBlank()) return block(blocked(TaskStatus.FAILED, "Which key: back, home, recents, notifications or quick_settings?"))
        }
        if (r.action in touchesScreen) {
            val fg = ZaraAccessibilityService.instance?.foregroundPackage()
            if (fg != null && fg in ProtectedApps.list(prefs))
                return block(blocked(TaskStatus.BLOCKED_BY_PERMISSION, "That's a protected app ($fg) — I don't read or operate payment, banking or password apps.", "You can change the list in Settings → App Control & Hands-Free."))
        }
        if (r.action in needsA11y && ZaraAccessibilityService.instance == null)
            return block(blocked(TaskStatus.BLOCKED_BY_PERMISSION, "Accessibility access isn't enabled.", "Enable Zara's accessibility service in Settings → App Control & Hands-Free."))
        val classKey = when (r.action) {
            "go_back", "go_home", "recent_apps", "scroll_down", "scroll_up" -> "allow_nav"
            "tap_text" -> "allow_tap"; "type_text" -> "allow_type"; else -> ""
        }
        if (classKey.isNotEmpty() && !prefs.b(classKey, true))
            return block(blocked(TaskStatus.BLOCKED_BY_PERMISSION, "That kind of action is switched off in your settings.", "Change it in Settings → App Control & Hands-Free."))

        val level = prefs.i("autonomy", 2).coerceIn(1, 3)   // 1 ask for everything · 2 ask for risky/public · 3 only for money/delete
        val svc = ZaraAccessibilityService.instance
        fun tapRisk(label: String) = when {
            level <= 1 -> Risk.CONSEQUENTIAL
            ALWAYS.containsMatchIn(label) -> Risk.CONSEQUENTIAL
            level == 2 && Commands.RISKY.containsMatchIn(label) -> Risk.CONSEQUENTIAL
            else -> Risk.LOW
        }
        val risk = when (r.action) {
            "whatsapp_draft", "sms_draft" -> Risk.CONSEQUENTIAL
            "get_screen", "wait", "press_key" -> Risk.LOW
            "tap_text" -> tapRisk(r.target)
            "tap_node", "long_press_node" -> tapRisk(svc?.nodeLabel(r.extra["id"]?.toIntOrNull() ?: -1).orEmpty())
            "tap_point" -> tapRisk(svc?.labelAt(r.extra["x"]?.toIntOrNull() ?: 0, r.extra["y"]?.toIntOrNull() ?: 0).orEmpty())
            else -> if (level <= 1) Risk.CONSEQUENTIAL else Risk.LOW
        }
        val typed = if (risk == Risk.CONSEQUENTIAL && r.action in setOf("tap_text", "tap_node", "tap_point")) svc?.focusedFieldText().orEmpty() else ""
        val context = if (typed.isNotBlank()) "\n\nText currently typed in the focused field:\n“$typed”" else ""
        val preview = when (r.action) {
            "open_app" -> "Open the app “${r.target}”."
            "play_media" -> "Play " + (if (r.text.isBlank()) "something" else "“${r.text}”") + " on " + mediaLabel(r.target) + "." +
                (if (r.target.lowercase() == "youtube") "\nI'll start the top result (needs Accessibility; otherwise the search is just opened)." else "")
            "web_search" -> "Search the web for:\n“${r.text}”"
            "navigate_to" -> "Open maps for:\n“${r.text}”"
            "set_timer" -> "Ask the Clock app to start a " + (r.text.toIntOrNull() ?: 0).let { s -> if (s % 3600 == 0) "${s / 3600} hour" else if (s % 60 == 0) "${s / 60} minute" else "$s second" } + " timer."
            "set_alarm" -> "Ask the Clock app to set an alarm for ${r.text}."
            "dial_number" -> "Open the phone dialer with ${r.text}. You press call yourself."
            "sms_draft" -> "Prepare a text to ${r.target} with this exact message. You press Send yourself:\n\n“${r.text}”"
            "search_youtube" -> "Search YouTube for:\n“${r.text}”\n\nYou choose which result to play."
            "open_settings" -> "Open the Android “${r.target.ifBlank { "general" }}” settings screen. You make any changes yourself."
            "whatsapp_draft" -> (if (r.target.isNotBlank()) "Recipient you mentioned: ${r.target} (you pick the chat yourself).\n" else "") +
                "Prepare a WhatsApp message with this exact text. You choose the recipient and press Send yourself:\n\n“${r.text}”"
            "get_screen" -> "Read the visible text of the current screen and share it with your AI."
            "tap_node" -> "Tap " + (svc?.nodeLabel(r.extra["id"]?.toIntOrNull() ?: -1)?.let { if (it.isBlank()) "element ${r.extra["id"]}" else "“$it”" } ?: "an element") + " in the app that is currently open."
            "long_press_node" -> "Long-press element ${r.extra["id"]} in the app that is currently open."
            "tap_point" -> "Tap the screen at ${r.extra["x"]}, ${r.extra["y"]}" + (svc?.labelAt(r.extra["x"]?.toIntOrNull() ?: 0, r.extra["y"]?.toIntOrNull() ?: 0)?.takeIf { it.isNotBlank() }?.let { " (“$it”)" } ?: "") + "."
            "swipe" -> "Swipe from ${r.extra["x"]},${r.extra["y"]} to ${r.extra["x2"]},${r.extra["y2"]}."
            "press_key" -> "Press the ${r.extra["key"] ?: r.target} key."
            "wait" -> "Wait a moment."
            "go_back" -> "Press Back on the app that is currently open."
            "go_home" -> "Go to the Android home screen."
            "recent_apps" -> "Open Android's recent apps."
            "scroll_down", "scroll_up" -> "Scroll the app that is currently open."
            "tap_text" -> "Tap the on-screen control labeled “${r.target}” in the app that is currently open."
            "type_text" -> "Type this exact text into the focused field (password fields are never filled):\n\n“${r.text}”"
            else -> ttl
        }
        return Preflight(risk, preview + context, ttl, null)
    }

    private val observeActions = setOf("open_app", "play_media", "search_youtube", "open_settings", "go_back", "go_home", "recent_apps",
        "scroll_down", "scroll_up", "tap_text", "type_text", "tap_node", "tap_point", "long_press_node", "swipe", "press_key", "web_search", "navigate_to")

    /** Act-and-observe: when Agent mode is on, the result already contains the new screen, saving a whole model round trip. */
    private fun observe(r: ActionRequest, res: ActionResult, done: (ActionResult) -> Unit) {
        val svc = ZaraAccessibilityService.instance
        if (r.action !in observeActions || svc == null || !prefs.b("agent_mode", false) || res.data.isNotBlank() ||
            res.status == TaskStatus.FAILED || res.status == TaskStatus.BLOCKED_BY_PERMISSION || r.extra["observe"] == "false") return done(res)
        val fg = svc.foregroundPackage()
        if (fg != null && fg in ProtectedApps.list(prefs)) return done(res.copy(data = "(a protected app is on screen — its contents are not shared)"))
        settle(svc, 0L, 140, 700) {
            val (pkg, text) = svc.snapshot(60)
            done(res.copy(data = "app: $pkg\n$text"))
        }
    }

    fun execute(r: ActionRequest, done: (ActionResult) -> Unit) {
        main.post {
            try { run(r) { res -> observe(r, res, done) } } catch (e: Exception) {
                done(ActionResult(TaskStatus.FAILED, "That didn't work on this device.", "Reason: " + e.javaClass.simpleName))
            }
        }
    }

    private fun run(r: ActionRequest, done: (ActionResult) -> Unit) {
        when (r.action) {
            "open_app" -> openApp(r.target, done)
            "play_media" -> playMedia(r.target, r.text, done)
            "web_search" -> {
                launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, r.text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                verifyForeground({ it != ctx.packageName }, "Opened a web search for “${r.text}”.", "Asked Android to search the web for “${r.text}”.", done)
            }
            "navigate_to" -> {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + URLEncoder.encode(r.text, "UTF-8"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                verifyForeground({ it != ctx.packageName }, "Opened maps for “${r.text}”.", "Asked Android to open maps for “${r.text}”.", done)
            }
            "set_timer" -> {
                val secs = r.text.toInt()
                launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "Zara").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "I asked the Clock app to start the timer. I can't read the Clock app, so please glance at it to confirm.", "Alarm/timer apps don't report back."))
            }
            "set_alarm" -> {
                val (h, m) = r.text.split(":").map { it.toInt() }
                launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "Zara").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "I asked the Clock app to set an alarm for ${r.text}. Please check the Clock app to confirm.", "Alarm apps don't report back."))
            }
            "dial_number" -> {
                launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(r.text))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                done(ActionResult(TaskStatus.NEEDS_USER_ACTION, "The dialer is open with ${r.text}. Press call yourself — I never place calls."))
            }
            "sms_draft" -> {
                launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(r.target))).putExtra("sms_body", r.text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                done(ActionResult(TaskStatus.NEEDS_USER_ACTION, "The message to ${r.target} is drafted in your messaging app. Review it and press Send yourself."))
            }
            "search_youtube" -> {
                val uri = Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(r.text, "UTF-8"))
                val yt = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { launch(yt) } catch (_: Exception) {
                    launch(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                launch(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                verifyForeground({ it.contains("settings", true) }, "Opened Android settings.", "Settings launch was requested.", done)
            }
            "whatsapp_draft" -> {
                val send = { pkg: String -> Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.text).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                val ok = try { launch(send("com.whatsapp")); true } catch (_: Exception) {
                    try { launch(send("com.whatsapp.w4b")); true } catch (_: Exception) { false }
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
            "get_screen" -> {
                val svc = ZaraAccessibilityService.instance ?: return done(ActionResult(TaskStatus.BLOCKED_BY_PERMISSION, "Accessibility access isn't enabled."))
                val (pkg, text) = svc.snapshot()
                done(ActionResult(TaskStatus.COMPLETED, "Screen read.", data = "app: $pkg\n$text"))
            }
            "wait" -> main.postDelayed({ done(ActionResult(TaskStatus.COMPLETED, "Waited.")) }, (r.extra["duration_ms"]?.toLongOrNull() ?: 600L).coerceIn(150L, 5000L))
            "press_key" -> {
                val svc = ZaraAccessibilityService.instance ?: return done(ActionResult(TaskStatus.BLOCKED_BY_PERMISSION, "Accessibility access isn't enabled."))
                val t0 = SystemClock.uptimeMillis()
                val o = svc.pressKey(r.extra["key"] ?: r.target)
                if (!o.dispatched) done(ActionResult(TaskStatus.FAILED, o.note.ifBlank { "Android didn't accept that key." }))
                else verifyChange(svc, t0, "Pressed ${r.extra["key"] ?: r.target}.", "Sent the ${r.extra["key"] ?: r.target} key.", done)
            }
            "tap_node", "tap_point", "long_press_node", "swipe" -> {
                val svc = ZaraAccessibilityService.instance ?: return done(ActionResult(TaskStatus.BLOCKED_BY_PERMISSION, "Accessibility access isn't enabled."))
                val t0 = SystemClock.uptimeMillis()
                val cb: (A11yOutcome) -> Unit = { o ->
                    if (!o.dispatched) done(ActionResult(TaskStatus.FAILED, o.note.ifBlank { "That didn't work on this screen." }, "Call get_screen again and retry, or ask the user."))
                    else verifyChange(svc, t0, title(r) + " — the screen changed.", title(r) + " was sent.", done)
                }
                val e = r.extra
                when (r.action) {
                    "tap_node" -> svc.tapNode(e["id"]!!.toInt(), cb)
                    "long_press_node" -> svc.longPressNode(e["id"]!!.toInt(), cb)
                    "tap_point" -> svc.tapPoint(e["x"]!!.toInt(), e["y"]!!.toInt(), 60, cb)
                    else -> svc.swipe(e["x"]!!.toInt(), e["y"]!!.toInt(), e["x2"]!!.toInt(), e["y2"]!!.toInt(), (e["duration_ms"]?.toLongOrNull() ?: 350L), cb)
                }
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

    private fun findApps(name: String): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
        val q = name.trim().lowercase()
        var hits = apps.filter { it.first.lowercase() == q }
        if (hits.isEmpty()) hits = apps.filter { it.first.lowercase().startsWith(q) }
        if (hits.isEmpty()) hits = apps.filter { it.first.lowercase().contains(q) }
        return hits
    }

    private fun installed(pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun playMedia(app: String, query: String, done: (ActionResult) -> Unit) {
        when (app.lowercase()) {
            "youtube" -> playYoutube(query, done)
            "spotify" -> mediaSearch("com.spotify.music", "Spotify", query, done)
            "ytmusic" -> mediaSearch("com.google.android.apps.youtube.music", "YouTube Music", query, done)
            else -> {
                val hits = findApps(app)
                when {
                    hits.isEmpty() -> done(ActionResult(TaskStatus.FAILED, "I couldn't find an installed app called “$app”."))
                    hits.size > 1 -> done(ActionResult(TaskStatus.NEEDS_USER_ACTION, "More than one app matches “$app”: " + hits.take(5).joinToString(", ") { it.first } + ". Which one?"))
                    else -> mediaSearch(hits[0].second, hits[0].first, query, done)
                }
            }
        }
    }

    /** Standard Android "play from search" request. Spotify, YouTube Music and many players handle it. */
    private fun mediaSearch(pkg: String, label: String, query: String, done: (ActionResult) -> Unit) {
        if (!installed(pkg)) return done(ActionResult(TaskStatus.FAILED, "$label isn't installed on this phone."))
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").putExtra(SearchManager.QUERY, query)
        try { launch(i) } catch (_: Exception) {
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { launch(it) }
            return done(ActionResult(TaskStatus.NEEDS_USER_ACTION, "$label opened, but it doesn't accept play requests from other apps. Search for " + (if (query.isBlank()) "something" else "“$query”") + " there yourself."))
        }
        val what = if (query.isBlank()) "music" else "“$query”"
        val svc = ZaraAccessibilityService.instance
            ?: return done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "I asked $label to play $what. I can't confirm playback because Accessibility is off.", "Enable it under App Control for verified results."))
        val start = SystemClock.uptimeMillis()
        fun poll() {
            if (svc.foregroundPackage() == pkg && svc.hasText(listOf("Pause"))) return done(ActionResult(TaskStatus.COMPLETED, "Playing $what in $label."))
            if (SystemClock.uptimeMillis() - start >= 3200) return done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "I asked $label to play $what, but I couldn't see it playing yet.", "It may still be loading, need you to sign in, or have shown a prompt — look at the screen."))
            main.postDelayed({ poll() }, 250)
        }
        main.postDelayed({ poll() }, 350)
    }

    /** YouTube has no play-from-search hook, so: open results, then (with Accessibility) tap the top real result as soon as it appears. */
    private fun playYoutube(query: String, done: (ActionResult) -> Unit) {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
        val yt = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { launch(yt) } catch (_: Exception) { launch(Intent(Intent.ACTION_VIEW, uri)) }
        val svc = ZaraAccessibilityService.instance
            ?: return done(ActionResult(TaskStatus.NEEDS_USER_ACTION, "YouTube search for “$query” is open. Turn on Accessibility (Settings → App Control) and I'll start the top result for you; for now, tap a video.", "Starting a video needs Accessibility."))
        var tries = 0
        val skip = Regex("(?i)sponsored|advertisement|\\bad\\b\\s*[·•-]")
        fun verify(since: Long) {
            if (svc.hasText(listOf("Minimize", "Pause video", "Collapse", "Pause"))) return done(ActionResult(TaskStatus.COMPLETED, "Playing the top YouTube result for “$query”."))
            if (SystemClock.uptimeMillis() - since >= 2400) return done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "I tapped the top result for “$query”, but I couldn't confirm it's playing.", "YouTube may still be loading or showing a prompt."))
            main.postDelayed({ verify(since) }, 200)
        }
        fun attempt() {
            val o = svc.clickFirstMatching(Regex("(?i)play video|play short"), skip)
            if (o.dispatched) main.postDelayed({ verify(SystemClock.uptimeMillis()) }, 300)
            else if (++tries < 28) main.postDelayed({ attempt() }, 250)
            else done(ActionResult(TaskStatus.NEEDS_USER_ACTION, "I opened the results for “$query” but couldn't find a video to tap. Please pick one.", o.note))
        }
        main.postDelayed({ attempt() }, 450)
    }

    private fun openApp(name: String, done: (ActionResult) -> Unit) {
        val pm = ctx.packageManager
        val hits = findApps(name)
        if (hits.isEmpty()) return done(ActionResult(TaskStatus.FAILED, "I couldn't find an installed app called “$name”."))
        if (hits.size > 1) return done(ActionResult(TaskStatus.NEEDS_USER_ACTION,
            "More than one app matches “$name”: " + hits.take(5).joinToString(", ") { it.first } + ". Which one do you mean?"))
        val (label, pkg) = hits[0]
        val i = pm.getLaunchIntentForPackage(pkg) ?: return done(ActionResult(TaskStatus.FAILED, "$label can't be launched."))
        launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        verifyForeground({ it == pkg }, "Opened $label and confirmed it's on screen.", "Asked Android to open $label.", done)
    }

    /** Waits until the screen changed after [t0] and has been quiet for [quietMs] — or [maxMs] passed. Never a fixed sleep. */
    private fun settle(svc: ZaraAccessibilityService?, t0: Long, quietMs: Long = 160, maxMs: Long = 900, then: (changed: Boolean) -> Unit) {
        if (svc == null) return then(false)
        val start = SystemClock.uptimeMillis()
        fun poll() {
            val now = SystemClock.uptimeMillis()
            val changed = svc.lastChange >= t0
            if ((changed && now - svc.lastChange >= quietMs) || now - start >= maxMs) then(changed) else main.postDelayed({ poll() }, 40)
        }
        main.postDelayed({ poll() }, 40)
    }

    /** Continues the moment [pred] holds for the foreground app (polled every 80 ms), or gives up after 3 s. */
    private fun verifyForeground(pred: (String) -> Boolean, ok: String, fallback: String, done: (ActionResult) -> Unit) {
        val svc = ZaraAccessibilityService.instance
            ?: return done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "$fallback I can't confirm what's on screen because Accessibility access is off.", "Enable it under App Control for verified results."))
        val start = SystemClock.uptimeMillis()
        fun poll() {
            val fg = svc.foregroundPackage()
            if (fg != null && pred(fg)) return done(ActionResult(TaskStatus.COMPLETED, ok))
            if (SystemClock.uptimeMillis() - start >= 3000) return done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "$fallback I didn't see it come to the front.", "It may still be loading or the app may have blocked it."))
            main.postDelayed({ poll() }, 80)
        }
        main.postDelayed({ poll() }, 80)
    }

    private fun verifyChange(svc: ZaraAccessibilityService, t0: Long, ok: String, fallback: String, done: (ActionResult) -> Unit) {
        settle(svc, t0) { changed ->
            if (changed) done(ActionResult(TaskStatus.COMPLETED, ok))
            else done(ActionResult(TaskStatus.COULD_NOT_VERIFY, "$fallback I didn't observe the screen change.", "The app may not report changes."))
        }
    }
}
