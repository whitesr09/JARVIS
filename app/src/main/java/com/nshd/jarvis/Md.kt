package com.nshd.jarvis

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** Minimal Markdown: fenced code, headings, bullets, **bold**, `inline code`. */
object Md {
    private val inline = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
    private val fence = Regex("```([A-Za-z0-9_+#-]*)\\n([\\s\\S]*?)```")

    fun extractCode(src: String): List<Pair<String, String>> = fence.findAll(src).map { it.groupValues[1] to it.groupValues[2].trimEnd() }.toList()

    fun render(src: String, t: ZTheme): CharSequence {
        val sb = SpannableStringBuilder()
        val codeBg = Themes.mix(t.bg2, t.text, 0.09f)
        var inCode = false
        val lines = src.split("\n")
        var first = true
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) { inCode = !inCode; continue }
            if (!first) sb.append("\n")
            first = false
            val start = sb.length
            if (inCode) {
                sb.append(" ").append(line).append(" ")
                sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(codeBg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.9f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            var text = line
            val h = Regex("^#{1,3}\\s+(.*)$").find(text)
            val bullet = Regex("^\\s*[-*]\\s+(.*)$").find(text)
            if (h != null) text = h.groupValues[1] else if (bullet != null) text = "•  " + bullet.groupValues[1]
            appendInline(sb, text, codeBg)
            if (h != null) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.12f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun appendInline(sb: SpannableStringBuilder, text: String, codeBg: Int) {
        var last = 0
        for (m in inline.findAll(text)) {
            sb.append(text.substring(last, m.range.first))
            val s0 = sb.length
            val tok = m.value
            if (tok.startsWith("**")) {
                sb.append(tok.substring(2, tok.length - 2))
                sb.setSpan(StyleSpan(Typeface.BOLD), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                sb.append(tok.substring(1, tok.length - 1))
                sb.setSpan(TypefaceSpan("monospace"), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(codeBg), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = m.range.last + 1
        }
        sb.append(text.substring(last))
    }
}
