package com.nshd.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Speech in/out. Listening is only active while a session is running (user tapped the mic).
 * Turn-taking is driven by real TTS completion events, not guessed delays.
 */
class ZVoice(private val ctx: Context, private val prefs: Prefs) : TextToSpeech.OnInitListener {
    enum class VState { IDLE, LISTENING, HEARING, PROCESSING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    var state = VState.IDLE; private set
    var sessionActive = false; private set
    var ttsNote = ""; private set

    var onState: (() -> Unit)? = null
    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String, Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLevel: ((Float) -> Unit)? = null
    var onSessionEnded: ((String) -> Unit)? = null

    private var tts: TextToSpeech? = TextToSpeech(ctx, this)
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var errorStreak = 0
    private var lastActivity = 0L
    private var pendingThen: (() -> Unit)? = null
    private var testCallback: ((String) -> Unit)? = null
    private var focus: AudioFocusRequest? = null
    private val am = ctx.getSystemService(AudioManager::class.java)

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            val limit = prefs.i("idle_timeout_s", 45) * 1000L
            if (state == VState.LISTENING && System.currentTimeMillis() - lastActivity > limit) {
                stopSession("Stopped listening after ${limit / 1000} seconds of silence.")
                return
            }
            main.postDelayed(this, 4000)
        }
    }

    fun micGranted() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun touch() { lastActivity = System.currentTimeMillis() }
    private fun setState(s: VState) { if (state != s) { state = s; onState?.invoke() } }

    /** Returns null on success, "need_permission", or a user-facing error. */
    fun startSession(): String? {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return "Speech recognition isn't available on this phone."
        if (!micGranted()) return "need_permission"
        sessionActive = true; errorStreak = 0; touch()
        main.removeCallbacks(idleCheck); main.postDelayed(idleCheck, 4000)
        listen()
        return null
    }

    fun stopSession(reason: String = "") {
        sessionActive = false
        testCallback = null
        main.removeCallbacks(idleCheck)
        cancelListening(); stopSpeaking()
        setState(VState.IDLE)
        onSessionEnded?.invoke(reason)
    }

    fun resumeListening() {
        if (state == VState.SPEAKING) return   // TTS completion resumes listening; avoids hearing ourselves
        if (sessionActive) { touch(); listen() } else if (state == VState.PROCESSING) setState(VState.IDLE)
    }

    /** Tap-to-interrupt while Zara is talking. */
    fun interrupt() { if (state == VState.SPEAKING) { stopSpeaking(); if (sessionActive) listen() } }

    /** One-shot recognition for the Voice Setup test button. */
    fun testOnce(cb: (String) -> Unit): String? {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return "Speech recognition isn't available on this phone."
        if (!micGranted()) return "need_permission"
        testCallback = cb; errorStreak = 0
        listen()
        return null
    }

    private fun intent(): Intent {
        val l = Langs.current(prefs)
        val tag = if (l.stt.isBlank()) Locale.getDefault().toLanguageTag() else l.stt
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Longer silence windows reduce clipped last words; the recognizer may ignore them on some devices.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, prefs.i("silence_complete_ms", 2000).toLong())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, prefs.i("silence_possible_ms", 1500).toLong())
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { setState(VState.LISTENING) }
        override fun onBeginningOfSpeech() { touch(); setState(VState.HEARING) }
        override fun onRmsChanged(rmsdB: Float) { onLevel?.invoke(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { setState(VState.PROCESSING) }
        override fun onError(error: Int) { handleError(error) }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!p.isNullOrBlank()) { touch(); onPartial?.invoke(p) }   // partials are display-only, never acted on
        }
        override fun onResults(results: Bundle?) {
            errorStreak = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
            val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
            val t = testCallback
            if (t != null) { testCallback = null; setState(VState.IDLE); t(text); return }
            if (text.isBlank()) { setState(VState.IDLE); if (sessionActive) main.postDelayed({ listen() }, 300); return }
            touch(); setState(VState.PROCESSING)
            onFinal?.invoke(text, conf)
        }
    }

    private fun listen() {
        if (!sessionActive && testCallback == null) return
        main.post {
            try {
                cancelListening()
                val r = SpeechRecognizer.createSpeechRecognizer(ctx)
                recognizer = r
                r.setRecognitionListener(listener)
                r.startListening(intent())
                setState(VState.LISTENING)
            } catch (_: Exception) { handleError(SpeechRecognizer.ERROR_CLIENT) }
        }
    }

    private fun cancelListening() {
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun handleError(code: Int) {
        val t = testCallback
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (t != null) { testCallback = null; setState(VState.IDLE); t("") }
                else if (sessionActive) { setState(VState.IDLE); main.postDelayed({ listen() }, 350) }
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                onError?.invoke("Microphone permission was turned off.")
                if (t != null) { testCallback = null; t("") }
                stopSession("Microphone permission was turned off.")
            }
            else -> {
                errorStreak++
                if (errorStreak >= 3 || t != null) {
                    val msg = when (code) {
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection right now."
                        SpeechRecognizer.ERROR_AUDIO -> "The microphone couldn't be used. Another app may be using it."
                        else -> "Speech recognition keeps failing on this device."
                    }
                    onError?.invoke(msg)
                    if (t != null) { testCallback = null; t("") }
                    stopSession(msg)
                } else main.postDelayed({ listen() }, 700)
            }
        }
    }

    // ---------------- speech output ----------------
    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) { ttsNote = "Text-to-speech isn't available on this phone."; return }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { finishSpeaking(true) } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { main.post { finishSpeaking(true) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { if (state == VState.SPEAKING) finishSpeaking(false) } }
        })
        applySettings()
    }

    fun applySettings() {
        val t = tts ?: return
        if (!ttsReady) return
        val l = Langs.current(prefs)
        val loc = if (l.tts.isBlank()) Locale.getDefault() else Locale.forLanguageTag(l.tts)
        val r = t.setLanguage(loc)
        ttsNote = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault())
            "No installed voice for ${loc.displayName}. Using the phone's default voice instead."
        } else ""
        val vn = prefs.s("tts_voice")
        if (vn.isNotBlank()) t.voices?.firstOrNull { it.name == vn }?.let { t.voice = it }
        t.setSpeechRate(prefs.i("tts_rate", 100) / 100f)
        t.setPitch(prefs.i("tts_pitch", 100) / 100f)
    }

    fun installedVoices(): List<Voice> {
        val all = try { tts?.voices?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val l = Langs.current(prefs)
        val lang = (if (l.tts.isBlank()) Locale.getDefault() else Locale.forLanguageTag(l.tts)).language
        val matching = all.filter { it.locale.language == lang }
        return (if (matching.isEmpty()) all else matching).sortedBy { it.name }
    }

    private fun speakable(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " (code omitted) ")
        .replace(Regex("https?://\\S+"), "link")
        .replace(Regex("[*_`#>]+"), "")
        .replace(Regex("\\s+"), " ").trim()

    fun speak(text: String, then: (() -> Unit)? = null) {
        val clean = speakable(text)
        if (!ttsReady || clean.isBlank()) { then?.invoke(); resumeListening(); return }
        cancelListening()
        pendingThen = then
        requestFocus()
        setState(VState.SPEAKING)
        tts?.speak(clean.take(3500), TextToSpeech.QUEUE_FLUSH, null, "zara-" + System.nanoTime())
    }

    fun stopSpeaking() {
        pendingThen = null
        try { tts?.stop() } catch (_: Exception) {}
        abandonFocus()
        if (state == VState.SPEAKING) setState(VState.IDLE)
    }

    private fun finishSpeaking(natural: Boolean) {
        abandonFocus()
        val cb = pendingThen; pendingThen = null
        setState(VState.IDLE)
        if (natural) { cb?.invoke(); if (sessionActive) { touch(); listen() } }
    }

    private fun requestFocus() {
        try {
            if (focus == null) focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            am.requestAudioFocus(focus!!)
        } catch (_: Exception) {}
    }
    private fun abandonFocus() { try { focus?.let { am.abandonAudioFocusRequest(it) } } catch (_: Exception) {} }

    fun shutdown() {
        sessionActive = false
        main.removeCallbacks(idleCheck)
        cancelListening()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }
}
