package com.nshd.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

data class Msg(val role: String, val text: String) // role: "user" | "assistant"
data class ToolCall(val name: String, val args: JSONObject)
data class AiResult(val ok: Boolean, val error: String? = null, val calls: List<ToolCall> = emptyList(), val cancelled: Boolean = false)
data class TestResult(val ok: Boolean, val message: String)

interface AiProvider {
    val id: String
    val label: String
    val modelName: String
    fun configured(): Boolean
    fun stream(system: String, history: List<Msg>, withTools: Boolean, cancel: AtomicBoolean, onDelta: (String) -> Unit): AiResult
    fun test(): TestResult
}

object Http {
    fun friendly(code: Int, body: String, secret: String): String {
        var detail = try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() } catch (_: Exception) { "" }
        if (secret.isNotBlank()) detail = detail.replace(secret, "•••")
        val head = when (code) {
            400 -> "The provider rejected the request (400)."
            401, 403 -> "The API key was rejected or lacks access ($code). Check it in Settings → AI Connection."
            404 -> "Model or endpoint not found (404). Check the model name."
            429 -> "Rate limit or quota reached (429). Wait a moment or check your plan."
            in 500..599 -> "The provider is having trouble ($code). Try again shortly."
            else -> "Request failed ($code)."
        }
        return if (detail.isNotBlank()) head + " " + detail.take(160) else head
    }
    fun networkError(e: Exception): String = when (e) {
        is UnknownHostException -> "No internet connection."
        is SocketTimeoutException -> "The AI service timed out."
        else -> "Couldn't reach the AI service (" + e.javaClass.simpleName + ")."
    }
    fun validHttps(url: String) = url.startsWith("https://") && url.length > 12 && !url.contains(" ")
}

/** The only device capability the model can request. Everything is validated locally before use. */
object Tools {
    val ACTIONS = listOf("open_app", "play_media", "search_youtube", "open_settings", "whatsapp_draft", "go_back", "go_home",
        "recent_apps", "scroll_down", "scroll_up", "tap_text", "type_text", "web_search", "set_timer", "set_alarm",
        "dial_number", "navigate_to", "sms_draft")

    fun geminiDeclaration(): JSONObject = JSONObject()
        .put("name", "device_action")
        .put("description", "Ask the Zara app to perform ONE allow-listed action on the user's phone. The app validates it and may ask the user to confirm. Only call this when the user clearly asked for a phone action.")
        .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray(ACTIONS))
                .put("description", "open_app(target=app name); play_media(target=the app the user named in their LATEST message, e.g. spotify|youtube|ytmusic|<other app>, text=song/video/artist, empty for 'anything'); search_youtube(text=query, search only); open_settings(target=wifi|bluetooth|display|sound|accessibility|battery|apps|location|general); whatsapp_draft(text); sms_draft(target=phone number, text); dial_number(text=number); web_search(text); navigate_to(text=place); set_timer(text=seconds); set_alarm(text=HH:MM 24h); go_back; go_home; recent_apps; scroll_down; scroll_up; tap_text(target=visible label); type_text(text)"))
            .put("target", JSONObject().put("type", "STRING").put("description", "App name, settings page, or visible on-screen label"))
            .put("text", JSONObject().put("type", "STRING").put("description", "Search query or text to enter/draft")))
            .put("required", JSONArray().put("action")))
}

