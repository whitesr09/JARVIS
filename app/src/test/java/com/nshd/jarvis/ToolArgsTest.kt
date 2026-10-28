package com.nshd.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolArgsTest {
    private val all = setOf("open_app", "play_media", "web_search", "tap_text", "type_text", "get_screen", "go_back", "set_timer", "set_alarm", "press_key", "scroll_down", "scroll_up", "tap_node")
    private fun n(a: String, vararg kv: Pair<String, String>) = ToolArgs.normalize(a, mapOf(*kv), all)

    @Test fun acceptsTheNamesModelsActuallyUse() {
        assertEquals(ActionRequest("open_app", "YouTube", ""), n("launch", "app" to "YouTube"))
        assertEquals(ActionRequest("play_media", "spotify", "lofi"), n("play", "app" to "spotify", "query" to "lofi"))
        assertEquals(ActionRequest("play_media", "Spotify", "Kesariya"), n("play_music", "platform" to "Spotify", "song" to "Kesariya"))
        assertEquals(ActionRequest("open_app", "Camera", ""), n("Open App", "name" to "Camera"))
        assertEquals(ActionRequest("tap_text", "Send", ""), n("click", "label" to "Send"))
    }

    @Test fun understandsTimesAndNumbers() {
        assertEquals("300", n("set_timer", "text" to "5 minutes")?.text)
        assertEquals("90", n("timer", "text" to "90 sec")?.text)
        assertEquals("18:30", n("set_alarm", "text" to "6:30 pm")?.text)
        assertEquals("12", n("tap_node", "id" to "12.0")?.extra?.get("id"))
        assertEquals("scroll_up", n("scroll", "direction" to "up")?.action)
    }

    @Test fun rejectsUnknownOrDisallowedActions() {
        assertNull(n("format_phone"))
        assertNull(ToolArgs.normalize("tap_node", mapOf("id" to "1"), setOf("open_app")))
    }
}
