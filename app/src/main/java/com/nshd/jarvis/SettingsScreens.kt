package com.nshd.jarvis

import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

// ============================================================ SETTINGS HUB
class SettingsScreen : Screen() {
    override fun build(ui: Ui): View {
        val t = ui.t; val p = act.prefs
        val col = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24)) }
        col.addView(ui.tv("Settings & Intelligence", 24f, t.text, true))
        col.addView(ui.dim("System preferences & configuration").apply { setPadding(0, ui.dp(4), 0, ui.dp(14)) })

        val prof = ui.card(16)
        val top = ui.hbox()
        top.addView(ui.badge("person", 56), ui.lpm(ui.dp(56), ui.dp(56), r = 14))
        val nm = ui.vbox()
        val name = p.s("user_name")
        nm.addView(ui.tv(if (name.isBlank()) "Set your name" else name, 19f, t.text, true))
        nm.addView(ui.tv("PRIMARY USER", 11f, t.dim, true).apply { letterSpacing = 0.08f; setPadding(0, ui.dp(4), 0, 0) })
        top.addView(nm, ui.lp(0, -2, 1f))
        top.addView(ui.iconButton("pencil", "Edit name", 44) { act.promptText("Your name", "Name", name) { n -> p.put("user_name", n.take(30)); act.rebuildCurrent() } })
        prof.addView(top)
        val chips = ui.hbox().apply { setPadding(0, ui.dp(12), 0, 0) }
        chips.addView(ui.chip("Voice setup", false) { act.go(VoiceSetupScreen()) }, ui.lpm(r = 8))
        chips.addView(ui.chip(Persona.preset(p).label, false) { act.go(PersonaScreen()) })
        prof.addView(chips)
        col.addView(prof)

        col.addView(ui.section("Voice & intelligence"))
        val g1 = ui.card(0)
        g1.addView(ui.row("mic", "Voice Setup", "Microphone, silence timing, test") { act.go(VoiceSetupScreen()) }.let { it })
        fun r(icon: String, title: String, sub: String, go: () -> Unit) { g1.addView(ui.divider()); g1.addView(ui.row(icon, title, sub, null, true) { go() }) }
        r("globe", "Conversation Language", Langs.current(p).label) { act.go(LanguageScreen()) }
        r("smile", "AI Persona & Voice", Persona.preset(p).label + " · " + Persona.name(p)) { act.go(PersonaScreen()) }
        g1.addView(ui.divider())
        g1.addView(ui.switchRow("stop", "Silent Execution Mode", "Don't narrate device tasks. Confirmations and safety checks still apply.", p.b("silent_mode", false)) { p.put("silent_mode", it) })
        col.addView(g1)

        col.addView(ui.section("System & data"))
        val g2 = ui.card(0)
        val prov = Providers.primary(act, p)
        g2.addView(ui.row("key", "AI Connection", prov.label + " · " + prov.modelName + " · " + (if (prov.configured()) "key saved" else "no key")) { act.go(AiConnectionScreen()) })
        fun r2(icon: String, title: String, sub: String, go: () -> Unit) { g2.addView(ui.divider()); g2.addView(ui.row(icon, title, sub, null, true) { go() }) }
        r2("memory", "Memory Core", act.db.memories().size.toString() + " saved · search, edit, export") { act.go(MemoryScreen()) }
        r2("shield", "System Permissions", "Microphone, location, overlay, accessibility") { act.go(PermissionsScreen()) }
        r2("smile", "Overlay Avatar", if (p.b("overlay_on", false)) "On · " + AvatarView.label(p.s("avatar", "orb")) else "Off") { act.go(OverlayScreen()) }
        r2("apps", "App Control & Hands-Free", if (p.b("app_control", false)) "Enabled" else "Off · chat-only") { act.go(AppControlScreen()) }
        r2("code", "Code Workspace", "Write, save and open code in other editors") { act.go(CodeScreen()) }
        col.addView(g2)

        col.addView(ui.section("Appearance & security"))
        val g3 = ui.card(0)
        g3.addView(ui.row("palette", "Theme Studio", Themes.all.firstOrNull { it.id == p.s("theme", Brand.DEFAULT_THEME) }?.label ?: "System") { act.go(ThemeScreen()) })
        g3.addView(ui.divider()); g3.addView(ui.row("lock", "Privacy & Data Shield", "What stays here, what is sent, delete data") { act.go(PrivacyScreen()) })
        g3.addView(ui.divider()); g3.addView(ui.row("info", "About & Credits", Brand.TAGLINE) { act.go(AboutScreen()) })
        col.addView(g3)

        val soc = ui.hbox().apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(22), 0, ui.dp(8)) }
        if (Brand.INSTAGRAM.isNotBlank()) soc.addView(ui.iconButton("instagram", "Instagram", 48) { act.openLink("https://instagram.com/" + Brand.INSTAGRAM) }, ui.lpm(ui.dp(48), ui.dp(48), r = 12))
        if (Brand.TELEGRAM.isNotBlank()) soc.addView(ui.iconButton("send", "Telegram", 48) { act.openLink("https://t.me/" + Brand.TELEGRAM) })
        col.addView(soc)
        col.addView(ui.tv(Brand.APP_NAME.uppercase() + " · v" + act.versionName() + " · BY " + Brand.CREATOR_SHORT, 11f, t.dim, true, Gravity.CENTER).apply { letterSpacing = 0.08f })
        return ui.scroll(col, 0)
    }
}