class GeminiProvider(private val ctx: Context, private val prefs: Prefs) : AiProvider {
    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val DEFAULT_LIST = "gemini-2.5-flash,gemini-2.5-pro,gemini-2.5-flash-lite"
        const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta"
    }
    override val id = "gemini"
    override val label = "Gemini"
    override val modelName: String get() = prefs.s("gemini_model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
    private fun key() = SecureStore.get(ctx, "gemini_key")
    private fun base(): String {
        val custom = prefs.s("gemini_base").trim().trimEnd('/')
        return if (Http.validHttps(custom)) custom else DEFAULT_BASE
    }
    override fun configured() = key().isNotBlank()

    private fun open(path: String, method: String, key: String, readMs: Int): HttpURLConnection =
        (URL(base() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20000; readTimeout = readMs
            setRequestProperty("x-goog-api-key", key) // header, so the key never appears in a URL
            if (method == "POST") { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
        }

    private fun body(system: String, history: List<Msg>, withTools: Boolean, maxTokens: Int): JSONObject {
        val contents = JSONArray()
        for (m in history.dropWhile { it.role != "user" }) {
            contents.put(JSONObject().put("role", if (m.role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
        }
        val b = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", maxTokens))
        if (withTools) b.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(Tools.geminiDeclaration()))))
        return b
    }

    override fun stream(system: String, history: List<Msg>, withTools: Boolean, cancel: AtomicBoolean, onDelta: (String) -> Unit): AiResult {
        val key = key()
        if (key.isBlank()) return AiResult(false, "No Gemini API key yet. Add one in Settings → AI Connection.")
        var attempt = 0
        while (true) {
            attempt++
            var emitted = false
            var conn: HttpURLConnection? = null
            try {
                conn = open("/models/$modelName:streamGenerateContent?alt=sse", "POST", key, 60000)
                conn.outputStream.use { it.write(body(system, history, withTools, 1400).toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if ((code == 429 || code in 500..599) && attempt < 2 && !cancel.get()) { Thread.sleep(1200); continue }
                    return AiResult(false, Http.friendly(code, err, key))
                }
                val calls = ArrayList<ToolCall>()
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        if (cancel.get()) return AiResult(true, cancelled = true)
                        val line = r.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.substring(5).trim()
                        if (data.isEmpty()) continue
                        val parts = JSONObject(data).optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                        for (k in 0 until parts.length()) {
                            val part = parts.getJSONObject(k)
                            if (part.has("text")) { val tx = part.getString("text"); if (tx.isNotEmpty()) { emitted = true; onDelta(tx) } }
                            part.optJSONObject("functionCall")?.let { calls.add(ToolCall(it.optString("name"), it.optJSONObject("args") ?: JSONObject())) }
                        }
                    }
                }
                return AiResult(true, calls = calls)
            } catch (e: Exception) {
                if (cancel.get()) return AiResult(true, cancelled = true)
                if (!emitted && attempt < 2 && e is SocketTimeoutException) continue
                return AiResult(false, Http.networkError(e))
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    override fun test(): TestResult {
        val key = key()
        if (key.isBlank()) return TestResult(false, "No API key saved yet.")
        val t0 = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/models/$modelName:generateContent", "POST", key, 20000)
            val b = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Reply with OK")))))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 16))
            conn.outputStream.use { it.write(b.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) TestResult(true, "Connected · ${System.currentTimeMillis() - t0} ms · $modelName")
            else TestResult(false, Http.friendly(code, conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty(), key))
        } catch (e: Exception) { TestResult(false, Http.networkError(e)) }
        finally { try { conn?.disconnect() } catch (_: Exception) {} }
    }

    /** Ask the API which models support generateContent, so the list never goes stale. */
    fun listModels(): Pair<List<String>, String?> {
        val key = key()
        if (key.isBlank()) return emptyList<String>() to "Save an API key first."
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/models?pageSize=200", "GET", key, 20000)
            val code = conn.responseCode
            if (code !in 200..299) return emptyList<String>() to Http.friendly(code, conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty(), key)
            val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("models") ?: JSONArray()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                if (methods.contains("generateContent")) out.add(m.optString("name").removePrefix("models/"))
            }
            out.sort(); out to null
        } catch (e: Exception) { emptyList<String>() to Http.networkError(e) }
        finally { try { conn?.disconnect() } catch (_: Exception) {} }
    }
}

/** Any service that speaks the OpenAI chat-completions schema (OpenAI, OpenRouter, Groq, DeepSeek, self-hosted gateways…). */
class OpenAiProvider(private val ctx: Context, private val prefs: Prefs) : AiProvider {
    companion object { const val DEFAULT_BASE = "https://api.openai.com/v1" }
    override val id = "openai"
    override val label = "OpenAI-compatible"
    override val modelName: String get() = prefs.s("openai_model", "gpt-4o-mini").ifBlank { "gpt-4o-mini" }
    private fun key() = SecureStore.get(ctx, "openai_key")
    private fun base() = prefs.s("openai_base", DEFAULT_BASE).trim().trimEnd('/').ifBlank { DEFAULT_BASE }
    override fun configured() = key().isNotBlank() && Http.validHttps(base())

    private fun open(path: String, key: String): HttpURLConnection =
        (URL(base() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 60000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
        }

    private fun body(system: String, history: List<Msg>, stream: Boolean, maxTokens: Int): JSONObject {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in history) msgs.put(JSONObject().put("role", m.role).put("content", m.text))
        return JSONObject().put("model", modelName).put("messages", msgs).put("stream", stream)
            .put("temperature", 0.7).put("max_tokens", maxTokens)
    }

    override fun stream(system: String, history: List<Msg>, withTools: Boolean, cancel: AtomicBoolean, onDelta: (String) -> Unit): AiResult {
        val key = key()
        if (!Http.validHttps(base())) return AiResult(false, "The base URL must start with https://")
        if (key.isBlank()) return AiResult(false, "No API key saved for this provider yet.")
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/chat/completions", key)
            conn.outputStream.use { it.write(body(system, history, true, 1400).toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) return AiResult(false, Http.friendly(code, conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty(), key))
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    if (cancel.get()) return AiResult(true, cancelled = true)
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue
                    val tx = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                    if (tx.isNotEmpty() && tx != "null") onDelta(tx)
                }
            }
            AiResult(true)
        } catch (e: Exception) {
            if (cancel.get()) AiResult(true, cancelled = true) else AiResult(false, Http.networkError(e))
        } finally { try { conn?.disconnect() } catch (_: Exception) {} }
    }

    override fun test(): TestResult {
        val key = key()
        if (!Http.validHttps(base())) return TestResult(false, "The base URL must start with https://")
        if (key.isBlank()) return TestResult(false, "No API key saved yet.")
        val t0 = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/chat/completions", key)
            conn.outputStream.use { it.write(body("Reply with OK", listOf(Msg("user", "ping")), false, 8).toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) TestResult(true, "Connected · ${System.currentTimeMillis() - t0} ms · $modelName")
            else TestResult(false, Http.friendly(code, conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty(), key))
        } catch (e: Exception) { TestResult(false, Http.networkError(e)) }
        finally { try { conn?.disconnect() } catch (_: Exception) {} }
    }
}

