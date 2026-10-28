package com.nshd.jarvis

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.DateFormat
import java.util.Date

// ============================================================ UPDATES / ACTIVITY
class ActivityScreen : Screen() {
    override fun build(ui: Ui): View {
        val t = ui.t
        val col = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24)) }
        col.addView(ui.tv("Updates & Activity", 24f, t.text, true))
        col.addView(ui.dim("Verified task history, pending steps and release notes").apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })

        val tasks = act.db.tasks(60)
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        val pending = tasks.filter { it.status == "NEEDS_USER_ACTION" && it.at > dayAgo }.take(5)
        if (pending.isNotEmpty()) {
            col.addView(ui.section("Waiting for you"))
            for (p in pending) col.addView(taskCard(ui, p), ui.lpm(b = 10))
        }
        col.addView(ui.section("Recent tasks"))
        if (tasks.isEmpty()) col.addView(ui.card(16).apply { addView(ui.dim("No device tasks yet. When Zara opens an app, searches or taps something for you, the real outcome is logged here — success is only shown when it was actually observed.", 13.5f)) })
        for (tk in tasks.take(25)) col.addView(taskCard(ui, tk), ui.lpm(b = 10))
        if (tasks.isNotEmpty()) col.addView(ui.button("Clear activity history", false, true) {
            act.confirm("Clear activity history?", "Deletes the task journal. Chats and memories are untouched.", "Clear", { act.db.clearTasks(); act.rebuildCurrent() }, null)
        }, ui.lpm(tp = 4))

        col.addView(ui.section("Version"))
        col.addView(ui.card(16).apply {
            addView(ui.tv(Brand.APP_NAME + " v" + act.versionName(), 15f, t.text, true))
            addView(ui.dim("Build " + act.versionCode() + " · debug-signed unless you configure release signing").apply { setPadding(0, ui.dp(4), 0, 0) })
        })

        col.addView(ui.section("Release notes"))
        col.addView(ui.card(16).apply {
            addView(ui.tv("0.5.0 — faster, more reliable phone actions", 14f, t.text, true))
            addView(ui.dim("• Actions continue the instant the screen settles (no fixed 1–3 s sleeps)\n• Every action returns the fresh screen, saving a model round trip\n• Multi-step jobs go to a fast task model while the voice keeps chatting, then she tells you the result\n• Tolerant tool arguments, compact screens, stuck/step-limit protection and a live progress line", 13f).apply { setPadding(0, ui.dp(8), 0, ui.dp(14)) })
            addView(ui.tv("0.4.0 — Live, agent mode, new orbs", 14f, t.text, true))
            addView(ui.dim("• Live conversation: mic stays open, speak while she thinks, interrupt any time, works with the app closed\n• Agent mode: the AI reads the screen and operates any app step by step, with approvals and protected apps\n• Fixed: after one YouTube command every later message re-ran it\n• Twelve orbs incl. the nine Thinking Orbs designs and a GPU fluid sphere\n• Permanent signing key (updates install over old builds)", 13f).apply { setPadding(0, ui.dp(8), 0, ui.dp(14)) })
            addView(ui.tv("0.3.0 — play anything, orb styles, Hey Zara", 14f, t.text, true))
            addView(ui.dim("• “Open Spotify and play …” now goes to Spotify (the AI no longer reuses YouTube)\n• YouTube: starts the top result with Accessibility; Spotify/YouTube Music/other players via play-from-search\n• New: timers, alarms, web search, maps, dial and SMS drafts\n• Five orb styles: Particles, Fluid, Aurora, Pulse, Waveform\n• Friendly, renameable names for every installed voice\n• Hands-free “Hey Zara” (experimental)", 13f).apply { setPadding(0, ui.dp(8), 0, ui.dp(14)) })
            addView(ui.tv("0.2.1 — layout fixes & polish", 14f, t.text, true))
            addView(ui.dim("• Fixed settings/home rows that rendered as tall blank cards\n• Denser orb with lavender disc and orbiting rings (no clipped halo)\n• Touch ripples, rounded-clip cards, richer Home tiles and chat start screen", 13f).apply { setPadding(0, ui.dp(8), 0, ui.dp(14)) })
            addView(ui.tv("0.2.0 — Zara AI", 14f, t.text, true))
            addView(ui.dim("• New multi-screen app: Home, Chat, Settings, Activity\n• Streaming Gemini chat with stop, retry and conversation history\n• OpenAI-compatible provider adapter\n• Keys encrypted with Android Keystore\n• Persona, language and installed-voice settings\n• Local memory with search, edit, export\n• Typed, confirmed device actions with verified outcomes\n• Optional accessibility actions and floating avatar\n• Themes: Sunset, AMOLED, Dark, Light\n\nNot built yet: wake phrase, Gemini Live audio, screen-sharing vision, calls, Instagram flows.", 13f).apply { setPadding(0, ui.dp(8), 0, 0) })
        })
        return ui.scroll(col, 0)
    }

    private fun taskCard(ui: Ui, tk: TaskRow): View {
        val c = ui.card(14)
        val top = ui.hbox()
        top.addView(ui.tv(tk.summary.ifBlank { tk.app }, 14f, ui.t.text, true), ui.lp(0, -2, 1f))
        top.addView(ui.statusChip(tk.status))
        c.addView(top)
        if (tk.hint.isNotBlank()) c.addView(ui.dim(tk.hint, 12.5f).apply { setPadding(0, ui.dp(6), 0, 0) })
        c.addView(ui.dim(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(tk.at)), 11.5f).apply { setPadding(0, ui.dp(6), 0, 0) })
        return c
    }
}

