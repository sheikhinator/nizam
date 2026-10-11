package com.nizam.app.feature.future

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A complete little chess engine: legal moves (castling, en passant, promotion), check, mate, and alpha-beta search. */
class ChessPos(
    val b: IntArray = START.copyOf(), var side: Int = 1, var castle: Int = 0b1111, var ep: Int = -1
) {
    data class Move(val from: Int, val to: Int, val promo: Int = 0)

    fun copy() = ChessPos(b.copyOf(), side, castle, ep)

    companion object {
        const val P = 1; const val N = 2; const val B = 3; const val R = 4; const val Q = 5; const val K = 6
        // index 0 = a8 … 63 = h1
        val START = intArrayOf(-R, -N, -B, -Q, -K, -B, -N, -R, -P, -P, -P, -P, -P, -P, -P, -P) + IntArray(32) + intArrayOf(P, P, P, P, P, P, P, P, R, N, B, Q, K, B, N, R)
        private val VAL = intArrayOf(0, 100, 320, 330, 500, 900, 20000)
        private val KN = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
        private val KG = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
        private val DIAG = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        private val LINE = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        fun sq(r: Int, f: Int) = r * 8 + f
        fun name(s: Int) = "${'a' + s % 8}${8 - s / 8}"
    }

    private fun on(r: Int, f: Int) = r in 0..7 && f in 0..7

    fun attacked(s: Int, by: Int): Boolean {
        val r = s / 8; val f = s % 8
        val pr = r + by                                   // a white pawn attacks from the rank below (higher index)
        if (on(pr, f - 1) && b[sq(pr, f - 1)] == P * by) return true
        if (on(pr, f + 1) && b[sq(pr, f + 1)] == P * by) return true
        for ((dr, df) in KN) if (on(r + dr, f + df) && b[sq(r + dr, f + df)] == N * by) return true
        for ((dr, df) in KG) if (on(r + dr, f + df) && b[sq(r + dr, f + df)] == K * by) return true
        for ((dirs, kinds) in listOf(DIAG to setOf(B, Q), LINE to setOf(R, Q))) for ((dr, df) in dirs) {
            var rr = r + dr; var ff = f + df
            while (on(rr, ff)) {
                val p = b[sq(rr, ff)]
                if (p != 0) { if (p * by > 0 && (p * by) in kinds) return true; break }
                rr += dr; ff += df
            }
        }
        return false
    }

    fun king(side: Int) = b.indexOfFirst { it == K * side }
    fun inCheck(side: Int = this.side) = king(side).let { it >= 0 && attacked(it, -side) }

    private fun pseudo(): List<Move> {
        val out = ArrayList<Move>(48)
        for (s in 0 until 64) {
            val p = b[s]; if (p == 0 || p * side < 0) continue
            val r = s / 8; val f = s % 8
            when (kotlin.math.abs(p)) {
                P -> {
                    val dir = -side; val startRank = if (side == 1) 6 else 1; val lastRank = if (side == 1) 0 else 7
                    fun add(t: Int) { if (t / 8 == lastRank) listOf(Q, N, R, B).forEach { out += Move(s, t, it) } else out += Move(s, t) }
                    if (on(r + dir, f) && b[sq(r + dir, f)] == 0) {
                        add(sq(r + dir, f))
                        if (r == startRank && b[sq(r + 2 * dir, f)] == 0) out += Move(s, sq(r + 2 * dir, f))
                    }
                    for (df in listOf(-1, 1)) if (on(r + dir, f + df)) {
                        val t = sq(r + dir, f + df)
                        if (b[t] * side < 0 || t == ep) add(t)
                    }
                }
                N -> for ((dr, df) in KN) if (on(r + dr, f + df)) { val t = sq(r + dr, f + df); if (b[t] * side <= 0) out += Move(s, t) }
                K -> {
                    for ((dr, df) in KG) if (on(r + dr, f + df)) { val t = sq(r + dr, f + df); if (b[t] * side <= 0) out += Move(s, t) }
                    val home = if (side == 1) 60 else 4
                    if (s == home && !attacked(s, -side)) {
                        val (kBit, qBit) = if (side == 1) 1 to 2 else 4 to 8
                        if ((castle and kBit) != 0 && b[s + 1] == 0 && b[s + 2] == 0 && b[s + 3] == R * side && !attacked(s + 1, -side)) out += Move(s, s + 2)
                        if ((castle and qBit) != 0 && b[s - 1] == 0 && b[s - 2] == 0 && b[s - 3] == 0 && b[s - 4] == R * side && !attacked(s - 1, -side)) out += Move(s, s - 2)
                    }
                }
                else -> {
                    val kind = kotlin.math.abs(p)
                    val dirs = when (kind) { B -> DIAG; R -> LINE; else -> DIAG + LINE }
                    for ((dr, df) in dirs) {
                        var rr = r + dr; var ff = f + df
                        while (on(rr, ff)) {
                            val t = sq(rr, ff)
                            if (b[t] * side > 0) break
                            out += Move(s, t)
                            if (b[t] != 0) break
                            rr += dr; ff += df
                        }
                    }
                }
            }
        }
        return out
    }

    fun play(m: Move): ChessPos {
        val n = copy()
        val p = n.b[m.from]
        n.b[m.to] = if (m.promo != 0) m.promo * side else p
        n.b[m.from] = 0
        if (kotlin.math.abs(p) == P && m.to == ep) n.b[m.to + 8 * side] = 0            // en passant capture
        if (kotlin.math.abs(p) == K && kotlin.math.abs(m.to - m.from) == 2) {           // castling moves the rook
            if (m.to > m.from) { n.b[m.to - 1] = n.b[m.to + 1]; n.b[m.to + 1] = 0 } else { n.b[m.to + 1] = n.b[m.to - 2]; n.b[m.to - 2] = 0 }
        }
        n.ep = if (kotlin.math.abs(p) == P && kotlin.math.abs(m.to - m.from) == 16) (m.from + m.to) / 2 else -1
        if (p == K) n.castle = n.castle and 0b1100; if (p == -K) n.castle = n.castle and 0b0011
        if (m.from == 63 || m.to == 63) n.castle = n.castle and 0b1110; if (m.from == 56 || m.to == 56) n.castle = n.castle and 0b1101
        if (m.from == 7 || m.to == 7) n.castle = n.castle and 0b1011; if (m.from == 0 || m.to == 0) n.castle = n.castle and 0b0111
        n.side = -side
        return n
    }

    fun legal(): List<Move> = pseudo().filter { !play(it).inCheck(side) }

    private fun eval(): Int {
        var s = 0
        for (i in 0 until 64) {
            val p = b[i]; if (p == 0) continue
            val k = kotlin.math.abs(p); val r = i / 8; val f = i % 8
            var v = VAL[k]
            val centre = 3 - maxOf(kotlin.math.abs(3.5 - r), kotlin.math.abs(3.5 - f)).toInt()
            if (k == N || k == B) v += centre * 10
            if (k == P) v += (if (p > 0) 6 - r else r - 1) * 8 + (if (f in 2..5) 6 else 0)
            s += if (p > 0) v else -v
        }
        return s * side
    }

    private fun search(depth: Int, alphaIn: Int, beta: Int): Int {
        val moves = legal()
        if (moves.isEmpty()) return if (inCheck()) -100_000 - depth else 0
        if (depth == 0) return eval()
        var alpha = alphaIn
        for (m in moves.sortedByDescending { if (b[it.to] != 0) kotlin.math.abs(b[it.to]) * 10 - kotlin.math.abs(b[it.from]) else 0 }) {
            val v = -play(m).search(depth - 1, -beta, -alpha)
            if (v >= beta) return v
            if (v > alpha) alpha = v
        }
        return alpha
    }

    /** The engine's choice. Level 1 is gentle (shallow, a little random), 3 thinks three moves ahead. */
    fun best(level: Int): Move? {
        val moves = legal().shuffled()
        if (moves.isEmpty()) return null
        val depth = when (level) { 1 -> 1; 2 -> 2; else -> 3 }
        val scored = moves.map { it to -play(it).search(depth - 1, -1_000_000, 1_000_000) }
        if (level == 1 && Math.random() < 0.3) return scored.sortedByDescending { it.second }.take(4).random().first
        return scored.maxByOrNull { it.second }?.first
    }
}

