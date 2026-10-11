package com.nizam.app.ui.screens.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.components.AppRow
import com.nizam.app.ui.components.HeroCard
import com.nizam.app.ui.components.SectionHeader
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.design.pressable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import com.nizam.app.ui.theme.DisplayFamily

private data class App(val emoji: String, val name: String, val blurb: String, val route: String, val tint: Color)

private val APPS = listOf(
    App("✨", "Superpowers", "Imagine, Authenticator, Documents, Pakistan today, games and more", Routes.POWERS, Color(0xFFB45FD6)),
    App("🧭", "Maps", "The whole world — search, directions, traffic, Qibla", Routes.MAPS, Color(0xFF4F8A6B)),
    App("🎬", "Cinema", "Films, series, your addons", Routes.CINEMA, Color(0xFF8A4F6B)),
    App("🎵", "Music", "Your library, playlists, lyrics and free catalogues", Routes.MUSIC, Color(0xFFE0603A)),
    App("🎙", "Podcasts", "Subscribe, download, listen", Routes.PODCASTS, Color(0xFF4FC3A1)),
    App("📺", "Live TV", "Your M3U playlists and channels", Routes.IPTV, Color(0xFF58A6C9)),
    App("🔌", "Playground", "Connect any service through MCP", Routes.PLAYGROUND, Color(0xFF9B8CD6)),
    App("🛍", "App store", "F-Droid, GitHub, Google Play and Aurora — your catalogue", Routes.APPSTORE, Color(0xFFD4A24C))
)

@Composable
fun AppsScreen(onOpen: (String) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 18.dp)) {
                Text(java.time.LocalDate.now().let { "${it.dayOfWeek.name.lowercase().replaceFirstChar { c -> c.uppercase() }} ${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }}" },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Apps", style = MaterialTheme.typography.displaySmall.copy(fontFamily = DisplayFamily))
            }
        }
        // a home-screen row of the apps first, then each with what it does
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                APPS.take(4).forEach { app ->
                    Column(Modifier.weight(1f).pressable { onOpen(app.route) }, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        com.nizam.app.ui.components.AppIcon(app.emoji, app.tint, 60.dp)
                        Text(app.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 7.dp))
                    }
                }
            }
        }
        items(APPS, key = { it.route }) { app ->
            AppRow(app.emoji, app.name, app.blurb, app.tint) { onOpen(app.route) }
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}