// ============================================================ CODE WORKSPACE
class CodeScreen : Screen() {
    override val isSub get() = true
    private var editor: EditText? = null
    private var status: TextView? = null
    private val langs = listOf("Kotlin" to "kt", "Java" to "java", "Python" to "py", "JavaScript" to "js", "HTML" to "html", "C++" to "cpp", "Text" to "txt")

    override fun build(ui: Ui): View {
        val t = ui.t
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val name = ui.input("File name", act.codeName)
        name.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { act.codeName = s?.toString().orEmpty() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        col.addView(name)
        val chipsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((l, ext) in langs) chipsRow.addView(ui.chip(l, act.codeLang == l) {
            act.codeLang = l
            val base = act.codeName.substringBeforeLast('.', act.codeName).ifBlank { "main" }
            act.codeName = "$base.$ext"; act.rebuildCurrent()
        }, ui.lpm(r = 8))
        col.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chipsRow) }, ui.lpm(tp = 10))

        val ed = EditText(act).apply {
            setText(act.codeText); setTextColor(t.text); setHintTextColor(t.dim); hint = "Write or paste code here, or ask Zara in Chat (Code mode)."
            typeface = android.graphics.Typeface.MONOSPACE; setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            background = ui.shape(t.surface, 16f, t.stroke); gravity = Gravity.TOP
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); minLines = 12
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { act.codeText = s?.toString().orEmpty(); act.codeStatus = "Only inside Zara (not saved yet)" ; status?.text = act.codeStatus }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        editor = ed
        col.addView(ed, ui.lpm(tp = 10))
        val st = ui.tv(act.codeStatus, 12f, t.accentText, true).apply { setPadding(ui.dp(4), ui.dp(8), 0, 0) }
        status = st; col.addView(st)

        fun rowOf(vararg b: View): LinearLayout = ui.hbox().apply { setPadding(0, ui.dp(10), 0, 0); b.forEachIndexed { i, v -> addView(v, ui.lpm(0, -2, r = if (i < b.size - 1) 8 else 0, weight = 1f)) } }
        col.addView(rowOf(ui.button("Copy", false) { act.copyText(act.codeText) }, ui.button("Share", false) { act.shareText(act.codeText) }, ui.button("Explain", false) { explain() }))
        col.addView(rowOf(ui.button("Save…") { save() }, ui.button("Open…", false) { openDoc() }, ui.button("Open with…", false) { openWith() }))
        col.addView(ui.dim("Zara never runs code on your phone. “Save…” uses Android's file picker, “Open with…” hands the saved file to any editor you have installed (QuickEdit or others). Status above only says saved when Android confirmed it.").apply { setPadding(ui.dp(6), ui.dp(12), ui.dp(6), 0) })
        return sub(ui, "Code Workspace", "Write, save and open code", col)
    }

    private fun setStatus(s: String) { act.codeStatus = s; status?.text = s }

    private fun save() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE, act.codeName.ifBlank { "main.txt" })
        try { act.startActivityForResult(i, 601) } catch (_: Exception) { act.toast("No file picker available.") }
    }

    private fun openDoc() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { act.startActivityForResult(i, 602) } catch (_: Exception) { act.toast("No file picker available.") }
    }

    private fun openWith() {
        val uri = act.codeUri
        if (uri == null) { act.toast("Save the file first, then open it in another editor."); return }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try { act.startActivity(Intent.createChooser(i, "Open with")); setStatus("Offered to other editors (Android's chooser opened)") }
        catch (_: Exception) { act.toast("No app could open that file.") }
    }

    private fun explain() {
        if (act.codeText.isBlank()) { act.toast("Nothing to explain yet."); return }
        act.tab(1)
        act.brain.codeMode = true
        act.brain.submit("Explain this code in simple language and point out likely mistakes:\n```" + act.codeLang.lowercase() + "\n" + act.codeText.take(6000) + "\n```")
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri: Uri = data?.data ?: return
        if (res != android.app.Activity.RESULT_OK) return
        if (req == 601) {
            try {
                act.contentResolver.openOutputStream(uri, "wt")?.use { it.write(act.codeText.toByteArray(Charsets.UTF_8)) } ?: throw java.io.IOException("no stream")
                act.codeUri = uri
                setStatus("Saved ✓ " + (uri.lastPathSegment?.substringAfterLast('/') ?: act.codeName))
            } catch (e: Exception) { setStatus("Not saved: " + (e.message ?: e.javaClass.simpleName)) }
        } else if (req == 602) {
            try {
                val txt = act.contentResolver.openInputStream(uri)?.use { it.readBytes().take(1_000_000).toByteArray().toString(Charsets.UTF_8) } ?: throw java.io.IOException("no stream")
                act.codeText = txt; act.codeUri = uri
                act.codeName = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { act.codeName } ?: act.codeName
                setStatus("Opened " + act.codeName)
                act.rebuildCurrent()
            } catch (e: Exception) { setStatus("Couldn't open that file: " + (e.message ?: e.javaClass.simpleName)) }
        }
    }
}

