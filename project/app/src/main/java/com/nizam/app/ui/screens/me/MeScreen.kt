package com.nizam.app.ui.screens.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.profile.Face
import com.nizam.app.feature.profile.FaceAvatar
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.theme.DisplayFamily

/** Everything about you rather than your day: identity, progress, your town, your settings. */
@Composable
fun MeScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val face by container.settingsRepository.face.collectAsState(initial = "2-1-0-2-0-0-0")
    val name by container.settingsRepository.displayName.collectAsState(initial = "")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(18.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(Routes.PROFILE) }
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                FaceAvatar(Face.decode(face), 72.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "You" }, style = MaterialTheme.typography.headlineSmall)
                    Text("Edit profile, avatar and stats", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Group("Worlds", listOf(
            Triple("Your AI company, rooms, team and clients", "Atlas HQ", Routes.HQ),
            Triple("Your living town, plot planner and museum", "Pixel Town", Routes.TOWN),
            Triple("Your companion: home, games, AR and playdates", "Buddy", "nx/pethome")
        ), onOpen)
        Group("Insights & health of the app", listOf(
            Triple("Live gauges and trends for your whole life", "Dashboards", "nx/dashboards"),
            Triple("Database, saved data, permissions and repairs", "Self-Check", "nx/selfcheck"),
            Triple("What Atlas can access and what it has done", "Trust Center", "nx/trust"),
            Triple("Everything Atlas did on its own, newest first", "What Atlas did", Routes.ACTIVITY),
            Triple("Theme, sound, motion and chart style", "Look & sound", "nx/instruments")
        ), onOpen)
        Group("", listOf(
            Triple("Patterns, simulator, what the evidence says", "Understand", Routes.UNDERSTAND_HUB),
            Triple("Memory, reflections, your book", "Story", Routes.STORY_HUB),
            Triple("Missions, Barakah, streaks, commitments", "Grow", Routes.GROW_HUB),
            Triple("Focus, rest, attention and calm", "Wellbeing", Routes.WELLBEING_HUB)
        ), onOpen)
        Group("More", listOf(
            Triple("Cinema, podcasts, TV and more", "Apps", Routes.APPS),
            Triple("What every AI in Atlas knows about you", "Profile", Routes.PROFILE),
            Triple("What Atlas may do on this phone", "Phone control", Routes.PHONE),
            Triple("Theme, icon, AI, backups", "Settings", Routes.SETTINGS)
        ), onOpen)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Group(title: String, rows: List<Triple<String, String, String>>, onOpen: (String) -> Unit) {
    if (title.isNotBlank()) com.nizam.app.ui.design.Label(title) else Spacer(Modifier.height(16.dp))
    com.nizam.app.ui.design.Group(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        rows.forEachIndexed { i, (detail, label, route) ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(route) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
            if (i < rows.lastIndex) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 16.dp),
                thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