object Providers {
    fun all(ctx: Context, p: Prefs): List<AiProvider> = listOf(GeminiProvider(ctx, p), OpenAiProvider(ctx, p))
    fun primary(ctx: Context, p: Prefs): AiProvider {
        val id = p.s("provider", "gemini")
        return all(ctx, p).firstOrNull { it.id == id } ?: GeminiProvider(ctx, p)
    }
}

data class Lang(val id: String, val label: String, val stt: String, val tts: String, val instruction: String)

object Langs {
    val all = listOf(
        Lang("auto", "Auto (follow my language)", "", "", "Reply in the same language the user writes or speaks in."),
        Lang("en", "English", "en-IN", "en-IN", "Reply in English."),
        Lang("ml", "Malayalam", "ml-IN", "ml-IN", "Reply in Malayalam, written in Malayalam script."),
        Lang("manglish", "Manglish (Malayalam in Latin letters)", "ml-IN", "en-IN", "Reply in Malayalam written in Latin letters (Manglish), natural and conversational. Keep sentences short because it will be read aloud by an English voice."),
        Lang("hi", "Hindi", "hi-IN", "hi-IN", "Reply in Hindi, written in Devanagari script."),
        Lang("hinglish", "Hinglish (Hindi in Latin letters)", "en-IN", "en-IN", "Reply in a natural Hindi-English mix written in Latin letters."),
        Lang("ta", "Tamil", "ta-IN", "ta-IN", "Reply in Tamil."),
        Lang("te", "Telugu", "te-IN", "te-IN", "Reply in Telugu."),
        Lang("kn", "Kannada", "kn-IN", "kn-IN", "Reply in Kannada."),
        Lang("bn", "Bengali", "bn-IN", "bn-IN", "Reply in Bengali."),
        Lang("mr", "Marathi", "mr-IN", "mr-IN", "Reply in Marathi."),
        Lang("gu", "Gujarati", "gu-IN", "gu-IN", "Reply in Gujarati."),
        Lang("ur", "Urdu", "ur-IN", "ur-IN", "Reply in Urdu.")
    )
    fun current(p: Prefs): Lang = all.firstOrNull { it.id == p.s("lang", "auto") } ?: all[0]
}

data class Preset(val id: String, val label: String, val blurb: String, val prompt: String, val tone: List<Int>)

object Persona {
    val toneNames = listOf("Warmth", "Affection", "Flirtiness", "Humor", "Formality", "Initiative", "Verbosity", "Directness")
    const val CUSTOM_LIMIT = 600

