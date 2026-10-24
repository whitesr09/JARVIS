package com.nshd.jarvis

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.DateFormat
import java.util.Date

// ============================================================ MEMORY CORE
class MemoryScreen : Screen() {
    override val isSub get() = true
    private var query = ""
    private var cat = ""
    private var listBox: LinearLayout? = null
    private lateinit var ui: Ui
    private val cats = listOf("Personal", "Preferences", "Contacts", "Routines", "Other")

    override fun build(ui: Ui): View {
        this.ui = ui; val t = ui.t; val p = act.prefs
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        col.addView(ui.card(0).apply {
            addView(ui.switchRow("memory", "Use memory", "Lets Zara save things you ask it to remember and use them in replies.", p.b("memory_on", true)) { p.put("memory_on", it) })
        })
        val search = ui.input("Search memories…", query)
        col.addView(search, ui.lpm(tp = 12))
        val chipsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val hs = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chipsRow) }
        fun chips() {
            chipsRow.removeAllViews()
            chipsRow.addView(ui.chip("All", cat.isEmpty()) { cat = ""; chips(); fill() }, ui.lpm(r = 8))
            for (c in cats) chipsRow.addView(ui.chip(c, cat == c) { cat = c; chips(); fill() }, ui.lpm(r = 8))
        }
        col.addView(hs, ui.lpm(tp = 10))
        val lb = ui.vbox(); listBox = lb
        col.addView(lb, ui.lpm(tp = 10))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString().orEmpty(); fill() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        chips(); fill()

        val actions = ui.hbox().apply { setPadding(0, ui.dp(16), 0, 0) }
        actions.addView(ui.button("+ Add") { edit(null) }, ui.lpm(0, -2, r = 8, weight = 1f))
        actions.addView(ui.button("Export", false) { export() }, ui.lpm(0, -2, r = 8, weight = 1f))
        actions.addView(ui.button("Clear all", false, true) {
            act.confirm("Clear all memories?", "This permanently deletes every saved memory. Your chats are not affected.", "Clear memories", { act.db.clearMemories(); fill() }, null)
        }, ui.lp(0, -2, 1f))
        col.addView(actions)
        col.addView(ui.dim("Memories stay on this phone. When memory is on, up to 30 recent ones are included in what's sent to your chosen AI provider. Passwords, keys, OTPs and card details are never saved.").apply { setPadding(ui.dp(6), ui.dp(12), ui.dp(6), 0) })
        return sub(ui, "Memory Core", "Things you've asked Zara to remember", col)
    }

    private fun fill() {
        val b = listBox ?: return
        b.removeAllViews()
        val rows = act.db.memories(query, cat)
        b.addView(ui.tv("SAVED MEMORIES (${rows.size})", 11f, ui.t.accentText, true).apply { letterSpacing = 0.1f; setPadding(ui.dp(4), 0, 0, ui.dp(8)) })
        if (rows.isEmpty()) b.addView(ui.dim(if (query.isNotBlank() || cat.isNotBlank()) "Nothing matches." else "No memories yet. Say “remember that I prefer short answers”, or tap + Add.", 14f).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(30), 0, ui.dp(30)) })
        for (m in rows) {
            val c = ui.card(14)
            c.addView(ui.tv(m.fact, 15f, ui.t.text))
            val meta = ui.hbox().apply { setPadding(0, ui.dp(8), 0, 0) }
            val date = if (m.created > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.created)) else "earlier"
            meta.addView(ui.dim(m.category + " · " + date), ui.lp(0, -2, 1f))
            meta.addView(ui.iconButton("pencil", "Edit memory", 40) { edit(m) }, ui.lpm(ui.dp(40), ui.dp(40), r = 8))
            meta.addView(ui.iconButton("trash", "Forget memory", 40) {
                act.confirm("Forget this?", "“${m.fact}”", "Forget", { act.db.deleteMemory(m.id); fill() }, null)
            })
            c.addView(meta)
            b.addView(c, ui.lpm(tp = 0, b = 10))
        }
    }

    private fun edit(m: Memory?) {
        val box = ui.vbox(8)
        val f = ui.input("What should Zara remember?", m?.fact ?: "", false, true).apply { minLines = 2 }
        box.addView(f)
        var chosen = m?.category ?: "Other"
        val catTv = ui.tv("Category: $chosen (tap to change)", 13f, ui.t.accentText, true).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)); isClickable = true }
        catTv.setOnClickListener { act.chooser("Category", cats.map { c -> c to { chosen = c; catTv.text = "Category: $c (tap to change)" } }) }
        box.addView(catTv)
        act.dialog().setTitle(if (m == null) "Add memory" else "Edit memory").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val fact = f.text.toString().trim()
                when {
                    fact.isEmpty() -> {}
                    Commands.sensitive(fact) -> act.toast("Not saved: passwords, keys, OTPs and card details are never stored.")
                    m == null -> { act.db.addMemory(fact, chosen); fill() }
                    else -> { act.db.updateMemory(m.id, fact, chosen); fill() }
                }
            }.show()
    }

    private fun export() {
        val rows = act.db.memories()
        if (rows.isEmpty()) { act.toast("Nothing to export."); return }
        act.shareText(Brand.APP_NAME + " memories\n\n" + rows.joinToString("\n") { "- [" + it.category + "] " + it.fact })
    }
}

