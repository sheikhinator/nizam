package com.nizam.app.feature.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A pixel character built from layers — skin, eyes, hair, facial hair, headwear, glasses — so a
 * handful of parts combine into hundreds of distinct faces. Encoded as a short string, e.g.
 * "2-3-1-0-4-1-5", which is all that gets stored.
 */
data class Face(
    val skin: Int = 2, val hair: Int = 1, val hairColour: Int = 0,
    val beard: Int = 0, val headwear: Int = 0, val glasses: Int = 0, val bg: Int = 0
) {
    fun encode() = listOf(skin, hair, hairColour, beard, headwear, glasses, bg).joinToString("-")

    companion object {
        fun decode(s: String): Face = runCatching {
            val p = s.split("-").map { it.toInt() }
            Face(p[0], p[1], p[2], p[3], p[4], p[5], p[6])
        }.getOrElse { Face() }
    }
}

object FaceParts {
    val skins = listOf(0xFFF6D7C3, 0xFFEBC09A, 0xFFD6A27A, 0xFFB57F55, 0xFF8C5A3C, 0xFF5E3A26).map { Color(it) }
    val hairColours = listOf(0xFF1C1612, 0xFF4A2E1C, 0xFF8A5A2B, 0xFFC9A15A, 0xFF9E9E9E, 0xFFB5462E).map { Color(it) }
    val backgrounds = listOf(0xFF1F6F63, 0xFF5B6BA8, 0xFF8A4F6B, 0xFFB57F4A, 0xFF3E7C8A, 0xFF6E8E4E, 0xFF7A5BA8, 0xFF8C2E1C).map { Color(it) }
    val hairNames = listOf("Bald", "Short", "Side part", "Curly", "Long", "Buzz", "Swept")
    val beardNames = listOf("None", "Stubble", "Full beard", "Goatee", "Moustache")
    val headwearNames = listOf("None", "Prayer cap", "Cap", "Hijab", "Beanie", "Turban")
    val glassesNames = listOf("None", "Round", "Square", "Shades")

    // 12×12 layers. 'S' skin, 'H' hair, 'E' eye, 'M' mouth, 'B' beard, 'W' headwear, 'G' glass, 'D' dark
    val head = listOf(
        "............", "............", "...SSSSSS...", "..SSSSSSSS..",
        "..SSSSSSSS..", "..SESSSSES..", "..SSSSSSSS..", "..SSSSSSSS..",
        "..SSSMMSSS..", "...SSSSSS...", "....SSSS....", "...SSSSSS..."
    )
    val hairs = listOf(
        emptyList(),
        listOf("............", "...HHHHHH...", "..HHHHHHHH..", "..H......H.."),
        listOf("............", "..HHHHHHH...", "..HHHHHHHHH.", "..HH.....H.."),
        listOf("...H.H.H....", "..HHHHHHHH..", ".HHHHHHHHHH.", ".HH......HH.", ".H........H."),
        listOf("............", "...HHHHHH...", "..HHHHHHHH..", ".HH......HH.", ".HH......HH.",
               ".HH......HH.", ".HH......HH.", ".HH......HH."),
        listOf("............", "............", "...HHHHHH...", "..H......H.."),
        listOf("............", "....HHHHHHH.", "..HHHHHHHHH.", "..H.......H.")
    )
    val beards = listOf(
        emptyList(),
        listOf("", "", "", "", "", "", "", "..D......D..", "..DD....DD..", "...DDDDDD..."),
        listOf("", "", "", "", "", "", "..B......B..", "..BB....BB..", "..BBBMMBBB..", "...BBBBBB...", "....BBBB...."),
        listOf("", "", "", "", "", "", "", "", "....BMMB....", ".....BB....."),
        listOf("", "", "", "", "", "", "", "....BBBB....")
    )
    val headwears = listOf(
        emptyList(),
        listOf("....WWWW....", "...WWWWWW...", "..WWWWWWWW.."),
        listOf("............", "...WWWWWW...", "..WWWWWWWWWW", "..W......W.."),
        listOf("...WWWWWW...", "..WWWWWWWW..", ".WWWWWWWWWW.", ".WW......WW.", ".WW......WW.",
               ".WW......WW.", ".WW......WW.", ".WW......WW.", ".WWW....WWW.", ".WWWW..WWWW.",
               "..WWW..WWW..", "..WWWWWWWW.."),
        listOf("....WWWW....", "...WWWWWW...", "..WWWWWWWW..", "..WWWWWWWW.."),
        listOf("...WWWWWW...", "..WWWWWWWW..", ".WWWWWWWWWW.", "..WWWWWWWW..")
    )
    val glasses = listOf(
        emptyList(),
        listOf("", "", "", "", "", "..GGG..GGG..", "...G....G..."),
        listOf("", "", "", "", "", ".GGGGGGGGGG.", "..GGG..GGG.."),
        listOf("", "", "", "", "", "..DDDDDDDD..", "..DDD..DDD..")
    )
    val headwearColours = listOf(Color(0xFFF2F2F2), Color(0xFF2F6F5E), Color(0xFF3E5C76), Color(0xFF7A5BA8), Color(0xFFE6E0D0))
}

