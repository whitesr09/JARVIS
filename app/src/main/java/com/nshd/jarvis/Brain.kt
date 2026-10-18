package com.nshd.jarvis

import android.os.Handler
import android.os.Looper
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

    init { loadActive() }

    private fun loadActive() {
        msgs.clear()
        for ((r, t) in act.db.messages(convo)) msgs.add(UiMsg(if (r == "assistant") "assistant" else "user", t))
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
        val s = when {
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
        add("user", text)
        when (val l = Commands.parse(text, ZaraAccessibilityService.instance != null)) {
            is Local.Remember -> remember(l.fact)
            is Local.Recall -> recall()
            is Local.Do -> runAction(l.req) { afterTasks("", false) }
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

    // ---------- AI ----------
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
        val history = msgs.filter { (it.role == "user" || it.role == "assistant") && it !== a && it.text.isNotBlank() }
            .takeLast(limit).map { Msg(it.role, it.text) }
        val mems = if (act.prefs.b("memory_on", true)) act.db.memories().take(30).map { it.fact } else emptyList()
        val system = Persona.system(act.prefs, mems, codeMode)
        val tools = provider.id == "gemini" && act.prefs.b("app_control", false)
        val token = cancel

        net.execute {
            val res = provider.stream(system, history, tools, token) { d ->
                main.post { if (!token.get()) { a.text += d; streamed() } }
            }
            main.post { if (!token.get() || res.cancelled) finishStream(a, res, provider, token) }
        }
    }

    private fun finishStream(a: UiMsg, res: AiResult, provider: AiProvider, token: AtomicBoolean) {
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
        if (a.text.isBlank() && res.calls.isEmpty()) {
            a.text = "I didn't get a reply from ${provider.label}. Please try again."
        } else if (a.text.isNotBlank()) {
            val c = convo; val t = a.text; io.execute { act.db.addMsg(c, "assistant", t) }
        } else msgs.remove(a)
        changed()
        refresh()
        val text = a.text
        if (res.calls.isNotEmpty()) runCalls(res.calls, 0) { afterTasks(text, true) } else afterTasks(text, false)
    }

    private fun afterTasks(text: String, hadCalls: Boolean) {
        refresh()
        val silent = act.prefs.b("silent_mode", false)
        if (act.voice.sessionActive) {
            if (text.isNotBlank() && !hadCalls) act.voice.speak(text) else act.voice.resumeListening()
        }
    }

    // ---------- device actions ----------
    private fun runCalls(calls: List<ToolCall>, i: Int, done: () -> Unit) {
        if (i >= calls.size) { done(); return }
        val req = if (calls[i].name == "device_action") act.executor.fromJson(calls[i].args) else null
        if (req == null) {
            logTask("Zara", "Unrecognised action request", ActionResult(TaskStatus.FAILED, "I got an action request I don't support, so I ignored it."))
            done(); return
        }
        runAction(req) { res -> if (res.status == TaskStatus.COMPLETED) runCalls(calls, i + 1, done) else done() }
    }

    fun runAction(req: ActionRequest, cb: (ActionResult) -> Unit) {
        val pre = act.executor.preflight(req)
        pre.blocked?.let { finishTask(pre.title, it, cb); return }
        fun go() {
            executing = true; refresh()
            OverlayService.setStatus(pre.title)
            act.executor.execute(req) { r -> executing = false; refresh(); finishTask(pre.title, r, cb) }
        }
        val needConfirm = pre.risk == Risk.CONSEQUENTIAL || !act.prefs.b("auto_low", false)
        if (needConfirm) {
            act.confirm(if (pre.risk == Risk.CONSEQUENTIAL) "Please confirm" else "Allow this action?", pre.preview, "Allow", { go() }) {
                add("assistant", "Okay — I didn't do that.", meta = Brand.COMPANION_NAME)
                cb(ActionResult(TaskStatus.FAILED, "Declined by you.", declined = true))
            }
        } else go()
    }

    private fun finishTask(title: String, res: ActionResult, cb: (ActionResult) -> Unit) {
        logTask(title, title, res)
        add("task", res.message + (if (res.hint.isNotBlank()) "\n" + res.hint else ""), meta = title, status = res.status.name)
        lastNote = title + " · " + res.status.name.replace('_', ' ').lowercase()
        if (res.status == TaskStatus.FAILED || res.status == TaskStatus.BLOCKED_BY_PERMISSION) flashError(lastNote)
        val silent = act.prefs.b("silent_mode", false)
        if (act.voice.sessionActive && !silent) act.voice.speak(res.message.substringBefore('\n').take(140)) {}
        cb(res)
    }

    private fun logTask(title: String, summary: String, res: ActionResult) {
        io.execute { act.db.logTask(title, summary, res.status.name, res.hint.ifBlank { res.message.take(120) }) }
    }

    fun shutdown() { cancel.set(true); net.shutdownNow(); io.shutdown() }
}
