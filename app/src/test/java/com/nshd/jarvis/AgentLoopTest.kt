package com.nshd.jarvis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    private fun call(action: String, vararg kv: Pair<String, String>) =
        ToolCall("device_action", JSONObject().put("action", action).also { j -> kv.forEach { j.put(it.first, it.second) } })
    private val parse: (JSONObject) -> ActionRequest? = { ToolArgs.normalize(it.optString("action"), it.keys().asSequence().associateWith { k -> it.optString(k) }, Tools.ACTIONS.toSet()) }
    private fun ok(msg: String = "done", data: String = "") = ActionResult(TaskStatus.COMPLETED, msg, data = data)

    /** A scripted model: returns the queued turns in order and records what it was shown each time. */
    private class Script(val turns: List<Pair<List<ToolCall>, String>>) {
        var n = 0; val seen = ArrayList<List<Msg>>()
        fun next(m: List<Msg>): Pair<AiResult, String> { seen.add(m.toList()); val t = turns[minOf(n++, turns.size - 1)]; return AiResult(true, calls = t.first) to t.second }
    }

    @Test fun finishesWithPlainTextAfterOneStep() {
        val s = Script(listOf(listOf(call("tap_text", "target" to "Search")) to "", emptyList<ToolCall>() to "Searched."))
        val seen = ArrayList<ActionRequest>()
        val o = AgentLoop(s::next, { seen.add(it); ok("tapped", "[0] Button \"x\"") }, parse).run("search", "screen0")
        assertTrue(o.ok); assertEquals("Searched.", o.summary); assertEquals(1, o.steps)
        assertEquals("tap_text", seen.single().action)
        assertTrue(s.seen[0].first().text.contains("GOAL: search") && s.seen[0].first().text.contains("screen0"))
        assertEquals("tool", s.seen[1].last().role)
    }

    @Test fun onlyTheNewestScreenIsKept() {
        val s = Script(listOf(listOf(call("tap_text", "target" to "A")) to "", listOf(call("tap_text", "target" to "B")) to "",
            listOf(call("tap_text", "target" to "C")) to "", emptyList<ToolCall>() to "ok"))
        var k = 0
        AgentLoop(s::next, { ok("m", "SCREEN" + (k++)) }, parse).run("g", "s")
        val last = s.seen.last().filter { it.role == "tool" }.map { it.responses.single().second }
        assertEquals(3, last.size)
        assertTrue(last[0].contains(AgentLoop.OMITTED) && last[1].contains(AgentLoop.OMITTED))
        assertTrue(last[2].contains("SCREEN2"))
    }

    @Test fun stopsWhenStuckRepeatingTheSameStep() {
        val s = Script(listOf(listOf(call("tap_text", "target" to "Next")) to ""))
        val o = AgentLoop(s::next, { ok("same") }, parse).run("g", "")
        assertFalse(o.ok); assertTrue(o.summary.contains("stuck")); assertEquals(3, o.steps)
    }

    @Test fun respectsTheStepBudget() {
        var i = 0
        val s = object { fun next(m: List<Msg>): Pair<AiResult, String> = AiResult(true, calls = listOf(call("tap_text", "target" to "t" + (i++)))) to "" }
        val o = AgentLoop(s::next, { ok("x") }, parse, maxSteps = 4).run("g", "")
        assertFalse(o.ok); assertTrue(o.summary.contains("step limit")); assertEquals(4, o.steps)
    }

    @Test fun cancellationStopsImmediately() {
        var stop = false
        val s = Script(listOf(listOf(call("tap_text", "target" to "A")) to ""))
        val o = AgentLoop(s::next, { stop = true; ok() }, parse, cancelled = { stop }).run("g", "")
        assertFalse(o.ok); assertEquals("Stopped.", o.summary)
    }

    @Test fun missingPermissionEndsWithTheExactFix() {
        val s = Script(listOf(listOf(call("get_screen")) to ""))
        val o = AgentLoop(s::next, { ActionResult(TaskStatus.BLOCKED_BY_PERMISSION, "Agent mode is off.", "Turn it on in Settings → App Control.") }, parse).run("g", "")
        assertFalse(o.ok); assertTrue(o.summary.contains("Agent mode is off") && o.summary.contains("Settings"))
    }

    @Test fun declinedStepEndsTheTask() {
        val s = Script(listOf(listOf(call("tap_text", "target" to "Send")) to ""))
        val o = AgentLoop(s::next, { ActionResult(TaskStatus.FAILED, "Declined", declined = true) }, parse).run("g", "")
        assertFalse(o.ok); assertTrue(o.summary.contains("declined"))
    }

    @Test fun modelErrorsAndUnknownToolsAreHandled() {
        val bad = Script(emptyList<Pair<List<ToolCall>, String>>() + (emptyList<ToolCall>() to ""))
        val err = AgentLoop({ AiResult(false, "quota") to "" }, { ok() }, parse).run("g", "")
        assertFalse(err.ok); assertEquals("quota", err.summary)
        val s = Script(listOf(listOf(ToolCall("rm_rf", JSONObject())) to "", emptyList<ToolCall>() to "fine"))
        val o = AgentLoop(s::next, { ok() }, parse).run("g", "")
        assertTrue(o.ok); assertTrue(s.seen[1].last().responses.single().second.contains("FAILED"))
        assertEquals(0, bad.n)
    }
}