// ============================================================ AI CONNECTION
class AiConnectionScreen : Screen() {
    override val isSub get() = true
    private val io = Executors.newSingleThreadExecutor()
    private var result: TextView? = null

    override fun build(ui: Ui): View {
        val t = ui.t; val p = act.prefs
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val primary = p.s("provider", "gemini")

        // status
        val st = ui.card(16)
        val prov = Providers.primary(act, p)
        val head = ui.hbox()
        head.addView(View(act).apply { background = ui.shape(if (prov.configured()) t.ok else t.warn, 8f); layoutParams = LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)) }, ui.lpm(ui.dp(10), ui.dp(10), r = 10))
        head.addView(ui.tv(if (prov.configured()) "Primary: ${prov.label} · key saved" else "No API key yet · add one below", 14f, t.text, true), ui.lp(0, -2, 1f))
        st.addView(head)
        val res = ui.dim("Not tested yet.", 13f).apply { setPadding(0, ui.dp(8), 0, ui.dp(10)) }
        result = res
        st.addView(res)
        st.addView(ui.button("Test connection & latency", false) { testPrimary() })
        col.addView(st)
        col.addView(ui.dim("Your messages go only to the provider marked Primary. There is no silent fallback to another provider.").apply { setPadding(ui.dp(6), ui.dp(10), ui.dp(6), 0) })

        // Gemini
        col.addView(ui.section("Google Gemini"))
        val g = ui.card(16)
        g.addView(providerHeader(ui, "gemini", primary == "gemini", "Free tier available · limits and terms can change"))
        val gkey = ui.input(if (SecureStore.has(act, "gemini_key")) "Key saved (hidden) — paste to replace" else "AIza… (from Google AI Studio)", "", true)
        g.addView(gkey, ui.lpm(tp = 12))
        val br = ui.hbox().apply { setPadding(0, ui.dp(8), 0, 0) }
        br.addView(ui.button("Paste", false) { gkey.setText(clip()) }, ui.lpm(0, -2, r = 8, weight = 1f))
        br.addView(ui.button("Save key") {
            val k = gkey.text.toString().trim()
            if (k.length < 20) act.toast("That doesn't look like an API key.") else { SecureStore.put(act, "gemini_key", k); act.toast("Key saved (encrypted on this device)."); act.rebuildCurrent() }
        }, ui.lpm(0, -2, r = 8, weight = 1f))
        br.addView(ui.button("Remove", false, true) { SecureStore.put(act, "gemini_key", ""); act.toast("Key removed."); act.rebuildCurrent() }, ui.lp(0, -2, 1f))
        g.addView(br)
        g.addView(ui.tv("Model", 13f, t.text, true).apply { setPadding(0, ui.dp(14), 0, ui.dp(6)) })
        val model = ui.input("Model name", p.s("gemini_model", GeminiProvider.DEFAULT_MODEL))
        g.addView(model)
        val list = p.s("gemini_models", GeminiProvider.DEFAULT_LIST).split(",").filter { it.isNotBlank() }
        val chipsBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var rowL = ui.hbox(false); var w = 0
        val maxW = act.resources.displayMetrics.widthPixels - ui.dp(32 + 32 + 8)
        for (m in list.take(16)) {
            val c = ui.chip(m, m == model.text.toString().trim()) { model.setText(m); p.put("gemini_model", m); act.rebuildCurrent() }
            c.measure(0, 0)
            if (w + c.measuredWidth > maxW) { chipsBox.addView(rowL); rowL = ui.hbox(false); w = 0 }
            rowL.addView(c, ui.lpm(-2, -2, r = 8, b = 8)); w += c.measuredWidth + ui.dp(8)
        }
        chipsBox.addView(rowL)
        g.addView(chipsBox, ui.lpm(tp = 8))
        val mr = ui.hbox().apply { setPadding(0, ui.dp(4), 0, 0) }
        mr.addView(ui.button("Use model", false) { p.put("gemini_model", model.text.toString().trim().ifBlank { GeminiProvider.DEFAULT_MODEL }); act.toast("Model saved."); act.rebuildCurrent() }, ui.lpm(0, -2, r = 8, weight = 1f))
        mr.addView(ui.button("Refresh model list", false) { refreshModels() }, ui.lp(0, -2, 1f))
        g.addView(mr)
        g.addView(ui.tv("Custom gateway URL (optional)", 13f, t.text, true).apply { setPadding(0, ui.dp(14), 0, ui.dp(6)) })
        val base = ui.input("https://… (blank = Google's default)", p.s("gemini_base"))
        g.addView(base)
        g.addView(ui.button("Save gateway", false) {
            val v = base.text.toString().trim()
            if (v.isNotEmpty() && !Http.validHttps(v)) act.toast("Gateway must start with https://") else { p.put("gemini_base", v); act.toast("Saved.") }
        }, ui.lpm(tp = 8))
        col.addView(g)

        // OpenAI-compatible
        col.addView(ui.section("OpenAI-compatible"))
        val o = ui.card(16)
        o.addView(providerHeader(ui, "openai", primary == "openai", "Not verified · usually needs credits"))
        o.addView(ui.dim("Works with services that use the OpenAI chat-completions format (OpenAI, OpenRouter, Groq, DeepSeek, your own gateway). Text chat only — device actions need Gemini.").apply { setPadding(0, ui.dp(8), 0, 0) })
        val ob = ui.input("Base URL", p.s("openai_base", OpenAiProvider.DEFAULT_BASE)); o.addView(ob, ui.lpm(tp = 10))
        val om = ui.input("Model name", p.s("openai_model", "gpt-4o-mini")); o.addView(om, ui.lpm(tp = 8))
        val ok = ui.input(if (SecureStore.has(act, "openai_key")) "Key saved (hidden) — paste to replace" else "API key", "", true); o.addView(ok, ui.lpm(tp = 8))
        val orow = ui.hbox().apply { setPadding(0, ui.dp(8), 0, 0) }
        orow.addView(ui.button("Save") {
            val b = ob.text.toString().trim().trimEnd('/')
            if (!Http.validHttps(b)) { act.toast("Base URL must start with https://"); return@button }
            p.put("openai_base", b); p.put("openai_model", om.text.toString().trim())
            val k = ok.text.toString().trim(); if (k.isNotEmpty()) SecureStore.put(act, "openai_key", k)
            act.toast("Saved."); act.rebuildCurrent()
        }, ui.lpm(0, -2, r = 8, weight = 1f))
        orow.addView(ui.button("Remove key", false, true) { SecureStore.put(act, "openai_key", ""); act.toast("Key removed."); act.rebuildCurrent() }, ui.lp(0, -2, 1f))
        o.addView(orow)
        col.addView(o)

        col.addView(ui.section("Conversation"))
        val c = ui.card(0)
        c.addView(ui.labeledSlider("Messages sent as context", { "$it" }, 4, 40, p.i("context_limit", 16)) { p.put("context_limit", it) })
        col.addView(c)
        col.addView(ui.dim("Keys are encrypted with the Android Keystore and kept out of logs. A phone can't guarantee a key is impossible to extract from a rooted or modified device, so use keys with spending limits.").apply { setPadding(ui.dp(6), ui.dp(12), ui.dp(6), 0) })
        return sub(ui, "AI Connection", "Providers, keys and models", col)
    }

    private fun providerHeader(ui: Ui, id: String, isPrimary: Boolean, badge: String): View {
        val box = ui.vbox()
        val r = ui.hbox()
        r.addView(ui.tv(if (id == "gemini") "Gemini" else "OpenAI-compatible", 16f, ui.t.text, true), ui.lp(0, -2, 1f))
        r.addView(if (isPrimary) ui.tv("PRIMARY", 11f, ui.t.onAccent, true).apply { setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5)); background = ui.shape(ui.t.accent, 12f) }
        else ui.chip("Set as primary", false) { act.prefs.put("provider", id); act.rebuildCurrent() })
        box.addView(r)
        box.addView(ui.tv(badge, 11.5f, ui.t.warn, true).apply { setPadding(0, ui.dp(6), 0, 0) })
        return box
    }

    private fun clip(): String = try {
        (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
    } catch (_: Exception) { "" }

    private fun testPrimary() {
        val prov = Providers.primary(act, act.prefs)
        result?.text = "Testing ${prov.label}…"
        io.execute {
            val r = prov.test()
            act.runOnUiThread { result?.text = (if (r.ok) "✓ " else "✗ ") + r.message; if (r.ok) act.brain.flashConnected() }
        }
    }

    private fun refreshModels() {
        result?.text = "Fetching the model list from Google…"
        io.execute {
            val (models, err) = GeminiProvider(act, act.prefs).listModels()
            act.runOnUiThread {
                if (err != null) result?.text = "✗ $err"
                else if (models.isEmpty()) result?.text = "No models returned."
                else { act.prefs.put("gemini_models", models.joinToString(",")); result?.text = "✓ ${models.size} models available — tap one below."; act.rebuildCurrent() }
            }
        }
    }
}