@Composable
fun FaceAvatar(face: Face, size: Dp = 48.dp, selected: Boolean = false) {
    val bg = FaceParts.backgrounds[face.bg.mod(FaceParts.backgrounds.size)]
    Box(
        Modifier
            .size(size)
            .background(bg.copy(alpha = 0.35f), RoundedCornerShape(size / 4))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else bg.copy(alpha = 0.6f), RoundedCornerShape(size / 4)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size * 0.82f)) {
            val cell = this.size.width / 12f
            val skin = FaceParts.skins[face.skin.mod(FaceParts.skins.size)]
            val hairC = FaceParts.hairColours[face.hairColour.mod(FaceParts.hairColours.size)]
            val wearC = FaceParts.headwearColours[face.hairColour.mod(FaceParts.headwearColours.size)]
            val dark = Color(0xFF1A1410)
            fun layer(rows: List<String>, colourOf: (Char) -> Color?) {
                rows.forEachIndexed { r, line ->
                    line.forEachIndexed { c, ch ->
                        val col = colourOf(ch) ?: return@forEachIndexed
                        drawRect(col, Offset(c * cell, r * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
            layer(FaceParts.head) { when (it) { 'S' -> skin; 'E' -> dark; 'M' -> Color(0xFF8C3B32); else -> null } }
            val hijab = face.headwear == 3
            if (!hijab) layer(FaceParts.hairs[face.hair.mod(FaceParts.hairs.size)]) { if (it == 'H') hairC else null }
            layer(FaceParts.beards[face.beard.mod(FaceParts.beards.size)]) {
                when (it) { 'B' -> hairC; 'D' -> hairC.copy(alpha = 0.55f); 'M' -> Color(0xFF8C3B32); else -> null }
            }
            layer(FaceParts.headwears[face.headwear.mod(FaceParts.headwears.size)]) { if (it == 'W') wearC else null }
            layer(FaceParts.glasses[face.glasses.mod(FaceParts.glasses.size)]) {
                when (it) { 'G' -> Color(0xFF2A2A2A); 'D' -> Color(0xFF111111); else -> null }
            }
        }
    }
}

/** Thirty ready-made faces so choosing is one tap; the editor goes further. */
val PRESET_FACES: List<Face> = buildList {
    val seeds = listOf(
        Face(2, 1, 0, 2, 0, 0, 0), Face(3, 2, 0, 1, 0, 0, 1), Face(1, 3, 1, 0, 0, 1, 2), Face(4, 5, 0, 2, 1, 0, 3),
        Face(0, 4, 3, 0, 0, 0, 4), Face(2, 0, 0, 2, 5, 0, 5), Face(3, 6, 1, 4, 0, 2, 6), Face(5, 1, 0, 1, 2, 0, 7),
        Face(1, 0, 0, 0, 3, 0, 0), Face(2, 2, 2, 3, 0, 1, 1), Face(4, 3, 0, 2, 0, 3, 2), Face(0, 6, 3, 0, 4, 0, 3),
        Face(3, 1, 4, 2, 0, 1, 4), Face(2, 5, 0, 1, 1, 0, 5), Face(5, 3, 0, 2, 0, 0, 6), Face(1, 4, 1, 0, 0, 2, 7),
        Face(2, 0, 0, 2, 1, 1, 0), Face(3, 2, 5, 0, 0, 0, 1), Face(4, 1, 0, 3, 2, 3, 2), Face(0, 2, 2, 0, 3, 0, 3),
        Face(2, 6, 0, 4, 0, 2, 4), Face(1, 5, 1, 1, 0, 0, 5), Face(3, 3, 0, 2, 5, 0, 6), Face(5, 4, 0, 0, 0, 1, 7),
        Face(2, 1, 1, 1, 4, 0, 0), Face(4, 0, 0, 2, 1, 2, 1), Face(1, 2, 3, 0, 0, 3, 2), Face(3, 1, 0, 2, 0, 1, 3),
        Face(0, 3, 4, 0, 3, 0, 4), Face(2, 4, 0, 0, 0, 0, 5)
    )
    addAll(seeds)
}
