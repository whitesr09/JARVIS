package com.nshd.jarvis

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.DateFormat
import java.util.Date

abstract class Screen {
    lateinit var act: MainActivity
    open val isSub: Boolean get() = false
    abstract fun build(ui: Ui): View
    open fun onShow() {}
    open fun onHide() {}
    open fun onActivityResult(req: Int, res: Int, data: Intent?) {}
    open fun onPermission(req: Int, granted: Boolean) {}
    protected fun sub(ui: Ui, title: String, subtitle: String?, content: View): View {
        val root = ui.vbox()
        root.addView(ui.topBar(title, subtitle) { act.back() })
        root.addView(ui.scroll(content), ui.lp(-1, 0, 1f))
        return root
    }
}

// ============================================================ HOME
class HomeScreen : Screen(), BrainListener {
    private var orb: OrbView? = null
    private var pill: TextView? = null
    private var dot: View? = null
    private var sub: TextView? = null
    private var weatherHolder: LinearLayout? = null
    private lateinit var ui: Ui

    override fun build(ui: Ui): View {
        this.ui = ui; val t = ui.t
        val col = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24)) }

        val header = ui.hbox()
        header.addView(ui.iconButton("chat", "Open chat") { act.tab(1) })
        val mid = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        mid.addView(ui.tv(Brand.COMPANION_NAME, 34f, t.accentText, false, Gravity.CENTER).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); letterSpacing = 0.14f
        })
        val who = Persona.userName(act.prefs)
        mid.addView(ui.dim(if (who.isBlank()) "Welcome back" else "Welcome back, $who", 13f).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, 0) })
        mid.addView(ui.tv("by " + Brand.CREATOR_SHORT, 10f, t.dim, false, Gravity.CENTER).apply { letterSpacing = 0.2f; setPadding(0, ui.dp(3), 0, 0) })
        header.addView(mid, ui.lp(0, -2, 1f))
        header.addView(ui.iconButton("gear", "Open settings") { act.tab(2) })
        col.addView(header)

        val o = OrbView(act).apply {
            styleFrom(t)
            style = act.prefs.s("orb_style", "to:auto")
            reduceMotion = act.prefs.b("reduce_motion", false); state = act.brain.orb
            isClickable = true; isFocusable = true
            contentDescription = "Zara orb. Tap to start or stop listening."
            setOnClickListener { act.toggleVoice() }
        }
        orb = o
        val side = minOf(act.resources.displayMetrics.widthPixels - ui.dp(48), ui.dp(330))
        col.addView(o, ui.lpm(side, side, tp = 8).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val p = TextView(act).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f
            gravity = Gravity.CENTER; minHeight = ui.dp(48); setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(10))
            background = ui.shape(t.surface, 26f, t.stroke); setTextColor(t.dim)
            isClickable = true; isFocusable = true
            setOnClickListener { act.toggleVoice() }
        }
        pill = p
        col.addView(p, ui.lpm(-2, -2, tp = 4).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val s = ui.dim("", 12f).apply { gravity = Gravity.CENTER }
        sub = s
        col.addView(s, ui.lpm(tp = 8))

        val wh = ui.vbox(); weatherHolder = wh
        col.addView(wh, ui.lpm(tp = 18))
        buildWeather()

        // quick actions: icon + title + one-line subtitle
        val memCount = act.db.memories().size
        val tiles = listOf(
            Triple("chat" to "Chat", "Talk or type") { act.tab(1) },
            Triple("apps" to "Open app", "Launch by name") { openAppDialog() },
            Triple("memory" to "Memories", "$memCount saved") { act.go(MemoryScreen()) },
            Triple("code" to "Code", "Write & save files") { act.go(CodeScreen()) },
            Triple("tasks" to "Tasks", "Verified activity log") { act.tab(3) },
            Triple("globe" to "Language", Langs.current(act.prefs).label.substringBefore(" (")) { act.go(LanguageScreen()) })
        for (r in 0 until 3) {
            val row = ui.hbox(false)
            for (c in 0 until 2) {
                val (head, sub, fn) = tiles[r * 2 + c]
                val tile = ui.hbox().apply {
                    setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); minimumHeight = ui.dp(68)
                    background = ui.ripple(ui.shape(t.surface, 18f, t.stroke), 18f)
                    isClickable = true; isFocusable = true; setOnClickListener { fn() }
                    contentDescription = head.second + ", " + sub
                    addView(ui.icon(head.first, 26), ui.lpm(ui.dp(26), ui.dp(26), r = 12))
                    val tx = ui.vbox()
                    tx.addView(ui.tv(head.second, 14f, t.text, true))
                    tx.addView(ui.dim(sub, 11.5f).apply { setPadding(0, ui.dp(2), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(tx, ui.lp(0, -2, 1f))
                }
                row.addView(tile, ui.lpm(0, -2, r = if (c == 0) 10 else 0, weight = 1f))
            }
            col.addView(row, ui.lpm(tp = 10))
        }
        updateState()
        return ui.scroll(col, 0)
    }

    private fun openAppDialog() {
        act.promptText("Open an app", "App name, e.g. YouTube", "") { name ->
            act.tab(1); act.brain.submit("open $name")
        }
    }

    private fun buildWeather() {
        val h = weatherHolder ?: return
        h.removeAllViews()
        val card = ui.card(16)
        if (!Weather.hasPermission(act)) {
            card.addView(ui.row("cloud", "Local weather", "Tap to allow approximate location. It's sent to open-meteo.com only when weather loads.", null, true) {
                act.requestPerm(502, Manifest.permission.ACCESS_COARSE_LOCATION)
            }.apply { setPadding(0, 0, 0, 0) })
            h.addView(card); return
        }
        val cached = Weather.cached
        if (cached != null) { fillWeather(card, cached); h.addView(card) }
        else { card.addView(ui.dim("Loading weather…")); h.addView(card) }
        Weather.load(act, false) { w, err ->
            if (weatherHolder !== h) return@load
            val c2 = ui.card(16)
            if (w != null) fillWeather(c2, w) else c2.addView(ui.row("cloud", "Weather unavailable", err, null, true) { Weather.load(act, true) { _, _ -> buildWeather() }; buildWeather() }.apply { setPadding(0, 0, 0, 0) })
            h.removeAllViews(); h.addView(c2)
        }
    }

    private fun fillWeather(card: LinearLayout, w: WeatherInfo) {
        val r = ui.hbox()
        r.addView(ui.tv("${w.tempC}°C", 32f, ui.t.text, true), ui.lpm(r = 16))
        val c = ui.vbox()
        c.addView(ui.tv(w.summary + (if (w.place.isNotBlank()) " · " + w.place else ""), 15f, ui.t.text, true))
        c.addView(ui.dim("Feels ${w.feelsC}° · H ${w.hiC}° L ${w.loC}° · ${w.humidity}% humidity").apply { setPadding(0, ui.dp(3), 0, 0) })
        r.addView(c, ui.lp(0, -2, 1f))
        r.addView(ui.icon("cloud", 34, ui.t.accent), ui.lpm(ui.dp(34), ui.dp(34), l = 8))
        card.addView(r)
    }

    private fun hint() = if (WakeService.running()) "Say “" + act.prefs.s("wake_phrase", "Hey Zara") + "” or tap the orb" else "Tap mic or orb to power on"

    private fun updateState() {
        val o = orb ?: return
        o.state = act.brain.orb
        val v = act.voice
        val txt: String
        when (act.brain.orb) {
            OrbState.ERROR -> txt = "Needs attention"
            OrbState.EXECUTING -> txt = if (act.liveOn() && LiveBus.note.isNotBlank()) LiveBus.note.take(34) else "Working on it…"
            OrbState.THINKING -> txt = "Thinking…"
            OrbState.SPEAKING -> txt = "Speaking · tap to interrupt"
            OrbState.LISTENING -> txt = if (v.state == ZVoice.VState.HEARING) "Hearing you…" else "Listening…"
            OrbState.CONNECTED -> txt = "Connected"
            OrbState.IDLE -> txt = if (act.listening()) "Ready · say something" else "ZARA OFF · TAP TO START"
        }
        pill?.text = txt.uppercase()
        pill?.setTextColor(if (act.brain.orb == OrbState.ERROR) ui.t.danger else if (act.listening()) ui.t.accentText else ui.t.dim)
        val prov = Providers.primary(act, act.prefs)
        sub?.text = when {
            act.brain.orb == OrbState.ERROR -> act.brain.lastNote
            act.partial.isNotBlank() && act.listening() && v.state != ZVoice.VState.IDLE -> "“" + act.partial + "”"
            act.liveOn() -> "Live conversation is on — just talk, interrupt any time · tap the orb to stop"
            act.listening() -> "Microphone is ON for this session · tap the orb to stop"
            !prov.configured() -> hint() + "\nNo AI key yet · Settings → AI Connection"
            else -> hint() + "\n" + prov.label + " · " + prov.modelName
        }
    }

    override fun stateChanged() { updateState() }
    override fun levelChanged(level: Float) { orb?.level = level }
    override fun onShow() { act.brain.listeners.add(this); updateState() }
    override fun onHide() { act.brain.listeners.remove(this) }
    override fun onPermission(req: Int, granted: Boolean) { if (req == 502) { if (granted) Weather.load(act, true) { _, _ -> buildWeather() }; buildWeather() } }
}

// ============================================================ CHAT
class ChatScreen : Screen(), BrainListener {
    private lateinit var ui: Ui
    private var list: LinearLayout? = null
    private var scroller: ScrollView? = null
    private var lastTv: TextView? = null
    private var input: EditText? = null
    private var sendBtn: FrameLayout? = null
    private var subtitle: TextView? = null
    private var codeChip: TextView? = null
    private var zaraChip: TextView? = null

    override fun build(ui: Ui): View {
        this.ui = ui; val t = ui.t
        val root = ui.vbox()
        val head = ui.hbox().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(8)) }
        head.addView(ui.iconButton("menu", "Conversations") { showHistory() }, ui.lpm(ui.dp(44), ui.dp(44), r = 12))
        val mid = ui.vbox()
        mid.addView(ui.tv("Chat", 20f, t.text, true))
        subtitle = ui.dim("", 12f).apply { setPadding(0, ui.dp(2), 0, 0) }
        mid.addView(subtitle)
        head.addView(mid, ui.lp(0, -2, 1f))
        head.addView(ui.iconButton("plus", "New chat") { act.brain.newChat() })
        root.addView(head)

        val l = ui.vbox().apply { setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(10)) }
        list = l
        val sc = ScrollView(act).apply { addView(l); overScrollMode = View.OVER_SCROLL_NEVER }
        scroller = sc
        root.addView(sc, ui.lp(-1, 0, 1f))

        val composer = ui.vbox(12).apply { background = ui.shape(t.surface, 22f, t.stroke) }
        val et = EditText(act).apply {
            hint = "Message ${Persona.name(act.prefs)}"; setHintTextColor(t.dim); setTextColor(t.text)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15.5f); background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1; maxLines = 6; setText(act.brain.draft); setSelection(text.length)
            setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { act.brain.draft = s?.toString().orEmpty() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        input = et
        composer.addView(et, ui.lp())
        val row = ui.hbox().apply { setPadding(0, ui.dp(6), 0, 0) }
        zaraChip = ui.chip(Persona.name(act.prefs), !act.brain.codeMode) { act.brain.codeMode = false; refreshChips() }
        codeChip = ui.chip("Code", act.brain.codeMode) { act.brain.codeMode = true; refreshChips() }
        row.addView(zaraChip, ui.lpm(r = 8)); row.addView(codeChip)
        row.addView(View(act), ui.lp(0, 1, 1f))
        val send = FrameLayout(act).apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); isClickable = true; isFocusable = true
            setOnClickListener { onSend() }
        }
        sendBtn = send
        row.addView(send)
        composer.addView(row)
        root.addView(composer, ui.lpm(l = 14, tp = 4, r = 14, b = 10))
        renderAll(); updateHeader(); updateSend()
        return root
    }

    private fun refreshChips() { act.rebuildCurrent() }

    private fun onSend() {
        if (act.brain.busy) { act.brain.stop(); return }
        val text = input?.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        input?.setText(""); act.brain.draft = ""
        act.brain.submit(text)
    }

    private fun updateSend() {
        val s = sendBtn ?: return
        val busy = act.brain.busy
        s.background = ui.shape(if (busy) ui.t.danger else ui.t.accent, 24f)
        s.removeAllViews()
        val fg = Themes.bestOn(if (busy) ui.t.danger else ui.t.accent)
        s.addView(IconView(act, if (busy) "stop" else "send", fg), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
        s.contentDescription = if (busy) "Stop generating" else "Send message"
    }

    private fun updateHeader() {
        val p = Providers.primary(act, act.prefs)
        subtitle?.text = if (p.configured()) p.label + " · " + p.modelName else "AI not connected · open Settings"
    }

    private fun renderAll() {
        val l = list ?: return
        l.removeAllViews(); lastTv = null
        val msgs = act.brain.msgs
        if (msgs.isEmpty()) {
            l.addView(ui.tv("Hi, I'm " + Persona.name(act.prefs), 22f, ui.t.text, true, Gravity.CENTER).apply { setPadding(0, ui.dp(56), 0, ui.dp(6)) }, ui.lp())
            l.addView(ui.dim("Ask anything, or try one of these:", 14f).apply { gravity = Gravity.CENTER }, ui.lp())
            for (s in listOf("Open YouTube", "Remember that I prefer short answers", "Explain how recursion works")) {
                l.addView(ui.chip(s, false) { input?.setText(s); input?.setSelection(s.length); input?.requestFocus() }, ui.lpm(-2, -2, tp = 10).apply { gravity = Gravity.CENTER_HORIZONTAL })
            }
        }
        for (m in msgs.takeLast(80)) l.addView(bubble(m), ui.lpm(-2, -2, tp = 10).apply { gravity = if (m.role == "user") Gravity.END else Gravity.START })
        scrollDown()
    }

    private fun bubble(m: UiMsg): View {
        val t = ui.t
        val maxW = (act.resources.displayMetrics.widthPixels * 0.84f).toInt()
        return when (m.role) {
            "user" -> ui.tv(m.text, 15.5f, t.onAccent).apply {
                background = ui.shape(t.accent, 20f); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); maxWidth = maxW
                setLineSpacing(0f, 1.1f); setOnLongClickListener { options(m); true }
            }
            "assistant" -> {
                val box = ui.vbox(12).apply { background = ui.shape(t.surface, 20f, t.stroke) }
                if (m.meta.isNotBlank()) box.addView(ui.tv(m.meta, 10.5f, t.dim, true).apply { letterSpacing = 0.05f; setPadding(0, 0, 0, ui.dp(4)) })
                val tv = ui.tv(Md.render(m.text.ifEmpty { "…" }, t), 15.5f, t.text).apply { setLineSpacing(0f, 1.12f) }
                if (m.streaming) lastTv = tv
                box.addView(tv)
                tv.maxWidth = maxW - ui.dp(24)
                box.setOnLongClickListener { options(m); true }
                tv.setOnLongClickListener { options(m); true }
                box
            }
            "task" -> {
                val box = ui.vbox(12).apply { background = ui.shape(t.surface, 16f, t.stroke) }
                val top = ui.hbox()
                top.addView(ui.tv(m.meta, 13f, t.text, true), ui.lp(0, -2, 1f)); top.addView(ui.statusChip(m.status))
                box.addView(top)
                box.addView(ui.dim(m.text, 13f).apply { setPadding(0, ui.dp(6), 0, 0) })
                FrameLayout(act).apply { addView(box, FrameLayout.LayoutParams(maxW, -2)) }
            }
            else -> ui.tv(m.text, 14f, t.danger).apply {
                background = ui.shape(android.graphics.Color.TRANSPARENT, 16f, t.danger, 1.2f); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); maxWidth = maxW
            }
        }
    }

    private fun options(m: UiMsg) {
        val code = Md.extractCode(m.text)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Copy" to { act.copyText(m.text) })
        items.add("Share" to { act.shareText(m.text) })
        if (m.role == "user") items.add("Send again" to { act.brain.submit(m.text) })
        if (code.isNotEmpty()) items.add("Open code in Code Workspace" to { act.openInCode(code[0].first, code[0].second) })
        act.chooser("Message", items)
    }

    private fun showHistory() {
        val ui = act.ui
        val box = ui.vbox(8)
        val search = ui.input("Search conversations")
        box.addView(search)
        val results = ui.vbox()
        box.addView(results, ui.lpm(tp = 8))
        var dlg: android.app.AlertDialog? = null
        fun fill(q: String) {
            results.removeAllViews()
            val rows = act.db.convos(q)
            if (rows.isEmpty()) results.addView(ui.dim("No conversations found."))
            for (c in rows) {
                val r = ui.row(null, c.preview, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.at)),
                    ui.iconButton("trash", "Delete conversation", 40) {
                        act.confirm("Delete conversation?", "This removes “${c.preview}” from this phone.", "Delete", { act.brain.deleteConvo(c.id); fill(search.text.toString()) }, null)
                    }, false) { act.brain.openConvo(c.id); dlg?.dismiss() }
                results.addView(r)
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { fill(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        fill("")
        dlg = act.dialog().setTitle("Conversations").setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear this chat") { _, _ ->
                act.confirm("Clear this chat?", "Only this conversation is removed. Saved memories are not touched.", "Clear", { act.brain.clearChat() }, null)
            }.show()
    }

    private fun scrollDown() { scroller?.post { scroller?.fullScroll(View.FOCUS_DOWN) } }

    override fun messagesChanged() { renderAll(); updateSend() }
    override fun streamUpdated() {
        val m = act.brain.msgs.lastOrNull { it.streaming } ?: return
        lastTv?.text = Md.render(m.text, ui.t); scrollDown()
    }
    override fun stateChanged() { updateSend(); updateHeader() }
    override fun onShow() { act.brain.listeners.add(this) }
    override fun onHide() { act.brain.listeners.remove(this) }
}