// ============================================================ LANGUAGE
class LanguageScreen : Screen() {
    override val isSub get() = true
    override fun build(ui: Ui): View {
        val t = ui.t; val p = act.prefs
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val cur = Langs.current(p)
        val info = ui.card(16)
        info.addView(ui.tv("Conversation language: " + cur.label, 14f, t.text, true))
        info.addView(ui.dim("Speech recognition: " + (cur.stt.ifBlank { "phone default" }) + "\nSpoken voice: " + (cur.tts.ifBlank { "phone default" }) +
            (if (act.voice.ttsNote.isNotBlank()) "\n⚠ " + act.voice.ttsNote else "") +
            "\n\nThese can differ. How well a language works depends on your phone's speech recognizer and installed voices — if it mishears you, type the correction in chat.").apply { setPadding(0, ui.dp(8), 0, 0) })
        col.addView(info)
        col.addView(ui.section("Choose language"))
        val card = ui.card(0)
        for ((i, l) in Langs.all.withIndex()) {
            if (i > 0) card.addView(ui.divider())
            val rb = RadioButton(act).apply { isChecked = l.id == cur.id; buttonTintList = android.content.res.ColorStateList.valueOf(t.accent); isClickable = false; contentDescription = l.label }
            card.addView(ui.row(null, l.label, if (l.id == "manglish") "Malayalam written in Latin letters — not a separate speech-recognition language" else null, rb, false) {
                p.put("lang", l.id); act.voice.applySettings(); act.rebuildCurrent()
            })
        }
        col.addView(card)
        return sub(ui, "Conversation Language", "Replies, speech input and voice", col)
    }
}

