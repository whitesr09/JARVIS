// Kotlin port of the geometry engine from "thinking-orbs" (https://github.com/Jakubantalik/thinking-orbs, v0.3.1).
// Copyright (c) 2026 Jakub Antalik — MIT License. The full licence text is reproduced in THIRD_PARTY_NOTICES.md.
// Port notes: pure math, no Android types. Verified against the library's own golden vectors
// (spec/orbs-golden.json: 72 frames, 11,288 dots, tolerance 1e-4) by app/src/test/.../ThinkingOrbsGoldenTest.kt.
package com.nshd.jarvis

import kotlin.math.*

class OrbDot(val x: Double, val y: Double, val z: Double, var r: Double, val white: Double, val a: Double = 1.0)
class OrbLine(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val white: Double, val a: Double, val w: Double)
class OrbFrame(val dots: List<OrbDot>, val lines: List<OrbLine>)
typealias ModeOpts = Map<String, Double>

private fun ModeOpts.g(k: String, d: Double): Double = this[k] ?: d

object ThinkingOrbs {
    // ---------------------------------------------------------------- core
    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f
    private fun frac(x: Double) = x - floor(x)
    private fun hashD(a: Double, b: Double): Double { val h = sin(a * 12.9898 + b * 78.233) * 43758.5453; return h - floor(h) }

