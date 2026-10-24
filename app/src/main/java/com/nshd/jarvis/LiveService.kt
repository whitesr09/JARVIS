package com.nshd.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Hosts the Live conversation so it keeps running with the app closed or the screen off. A persistent notification
 * shows that the microphone is on and offers Stop. Started only by the user (button, orb, overlay or notification).
 */
class LiveService : Service() {
    companion object {
        @Volatile var instance: LiveService? = null
        private const val CH = "zara_live"
        private const val ACTION_STOP = "com.nshd.jarvis.LIVE_STOP"
        fun isRunning() = instance?.session?.active == true
        fun start(ctx: Context) {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val i = Intent(ctx, LiveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            val s = instance
            if (s != null) s.session?.stop() else ctx.stopService(Intent(ctx, LiveService::class.java))
            ctx.stopService(Intent(ctx, LiveService::class.java))
        }
    }

    var session: LiveSession? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { session?.stop(); stopSelf(); return START_NOT_STICKY }
        val n = notification()
        if (Build.VERSION.SDK_INT >= 30) startForeground(80, n, 128 /* microphone */) else startForeground(80, n)
        val prefs = Prefs(this)
        val db = ZDb(this)
        val runner = AgentRunner(this, prefs, db, ActionExecutor(this, prefs))
        WakeService.pause()                       // the legacy wake recogniser must not share the mic
        val s = LiveSession(this, prefs, db, runner)
        s.onFatal = { stopSelf() }
        session = s
        val err = s.start()
        if (err != null) { LiveBus.state(LiveState.ERROR, err); stopSelf(); return START_NOT_STICKY }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zara:live").apply { acquire(3 * 60 * 60 * 1000L) }
        AgentGuard.reset()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Live conversation", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 6, Intent(this, LiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_zara)
            .setContentTitle(Brand.COMPANION_NAME + " is live").setContentText("The microphone is on · tap Stop to end")
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build()
    }

    override fun onDestroy() {
        session?.stop(); session = null; instance = null
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        WakeService.resume()
        super.onDestroy()
    }
}
