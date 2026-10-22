package com.nizam.app.feature.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.Share
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Art3D
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.OutlinedButton
import kotlinx.coroutines.launch
import org.json.JSONObject

private val CAT_COLOURS = mapOf("Automation" to 0xFFF97316, "Productivity" to 0xFF6366F1, "Messaging" to 0xFF10B981, "Email" to 0xFFEF4444, "Developer" to 0xFF334155,
    "AI" to 0xFF8B5CF6, "Money" to 0xFF16A34A, "Commerce" to 0xFFF59E0B, "CRM" to 0xFFEC4899, "Files" to 0xFF0EA5E9, "Smart home" to 0xFF14B8A6, "Health" to 0xFFE11D48,
    "Media" to 0xFFA855F7, "Social" to 0xFF3B82F6, "News" to 0xFF64748B, "Weather" to 0xFF38BDF8, "Maps" to 0xFF22C55E, "Knowledge" to 0xFF0891B2, "Deen" to 0xFF059669,
    "Websites" to 0xFF7C3AED, "Learning" to 0xFFD97706, "Support" to 0xFF0D9488, "Travel" to 0xFF2563EB, "Shopping" to 0xFFF43F5E, "Food" to 0xFFFB923C, "Games" to 0xFF9333EA,
    "Marketing" to 0xFFDB2777, "Web" to 0xFF475569, "On this phone" to 0xFF6366F1)

private fun colourOf(s: Service) = Color(CAT_COLOURS[s.cat] ?: 0xFF6366F1)

@Composable
private fun Logo(s: Service, size: Int = 44) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(Brush.linearGradient(listOf(colourOf(s), colourOf(s).copy(alpha = 0.6f)))),
        contentAlignment = Alignment.Center) { Text(s.emoji, fontSize = (size * 0.48f).sp) }
}

