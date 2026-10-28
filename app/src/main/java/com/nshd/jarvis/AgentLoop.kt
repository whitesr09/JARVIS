package com.nshd.jarvis

import org.json.JSONObject

/**
 * Operates the phone toward a goal: ask the model → run its tool calls → feed back results (each already carries the
 * fresh screen) → repeat until it answers in plain text. Pure logic (the model and the executor are injected) so the
 * behaviour — step budget, stuck detection, cancellation, pruning old screens — is unit-tested without a device.
 * [callModel] and [execute] are blocking; run this on a worker thread.
 */
class AgentLoop(
    private val callModel: (List<Msg>) -> Pair<AiResult, String>,
    private val execute: (ActionRequest) -> ActionResult,
    private val parse: (JSONObject) -> ActionRequest?,
    private val maxSteps: Int = 25,
    private val cancelled: () -> Boolean = { false },
    private val onStep: (String) -> Unit = {}
) {
    data class Outcome(val ok: Boolean, val summary: String, val steps: Int)

    companion object {
        const val OMITTED = "(older screen omitted)"
        fun resp(status: String, message: String, hint: String = "", screen: String = ""): String =
            JSONObject().put("status", status).put("message", message).also {
                if (hint.isNotBlank()) it.put("hint", hint)
                if (screen.isNotBlank()) it.put("screen", screen)
            }.toString()

        /** Only the newest screen is useful; older dumps just make every later model call slower and dearer. */
        fun pruneScreens(msgs: MutableList<Msg>) {
            for (i in msgs.indices) {
                val m = msgs[i]
                if (m.role != "tool") continue
                msgs[i] = m.copy(responses = m.responses.map { (n, json) ->
                    val j = try { JSONObject(json) } catch (_: Exception) { return@map n to json }
                    if (j.has("screen")) j.put("screen", OMITTED)
                    n to j.toString()
                })
            }
        }

        fun describe(r: ActionRequest): String =
            (r.action.replace('_', ' ') + " " + r.target.ifBlank { r.text.ifBlank { r.extra["id"] ?: r.extra["key"] ?: "" } }).trim().take(48)
    }

    fun run(goal: String, firstScreen: String): Outcome {
        val msgs = ArrayList<Msg>()
        msgs.add(Msg("user", "GOAL: $goal\n\nCurrent screen:\n" + firstScreen.ifBlank { "(not available yet — use open_app or get_screen)" }))
        var steps = 0
        val recent = ArrayDeque<String>()
        while (true) {
            if (cancelled()) return Outcome(false, "Stopped.", steps)
            val (res, text) = callModel(msgs)
            if (!res.ok) return Outcome(false, res.error ?: "The AI service failed.", steps)
            if (res.cancelled) return Outcome(false, "Stopped.", steps)
            if (res.calls.isEmpty()) return Outcome(true, text.trim().ifBlank { "Done." }, steps)
            if (steps >= maxSteps) return Outcome(false, "I reached the step limit before finishing.", steps)

            msgs.add(Msg("assistant", text, res.calls))
            val responses = ArrayList<Pair<String, String>>()
            var abort: Outcome? = null
            for (c in res.calls) {
                if (abort != null || cancelled()) { responses.add(c.name to resp("SKIPPED", "Not run.")); continue }
                val req = if (c.name == "device_action") parse(c.args) else null
                if (req == null) { responses.add(c.name to resp("FAILED", "Unknown or invalid action request.")); continue }
                steps++
                onStep(describe(req))
                val r = execute(req)
                responses.add(c.name to resp(r.status.name, r.message, r.hint, r.data))
                recent.addLast(req.action + "|" + req.target + "|" + req.text + "|" + req.extra + "|" + r.status + "|" + r.message)
                if (recent.size > 3) recent.removeFirst()
                if (recent.size == 3 && recent.toSet().size == 1) abort = Outcome(false, "I got stuck repeating the same step, so I stopped.", steps)
                else if (r.declined) abort = Outcome(false, "You declined that step, so I stopped.", steps)
                else if (r.status == TaskStatus.BLOCKED_BY_PERMISSION) abort = Outcome(false, (r.message + " " + r.hint).trim(), steps)
            }
            pruneScreens(msgs)
            msgs.add(Msg("tool", "", emptyList(), responses))
            abort?.let { return it }
        }
    }
}
