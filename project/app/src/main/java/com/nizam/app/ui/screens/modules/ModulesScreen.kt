package com.nizam.app.ui.screens.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.LaunchedEffect
import com.nizam.app.ui.design.pressable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.dynamic.ModuleSpec
import com.nizam.app.feature.dynamic.SpecField
import com.nizam.app.feature.dynamic.parseColour
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.Spine
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

private data class Tile(
    val emoji: String,
    val name: String,
    val route: String,
    val colour: Color,
    val group: String,
    val dynamicKey: String? = null,
    val builtIn: Boolean = false
)

private data class NewModuleFieldDraft(
    val label: String = "",
    val type: String = "TEXT",
    val options: String = "",
    val required: Boolean = false
)

private val NEW_MODULE_FIELD_TYPES = listOf(
    "TEXT", "LONGTEXT", "NUMBER", "DATE", "CHOICE", "MULTI_SELECT", "TAGS", "RATING", "BOOL", "FILE"
)

private fun customFieldKey(label: String, index: Int, used: MutableSet<String>): String {
    var base = label.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(34)
        .ifBlank { "field_${index + 1}" }
    if (base.firstOrNull()?.isLetter() != true) base = "field_$base"
    base = base.take(40)
    var key = base
    var suffix = 2
    while (!used.add(key)) {
        val tail = "_$suffix"
        key = base.take(40 - tail.length) + tail
        suffix++
    }
    return key
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ModulesScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val modeStore = androidx.compose.runtime.remember(context) { com.nizam.app.feature.nx.ModeNavigationStore.get(context) }
    val modeState by modeStore.state.collectAsState()
    val specs by container.dynamicRepository.specs().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf("All") }

    val core = listOf(

        Tile("⚖️", "Council", Routes.COUNCIL, Spine.Diary, "Daily"),

        Tile("✅", "Tasks", Routes.TASKS, Spine.Tasks, "Daily"),
        Tile("🕌", "Prayer", Routes.PRAYER, Spine.Prayer, "Faith"),
        Tile("🤲", "Deen", Routes.DEEN, Spine.Prayer, "Faith"),
        Tile("📖", "Quran", Routes.QURAN, Spine.Scripture, "Faith"),
        Tile("📜", "Hadith", Routes.HADITH, Spine.Scripture, "Faith"),

        Tile("💰", "Finance", Routes.FINANCE, Spine.Finance, "Money"),
        Tile("🥗", "Diet", Routes.DIET, Spine.Diet, "Body"),
        Tile("🏋️", "Gym", Routes.GYM, Spine.Gym, "Body"),

        Tile("🎓", "Courses", Routes.COURSES, Spine.Learning, "Mind"),
        Tile("🧠", "Learning", Routes.LEARNING, Spine.Learning, "Mind"),
        Tile("🗒️", "Notes", Routes.NOTES, Spine.Notes, "Mind"),
        Tile("📚", "Library", Routes.LIBRARY, Spine.Library, "Mind"),
        Tile("✍️", "Diary", Routes.DIARY, Spine.Diary, "Mind"),
        Tile("💬", "Quotes", Routes.QUOTES, Spine.Diary, "Mind"),
        Tile("🎥", "Rep counter", Routes.REPS, Spine.Gym, "Body"),
        Tile("📸", "Snap plate", Routes.PLATE, Spine.Diet, "Body"),
        Tile("🎧", "Voice coach", Routes.COACH, Spine.Gym, "Body"),
        Tile("🗣", "Language", Routes.LANGUAGE, Spine.Learning, "Mind"),
        Tile("🌤", "Daily life", Routes.DAILY, Spine.Diet, "Daily"),
        Tile("🏘", "Pixel Town", Routes.TOWN, Spine.Gym, "Tools"),
        Tile("🔒", "Vault", Routes.VAULT, Spine.Gym, "Tools"),
        Tile("🛠", "Tools", Routes.TOOLS, Spine.Gym, "Tools"),
        Tile("🏦", "Auto-logging", Routes.AUTOMONEY, Spine.Gym, "Money"),
        Tile("📷", "Scanner", Routes.SCAN, Spine.Gym, "Tools"),
        Tile("🧰", "Toolkit", Routes.TOOLKIT, Spine.Gym, "Tools"),
        Tile("🌐", "Browser", Routes.BROWSER, Spine.Notes, "Tools"),
        Tile("📰", "News", Routes.NEWS, Spine.Finance, "Tools"),
        Tile("⛑️", "Survival", Routes.SURVIVAL, Spine.Survival, "Tools")
    )

    val dynamic = specs.map {
        Tile(it.emoji, it.name, Routes.dynamic(it.key), parseColour(it.colorHex, Spine.Notes),
            modeState.moduleGroups[it.key] ?: "Tracked", it.key, it.builtIn)
    }

    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val hidden by container.settingsRepository.hiddenTiles.collectAsState(initial = emptySet())
    val savedOrder by container.settingsRepository.tileOrder.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Tile?>(null) }
    var showNewModule by remember { mutableStateOf(false) }
    var moduleName by remember { mutableStateOf("") }
    var moduleTagline by remember { mutableStateOf("") }
    val moduleFields = remember { mutableStateListOf(NewModuleFieldDraft()) }
    var fieldTypeMenu by remember { mutableStateOf<Int?>(null) }
    var moduleCreateError by remember { mutableStateOf<String?>(null) }
    var creatingModule by remember { mutableStateOf(false) }

    // Your order first, then anything new appended — so a freshly created module never vanishes
    val all = (core + dynamic).let { tiles ->
        val byRoute = tiles.associateBy { it.route }
        val areas = (listOf("Daily", "Body", "Mind", "Money", "Faith", "Tracked", "Tools") + modeState.customGroups).distinct()
        val natural = tiles.sortedBy { areas.indexOf(it.group).let { i -> if (i < 0) 99 else i } }
        savedOrder.mapNotNull { byRoute[it] } + natural.filterNot { it.route in savedOrder }
    }
    val ctx = context
    var focusOn by remember { mutableStateOf(com.nizam.app.feature.wellbeing.FocusPillars.on(ctx)) }
    var pillars by remember { mutableStateOf(com.nizam.app.feature.wellbeing.FocusPillars.chosen(ctx)) }
    var pickPillars by remember { mutableStateOf(false) }
    // Focus Mode: fewer things on screen, by design — research says people keep apps that stay simple
    val visible = (if (editing) all else all.filterNot { it.route in hidden })
        .filter { editing || !focusOn || it.group in pillars || it.group in modeState.customGroups }

    fun move(tile: Tile, delta: Int) {
        val order = all.map { it.route }.toMutableList()
        val i = order.indexOf(tile.route)
        val j = (i + delta).coerceIn(0, order.lastIndex)
        if (i == j) return
        order.removeAt(i); order.add(j, tile.route)
        scope.launch { container.settingsRepository.setTileOrder(order) }
    }

    fun createCustomModule() {
        val cleanName = moduleName.trim()
        val cleanTagline = moduleTagline.trim()
        if (cleanName.isBlank() || cleanName.length > 60) {
            moduleCreateError = "Enter a module name of 1–60 characters."
            return
        }
        if (cleanTagline.length > 120) {
            moduleCreateError = "Keep the description under 120 characters."
            return
        }
        if (moduleFields.isEmpty() || moduleFields.size > 12) {
            moduleCreateError = "Add between 1 and 12 fields."
            return
        }
        if (moduleFields.any { it.label.isBlank() || it.label.trim().length > 80 }) {
            moduleCreateError = "Give every field a name of 1–80 characters."
            return
        }

        val optionsByField = mutableListOf<List<String>>()
        for (field in moduleFields) {
            if (field.type == "CHOICE" || field.type == "MULTI_SELECT") {
                val options = field.options.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (options.size !in 2..20 || options.any { it.length > 60 } ||
                    options.map { it.lowercase(Locale.ROOT) }.distinct().size != options.size
                ) {
                    moduleCreateError = "${field.label.trim()} needs 2–20 distinct options, separated by commas."
                    return
                }
                optionsByField.add(options)
            } else {
                optionsByField.add(emptyList())
            }
        }

        val usedKeys = mutableSetOf<String>()
        val fields = moduleFields.mapIndexed { index, field ->
            SpecField(
                key = customFieldKey(field.label.trim(), index, usedKeys),
                label = field.label.trim(),
                type = field.type,
                options = optionsByField[index],
                required = field.required
            )
        }
        val baseKey = cleanName.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(20)
            .ifBlank { "custom" }
        val keyPrefix = if (baseKey.first().isLetter()) baseKey else "module_$baseKey"
        val key = "${keyPrefix}_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val fieldsJson = JSONArray().apply {
            fields.forEach { field ->
                put(JSONObject()
                    .put("key", field.key)
                    .put("label", field.label)
                    .put("type", field.type)
                    .put("options", JSONArray().apply { field.options.forEach { put(it) } })
                    .put("required", field.required))
            }
        }.toString()
        val spec = ModuleSpec(
            key = key,
            name = cleanName,
            emoji = "✨",
            tagline = cleanTagline,
            colorHex = "#4FA694",
            fieldsJson = fieldsJson,
            listStyle = "CARD",
            builtIn = false,
            enabled = true,
            sortIndex = 100
        )
        moduleCreateError = null
        creatingModule = true
        scope.launch {
            try {
                container.dynamicRepository.addSpec(spec)
                showNewModule = false
                moduleName = ""
                moduleTagline = ""
                moduleFields.clear()
                moduleFields += NewModuleFieldDraft()
                onOpen(Routes.dynamic(key))
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                moduleCreateError = error.message ?: "The module could not be created."
            } finally {
                creatingModule = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Life", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
            Text(
                if (editing) "Done" else "Edit",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { editing = !editing }
                    .padding(8.dp)
            )
            Text(
                "Modes",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) {
                    onOpen("nx/modesnavigation")
                }.padding(8.dp)
            )
            Text(
                "＋ Module",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) {
                    moduleCreateError = null
                    showNewModule = true
                }.padding(8.dp)
            )
        }
        if (modeState.pinnedDestinations.isNotEmpty() || modeState.recentDestinations.isNotEmpty()) {
            Text("Quick access · ${modeState.activeMode.name}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                (modeState.pinnedDestinations + modeState.recentDestinations).distinct().take(12).forEach { route ->
                    val title = all.firstOrNull { it.route == route }?.name
                        ?: com.nizam.app.ui.screens.search.DESTINATIONS.firstOrNull { it.route == route }?.name
                        ?: route
                    com.nizam.app.ui.design.Capsule(title, route in modeState.pinnedDestinations) {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { modeStore.recordRecent(route) }
                        }
                        onOpen(route)
                    }
                }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            com.nizam.app.ui.design.Capsule(if (focusOn) "Focus · ${pillars.size} areas" else "Focus mode", focusOn) {
                focusOn = !focusOn; com.nizam.app.feature.wellbeing.FocusPillars.save(ctx, focusOn, pillars); if (focusOn) pickPillars = true
            }
            if (focusOn) Text("  change", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable { pickPillars = !pickPillars })
        }
        if (pickPillars && focusOn) {
            Text("Show only what you're working on right now:", style = MaterialTheme.typography.labelMedium)
            com.nizam.app.core.MultiChipRow(com.nizam.app.feature.wellbeing.FocusPillars.ALL, pillars, { g ->
                pillars = if (g in pillars) pillars - g else pillars + g
                com.nizam.app.feature.wellbeing.FocusPillars.save(ctx, focusOn, pillars)
            })
        }
        if (editing) {
            Text(
                "‹ › move a module. Hide tucks it away (nothing is lost); delete removes only modules you created.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        confirmDelete?.let { tile ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Delete ${tile.name} and every entry in it?", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Text("Delete", color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    container.dynamicRepository.spec(tile.dynamicKey ?: "")
                                        ?.let { container.dynamicRepository.deleteModule(it) }
                                    confirmDelete = null
                                }
                            })
                        Text("Keep it", modifier = Modifier.clickable { confirmDelete = null })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        val areas = (listOf("Daily", "Body", "Mind", "Money", "Faith", "Tracked", "Tools") + modeState.customGroups).distinct()
            .filter { a -> visible.any { it.group == a } }
        if (areas.isNotEmpty()) {
            val pager = androidx.compose.foundation.pager.rememberPagerState { areas.size }
            val swipe = com.nizam.app.ui.design.rememberSafeScope()
            // a category strip that follows your swipe, and swipes when you tap it
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(areas) { area ->
                    val i = areas.indexOf(area)
                    com.nizam.app.ui.design.Capsule(area, pager.currentPage == i) { swipe.launch { pager.animateScrollToPage(i) } }
                }
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.pager.HorizontalPager(
                pager, Modifier.fillMaxSize(), pageSpacing = 16.dp,
                verticalAlignment = androidx.compose.ui.Alignment.Top      // pages were centring, leaving a huge gap
            ) { page ->
                val tiles = visible.filter { it.group == areas[page] }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (modeState.lowCognitiveLoad) 2 else 4),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 110.dp)
                ) {
            itemsIndexed(tiles) { index, tile ->
                // iOS home-screen feel: icons arrive in a quick cascade, press down with a spring, and wiggle while editing
                val appear = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(tile.route, modeState.lowCognitiveLoad) { com.nizam.app.ui.design.guard {
                    if (modeState.lowCognitiveLoad) appear.snapTo(1f)
                    else {
                        kotlinx.coroutines.delay(index * 28L)
                        appear.animateTo(1f, androidx.compose.animation.core.spring(0.62f, 380f))
                    }
                } }
                val angle = if (editing && !modeState.lowCognitiveLoad) androidx.compose.animation.core.rememberInfiniteTransition(label = "wiggle").animateFloat(-2.2f, 2.2f,
                    androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(130 + (index % 3) * 20),
                        androidx.compose.animation.core.RepeatMode.Reverse), label = "a").value else 0f
                Column(
                    Modifier.fillMaxWidth()
                        .graphicsLayer { alpha = appear.value; scaleX = 0.6f + 0.4f * appear.value; scaleY = scaleX; translationY = (1f - appear.value) * 40f
                            rotationZ = if (editing) angle else 0f }
                        .alpha(if (editing && tile.route in hidden) 0.4f else 1f)
                        .pressable {
                            if (!editing) {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { modeStore.recordRecent(tile.route) }
                                }
                                onOpen(tile.route)
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    com.nizam.app.ui.components.AppIcon(
                        tile.emoji, tile.colour, if (modeState.lowCognitiveLoad) 72.dp else 60.dp
                    )
                    Text(
                        tile.name,
                        style = if (modeState.lowCognitiveLoad) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 7.dp)
                    )
                    if (tile.dynamicKey?.let { it in modeState.activeMode.offlineContent } == true) {
                        Text("Offline priority", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    if (editing) Row(horizontalArrangement = Arrangement.Center) {
                        Text("‹", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { move(tile, -1) }.padding(horizontal = 5.dp))
                        Text(if (tile.route in hidden) "show" else "hide",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    container.settingsRepository.setHiddenTiles(
                                        if (tile.route in hidden) hidden - tile.route else hidden + tile.route)
                                }
                            }.padding(4.dp))
                        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { move(tile, 1) }.padding(horizontal = 5.dp))
                    }
                    if (editing && tile.dynamicKey != null && !tile.builtIn) Text("delete",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { confirmDelete = tile }.padding(2.dp))
                    if (editing && tile.dynamicKey != null) {
                        val groupOptions = (listOf("Tracked") + modeState.customGroups).distinct()
                        Text(
                            "Group: ${tile.group} · change",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                val index = groupOptions.indexOf(tile.group).coerceAtLeast(0)
                                val next = groupOptions[(index + 1) % groupOptions.size]
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        modeStore.update { current ->
                                            current.copy(moduleGroups = current.moduleGroups + (tile.dynamicKey to next))
                                        }
                                    }
                                }
                            }.padding(2.dp)
                        )
                    }
                }
            }
                }
            }
        }

    }
    if (showNewModule) {
        AlertDialog(
            onDismissRequest = { if (!creatingModule) showNewModule = false },
            title = { Text("Create a custom module") },
            text = {
                Column(
                    Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Create a local record type. You can change its display settings later; field changes use the Curator or a reviewed template migration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = moduleName,
                        onValueChange = { moduleName = it.take(80) },
                        label = { Text("Module name") },
                        singleLine = true,
                        enabled = !creatingModule,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = moduleTagline,
                        onValueChange = { moduleTagline = it.take(140) },
                        label = { Text("Description (optional)") },
                        singleLine = true,
                        enabled = !creatingModule,
                        modifier = Modifier.fillMaxWidth()
                    )
                    moduleFields.forEachIndexed { index, field ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Field ${index + 1}", modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelLarge)
                                    TextButton(
                                        enabled = moduleFields.size > 1 && !creatingModule,
                                        onClick = {
                                            moduleFields.removeAt(index)
                                            fieldTypeMenu = null
                                        }
                                    ) { Text("Remove") }
                                }
                                OutlinedTextField(
                                    value = field.label,
                                    onValueChange = { moduleFields[index] = field.copy(label = it.take(100)) },
                                    label = { Text("Field name") },
                                    singleLine = true,
                                    enabled = !creatingModule,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Field type", modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelMedium)
                                    Box {
                                        OutlinedButton(
                                            onClick = { fieldTypeMenu = index },
                                            enabled = !creatingModule
                                        ) { Text(field.type) }
                                        DropdownMenu(
                                            expanded = fieldTypeMenu == index,
                                            onDismissRequest = { fieldTypeMenu = null }
                                        ) {
                                            NEW_MODULE_FIELD_TYPES.forEach { type ->
                                                DropdownMenuItem(
                                                    text = { Text(type) },
                                                    onClick = {
                                                        moduleFields[index] = field.copy(type = type)
                                                        fieldTypeMenu = null
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                if (field.type == "CHOICE" || field.type == "MULTI_SELECT") {
                                    OutlinedTextField(
                                        value = field.options,
                                        onValueChange = { moduleFields[index] = field.copy(options = it.take(500)) },
                                        label = { Text("Options, separated by commas") },
                                        singleLine = true,
                                        enabled = !creatingModule,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Required", modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = field.required,
                                        onCheckedChange = { moduleFields[index] = field.copy(required = it) },
                                        enabled = !creatingModule
                                    )
                                }
                            }
                        }
                    }
                    if (moduleFields.size < 12) {
                        OutlinedButton(
                            onClick = { moduleFields += NewModuleFieldDraft() },
                            enabled = !creatingModule,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("＋ Add field") }
                    }
                    moduleCreateError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { createCustomModule() }, enabled = !creatingModule) {
                    Text(if (creatingModule) "Creating…" else "Create module")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewModule = false }, enabled = !creatingModule) { Text("Cancel") }
            }
        )
    }
}
