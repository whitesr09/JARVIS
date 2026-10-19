package com.nshd.jarvis

import android.content.Context
import android.graphics.*
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import kotlin.math.*

/** Small line icons drawn on a 24-unit grid so no image assets are needed. */
class IconView(ctx: Context, var kind: String, var color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 1.9f
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 24f
        if (s <= 0f) return
        p.color = color
        c.save()
        c.translate((width - 24 * s) / 2f, (height - 24 * s) / 2f)
        c.scale(s, s)
        draw24(c)
        c.restore()
    }

    private fun line(c: Canvas, a: Float, b: Float, x: Float, y: Float) = c.drawLine(a, b, x, y, p)
    private fun poly(c: Canvas, vararg v: Float) {
        val path = Path(); path.moveTo(v[0], v[1])
        var i = 2
        while (i < v.size) { path.lineTo(v[i], v[i + 1]); i += 2 }
        c.drawPath(path, p)
    }
    private fun dot(c: Canvas, x: Float, y: Float, r: Float = 1.2f) {
        p.style = Paint.Style.FILL; c.drawCircle(x, y, r, p); p.style = Paint.Style.STROKE
    }

    private fun draw24(c: Canvas) {
        when (kind) {
            "home" -> { poly(c, 3.5f, 11f, 12f, 4f, 20.5f, 11f); poly(c, 6f, 9.5f, 6f, 19.5f, 18f, 19.5f, 18f, 9.5f) }
            "chat" -> { c.drawRoundRect(RectF(4f, 5f, 20f, 16f), 3f, 3f, p); poly(c, 9f, 16f, 9f, 20f, 13.5f, 16f) }
            "mic" -> {
                c.drawRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, p)
                c.drawArc(RectF(6f, 8f, 18f, 18f), 0f, 180f, false, p)
                line(c, 12f, 18f, 12f, 21f); line(c, 9f, 21f, 15f, 21f)
            }
            "gear" -> {
                c.drawCircle(12f, 12f, 3f, p); c.drawCircle(12f, 12f, 6.2f, p)
                for (k in 0 until 8) {
                    val a = k * PI / 4
                    line(c, 12f + (6.2f * cos(a)).toFloat(), 12f + (6.2f * sin(a)).toFloat(), 12f + (9f * cos(a)).toFloat(), 12f + (9f * sin(a)).toFloat())
                }
            }
            "bell" -> {
                val path = Path(); path.moveTo(6f, 16f); path.lineTo(6f, 11f); path.arcTo(RectF(6f, 5f, 18f, 17f), 180f, 180f)
                path.lineTo(18f, 16f); path.lineTo(19.5f, 18f); path.lineTo(4.5f, 18f); path.close(); c.drawPath(path, p)
                line(c, 10f, 20.5f, 14f, 20.5f)
            }
            "back" -> { line(c, 19f, 12f, 5f, 12f); poly(c, 11f, 6f, 5f, 12f, 11f, 18f) }
            "plus" -> { line(c, 12f, 5f, 12f, 19f); line(c, 5f, 12f, 19f, 12f) }
            "check" -> poly(c, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
            "globe" -> { c.drawCircle(12f, 12f, 8.5f, p); c.drawOval(RectF(7f, 3.5f, 17f, 20.5f), p); line(c, 3.5f, 12f, 20.5f, 12f) }
            "person" -> { c.drawCircle(12f, 8f, 4f, p); c.drawArc(RectF(5f, 14f, 19f, 30f), 180f, 180f, false, p) }
            "key" -> { c.drawCircle(8f, 15f, 3.5f, p); line(c, 10.5f, 12.5f, 19f, 4f); line(c, 16f, 7f, 18.5f, 9.5f) }
            "memory" -> {
                c.drawOval(RectF(5f, 4f, 19f, 9f), p)
                line(c, 5f, 6.5f, 5f, 17.5f); line(c, 19f, 6.5f, 19f, 17.5f)
                c.drawArc(RectF(5f, 9.5f, 19f, 14.5f), 0f, 180f, false, p)
                c.drawArc(RectF(5f, 15f, 19f, 20f), 0f, 180f, false, p)
            }
            "shield" -> {
                val path = Path(); path.moveTo(12f, 3f); path.lineTo(19f, 6f); path.lineTo(19f, 11f)
                path.cubicTo(19f, 16f, 16f, 19.5f, 12f, 21f); path.cubicTo(8f, 19.5f, 5f, 16f, 5f, 11f); path.lineTo(5f, 6f); path.close()
                c.drawPath(path, p); poly(c, 8.8f, 12f, 11f, 14.2f, 15.2f, 9.6f)
            }
            "smile" -> { c.drawCircle(12f, 12f, 8.5f, p); dot(c, 9f, 10f); dot(c, 15f, 10f); c.drawArc(RectF(8f, 8.5f, 16f, 16.5f), 25f, 130f, false, p) }
            "grid" -> {
                c.drawRoundRect(RectF(4f, 4f, 10.5f, 10.5f), 1.5f, 1.5f, p); c.drawRoundRect(RectF(13.5f, 4f, 20f, 10.5f), 1.5f, 1.5f, p)
                c.drawRoundRect(RectF(4f, 13.5f, 10.5f, 20f), 1.5f, 1.5f, p); c.drawRoundRect(RectF(13.5f, 13.5f, 20f, 20f), 1.5f, 1.5f, p)
            }
            "palette" -> { c.drawCircle(12f, 12f, 8.5f, p); dot(c, 8.5f, 10f); dot(c, 12f, 7.5f); dot(c, 15.5f, 10f); dot(c, 9f, 14.5f) }
            "lock" -> {
                c.drawRoundRect(RectF(6f, 11f, 18f, 20f), 2f, 2f, p)
                c.drawArc(RectF(8f, 4f, 16f, 12f), 180f, 180f, false, p); line(c, 8f, 8f, 8f, 11f); line(c, 16f, 8f, 16f, 11f)
            }
            "code" -> { poly(c, 8f, 7f, 3f, 12f, 8f, 17f); poly(c, 16f, 7f, 21f, 12f, 16f, 17f); line(c, 14f, 5f, 10f, 19f) }
            "info" -> { c.drawCircle(12f, 12f, 8.5f, p); line(c, 12f, 11f, 12f, 16.5f); dot(c, 12f, 7.8f, 1.1f) }
            "send" -> poly(c, 3.5f, 11f, 20.5f, 3.5f, 13f, 20.5f, 11f, 13f, 3.5f, 11f)
            "stop" -> c.drawRoundRect(RectF(7f, 7f, 17f, 17f), 2f, 2f, p)
            "pencil" -> { poly(c, 4f, 20f, 5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f, 4f, 20f) }
            "menu" -> { line(c, 4f, 7f, 20f, 7f); line(c, 4f, 12f, 20f, 12f); line(c, 4f, 17f, 20f, 17f) }
            "trash" -> { line(c, 4f, 7f, 20f, 7f); poly(c, 6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f); poly(c, 9.5f, 7f, 9.5f, 4f, 14.5f, 4f, 14.5f, 7f) }
            "tasks" -> { poly(c, 4f, 7f, 6f, 9f, 9f, 5.5f); line(c, 12f, 7f, 20f, 7f); poly(c, 4f, 17f, 6f, 19f, 9f, 15.5f); line(c, 12f, 17f, 20f, 17f) }
            "instagram" -> { c.drawRoundRect(RectF(4f, 4f, 20f, 20f), 5.5f, 5.5f, p); c.drawCircle(12f, 12f, 3.8f, p); dot(c, 16.6f, 7.4f, 1f) }
            "share" -> { c.drawCircle(6f, 12f, 2.4f, p); c.drawCircle(18f, 6f, 2.4f, p); c.drawCircle(18f, 18f, 2.4f, p); line(c, 8.2f, 10.8f, 15.8f, 7.2f); line(c, 8.2f, 13.2f, 15.8f, 16.8f) }
            "apps" -> { c.drawRoundRect(RectF(4f, 5f, 20f, 19f), 2.5f, 2.5f, p); line(c, 4f, 9.5f, 20f, 9.5f) }
            "cloud" -> { val path = Path(); path.moveTo(7f, 18f); path.arcTo(RectF(3f, 10f, 9f, 18f), 90f, 180f); path.arcTo(RectF(6f, 5f, 16f, 15f), 180f, 190f); path.arcTo(RectF(13f, 9f, 21f, 18f), 270f, 180f); path.close(); c.drawPath(path, p) }
            else -> c.drawCircle(12f, 12f, 8f, p)
        }
    }
}

