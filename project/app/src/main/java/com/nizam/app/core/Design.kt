package com.nizam.app.core

import androidx.compose.ui.graphics.Color

/**
 * Tone/accent pairs. Each lesson, insight and module page picks one deterministically from its
 * own id, so colour is consistent per subject without anyone choosing it by hand.
 */
object Tones {
    data class Pair(val tone: Color, val accent: Color)

    val pairs = listOf(
        Pair(Color(0xFF7B9D7C), Color(0xFFF0AB57)),
        Pair(Color(0xFF7AAEC0), Color(0xFF5A6E89)),
        Pair(Color(0xFF9B7BB0), Color(0xFF7B9D7C)),
        Pair(Color(0xFFB57F4A), Color(0xFF5A6E89)),
        Pair(Color(0xFFA85D5D), Color(0xFFF0AB57)),
        Pair(Color(0xFF5A6E89), Color(0xFFF0AB57)),
        Pair(Color(0xFF6E8E7E), Color(0xFFC98A45)),
        Pair(Color(0xFF8A7BA8), Color(0xFF5B8F7A)),
        Pair(Color(0xFF4F7C8A), Color(0xFFD98E4A)),
        Pair(Color(0xFF8C6A4F), Color(0xFF7B9D7C)),
        Pair(Color(0xFF6B7FA8), Color(0xFFB57F4A))
    )

    fun forKey(key: String): Pair {
        val hash = key.fold(7) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
        return pairs[hash % pairs.size]
    }
}

/** Glyphs used as quiet section marks, matching the reading-first feel. */
val GLYPHS = listOf("◐", "^", "◆", "●", "■", "◇", "○", "△", "◈", "❖")

fun glyphFor(key: String): String {
    val hash = key.fold(11) { acc, c -> (acc * 17 + c.code) and 0x7FFFFFFF }
    return GLYPHS[hash % GLYPHS.size]
}

/** Levels grow slowly at first, then need real consistency. */
object Levels {
    fun levelFor(xp: Int): Int {
        var level = 1
        var needed = 100
        var remaining = xp
        while (remaining >= needed) {
            remaining -= needed
            level++
            needed = (needed * 1.25).toInt()
        }
        return level
    }

    fun progressInLevel(xp: Int): kotlin.Pair<Int, Int> {
        var needed = 100
        var remaining = xp
        while (remaining >= needed) {
            remaining -= needed
            needed = (needed * 1.25).toInt()
        }
        return kotlin.Pair(remaining, needed)
    }

    fun title(level: Int): String = when {
        level < 3 -> "Starting out"
        level < 6 -> "Finding rhythm"
        level < 10 -> "Consistent"
        level < 15 -> "Disciplined"
        level < 22 -> "Formidable"
        level < 30 -> "Relentless"
        else -> "Master of the daily"
    }
}
