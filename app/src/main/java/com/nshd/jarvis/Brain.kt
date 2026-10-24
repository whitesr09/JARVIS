package com.nshd.jarvis

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** role: user | assistant | task | error. Task cards carry a [status] and are not stored in chat history. */
class UiMsg(val role: String, var text: String, var streaming: Boolean = false, var meta: String = "", var status: String = "")

interface BrainListener {
    fun messagesChanged() {}
    fun streamUpdated() {}
    fun stateChanged() {}
    fun levelChanged(level: Float) {}
}

/** Conversation coordinator shared by chat and voice: local commands, memory, AI streaming, device actions. */
class Brain(private val act: MainActivity) {
    val msgs = ArrayList<UiMsg>()
    val listeners = ArrayList<BrainListener>()
    var orb = OrbState.IDLE; private set
    var busy = false; private set
    var executing = false; private set
    var draft = ""
    var codeMode = false
    var lastNote = ""      // short human line for the home screen ("Opened YouTube", provider errors…)

    private val net = Executors.newSingleThreadExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cancel = AtomicBoolean(false)
    private var convo = act.prefs.i("active_convo", 1)
    private var errorUntil = 0L
    private var connectedUntil = 0L

    val activeConvo get() = convo
    private val runner = AgentRunner(act.applicationContext, act.prefs, act.db, act.executor)

    // ---- Live conversation events (the Live service owns the session; this just mirrors it in the chat) ----
    private var liveUser: UiMsg? = null
    private var liveBot: UiMsg? = null
    private val liveListener = object : LiveListener {
        override fun onLiveState(s: LiveState, note: String) { if (s == LiveState.ERROR && note.isNotBlank()) lastNote = note; refresh() }
        override fun onLiveUser(text: String) {
            if (liveBot != null) { liveBot?.streaming = false; liveBot = null; liveUser = null }
            if (liveUser == null) { liveUser = UiMsg("user", "", false); msgs.add(liveUser!!) }
            liveUser!!.text += text; changed()
        }
        override fun onLiveAssistant(text: String) {
            if (liveBot == null) { liveBot = UiMsg("assistant", "", true, "Live · " + Persona.name(act.prefs)); msgs.add(liveBot!!); changed() }
            liveBot!!.text += text; streamed()
        }
        override fun onLiveTurnComplete() { liveBot?.streaming = false; liveBot = null; liveUser = null; changed() }
        override fun onLiveTask(title: String, res: ActionResult) {
            msgs.add(UiMsg("task", res.message + (if (res.hint.isNotBlank()) "\n" + res.hint else ""), false, title, res.status.name)); changed()
        }
        override fun onLiveLevel(v: Float) { level(v) }
    }

    init { loadActive(); LiveBus.listeners.add(liveListener) }

    private fun loadActive() {
        msgs.clear()
        for ((r, t) in act.db.messages(convo)) {
            if (r == "task") { val p = t.split('\u001F'); msgs.add(UiMsg("task", p.getOrElse(2) { "" }, false, p.getOrElse(1) { "" }, p.getOrElse(0) { "" })) }
            else msgs.add(UiMsg(if (r == "assistant") "assistant" else "user", t))
        }
    }

    // ---------- conversation management ----------
    fun newChat() { stop(); convo = maxOf(act.db.maxConvo(), convo) + 1; act.prefs.put("active_convo", convo); msgs.clear(); changed() }
    fun openConvo(id: Int) { stop(); convo = id; act.prefs.put("active_convo", id); loadActive(); changed() }
    fun clearChat() { stop(); val c = convo; io.execute { act.db.deleteConvo(c) }; msgs.clear(); changed() }
    fun deleteConvo(id: Int) {
        io.execute { act.db.deleteConvo(id) }
        if (id == convo) { msgs.clear(); changed() }
    }

    fun stop() {
        cancel.set(true)
        busy = false
        msgs.lastOrNull()?.let { if (it.streaming) it.streaming = false }
        act.voice.stopSpeaking()
        refresh(); changed()
    }

    // ---------- state ----------
    fun flashConnected() { connectedUntil = System.currentTimeMillis() + 2500; refresh(); main.postDelayed({ refresh() }, 2600) }
    fun flashError(note: String) { lastNote = note; errorUntil = System.currentTimeMillis() + 4000; refresh(); main.postDelayed({ refresh() }, 4100) }

