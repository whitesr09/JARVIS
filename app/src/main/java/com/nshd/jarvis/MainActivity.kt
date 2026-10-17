package com.nshd.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.Gravity
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val cyan = Color.rgb(0, 220, 255)
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var transcript: TextView
    private lateinit var status: TextView
    private lateinit var entry: EditText
    private lateinit var voice: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var voiceReady = false
    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }
    private val db by lazy { openOrCreateDatabase("jarvis.db", MODE_PRIVATE, null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE
        db.execSQL("CREATE TABLE IF NOT EXISTS chat(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, at INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memories(id INTEGER PRIMARY KEY AUTOINCREMENT, fact TEXT UNIQUE)")
        voice = TextToSpeech(this, this)
        buildUi()
        add("JARVIS", "Systems online. Tap START SESSION and talk naturally; you do not need to say Jarvis before each command.")
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 18)
            setBackgroundColor(Color.BLACK)
        }
        val title = TextView(this).apply {
            text = "J A R V I S"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setPadding(0, 12, 0, 4)
        }
        status = TextView(this).apply {
            text = "● IDLE · READY"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, 4, 0, 16)
        }
        transcript = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(230, 240, 245))
            setPadding(12, 12, 12, 12)
        }
        val scroller = ScrollView(this).apply {
            addView(transcript)
            setBackgroundColor(Color.rgb(10, 16, 23))
        }
        entry = EditText(this).apply {
            hint = "Type a message…"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(false)
            setBackgroundColor(Color.rgb(18, 25, 34))
        }
        val send = button("SEND") { val text = entry.text.toString().trim(); if (text.isNotEmpty()) { entry.setText(""); handle(text) } }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(entry, LinearLayout.LayoutParams(0, -2, 1f)); addView(send) }
        root.addView(title)
        root.addView(status)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row)
        root.addView(button("START SESSION") { startListening() })
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("API KEY") { keyDialog() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("MEMORY") { memoryDialog() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("STOP") { stopListening() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(tools)
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(cyan)
        setBackgroundColor(Color.rgb(12, 22, 31))
        setOnClickListener { action() }
    }

    private fun add(who: String, text: String) {
        transcript.append("\n$who\n$text\n")
        transcript.post { (transcript.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun handle(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        add("YOU", text)
        db.execSQL("INSERT INTO chat(role,text,at) VALUES(?,?,?)", arrayOf("user", text, System.currentTimeMillis()))
        val remember = Regex("(?i)^remember that (.+)$").find(text)
        if (remember != null) {
            val fact = remember.groupValues[1].trim()
            db.execSQL("INSERT OR IGNORE INTO memories(fact) VALUES(?)", arrayOf(fact))
            reply("Got it. I saved that in local memory.")
            return
        }
        if (text.equals("what do you remember", true) || text.equals("what do you remember?", true)) {
            val c = db.rawQuery("SELECT fact FROM memories ORDER BY id DESC LIMIT 30", null)
            val list = mutableListOf<String>()
            while (c.moveToNext()) list.add(c.getString(0))
            c.close()
            reply(if (list.isEmpty()) "I have no saved memories yet." else "Here's what I remember: " + list.joinToString("; "))
            return
        }
        val action = Regex("(?i)^(open|launch|start)\\s+(youtube|chrome|maps|gmail|whatsapp|settings)$").find(text)
        if (action != null) {
            val name = action.groupValues[2].lowercase(Locale.ROOT)
            try {
                if (name == "settings") startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                else {
                    val pkg = mapOf("youtube" to "com.google.android.youtube", "chrome" to "com.android.chrome", "maps" to "com.google.android.apps.maps", "gmail" to "com.google.android.gm", "whatsapp" to "com.whatsapp")[name]!!
                    val intent = packageManager.getLaunchIntentForPackage(pkg)
                    if (intent == null) { reply("$name does not appear to be installed."); return }
                    startActivity(intent)
                }
                reply("Opening $name.")
            } catch (_: Exception) { reply("I couldn't open $name on this device.") }
            return
        }
        askAI(text)
    }

    private fun askAI(prompt: String) {
        val key = prefs.getString("api_key", "").orEmpty()
        if (key.isBlank()) { reply("AI isn't connected yet. Tap API KEY and add your Gemini API key."); return }
        status.text = "● THINKING"
        val history = JSONArray()
        val c = db.rawQuery("SELECT role,text FROM chat ORDER BY id DESC LIMIT 12", null)
        val rows = mutableListOf<Pair<String,String>>()
        while (c.moveToNext()) rows.add(c.getString(0) to c.getString(1))
        c.close()
        rows.reverse()
        for ((role, content) in rows) history.put(JSONObject().put("role", if (role == "assistant") "model" else "user").put("parts", JSONArray().put(JSONObject().put("text", content))))
        val mem = JSONArray()
        val mc = db.rawQuery("SELECT fact FROM memories ORDER BY id DESC LIMIT 30", null)
        while (mc.moveToNext()) mem.put(mc.getString(0))
        mc.close()
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "You are JARVIS, a natural, concise Android voice assistant. Remember context and follow-up questions. Never claim actions you did not perform. Saved user memories: $mem"))))
            .put("contents", history)
            .put("generationConfig", JSONObject().put("temperature", 0.65).put("maxOutputTokens", 700))
        executor.execute {
            val answer = try {
                val model = prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=" + URLEncoder.encode(key, "UTF-8"))
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 20000; readTimeout = 45000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val rawResponse = stream.bufferedReader().use { it.readText() }
                conn.disconnect()
                if (code !in 200..299) "AI API error $code. Check your key, model, quota, and internet." else JSONObject(rawResponse).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty().ifBlank { "I didn't receive a response. Please try again." }
            } catch (_: Exception) { "I couldn't reach the AI service. Check internet and API settings." }
            runOnUiThread { reply(answer) }
        }
    }

    private fun reply(text: String) {
        add("JARVIS", text)
        db.execSQL("INSERT INTO chat(role,text,at) VALUES(?,?,?)", arrayOf("assistant", text, System.currentTimeMillis()))
        status.text = if (listening) "● LISTENING" else "● IDLE · READY"
        if (voiceReady) voice.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-reply")
        // Resume recognition after the spoken reply so follow-up questions do not need a wake word.
        if (listening) root.postDelayed({ if (listening) listenOnce() }, (text.length * 48L).coerceIn(1200L, 12000L))
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { reply("Speech recognition isn't available on this phone."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 501); return
        }
        listening = true
        listenOnce()
    }

    private fun listenOnce() {
        if (!listening) return
        try {
            if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "● LISTENING · SPEAK NOW" }
                override fun onBeginningOfSpeech() { status.text = "● HEARING YOU" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "● PROCESSING SPEECH" }
                override fun onError(error: Int) {
                    if (listening) root.postDelayed({ listenOnce() }, 500)
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) handle(text) else if (listening) root.postDelayed({ listenOnce() }, 300)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) status.text = "● HEARING: $partial"
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) { status.text = "● SPEECH SERVICE ERROR" }
    }

    private fun stopListening() {
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        status.text = "● IDLE · READY"
    }

    private fun keyDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 4) }
        val key = EditText(this).apply { hint = "Gemini API key"; setText(prefs.getString("api_key", "")); inputType = 129 }
        val model = EditText(this).apply { hint = "Model name"; setText(prefs.getString("model", "gemini-2.5-flash")) }
        box.addView(key); box.addView(model)
        android.app.AlertDialog.Builder(this).setTitle("AI connection")
            .setMessage("The key stays in this app's private preferences. Requests are sent to Google; don't share the key.")
            .setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> prefs.edit().putString("api_key", key.text.toString().trim()).putString("model", model.text.toString().trim().ifBlank { "gemini-2.5-flash" }).apply(); add("JARVIS", "AI settings saved on this device.") }.show()
    }

    private fun memoryDialog() {
        val c = db.rawQuery("SELECT fact FROM memories ORDER BY id DESC", null)
        val facts = mutableListOf<String>()
        while (c.moveToNext()) facts.add("• " + c.getString(0))
        c.close()
        android.app.AlertDialog.Builder(this).setTitle("Local memory")
            .setMessage(if (facts.isEmpty()) "No memories saved yet. Say: Remember that I prefer short answers." else facts.joinToString("\n"))
            .setPositiveButton("OK", null).setNeutralButton("Clear all") { _, _ -> db.execSQL("DELETE FROM memories"); add("JARVIS", "Saved memories cleared.") }.show()
    }

    override fun onInit(status: Int) {
        voiceReady = status == TextToSpeech.SUCCESS
        if (voiceReady) voice.language = Locale.getDefault()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 501 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else if (requestCode == 501) add("JARVIS", "Microphone permission is needed for voice input.")
    }

    override fun onDestroy() {
        listening = false
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { if (::voice.isInitialized) voice.shutdown() } catch (_: Exception) {}
        try { db.close() } catch (_: Exception) {}
        executor.shutdownNow()
        super.onDestroy()
    }
}
