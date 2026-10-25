package com.nizam.app.feature.nx

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Atlas as your phone's home screen. Off until you switch it on; then the home button opens your Atlas
 * (Today, in whichever style you picked) and the app drawer is sorted by what you actually use.
 * Switching it off gives your old launcher back.
 */
object Launcher {
    fun component(c: Context) = ComponentName(c, AtlasHomeActivity::class.java)
    fun enabled(c: Context) = c.packageManager.getComponentEnabledSetting(component(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    fun setEnabled(c: Context, on: Boolean) = c.packageManager.setComponentEnabledSetting(component(c),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    fun isDefault(c: Context): Boolean = runCatching {
        c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == c.packageName
    }.getOrDefault(false)
    fun chooseHome(c: Context) = runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    private fun p(c: Context) = c.getSharedPreferences("launcher_use", 0)
    fun used(c: Context, pkg: String) = p(c).edit().putInt(pkg, p(c).getInt(pkg, 0) + 1).putLong("t_$pkg", System.currentTimeMillis()).apply()
    fun uses(c: Context, pkg: String) = p(c).getInt(pkg, 0)

    class App(val label: String, val pkg: String, val activity: String, val icon: ImageBitmap?)

    fun apps(c: Context): List<App> {
        val pm = c.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != c.packageName }
            .map { ri ->
                val icon = runCatching { ri.loadIcon(pm).let { d ->
                    val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                    val cv = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(cv); b.asImageBitmap() } }.getOrNull()
                App(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, ri.activityInfo.name, icon)
            }.distinctBy { it.pkg + it.activity }.sortedBy { it.label.lowercase() }
    }

    fun launch(c: Context, a: App) {
        used(c, a.pkg)
        runCatching { c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(a.pkg, a.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }.onFailure { Feedback.show("Couldn't open ${a.label}") }
    }
}

/** Pressing Home lands here; it hands straight to Atlas and steps aside. */
class AtlasHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); go() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); go() }
    private fun go() {
        startActivity(Intent(this, com.nizam.app.MainActivity::class.java).putExtra("route", com.nizam.app.ui.nav.Routes.TODAY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        overridePendingTransition(0, 0)
        finish()
    }
}

@Composable
fun AppDrawerScreen() {
    val c = LocalContext.current
    var q by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val apps by produceState(initialValue = emptyList<Launcher.App>()) { value = withContext(Dispatchers.IO) { runCatching { Launcher.apps(c) }.getOrDefault(emptyList()) } }
    val top = remember(apps, tick) { apps.filter { Launcher.uses(c, it.pkg) > 0 }.sortedByDescending { Launcher.uses(c, it.pkg) }.take(8) }
    val shown = remember(apps, q) { if (q.isBlank()) apps else apps.filter { it.label.contains(q.trim(), true) } }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search ${apps.size} apps") })
        if (q.isBlank() && top.isNotEmpty()) {
            Text("You use most", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            AppGrid(top) { Launcher.launch(c, it); tick++ }
        }
        Text(if (q.isBlank()) "All apps" else "Results", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        AppGrid(shown) { Launcher.launch(c, it); tick++ }
        if (q.isNotBlank() && shown.size == 1) Text("Tip: tap to open", style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppGrid(list: List<Launcher.App>, open: (Launcher.App) -> Unit) {
    val c = LocalContext.current
    list.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            row.forEach { a ->
                Column(Modifier.weight(1f).combinedClickable(onClick = { open(a) }, onLongClick = {
                    runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }), horizontalAlignment = Alignment.CenterHorizontally) {
                    a.icon?.let { Image(it, a.label, Modifier.size(48.dp)) }
                    Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp))
                }
            }
            repeat(4 - row.size) { Column(Modifier.weight(1f)) {} }
        }
    }
}

@Composable
fun LauncherSetupScreen(open: (String) -> Unit) {
    val c = LocalContext.current
    var on by remember { mutableStateOf(Launcher.enabled(c)) }
    val isDefault = remember(on) { Launcher.isDefault(c) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Make Atlas your home screen. The home button opens your Atlas, and your apps live in an Atlas drawer sorted by what you use.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Offer Atlas as a home app", fontWeight = FontWeight.SemiBold)
                    Text(if (isDefault) "Atlas is your home screen now" else if (on) "On. Now pick Atlas as your home app" else "Off: your current launcher stays",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(on, { v -> runCatching { Launcher.setEnabled(c, v) }; on = v; if (v) Launcher.chooseHome(c) })
            }
            if (on && !isDefault) Button(onClick = { Launcher.chooseHome(c) }, modifier = Modifier.padding(top = 8.dp)) { Text("Choose home app") }
            if (!on) Text("To go back later: switch this off, then choose your old launcher.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        Button(onClick = { open("nx/appdrawer") }) { Text("Open the app drawer") }
    }
}

val LauncherSpecs: List<Spec> get() = listOf(
    ScreenSpec("launcher", "Atlas as home screen", "Make Atlas your phone's launcher, with an app drawer sorted by what you use",
        Icons.Outlined.Home, Hub.Tools, "launcher home screen default home app replace launcher drawer") { _, open -> LauncherSetupScreen(open) },
    ScreenSpec("appdrawer", "App drawer", "All your phone's apps, searchable, most-used first",
        Icons.Outlined.Apps, Hub.Tools, "apps drawer installed apps open app launcher list") { _, _ -> AppDrawerScreen() },
)