private val GLYPH = mapOf(6 to "♔", 5 to "♕", 4 to "♖", 3 to "♗", 2 to "♘", 1 to "♙", -6 to "♚", -5 to "♛", -4 to "♜", -3 to "♝", -2 to "♞", -1 to "♟")

@Composable
fun ChessScreen() {
    val scope = rememberWorkScope()
    var pos by remember { mutableStateOf(ChessPos()) }
    var history by remember { mutableStateOf(listOf<ChessPos>()) }
    var selected by remember { mutableStateOf(-1) }
    var level by remember { mutableStateOf("Medium") }
    var thinking by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<ChessPos.Move?>(null) }
    var hint by remember { mutableStateOf<ChessPos.Move?>(null) }
    val legal = remember(pos) { pos.legal() }
    val status = when {
        legal.isEmpty() && pos.inCheck() -> if (pos.side == 1) "Checkmate — Atlas wins" else "Checkmate — you win! 🎉"
        legal.isEmpty() -> "Stalemate — a draw"
        thinking -> "Atlas is thinking…"
        pos.inCheck() -> "Check!"
        else -> "Your move (white)"
    }
    fun reply(after: ChessPos) {
        thinking = true
        scope.launch {
            val lv = when (level) { "Easy" -> 1; "Medium" -> 2; else -> 3 }
            val m = withContext(Dispatchers.Default) { after.best(lv) }
            if (m != null) { pos = after.play(m); last = m } else pos = after
            thinking = false
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ModuleTitle("Chess", status)
        ChipRow(listOf("Easy", "Medium", "Hard"), level, { level = it })
        Column(Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))) {
            for (r in 0 until 8) Row(Modifier.weight(1f)) {
                for (f in 0 until 8) {
                    val s = r * 8 + f
                    val light = (r + f) % 2 == 0
                    val target = selected >= 0 && legal.any { it.from == selected && it.to == s }
                    val base = if (light) Color(0xFFEEDDBB) else Color(0xFFB58863)
                    val tint = when {
                        s == selected -> Color(0xFF7FB069)
                        hint?.let { it.from == s || it.to == s } == true -> Color(0xFF6FA8DC)
                        last?.let { it.from == s || it.to == s } == true -> if (light) Color(0xFFF5E68C) else Color(0xFFD9C35F)
                        else -> base
                    }
                    Box(Modifier.weight(1f).fillMaxSize().background(tint).clickable(enabled = !thinking && pos.side == 1) {
                        val move = legal.firstOrNull { it.from == selected && it.to == s && (it.promo == 0 || it.promo == ChessPos.Q) }
                        when {
                            move != null -> { history = history + pos; val after = pos.play(move); pos = after; last = move; selected = -1; hint = null
                                if (after.legal().isNotEmpty()) reply(after) }
                            pos.b[s] > 0 -> selected = s
                            else -> selected = -1
                        }
                    }, contentAlignment = Alignment.Center) {
                        GLYPH[pos.b[s]]?.let { Text(it, fontSize = 30.sp, color = Color.Black) }
                        if (target) Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0x66000000)))
                    }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pos = ChessPos(); history = emptyList(); selected = -1; last = null; hint = null }) { Text("New game") }
            OutlinedButton(enabled = history.isNotEmpty() && !thinking, onClick = { pos = history.last(); history = history.dropLast(1); last = null; selected = -1 }) { Text("Undo") }
            OutlinedButton(enabled = !thinking && pos.side == 1 && legal.isNotEmpty(), onClick = { scope.launch { hint = withContext(Dispatchers.Default) { pos.best(3) } } }) { Text("Hint") }
        }
        hint?.let { Text("Try ${ChessPos.name(it.from)} → ${ChessPos.name(it.to)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
    }
}
