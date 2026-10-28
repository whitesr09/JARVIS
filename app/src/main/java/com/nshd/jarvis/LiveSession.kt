package com.nshd.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Voices offered for Live conversations (Google's names and style words). */
object LiveVoices {
    val all = listOf("Aoede" to "Breezy", "Puck" to "Upbeat", "Kore" to "Firm", "Charon" to "Informative", "Fenrir" to "Excitable",
        "Leda" to "Youthful", "Orus" to "Firm", "Zephyr" to "Bright", "Sulafat" to "Warm", "Achird" to "Friendly", "Gacrux" to "Mature", "Algieba" to "Smooth")
    fun label(id: String) = all.firstOrNull { it.first == id }?.let { it.first + " — " + it.second } ?: id
}

object LiveModels {
    const val DEFAULT = "gemini-3.1-flash-live-preview"
    /** Prefer the newest Live/native-audio model from the account's own model list. */
    fun pick(names: List<String>): String? =
        names.filter { it.contains("live", true) || it.contains("native-audio", true) }
            .sortedWith(compareByDescending<String> { it.contains("3.1") }.thenByDescending { it.contains("native-audio") }.thenByDescending { it }).firstOrNull()
}

/**
 * One continuous, full-duplex conversation with Gemini Live. The microphone stays open (no start/stop beeps),
 * model audio plays as it streams, the server's voice-activity detection provides barge-in, and tool calls are
 * routed through [AgentRunner] so the same safety rules apply as in text chat.
 */
class LiveSession(private val ctx: Context, private val prefs: Prefs, private val db: ZDb, private val runner: AgentRunner) {
    private val main = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    @Volatile var active = false; private set
    @Volatile private var ready = false
    private var ws: WsClient? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private val playQ = LinkedBlockingQueue<ByteArray>()
    @Volatile private var playing = false
    @Volatile private var lastWrite = 0L
    @Volatile private var epoch = 0                    // bumped on every (re)connect so old threads exit
    private var handle: String? = null
    private var retries = 0
    private var queryAuth = false
    private var triedModelDiscovery = false
    private var focus: AudioFocusRequest? = null
    private val userBuf = StringBuilder()
    private val botBuf = StringBuilder()
    private var newUserTurn = true
    @Volatile private var lastUserAt = 0L
    private val convo get() = prefs.i("active_convo", 1)
    private val pendingCalls = HashSet<String>()
    private val taskAgent = TaskAgent(ctx, prefs, runner)

    fun micGranted() = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Returns null on success or a user-facing problem. */
    fun start(): String? {
        if (active) return null
        if (!micGranted()) return "Microphone permission is needed."
        if (SecureStore.get(ctx, "gemini_key").isBlank()) return "Live conversation needs a Gemini API key (Settings → AI Connection)."
        active = true; retries = 0; queryAuth = false; handle = null; lastUserAt = System.currentTimeMillis()
        setAudioMode(true)
        connect()
        main.postDelayed(idleCheck, 30_000)
        return null
    }

    fun stop(note: String = "") {
        if (!active && LiveBus.state == LiveState.OFF) return
        active = false; epoch++
        main.removeCallbacks(idleCheck)
        try { ws?.sendText(LiveProtocol.audioStreamEnd()) } catch (_: Exception) {}
        try { ws?.close() } catch (_: Exception) {}
        ws = null; ready = false
        stopAudio(); setAudioMode(false)
        flushTranscripts()
        AgentGuard.cancel()
        LiveBus.state(LiveState.OFF, note)
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!active) return
            val limit = prefs.i("live_idle_min", 10) * 60_000L
            if (limit > 0 && !prefs.b("live_gating", false) && System.currentTimeMillis() - lastUserAt > limit) { stop("Live conversation ended after ${limit / 60000} minutes of silence."); return }
            main.postDelayed(this, 30_000)
        }
    }

    // ------------------------------------------------------------------ connection
    private fun connect() {
        val key = SecureStore.get(ctx, "gemini_key")
        val my = ++epoch
        ready = false
        LiveBus.state(LiveState.CONNECTING)
        val url = if (queryAuth) LiveProtocol.ENDPOINT + "?key=" + key else LiveProtocol.ENDPOINT
        val headers = if (queryAuth) emptyMap() else mapOf("x-goog-api-key" to key)
        val c = WsClient(url, headers, object : WsClient.Listener {
            override fun onOpen() { if (my == epoch) ws?.sendText(buildSetup()) }
            override fun onMessage(text: String) { if (my == epoch) handle(text) }
            override fun onClosed(code: Int, reason: String) { if (my == epoch) main.post { lost("closed $code $reason") } }
            override fun onError(t: Throwable) { if (my == epoch) main.post { lost(t.message ?: t.javaClass.simpleName) } }
        })
        ws = c
        c.connect()
    }

    private fun lost(why: String) {
        if (!active) return
        stopAudio(); ready = false
        if (!queryAuth && (why.contains("401") || why.contains("403") || why.contains("400 "))) { queryAuth = true; connect(); return }
        // A model that doesn't exist / isn't Live-capable: discover one from the account's own model list, once.
        if (!triedModelDiscovery && (why.contains("1007") || why.contains("1008") || why.contains("not found", true) || why.contains("not supported", true) || why.contains("404"))) {
            triedModelDiscovery = true
            Thread {
                val (names, _) = GeminiProvider(ctx, prefs).listModels("bidiGenerateContent")
                val m = LiveModels.pick(names)
                main.post { if (m != null && m != prefs.s("live_model")) { prefs.put("live_model", m); LiveBus.state(LiveState.CONNECTING, "Switching to $m"); connect() } else fail(why) }
            }.start(); return
        }
        if (++retries <= 5) { LiveBus.state(LiveState.CONNECTING, "Reconnecting…"); main.postDelayed({ if (active) connect() }, 1000L * retries) }
        else fail(why)
    }

    private fun fail(why: String) {
        val friendly = when {
            why.contains("401") || why.contains("403") -> "Google rejected the API key for Live conversations. Check the key and that Live API is available for it."
            why.contains("1008") || why.contains("not found", true) -> "The Live model “${prefs.s("live_model", LiveModels.DEFAULT)}” isn't available. Open Voice Setup and refresh the Live models."
            why.contains("429") || why.contains("quota", true) -> "Live API quota reached. Try again later or use the classic voice mode."
            else -> "Live connection lost ($why)."
        }
        active = false; epoch++
        stopAudio(); setAudioMode(false); main.removeCallbacks(idleCheck)
        LiveBus.state(LiveState.ERROR, friendly)
        main.postDelayed({ if (!active) LiveBus.state(LiveState.OFF) }, 4000)
        onFatal?.invoke(friendly)
    }
    var onFatal: ((String) -> Unit)? = null

    private fun buildSetup(): String {
        val mems = if (prefs.b("memory_on", true)) db.memories().take(30).map { it.fact } else emptyList()
        val gating = prefs.b("live_gating", false)
        val svcOn = ZaraAccessibilityService.instance != null
        var sys = Persona.system(prefs, mems, false) +
            "\nYou are in a LIVE voice conversation. Speak naturally and briefly, never use markdown or lists, and react to being interrupted gracefully.\n" +
            "You can control the user's phone. For ONE quick thing (open an app, play something in a named app, timer, alarm, web search, navigate, dial, draft a message, open a settings page, back/home) call phone_action. " +
            "For anything that needs several steps on the screen call do_task with one clear sentence containing every detail — it returns at once, so say a few words like “on it”, keep chatting, and you will later receive a SYSTEM UPDATE with the result to tell the user in one short sentence. " +
            "Never claim a phone action worked unless a tool result or SYSTEM UPDATE says so. If a result is BLOCKED_BY_PERMISSION, tell the user the exact setting from its hint. If the user says stop or cancel, call stop_task. " +
            "Never operate payment, banking or password apps, and never type passwords.\n" +
            "Phone-control status right now: app control " + (if (prefs.b("app_control", false)) "ON" else "OFF (user must enable “Allow Zara to act on my phone”)") +
            ", agent mode " + (if (prefs.b("agent_mode", false)) "ON" else "OFF") + ", accessibility " + (if (svcOn) "ON" else "OFF") + "."
        if (gating) sys += "\nAlways-listening mode: stay completely silent unless the user addresses you (for example “Hey ${Persona.name(prefs)}”) or is clearly continuing a conversation with you from the last ~30 seconds. Ignore TV, other people and background speech."
        val tools = Tools.liveDeclarations()
        return LiveProtocol.setup(prefs.s("live_model", LiveModels.DEFAULT).ifBlank { LiveModels.DEFAULT }, sys, prefs.s("live_voice", "Aoede"), tools, gating, handle)
    }

    // ------------------------------------------------------------------ incoming
    private fun handle(raw: String) {
        for (e in LiveProtocol.parse(raw)) when (e) {
            is LiveEvent.SetupComplete -> main.post { ready = true; retries = 0; startAudio(); LiveBus.state(LiveState.LISTENING) }
            is LiveEvent.Audio -> {
                if (!playing) { playing = true; LiveBus.state(LiveState.SPEAKING) }
                playQ.offer(e.pcm)
            }
            is LiveEvent.InputText -> main.post {
                lastUserAt = System.currentTimeMillis()
                if (newUserTurn) { newUserTurn = false; if (!taskAgent.running) AgentGuard.reset(); if (botBuf.isNotEmpty()) flushTranscripts() }
                userBuf.append(e.text); LiveBus.user(e.text)
                if (LiveBus.state == LiveState.LISTENING) LiveBus.state(LiveState.THINKING)
            }
            is LiveEvent.OutputText -> main.post { botBuf.append(e.text); LiveBus.assistant(e.text) }
            is LiveEvent.Interrupted -> main.post { clearPlayback(); LiveBus.state(LiveState.LISTENING) }
            is LiveEvent.TurnComplete -> main.post { flushTranscripts(); newUserTurn = true; LiveBus.turnComplete() }
            is LiveEvent.ToolCalls -> main.post { for (c in e.calls) onToolCall(c) }
            is LiveEvent.ToolCancel -> main.post { pendingCalls.removeAll(e.ids.toSet()) }
            is LiveEvent.GoAway -> main.post { if (active) { LiveBus.state(LiveState.CONNECTING, "Refreshing session…"); main.postDelayed({ if (active) { try { ws?.close() } catch (_: Exception) {}; connect() } }, 500) } }
            is LiveEvent.Resume -> if (e.resumable && e.handle != null) handle = e.handle
        }
    }

    private fun flushTranscripts() {
        val u = userBuf.toString().trim(); val b = botBuf.toString().trim()
        userBuf.setLength(0); botBuf.setLength(0)
        if (u.isEmpty() && b.isEmpty()) return
        val c = convo
        Thread { try { if (u.isNotEmpty()) db.addMsg(c, "user", u); if (b.isNotEmpty()) db.addMsg(c, "assistant", b) } catch (_: Exception) {} }.start()
    }

    private fun onToolCall(c: LiveCall) {
        pendingCalls.add(c.id)
        fun reply(status: String, message: String, hint: String = "", screen: String = "") {
            if (!pendingCalls.remove(c.id) || !active) return
            val r = JSONObject().put("status", status).put("message", message)
            if (hint.isNotBlank()) r.put("hint", hint)
            if (screen.isNotBlank()) r.put("screen", screen)
            ws?.sendText(LiveProtocol.toolResponse(c.id, c.name, r))
            if (active && LiveBus.state == LiveState.WORKING && !taskAgent.running) LiveBus.state(if (playing) LiveState.SPEAKING else LiveState.THINKING)
        }
        when (c.name) {
            "phone_action" -> {
                val parsed = runner.executor.fromJson(c.args, Tools.QUICK)
                if (parsed == null) { reply("FAILED", "That isn't a quick action. Use do_task for multi-step jobs."); return }
                val req = parsed.copy(extra = parsed.extra + ("observe" to "false"))    // the voice model never needs the screen text
                LiveBus.state(LiveState.WORKING, runner.executor.title(req))
                runner.run(req) { res, title ->
                    LiveBus.task(title, res)
                    reply(res.status.name, res.message, res.hint)
                }
            }
            "do_task" -> {
                val goal = c.args.optString("goal").ifBlank { c.args.optString("text") }.trim()
                when {
                    goal.isEmpty() -> reply("FAILED", "No goal was given.")
                    !prefs.b("app_control", false) -> reply("BLOCKED_BY_PERMISSION", "App control is off.", "Turn on “Allow Zara to act on my phone” in Settings → App Control & Hands-Free.")
                    taskAgent.running -> reply("BUSY", "A phone task is already running. Wait for its update, or call stop_task.")
                    else -> {
                        reply("STARTED", "Started. Tell the user in a few words that you're on it. You will get a SYSTEM UPDATE with the result — don't wait or ask questions meanwhile.")
                        LiveBus.state(LiveState.WORKING, "Working: " + goal.take(28))
                        taskAgent.run(goal, { step -> if (active) LiveBus.state(LiveState.WORKING, step) }) { out ->
                            if (active) {
                                ws?.sendText(LiveProtocol.text("[SYSTEM UPDATE — not the user speaking] The phone task \"" + goal.take(80) + "\" " +
                                    (if (out.ok) "finished" else "did NOT finish") + " after " + out.steps + " steps. Result: " + out.summary + " — tell the user in one short sentence."))
                                LiveBus.state(if (playing) LiveState.SPEAKING else LiveState.THINKING)
                            }
                        }
                    }
                }
            }
            "stop_task" -> { AgentGuard.cancel(); reply("COMPLETED", if (taskAgent.running) "Stopping the phone task." else "No phone task was running.") }
            else -> reply("FAILED", "Unknown tool.")
        }
    }

    // ------------------------------------------------------------------ audio
    private fun headsetConnected(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    @Suppress("DEPRECATION")
    private fun setAudioMode(on: Boolean) {
        try {
            if (on) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = !headsetConnected()
                focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
                am.requestAudioFocus(focus!!)
            } else {
                am.isSpeakerphoneOn = false; am.mode = AudioManager.MODE_NORMAL
                focus?.let { am.abandonAudioFocusRequest(it) }; focus = null
            }
        } catch (_: Exception) {}
    }

    private fun startAudio() {
        stopAudio()
        val my = epoch
        val minRec = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minRec, 3200 * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); fail("microphone unavailable (another app may be using it)"); return }
        try { if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true } } } catch (_: Exception) {}
        try { if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true } } } catch (_: Exception) {}
        record = rec
        val guard = prefs.b("echo_guard", aec == null)   // no hardware echo cancellation → don't upload while Zara is talking
        rec.startRecording()
        Thread({
            val buf = ByteArray(3200)
            while (active && my == epoch) {
                var off = 0
                while (off < buf.size && active && my == epoch) { val n = rec.read(buf, off, buf.size - off); if (n <= 0) break; off += n }
                if (off < buf.size) break
                var sum = 0.0
                var i = 0; while (i < buf.size) { val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt(); sum += s.toDouble() * s; i += 2 }
                val rms = sqrt(sum / (buf.size / 2)) / 32768.0
                LiveBus.level(min(1.0, rms * 6).toFloat())
                if (guard && playing) continue
                ws?.sendText(LiveProtocol.audioChunk(buf, buf.size))
            }
        }, "live-mic").apply { isDaemon = true; start() }

        val minTrk = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(minTrk, 24000)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t; t.play()
        Thread({
            while (active && my == epoch) {
                val b = try { playQ.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
                if (b == null) {
                    if (playing && System.currentTimeMillis() - lastWrite > 450) { playing = false; main.post { if (active && LiveBus.state == LiveState.SPEAKING) LiveBus.state(LiveState.LISTENING) } }
                    continue
                }
                playing = true; lastWrite = System.currentTimeMillis()
                var off = 0
                while (off < b.size && active && my == epoch) { val n = t.write(b, off, b.size - off); if (n <= 0) break; off += n }
                lastWrite = System.currentTimeMillis()
                var sum = 0.0; var i = 0; while (i + 1 < b.size) { val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt(); sum += s.toDouble() * s; i += 2 }
                LiveBus.level(min(1.0, sqrt(sum / max(1, b.size / 2)) / 32768.0 * 5).toFloat())
            }
        }, "live-play").apply { isDaemon = true; start() }
    }

    private fun clearPlayback() {
        playQ.clear(); playing = false
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
    }

    private fun stopAudio() {
        playQ.clear(); playing = false
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { aec?.release(); ns?.release() } catch (_: Exception) {}
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
        record = null; track = null; aec = null; ns = null
    }
}