// ============================================================ PERSONA & VOICE
class PersonaScreen : Screen() {
    override val isSub get() = true
    override fun build(ui: Ui): View {
        val t = ui.t; val p = act.prefs
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val cur = Persona.preset(p)
        col.addView(ui.section("Role"))
        val card = ui.card(0)
        for ((i, pr) in Persona.presets.withIndex()) {
            if (i > 0) card.addView(ui.divider())
            val rb = RadioButton(act).apply { isChecked = pr.id == cur.id; buttonTintList = android.content.res.ColorStateList.valueOf(t.accent); isClickable = false; contentDescription = pr.label }
            card.addView(ui.row(null, pr.label, pr.blurb, rb, false) {
                if (pr.id != cur.id) { p.put("persona", pr.id); for (k in 0 until 8) p.remove("tone_$k"); act.rebuildCurrent() }
            })
        }
        col.addView(card)

        col.addView(ui.section("Names"))
        val names = ui.card(16)
        names.addView(ui.tv("Persona name", 13f, t.text, true))
        val pn = ui.input("Zara", p.s("persona_name")); names.addView(pn, ui.lpm(tp = 6))
        names.addView(ui.tv("What should it call you?", 13f, t.text, true).apply { setPadding(0, ui.dp(12), 0, 0) })
        val nk = ui.input("Nickname (optional)", p.s("nickname")); names.addView(nk, ui.lpm(tp = 6))
        names.addView(ui.tv(if (cur.id == "custom") "Describe the persona" else "Extra notes (optional)", 13f, t.text, true).apply { setPadding(0, ui.dp(12), 0, 0) })
        val cu = ui.input("e.g. calm, speaks in short sentences…", p.s("persona_custom"), false, true).apply { minLines = 3; filters = arrayOf(android.text.InputFilter.LengthFilter(Persona.CUSTOM_LIMIT)) }
        names.addView(cu, ui.lpm(tp = 6))
        names.addView(ui.dim("Up to ${Persona.CUSTOM_LIMIT} characters. Persona text never overrides safety, privacy or confirmation rules.").apply { setPadding(0, ui.dp(6), 0, 0) })
        names.addView(ui.button("Save names & notes") {
            p.put("persona_name", pn.text.toString().trim()); p.put("nickname", nk.text.toString().trim()); p.put("persona_custom", cu.text.toString().trim())
            act.toast("Saved."); act.rebuildCurrent()
        }, ui.lpm(tp = 12))
        col.addView(names)

        col.addView(ui.section("Style dials"))
        val tones = ui.card(0)
        for (i in 0 until 8) {
            if (i > 0) tones.addView(ui.divider())
            tones.addView(ui.labeledSlider(Persona.toneNames[i], { "$it" }, 0, 100, Persona.tone(p, i)) { p.put("tone_$i", it) })
        }
        col.addView(tones)
        col.addView(ui.button("Reset dials to preset", false) { for (k in 0 until 8) p.remove("tone_$k"); act.rebuildCurrent() }, ui.lpm(tp = 10))

        col.addView(ui.section("Spoken voice"))
        val vc = ui.card(0)
        val voices = act.voice.installedVoices()
        val vNames = VoiceNames.assign(voices)
        val sel = voices.firstOrNull { it.name == p.s("tts_voice") }
        vc.addView(ui.row("mic", "Voice", if (sel != null) VoiceNames.label(p, vNames, sel) + " — " + VoiceNames.detail(sel) else "Phone default · ${voices.size} voices installed for this language") {
            if (voices.isEmpty()) act.toast("No voices reported by Android text-to-speech.")
            else act.chooser("Choose a voice", listOf<Pair<String, () -> Unit>>("Phone default" to { p.remove("tts_voice"); act.voice.applySettings(); act.rebuildCurrent() }) +
                voices.take(40).map { v ->
                    val nm = VoiceNames.label(p, vNames, v)
                    (nm + " — " + VoiceNames.detail(v)) to {
                        p.put("tts_voice", v.name); act.voice.applySettings(); act.rebuildCurrent()
                        act.voice.speak("Hi, I'm " + Persona.name(p) + ". This is the voice called $nm.")
                    }
                })
        })
        if (sel != null) {
            vc.addView(ui.divider())
            vc.addView(ui.row("pencil", "Rename this voice", "Currently “" + VoiceNames.label(p, vNames, sel) + "”") {
                act.promptText("Name this voice", "e.g. Aria", VoiceNames.label(p, vNames, sel)) { n -> p.put("voice_label_" + sel.name, n.take(24)); act.rebuildCurrent() }
            })
        }
        vc.addView(ui.divider())
        vc.addView(ui.labeledSlider("Speech rate", { (it / 100f).toString() + "×" }, 50, 200, p.i("tts_rate", 100)) { p.put("tts_rate", it); act.voice.applySettings() })
        vc.addView(ui.divider())
        vc.addView(ui.labeledSlider("Pitch", { (it / 100f).toString() + "×" }, 50, 200, p.i("tts_pitch", 100)) { p.put("tts_pitch", it); act.voice.applySettings() })
        col.addView(vc)
        col.addView(ui.dim("Voices come from your phone's installed text-to-speech engine, not from the AI provider.").apply { setPadding(ui.dp(6), ui.dp(8), ui.dp(6), 0) })
        col.addView(ui.button("Preview voice", false) {
            act.voice.applySettings(); act.voice.speak("Hi, I'm " + Persona.name(p) + ". This is how I sound.")
        }, ui.lpm(tp = 10))
        col.addView(ui.section("Visual avatar"))
        col.addView(ui.card(0).apply { addView(ui.row("smile", "Floating avatar", "Chosen separately from the spoken voice") { act.go(OverlayScreen()) }) })
        return sub(ui, "AI Persona & Voice", "Personality, names and spoken voice", col)
    }
}

