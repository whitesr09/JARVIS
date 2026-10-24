package com.nshd.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity(), BrainListener {
    companion object {
        /** The instance currently on screen (null when the app is in the background). */
        @Volatile var visible: MainActivity? = null
        /** True while a voice session owns the microphone. */
        @Volatile var voiceActive = false
    }

    lateinit var prefs: Prefs
    lateinit var db: ZDb
    lateinit var voice: ZVoice
    lateinit var executor: ActionExecutor
    lateinit var brain: Brain
    lateinit var theme: ZTheme
    lateinit var ui: Ui
    var partial = ""

    // Code workspace state (kept in memory only)
    var codeName = "main.kt"
    var codeLang = "Kotlin"
    var codeText = ""
    var codeUri: Uri? = null
    var codeStatus = "Only inside Zara (not saved yet)"

    private val screens = ArrayList<Screen>()
    private var tabIndex = 0
    private lateinit var rootView: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var navBar: LinearLayout
    private var resumedNow = false
    private var pendingConfirm: (() -> Unit)? = null
    private var pendingVoice = false
    private var pendingWake = false
    private var pendingLive = false
    private val liveWatcher = object : LiveListener {
        override fun onLiveState(s: LiveState, note: String) { if ((s == LiveState.ERROR || s == LiveState.OFF) && note.isNotBlank()) toast(note); updateMic() }
    }
    private val navItems = ArrayList<Pair<LinearLayout, IconView>>()
    private var micFab: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        SecureStore.migrateLegacy(this, prefs)
        db = ZDb(this)
        voice = ZVoice(this, prefs)
        executor = ActionExecutor(this, prefs)
        brain = Brain(this)
        wireVoice()
        brain.listeners.add(this)
        LiveBus.listeners.add(liveWatcher)
        applyTheme(initial = true)
        if (!prefs.b("onboarded", false)) go(OnboardingScreen())
        if (prefs.b("overlay_on", false) && OverlayService.canDraw(this)) OverlayService.start(this)
        if (intent?.getBooleanExtra("wake", false) == true) window.decorView.post { startVoiceFromWake() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("wake", false) == true) window.decorView.post { startVoiceFromWake() }
    }

    /** Called when "Hey Zara" is heard: bring up a normal voice session. */
    fun startVoiceFromWake() {
        if (voice.sessionActive) return
        beginVoice()
    }

    fun activateWake() {
        prefs.put("wake_on", true); WakeService.start(this); rebuildCurrent()
    }

    fun requestWake() { pendingWake = true; requestPerm(501, Manifest.permission.RECORD_AUDIO) }

    /** Single entry point for starting a session: the wake service must release the mic first. */
    private fun beginVoice() {
        WakeService.pause()
        when (val r = voice.startSession()) {
            null -> voiceActive = true
            "need_permission" -> { pendingVoice = true; requestPerm(501, Manifest.permission.RECORD_AUDIO) }
            else -> { toast(r); WakeService.resume() }
        }
        brain.refresh(); updateMic()
    }

    private fun wireVoice() {
        voice.onState = { brain.refresh() }
        voice.onPartial = { partial = it; brain.listeners.toList().forEach { l -> l.stateChanged() } }
        voice.onLevel = { brain.level(it) }
        voice.onError = { m -> toast(m); brain.flashError(m) }
        voice.onSessionEnded = { reason -> partial = ""; voiceActive = false; WakeService.resume(); brain.refresh(); if (reason.isNotBlank()) toast(reason); updateMic() }
        voice.onFinal = { text, conf ->
            partial = ""
            if (conf in 0f..0.35f) {
                // Low confidence: let the user check/edit instead of acting on a likely mishearing.
                brain.draft = text
                voice.stopSession("")
                tab(1); toast("I wasn't sure I heard that right — check it, then send.")
            } else brain.submit(text)
        }
    }

    // ------------------------------------------------------------ theme / chrome
    fun applyTheme(initial: Boolean = false) {
        theme = Themes.resolve(this, prefs)
        ui = Ui(this, theme)
        @Suppress("DEPRECATION")
        window.apply {
            statusBarColor = theme.bg; navigationBarColor = theme.bg2
            var f = 0
            if (!theme.dark) f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decorView.systemUiVisibility = f
        }
        buildRoot()
        if (screens.isEmpty()) { screens.add(makeTab(tabIndex)) }
        showCurrent()
    }

    private fun buildRoot() {
        rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = ui.pageBackground() }
        content = FrameLayout(this)
        rootView.addView(content, ui.lp(-1, 0, 1f))
        navBar = buildNav()
        rootView.addView(navBar, ui.lpm(-1, ui.dp(68), l = 12, tp = 4, r = 12, b = 10))
        setContentView(rootView)
    }

    private fun buildNav(): LinearLayout {
        navItems.clear()
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = ui.shape(theme.surface, 34f, theme.stroke) }
        fun item(idx: Int, icon: String, label: String): LinearLayout {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
                contentDescription = label; setOnClickListener { tab(idx) }
            }
            val ic = IconView(this, icon, theme.dim)
            b.addView(ic, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
            b.addView(ui.tv(label, 11f, theme.dim, true, Gravity.CENTER).apply { setPadding(0, ui.dp(3), 0, 0) })
            navItems.add(b to ic)
            return b
        }
        bar.addView(item(0, "home", "Home"), ui.lp(0, -1, 1f))
        bar.addView(item(1, "chat", "Chat"), ui.lp(0, -1, 1f))
        val fab = FrameLayout(this).apply { isClickable = true; isFocusable = true; setOnClickListener { toggleVoice() } }
        micFab = fab
        bar.addView(fab, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
        bar.addView(item(2, "gear", "Settings"), ui.lp(0, -1, 1f))
        bar.addView(item(3, "bell", "Updates"), ui.lp(0, -1, 1f))
        updateMic()
        return bar
    }

    private fun updateMic() {
        val fab = micFab ?: return
        val on = listening()
        fab.background = ui.ripple(ui.shape(if (on) theme.accent else theme.surface2, 32f, if (on) theme.accent else theme.stroke, 1.5f), 32f)
        fab.removeAllViews()
        val fg = if (on) theme.onAccent else theme.text
        fab.addView(IconView(this, if (voice.state == ZVoice.VState.SPEAKING) "stop" else "mic", fg), FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER))
        fab.contentDescription = if (on) "Stop voice session" else "Start voice session"
    }

    private fun updateNav() {
        val cur = screens.lastOrNull()
        navBar.visibility = if (cur == null || cur.isSub) View.GONE else View.VISIBLE
        val map = intArrayOf(0, 1, 2, 3)
        for ((i, p) in navItems.withIndex()) {
            val sel = i == tabIndex && screens.size <= 1
            p.second.color = if (sel) theme.accentText else theme.dim
            p.second.invalidate()
            (p.first.getChildAt(1) as TextView).setTextColor(if (sel) theme.accentText else theme.dim)
            p.first.background = ui.ripple(if (sel) ui.shape(com.nshd.jarvis.Themes.alpha(theme.accent, 36), 22f) else null, 22f)
            map[i] = i
        }
    }

    // ------------------------------------------------------------ navigation
    private fun makeTab(i: Int): Screen = when (i) { 1 -> ChatScreen(); 2 -> SettingsScreen(); 3 -> ActivityScreen(); else -> HomeScreen() }

    fun tab(i: Int) {
        screens.lastOrNull()?.onHide()
        screens.clear(); tabIndex = i
        screens.add(makeTab(i))
        showCurrent()
    }

    fun go(s: Screen) {
        screens.lastOrNull()?.onHide()
        screens.add(s)
        if (::content.isInitialized) showCurrent()
    }

    fun back() {
        if (screens.size > 1) { screens.removeAt(screens.size - 1).onHide(); showCurrent() }
        else if (tabIndex != 0) tab(0)
        else finish()
    }

    fun finishOnboarding(next: Screen?) {
        screens.clear(); screens.add(makeTab(0)); tabIndex = 0
        if (next != null) { screens.add(next) }
        showCurrent()
    }

    private fun showCurrent() {
        val s = screens.last()
        s.act = this
        content.removeAllViews()
        content.addView(s.build(ui), FrameLayout.LayoutParams(-1, -1))
        updateNav()
        s.onShow()
    }

    fun rebuildCurrent() {
        val s = screens.lastOrNull() ?: return
        val oldView = content.getChildAt(0)
        val y = findScroll(oldView)?.scrollY ?: 0
        s.onHide()
        showCurrent()
        if (y > 0) content.post { findScroll(content.getChildAt(0))?.scrollTo(0, y) }
    }

    private fun findScroll(v: View?): ScrollView? {
        if (v is ScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    override fun onBackPressed() = back()

    // ------------------------------------------------------------ voice
    fun liveOn() = LiveBus.state != LiveState.OFF
    /** True while any microphone session (Live or classic) is running. */
    fun listening() = voice.sessionActive || liveOn()
    /** "auto" = Live whenever a Gemini key exists; the user can force Live or classic in Voice Setup. */
    fun useLive() = prefs.s("voice_engine", "auto").let { it == "live" || (it == "auto" && SecureStore.has(this, "gemini_key")) }

    fun startLive() {
        if (!voice.micGranted()) { pendingLive = true; requestPerm(501, Manifest.permission.RECORD_AUDIO); return }
        WakeService.pause()
        LiveService.start(this)
    }

    fun toggleVoice() {
        if (liveOn()) { LiveService.stop(this); return }
        if (useLive()) { startLive(); return }
        if (voice.state == ZVoice.VState.SPEAKING) { voice.interrupt(); return }
        if (voice.sessionActive) { voice.stopSession(""); return }
        beginVoice()
    }

    override fun stateChanged() {
        MainActivityHolder.line = brain.lastNote.ifBlank { brain.orb.label }
        updateMic()
    }

    // ------------------------------------------------------------ helpers used by screens
    fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
    @Suppress("DEPRECATION")
    fun versionCode(): Int = try { packageManager.getPackageInfo(packageName, 0).versionCode } catch (_: Exception) { 0 }

    fun dialog(): AlertDialog.Builder = AlertDialog.Builder(this,
        if (theme.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)

    fun confirm(title: String, body: String, positive: String, onYes: () -> Unit, onNo: (() -> Unit)?) {
        val show = {
            dialog().setTitle(title).setMessage(body).setCancelable(false)
                .setPositiveButton(positive) { _, _ -> onYes() }
                .setNegativeButton("Cancel") { _, _ -> onNo?.invoke() }.show()
            Unit
        }
        if (resumedNow) show()
        else {
            // Zara isn't on screen (e.g. mid-task in another app): the question waits here until you're back.
            pendingConfirm = show
            brain.lastNote = "Waiting for your OK in Zara"
            OverlayService.setStatus("Waiting for your OK")
            try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    fun promptText(title: String, hint: String, initial: String, cb: (String) -> Unit) {
        val et = ui.input(hint, initial)
        val box = FrameLayout(this).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(et) }
        dialog().setTitle(title).setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> val v = et.text.toString().trim(); if (v.isNotEmpty()) cb(v) }.show()
    }

    fun chooser(title: String, items: List<Pair<String, () -> Unit>>) {
        dialog().setTitle(title).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    fun copyText(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(Brand.APP_NAME, text))
        toast("Copied")
    }

    fun shareText(text: String) {
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share")) } catch (_: Exception) { toast("Nothing can handle that.") }
    }

    private val allowedHosts get() = setOf("instagram.com", "www.instagram.com", "t.me", "wa.me") +
        (if (Brand.WEBSITE.startsWith("https://")) setOf(Uri.parse(Brand.WEBSITE).host.orEmpty()) else emptySet())

    fun openLink(url: String) {
        val u = Uri.parse(url)
        if (u.scheme != "https" || u.host !in allowedHosts) { toast("That link isn't allowed."); return }
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { toast("No app can open that link.") }
    }

    fun openAppSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    fun requestPerm(code: Int, perm: String) { requestPermissions(arrayOf(perm), code) }

    fun openInCode(lang: String, code: String) {
        val map = mapOf("kotlin" to ("Kotlin" to "kt"), "kt" to ("Kotlin" to "kt"), "java" to ("Java" to "java"), "python" to ("Python" to "py"), "py" to ("Python" to "py"),
            "javascript" to ("JavaScript" to "js"), "js" to ("JavaScript" to "js"), "html" to ("HTML" to "html"), "cpp" to ("C++" to "cpp"), "c++" to ("C++" to "cpp"))
        val (l, ext) = map[lang.lowercase()] ?: ("Text" to "txt")
        codeText = code; codeLang = l; codeName = "main.$ext"; codeUri = null; codeStatus = "Only inside Zara (not saved yet)"
        go(CodeScreen())
    }

    // ------------------------------------------------------------ lifecycle & results
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        screens.lastOrNull()?.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (requestCode == 501) {
            if (ok && pendingLive) { pendingLive = false; startLive() }
            else if (ok && pendingWake) { pendingWake = false; activateWake() }
            else if (ok && pendingVoice) { pendingVoice = false; toggleVoice() }
            else if (!ok) { pendingVoice = false; pendingWake = false; pendingLive = false; toast("Microphone permission is needed for voice. Text chat still works.") }
        }
        screens.lastOrNull()?.onPermission(requestCode, ok)
    }

    override fun onStart() { super.onStart(); visible = this }

    override fun onResume() {
        super.onResume()
        resumedNow = true
        if (prefs.b("wake_on", false) && !WakeService.running()) WakeService.start(this)
        val s = screens.lastOrNull()
        if (s is PermissionsScreen || s is AppControlScreen || s is OverlayScreen || s is VoiceSetupScreen) rebuildCurrent()
        if (prefs.b("overlay_on", false) && OverlayService.canDraw(this) && OverlayService.instance == null) OverlayService.start(this)
        pendingConfirm?.let { pendingConfirm = null; it() }
        updateMic()
    }

    override fun onPause() { resumedNow = false; super.onPause() }

    override fun onStop() {
        if (visible === this) visible = null
        // Android doesn't allow reliable background listening; end the session so the mic state is never ambiguous.
        if (voice.sessionActive) voice.stopSession("")
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    override fun onDestroy() {
        brain.listeners.remove(this)
        LiveBus.listeners.remove(liveWatcher)
        brain.shutdown()
        voice.shutdown()
        try { db.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
