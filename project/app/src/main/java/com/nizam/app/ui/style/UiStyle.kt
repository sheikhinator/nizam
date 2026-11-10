package com.nizam.app.ui.style

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.AutoAwesome

/**
 * How Atlas looks and moves. Same structure everywhere (Today, spaces, hubs, the dock), drawn five ways.
 * Classic is the look you already have and is never changed by the others.
 */
enum class UiStyle(val title: String, val blurb: String, val emoji: String, val sci: Boolean = false) {
    CLASSIC("Classic", "The Atlas you know, exactly as it is", "◐"),
    LAB("Atlas Lab", "A scientific dashboard on every screen: KPIs, live charts, flat panels", "⌬", true)
}

object UiStyles {
    var current by mutableStateOf(UiStyle.CLASSIC)
        private set
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val raw = c.getSharedPreferences("ui_style", 0).getString("style", "CLASSIC") ?: "CLASSIC"
        // the nine earlier scientific views became one: anyone on one of them lands on Atlas Lab; retired looks fall back to Classic
        current = when (raw) { "LAB", "NOTEBOOK", "INSTRUMENT", "PAPER", "HEATGRID", "TERMINAL", "GITLOG", "CELLS", "PROCESS", "GRAPH" -> UiStyle.LAB; else -> UiStyle.CLASSIC }
    }

    fun set(c: Context, s: UiStyle) {
        current = s
        c.getSharedPreferences("ui_style", 0).edit().putString("style", s.name).apply()
    }

    @Composable
    fun now(): UiStyle {
        if (!loaded) load(androidx.compose.ui.platform.LocalContext.current)
        return current
    }
}

/** Shared visual vocabulary of each style. */
object StyleKit {
    val bridgeInk = Color(0xFF05080D)
    val bridgePanel = Color(0xFF0B121B)
    val bridgeLine = Color(0xFF1C2A3A)
    val bridgeText = Color(0xFFD6E4F0)
    val bridgeDim = Color(0xFF6E8399)
    val bridgeCyan = Color(0xFF41E1F2)
    val bridgeAmber = Color(0xFFFFB547)
    val bridgeLime = Color(0xFF9CF26B)
    val bridgeRose = Color(0xFFFF5C8A)
    val mono = FontFamily.Monospace
    val serif = FontFamily.Serif
}

/** Pick how Atlas looks. Applies at once; Classic is always one tap away. */
@Composable
fun StylePickerScreen() {
    val c = androidx.compose.ui.platform.LocalContext.current
    val now = UiStyles.now()
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Text("Same Atlas, same places — drawn your way. Switch any time.", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        UiStyle.values().forEach { s ->
            val on = s == now
            val cs = androidx.compose.material3.MaterialTheme.colorScheme
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(if (on) cs.primary.copy(alpha = 0.14f) else cs.surfaceVariant.copy(alpha = 0.5f))
                .border(if (on) 2.dp else 0.dp, cs.primary, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .clickable { UiStyles.set(c, s) }.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                StylePreview(s)
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f).padding(start = 14.dp)) {
                    androidx.compose.material3.Text(s.title + if (on) "  ✓" else "", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    androidx.compose.material3.Text(s.blurb, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        androidx.compose.material3.Text("Tip: the Atlas mark in the dock has its own studio — shapes, surfaces, colours and moods.",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** A tiny drawing of each style so you can tell them apart before choosing. */
@Composable
private fun StylePreview(s: UiStyle) {
    val SciDark = LocalSci.current
    val p = androidx.compose.material3.MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.size(64.dp, 96.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(if (s.sci) 2.dp else 12.dp))
        .background(if (s.sci) SciDark.bg else androidx.compose.material3.MaterialTheme.colorScheme.surface)) {
        val w = size.width; val h = size.height
        fun rr(x: Float, y: Float, ww: Float, hh: Float, col: androidx.compose.ui.graphics.Color, r: Float = 6f) =
            drawRoundRect(col, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(ww, hh), androidx.compose.ui.geometry.CornerRadius(r, r))
        if (!s.sci) { rr(6f, 8f, w * 0.6f, 8f, p.copy(alpha = 0.6f)); for (i in 0..3) rr(6f + (i % 2) * (w / 2 - 3), 24f + (i / 2) * 30f, w / 2 - 10, 24f, p.copy(alpha = 0.25f)) }
        else { for (i in 0..3) rr(5f + i * (w - 10f) / 4f, 8f, (w - 10f) / 4f - 3f, 16f, SciDark.a.copy(alpha = 0.5f), 0f)
            drawCircle(SciDark.a, 14f, androidx.compose.ui.geometry.Offset(22f, 52f), style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
            for (i in 0..2) rr(w * 0.5f, 40f + i * 10f, w * 0.4f, 5f, listOf(SciDark.w, SciDark.g, SciDark.v)[i], 0f)
            for (i in 0..5) rr(5f + i * (w - 10f) / 6f, 78f, (w - 10f) / 6f - 2f, 12f, SciDark.a.copy(alpha = 0.2f + i * 0.13f), 0f) }
    }
}

val StyleSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    com.nizam.app.feature.nx.ScreenSpec("styles", "Atlas styles", "Classic, Living Deck, Command Bridge, Magazine or Living Layers: the same Atlas, drawn your way",
        androidx.compose.material.icons.Icons.Outlined.Palette, com.nizam.app.ui.design.Hub.Tools,
        "style look ui layout design deck bridge magazine layers theme appearance new ui skin") { _, _ -> StylePickerScreen() },
    com.nizam.app.feature.nx.ScreenSpec("logostudio", "Logo Studio", "Shape, surface, colour and moods of the Atlas mark, plus the home-screen icon",
        androidx.compose.material.icons.Icons.Outlined.AutoAwesome, com.nizam.app.ui.design.Hub.Tools,
        "logo orb gem icon mark shape customize atlas logo reactor monogram") { _, _ -> LogoStudioScreen() },
)