// ============================================================ PERMISSIONS
class PermissionsScreen : Screen() {
    override val isSub get() = true
    override fun build(ui: Ui): View {
        val t = ui.t
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        col.addView(ui.dim("Zara asks for a permission only when you turn on the feature that needs it. Nothing here is requested at startup.").apply { setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(10)) })

        fun item(icon: String, title: String, why: String, ok: Boolean, needsSetup: Boolean, actionLabel: String, onClick: () -> Unit) {
            val c = ui.card(14)
            val st = if (ok) "Available" else if (needsSetup) "Needs setup" else "Not granted"
            val top = ui.hbox()
            top.addView(ui.badge(icon, 44), ui.lpm(ui.dp(44), ui.dp(44), r = 12))
            val tx = ui.vbox()
            tx.addView(ui.tv(title, 15f, t.text, true))
            tx.addView(ui.tv(st, 12f, if (ok) t.ok else t.warn, true).apply { setPadding(0, ui.dp(3), 0, 0) })
            top.addView(tx, ui.lp(0, -2, 1f))
            top.addView(ui.button(if (ok) "Manage" else actionLabel, ok) { onClick() }.apply { minHeight = ui.dp(44); setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)) })
            c.addView(top); c.addView(ui.dim(why).apply { setPadding(0, ui.dp(8), 0, 0) })
            col.addView(c, ui.lpm(b = 10))
        }
        item("mic", "Microphone", "For voice sessions. Used only while a session is running; the app shows when it's on.", act.voice.micGranted(), false, "Grant") {
            if (act.voice.micGranted()) act.openAppSettings() else act.requestPerm(501, Manifest.permission.RECORD_AUDIO)
        }
        item("cloud", "Approximate location", "Optional. Only for the Home weather card. Not collected otherwise.", Weather.hasPermission(act), false, "Grant") {
            if (Weather.hasPermission(act)) act.openAppSettings() else act.requestPerm(502, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        item("smile", "Display over other apps", "Optional. Needed only for the floating avatar.", OverlayService.canDraw(act), true, "Set up") {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)))
        }
        item("apps", "Accessibility service", "Lets Zara tap, scroll and type in other apps when you ask. Enable it yourself in Android Settings.", ZaraAccessibilityService.instance != null, true, "Set up") { act.go(AppControlScreen()) }
        col.addView(ui.button("Open Android app settings", false) { act.openAppSettings() }, ui.lpm(tp = 6))
        col.addView(ui.dim("Not requested at all: contacts, phone calls, camera, SMS, files, or notifications access.").apply { setPadding(ui.dp(6), ui.dp(12), ui.dp(6), 0) })
        return sub(ui, "System Permissions", "What's allowed, and why", col)
    }
    override fun onShow() { }
    override fun onPermission(req: Int, granted: Boolean) { act.rebuildCurrent() }
    override fun onActivityResult(req: Int, res: Int, data: Intent?) { act.rebuildCurrent() }
}

