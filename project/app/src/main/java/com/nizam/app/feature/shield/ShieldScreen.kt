package com.nizam.app.feature.shield

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
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.phone.AtlasAccessibilityService

@Composable
fun ShieldScreen() {
    val c = LocalContext.current
    val apps = remember { Shield.apps(c) }
    var blocked by remember { mutableStateOf(Shield.blocked(c)) }
    var focus by remember { mutableStateOf(Shield.duringFocus(c)) }
    var prayer by remember { mutableStateOf(Shield.duringPrayer(c)) }
    var active by remember { mutableStateOf(Shield.isActive(c)) }
    var minutes by remember { mutableStateOf("30") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Focus shield", "Opening a blocked app sends you straight back home")
        if (AtlasAccessibilityService.instance == null) {
            Text("The shield needs Atlas's accessibility service. Turn it on in Me > Phone control.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (active) {
                    Text("Shield is ON — ${((Shield.activeUntil(c) - System.currentTimeMillis()) / 60_000) + 1} min left",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { Shield.stop(c); active = false }) { Text("Turn off now") }
                } else {
                    Text("Shield now for", style = MaterialTheme.typography.titleMedium)
                    ChipRow(listOf("15", "30", "60", "120"), minutes, { minutes = it }, labels = { "$it min" })
                    Button(enabled = blocked.isNotEmpty(), onClick = { Shield.activate(c, minutes.toInt()); active = true }) {
                        Text("Start shield")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(focus, { focus = it; Shield.setDuringFocus(c, it) }); Spacer(Modifier.width(10.dp))
                    Text("Shield automatically during focus sessions", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(prayer, { prayer = it; Shield.setDuringPrayer(c, it) }); Spacer(Modifier.width(10.dp))
                    Text("Shield for 20 minutes at each prayer time", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("BLOCKED APPS (${blocked.size})", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        apps.forEach { (label, pkg) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(pkg in blocked, { on ->
                    blocked = if (on) blocked + pkg else blocked - pkg; Shield.setBlocked(c, blocked)
                })
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