enum class OrbState(val label: String) {
    IDLE("Idle"), LISTENING("Listening"), THINKING("Thinking"), SPEAKING("Speaking"),
    EXECUTING("Working"), CONNECTED("Connected"), ERROR("Needs attention")
}

/**
 * Dotted particle orb: a lavender disc, a dense front-hemisphere dot cloud (denser toward the rim because
 * of the projection), and two slowly orbiting rings. Everything is drawn inside the view bounds.
 * Animation stops when hidden, with reduce-motion, or in Android battery saver (static frame instead).
 */
class OrbView(ctx: Context) : View(ctx) {
    var accent = Color.WHITE
    var accent2 = Color.GRAY
    var errorColor = Color.RED
    var discHi = Color.WHITE
    var discLo = Color.LTGRAY
    var reduceMotion = false
        set(v) { field = v; restart() }
    var state = OrbState.IDLE
        set(v) { field = v; if (!animated()) invalidate() }
    var level = 0f
    private val dots: Array<FloatArray>
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var running = false
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val tick = object : Runnable {
        override fun run() { if (!running) return; invalidate(); postDelayed(this, 33L) }
    }
    private var cacheKey = 0L
    private var glow: Shader? = null
    private var disc: Shader? = null
    private var ring: Shader? = null

    init {
        val n = if (pm.isPowerSaveMode) 420 else 820
        dots = Array(n) { i ->
            val y = 1f - (i / (n - 1f)) * 2f
            val r = sqrt(max(0f, 1f - y * y))
            val th = (i * 2.399963f)
            floatArrayOf(cos(th) * r, y, sin(th) * r)
        }
        contentDescription = "Zara status orb"
    }