// ============================================================ OVERLAY AVATAR
class OverlayScreen : Screen() {
    override val isSub get() = true
    override fun build(ui: Ui): View {
        val t = ui.t; val p = act.prefs
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val sw = ui.card(0)
        sw.addView(ui.switchRow("smile", "Show floating avatar", "A small bubble you can drag. Tap it to open Zara; double-tap for status.", p.b("overlay_on", false)) { on ->
            if (on) {
                if (!OverlayService.canDraw(act)) {
                    p.put("overlay_on", false)
                    act.confirm("Allow display over other apps?", "Android will open a settings page where you can allow it. The avatar never impersonates system dialogs and you can turn it off here or from its notification.", "Open settings", {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)))
                    }) { act.rebuildCurrent() }
                } else { p.put("overlay_on", true); OverlayService.start(act) }
            } else { p.put("overlay_on", false); OverlayService.stop(act) }
        })
        col.addView(sw)

        col.addView(ui.section("Visibility"))
        val vis = ui.card(16)
        val vr = ui.hbox()
        val working = p.s("overlay_vis", "always") == "working"
        vr.addView(ui.chip("While Zara is working", working) { p.put("overlay_vis", "working"); OverlayService.refresh(); act.rebuildCurrent() }, ui.lpm(r = 8))
        vr.addView(ui.chip("Always on", !working) { p.put("overlay_vis", "always"); OverlayService.refresh(); act.rebuildCurrent() })
        vis.addView(vr)
        val er = ui.hbox().apply { setPadding(0, ui.dp(12), 0, 0) }
        val left = p.s("overlay_edge", "right") == "left"
        er.addView(ui.chip("Dock left", left) { p.put("overlay_edge", "left"); OverlayService.refresh(); act.rebuildCurrent() }, ui.lpm(r = 8))
        er.addView(ui.chip("Dock right", !left) { p.put("overlay_edge", "right"); OverlayService.refresh(); act.rebuildCurrent() })
        vis.addView(er)
        vis.addView(ui.dim("Dragging the avatar snaps it to the nearest edge.").apply { setPadding(0, ui.dp(10), 0, 0) })
        col.addView(vis)
        val tr = ui.card(0)
        tr.addView(ui.labeledSlider("Transparency", { "${it}% opaque" }, 30, 100, p.i("overlay_alpha", 100)) { p.put("overlay_alpha", it); OverlayService.refresh() })
        col.addView(tr, ui.lpm(tp = 10))

        col.addView(ui.section("Choose an avatar"))
        val cur = p.s("avatar", "orb")
        var rowL = ui.hbox(false)
        for ((i, k) in AvatarView.kinds.withIndex()) {
            val cell = ui.vbox(8).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = ui.shape(if (k == cur) Themes.alpha(t.accent, 40) else t.surface, 16f, if (k == cur) t.accent else t.stroke, if (k == cur) 2f else 1f)
                isClickable = true; isFocusable = true; contentDescription = AvatarView.label(k) + (if (k == cur) ", selected" else "")
                setOnClickListener { p.put("avatar", k); OverlayService.refresh(); act.rebuildCurrent() }
                addView(AvatarView(act, k, t.accent), LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)))
                addView(ui.tv(AvatarView.label(k) + (if (k == cur) " ✓" else ""), 12f, t.text, true, Gravity.CENTER).apply { setPadding(0, ui.dp(4), 0, 0) })
            }
            rowL.addView(cell, ui.lpm(0, -2, r = if (i % 3 < 2) 10 else 0, weight = 1f))
            if (i % 3 == 2 || i == AvatarView.kinds.size - 1) {
                if (i % 3 != 2) repeat(2 - i % 3) { rowL.addView(View(act), ui.lpm(0, 1, r = if (it == 0 && i % 3 == 0) 10 else 0, weight = 1f)) }
                col.addView(rowL, ui.lpm(b = 10)); rowL = ui.hbox(false)
            }
        }
        col.addView(ui.dim("The avatar is small and draggable, and is never placed over system dialogs or Android's own controls.").apply { setPadding(ui.dp(6), ui.dp(6), ui.dp(6), 0) })
        return sub(ui, "Overlay Avatar", "Pick the companion shown on screen", col)
    }
    override fun onActivityResult(req: Int, res: Int, data: Intent?) { act.rebuildCurrent() }
}