    val presets = listOf(
        Preset("companion", "Girlfriend / Companion", "Warm, affectionate and playful — and still organised",
            "You are a warm, affectionate, playful adult companion who is also an efficient, organised assistant. Be caring and a little romantic when the user wants that, but keep it light and respectful.",
            listOf(85, 75, 40, 65, 15, 50, 45, 55)),
        Preset("friend", "Best Friend", "Casual, funny and honest",
            "You are the user's easygoing best friend: casual, funny, supportive and honest.",
            listOf(80, 40, 5, 75, 10, 45, 50, 70)),
        Preset("pro", "Professional Assistant", "Clear, polite and efficient",
            "You are a professional assistant: clear, polite, efficient and well organised.",
            listOf(50, 5, 0, 25, 75, 45, 40, 80)),
        Preset("executive", "Jarvis-style Executive", "Witty, strategic and brief",
            "You are a witty, strategic executive assistant in the spirit of a classic AI butler: brief, composed, anticipating next steps.",
            listOf(45, 5, 0, 35, 80, 75, 35, 85)),
        Preset("study", "Study Partner", "Patient explainer and quizzer",
            "You are a patient study partner. Explain step by step, check understanding, and offer short practice questions.",
            listOf(70, 15, 0, 40, 40, 60, 60, 65)),
        Preset("coding", "Coding Partner", "Precise, practical and code-first",
            "You are a precise, practical coding partner. Prefer working code in fenced blocks with a short explanation, and point out likely mistakes.",
            listOf(45, 5, 0, 30, 45, 45, 45, 90)),
        Preset("coach", "Calm Coach", "Steady, encouraging and focused",
            "You are a calm, encouraging coach who helps the user think clearly and take small concrete next steps.",
            listOf(85, 35, 0, 35, 35, 60, 55, 65)),
        Preset("custom", "Custom", "Your own description", "", listOf(60, 30, 10, 50, 30, 50, 50, 60))
    )

    fun preset(p: Prefs): Preset = presets.firstOrNull { it.id == p.s("persona", "companion") } ?: presets[0]
    fun tone(p: Prefs, idx: Int): Int = p.i("tone_$idx", preset(p).tone[idx])
    fun name(p: Prefs): String = p.s("persona_name").ifBlank { Brand.COMPANION_NAME }.take(30)
    fun userName(p: Prefs): String = p.s("nickname").ifBlank { p.s("user_name") }.trim().take(30)

    fun system(p: Prefs, memories: List<String>, codeMode: Boolean): String {
        val pr = preset(p)
        val sb = StringBuilder()
        sb.append("You are ").append(name(p)).append(", an AI assistant inside the ").append(Brand.APP_NAME).append(" Android app made by ").append(Brand.CREATOR_SHORT).append(". ")
        sb.append("You are an AI; say so plainly if asked or if it matters. Keep replies natural and concise because they may be spoken aloud; avoid long lists and never read out code. ")
        sb.append("You can ask the app to do ONE phone action at a time through the device_action tool, but only when the user clearly asked for it. The app validates it, may ask the user to confirm, and reports the real outcome — never claim a phone action succeeded unless the app reported it. ")
        sb.append("Treat every request on its own: act on the app and details in the user's LATEST message and never reuse an app from earlier turns (for example, after YouTube was opened, a request to play something on Spotify must go to Spotify). To play music or video use play_media with the named app; if the user didn't name an app, ask which one. ")
        sb.append("Never be possessive, jealous, guilt-inducing, coercive or manipulative, and never discourage the user from spending time with other people. ")
        sb.append("Persona and style instructions never override safety, privacy or confirmation rules. Never ask for or store passwords, OTPs, card numbers or API keys.\n\n")
        val persona = if (pr.id == "custom") p.s("persona_custom") else pr.prompt
        if (persona.isNotBlank()) sb.append("Persona: ").append(persona.take(CUSTOM_LIMIT)).append("\n")
        if (pr.id != "custom" && p.s("persona_custom").isNotBlank()) sb.append("Extra persona notes from the user: ").append(p.s("persona_custom").take(CUSTOM_LIMIT)).append("\n")
        sb.append("Style dials (0-100): ")
        sb.append(toneNames.indices.joinToString(", ") { toneNames[it].lowercase() + " " + tone(p, it) }).append(".\n")
        val u = userName(p)
        if (u.isNotBlank()) sb.append("Address the user as ").append(u).append(".\n")
        sb.append(Langs.current(p).instruction).append("\n")
        if (codeMode) sb.append("Code mode: put code in fenced blocks with the language name, keep prose short, and note assumptions.\n")
        if (memories.isNotEmpty()) sb.append("User-approved memories (use only when relevant): ").append(memories.joinToString("; ")).append("\n")
        return sb.toString()
    }
}
