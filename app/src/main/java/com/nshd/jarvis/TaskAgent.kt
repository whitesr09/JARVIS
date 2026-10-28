package com.nshd.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a phone task on a fast *text* model (tool use is more reliable and much quicker there than in a voice model),
 * using [AgentLoop]. The voice model only hands over the goal and later speaks the result.
 */
class TaskAgent(private val ctx: Context, private val prefs: Prefs, private val runner: AgentRunner) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var running = false; private set

    private fun <T> onMain(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val latch = CountDownLatch(1); var out: Any? = null
        main.post { out = f(); latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST") return out as T
    }

    private fun system() = """
You operate an Android phone for the user to achieve a GOAL, using the device_action tool.
- Be fast. Every tool result already includes the NEW screen, so do not call get_screen unless you must look again.
- Element ids ([n]) are valid only for the most recent screen listing. Prefer tap_node(id) or tap_text(label). open_app switches apps. type_text writes into the focused field (tap the field first). press_key(back|home|recents|notifications|quick_settings). swipe scrolls when scroll_down/scroll_up don't.
- For one-shot things use the direct actions (play_media, set_timer, web_search, …) instead of navigating screens.
- Finish as soon as the goal is achieved: reply with ONE short sentence and NO tool call. If you cannot proceed (login or permission needed, ambiguous contact, blocked), reply with one short sentence saying exactly why — do not guess.
- The app itself asks the user before anything that sends, posts, pays or deletes. If a step is declined, stop. Never operate payment, banking or password apps; never type passwords or OTPs.
""".trim()

    fun run(goal: String, onStep: (String) -> Unit, done: (AgentLoop.Outcome) -> Unit) {
        if (running) { done(AgentLoop.Outcome(false, "Another phone task is still running.", 0)); return }
        val provider = GeminiProvider(ctx, prefs).apply { modelOverride = prefs.s("agent_model").ifBlank { null }; thinkingMinimal = true }
        if (!provider.configured()) { done(AgentLoop.Outcome(false, "No Gemini API key is saved.", 0)); return }
        running = true
        AgentGuard.reset()
        Thread({
            val token = AtomicBoolean(false)
            val first = try { onMain { firstScreen() } } catch (_: Throwable) { "" }
            val loop = AgentLoop(
                callModel = { msgs ->
                    token.set(AgentGuard.cancelled)
                    val sb = StringBuilder()
                    val r = provider.stream(system(), msgs, true, token) { sb.append(it) }
                    r to sb.toString()
                },
                execute = { req -> blockingExecute(req) },
                parse = { runner.executor.fromJson(it) },
                maxSteps = 25,
                cancelled = { AgentGuard.cancelled },
                onStep = { s -> main.post { onStep(s) } })
            val out = try { loop.run(goal, first) } catch (t: Throwable) { AgentLoop.Outcome(false, "The phone task failed (" + t.javaClass.simpleName + ").", 0) }
            running = false
            main.post { done(out) }
        }, "task-agent").apply { isDaemon = true; start() }
    }

    private fun firstScreen(): String {
        val svc = ZaraAccessibilityService.instance ?: return ""
        if (!prefs.b("agent_mode", false)) return ""
        val fg = svc.foregroundPackage()
        if (fg != null && fg in ProtectedApps.list(prefs)) return "(a protected app is on screen — its contents are not shared)"
        val (pkg, text) = svc.snapshot(60)
        return "app: $pkg\n$text"
    }

    private fun blockingExecute(req: ActionRequest): ActionResult {
        val latch = CountDownLatch(1)
        var res: ActionResult? = null
        main.post { runner.run(req) { r, title -> LiveBus.task(title, r); res = r; latch.countDown() } }
        return if (latch.await(150, TimeUnit.SECONDS)) res!! else ActionResult(TaskStatus.FAILED, "Timed out waiting for the phone to respond.")
    }
}