    fun styleFrom(t: ZTheme) {
        accent = t.accent; accent2 = t.accent2; errorColor = t.danger
        discHi = if (t.dark) Themes.mix(t.surface2, t.text, 0.14f) else Color.WHITE
        discLo = Themes.mix(if (t.dark) t.surface else t.bg2, Color.parseColor("#8E55E8"), if (t.dark) 0.16f else 0.14f)
        cacheKey = 0L; invalidate()
    }

    private fun animated() = !reduceMotion && !pm.isPowerSaveMode
    private fun restart() { stop(); if (animated() && isShown) start() else invalidate() }
    private fun start() { if (running || !animated()) return; running = true; post(tick) }
    private fun stop() { running = false; removeCallbacks(tick) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (animated()) start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); if (v == VISIBLE) restart() else stop() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val s = min(w, h)
        val bigR = s * 0.335f
        val tm = if (animated()) SystemClock.uptimeMillis() / 1000f else 0.8f
        val err = state == OrbState.ERROR
        val col = if (err) errorColor else accent
        val col2 = if (err) errorColor else accent2
        val ringCol = if (err) errorColor else Themes.mix(accent2, Color.parseColor("#8E55E8"), 0.55f)

        val key = (w.toInt() * 31L + h.toInt()) * 31L + col * 17L + col2 + discHi * 7L + discLo * 13L
        if (key != cacheKey) {
            cacheKey = key
            glow = RadialGradient(cx, cy, s * 0.49f, intArrayOf(Themes.alpha(col, 72), Themes.alpha(col, 0)), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
            disc = RadialGradient(cx - bigR * 0.25f, cy - bigR * 0.3f, bigR * 1.25f, intArrayOf(Themes.alpha(discHi, 240), Themes.alpha(discLo, 225)), null, Shader.TileMode.CLAMP)
            ring = SweepGradient(cx, cy, intArrayOf(Themes.alpha(ringCol, 0), Themes.alpha(ringCol, 230), Themes.alpha(ringCol, 0)), floatArrayOf(0f, 0.5f, 1f))
        }

        val speed = when (state) { OrbState.THINKING -> 1.5f; OrbState.LISTENING -> 0.5f; OrbState.SPEAKING -> 0.75f; OrbState.EXECUTING -> 1.1f; else -> 0.2f }
        val ang = tm * speed
        val lv = if (state == OrbState.LISTENING || state == OrbState.SPEAKING) level else 0f
        val breathe = 1f + 0.02f * sin(tm * 1.4f) + lv * 0.06f
        val R = bigR * breathe

        paint.shader = glow; c.drawCircle(cx, cy, s * 0.49f, paint)
        paint.shader = disc; c.drawCircle(cx, cy, R * 0.99f, paint)
        paint.shader = null

        val ca = cos(ang); val sa = sin(ang)
        val tilt = 0.32f; val ct = cos(tilt); val st = sin(tilt)
        val base = max(0.9f, R / 135f)
        for (i in dots.indices) {
            val d = dots[i]
            val x1 = d[0] * ca + d[2] * sa
            val z1 = -d[0] * sa + d[2] * ca
            val y2 = d[1] * ct - z1 * st
            val z2 = d[1] * st + z1 * ct
            if (z2 < -0.12f) continue                      // front hemisphere only, like a lit globe
            val depth = ((z2 + 1f) / 2f).coerceIn(0f, 1f)
            var bright = 0.3f + 0.7f * depth
            var rad = base * (0.55f + 1.4f * depth)
            var disp = 1f
            when (state) {
                OrbState.THINKING -> bright = (bright + 0.5f * (0.5f + 0.5f * sin(d[1] * 5f - tm * 4.5f))).coerceIn(0f, 1f)
                OrbState.SPEAKING -> disp = 1f + 0.05f * sin(tm * 9f + i * 0.7f) * (0.4f + level)
                OrbState.LISTENING -> disp = 1f + 0.03f * sin(tm * 3f + i * 0.3f) * (0.3f + level * 1.6f)
                OrbState.EXECUTING -> { val rg = (sin(tm * 3f - hypot(d[0], d[1]) * 4f) * 0.5f + 0.5f); bright = (bright + 0.4f * rg).coerceIn(0f, 1f); rad *= (1f + 0.25f * rg) }
                else -> {}
            }
            paint.color = Themes.alpha(Themes.mix(col2, col, depth), (60 + 195 * bright).toInt().coerceIn(0, 255))
            c.drawCircle(cx + x1 * R * disp, cy + y2 * R * disp, rad, paint)
        }

        // orbiting rings (sweep gradient makes each a fading arc)
        ringPaint.shader = ring
        val spin = tm * (if (state == OrbState.THINKING) 120f else 38f)
        ringPaint.strokeWidth = max(1.5f, s * 0.006f)
        c.save(); c.rotate(spin, cx, cy)
        c.drawCircle(cx, cy, R * (1.14f + lv * 0.05f), ringPaint)
        c.restore()
        ringPaint.strokeWidth = max(1f, s * 0.0035f); ringPaint.alpha = 130
        c.save(); c.rotate(-spin * 0.6f + 140f, cx, cy)
        c.drawCircle(cx, cy, R * 1.26f, ringPaint)
        c.restore()
        ringPaint.alpha = 255; ringPaint.shader = null
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val s = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(s, s)
    }
}

