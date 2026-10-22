package com.nizam.app.feature.store

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.launch

@Composable
fun AppStoreScreen() {
    var tab by remember { mutableStateOf("My apps") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("App store", "Your apps from F-Droid, IzzyOnDroid, GitHub and Google Play — in one catalogue")
        ChipRow(listOf("My apps", "F-Droid", "Google Play", "GitHub", "Link"), tab, { tab = it })
        Spacer(Modifier.height(8.dp))
        InstallProgress()
        when (tab) {
            "My apps" -> MyApps { tab = it }
            "F-Droid" -> FdroidTab()
            "Google Play" -> PlayTab()
            "GitHub" -> GithubTab()
            else -> LinkTab()
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun InstallProgress() {
    val p by AppStore.progress.collectAsState()
    val cur = p ?: return
    if (cur.state == "done") return
    Group(Modifier.padding(bottom = 10.dp)) {
        Text(when (cur.state) { "failed" -> "Couldn't install ${cur.name}"; "installing" -> "Opening the installer for ${cur.name}"
            else -> "Downloading ${cur.name} · ${cur.percent}%" }, style = MaterialTheme.typography.titleSmall)
        if (cur.state == "downloading") LinearProgressIndicator(progress = { cur.percent / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant)
        if (cur.state == "failed") Text(cur.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** The app's own icon if it's installed, otherwise the store's picture, otherwise its first letter. */
@Composable
private fun AppIcon(app: StoreApp, size: Int = 44) {
    val c = LocalContext.current
    val installed = remember(app.pkg) {
        app.pkg.takeIf { it.isNotBlank() }?.let { runCatching { c.packageManager.getApplicationIcon(it) }.getOrNull() }
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)), contentAlignment = Alignment.Center) {
        when {
            installed != null -> androidx.compose.foundation.Image(
                installed.toBitmap(96, 96).asImageBitmap(), app.name, Modifier.size(size.dp))
            app.icon.isNotBlank() -> AsyncImage(app.icon, app.name, Modifier.size(size.dp))
            else -> Text(app.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun AppRow(app: StoreApp, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(app.source, app.version, app.summary).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** Installs an open-source entry, asking for the one-time "install unknown apps" permission first if needed. */
private fun installFlow(c: android.content.Context, app: StoreApp) {
    if (!AppStore.canInstall(c)) {
        Feedback.show("Allow Atlas to install apps, then tap Install again")
        AppStore.askInstallPermission(c); return
    }
    AppStore.install(c, app)
}

// ── My apps ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun MyApps(goTo: (String) -> Unit) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var tick by remember { mutableIntStateOf(0) }
    // re-read when you come back from an installer or uninstaller
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val apps = remember(tick) { AppStore.catalogue(c) }
    var updates by remember { mutableStateOf<List<StoreApp>>(emptyList()) }
    LaunchedEffect(tick) { com.nizam.app.ui.design.guard { updates = runCatching { AppStore.updates(c) }.getOrElse { emptyList() } } }
    var editing by remember { mutableStateOf<StoreApp?>(null) }

    if (apps.isEmpty()) {
        Text("Nothing here yet. Find apps in F-Droid, GitHub or Google Play and add them — they'll live here, ready to open, " +
            "update or remove.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { goTo("F-Droid") }) { Text("Browse F-Droid") }
            OutlinedButton(onClick = { goTo("Google Play") }) { Text("Add from Play") }
        }
        return
    }
    if (updates.isNotEmpty()) {
        Label("Updates")
        Group(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) {
            updates.forEach { u -> AppRow(u) { Button(onClick = { installFlow(c, u); AppStore.update(c, u) }) { Text("Update") } } }
        }
    }
    Label("Your catalogue")
    Group(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) {
        apps.forEachIndexed { i, app ->
            val have = app.pkg.takeIf { it.isNotBlank() }?.let { AppStore.installedVersion(c, it) }.also { tick }
            Column {
                AppRow(app) {
                    if (have != null) Button(onClick = { if (!AppStore.launch(c, app.pkg)) Feedback.show("${app.name} has no screen to open") }) { Text("Open") }
                    else when {
                        app.source == "Google Play" -> OutlinedButton(onClick = { AppStore.openInStore(c, app.pkg) }) { Text("Get") }
                        app.apkUrl.isNotBlank() -> OutlinedButton(onClick = { installFlow(c, app) }) { Text("Install") }
                        else -> OutlinedButton(onClick = {
                            scope.launch { runCatching { AppStore.resolveRepo(app) }.onSuccess { r -> AppStore.update(c, r); installFlow(c, r) }
                                .onFailure { Feedback.failed("Finding the APK", it) } }
                        }) { Text("Install") }
                    }
                }
                Row(Modifier.padding(start = 56.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (have != null) Text("v${have.first}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (have != null) Link("App info") { AppStore.appInfo(c, app.pkg) }
                    if (have != null) Link("Uninstall", error = true) { AppStore.uninstall(c, app.pkg) }
                    if (app.source == "Google Play" && have != null) Link("Store page") { AppStore.openInStore(c, app.pkg) }
                    Link("Edit") { editing = app }
                    Link("Remove") { AppStore.remove(c, app); tick++ }
                }
                if (app.note.isNotBlank()) Text(app.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 56.dp, bottom = 8.dp))
                if (editing == app) EditApp(app) { edited -> if (edited != null) AppStore.update(c, edited); editing = null; tick++ }
            }
            if (i < apps.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun Link(text: String, error: Boolean = false, onClick: () -> Unit) = Text(text,
    style = MaterialTheme.typography.labelMedium,
    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    modifier = Modifier.clickable(role = Role.Button, onClick = onClick))

@Composable
private fun EditApp(app: StoreApp, done: (StoreApp?) -> Unit) {
    var name by remember { mutableStateOf(app.name) }
    var note by remember { mutableStateOf(app.note) }
    var apk by remember { mutableStateOf(app.apkUrl) }
    Column(Modifier.padding(start = 56.dp, bottom = 10.dp)) {
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
        if (app.source != "Google Play") OutlinedTextField(apk, { apk = it.trim() }, singleLine = true, label = { Text("APK link") },
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { done(app.copy(name = name.trim().ifBlank { app.name }, note = note.trim(), apkUrl = apk)) }) { Text("Save") }
            OutlinedButton(onClick = { done(null) }) { Text("Cancel") }
        }
    }
}

// ── F-Droid ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun FdroidTab() {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StoreApp>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pkg by remember { mutableStateOf("") }
    Text("Free and open-source apps, installed straight from the repository.", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Search F-Droid") })
        Spacer(Modifier.width(8.dp))
        Button(enabled = q.isNotBlank() && !busy, onClick = {
            busy = true
            scope.launch { results = runCatching { AppStore.searchFdroid(q) }.onFailure { Feedback.failed("Searching F-Droid", it) }
                .getOrElse { emptyList() }; busy = false }
        }) { Text("Search") }
    }
    if (busy) Thinking(label = "Searching F-Droid")
    results?.let { list ->
        if (list.isEmpty()) Text("No matches.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEach { app -> AppRow(app) { AddButtons(app) } }
    }
    Label("By package name (F-Droid or IzzyOnDroid)")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(pkg, { pkg = it.trim() }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("com.aurora.store") })
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = pkg.contains('.'), onClick = {
            scope.launch {
                val found = runCatching { AppStore.lookup(pkg) }.getOrNull()
                if (found == null) Feedback.show("Not found in F-Droid or IzzyOnDroid")
                else { AppStore.add(c, found); Feedback.show("Added ${found.pkg} from ${found.source}") }
            }
        }) { Text("Add") }
    }
    Text("Tip: Aurora Store (com.aurora.store) is on F-Droid — add it here to browse Google Play anonymously.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun AddButtons(app: StoreApp) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var added by remember { mutableStateOf(AppStore.catalogue(c).any { it.pkg == app.pkg }) }
    OutlinedButton(onClick = {
        scope.launch {
            val resolved = runCatching { AppStore.resolveRepo(app) }.getOrElse { app }
            AppStore.add(c, resolved); added = true
            Feedback.show("${app.name} added to your catalogue")
        }
    }, enabled = !added) { Text(if (added) "Added" else "Add") }
}

// ── Google Play / Aurora ─────────────────────────────────────────────────────────────────────────

@Composable
private fun PlayTab() {
    val c = LocalContext.current
    var q by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    val aurora = remember { AppStore.auroraInstalled(c) }
    Text("Google Play has no public API, so Atlas doesn't browse it directly. Search opens ${if (aurora) "Aurora Store" else "the Play Store"}; " +
        "add any app to your catalogue by pasting its link or package name, and install or update it from there with one tap.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Search Google Play") })
        Spacer(Modifier.width(8.dp))
        Button(enabled = q.isNotBlank(), onClick = { AppStore.searchInStore(c, q) }) { Text("Search") }
    }
    Label("Add to your catalogue")
    OutlinedTextField(input, { input = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("play.google.com/store/apps/details?id=… or com.example.app") })
    Button(enabled = AppStore.playPackage(input) != null, modifier = Modifier.padding(top = 6.dp), onClick = {
        val pkg = AppStore.playPackage(input)!!
        val label = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrElse { pkg }
        AppStore.add(c, StoreApp(pkg, label, "", "", "Google Play"))
        input = ""; Feedback.show("Added — find it in My apps")
    }) { Text("Add") }
    if (!aurora) Text("Want Play apps without a Google account? Add Aurora Store from the F-Droid tab (com.aurora.store).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
}

// ── GitHub releases ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun GithubTab() {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var repo by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<StoreApp?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("Many open-source apps publish their APK on GitHub. Atlas fetches the latest release's APK.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(repo, { repo = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("owner/repo") })
        Spacer(Modifier.width(8.dp))
        Button(enabled = repo.contains('/') && !busy, onClick = {
            busy = true
            scope.launch { found = runCatching { AppStore.github(repo) }.onFailure { Feedback.failed("Reading the release", it) }.getOrNull(); busy = false }
        }) { Text("Find") }
    }
    if (busy) Thinking(label = "Reading the latest release")
    found?.let { app ->
        AppRow(app) {
            OutlinedButton(onClick = { AppStore.add(c, app); Feedback.show("Added to My apps") }) { Text("Add") }
        }
        Button(onClick = { AppStore.add(c, app); installFlow(c, app) }) { Text("Install ${app.version}") }
    }
}

// ── Any link ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LinkTab() {
    val c = LocalContext.current
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Text("Any direct link to an .apk file.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(url, { url = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("https://…/app.apk") })
    OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name") })
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val app = StoreApp("", name.ifBlank { url.substringAfterLast('/').substringBefore('?') }, "", "", "Link", url)
        Button(enabled = url.startsWith("http"), onClick = { AppStore.add(c, app); installFlow(c, app) }) { Text("Install") }
        OutlinedButton(enabled = url.startsWith("http"), onClick = { AppStore.add(c, app); Feedback.show("Added to My apps") }) { Text("Just add") }
    }
    Text("Only install APKs from sources you trust. Android will show what the app is and ask you to confirm.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
}