@Composable
fun ConnectorsScreen(container: AppContainer) {
    val c = LocalContext.current
    val v = Connectors.version
    val all = remember(v) { Connectors.services(c) }
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("Popular") }
    var open by remember { mutableStateOf<Service?>(null) }
    var addCustom by remember { mutableStateOf(false) }
    open?.let { s -> Detail(container, s) { open = null }; return }
    if (addCustom) { CustomApi { addCustom = false }; return }
    val status = remember(v) { all.associate { it.id to Connectors.isConnected(c, it) } }
    val cats = listOf("Popular", "Connected", "Phone apps", "Via hub") + all.map { it.cat }.distinct().sorted()
    val shown = all.filter { s ->
        (q.isBlank() || s.name.contains(q, true) || s.cat.contains(q, true) || s.id.contains(q, true)) && when (cat) {
            "Popular" -> q.isNotBlank() || s.popular
            "Connected" -> status[s.id] == true && s.kind in setOf("api", "mcp")
            "Phone apps" -> s.kind == "app"
            "Via hub" -> s.kind == "hub"
            else -> s.cat == cat
        }
    }
    val connectedCount = status.count { it.value } - all.count { it.kind == "device" }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Connectors", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${all.size} services · $connectedCount connected · plus thousands more through Zapier, Composio and Pipedream",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Art3D("high_voltage", 58.dp)
        } }
        item {
            val cs = MaterialTheme.colorScheme
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.onSurface.copy(alpha = 0.06f)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, tint = cs.primary); Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search 250+ apps and services", color = cs.onSurfaceVariant)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface), cursorBrush = SolidColor(cs.primary), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { cats.forEach { k -> Capsule(k, cat == k) { cat = k } } } }
        if (cat == "Connected") item {
            val scope = com.nizam.app.ui.design.rememberSafeScope()
            var health by remember { mutableStateOf<Map<String, Pair<Boolean, String>>?>(null) }
            var checking by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connection health", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Button(onClick = { checking = true; scope.launch { health = Connectors.testAll(c); checking = false } }, enabled = !checking) {
                        if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Test all") }
                }
                health?.let { h -> if (h.isEmpty()) Text("Nothing to test yet.", style = MaterialTheme.typography.bodySmall)
                    h.forEach { (id, r) -> Text((if (r.first) "✓ " else "✕ ") + (all.firstOrNull { it.id == id }?.name ?: id) + " — " + r.second,
                        style = MaterialTheme.typography.bodySmall, color = if (r.first) Color(0xFF16A34A) else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) } }
            }
        }
        if (cat == "Popular" && q.isBlank()) item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFFF97316).copy(alpha = 0.18f), Color(0xFF8B5CF6).copy(alpha = 0.12f)))).padding(16.dp)) {
                Text("⚡ One link, thousands of apps", style = MaterialTheme.typography.titleMedium)
                Text("Gmail, Google Sheets, Outlook, Instagram, LinkedIn and other sign-in-only apps connect through an automation hub. Connect Zapier, Composio or Pipedream once and every app you add there becomes a tool for Atlas.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("zapier", "composio", "pipedream").mapNotNull { id -> all.firstOrNull { it.id == id } }.forEach { s -> Capsule((if (status[s.id] == true) "✓ " else "") + s.name.substringBefore(" —"), status[s.id] == true) { open = s } }
                }
            }
        }
        items(shown.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { s -> Tile(s, status[s.id] == true, Modifier.weight(1f)) { open = s } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (shown.isEmpty()) item { Text("No ready connector for “$q”. Add it through a hub, or as a custom API below.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedButton(onClick = { addCustom = true }, modifier = Modifier.padding(top = 6.dp)) { Text("+ Add any other API") } }
    }
}

@Composable
private fun Tile(s: Service, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val badge = when (s.kind) { "device" -> "Built in"; "app" -> if (on) "Installed" else "App"; "hub" -> if (on) "Via hub" else "Needs a hub"; "mcp" -> if (on) "Connected" else "MCP"; else -> if (on) "Connected" else "Connect" }
    val badgeColour = if (on) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(if (on) colourOf(s).copy(alpha = 0.10f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
        .border(1.dp, if (on) colourOf(s).copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(20.dp)).clickable { onClick() }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(s, 40)
            Spacer(Modifier.weight(1f))
            Text(badge, style = MaterialTheme.typography.labelSmall, color = badgeColour, modifier = Modifier.clip(RoundedCornerShape(50)).background(badgeColour.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(s.cat, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Detail(container: AppContainer, s: Service, back: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val v = Connectors.version
    val connected = remember(v) { Connectors.isConnected(c, s) }
    val values = remember { mutableStateMapOf<String, String>().apply { s.fields.forEach { put(it.key, it.default) } } }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("‹ Connectors", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { back() }.padding(vertical = 6.dp)) }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(s, 64)
            Column(Modifier.padding(start = 14.dp)) {
                Text(s.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${s.cat} · " + when (s.kind) { "api" -> "direct API"; "mcp" -> "MCP server"; "hub" -> "through an automation hub"; "app" -> "phone app"; else -> "built into your phone" } +
                    if (connected) " · ✓ ready" else "", style = MaterialTheme.typography.labelMedium, color = if (connected) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
        when (s.kind) {
            "device" -> item { Text(s.notes, style = MaterialTheme.typography.bodyMedium) }
            "hub" -> {
                item { Text("${s.name} only lets approved sign-in apps in, so Atlas reaches it through an automation hub. Connect one hub once, then add ${s.name} inside it — its actions (read, send, create…) appear as Atlas tools.",
                    style = MaterialTheme.typography.bodyMedium) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Zapier" to "https://mcp.zapier.com", "Composio" to "https://mcp.composio.dev", "Pipedream" to "https://mcp.pipedream.com").forEach { (n, u) ->
                        OutlinedButton(onClick = { Share.open(c, u) }) { Text(n) } }
                } }
                item { Text(if (Connectors.hubConnected(c)) "✓ You have a hub connected — add ${s.name} in it and ask Atlas to use it." else "No hub connected yet: open Zapier, Composio or Pipedream in Connectors → Automation.",
                    style = MaterialTheme.typography.labelLarge, color = if (Connectors.hubConnected(c)) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            "app" -> {
                item { Text(if (connected) "Installed. Atlas opens it straight to the right screen, and can operate it for you with screen control." else "Not installed on this phone.",
                    style = MaterialTheme.typography.bodyMedium) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!connected && s.pkg.isNotBlank()) Button(onClick = { Share.open(c, "market://details?id=${s.pkg}") }) { Text("Get it") }
                    s.links.keys.forEach { k -> OutlinedButton(onClick = { Connectors.openInApp(c, JSONObject().put("app", s.id).put("action", k).put("query", "Lahore").put("text", "Assalamu alaikum")).let { Feedback.show(it.second) } }) {
                        Text("Try: $k") } }
                } }
                item { Text("Ask Atlas things like “${when { "send" in s.links -> "send Ali ‘running late’ on ${s.name}"; "search" in s.links -> "search ${s.name} for biryani recipes"
                    "directions" in s.links -> "directions to Liberty Market in ${s.name}"; else -> "open ${s.name}" }}”.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            else -> {
                if (s.notes.isNotBlank()) item { Text(if (s.kind == "mcp") s.notes else "What Atlas can do: ${s.notes}", style = MaterialTheme.typography.bodyMedium) }
                if (s.tokenUrl.isNotBlank()) item { OutlinedButton(onClick = { Share.open(c, s.tokenUrl) }) { Text(if (s.kind == "mcp") "Open ${s.name.substringBefore(" —")}" else "Get your key ↗") } }
                if (connected) {
                    item { Text("✓ Connected. Keys are sealed in this phone's hardware keystore and are never shown to the AI.", color = Color(0xFF16A34A), style = MaterialTheme.typography.bodyMedium) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { busy = true; scope.launch { result = Connectors.test(c, s); busy = false } }, enabled = !busy && s.kind == "api") { Text(if (busy) "Testing…" else "Test") }
                        OutlinedButton(onClick = { Connectors.disconnect(c, s); result = null; Feedback.show("Disconnected") }) { Text("Disconnect") }
                    } }
                } else {
                    s.fields.forEach { f -> item(f.key) {
                        OutlinedTextField(values[f.key].orEmpty(), { values[f.key] = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(f.label) },
                            placeholder = { if (f.hint.isNotBlank()) Text(f.hint) }, visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
                    } }
                    item { Button(onClick = { busy = true; scope.launch { result = Connectors.connect(c, s, values.toMap()); busy = false } }, enabled = !busy) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (s.fields.isEmpty()) "Add" else "Connect & test") } }
                }
                result?.let { (ok, msg) -> item { Text(msg, color = if (ok) Color(0xFF16A34A) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
                item { Text("Then just ask: “${when (s.cat) { "Productivity" -> "add ‘call the bank’ to my ${s.name}"; "Developer" -> "what's open in my ${s.name}?"
                    "Messaging" -> "post ‘meeting moved to 3’ on ${s.name}"; "Money" -> "how much came in on ${s.name} this week?"; "Smart home" -> "turn off the lounge lights"
                    else -> "use ${s.name} to …" }}”", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun CustomApi(back: () -> Unit) {
    val c = LocalContext.current
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("https://") }
    var style by remember { mutableStateOf("Bearer") }
    var header by remember { mutableStateOf("X-Api-Key") }
    var token by remember { mutableStateOf("") }
    var test by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("‹ Connectors", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { back() }.padding(vertical = 6.dp)) }
        item { Text("Add any API", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("Anything with a key or token works — your school portal, a company tool, your own server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true) }
        item { OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Bearer", "Header", "Query", "None").forEach { k -> Capsule(k, style == k) { style = k } } } }
        if (style == "Header" || style == "Query") item { OutlinedTextField(header, { header = it }, Modifier.fillMaxWidth(), label = { Text(if (style == "Header") "Header name" else "Query parameter") }, singleLine = true) }
        if (style != "None") item { OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Key / token") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
        item { OutlinedTextField(test, { test = it }, Modifier.fillMaxWidth(), label = { Text("Test path (optional), e.g. /me") }, singleLine = true) }
        item { OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes for Atlas: useful endpoints") }, minLines = 2) }
        item { Button(onClick = {
            val auth = when (style) { "Bearer" -> Catalog.BEARER; "Header" -> "H:$header={token}"; "Query" -> "Q:$header={token}"; else -> "" }
            Connectors.addCustom(c, name.trim(), base.trim(), auth, test.trim(), notes.trim(), token.trim()); Feedback.show("$name added"); back()
        }, enabled = name.isNotBlank() && base.startsWith("http")) { Text("Add") } }
    }
}