// ============================================================ VOICE SETUP
class VoiceSetupScreen : Screen() {
    override val isSub get() = true
    private var result: TextView? = null
    override fun build(ui: Ui): View {
        val t = ui.t; val p = act.prefs
        val col = ui.vbox().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }

        val st = ui.card(16)
        val granted = act.voice.micGranted()
        st.addView(ui.tv(if (granted) "MICROPHONE: ALLOWED" else "MICROPHONE: NOT ALLOWED", 12f, if (granted) t.ok else t.warn, true).apply { letterSpacing = 0.06f })
        st.addView(ui.dim(if (act.voice.sessionActive) "A listening session is active right now." else "Zara listens only while a voice session is running — start one with the mic button.").apply { setPadding(0, ui.dp(6), 0, ui.dp(10)) })
        if (!granted) st.addView(ui.button("Allow microphone") { act.requestPerm(501, android.Manifest.permission.RECORD_AUDIO) })
        col.addView(st)

        col.addView(ui.section("Test"))
        val tc = ui.card(16)
        tc.addView(ui.dim("Say something and see exactly what the recognizer heard (language: ${Langs.current(p).label})."))
        val res = ui.tv("", 15f, t.text, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(10)) }
        result = res; tc.addView(res)
        tc.addView(ui.button("Test speech recognition (speak now)") { runTest() })
        tc.addView(ui.button("Test spoken reply", false) { act.voice.applySettings(); act.voice.speak("Voice output is working.") }, ui.lpm(tp = 8))
        col.addView(tc)

        col.addView(ui.section("Turn-taking"))
        val tt = ui.card(0)
        tt.addView(ui.labeledSlider("Wait after you stop talking", { String.format("%.1f s", it / 1000f) }, 1000, 4000, p.i("silence_complete_ms", 2000)) { p.put("silence_complete_ms", it) })
        tt.addView(ui.divider())
        tt.addView(ui.labeledSlider("Pause that may be a hesitation", { String.format("%.1f s", it / 1000f) }, 800, 3000, p.i("silence_possible_ms", 1500)) { p.put("silence_possible_ms", it) })
        tt.addView(ui.divider())
        tt.addView(ui.labeledSlider("Stop listening after silence", { "$it s" }, 15, 120, p.i("idle_timeout_s", 45)) { p.put("idle_timeout_s", it) })
        col.addView(tt)
        col.addView(ui.dim("Raise the first two if your last words get cut off. Some phones' recognizers ignore these hints. While Zara is speaking, tap the orb or mic to interrupt.").apply { setPadding(ui.dp(6), ui.dp(8), ui.dp(6), 0) })

        col.addView(ui.section("Not available yet"))
        val na = ui.card(0)
        na.addView(ui.row("mic", "Wake phrase (hands-free)", "In development. Sessions start when you tap the mic.", null, false, null))
        na.addView(ui.divider())
        na.addView(ui.row("lock", "Owner-voice lock", "Not implemented. Voice matching isn't secure authentication, so Zara doesn't claim to lock to your voice.", null, false, null))
        col.addView(na)
        return sub(ui, "Voice Setup", "Microphone, timing and tests", col)
    }

    private fun runTest() {
        WakeService.pause()
        val r = act.voice.testOnce { text ->
            WakeService.resume()
            act.runOnUiThread { result?.text = if (text.isBlank()) "Nothing heard. Try again, closer to the mic." else "“$text”" }
        }
        when (r) {
            null -> result?.text = "Listening…"
            "need_permission" -> { WakeService.resume(); act.requestPerm(501, android.Manifest.permission.RECORD_AUDIO) }
            else -> { WakeService.resume(); result?.text = r }
        }
    }
    override fun onPermission(req: Int, granted: Boolean) { if (req == 501) act.rebuildCurrent() }
}