    private fun vnoise(x: Double, y: Double): Double {
        val xi = floor(x); val yi = floor(y)
        var fx = x - xi; var fy = y - yi
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy)
        val a = hashD(xi, yi); val b = hashD(xi + 1, yi); val c = hashD(xi, yi + 1); val d = hashD(xi + 1, yi + 1)
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
    }

    private fun fibDir(i: Int, n: Int): DoubleArray {
        val golden = PI * (3 - sqrt(5.0))
        val y = 1 - (2 * (i + 0.5)) / n
        val rad = sqrt(1 - y * y)
        val a = i * golden
        return doubleArrayOf(rad * cos(a), y, rad * sin(a))
    }

    private fun angleDelta(a: Double, b: Double) = atan2(sin(a - b), cos(a - b))

    private class Proj(yaw: Double, tilt: Double, val cx: Double, val cy: Double, val scale: Double) {
        private val st = sin(tilt); private val ct = cos(tilt); private val sy = sin(yaw); private val cyw = cos(yaw)
        fun p(x: Double, y: Double, z: Double): DoubleArray {
            val x1 = x * cyw + z * sy
            val z1 = -x * sy + z * cyw
            val y1 = y * ct - z1 * st
            val z2 = y * st + z1 * ct
            return doubleArrayOf(cx + x1 * scale, cy - y1 * scale, z2)
        }
    }

    private fun radiusScale(size: Double, pow: Double) = (size / 300.0).pow(pow)

    private fun finalizeFrame(dots: List<OrbDot>, lines: List<OrbLine>, rMin: Double = 0.3): OrbFrame {
        val visible = ArrayList<OrbDot>()
        for (d in dots) { if (d.a < 0.02) continue; d.r = max(rMin, d.r); visible.add(d) }
        visible.sortBy { it.z }   // stable, ascending z = far → near
        return OrbFrame(visible, lines.filter { it.a >= 0.02 })
    }

    // ---------------------------------------------------------------- orbits (working)
    private fun frameOrbits(size: Double, t: Double, o: ModeOpts): OrbFrame {
        val cx = size / 2; val cy = size / 2
        val R = (size / 2) * 0.82
        val pt = Proj(t * 0.12, 0.3, cx, cy, 1.0)
        val rs = radiusScale(size, o.g("rsPow", 0.6))
        val dots = ArrayList<OrbDot>()
        val orbitN = o.g("orbitN", 12.0).toInt(); val ghostN = o.g("ghostN", 40.0).toInt(); val particles = o.g("particles", 3.0).toInt()
        for (orb in 0 until orbitN) {
            val h1 = hashD(orb.toDouble(), 1.7); val h2 = hashD(orb.toDouble(), 5.2); val h3 = hashD(orb.toDouble(), 8.9)
            val ro = R * (0.45 + 0.52 * h1)
            val th = h1 * 2 * PI
            val phi = acos(2 * h2 - 1)
            val nx = sin(phi) * cos(th); val ny = cos(phi); val nz = sin(phi) * sin(th)
            var ux = -ny; var uy = nx; val uz = 0.0
            val ul = max(1e-6, sqrt(ux * ux + uy * uy))
            ux /= ul; uy /= ul
            val vx = ny * uz - nz * uy; val vy = nz * ux - nx * uz; val vz = nx * uy - ny * ux
            val speed = (0.25 + 0.55 * h3) * (if (h3 > 0.5) 1 else -1)
            for (k in 0 until ghostN) {
                val a = (k.toDouble() / ghostN) * 2 * PI
                val p = pt.p((ux * cos(a) + vx * sin(a)) * ro, (uy * cos(a) + vy * sin(a)) * ro, (uz * cos(a) + vz * sin(a)) * ro)
                val depth = (p[2] / ro + 1) / 2
                dots.add(OrbDot(p[0], p[1], p[2], o.g("ghostR", 0.9) * rs, 0.72, o.g("ghostA", 0.5) * (0.4 + 0.6 * depth)))
            }
            for (m in 0 until particles) {
                val a = t * speed + (m.toDouble() / particles) * 2 * PI + h2 * 6
                val p = pt.p((ux * cos(a) + vx * sin(a)) * ro, (uy * cos(a) + vy * sin(a)) * ro, (uz * cos(a) + vz * sin(a)) * ro)
                val depth = (p[2] / ro + 1) / 2
                dots.add(OrbDot(p[0], p[1], p[2], (o.g("partR", 1.2) + o.g("partRDepth", 1.6) * depth) * rs, 0.3 - 0.22 * depth))
            }
        }
        return finalizeFrame(dots, emptyList(), o.g("rMin", 0.3))
    }

    // ---------------------------------------------------------------- lattice modes
    private class Move(val axis: Int, val lo: Double, val hi: Double, val ang: Double)
    private class Solve(val amount: DoubleArray, val active: Int)

    private fun solveCycle(time: Double, count: Int, slotDur: Double, rest: Double): Solve {
        val cyc = 2 * count * slotDur + rest
        val tc = time % cyc
        val amount = DoubleArray(count)
        var active = -1
        if (tc < 2 * count * slotDur) {
            val slot = floor(tc / slotDur).toInt()
            val p = (tc - slot * slotDur) / slotDur
            val cl = min(1.0, p / 0.7)
            val ep = 1 - (1 - cl).pow(3)
            if (slot < count) {
                for (i in 0 until slot) amount[i] = 1.0
                amount[slot] = ep; active = slot
            } else {
                val u = 2 * count - 1 - slot
                for (i in 0 until u) amount[i] = 1.0
                amount[u] = 1 - ep; active = u
            }
        }
        return Solve(amount, active)
    }

    private fun makeMoves(count: Int): List<Move> {
        val moves = ArrayList<Move>()
        for (i in 0 until count) {
            val axis = min(2, floor(hashD(i.toDouble(), 2.3) * 3).toInt())
            val lo = -1.0 + 0.5 * min(3, floor(hashD(i.toDouble(), 5.9) * 4).toInt())
            val dir = if (hashD(i.toDouble(), 7.7) < 0.5) 1 else -1
            moves.add(Move(axis, lo, lo + 0.5, dir * PI / 2))
        }
        return moves
    }

    private fun applyMoves(p0: DoubleArray, moves: List<Move>, sc: Solve): Pair<DoubleArray, Boolean> {
        var x = p0[0]; var y = p0[1]; var z = p0[2]
        var inActive = false
        for (i in moves.indices) {
            if (sc.amount[i] <= 0) continue
            val mv = moves[i]
            val coord = if (mv.axis == 0) x else if (mv.axis == 1) y else z
            if (coord < mv.lo || coord >= mv.hi) continue
            if (i == sc.active) inActive = true
            val a = mv.ang * sc.amount[i]
            val ca = cos(a); val sa = sin(a)
            if (mv.axis == 0) { val y2 = y * ca - z * sa; z = y * sa + z * ca; y = y2 }
            else if (mv.axis == 1) { val x2 = x * ca + z * sa; z = -x * sa + z * ca; x = x2 }
            else { val x2 = x * ca - y * sa; y = x * sa + y * ca; x = x2 }
        }
        return doubleArrayOf(x, y, z) to inActive
    }

    private fun frameGlobe(size: Double, t: Double, o: ModeOpts): OrbFrame {
        val spin = 0.5
        val cx = size / 2; val cy = size / 2
        val radius = (size / 2) * 0.82
        val tilt = 0.4 + 0.06 * sin(t * 0.35)
        val pt = Proj(t * spin, tilt, cx, cy, radius)
        val scan = t * (spin + (1.7 - spin) * o.g("scanMul", 1.0))
        val rs = radiusScale(size, o.g("rsPow", 0.6))
        val dimBase = o.g("dimBase", 1.0)
        val dots = ArrayList<OrbDot>()
        val latRings = o.g("latRings", 17.0).toInt(); val lonDensity = o.g("lonDensity", 44.0)
        for (li in 0..latRings) {
            val lat = -PI / 2 + (li.toDouble() / latRings) * PI
            val cosLat = cos(lat); val sinLat = sin(lat)
            val lonCount = max(1.0, Math.round(abs(cosLat) * lonDensity).toDouble()).toInt()
            for (lj in 0 until lonCount) {
                val lon = (lj.toDouble() / lonCount) * 2 * PI
                val p = pt.p(cosLat * cos(lon), sinLat, cosLat * sin(lon))
                val z = p[2]
                val depth = (z + 1) / 2
                val d = angleDelta(lon + t * spin, scan)
                val boost = exp(-(d * d) / 0.18) * max(0.0, z)
                dots.add(OrbDot(p[0], p[1], z,
                    (o.g("rBase", 0.6) + o.g("rDepth", 1.7) * depth + o.g("rBoost", 1.0) * boost) * rs,
                    o.g("inkFar", 0.62) - o.g("inkSpan", 0.54) * depth,
                    dimBase + (1 - dimBase) * min(1.0, boost)))
            }
        }
        return finalizeFrame(dots, emptyList(), o.g("rMin", 0.3))
    }

    private fun frameRubik(size: Double, t: Double, o: ModeOpts): OrbFrame {
        val cx = size / 2; val cy = size / 2
        val R = (size / 2) * 0.82
        val pt = Proj(t * 0.55, 0.35 + 0.1 * sin(t * 0.9), cx, cy, R)
        val rs = radiusScale(size, o.g("rsPow", 0.6))
        val moveCount = o.g("moveCount", 14.0).toInt()
        val moves = makeMoves(moveCount)
        val sc = solveCycle(t, moveCount, 0.42, 1.2)
        val dots = ArrayList<OrbDot>()
        val latRings = o.g("latRings", 15.0).toInt(); val lonDensity = o.g("lonDensity", 40.0)
        for (li in 0..latRings) {
            val lat = -PI / 2 + (li.toDouble() / latRings) * PI
            val cosLat = cos(lat); val sinLat = sin(lat)
            val lonCount = max(1.0, Math.round(abs(cosLat) * lonDensity).toDouble()).toInt()
            for (lj in 0 until lonCount) {
                val lon = (lj.toDouble() / lonCount) * 2 * PI
                val (v, inActive) = applyMoves(doubleArrayOf(cosLat * cos(lon), sinLat, cosLat * sin(lon)), moves, sc)
                val p = pt.p(v[0], v[1], v[2])
                val depth = (p[2] + 1) / 2
                dots.add(OrbDot(p[0], p[1], p[2],
                    (o.g("rBase", 0.6) + o.g("rDepth", 1.7) * depth + (if (inActive) o.g("rActive", 0.3) else 0.0)) * rs,
                    o.g("inkFar", 0.62) - o.g("inkSpan", 0.54) * depth - (if (inActive) 0.14 else 0.0)))
            }
        }
        return finalizeFrame(dots, emptyList(), o.g("rMin", 0.3))
    }

    private fun frameWave(size: Double, t: Double, o: ModeOpts): OrbFrame {
        val cx = size / 2; val cy = size / 2
        val R = (size / 2) * 0.874
        val pt = Proj(t * 0.18, 0.38, cx, cy, 1.0)
        val rs = radiusScale(size, o.g("rsPow", 0.6))
        val dots = ArrayList<OrbDot>()
        val rings = o.g("rings", 15.0).toInt(); val lonDensity = o.g("lonDensity", 40.0)
        for (ri in 0..rings) {
            val lat = -PI / 2 + (ri.toDouble() / rings) * PI
            val cosLat = cos(lat); val sinLat = sin(lat)
            val w = 0.62 * sin(t * 2.1 - ri * 0.52) + 0.38 * sin(t * 1.27 + ri * 0.83)
            val rr = R * (0.88 + 0.105 * w)
            val lonCount = max(1.0, Math.round(abs(cosLat) * lonDensity).toDouble()).toInt()
            for (lj in 0 until lonCount) {
                val lon = (lj.toDouble() / lonCount) * 2 * PI
                val p = pt.p(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
                val depth = (p[2] / R + 1) / 2
                val crest = max(0.0, w)
                dots.add(OrbDot(p[0], p[1], p[2], (o.g("rBase", 0.6) + o.g("rDepth", 1.7) * depth) * (1 + 0.4 * crest) * rs, 0.66 - 0.56 * depth - 0.1 * crest))
            }
        }
        return finalizeFrame(dots, emptyList(), o.g("rMin", 0.3))
    }

    // ---------------------------------------------------------------- braid (weaving)
    private fun frameBraid(size: Double, t: Double, o: ModeOpts): OrbFrame {
        val cx = size / 2; val cy = size / 2
        val R = (size / 2) * 0.76
        val pt = Proj(t * 0.4, 0.3, cx, cy, 1.0)
        val rs = radiusScale(size, o.g("rsPow", 0.6))
        val dots = ArrayList<OrbDot>()
        val ghostN = o.g("ghostN", 150.0).toInt()
        for (i in 0 until ghostN) {
            val d = fibDir(i, ghostN)
            val p = pt.p(d[0] * R, d[1] * R, d[2] * R)
            val depth = (p[2] / R + 1) / 2
            dots.add(OrbDot(p[0], p[1], p[2], 0.8 * rs, 0.78, 0.1 + 0.22 * depth))
        }
        val strandN = o.g("strandN", 52.0).toInt(); val turns = o.g("turns", 3.0)
        for (s in 0 until 3) {
            val phase = (s / 3.0) * 2 * PI
            for (i in 0 until strandN) {
                val u = (frac(i.toDouble() / strandN + t * 0.045) * 2 - 1) * 0.96
                val surf = sqrt(max(0.0, 1 - u * u))
                val endFade = min(1.0, (1 - abs(u)) / 0.1)
                val a = u * PI * turns + phase
                val weave = 1 + 0.075 * sin(u * PI * turns * 2 + phase * 2 + t * 0.8)
                val rr = surf * R * weave
                val p = pt.p(cos(a) * rr, u * R * weave, sin(a) * rr)
                val depth = (p[2] / R + 1) / 2
                dots.add(OrbDot(p[0], p[1], p[2], (o.g("rBase", 1.2) + o.g("rDepth", 1.8) * depth) * rs, 0.55 - 0.45 * depth, endFade * (0.45 + 0.55 * depth)))
            }
        }
        return finalizeFrame(dots, emptyList(), o.g("rMin", 0.3))
    }

    // ---------------------------------------------------------------- ribbon / ring (composing / breathing)
    private fun frameRibbon(size: Double, t: Double, o: ModeOpts): OrbFrame {
        val cx = size / 2; val cy = size / 2
        val R = (size / 2) * 0.78
        val spin = o.g("spin", 1.0)
        val camTilt = 0.3
        val pt = Proj(t * 0.1 * spin, camTilt, cx, cy, 1.0)
        val rs = radiusScale(size, o.g("rsPow", 0.6))
        val faceOn = (o["faceOn"] ?: 0.0) != 0.0
        val dots = ArrayList<OrbDot>()
        val ghostN = o.g("ghostN", 150.0).toInt()
        for (i in 0 until ghostN) {
            val d = fibDir(i, ghostN)
            val p = pt.p(d[0] * R, d[1] * R, d[2] * R)
            val depth = (p[2] / R + 1) / 2
            dots.add(OrbDot(p[0], p[1], p[2], 0.8 * rs, 0.78, 0.1 + 0.22 * depth))
        }
        val ya = t * 0.24 * spin
        val ta = if (faceOn) -camTilt else 0.55 + 0.3 * sin(t * 0.18) * spin
        val ux = cos(ya); val uy = 0.0; val uz = sin(ya)
        val vx = -uz * sin(ta); val vy = cos(ta); val vz = ux * sin(ta)
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val wobAmp = 0.23 * o.g("wobMul", 1.0)
        val baseR = if (faceOn) R / (1 + 0.85 * wobAmp) else R
        val baseLanes = o.g("lanes", 5.0)
        val segs = o.g("segs", 88.0).toInt()
        val lanes = max(1.0, Math.round(baseLanes * o.g("bandMul", 1.0)).toDouble()).toInt()
        for (w in 0 until lanes) {
            val laneOff = (w - (lanes - 1) / 2.0) * 0.075
            val edge = abs(w - (lanes - 1) / 2.0) / max(1.0, (lanes - 1) / 2.0)
            for (k in 0 until segs) {
                val a = (k.toDouble() / segs) * 2 * PI
                val wob = (0.16 * sin(a * 3 - t * 1.7 + w * 0.22) + 0.07 * sin(a * 5 + t * 1.1)) * o.g("wobMul", 1.0)
                val radial = if (faceOn) 1 + wob else 1.0
                val off = if (faceOn) laneOff else laneOff + wob
                val x = ux * cos(a) + vx * sin(a) + nx * off
                val y = uy * cos(a) + vy * sin(a) + ny * off
                val z = uz * cos(a) + vz * sin(a) + nz * off
                val l = sqrt(x * x + y * y + z * z)
                val rr = baseR * radial
                val p = pt.p((x / l) * rr, (y / l) * rr, (z / l) * rr)
                val depth = (p[2] / R + 1) / 2
                dots.add(OrbDot(p[0], p[1], p[2], (o.g("rBase", 1.1) + o.g("rDepth", 1.7) * depth) * (1 - 0.25 * edge) * rs, 0.52 - 0.44 * depth + 0.18 * edge, 0.4 + 0.6 * depth))
            }
        }
        return finalizeFrame(dots, emptyList(), o.g("rMin", 0.3))
    }

    // ---------------------------------------------------------------- web (connecting)
    private fun frameWeb(size: Double, t: Double, o: ModeOpts): OrbFrame {
        val cx = size / 2; val cy = size / 2
        val R = (size / 2) * 0.8 * o.g("spread", 1.0)
        val pt = Proj(t * 0.12, 0.32, cx, cy, R)
        val rs = radiusScale(size, o.g("rsPow", 0.6))
        val nodeN = o.g("nodeN", 30.0).toInt(); val thr = o.g("thr", 0.72)
        val nodeR = o.g("nodeR", 1.4); val nodeRDepth = o.g("nodeRDepth", 1.8)
        val nodes = ArrayList<DoubleArray>()
        for (i in 0 until nodeN) {
            val d = fibDir(i, nodeN)
            val x = d[0] + 0.3 * (vnoise(i * 0.31 + 9, t * 0.24) - 0.5) * 2
            val y = d[1] + 0.3 * (vnoise(i * 0.53 + 27, t * 0.21) - 0.5) * 2
            val z = d[2] + 0.3 * (vnoise(i * 0.77 + 55, t * 0.27) - 0.5) * 2
            val l = sqrt(x * x + y * y + z * z)
            nodes.add(doubleArrayOf(x / l, y / l, z / l))
        }
        val lines = ArrayList<OrbLine>(); val dots = ArrayList<OrbDot>()
        for (i in 0 until nodeN) for (j in i + 1 until nodeN) {
            val dx = nodes[i][0] - nodes[j][0]; val dy = nodes[i][1] - nodes[j][1]; val dz = nodes[i][2] - nodes[j][2]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist >= thr) continue
            val p1 = pt.p(nodes[i][0], nodes[i][1], nodes[i][2]); val p2 = pt.p(nodes[j][0], nodes[j][1], nodes[j][2])
            val depth = ((p1[2] + p2[2]) / 2 + 1) / 2
            lines.add(OrbLine(p1[0], p1[1], p2[0], p2[1], 0.42, (1 - dist / thr) * (0.3 + 0.55 * depth), max(0.6, o.g("lineW", 0.8) * rs)))
        }
        for (i in 0 until nodeN) {
            val p = pt.p(nodes[i][0], nodes[i][1], nodes[i][2])
            val depth = (p[2] + 1) / 2
            val pulse = 1 + 0.25 * sin(t * 1.4 + i * 2.7)
            dots.add(OrbDot(p[0], p[1], p[2], (nodeR + nodeRDepth * depth) * pulse * rs, 0.55 - 0.45 * depth))
        }
        val signals = o.g("signals", 5.0).toInt()
        for (s in 0 until signals) {
            val seg = floor(t * 0.55 + s * 7.31)
            val a = floor(hashD(seg, s * 3.1 + 1.7) * nodeN).toInt()
            val b = floor(hashD(seg, s * 5.7 + 4.2) * nodeN).toInt()
            if (a == b) continue
            val f = frac(t * 0.55 + s * 7.31)
            val x = lerp(nodes[a][0], nodes[b][0], f); val y = lerp(nodes[a][1], nodes[b][1], f); val z = lerp(nodes[a][2], nodes[b][2], f)
            val l = max(1e-6, sqrt(x * x + y * y + z * z))
            val p = pt.p(x / l, y / l, z / l)
            val depth = (p[2] + 1) / 2
            dots.add(OrbDot(p[0], p[1], p[2], (nodeR * 1.5 + nodeRDepth * depth) * rs, 0.05, 0.5 + 0.5 * depth))
        }
        return finalizeFrame(dots, lines, o.g("rMin", 0.3))
    }

    // ---------------------------------------------------------------- morph (shaping)
    private fun smoothE(x: Double) = x * x * (3 - 2 * x)
    private fun polyPath(verts: List<DoubleArray>): (Double) -> DoubleArray {
        val V = verts.size
        val L = DoubleArray(V); var total = 0.0
        for (i in 0 until V) { val a = verts[i]; val b = verts[(i + 1) % V]; val l = hypot(b[0] - a[0], b[1] - a[1]); L[i] = l; total += l }
        return { f ->
            var target = f * total; var i = 0
            while (target > L[i] && i < V - 1) { target -= L[i]; i++ }
            val a = verts[i]; val b = verts[(i + 1) % V]
            val ff = if (L[i] != 0.0) min(1.0, target / L[i]) else 0.0
            doubleArrayOf(a[0] + (b[0] - a[0]) * ff, a[1] + (b[1] - a[1]) * ff)
        }
    }
    private val CIRCLE: (Double) -> DoubleArray = { f -> val a = -PI / 2 + f * 2 * PI; doubleArrayOf(cos(a) * 0.24, sin(a) * 0.24) }
    private val TRIANGLE = polyPath(listOf(doubleArrayOf(0.0, -0.26), doubleArrayOf(0.24, 0.16), doubleArrayOf(-0.24, 0.16)))
    private val SQUARE = polyPath(listOf(doubleArrayOf(0.0, -0.2), doubleArrayOf(0.2, -0.2), doubleArrayOf(0.2, 0.2), doubleArrayOf(-0.2, 0.2), doubleArrayOf(-0.2, -0.2)))
    private val CYCLE = listOf(CIRCLE, TRIANGLE, SQUARE)
    private const val HOLD = 1.4
    private const val MORPH = 0.9
    private const val SEG = HOLD + MORPH

    private fun frameMorph(size: Double, t: Double, o: ModeOpts): OrbFrame {
        val K = CYCLE.size
        val tc = t % (SEG * K)
        val k = floor(tc / SEG).toInt()
        val local = tc - k * SEG
        val m = if (local > HOLD) smoothE((local - HOLD) / MORPH) else 0.0
        val sprd = o.g("spread", 1.0)
        val pA = CYCLE[k]; val pB = CYCLE[(k + 1) % K]
        val M = 160
        val pts = ArrayList<DoubleArray>()
        for (i in 0 until M) {
            val f = i.toDouble() / M
            val a = pA(f); val b = pB(f)
            pts.add(doubleArrayOf((a[0] + (b[0] - a[0]) * m) * sprd, (a[1] + (b[1] - a[1]) * m) * sprd))
        }
        val L = DoubleArray(M); var total = 0.0
        for (i in 0 until M) { val a = pts[i]; val b = pts[(i + 1) % M]; val l = hypot(b[0] - a[0], b[1] - a[1]); L[i] = l; total += l }
        val n = max(6.0, Math.round(34 * o.g("iconD", 1.0)).toDouble()).toInt()
        val re = o.g("rDot", 0.021) * 1.35 * sprd
        val pulse = 1 + 0.02 * sin(local * 3.1)
        val dots = ArrayList<OrbDot>()
        val c2 = size / 2
        var seg = 0; var acc = 0.0
        for (k2 in 0 until n) {
            val target = (k2.toDouble() / n) * total
            while (acc + L[seg] < target && seg < M - 1) { acc += L[seg]; seg++ }
            val a = pts[seg]; val b = pts[(seg + 1) % M]
            val f = if (L[seg] != 0.0) min(1.0, (target - acc) / L[seg]) else 0.0
            val x = (a[0] + (b[0] - a[0]) * f) * pulse; val y = (a[1] + (b[1] - a[1]) * f) * pulse
            dots.add(OrbDot(c2 + x * size, c2 + y * size, 0.0, max(0.35, re * size), 0.1))
        }
        return finalizeFrame(dots, emptyList(), o.g("rMin", 0.3))
    }

    fun frameFor(mode: String, size: Double, t: Double, o: ModeOpts): OrbFrame = when (mode) {
        "orbits" -> frameOrbits(size, t, o); "globe" -> frameGlobe(size, t, o); "rubik" -> frameRubik(size, t, o)
        "wave" -> frameWave(size, t, o); "web" -> frameWeb(size, t, o); "braid" -> frameBraid(size, t, o)
        "ribbon", "ring" -> frameRibbon(size, t, o); "morph" -> frameMorph(size, t, o)
        else -> frameOrbits(size, t, o)
    }

    // ---------------------------------------------------------------- presets (state × size)
    val STATES = listOf("working", "searching", "solving", "listening", "connecting", "weaving", "composing", "breathing", "shaping")
    val STATE_TO_MODE = mapOf("working" to "orbits", "searching" to "globe", "solving" to "rubik", "listening" to "wave",
        "connecting" to "web", "weaving" to "braid", "composing" to "ribbon", "breathing" to "ring", "shaping" to "morph")

    class Preset(val speed: Double, val count: Double, val size: Double, val extra: Map<String, Double> = emptyMap())
    class Resolved(val mode: String, val speed: Double, val opts: ModeOpts)

    private val BASE = mapOf(
        "globe" to mapOf("latRings" to 17.0, "lonDensity" to 44.0, "rBase" to 0.6, "rDepth" to 1.7, "rBoost" to 1.0, "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3),
        "orbits" to mapOf("orbitN" to 12.0, "ghostN" to 40.0, "ghostR" to 0.9, "ghostA" to 0.5, "particles" to 3.0, "partR" to 1.2, "partRDepth" to 1.6, "rsPow" to 0.6, "rMin" to 0.3),
        "rubik" to mapOf("latRings" to 15.0, "lonDensity" to 40.0, "moveCount" to 14.0, "rBase" to 0.6, "rDepth" to 1.7, "rActive" to 0.3, "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3),
        "wave" to mapOf("rings" to 15.0, "lonDensity" to 40.0, "rBase" to 0.6, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3),
        "web" to mapOf("nodeN" to 30.0, "thr" to 0.72, "signals" to 5.0, "nodeR" to 1.4, "nodeRDepth" to 1.8, "lineW" to 0.8, "rsPow" to 0.6, "rMin" to 0.3),
        "braid" to mapOf("strandN" to 52.0, "turns" to 3.0, "ghostN" to 150.0, "rBase" to 1.2, "rDepth" to 1.8, "rsPow" to 0.6, "rMin" to 0.3),
        "ribbon" to mapOf("lanes" to 5.0, "segs" to 88.0, "ghostN" to 150.0, "rBase" to 1.1, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3),
        "ring" to mapOf("lanes" to 5.0, "segs" to 88.0, "ghostN" to 0.0, "faceOn" to 1.0, "rBase" to 1.1, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3),
        "morph" to mapOf("rDot" to 0.021, "iconD" to 1.0, "rMin" to 0.25))

    private val PRESETS: Map<String, Map<Int, Preset>> = mapOf(
        "orbits" to mapOf(64 to Preset(1.885, 1.0, 1.0), 20 to Preset(3.9, 0.238, 2.4)),
        "globe" to mapOf(64 to Preset(2.015, 0.42, 1.15, mapOf("scanMul" to 4.08, "dimBase" to 0.45)), 20 to Preset(2.665, 0.105, 1.75, mapOf("scanMul" to 4.335, "dimBase" to 0.45))),
        "rubik" to mapOf(64 to Preset(1.82, 0.35, 1.05), 20 to Preset(1.95, 0.088, 1.9)),
        "wave" to mapOf(64 to Preset(4.388, 0.341, 1.0), 20 to Preset(3.998, 0.105, 1.6)),
        "web" to mapOf(64 to Preset(3.315, 1.35, 0.95), 20 to Preset(6.63, 0.25, 1.52)),
        "braid" to mapOf(64 to Preset(1.625, 0.5, 1.0), 20 to Preset(2.75, 0.1125, 1.36)),
        "ribbon" to mapOf(64 to Preset(2.34, 0.25, 0.85, mapOf("spin" to 0.0, "bandMul" to 3.9, "wobMul" to 1.0)), 20 to Preset(3.12, 0.051, 1.073, mapOf("spin" to 0.0, "bandMul" to 4.94, "wobMul" to 1.0))),
        "ring" to mapOf(64 to Preset(3.24, 0.25, 0.956, mapOf("spin" to 0.0, "bandMul" to 3.627, "wobMul" to 0.368)), 20 to Preset(3.78, 0.028, 1.622, mapOf("spin" to 0.0, "bandMul" to 3.968, "wobMul" to 0.565))),
        "morph" to mapOf(64 to Preset(2.405, 0.702, 0.395, mapOf("spread" to 1.45)), 20 to Preset(2.08, 0.53, 1.011, mapOf("spread" to 1.45))))

    private val COUNT_PAIRS = listOf("latRings" to "lonDensity", "rings" to "lonDensity", "lanes" to "segs")
    private val COUNT_KEYS = listOf("orbitN", "ghostN", "nodeN", "strandN", "signals")
    private val RADIUS_KEYS = listOf("rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth", "nodeR", "nodeRDepth")

    fun scaleCounts(opts: ModeOpts, scale: Double): ModeOpts {
        val out = HashMap(opts); val done = HashSet<String>(); val rt = sqrt(scale)
        for ((a, b) in COUNT_PAIRS) {
            val va = out[a]; val vb = out[b]
            if (va != null && vb != null && a !in done && b !in done) {
                out[a] = max(2.0, Math.round(va * rt).toDouble()); out[b] = max(2.0, Math.round(vb * rt).toDouble())
                done.add(a); done.add(b)
            }
        }
        for (k in COUNT_KEYS) { val v = out[k]; if (v != null && v != 0.0 && k !in done) out[k] = max(1.0, Math.round(v * scale).toDouble()) }
        out["iconD"]?.let { out["iconD"] = max(0.02, it * scale) }
        return out
    }

    fun scaleRadii(opts: ModeOpts, scale: Double): ModeOpts {
        val out = HashMap(opts)
        for (k in RADIUS_KEYS) out[k]?.let { out[k] = it * scale }
        out["rSizeMul"] = (out["rSizeMul"] ?: 1.0) * scale
        return out
    }

    private val cache = HashMap<String, Resolved>()
    fun resolvePreset(state: String, size: Int): Resolved = cache.getOrPut("$state-$size") {
        val mode = STATE_TO_MODE[state] ?: "orbits"
        val p = PRESETS.getValue(mode).getValue(size)
        var opts: ModeOpts = HashMap(BASE.getValue(mode))
        if (p.count != 1.0) opts = scaleCounts(opts, p.count)
        if (p.size != 1.0) opts = scaleRadii(opts, p.size)
        if (p.extra.isNotEmpty()) opts = opts + p.extra
        Resolved(mode, p.speed, opts)
    }
}
