package com.nizam.app.feature.self

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.prettyDate
import com.nizam.app.core.today
import com.nizam.app.net.Ai
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.launch

@Composable
fun SelfScreen(container: AppContainer) {
    val repository = container.selfRepository
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) }
    var sheet by remember { mutableStateOf<CharacterSheet?>(null) }

    LaunchedEffect(Unit) { sheet = repository.character() }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        ModuleTitle("Self", "Who you're becoming, by the evidence")
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Character") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Review") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Identity") })
        }
        Spacer(Modifier.height(14.dp))

        when (tab) {
            0 -> CharacterTab(repository, sheet)
            1 -> ReviewTab(repository)
            else -> IdentityTab(repository, scope)
        }
    }
}

@Composable
private fun CharacterTab(repository: SelfRepository, sheet: CharacterSheet?) {
    var reading by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (sheet == null) {
            CircularProgressIndicator()
            return@Column
        }

        sheet.scores.forEach { score ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(score.attribute.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            score.attribute.meaning,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("Lv ${score.level}", style = MonoNumber, color = score.attribute.colour)
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(4.dp)
                        )
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                (score.progress.toFloat() / score.need.coerceAtLeast(1))
                                    .coerceIn(0f, 1f)
                            )
                            .height(8.dp)
                            .background(score.attribute.colour, RoundedCornerShape(4.dp))
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "active ${score.last30Days} of last 30 days",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Strongest: ${sheet.strongest.attribute.label} · Weakest: ${sheet.weakest.attribute.label}",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(18.dp))
        Button(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true; error = null
                    try {
                        reading = repository.readCharacter()
                    } catch (e: Ai.MissingKeyException) {
                        error = "Add an AI key first — Settings › API keys."
                    } catch (e: Exception) {
                        error = e.message
                    }
                    busy = false
                }
            }
        ) { Text(if (busy) "Reading…" else "Read me honestly") }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (reading.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(reading, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun ReviewTab(repository: SelfRepository) {
    val reviews by repository.reviews().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var well by remember { mutableStateOf("") }
    var badly by remember { mutableStateOf("") }
    var change by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val doneToday = reviews.any { it.kind == "DAILY" && it.localDate == today() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            if (doneToday) "Tonight's review is done." else "Tonight's review",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = well, onValueChange = { well = it },
            label = { Text("What went well?") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = badly, onValueChange = { badly = it },
            label = { Text("What didn't?") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = change, onValueChange = { change = it },
            label = { Text("One change tomorrow") }, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        try {
                            repository.saveDaily(well, badly, change)
                            well = ""; badly = ""; change = ""
                        } catch (e: Exception) {
                            error = e.message
                        }
                        busy = false
                    }
                }
            ) { Text("Close the day") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        try {
                            repository.runWeekly()
                        } catch (e: Ai.MissingKeyException) {
                            error = "Add an AI key first — Settings › API keys."
                        } catch (e: Exception) {
                            error = e.message
                        }
                        busy = false
                    }
                }
            ) { Text("Run weekly review") }
        }
        if (busy) {
            Spacer(Modifier.height(10.dp))
            CircularProgressIndicator()
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(20.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)

        reviews.forEach { review ->
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                Text(
                    "${if (review.kind == "WEEKLY") "WEEK ENDING" else "DAY"} · ${prettyDate(review.localDate)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                if (review.went_well.isNotBlank()) {
                    Text("Went well — ${review.went_well}", style = MaterialTheme.typography.bodyMedium)
                }
                if (review.went_badly.isNotBlank()) {
                    Text("Didn't — ${review.went_badly}", style = MaterialTheme.typography.bodyMedium)
                }
                if (review.oneChange.isNotBlank()) {
                    Text("Change — ${review.oneChange}", style = MaterialTheme.typography.bodyMedium)
                }
                if (review.aiReading.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(review.aiReading, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun IdentityTab(repository: SelfRepository, scope: kotlinx.coroutines.CoroutineScope) {
    val lines by repository.identity().collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Write who you are deciding to be. Not goals — identity. \"I am someone who trains " +
                "whether or not I feel like it.\" The app measures whether the evidence agrees.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = draft, onValueChange = { draft = it },
            label = { Text("I am someone who…") }, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch { repository.addIdentity(draft); draft = "" }
        }) { Text("Commit it") }

        Spacer(Modifier.height(20.dp))
        lines.forEach { line ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        line.text,
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFamily),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "remove",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(role = Role.Button) { scope.launch { repository.removeIdentity(line) } }
                    )
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}
