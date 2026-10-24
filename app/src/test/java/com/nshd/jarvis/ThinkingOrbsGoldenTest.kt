package com.nshd.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Parity test: the Kotlin port must reproduce the original thinking-orbs library's own golden vectors
 * (72 frames, 11,288 dots, tolerance 1e-4). Vectors © 2026 Jakub Antalik, MIT — see THIRD_PARTY_NOTICES.md.
 */
class ThinkingOrbsGoldenTest {
    private fun parseOpts(s: String): Map<String, Double> = s.split(",").filter { it.isNotBlank() }.associate { val (k, v) = it.split("="); k to v.toDouble() }

    private val lines: List<String> = javaClass.classLoader!!.getResourceAsStream("orbs-golden.txt")!!.bufferedReader().readLines()

    @Test fun presetResolutionMatchesTheLibrary() {
        var n = 0
        for (l in lines) if (l.startsWith("RES ")) {
            val p = l.split(" ", limit = 6)
            val mine = ThinkingOrbs.resolvePreset(p[1], p[2].toInt())
            val opts = parseOpts(p[5])
            assertEquals(p[3], mine.mode)
            assertEquals(p[4].toDouble(), mine.speed, 1e-9)
            assertEquals(opts.keys, mine.opts.keys)
            for ((k, v) in opts) assertEquals("$k for ${p[1]}-${p[2]}", v, mine.opts.getValue(k), 1e-6)
            n++
        }
        assertEquals(18, n)
    }

    @Test fun everyFrameMatchesTheGoldenDotsAndLines() {
        var frames = 0; var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("CASE ")) {
                val p = l.split(" ")
                val gd = lines[i + 1].removePrefix("D").trim().split(" ").filter { it.isNotEmpty() }.map { it.toDouble() }
                val gl = lines[i + 2].removePrefix("L").trim().split(" ").filter { it.isNotEmpty() }.map { it.toDouble() }
                val r = ThinkingOrbs.resolvePreset(p[2], p[3].toInt())
                val f = ThinkingOrbs.frameFor(r.mode, p[3].toDouble(), p[5].toDouble(), r.opts)
                assertEquals("dot count ${p[1]}", p[6].toInt(), f.dots.size)
                assertEquals("line count ${p[1]}", p[7].toInt(), f.lines.size)
                for ((n, d) in f.dots.withIndex()) {
                    val e = doubleArrayOf(d.x, d.y, d.z, d.r, d.white, d.a)
                    for (q in 0 until 6) assertTrue("${p[1]} dot $n field $q", abs(e[q] - gd[n * 6 + q]) < 1e-4)
                }
                for ((n, d) in f.lines.withIndex()) {
                    val e = doubleArrayOf(d.x1, d.y1, d.x2, d.y2, d.white, d.a, d.w)
                    for (q in 0 until 7) assertTrue("${p[1]} line $n field $q", abs(e[q] - gl[n * 7 + q]) < 1e-4)
                }
                frames++; i += 3
            } else i++
        }
        assertEquals(72, frames)
    }
}