/** Simple friendly avatars used for the optional floating overlay and the avatar picker. */
class AvatarView(ctx: Context, var kind: String, var base: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    companion object {
        val kinds = listOf("orb", "circle", "ghost", "cat", "droid", "drop", "hexagon", "blob")
        fun label(k: String) = k.replaceFirstChar { it.uppercase() }
        fun colorFor(k: String): Int = when (k) {
            "orb" -> Color.parseColor("#FF6B3D"); "circle" -> Color.parseColor("#8E55E8"); "ghost" -> Color.parseColor("#F4F4F8")
            "cat" -> Color.parseColor("#F29A3A"); "droid" -> Color.parseColor("#9DB0D8"); "drop" -> Color.parseColor("#33D1C9")
            "hexagon" -> Color.parseColor("#F26D6D"); else -> Color.parseColor("#7FD66B")
        }
    }
    init { contentDescription = "Zara avatar" }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2; val r = min(w, h) * 0.38f
        val col = colorFor(kind)
        p.style = Paint.Style.FILL; p.color = col
        when (kind) {
            "ghost" -> {
                val path = Path()
                path.moveTo(cx - r, cy + r); path.lineTo(cx - r, cy)
                path.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f)
                path.lineTo(cx + r, cy + r)
                val seg = 2 * r / 4f
                for (k in 0 until 4) { val x0 = cx + r - k * seg; path.quadTo(x0 - seg / 2, cy + r * 0.72f + (if (k % 2 == 0) r * 0.0f else 0f), x0 - seg, cy + r) }
                path.close(); c.drawPath(path, p)
            }
            "cat" -> {
                c.drawCircle(cx, cy + r * 0.1f, r, p)
                val e1 = Path(); e1.moveTo(cx - r, cy - r * 0.2f); e1.lineTo(cx - r * 0.85f, cy - r * 1.15f); e1.lineTo(cx - r * 0.2f, cy - r * 0.75f); e1.close()
                val e2 = Path(); e2.moveTo(cx + r, cy - r * 0.2f); e2.lineTo(cx + r * 0.85f, cy - r * 1.15f); e2.lineTo(cx + r * 0.2f, cy - r * 0.75f); e2.close()
                c.drawPath(e1, p); c.drawPath(e2, p)
            }
            "droid" -> {
                c.drawRoundRect(RectF(cx - r, cy - r * 0.8f, cx + r, cy + r), r * 0.4f, r * 0.4f, p)
                p.color = Themes.mix(col, Color.BLACK, 0.2f); c.drawRect(cx - 2f, cy - r * 1.15f, cx + 2f, cy - r * 0.8f, p)
                c.drawCircle(cx, cy - r * 1.15f, r * 0.14f, p)
            }
            "drop" -> {
                val path = Path(); path.moveTo(cx, cy - r * 1.15f)
                path.cubicTo(cx + r * 0.2f, cy - r * 0.5f, cx + r, cy - r * 0.1f, cx + r, cy + r * 0.3f)
                path.arcTo(RectF(cx - r, cy - r * 0.4f, cx + r, cy + r), 0f, 180f)
                path.cubicTo(cx - r, cy - r * 0.1f, cx - r * 0.2f, cy - r * 0.5f, cx, cy - r * 1.15f); path.close(); c.drawPath(path, p)
            }
            "hexagon" -> {
                val path = Path()
                for (k in 0 until 6) { val a = Math.toRadians(60.0 * k - 30); val x = cx + (r * 1.1f * cos(a)).toFloat(); val y = cy + (r * 1.1f * sin(a)).toFloat(); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                path.close(); c.drawPath(path, p)
            }
            "blob" -> { c.drawOval(RectF(cx - r * 1.1f, cy - r * 0.85f, cx + r * 1.1f, cy + r * 0.95f), p); c.drawCircle(cx - r * 0.5f, cy - r * 0.6f, r * 0.5f, p) }
            "orb" -> {
                p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f, Color.WHITE, col, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, p); p.shader = null
            }
            else -> c.drawCircle(cx, cy, r, p)
        }
        // face
        p.color = Color.parseColor("#26202A")
        c.drawCircle(cx - r * 0.35f, cy - r * 0.1f, r * 0.09f, p); c.drawCircle(cx + r * 0.35f, cy - r * 0.1f, r * 0.09f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.1f; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(RectF(cx - r * 0.35f, cy - r * 0.05f, cx + r * 0.35f, cy + r * 0.5f), 20f, 140f, false, p)
    }
}