// ============================================================ APP CONTROL & HANDS-FREE
class AppControlScreen : Screen() {
    override val isSub get() = true
    override fun build(ui: Ui): View {
        val t = ui.t; val p = act.prefs
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val on = ZaraAccessibilityService.instance != null

        val head = ui.card(16)
        head.addView(ui.tv("DEVICE ACTIONS", 11f, t.accentText, true).apply { letterSpacing = 0.1f })
        head.addView(ui.switchRow(null, "Allow Zara to act on my phone", "Master switch. Off = chat only. Every action is checked first, and sending/posting/typing always asks you.", p.b("app_control", false)) { v ->
            if (v) act.confirm("Turn on device actions?", "Zara will be able to open apps, search YouTube, open settings pages, prepare drafts and (with Accessibility) tap, scroll and type — only when you ask, and with confirmation for anything public or consequential.", "Turn on", { p.put("app_control", true); act.rebuildCurrent() }) { act.rebuildCurrent() }
            else p.put("app_control", false)
        }.apply { setPadding(0, ui.dp(6), 0, 0) })
        col.addView(head)

        // ---------- hands-free
        col.addView(ui.section("Hands-free · wake phrase"))
        val phrase = p.s("wake_phrase", "Hey Zara")
        val running = WakeService.running()
        val hf = ui.card(0)
        hf.addView(ui.switchRow("mic", "Wake with “$phrase”", "Zara listens in the background (a notification is always shown) and opens when it hears the phrase.", p.b("wake_on", false)) { v ->
            if (v) act.confirm("Turn on “$phrase”?",
                "This keeps the microphone listening in the background while a notification is shown. Your phone's speech recogniser processes the audio — on some phones that uses Google's servers — and it uses extra battery. Nothing goes to your AI provider until after you say the phrase. You can turn it off any time.",
                "Turn on", { if (act.voice.micGranted()) act.activateWake() else act.requestWake() }) { act.rebuildCurrent() }
            else { p.put("wake_on", false); WakeService.stop(act); act.rebuildCurrent() }
        })
        col.addView(hf)
        val st = ui.card(16)
        st.addView(ui.tv(if (running) "● LISTENING FOR THE PHRASE" else "● OFF", 12f, if (running) t.ok else t.dim, true).apply { letterSpacing = 0.06f })
        fun req(ok: Boolean, name: String, why: String, btn: String?, fn: () -> Unit) {
            val r = ui.hbox().apply { setPadding(0, ui.dp(10), 0, 0) }
            val tx = ui.vbox()
            tx.addView(ui.tv((if (ok) "✓ " else "○ ") + name, 14f, if (ok) t.text else t.warn, true))
            tx.addView(ui.dim(why, 12f).apply { setPadding(0, ui.dp(2), 0, 0) })
            r.addView(tx, ui.lp(0, -2, 1f))
            if (!ok && btn != null) r.addView(ui.button(btn, false) { fn() }.apply { minHeight = ui.dp(40); setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)) })
            st.addView(r)
        }
        req(act.voice.micGranted(), "Microphone", "Required.", "Allow") { act.requestPerm(501, android.Manifest.permission.RECORD_AUDIO) }
        req(OverlayService.canDraw(act), "Display over other apps", "Lets Zara open itself when it hears you. Without it you'll get a tap-to-talk notification instead.", "Allow") {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)))
        }
        val pmgr = act.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        req(pmgr.isIgnoringBatteryOptimizations(act.packageName), "Allowed to run in the background", "Many phones (vivo, Xiaomi, Oppo…) stop background listening unless battery limits are removed for this app.", "Open") {
            try { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { act.openAppSettings() }
        }
        col.addView(st, ui.lpm(tp = 10))

        val hs = ui.card(0)
        val sens = p.i("wake_sens", 1)
        val chips = ui.hbox().apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(4)) }
        for ((i, lb) in listOf("Strict", "Balanced", "Loose").withIndex()) chips.addView(ui.chip(lb, sens == i) { p.put("wake_sens", i); act.rebuildCurrent() }, ui.lpm(r = 8))
        hs.addView(ui.tv("Sensitivity", 14f, t.text, true).apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), 0) })
        hs.addView(chips)
        hs.addView(ui.dim("Strict needs “hey zara” (and close spellings). Balanced also accepts “hey sara”. Loose reacts to just “zara”, so it may trigger on other talk.").apply { setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(12)) })
        hs.addView(ui.divider())
        hs.addView(ui.row("pencil", "Wake phrase", "“$phrase” — change it if another phrase works better on your phone") {
            act.promptText("Wake phrase", "e.g. Hey Zara", phrase) { v -> p.put("wake_phrase", v.take(30)); if (WakeService.running()) { WakeService.stop(act); WakeService.start(act) }; act.rebuildCurrent() }
        })
        hs.addView(ui.divider())
        hs.addView(ui.switchRow(null, "Beep when I'm heard", "A short tone so you know it woke up.", p.b("wake_beep", true)) { p.put("wake_beep", it) })
        col.addView(hs, ui.lpm(tp = 10))
        col.addView(ui.dim("Honest limits: this uses Android's speech recogniser in a loop, not a dedicated low-power wake-word engine. It uses more battery, some phones make a faint beep each time it restarts, it can't hear you while the screen is locked, and it restarts only when you open the app (not after rebooting). A true low-power wake engine is the planned upgrade.").apply { setPadding(ui.dp(6), ui.dp(10), ui.dp(6), 0) })

        col.addView(ui.section("Accessibility service"))
        val a = ui.card(16)
        a.addView(ui.tv(if (on) "● ENABLED" else "● NOT ENABLED", 12f, if (on) t.ok else t.warn, true).apply { letterSpacing = 0.06f })
        a.addView(ui.dim("Needed only for tapping, scrolling, typing and confirming results inside other apps. Opening apps, YouTube searches and settings pages work without it. Zara uses it only to carry out a request you just made — it never records or uploads your screen.").apply { setPadding(0, ui.dp(8), 0, ui.dp(10)) })
        a.addView(ui.button(if (on) "Open Accessibility settings" else "Enable in Android settings") { act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(a)
        val steps = ui.card(16)
        steps.addView(ui.tv("How to enable it", 14f, t.text, true))
        steps.addView(ui.dim("1. Tap the button above.\n2. Open “Installed apps” / “Downloaded apps”, then “${Brand.APP_NAME}”.\n3. Turn the switch on and confirm.\n\nIf Android says “Restricted setting” (common for apps installed from a downloaded APK on Android 13+): open Settings → Apps → ${Brand.APP_NAME}, tap ⋮ (top right) → “Allow restricted settings”, then try again. Android intentionally makes you do this yourself.", 13f).apply { setPadding(0, ui.dp(8), 0, 0) })
        col.addView(steps, ui.lpm(tp = 10))

        col.addView(ui.section("Agent · operate any app"))
        val ag = ui.card(0)
        ag.addView(ui.switchRow("apps", "Agent mode", "Lets the AI read what's on screen and tap, swipe and type in any app, one verified step at a time — not just fixed commands. Needs Accessibility.", p.b("agent_mode", false)) { v ->
            if (v) act.confirm("Turn on Agent mode?", "The AI will be able to read the visible text of your screen (never password fields) and operate apps for you. That screen text is sent to your AI provider. Payment, banking and password apps are protected, and Zara asks before sending, paying or deleting. You can stop any task from the notification.", "Turn on", { p.put("agent_mode", true); act.rebuildCurrent() }) { act.rebuildCurrent() }
            else p.put("agent_mode", false)
        })
        col.addView(ag)
        val au = ui.card(16)
        val level = p.i("autonomy", 2)
        au.addView(ui.tv("How much to ask first", 14f, t.text, true))
        val ar = ui.hbox().apply { setPadding(0, ui.dp(10), 0, 0) }
        for ((i, lb) in listOf("Everything", "Risky things", "Money & delete").withIndex()) ar.addView(ui.chip(lb, level == i + 1) { p.put("autonomy", i + 1); act.rebuildCurrent() }, ui.lpm(r = 8))
        au.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(ar) })
        au.addView(ui.dim(when (level) {
            1 -> "Asks before every action. Safest, slowest."
            3 -> "Only asks before payments, deleting, resetting or signing out. Sending and posting go ahead on their own."
            else -> "Asks before sending, posting, sharing, paying, deleting and similar. Everything else runs on its own."
        }).apply { setPadding(0, ui.dp(10), 0, 0) })
        col.addView(au, ui.lpm(tp = 10))
        val pr = ui.card(0)
        pr.addView(ui.row("shield", "Protected apps", ProtectedApps.list(p).size.toString() + " apps the agent will never read or operate") {
            act.promptText("Protected app package names", "one per line or comma-separated", ProtectedApps.list(p).joinToString(", ")) { v -> p.put("protected_apps", v) ; act.rebuildCurrent() }
        })
        pr.addView(ui.divider())
        pr.addView(ui.switchRow(null, "Instant commands", "Handle simple phrases like “open YouTube” without asking the AI. Off = the AI handles everything and can chain steps.", p.b("quick_commands", !Providers.primary(act, p).configured())) { p.put("quick_commands", it) })
        col.addView(pr, ui.lpm(tp = 10))
        col.addView(ui.dim("Each request gets a budget of ${AgentGuard.MAX_STEPS} steps. “Stop current task” below (or Stop in the notification) halts the agent immediately.").apply { setPadding(ui.dp(6), ui.dp(8), ui.dp(6), 0) })

        col.addView(ui.section("Safety & policy"))
        val pol = ui.card(0)
        pol.addView(ui.switchRow(null, "Allow navigation", "Back, home, recents, scrolling", p.b("allow_nav", true)) { p.put("allow_nav", it) })
        pol.addView(ui.divider())
        pol.addView(ui.switchRow(null, "Allow tapping on-screen controls", "Needs Accessibility", p.b("allow_tap", true)) { p.put("allow_tap", it) })
        pol.addView(ui.divider())
        pol.addView(ui.switchRow(null, "Allow typing into fields", "Needs Accessibility. Password fields are never filled.", p.b("allow_type", true)) { p.put("allow_type", it) })
        col.addView(pol)
        col.addView(ui.dim("Always asks first, no matter what: sending, posting, typing text, deleting, buying, calling, or tapping controls with words like Send, Post, Delete, Pay, Allow. Android permission and install dialogs are never auto-tapped, and OTP, CAPTCHA and lock screens are left to you.").apply { setPadding(ui.dp(6), ui.dp(10), ui.dp(6), 0) })

        col.addView(ui.section("What works today"))
        val ex = ui.card(16)
        ex.addView(ui.dim("• “Open YouTube” / “open Settings” / any installed app by name\n• “Play kesariya on Spotify” / “open Spotify and play lofi” / “play … on YouTube Music” (also any player app by name)\n• “Play iron man trailer on YouTube” — starts the top result (needs Accessibility)\n• “Search YouTube for …”, “google …”, “navigate to …”, “set a timer for 5 minutes”, “set an alarm for 6:30 pm”\n• “Call 98765 43210” / “text 98765 43210 saying …” open the dialer / a drafted message for you to send\n• “Open Bluetooth settings” (also Wi-Fi, display, sound, battery, location, apps)\n• “Send WhatsApp message saying I'm on my way” → draft opens, you choose the chat and press Send\n• With Accessibility: “go back”, “go home”, “scroll down”, “tap Search”, “type hello”\n• Ask in plain language — the AI can request one of these actions, and Zara still checks and confirms it", 13.5f))
        ex.addView(ui.tv("Honest limits", 13f, t.text, true).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
        ex.addView(ui.dim("Some apps hide controls from Accessibility or block automation, so a step may be reported as “could not verify” or handed back to you. Contact-name lookup, placing calls by itself, Instagram posting and screen-sharing vision are not built yet.", 13f))
        col.addView(ex)
        col.addView(ui.button("Stop current task", false, true) { AgentGuard.cancel(); act.brain.stop(); act.toast("Stopped.") }, ui.lpm(tp = 12))
        return sub(ui, "App Control & Hands-Free", "Voice-driven phone actions, with guardrails", col)
    }
    override fun onShow() { }
}

