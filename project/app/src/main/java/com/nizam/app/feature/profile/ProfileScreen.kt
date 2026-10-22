package com.nizam.app.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Everything the app's AIs should know about you, in one place — so they stop asking. */
private val FIELDS = listOf(
    "Name" to "", "Bio" to "one or two lines about you", "Age" to "", "City" to "",
    "Occupation" to "", "Employer" to "", "Education" to "", "Languages" to "",
    "Height" to "cm", "Weight" to "kg", "Goals this year" to "", "Interests" to "",
    "Strengths" to "", "Working on" to "what you want to improve", "Routine" to "e.g. work 9–6, gym evenings"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(container: AppContainer) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val settings = container.settingsRepository
    val stored by settings.profileJson.collectAsState(initial = "{}")
    val storedFace by settings.face.collectAsState(initial = "2-1-0-2-0-0-0")

    val values = remember { mutableStateMapOf<String, String>() }
    var face by remember { mutableStateOf(Face()) }
    var customKey by remember { mutableStateOf("") }
    var customValue by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(stored, storedFace) { com.nizam.app.ui.design.guard {
        if (loaded) return@LaunchedEffect
        runCatching {
            val o = JSONObject(stored)
            o.keys().forEach { values[it] = o.optString(it) }
        }
        face = Face.decode(storedFace)
        loaded = true
    } }

    fun save() = scope.launch {
        val o = JSONObject()
        values.filterValues { it.isNotBlank() }.forEach { (k, v) -> o.put(k, v) }
        settings.setProfile(o.toString())
        settings.setFace(face.encode())
        values["Name"]?.takeIf { it.isNotBlank() }?.let { settings.setDisplayName(it) }
        status = "Saved — every AI in Atlas now knows this."
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Profile", "Your knowledge base — every AI reads this")

        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FaceAvatar(face, 96.dp, selected = true)
                Spacer(Modifier.height(8.dp))
                Text(values["Name"].orEmpty().ifBlank { "You" }, style = MaterialTheme.typography.titleLarge)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("CHOOSE A FACE", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESET_FACES.forEach { preset ->
                androidx.compose.foundation.layout.Box(
                    Modifier.clickable(role = Role.Button) { face = preset }
                ) { FaceAvatar(preset, 48.dp, selected = preset == face) }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("OR BUILD YOUR OWN", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Part("Skin", List(FaceParts.skins.size) { "${it + 1}" }, face.skin) { face = face.copy(skin = it) }
        Part("Hair", FaceParts.hairNames, face.hair) { face = face.copy(hair = it) }
        Part("Hair colour", listOf("Black", "Brown", "Auburn", "Blond", "Grey", "Red"), face.hairColour) {
            face = face.copy(hairColour = it)
        }
        Part("Facial hair", FaceParts.beardNames, face.beard) { face = face.copy(beard = it) }
        Part("Headwear", FaceParts.headwearNames, face.headwear) { face = face.copy(headwear = it) }
        Part("Glasses", FaceParts.glassesNames, face.glasses) { face = face.copy(glasses = it) }
        Part("Background", List(FaceParts.backgrounds.size) { "${it + 1}" }, face.bg) { face = face.copy(bg = it) }

        Spacer(Modifier.height(18.dp))
        Text("ABOUT YOU", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FIELDS.forEach { (label, hint) ->
            OutlinedTextField(
                value = values[label].orEmpty(),
                onValueChange = { values[label] = it },
                label = { Text(if (hint.isBlank()) label else "$label · $hint") },
                singleLine = label != "Bio",
                modifier = Modifier.fillMaxWidth()
            )
        }
        values.keys.filter { k -> FIELDS.none { it.first == k } }.forEach { k ->
            OutlinedTextField(
                value = values[k].orEmpty(), onValueChange = { values[k] = it },
                label = { Text(k) }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("ADD YOUR OWN FIELD", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(customKey, { customKey = it }, label = { Text("e.g. Blood group") },
                singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(customValue, { customValue = it }, label = { Text("Value") },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        Text(
            "＋ add field",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(role = Role.Button) {
                if (customKey.isNotBlank()) { values[customKey.trim()] = customValue; customKey = ""; customValue = "" }
            }.padding(vertical = 8.dp)
        )

        Spacer(Modifier.height(10.dp))
        Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save profile") }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun Part(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium)
    ChipRow(options = options, selected = options.getOrNull(selected), onSelect = { onPick(options.indexOf(it)) })
}
