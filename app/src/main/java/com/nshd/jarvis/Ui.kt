package com.nshd.jarvis

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ViewOutlineProvider
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Tiny programmatic view toolkit bound to the active theme. */
class Ui(val ctx: Context, val t: ZTheme) {
    fun dp(v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun lp(w: Int = -1, h: Int = -2, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    /**
     * Margin-aware params. Width defaults to WRAP when only side margins are given (side-by-side items),
     * and to MATCH otherwise (stacked items). Pass [w] explicitly to override.
     */
    fun lpm(w: Int = UNSET, h: Int = -2, l: Int = 0, tp: Int = 0, r: Int = 0, b: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams {
        val width = if (w != UNSET) w else if (tp == 0 && b == 0 && (l != 0 || r != 0)) -2 else -1
        return LinearLayout.LayoutParams(width, h, weight).apply { setMargins(dp(l), dp(tp), dp(r), dp(b)) }
    }
    private companion object { const val UNSET = Int.MIN_VALUE }

    fun shape(fill: Int, radius: Float = 16f, stroke: Int = 0, strokeDp: Float = 1f) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(strokeDp), stroke)
    }

    /** Touch feedback: an accent ripple bounded by a rounded mask (or a plain rectangle when radius is 0). */
    fun ripple(content: Drawable?, radius: Float = 0f): Drawable {
        val mask: Drawable = if (radius > 0f) shape(Color.WHITE, radius) else ColorDrawable(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(Themes.alpha(t.accent, 64)), content, mask)
    }

    fun pageBackground(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bg, t.bg2))

    fun tv(text: CharSequence, sp: Float = 15f, color: Int = t.text, bold: Boolean = false, gravity: Int = Gravity.START) =
        TextView(ctx).apply {
            this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); this.gravity = gravity
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

    fun dim(text: CharSequence, sp: Float = 12.5f) = tv(text, sp, t.dim).apply { setLineSpacing(0f, 1.12f) }