    fun refresh() {
        val now = System.currentTimeMillis()
        val v = act.voice.state
        val ls = LiveBus.state
        val s = when {
            ls != LiveState.OFF -> when (ls) {
                LiveState.ERROR -> OrbState.ERROR; LiveState.WORKING -> OrbState.EXECUTING
                LiveState.THINKING, LiveState.CONNECTING -> OrbState.THINKING; LiveState.SPEAKING -> OrbState.SPEAKING
                else -> OrbState.LISTENING
            }
            errorUntil > now -> OrbState.ERROR
            executing -> OrbState.EXECUTING
            busy -> OrbState.THINKING
            v == ZVoice.VState.SPEAKING -> OrbState.SPEAKING
            v == ZVoice.VState.LISTENING || v == ZVoice.VState.HEARING -> OrbState.LISTENING
            connectedUntil > now -> OrbState.CONNECTED
            else -> OrbState.IDLE
        }
        if (s != orb) {
            orb = s
            OverlayService.setBusy(s != OrbState.IDLE && s != OrbState.CONNECTED)
            OverlayService.setStatus(s.label)
            for (l in ArrayList(listeners)) l.stateChanged()
        }
    }

    fun level(v: Float) { for (l in ArrayList(listeners)) l.levelChanged(v) }
    private fun changed() { for (l in ArrayList(listeners)) l.messagesChanged() }
    private fun streamed() { for (l in ArrayList(listeners)) l.streamUpdated() }

    // ---------- messages ----------
    private fun add(role: String, text: String, meta: String = "", status: String = ""): UiMsg {
        val m = UiMsg(role, text, false, meta, status)
        msgs.add(m)
        if (role == "user" || role == "assistant") { val c = convo; io.execute { act.db.addMsg(c, role, text) } }
        else if (role == "task") { val c = convo; val packed = status + "\u001F" + meta + "\u001F" + text; io.execute { act.db.addMsg(c, "task", packed) } }
        changed()
        return m
    }

    /** Local, deterministic reply (not from the model). */
    private fun reply(text: String, speak: Boolean = true) {
        add("assistant", text, meta = Brand.COMPANION_NAME)
        refresh()
        if (speak && act.voice.sessionActive) act.voice.speak(text) else act.voice.resumeListening()
    }