// ============================================================ THEME STUDIO
class ThemeScreen : Screen() {
    override val isSub get() = true
    override fun build(ui: Ui): View {
        val t = ui.t; val p = act.prefs
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val cur = p.s("theme", Brand.DEFAULT_THEME)

        val prev = ui.card(16)
        val o = OrbView(act).apply { styleFrom(t); reduceMotion = p.b("reduce_motion", false) }
        prev.addView(o, ui.lpm(ui.dp(120), ui.dp(120)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val pr = ui.hbox().apply { setPadding(0, ui.dp(10), 0, 0); gravity = Gravity.CENTER }
        pr.addView(ui.statusChip("COMPLETED"), ui.lpm(r = 8)); pr.addView(ui.chip("Sample", true) {}, ui.lpm(r = 8)); pr.addView(ui.button("Button") {})
        prev.addView(pr)
        col.addView(prev)

        col.addView(ui.section("Mode"))
        val modes = ui.card(0)
        val opts = Themes.all.map { it.id to (it.label + " · " + it.blurb) } + listOf("system" to "System · follows Android dark mode")
        for ((i, e) in opts.withIndex()) {
            if (i > 0) modes.addView(ui.divider())
            val rb = RadioButton(act).apply { isChecked = e.first == cur; buttonTintList = android.content.res.ColorStateList.valueOf(t.accent); isClickable = false; contentDescription = e.second }
            modes.addView(ui.row(null, e.second.substringBefore(" · "), e.second.substringAfter(" · "), rb, false) { p.put("theme", e.first); act.applyTheme() })
        }
        col.addView(modes)

        col.addView(ui.section("Accent colour"))
        val ac = ui.card(16)
        val swatches = listOf(0, "#D8BF8E", "#FF6B3D", "#F44B6E", "#8E55E8", "#4A90E2", "#2FB59B", "#E0A94F")
        var row = ui.hbox(false)
        val cs = p.i("accent", 0)
        for ((i, s) in swatches.withIndex()) {
            val colr = if (s is String) android.graphics.Color.parseColor(s) else 0
            val v = FrameLayout(act).apply {
                background = ui.shape(if (colr == 0) t.surface2 else colr, 22f, if (colr == cs) t.text else t.stroke, if (colr == cs) 3f else 1f)
                isClickable = true; isFocusable = true; contentDescription = if (colr == 0) "Theme default accent" else "Accent $s"
                setOnClickListener { p.put("accent", colr); act.applyTheme() }
                if (colr == 0) addView(ui.tv("A", 14f, t.text, true, Gravity.CENTER), FrameLayout.LayoutParams(-1, -1))
            }
            row.addView(v, ui.lpm(ui.dp(44), ui.dp(44), r = 10, b = 10))
            if (i == 3) { ac.addView(row); row = ui.hbox(false) }
        }
        ac.addView(row)
        ac.addView(ui.dim("Text colour is picked automatically so it stays readable on any accent.").apply { setPadding(0, ui.dp(4), 0, 0) })
        col.addView(ac)

        col.addView(ui.section("Orb style"))
        val curStyle = p.s("orb_style", "to:auto")
        var orow = ui.hbox(false)
        for ((i, e) in OrbStyles.all.withIndex()) {
            val sel = e.first == curStyle
            val cell = ui.vbox(8).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = ui.ripple(ui.shape(if (sel) Themes.alpha(t.accent, 36) else t.surface, 16f, if (sel) t.accent else t.stroke, if (sel) 2f else 1f), 16f)
                isClickable = true; isFocusable = true; contentDescription = e.second + (if (sel) ", selected" else "")
                setOnClickListener { p.put("orb_style", e.first); act.rebuildCurrent() }
                val pv = OrbView(act).apply { styleFrom(t); style = e.first; reduceMotion = p.b("reduce_motion", false) }
                addView(pv, LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)))
                addView(ui.tv(e.second + (if (sel) " ✓" else ""), 12.5f, t.text, true, Gravity.CENTER).apply { setPadding(0, ui.dp(2), 0, 0) })
            }
            orow.addView(cell, ui.lpm(0, -2, r = if (i % 2 == 0) 10 else 0, weight = 1f))
            if (i % 2 == 1 || i == OrbStyles.all.size - 1) {
                if (i % 2 == 0) orow.addView(View(act), ui.lpm(0, 1, weight = 1f))
                col.addView(orow, ui.lpm(b = 10)); orow = ui.hbox(false)
            }
        }
        col.addView(ui.section("Motion"))
        val mo = ui.card(0)
        mo.addView(ui.switchRow(null, "Reduce motion", "Shows a still orb. Also automatic when Android battery saver is on.", p.b("reduce_motion", false)) { p.put("reduce_motion", it); o.reduceMotion = it })
        col.addView(mo)
        return sub(ui, "Theme Studio", "Colours, accent and motion", col)
    }
}

