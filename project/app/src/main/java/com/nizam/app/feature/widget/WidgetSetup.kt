package com.nizam.app.feature.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.launch

/** Choose the four lines of the "My Atlas" home-screen widget; the widget updates straight away. */
@Composable
fun WidgetSetupScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(MyWidget.lines(ctx)) }
    var title by remember { mutableStateOf(MyWidget.title(ctx)) }
    fun push() { scope.launch { runCatching { MyWidget.refresh(ctx) } } }
    AtlasScreen(title = "My widget", subtitle = "Add \"My Atlas\" from your home screen's widget list, then pick what it shows.") {
        item("t") {
            OutlinedTextField(title, { title = it; MyWidget.setTitle(ctx, it); push() }, Modifier.fillMaxWidth().padding(horizontal = Space.page),
                label = { Text("Heading") }, singleLine = true)
        }
        lines.forEachIndexed { i, cur ->
            item("h$i") { SectionHeader("Line ${i + 1}") }
            item("l$i") {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    items(MyWidget.SOURCES.entries.toList()) { (k, label) ->
                        Capsule(label, k == cur) { MyWidget.setLine(ctx, i, k); lines = MyWidget.lines(ctx); push() }
                    }
                }
            }
        }
        item("note") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Text("The widget refreshes every half hour and whenever you leave Atlas.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