// ============================================================ FIRST RUN
class OnboardingScreen : Screen() {
    override val isSub get() = true
    override fun build(ui: Ui): View {
        val t = ui.t; val p = act.prefs
        val col = ui.vbox().apply { setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(24)) }
        col.addView(ui.tv(Brand.COMPANION_NAME, 44f, t.accentText, false, Gravity.CENTER).apply { typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL); letterSpacing = 0.16f })
        col.addView(ui.tv(Brand.TAGLINE, 13f, t.dim, true, Gravity.CENTER).apply { setPadding(0, ui.dp(6), 0, ui.dp(22)) })
        col.addView(ui.card(18).apply {
            addView(ui.tv("Welcome", 18f, t.text, true))
            addView(ui.dim("• Chat and talk with an AI companion — it starts in chat-only mode.\n• You choose what it can do: phone actions, accessibility and the floating avatar are all off until you turn them on.\n• Your chats and memories stay on this phone. Messages go only to the AI provider you pick.\n• Nothing is requested at startup — permissions are asked only when you use a feature.", 14f).apply { setPadding(0, ui.dp(10), 0, 0) })
        })
        col.addView(ui.tv("What should I call you?", 13f, t.text, true).apply { setPadding(ui.dp(4), ui.dp(20), 0, ui.dp(6)) })
        val name = ui.input("Your name (optional)", p.s("user_name")); col.addView(name)
        fun finish(next: Screen?) {
            p.put("user_name", name.text.toString().trim().take(30)); p.put("onboarded", true)
            act.finishOnboarding(next)
        }
        col.addView(ui.button("Start in chat-only mode") { finish(null) }, ui.lpm(tp = 22))
        col.addView(ui.button("Add my AI key first", false) { finish(AiConnectionScreen()) }, ui.lpm(tp = 10))
        col.addView(ui.dim("You can enable phone actions any time in Settings → App Control & Hands-Free.", 12.5f).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(14), 0, 0) })
        return ui.scroll(col, 0)
    }
}
