package com.nshd.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class LiveCall(val id: String, val name: String, val args: JSONObject)

sealed class LiveEvent {
    object SetupComplete : LiveEvent()
    class Audio(val pcm: ByteArray, val rate: Int) : LiveEvent()
    class InputText(val text: String) : LiveEvent()
    class OutputText(val text: String) : LiveEvent()
    object Interrupted : LiveEvent()
    object TurnComplete : LiveEvent()
    class ToolCalls(val calls: List<LiveCall>) : LiveEvent()
    class ToolCancel(val ids: List<String>) : LiveEvent()
    class GoAway(val timeLeftMs: Long) : LiveEvent()
    class Resume(val handle: String?, val resumable: Boolean) : LiveEvent()
}

/** Gemini Live API (BidiGenerateContent) message building and parsing. Spec: ai.google.dev/api/live */
object LiveProtocol {
    const val ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val IN_MIME = "audio/pcm;rate=16000"

    fun setup(model: String, system: String, voice: String, tools: JSONArray?, gating: Boolean, resumeHandle: String?): String {
        val m = if (model.startsWith("models/")) model else "models/$model"
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
        val s = JSONObject()
            .put("model", m)
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", JSONObject().also { if (!resumeHandle.isNullOrBlank()) it.put("handle", resumeHandle) })
        if (tools != null && tools.length() > 0) s.put("tools", tools)
        if (gating) s.put("proactivity", JSONObject().put("proactiveAudio", true))
        return JSONObject().put("setup", s).toString()
    }

    fun audioChunk(pcm: ByteArray, len: Int): String =
        JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("data", Base64.getEncoder().encodeToString(if (len == pcm.size) pcm else pcm.copyOf(len)))
            .put("mimeType", IN_MIME))).toString()

    fun audioStreamEnd(): String = JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()

    fun text(t: String): String = JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()

    fun toolResponse(id: String, name: String, response: JSONObject): String =
        JSONObject().put("toolResponse", JSONObject().put("functionResponses",
            JSONArray().put(JSONObject().put("id", id).put("name", name).put("response", response)))).toString()

    private fun durationMs(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        return ((s.trim().removeSuffix("s").toDoubleOrNull() ?: 0.0) * 1000).toLong()
    }

    fun parse(raw: String): List<LiveEvent> {
        val out = ArrayList<LiveEvent>()
        val j = try { JSONObject(raw) } catch (_: Exception) { return out }
        if (j.has("setupComplete")) out.add(LiveEvent.SetupComplete)
        j.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val inl = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                    val mime = inl.optString("mimeType")
                    if (!mime.startsWith("audio/")) continue
                    val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                    out.add(LiveEvent.Audio(Base64.getDecoder().decode(inl.optString("data")), rate))
                }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { out.add(LiveEvent.InputText(it)) }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { out.add(LiveEvent.OutputText(it)) }
            if (sc.optBoolean("interrupted")) out.add(LiveEvent.Interrupted)
            if (sc.optBoolean("turnComplete")) out.add(LiveEvent.TurnComplete)
        }
        j.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { fc ->
            val calls = ArrayList<LiveCall>()
            for (i in 0 until fc.length()) { val c = fc.getJSONObject(i); calls.add(LiveCall(c.optString("id"), c.optString("name"), c.optJSONObject("args") ?: JSONObject())) }
            if (calls.isNotEmpty()) out.add(LiveEvent.ToolCalls(calls))
        }
        j.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { a -> out.add(LiveEvent.ToolCancel((0 until a.length()).map { a.getString(it) })) }
        j.optJSONObject("goAway")?.let { out.add(LiveEvent.GoAway(durationMs(it.optString("timeLeft")))) }
        j.optJSONObject("sessionResumptionUpdate")?.let { out.add(LiveEvent.Resume(it.optString("newHandle").ifEmpty { null }, it.optBoolean("resumable"))) }
        return out
    }
}
