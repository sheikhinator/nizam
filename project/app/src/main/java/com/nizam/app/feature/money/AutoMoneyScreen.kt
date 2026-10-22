package com.nizam.app.feature.money

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.fmtAmount
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.launch

@Composable
fun AutoMoneyScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (it) BankSms.setEnabled(c, true) }
    var on by remember { mutableStateOf(BankSms.enabled(c)) }
    var auto by remember { mutableStateOf(BankSms.auto(c)) }
    var found by remember { mutableStateOf<List<BankTxn>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        ModuleTitle("Auto-logging", "Bank texts become entries — no typing")
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(on && granted, { v ->
                        if (v && !granted) ask.launch(Manifest.permission.READ_SMS) else { on = v; BankSms.setEnabled(c, v) }
                    })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Read bank messages", style = MaterialTheme.typography.titleMedium)
                        Text("Parsed on this phone — the texts never leave it, and nothing is sent to any AI.",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (on && granted) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Switch(auto, { auto = it; BankSms.setAuto(c, it) }); Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Log them automatically", style = MaterialTheme.typography.bodyLarge)
                        Text("Off: Atlas shows them here and you confirm. On: they go straight into Finance.",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (granted) Button(onClick = {
            found = BankSms.scan(c); status = if (found.isEmpty()) "No new bank messages in the last 30 days." else null
        }, modifier = Modifier.padding(top = 12.dp)) { Text("Scan the last 30 days") }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (found.isNotEmpty()) Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${found.size} found · ${fmtAmount(found.filter { it.kind == "EXPENSE" }.sumOf { it.amount })} PKR spent", style = MonoNumber)
            Text("log all", color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp).clickable2 { scope.launch { found.forEach { BankSms.log(container, c, it) }; found = emptyList(); status = "Logged." } })
        }
        LazyColumn(Modifier.weight(1f)) {
            items(found) { t ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${if (t.kind == "INCOME") "+" else "−"} ${fmtAmount(t.amount)} PKR · ${BankSms.category(t.merchant)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (t.kind == "INCOME") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text("${t.merchant} · ${t.date}", style = MaterialTheme.typography.labelMedium)
                        Text(t.raw, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Text("log it", color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable2 { scope.launch { BankSms.log(container, c, t); found = found - t } })
                            Text("skip", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable2 { BankSms.markDone(c, t.id); found = found - t })
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.clickable2(onClick: () -> Unit) =
    this.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