    // ---------- input ----------
    fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (busy) stop()
        cancel = AtomicBoolean(false)
        AgentGuard.reset()
        add("user", text)
        // With an AI connected, the agent handles everything (it can already open apps, play media, operate screens…).
        // "Instant commands" is an opt-in shortcut for simple phrases; without an AI they are the only option.
        val instant = act.prefs.b("quick_commands", !Providers.primary(act, act.prefs).configured())
        when (val l = Commands.parse(text, ZaraAccessibilityService.instance != null)) {
            is Local.Remember -> remember(l.fact)
            is Local.Recall -> recall()
            is Local.Do -> if (instant) runAction(l.req) { afterTasks("", false) } else askAi()
            null -> askAi()
        }
    }

    private fun remember(fact: String) {
        if (!act.prefs.b("memory_on", true)) { reply("Memory is switched off. You can turn it on in Memory Core."); return }
        if (Commands.sensitive(fact)) { reply("I won't store that — passwords, keys, OTPs and card details are never saved as memory."); return }
        val cat = Commands.categorize(fact)
        val added = act.db.addMemory(fact, cat)
        reply(if (added) "Saved to memory under $cat. You can edit or delete it any time in Memory Core." else "I already have that saved.")
    }

    private fun recall() {
        val list = act.db.memories().take(30)
        reply(if (list.isEmpty()) "I don't have any saved memories yet." else "Here's what I have saved: " + list.joinToString("; ") { it.fact })
    }

    // ---------- AI: a real multi-step agent loop ----------
    private fun askAi() {
        val provider = Providers.primary(act, act.prefs)
        if (!provider.configured()) {
            reply("AI isn't connected yet. Open Settings → AI Connection and add a key. Simple commands like “open YouTube” work without one once App Control is on.", true)
            return
        }
        busy = true; refresh()
        val a = UiMsg("assistant", "", true, provider.label + " · " + provider.modelName)
        msgs.add(a); changed()

        val limit = act.prefs.i("context_limit", 16).coerceIn(4, 40)
        val convoMsgs = ArrayList<Msg>(HistoryBuilder.build(msgs.filter { it !== a }.map { HItem(it.role, it.text, it.status, it.meta) }, limit).map { Msg(it.first, it.second) })
        val mems = if (act.prefs.b("memory_on", true)) act.db.memories().take(30).map { it.fact } else emptyList()
        val system = Persona.system(act.prefs, mems, codeMode) +
            (if (act.prefs.b("app_control", false)) "\nTo operate the phone like a person: call get_screen to see numbered elements, act one step at a time, then get_screen again to verify before continuing. Stop and tell the user if a step is blocked, needs them, or was declined." else "")
        val tools = provider.id == "gemini" && act.prefs.b("app_control", false)
        streamRound(provider, system, convoMsgs, tools, a, cancel, 0)
    }

    private fun streamRound(provider: AiProvider, system: String, convoMsgs: MutableList<Msg>, tools: Boolean, a: UiMsg, token: AtomicBoolean, round: Int) {
        val roundStart = a.text.length
        net.execute {
            val res = provider.stream(system, convoMsgs, tools, token) { d -> main.post { if (!token.get()) { a.text += d; streamed() } } }
            main.post {
                if (token !== cancel) { a.streaming = false; msgs.remove(a); changed() }       // superseded by a newer request
                else if (res.ok && !res.cancelled && !token.get() && res.calls.isNotEmpty() && round < 10) {
                    convoMsgs.add(Msg("assistant", a.text.substring(minOf(roundStart, a.text.length)), res.calls))
                    collectCalls(res.calls) { responses ->
                        if (token !== cancel || token.get()) finishStream(a, AiResult(true, cancelled = true), provider, token, true)
                        else {
                            convoMsgs.add(Msg("tool", "", emptyList(), responses))
                            if (a.text.isNotBlank() && !a.text.endsWith("\n")) a.text += "\n\n"
                            streamRound(provider, system, convoMsgs, tools, a, token, round + 1)
                        }
                    }
                } else finishStream(a, res, provider, token, round > 0)
            }
        }
    }

    private fun finishStream(a: UiMsg, res: AiResult, provider: AiProvider, token: AtomicBoolean, hadCalls: Boolean) {
        a.streaming = false
        if (token !== cancel) { msgs.remove(a); changed(); return }   // a newer request replaced this one
        busy = false
        if (token.get()) { // user pressed stop
            if (a.text.isBlank()) msgs.remove(a) else { val c = convo; val t = a.text; io.execute { act.db.addMsg(c, "assistant", t) } }
            changed(); refresh(); return
        }
        if (!res.ok) {
            if (a.text.isBlank()) msgs.remove(a) else { val c = convo; val t = a.text; io.execute { act.db.addMsg(c, "assistant", t) } }
            add("error", res.error ?: "Something went wrong.")
            flashError(res.error ?: "AI error")
            act.voice.resumeListening()
            return
        }
        if (a.text.isBlank()) {
            if (hadCalls) msgs.remove(a) else a.text = "I didn't get a reply from ${provider.label}. Please try again."
        } else { val c = convo; val t = a.text; io.execute { act.db.addMsg(c, "assistant", t) } }
        changed()
        refresh()
        afterTasks(a.text, hadCalls)
    }

    private fun afterTasks(text: String, hadCalls: Boolean) {
        refresh()
        if (act.voice.sessionActive) {
            if (text.isNotBlank() && !hadCalls) act.voice.speak(text) else act.voice.resumeListening()
        }
    }

    // ---------- device actions ----------
    private fun resp(status: String, message: String, hint: String = "", screen: String = ""): String =
        JSONObject().put("status", status).put("message", message).also { if (hint.isNotBlank()) it.put("hint", hint); if (screen.isNotBlank()) it.put("screen", screen) }.toString()

    /** Runs the model's tool calls one at a time and returns a result for every call (the API requires one each). */
    private fun collectCalls(calls: List<ToolCall>, done: (List<Pair<String, String>>) -> Unit) {
        val out = ArrayList<Pair<String, String>>()
        var stopped = false
        fun next(i: Int) {
            if (i >= calls.size) { done(out); return }
            val c = calls[i]
            if (stopped) { out.add(c.name to resp("SKIPPED", "Not run because an earlier step did not complete.")); next(i + 1); return }
            val req = if (c.name == "device_action") act.executor.fromJson(c.args) else null
            if (req == null) { stopped = true; out.add(c.name to resp("FAILED", "Unknown or invalid action request.")); next(i + 1); return }
            runAction(req) { res ->
                out.add(c.name to resp(res.status.name, res.message, res.hint, res.data))
                if (res.status == TaskStatus.FAILED || res.status == TaskStatus.BLOCKED_BY_PERMISSION || res.declined) stopped = true
                next(i + 1)
            }
        }
        next(0)
    }

    fun runAction(req: ActionRequest, cb: (ActionResult) -> Unit) {
        runner.run(req, { title -> executing = true; refresh(); OverlayService.setStatus(title) }) { res, title ->
            executing = false; refresh()
            if (res.declined) { add("assistant", "Okay — I didn't do that.", meta = Brand.COMPANION_NAME); cb(res) }
            else finishTask(title, res, cb)
        }
    }

    private fun finishTask(title: String, res: ActionResult, cb: (ActionResult) -> Unit) {
        add("task", res.message + (if (res.hint.isNotBlank()) "\n" + res.hint else ""), meta = title, status = res.status.name)
        lastNote = title + " · " + res.status.name.replace('_', ' ').lowercase()
        if (res.status == TaskStatus.FAILED || res.status == TaskStatus.BLOCKED_BY_PERMISSION) flashError(lastNote)
        val silent = act.prefs.b("silent_mode", false)
        if (act.voice.sessionActive && !silent) act.voice.speak(res.message.substringBefore('\n').take(140)) {}
        cb(res)
    }

    fun shutdown() { LiveBus.listeners.remove(liveListener); cancel.set(true); net.shutdownNow(); io.shutdown() }
}
