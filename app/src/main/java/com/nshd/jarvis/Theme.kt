package com.nshd.jarvis

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

class ZTheme(
    val id: String, val label: String, val blurb: String, val dark: Boolean,
    val bg: Int, val bg2: Int, val surface: Int, val surface2: Int,
    val text: Int, val dim: Int, val accent: Int, val accent2: Int, val stroke: Int,
    val danger: Int, val ok: Int, val warn: Int
) {
    /** Readable text colour to place on top of [accent]. */
    val onAccent: Int get() = Themes.bestOn(accent)

    /** Accent adjusted so small accent-coloured text stays readable on [bg]. */
    val accentText: Int by lazy {
        var c = accent
        var guard = 0
        while (Themes.contrast(c, bg) < 4.5 && guard++ < 24) {
            c = if (dark) Themes.mix(c, Color.WHITE, 0.08f) else Themes.mix(c, Color.BLACK, 0.08f)
        }
        c
    }

    fun withAccent(a: Int) = ZTheme(id, label, blurb, dark, bg, bg2, surface, surface2, text, dim, a, accent2, stroke, danger, ok, warn)
}

object Themes {
    private fun c(s: String) = Color.parseColor(s)

    val sunset = ZTheme("sunset", "Sunset", "Warm peach · coral accent", false,
        c("#FFEFE8"), c("#FFE2D8"), c("#FFF8F4"), c("#FFFFFF"), c("#2B0F1C"), c("#7E4466"),
        c("#FF6B3D"), c("#F44B6E"), c("#E6CBC3"), c("#C62F3B"), c("#1E8E5A"), c("#B26A00"))
    val amoled = ZTheme("amoled", "AMOLED", "True black · champagne accent", true,
        c("#000000"), c("#000000"), c("#0D0D0F"), c("#18181B"), c("#F3EFE8"), c("#A19D95"),
        c("#D8BF8E"), c("#B89A66"), c("#2A2A2E"), c("#E5645A"), c("#6CC38A"), c("#E0A94F"))
    val dark = ZTheme("dark", "Dark", "Charcoal · soft blue accent", true,
        c("#0F1013"), c("#0F1013"), c("#17181C"), c("#202228"), c("#ECEDEF"), c("#9FA2AB"),
        c("#8FB4FF"), c("#B79CFF"), c("#2B2D34"), c("#E5645A"), c("#6CC38A"), c("#E0A94F"))
    val light = ZTheme("light", "Light", "Warm white · bronze accent", false,
        c("#F6F4F1"), c("#EFECE7"), c("#FFFFFF"), c("#F0EDE8"), c("#1B1A18"), c("#625E57"),
        c("#9A6B16"), c("#C8A15A"), c("#DDD8D0"), c("#B3261E"), c("#1E7A4C"), c("#8A5A00"))

    val all = listOf(sunset, amoled, dark, light)

    fun resolve(ctx: Context, p: Prefs): ZTheme {
        var id = p.s("theme", Brand.DEFAULT_THEME)
        if (id == "system") {
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            id = if (night) "amoled" else "light"
        }
        val base = all.firstOrNull { it.id == id } ?: sunset
        val acc = p.i("accent", 0)
        return if (acc != 0) base.withAccent(acc) else base
    }

    fun lum(color: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(Color.red(color)) + 0.7152 * ch(Color.green(color)) + 0.0722 * ch(Color.blue(color))
    }
    fun contrast(a: Int, b: Int): Double {
        val la = lum(a); val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
    fun bestOn(bg: Int): Int {
        val dark = Color.parseColor("#17120B")
        return if (contrast(Color.WHITE, bg) >= contrast(dark, bg)) Color.WHITE else dark
    }
    fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    fun alpha(c: Int, a: Int): Int = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
