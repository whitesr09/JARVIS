package com.nshd.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

enum class LiveState { OFF, CONNECTING, LISTENING, THINKING, SPEAKING, WORKING, ERROR }

interface LiveListener {
    fun onLiveState(s: LiveState, note: String) {}
    fun onLiveUser(text: String) {}
    fun onLiveAssistant(text: String) {}
    fun onLiveTurnComplete() {}
    fun onLiveTask(title: String, res: ActionResult) {}
    fun onLiveLevel(v: Float) {}
}

/** In-process event hub: the Live service publishes, the UI (when open) subscribes. Callbacks run on the main thread. */
object LiveBus {
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<LiveListener>()
    @Volatile var state = LiveState.OFF
    @Volatile var note = ""
    @Volatile var lastLine = ""

    private fun post(f: (LiveListener) -> Unit) = main.post { for (l in listeners) f(l) }

    fun state(s: LiveState, n: String = "") {
        state = s; note = n
        OverlayService.setBusy(s != LiveState.OFF && s != LiveState.LISTENING)
        OverlayService.setStatus(when (s) { LiveState.LISTENING -> "Listening"; LiveState.THINKING -> "Thinking"; LiveState.SPEAKING -> "Speaking"; LiveState.WORKING -> n.ifBlank { "Working" }; LiveState.CONNECTING -> "Connecting"; LiveState.ERROR -> "Needs attention"; else -> "" })
        post { it.onLiveState(s, n) }
    }
    fun user(t: String) = post { it.onLiveUser(t) }
    fun assistant(t: String) = post { it.onLiveAssistant(t) }
    fun turnComplete() = post { it.onLiveTurnComplete() }
    fun task(title: String, res: ActionResult) { lastLine = title + " · " + res.status.name.lowercase().replace('_', ' '); post { it.onLiveTask(title, res) } }
    fun level(v: Float) = post { it.onLiveLevel(v) }
}

/**
 * Asks the user to approve a consequential action. If the app is on screen this is a normal dialog; otherwise a
 * heads-up notification with Allow / Deny buttons, so you can approve without opening the app. No answer = Deny.
 */
object ConfirmCenter {
    private const val CH = "zara_confirm"
    private val main = Handler(Looper.getMainLooper())
    private var seq = 1000
    private val pending = HashMap<Int, Pair<() -> Unit, () -> Unit>>()

    fun ask(ctx: Context, title: String, body: String, onYes: () -> Unit, onNo: () -> Unit) {
        val vis = MainActivity.visible
        if (vis != null) { vis.runOnUiThread { vis.confirm(title, body, "Allow", onYes, onNo) }; return }
        val id = ++seq
        synchronized(pending) { pending[id] = onYes to onNo }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Approvals", NotificationManager.IMPORTANCE_HIGH))
        fun pi(allow: Boolean, rc: Int) = PendingIntent.getBroadcast(ctx, rc, Intent(ctx, ConfirmReceiver::class.java).putExtra("id", id).putExtra("allow", allow), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat_zara)
            .setContentTitle(title).setContentText(body.lineSequence().firstOrNull().orEmpty().take(90))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(null, "Allow", pi(true, id * 2)).build())
            .addAction(Notification.Action.Builder(null, "Deny", pi(false, id * 2 + 1)).build()).build()
        nm.notify(id, n)
        main.postDelayed({ resolve(ctx, id, false) }, 90_000)   // no answer = no
    }

    fun resolve(ctx: Context, id: Int, allow: Boolean) {
        val p = synchronized(pending) { pending.remove(id) } ?: return
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
        main.post { if (allow) p.first() else p.second() }
    }
}

class ConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { ConfirmCenter.resolve(c, i.getIntExtra("id", -1), i.getBooleanExtra("allow", false)) }
}

/** The single path every device action takes (text chat, local commands and Live): preflight → confirm → execute → log. */
class AgentRunner(private val ctx: Context, private val prefs: Prefs, private val db: ZDb, val executor: ActionExecutor) {
    fun run(req: ActionRequest, onStart: ((String) -> Unit)? = null, cb: (ActionResult, String) -> Unit) {
        val pre = executor.preflight(req)
        pre.blocked?.let { finish(pre.title, it, cb); return }
        fun go() {
            AgentGuard.steps++
            LiveBus.lastLine = pre.title
            onStart?.invoke(pre.title)
            executor.execute(req) { r -> finish(pre.title, r, cb) }
        }
        if (pre.risk == Risk.CONSEQUENTIAL) {
            ConfirmCenter.ask(ctx, if (pre.risk == Risk.CONSEQUENTIAL) "Zara needs your OK" else "Allow this action?", pre.preview, { go() }) {
                cb(ActionResult(TaskStatus.FAILED, "The user declined this action. Do not retry it; ask what they want instead.", declined = true), pre.title)
            }
        } else go()
    }

    private fun finish(title: String, res: ActionResult, cb: (ActionResult, String) -> Unit) {
        if (!res.declined) Thread { try { db.logTask(title, title, res.status.name, res.hint.ifBlank { res.message.take(120) }) } catch (_: Exception) {} }.start()
        cb(res, title)
    }
}
