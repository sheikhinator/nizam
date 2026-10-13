package com.nizam.app.core.spine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.design.Radius
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "What Atlas did" — the window onto the trust spine. Every autonomous act Atlas took is listed
 * here from its [Receipts] log, newest first, so nothing it does is hidden. Reversible actions are
 * marked; the Undo wire lands in the next pass.
 */
@Composable
fun AtlasActivityScreen() {
    val context = LocalContext.current
    var receipts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    LaunchedEffect(Unit) {
        receipts = withContext(Dispatchers.IO) { Receipts.recent(context, 100) }
    }

    val timeFmt = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Space.page),
        verticalArrangement = Arrangement.spacedBy(Space.s)
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = Space.m)) {
                Text("What Atlas did", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Everything Atlas did on its own, newest first. Nothing happens silently.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (receipts.isEmpty()) {
            item {
                Text(
                    "Nothing yet — Atlas hasn't needed to act on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.l)
                )
            }
        }
        items(receipts) { r ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = Radius.card,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Space.l)) {
                    Text(
                        r.optString("detail").ifBlank { r.optString("action") },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val when_ = runCatching { timeFmt.format(Date(r.optLong("at"))) }.getOrDefault("")
                    val tag = r.optString("domain") + " · " + r.optString("action") +
                        (if (!r.optBoolean("reversible", true)) " · can't be undone" else "")
                    Text(
                        "$when_   ·   $tag",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
