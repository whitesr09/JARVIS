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
        assertEquals(d("search_youtube", "", "iron man highlights"), Commands.parse("play iron man highlights on youtube"))
        assertEquals(d("search_youtube", "", "lofi beats"), Commands.parse("open youtube and play lofi beats"))
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
