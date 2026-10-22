package com.nizam.app.feature.nx

import androidx.compose.ui.Modifier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.data.prefs.ThemeMode
import com.nizam.app.feature.dash.Dash
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.Instruments
import com.nizam.app.ui.design.SoundFx
import kotlinx.coroutines.launch

object Mega12 {
    val all: List<Spec> by lazy { listOf(
        ScreenSpec("dashboards", "Dashboards", "Live gauges, charts and trends for your day, money, health, prayer and study", Icons.Outlined.Dashboard, Hub.Tools,
            "dashboard kpi gauge chart trend heatmap instruments analytics overview") { c, open -> DashboardsScreen(c, open) },
        ScreenSpec("instruments", "Look: instruments & sound", "Navy or lab-paper theme, friendly or lab-grade charts, sound and motion levels", Icons.Outlined.Tune, Hub.Tools,
            "theme sound motion calm style navy lab paper haptic instruments display mode") { c, _ -> InstrumentSettings(c) },
    ) }
}

@Composable
private fun DashboardsScreen(container: AppContainer, open: (String) -> Unit) {
    var data by remember { mutableStateOf<com.nizam.app.feature.dash.DashData?>(null) }
    var tab by remember { mutableStateOf("Today") }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { data = Dash.load(container) } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Today", "Money", "Health", "Deen", "Study", "AI", "System").forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t) }) }
        }
        val d = data ?: run { Text("Reading your data…", style = MaterialTheme.typography.bodySmall); return }
        when (tab) { "Today" -> Dash.Today(d, open); "Money" -> Dash.Money(d, open); "Health" -> Dash.Health(d, open); "Deen" -> Dash.Deen(d, open)
            "Study" -> Dash.Study(d, open); "AI" -> Dash.Ai(d, open); else -> Dash.System(d, open) }
    }
}

@Composable
private fun InstrumentSettings(container: AppContainer) {
    val c = LocalContext.current; val scope = com.nizam.app.ui.design.rememberSafeScope()
    var style by remember { mutableStateOf(Instruments.style(c)) }
    var sound by remember { mutableStateOf(Instruments.sound(c)) }
    var motion by remember { mutableStateOf(Instruments.motion(c)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { scope.launch { container.settingsRepository.setAccent("instruments"); container.settingsRepository.setThemeMode(ThemeMode.DARK) } }) { Text("Navy (dark)") }
            TextButton(onClick = { scope.launch { container.settingsRepository.setAccent("labpaper"); container.settingsRepository.setThemeMode(ThemeMode.LIGHT) } }) { Text("Lab paper (light)") }
            TextButton(onClick = { scope.launch { container.settingsRepository.setAccent(""); container.settingsRepository.setThemeMode(ThemeMode.SYSTEM) } }) { Text("Automatic") }
            TextButton(onClick = { scope.launch { container.settingsRepository.setAccent("cbsafe"); container.settingsRepository.setThemeMode(ThemeMode.DARK) } }) { Text("Colour-blind safe") }
        }
        Text("Spacing", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        var dens by remember { mutableStateOf(com.nizam.app.ui.design.Density.level(c)) }
        Row { listOf("Compact", "Comfortable", "Large").forEach { d -> TextButton(onClick = { dens = d; com.nizam.app.ui.design.Density.set(c, d) }) { Text(d, fontWeight = if (dens == d) FontWeight.Bold else FontWeight.Normal) } } }
        Text("Applies to screens you open next.", style = MaterialTheme.typography.bodySmall)
        Text("Chart style", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row { Instruments.StyleLevel.entries.forEach { s -> TextButton(onClick = { style = s; Instruments.set(c, style = s) }) { Text(s.label, fontWeight = if (style == s) FontWeight.Bold else FontWeight.Normal) } } }
        Text("Sound", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row { Instruments.SoundLevel.entries.forEach { s -> TextButton(onClick = { sound = s; Instruments.set(c, sound = s); SoundFx.chime(c) }) { Text(s.label, fontWeight = if (sound == s) FontWeight.Bold else FontWeight.Normal) } } }
        Text("Silent mode and Worship mode always mute sound.", style = MaterialTheme.typography.bodySmall)
        Text("Motion", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row { Instruments.MotionLevel.entries.forEach { m -> TextButton(onClick = { motion = m; Instruments.set(c, motion = m) }) { Text(m.label, fontWeight = if (motion == m) FontWeight.Bold else FontWeight.Normal) } } }
        Text("Calm keeps colour and layout but stops charts and numbers from animating.", style = MaterialTheme.typography.bodySmall)
    }
}
