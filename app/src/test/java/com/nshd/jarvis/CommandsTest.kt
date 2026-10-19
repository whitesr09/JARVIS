package com.nshd.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    private fun d(a: String, t: String = "", x: String = "") = Local.Do(ActionRequest(a, t, x))

    @Test fun opensAppsByName() {
        assertEquals(d("open_app", "YouTube"), Commands.parse("Open YouTube"))
        assertEquals(d("open_app", "WhatsApp"), Commands.parse("Hey Zara, please open WhatsApp."))
        assertEquals(d("open_app", "camera"), Commands.parse("launch the camera app"))
    }

    @Test fun settingsPages() {
        assertEquals(d("open_settings", "bluetooth"), Commands.parse("open Bluetooth settings"))
        assertEquals(d("open_settings", "wifi"), Commands.parse("take me to wi-fi settings"))
        assertEquals(d("open_settings", "general"), Commands.parse("open settings"))
    }

    @Test fun youtubeSearch() {
        assertEquals(d("search_youtube", "", "arijit singh songs"), Commands.parse("search YouTube for arijit singh songs"))
        assertEquals(d("search_youtube", "", "lofi"), Commands.parse("search youtube for lofi"))
        assertEquals(d("play_media", "youtube", "iron man highlights"), Commands.parse("play iron man highlights on youtube"))
        assertEquals(d("play_media", "youtube", "lofi beats"), Commands.parse("open youtube and play lofi beats"))
    }

    @Test fun eachRequestCarriesItsOwnApp() {
        // Regression: "open Spotify and play …" used to fall through to YouTube.
        assertEquals(d("play_media", "spotify", "lofi beats"), Commands.parse("open Spotify and play lofi beats"))
        assertEquals(d("play_media", "spotify", "kesariya"), Commands.parse("play kesariya on spotify"))
        assertEquals(d("play_media", "spotify", ""), Commands.parse("play something on Spotify"))
        assertEquals(d("play_media", "ytmusic", "believer"), Commands.parse("play believer on youtube music"))
        assertEquals(d("play_media", "JioSaavn", "arijit"), Commands.parse("open JioSaavn and play arijit"))
    }

    @Test fun timersAlarmsAndMore() {
        assertEquals(d("set_timer", "", "300"), Commands.parse("set a timer for 5 minutes"))
        assertEquals(d("set_alarm", "", "18:30"), Commands.parse("set an alarm for 6:30 pm"))
        assertEquals(d("set_alarm", "", "00:00"), Commands.parse("set alarm at 12 am"))
        assertEquals(d("web_search", "", "best pizza near me"), Commands.parse("google best pizza near me"))
        assertEquals(d("dial_number", "", "9876543210"), Commands.parse("call 9876543210"))
        assertEquals(d("sms_draft", "9876543210", "running late"), Commands.parse("text 9876543210 saying running late"))
    }

    @Test fun wakePhrase() {
        assertTrue(Wake.matches("hey zara", 1))
        assertTrue(Wake.matches("heyzara", 0))
        assertFalse(Wake.matches("hey sara", 0))
        assertTrue(Wake.matches("hey sara", 1))
        assertFalse(Wake.matches("zara", 0))
        assertTrue(Wake.matches("zara open youtube", 2))
        assertFalse(Wake.matches("hey there how are you", 2))
    }

    @Test fun whatsappIsOnlyADraft() {
        assertEquals(d("whatsapp_draft", "Rahul", "I will reach in 10 minutes"), Commands.parse("Send WhatsApp to Rahul saying I will reach in 10 minutes"))
        assertEquals(d("whatsapp_draft", "", "hello"), Commands.parse("send a whatsapp message saying hello"))
    }

    @Test fun navigationAndTyping() {
        assertEquals(d("go_back"), Commands.parse("go back"))
        assertEquals(d("go_home"), Commands.parse("go to the home screen"))
        assertEquals(d("scroll_down"), Commands.parse("scroll down"))
        assertEquals(d("tap_text", "Search"), Commands.parse("tap the Search button"))
        assertNull(Commands.parse("type hello there"))
        assertEquals(d("type_text", "", "hello there"), Commands.parse("type hello there", true))
    }

    @Test fun sentencesAndMultiStepGoToTheModel() {
        assertNull(Commands.parse("open instagram and comment nice video"))
        assertNull(Commands.parse("open the door for me when I get home tonight please"))
        assertNull(Commands.parse("what is the capital of France"))
    }

    @Test fun memoryCommands() {
        assertEquals(Local.Remember("I prefer short answers"), Commands.parse("Remember that I prefer short answers"))
        assertEquals(Local.Recall, Commands.parse("what do you remember?"))
    }

    @Test fun secretsAreNeverMemory() {
        assertTrue(Commands.sensitive("my wifi password is hunter2"))
        assertTrue(Commands.sensitive("card is 4111111111111111"))
        assertTrue(Commands.sensitive("my api key is abc"))
        assertFalse(Commands.sensitive("I prefer short answers"))
        assertFalse(Commands.sensitive("I like pineapple"))
    }

    @Test fun riskyTapsNeedConfirmation() {
        assertTrue(Commands.RISKY.containsMatchIn("Send"))
        assertTrue(Commands.RISKY.containsMatchIn("Add a comment"))
        assertFalse(Commands.RISKY.containsMatchIn("Search"))
    }
}
