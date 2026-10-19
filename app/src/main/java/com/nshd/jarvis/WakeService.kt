package com.nshd.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Optional hands-free mode: while on, a foreground service (with a permanent notification) keeps Android's speech
 * recognizer running and watches for the wake phrase. Honest limits: this is a recognizer loop, not a dedicated
 * low-power wake-word engine — it uses more battery, some phones beep when the recognizer restarts, and the OS
 * may stop it on aggressive battery managers. Nothing is sent to the AI provider until after the phrase.
 */
class WakeService : Service() {
    companion object {
        @Volatile var instance: WakeService? = null
        private const val CH = "zara_wake"
        private const val CH_ALERT = "zara_wake_alert"
        private const val ACTION_STOP = "com.nshd.jarvis.WAKE_STOP"
        private val main = Handler(Looper.getMainLooper())

        fun running() = instance != null
        fun start(ctx: Context) {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val i = Intent(ctx, WakeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeService::class.java)) }
        /** Called before any other part of the app uses the microphone. */
        fun pause() { main.post { instance?.pauseListening() } }
        fun resume() { main.post { instance?.resumeListening() } }
    }

    private lateinit var prefs: Prefs
    private var rec: SpeechRecognizer? = null
    private var paused = false
    private var streak = 0
    private var lastFire = 0L
    private val restartRun = Runnable { listen() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { prefs.put("wake_on", false); stopSelf(); return START_NOT_STICKY }
        val n = notification()
        if (Build.VERSION.SDK_INT >= 30) startForeground(78, n, 128 /* FOREGROUND_SERVICE_TYPE_MICROPHONE */) else startForeground(78, n)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            prefs.put("wake_on", false); stopSelf(); return START_NOT_STICKY
        }
        paused = MainActivity.voiceActive
        if (!paused) scheduleRestart(300)
        return START_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Hey Zara listening", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 3, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_zara)
            .setContentTitle("Zara is listening for “" + prefs.s("wake_phrase", "Hey Zara") + "”")
            .setContentText("The microphone is on for the wake phrase · tap Turn off to stop")
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setOngoing(true).build()
    }

    // ---------------- listening loop
    private fun scheduleRestart(ms: Long) { main.removeCallbacks(restartRun); main.postDelayed(restartRun, ms) }

    private fun destroyRec() { try { rec?.cancel(); rec?.destroy() } catch (_: Exception) {}; rec = null }

    fun pauseListening() { paused = true; main.removeCallbacks(restartRun); destroyRec() }
    fun resumeListening() { paused = false; if (prefs.b("wake_on", false)) scheduleRestart(700) }

    private fun listen() {
        if (paused || !prefs.b("wake_on", false)) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        destroyRec()
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            rec = r
            r.setRecognitionListener(listener)
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)      // keeps audio on the phone when an offline pack exists
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.s("wake_lang", "en-IN"))
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            })
        } catch (_: Exception) { scheduleRestart(2500) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { streak = 0 }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) { check(partialResults) }
        override fun onResults(results: Bundle?) { if (!check(results) && !paused) scheduleRestart(200) }
        override fun onError(error: Int) {
            if (paused) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(300)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { destroyRec(); scheduleRestart(1500) }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { prefs.put("wake_on", false); stopSelf() }
                else -> { streak++; scheduleRestart(minOf(30000L, 1000L * streak * streak)) }
            }
        }
    }

    private fun check(b: Bundle?): Boolean {
        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val sens = prefs.i("wake_sens", 1)
        val phrase = prefs.s("wake_phrase", "Hey Zara")
        if (list.any { Wake.matches(it, sens, phrase) }) { fire(); return true }
        return false
    }

    // ---------------- wake
    private fun fire() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFire < 3000) return
        lastFire = now
        pauseListening()
        if (prefs.b("wake_beep", true)) try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).startTone(ToneGenerator.TONE_PROP_ACK, 160) } catch (_: Exception) {}
        val vis = MainActivity.visible
        if (vis != null) { vis.runOnUiThread { vis.startVoiceFromWake() } }
        else {
            // From the background Android only lets us open the app if "Display over other apps" is allowed.
            if (OverlayService.canDraw(this)) {
                try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("wake", true)) }
                catch (_: Exception) { tapNotification() }
            } else tapNotification()
        }
        // Safety net: never stay paused forever if the app didn't come up.
        main.postDelayed({ if (!MainActivity.voiceActive) resumeListening() }, 12000)
    }

    private fun tapNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Hey Zara heard you", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java).putExtra("wake", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(79, Notification.Builder(this, CH_ALERT).setSmallIcon(R.drawable.ic_stat_zara)
            .setContentTitle("Heard “" + prefs.s("wake_phrase", "Hey Zara") + "”").setContentText("Tap to talk. Allow “Display over other apps” and Zara will open by itself.")
            .setContentIntent(pi).setAutoCancel(true).build())
    }

    override fun onDestroy() {
        main.removeCallbacks(restartRun); destroyRec(); instance = null
        super.onDestroy()
    }
}