// ============================================================ PRIVACY
class PrivacyScreen : Screen() {
    override val isSub get() = true
    override fun build(ui: Ui): View {
        val t = ui.t; val p = act.prefs
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val live = ui.card(16)
        live.addView(ui.tv("RIGHT NOW", 11f, t.accentText, true).apply { letterSpacing = 0.1f })
        live.addView(ui.tv(if (act.listening()) "● Microphone is ON (voice session running)" else "● Microphone is off", 14f, if (act.listening()) t.warn else t.ok, true).apply { setPadding(0, ui.dp(8), 0, 0) })
        live.addView(ui.tv(if (ZaraAccessibilityService.instance != null) "● Accessibility service is enabled" else "● Accessibility service is off", 14f, t.text, true).apply { setPadding(0, ui.dp(6), 0, 0) })
        live.addView(ui.tv("● Screen sharing: not available in this version", 14f, t.dim, true).apply { setPadding(0, ui.dp(6), 0, 0) })
        col.addView(live)

        fun info(title: String, body: String) {
            col.addView(ui.section(title))
            col.addView(ui.card(16).apply { addView(ui.dim(body, 13.5f)) })
        }
        info("Stays on this phone", "Chat history, saved memories, the task journal and your settings are stored in this app's private storage. There is no ${Brand.APP_NAME} server and no account or sign-up. API keys are stored encrypted with the Android Keystore.")
        info("Sent to your AI provider", "When you send a message (typed or spoken-then-recognized), the app sends it to the provider you set as Primary — along with recent messages, your persona text, and up to 30 saved memories if Memory is on. Nothing is sent to any other provider. Screen contents are never sent.")
        info("Speech & wake phrase", "Speech recognition and text-to-speech use your phone's installed Android engines. Depending on your device, the recognizer (often Google's) may process audio on its servers — that's controlled by Android, not by this app. If you turn on the wake phrase, that recogniser runs continuously in the background (a notification is always shown) and nothing is sent to your AI provider until after the phrase.")
        info("Location & weather", "If you allow approximate location, the Home weather card sends rounded coordinates to open-meteo.com when weather loads (at most once every 20 minutes). Nothing else uses location.")
        info("Limits of this app's claims", "Keys stored on a phone can't be made impossible to extract from a rooted or modified device. Voice matching isn't a lock. This app hasn't been independently audited.")

        col.addView(ui.section("Your data"))
        val d = ui.card(0)
        fun act2(icon: String, title: String, sub: String, danger: Boolean, body: String, fn: () -> Unit) {
            d.addView(ui.row(icon, title, sub, null, false) { act.confirm(title + "?", body, "Delete", { fn(); act.toast("Done.") }, null) })
        }
        d.addView(ui.row("share", "Export my data", "Chats, memories and task history as text", null, false) { exportAll() })
        d.addView(ui.divider()); act2("trash", "Clear all chat history", "Memories are kept", true, "Every conversation on this phone will be deleted. Saved memories are not touched.") { act.db.clearAllChats(); act.brain.clearChat() }
        d.addView(ui.divider()); act2("trash", "Clear memories", "Chats are kept", true, "All saved memories will be deleted.") { act.db.clearMemories() }
        d.addView(ui.divider()); act2("trash", "Clear activity history", "Task journal only", true, "The list of past device tasks will be deleted.") { act.db.clearTasks() }
        d.addView(ui.divider()); act2("key", "Delete API keys", "Removes saved keys from this phone", true, "All saved provider keys will be removed. You'll need to add one again to chat with the AI.") { SecureStore.clearAll(act) }
        col.addView(d)
        col.addView(ui.dim("Third-party software and credits: see About & Credits and THIRD_PARTY_NOTICES.md in the repository.").apply { setPadding(ui.dp(6), ui.dp(12), ui.dp(6), 0) })
        return sub(ui, "Privacy & Data Shield", "What stays here and what's sent", col)
    }

