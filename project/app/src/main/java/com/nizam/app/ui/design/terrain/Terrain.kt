package com.nizam.app.ui.design.terrain

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import java.time.LocalTime
import kotlin.math.sin

enum class TimePhase { DAWN, DAY, DUSK, NIGHT }

data class TerrainPalette(
    val void: Color,
    val basalt: Color,
    val slate: Color,
    val seam: Color,
    val bone: Color,
    val mist: Color,
    val ember: Color,
    val gold: Color,
    val moss: Color,
    val ice: Color,
    val accent: Color,
    val glow: Color
)

object Terrain {
    fun phase(): TimePhase {
        val h = LocalTime.now().hour
        return when (h) {
            in 5..7 -> TimePhase.DAWN
            in 8..16 -> TimePhase.DAY
            in 17..19 -> TimePhase.DUSK
            else -> TimePhase.NIGHT
        }
    }
    
    fun palette(phase: TimePhase, energy: Float = 0.5f): TerrainPalette {
        return when (phase) {
            TimePhase.DAWN -> TerrainPalette(
                void = Color(0xFF080604),
                basalt = Color(0xFF14100B),
                slate = Color(0xFF1F1810),
                seam = Color(0xFFFFF3E0).copy(alpha = 0.12f),
                bone = Color(0xFFF5EDE0),
                mist = Color(0xFFB8A99A),
                ember = Color(0xFFFF8A4C),
                gold = Color(0xFFFFB84D),
                moss = Color(0xFF8FB87A),
                ice = Color(0xFFB3E5FC),
                accent = Color(0xFFFF7A45).copy(alpha = 0.8f + energy * 0.2f),
                glow = Color(0xFFFF6B35).copy(alpha = 0.15f)
            )
            TimePhase.DAY -> TerrainPalette(
                void = Color(0xFF06080A),
                basalt = Color(0xFF0E1216),
                slate = Color(0xFF171D24),
                seam = Color(0xFFFFFFFF).copy(alpha = 0.08f),
                bone = Color(0xFFF1EDE3),
                mist = Color(0xFF9AA6B2),
                ember = Color(0xFFFF7A45),
                gold = Color(0xFFE4B45C),
                moss = Color(0xFF7FB08A),
                ice = Color(0xFF7FD4E8),
                accent = Color(0xFFFF7A45).copy(alpha = 0.9f + energy * 0.1f),
                glow = Color(0xFFFF7A45).copy(alpha = 0.12f)
            )
            TimePhase.DUSK -> TerrainPalette(
                void = Color(0xFF080608),
                basalt = Color(0xFF120E14),
                slate = Color(0xFF1C161F),
                seam = Color(0xFFF3E5F5).copy(alpha = 0.1f),
                bone = Color(0xFFF0EDE8),
                mist = Color(0xFFB0A0B8),
                ember = Color(0xFFFF6B6B),
                gold = Color(0xFFFFB74D),
                moss = Color(0xFF81C784),
                ice = Color(0xFF64B5F6),
                accent = Color(0xFFFF6B9D).copy(alpha = 0.85f + energy * 0.15f),
                glow = Color(0xFFE91E63).copy(alpha = 0.14f)
            )
            TimePhase.NIGHT -> TerrainPalette(
                void = Color(0xFF050507),
                basalt = Color(0xFF0A0A0E),
                slate = Color(0xFF121216),
                seam = Color(0xFFE1F5FE).copy(alpha = 0.06f),
                bone = Color(0xFFE8E6E1),
                mist = Color(0xFF7A8494),
                ember = Color(0xFFFF7043),
                gold = Color(0xFFDAA520),
                moss = Color(0xFF66BB6A),
                ice = Color(0xFF4FC3F7),
                accent = Color(0xFF42A5F5).copy(alpha = 0.9f + energy * 0.1f),
                glow = Color(0xFF2196F3).copy(alpha = 0.1f)
            )
        }
    }
}

val LocalTerrain = staticCompositionLocalOf { Terrain.palette(TimePhase.DAY) }

@Composable
fun ProvideTerrain(content: @Composable () -> Unit) {
    var phase by remember { mutableStateOf(Terrain.phase()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60000)
            phase = Terrain.phase()
        }
    }
    val palette = Terrain.palette(phase)
    CompositionLocalProvider(LocalTerrain provides palette) {
        content()
    }
}
