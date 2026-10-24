package com.nshd.jarvis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveProtocolTest {
    @Test fun setupFollowsTheLiveApiShape() {
        val s = JSONObject(LiveProtocol.setup("gemini-3.1-flash-live-preview", "You are Zara.", "Aoede", null, false, null)).getJSONObject("setup")
        assertEquals("models/gemini-3.1-flash-live-preview", s.getString("model"))
        assertEquals("AUDIO", s.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals("Aoede", s.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        assertTrue(s.has("inputAudioTranscription") && s.has("outputAudioTranscription"))
        assertFalse(s.has("proactivity"))
    }

    @Test fun gatingAndResumptionAreOptIn() {
        assertTrue(JSONObject(LiveProtocol.setup("m", "s", "Puck", null, true, null)).getJSONObject("setup").has("proactivity"))
        assertEquals("H1", JSONObject(LiveProtocol.setup("m", "s", "Puck", null, false, "H1")).getJSONObject("setup").getJSONObject("sessionResumption").getString("handle"))
    }

    @Test fun audioChunkIsBase64Pcm16k() {
        val j = JSONObject(LiveProtocol.audioChunk(ByteArray(3200), 3200)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", j.getString("mimeType"))
        assertEquals(3200, java.util.Base64.getDecoder().decode(j.getString("data")).size)
    }

    @Test fun parsesServerEvents() {
        val ev = LiveProtocol.parse("""{"serverContent":{"inputTranscription":{"text":"hi"},"outputTranscription":{"text":"hello"},"interrupted":true,"turnComplete":true}}""")
        assertTrue(ev.any { it is LiveEvent.InputText && it.text == "hi" })
        assertTrue(ev.any { it is LiveEvent.OutputText && it.text == "hello" })
        assertTrue(ev.any { it is LiveEvent.Interrupted })
        assertTrue(ev.any { it is LiveEvent.TurnComplete })
    }

    @Test fun parsesToolCallsGoAwayAndResumption() {
        val calls = LiveProtocol.parse("""{"toolCall":{"functionCalls":[{"id":"c1","name":"device_action","args":{"action":"get_screen"}}]}}""")
        val c = (calls.single() as LiveEvent.ToolCalls).calls.single()
        assertEquals("c1", c.id); assertEquals("get_screen", c.args.getString("action"))
        assertEquals(50000L, (LiveProtocol.parse("""{"goAway":{"timeLeft":"50s"}}""").single() as LiveEvent.GoAway).timeLeftMs)
        val r = LiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"abc","resumable":true}}""").single() as LiveEvent.Resume
        assertEquals("abc", r.handle); assertTrue(r.resumable)
        assertTrue(LiveProtocol.parse("not json").isEmpty())
    }

    @Test fun decodesModelAudio() {
        val pcm = java.util.Base64.getEncoder().encodeToString(ByteArray(4800))
        val a = LiveProtocol.parse("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}]}}}""").single() as LiveEvent.Audio
        assertEquals(4800, a.pcm.size); assertEquals(24000, a.rate)
    }
}