    private fun exportAll() {
        val sb = StringBuilder(Brand.APP_NAME + " data export\n\n== Memories ==\n")
        for (m in act.db.memories()) sb.append("- [").append(m.category).append("] ").append(m.fact).append('\n')
        sb.append("\n== Active chat ==\n")
        for ((r, tx) in act.db.messages(act.brain.activeConvo, 100)) sb.append(r).append(": ").append(tx).append("\n\n")
        sb.append("== Recent tasks ==\n")
        for (tk in act.db.tasks(40)) sb.append(tk.status).append(" · ").append(tk.summary).append('\n')
        act.shareText(sb.toString())
    }
}

// ============================================================ ABOUT
class AboutScreen : Screen() {
    override val isSub get() = true
    override fun build(ui: Ui): View {
        val t = ui.t
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val h = ui.card(20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        h.addView(ui.tv(Brand.COMPANION_NAME, 36f, t.accentText, false, Gravity.CENTER).apply { typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL); letterSpacing = 0.14f })
        h.addView(ui.tv(Brand.TAGLINE, 14f, t.text, true, Gravity.CENTER).apply { setPadding(0, ui.dp(8), 0, 0) })
        h.addView(ui.dim("Version " + act.versionName(), 12f).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, 0) })
        col.addView(h)

        col.addView(ui.section("Creator"))
        val c = ui.card(0)
        var first = true
        fun link(icon: String, title: String, sub: String, url: String) { if (!first) c.addView(ui.divider()); first = false; c.addView(ui.row(icon, title, sub, null, true) { act.openLink(url) }) }
        if (Brand.INSTAGRAM.isNotBlank()) link("instagram", "Instagram", "@" + Brand.INSTAGRAM, "https://instagram.com/" + Brand.INSTAGRAM)
        if (Brand.TELEGRAM.isNotBlank()) link("send", "Telegram", "@" + Brand.TELEGRAM, "https://t.me/" + Brand.TELEGRAM)
        if (Brand.WHATSAPP_NUMBER.isNotBlank()) link("chat", "WhatsApp", "Message " + Brand.CREATOR_SHORT, "https://wa.me/" + Brand.WHATSAPP_NUMBER)
        if (Brand.WEBSITE.isNotBlank()) link("globe", "Website", Brand.WEBSITE, Brand.WEBSITE)
        col.addView(c)
        col.addView(ui.dim("Contact rows come from Brand.kt in the source. Blank values are hidden.").apply { setPadding(ui.dp(6), ui.dp(8), ui.dp(6), 0) })

        col.addView(ui.section("Open-source credits & third-party notices"))
        col.addView(ui.card(16).apply {
            addView(ui.dim("${Brand.APP_NAME} is built with the Android SDK and the Kotlin standard library only — no other libraries are bundled.\n\nIdeas that inspired the design (no code or assets copied):\n• Thinking Orbs — github.com/Jakubantalik/thinking-orbs\n• ShaderGradient — github.com/ruucm/shadergradient\n• The “Jarvis” Android tutorial project — github.com/techgptx/Playlist\n\nThe orb, icons and avatars are drawn in code and are original to this app. Weather data: Open-Meteo.com (free API, attribution). Full notices: THIRD_PARTY_NOTICES.md.", 13f))
        })
        return sub(ui, "About & Credits", Brand.TAGLINE, col)
    }
}
