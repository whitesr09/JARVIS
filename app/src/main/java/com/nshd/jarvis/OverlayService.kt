package com.nshd.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Optional floating avatar. Small, draggable, snaps to a screen edge, always disableable from the
 * notification or the app. It never shows system-style dialogs and never covers system controls.
 */
class OverlayService : Service() {
    companion object {
        @Volatile var instance: OverlayService? = null
        private const val CH = "zara_overlay"
        private const val ACTION_STOP = "com.nshd.jarvis.OVERLAY_STOP"
        private val main = Handler(Looper.getMainLooper())

        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)
        fun start(ctx: Context) {
            if (!canDraw(ctx)) return
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
        fun setBusy(b: Boolean) { main.post { instance?.apply { busy = b; applyVisibility() } } }
        fun setStatus(s: String) { main.post { instance?.showStatus(s) } }
        fun refresh() { main.post { instance?.rebuild() } }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var label: TextView? = null
    private var busy = false
    private val hideLabel = Runnable { label?.visibility = View.GONE }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.put("overlay_on", false)
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(77, notification())
        if (!canDraw(this)) { stopSelf(); return START_NOT_STICKY }
        rebuild()
        return START_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Floating avatar", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_zara)
            .setContentTitle(Brand.APP_NAME + " avatar is showing").setContentText("Tap to open · use Turn off to hide it")
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setOngoing(true).build()
    }

    fun rebuild() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        if (!canDraw(this)) return
        val dm = resources.displayMetrics
        val size = (58 * dm.density).toInt()
        val th = Themes.resolve(this, prefs)
        val frame = FrameLayout(this)
        val avatar = AvatarView(this, prefs.s("avatar", "orb"), th.accent)
        frame.addView(avatar, FrameLayout.LayoutParams(size, size))
        val tag = TextView(this).apply {
            textSize = 11f; setTextColor(th.text); setPadding(18, 8, 18, 8); visibility = View.GONE
            background = Ui(this@OverlayService, th).shape(th.surface, 14f, th.stroke)
        }
        frame.addView(tag, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { topMargin = size })
        label = tag
        frame.alpha = prefs.i("overlay_alpha", 100).coerceIn(30, 100) / 100f

        val p = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        val left = prefs.s("overlay_edge", "right") == "left"
        p.x = if (left) 0 else dm.widthPixels - size
        p.y = prefs.i("overlay_y", (dm.heightPixels * 0.4f).toInt()).coerceIn(0, dm.heightPixels - size * 2)
        params = p

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { showStatus(MainActivityHolder.lastLine()); return true }
        })
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        frame.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dx = ev.rawX; dy = ev.rawY; sx = p.x; sy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(ev.rawX - dx) > 12 || Math.abs(ev.rawY - dy) > 12) moved = true
                    if (moved) {
                        p.x = (sx + (ev.rawX - dx)).toInt().coerceIn(0, dm.widthPixels - size)
                        p.y = (sy + (ev.rawY - dy)).toInt().coerceIn(0, dm.heightPixels - size * 2)
                        try { wm.updateViewLayout(frame, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) {
                    val toLeft = p.x + size / 2 < dm.widthPixels / 2
                    p.x = if (toLeft) 0 else dm.widthPixels - size
                    prefs.put("overlay_edge", if (toLeft) "left" else "right"); prefs.put("overlay_y", p.y)
                    try { wm.updateViewLayout(frame, p) } catch (_: Exception) {}
                }
            }
            true
        }
        frame.contentDescription = "Zara avatar. Tap to open the app."
        try { wm.addView(frame, p); root = frame } catch (_: Exception) { stopSelf() }
        applyVisibility()
    }

    fun applyVisibility() {
        val working = prefs.s("overlay_vis", "always") == "working"
        root?.visibility = if (working && !busy) View.GONE else View.VISIBLE
    }

    fun showStatus(s: String) {
        val l = label ?: return
        if (s.isBlank()) return
        l.text = s.take(48); l.visibility = View.VISIBLE
        main.removeCallbacks(hideLabel); main.postDelayed(hideLabel, 2600)
    }

    override fun onDestroy() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null; instance = null
        super.onDestroy()
    }
}

/** Lets the overlay read the latest short status without holding an Activity reference. */
object MainActivityHolder {
    @Volatile var line = ""
    fun lastLine() = line.ifBlank { "Ready" }
}