    fun vbox(pad: Int = 0) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad)) }
    fun hbox(center: Boolean = true) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; if (center) gravity = Gravity.CENTER_VERTICAL }
    fun space(h: Int) = View(ctx).apply { layoutParams = lp(-1, dp(h)) }

    fun card(pad: Int = 16): LinearLayout = vbox(pad).apply {
        background = shape(t.surface, 20f, t.stroke)
        outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true   // keeps row ripples inside the rounded corners
    }

    fun divider() = View(ctx).apply { setBackgroundColor(t.stroke); layoutParams = lp(-1, dp(1)) }

    fun section(label: String) = tv(label.uppercase(), 11f, t.accentText, true).apply {
        letterSpacing = 0.1f; setPadding(dp(6), dp(20), 0, dp(8))
    }

    fun button(label: String, filled: Boolean = true, danger: Boolean = false, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(18), dp(10), dp(18), dp(10))
        val c = if (danger) t.danger else t.accent
        if (filled) { setTextColor(Themes.bestOn(c)); background = ripple(shape(c, 26f), 26f) }
        else { setTextColor(if (danger) t.danger else t.accentText); background = ripple(shape(Color.TRANSPARENT, 26f, c, 1.4f), 26f) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER; minHeight = dp(40)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        if (selected) { setTextColor(t.onAccent); background = ripple(shape(t.accent, 22f), 22f) }
        else { setTextColor(t.text); background = ripple(shape(Color.TRANSPARENT, 22f, t.stroke, 1.2f), 22f) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    fun icon(kind: String, size: Int = 24, color: Int = t.accentText) = IconView(ctx, kind, color).apply { layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)) }

    fun badge(kind: String, size: Int = 44): FrameLayout = FrameLayout(ctx).apply {
        background = shape(t.surface2, 14f, t.stroke)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        addView(IconView(ctx, kind, t.accentText), FrameLayout.LayoutParams(dp(size * 0.55f), dp(size * 0.55f), Gravity.CENTER))
    }

    fun iconButton(kind: String, desc: String, size: Int = 44, onClick: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        background = ripple(shape(t.surface, 14f, t.stroke), 14f)
        minimumWidth = dp(size); minimumHeight = dp(size)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        contentDescription = desc; isClickable = true; isFocusable = true
        addView(IconView(ctx, kind, t.text), FrameLayout.LayoutParams(dp(size * 0.5f), dp(size * 0.5f), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    fun row(iconKind: String?, title: String, sub: String? = null, trailing: View? = null, chevron: Boolean = true, onClick: (() -> Unit)? = null): LinearLayout =
        hbox().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12)); minimumHeight = dp(56)
            if (iconKind != null) addView(badge(iconKind), lpm(dp(44), dp(44), r = 14))
            val col = vbox().apply {
                addView(tv(title, 15f, t.text, true))
                if (!sub.isNullOrBlank()) addView(dim(sub).apply { setPadding(0, dp(3), 0, 0) })
            }
            addView(col, lp(0, -2, 1f))
            if (trailing != null) addView(trailing, lpm(l = 8))
            if (chevron && onClick != null) addView(tv("›", 22f, t.dim), lpm(l = 8))
            if (onClick != null) { background = ripple(null, 0f); isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
        }

    fun switchView(checked: Boolean, desc: String, onChange: (Boolean) -> Unit): Switch = Switch(ctx).apply {
        isChecked = checked; contentDescription = desc
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(t.accent, t.dim))
        trackTintList = ColorStateList(states, intArrayOf(Themes.alpha(t.accent, 120), Themes.alpha(t.dim, 90)))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    fun switchRow(iconKind: String?, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = switchView(checked, title, onChange)
        val r = row(iconKind, title, sub, sw, false, null)
        r.background = ripple(null, 0f); r.isClickable = true; r.isFocusable = true
        r.setOnClickListener { sw.toggle() }
        return r
    }

    fun slider(min: Int, max: Int, value: Int, onChange: (Int) -> Unit): SeekBar = SeekBar(ctx).apply {
        this.max = max - min; progress = (value - min).coerceIn(0, max - min)
        progressTintList = ColorStateList.valueOf(t.accent); thumbTintList = ColorStateList.valueOf(t.accent)
        progressBackgroundTintList = ColorStateList.valueOf(Themes.alpha(t.dim, 90))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p + min) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    fun labeledSlider(label: String, valueText: (Int) -> String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): LinearLayout =
        vbox().apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val head = hbox()
            val name = tv(label, 14f, t.text, true)
            val v = dim(valueText(value), 13f)
            head.addView(name, lp(0, -2, 1f)); head.addView(v)
            addView(head)
            addView(slider(min, max, value) { n -> v.text = valueText(n); onChange(n) }, lpm(tp = 4))
        }

    fun input(hint: String, value: String = "", secret: Boolean = false, multiline: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint; setText(value); setTextColor(t.text); setHintTextColor(t.dim)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = shape(t.surface2, 14f, t.stroke)
        setPadding(dp(14), dp(12), dp(14), dp(12)); minHeight = dp(48)
        inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) { gravity = Gravity.TOP; setHorizontallyScrolling(false) }
    }

    fun topBar(title: String, subtitle: String?, onBack: () -> Unit): LinearLayout = hbox().apply {
        setPadding(dp(16), dp(12), dp(16), dp(8))
        addView(iconButton("back", "Back", 44, onBack), lpm(dp(44), dp(44), r = 14))
        val col = vbox()
        col.addView(tv(title, 20f, t.text, true))
        if (!subtitle.isNullOrBlank()) col.addView(dim(subtitle).apply { setPadding(0, dp(2), 0, 0) })
        addView(col, lp(0, -2, 1f))
    }

    fun scroll(content: View, padH: Int = 16): ScrollView = ScrollView(ctx).apply {
        isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER
        content.setPadding(if (padH > 0) dp(padH) else content.paddingLeft, content.paddingTop,
            if (padH > 0) dp(padH) else content.paddingRight, maxOf(content.paddingBottom, dp(28)))
        addView(content, ViewGroup.LayoutParams(-1, -2))
    }

    /** Coloured status chip used for task results. */
    fun statusChip(status: String): TextView {
        val c = when (status) {
            "COMPLETED" -> t.ok; "PARTIALLY_COMPLETED", "NEEDS_USER_ACTION", "COULD_NOT_VERIFY" -> t.warn
            else -> t.danger
        }
        return tv(status.replace('_', ' '), 10.5f, c, true).apply {
            letterSpacing = 0.05f; setPadding(dp(10), dp(4), dp(10), dp(4))
            background = shape(Themes.alpha(c, 28), 12f, Themes.alpha(c, 120), 1f)
        }
    }
}
