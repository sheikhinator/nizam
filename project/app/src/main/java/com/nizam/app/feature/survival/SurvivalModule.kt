package com.nizam.app.feature.survival

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.nizam.app.AppContainer
import com.nizam.app.core.AiPanel
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.vmFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Entity(tableName = "checklist_item")
data class ChecklistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listName: String,
    val text: String,
    val checked: Boolean = false
)

@Dao
interface SurvivalDao {
    @Query("SELECT * FROM checklist_item ORDER BY listName, id")
    fun observeItems(): Flow<List<ChecklistItem>>

    @Query("SELECT COUNT(*) FROM checklist_item")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ChecklistItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ChecklistItem>)
}

class SurvivalRepository(private val dao: SurvivalDao) {
    fun items() = dao.observeItems()
    suspend fun toggle(item: ChecklistItem) = dao.upsert(item.copy(checked = !item.checked))
    suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        val seed = mutableListOf<ChecklistItem>()
        SEED_CHECKLISTS.forEach { (list, entries) ->
            entries.forEach { seed.add(ChecklistItem(listName = list, text = it)) }
        }
        dao.insertAll(seed)
    }
}

val SEED_CHECKLISTS: List<Pair<String, List<String>>> = listOf(
    "72-hour go bag" to listOf(
        "4 litres water per person", "Water filter or purification tablets",
        "Three days of non-perishable food", "Torch and spare batteries",
        "Power bank, fully charged", "Printed copies of ID and key documents",
        "Cash in small notes", "First aid kit", "Whistle", "Multi-tool",
        "Weather-appropriate change of clothes", "Dust mask", "Paper map of your city"
    ),
    "Home emergency kit" to listOf(
        "20 litres stored water", "Two weeks of shelf-stable food", "Manual can opener",
        "Battery or hand-crank radio", "Candles and lighter", "Fire extinguisher",
        "Blankets", "Spare gas cylinder", "Written emergency plan on the fridge"
    ),
    "Blackout kit" to listOf(
        "Rechargeable lamp", "Power bank per person", "UPS for the router",
        "Offline maps downloaded", "Cash", "Battery-powered fan in summer"
    ),
    "Travel kit" to listOf(
        "Photocopy of passport and CNIC", "Emergency contact card",
        "Basic medicines", "Charger and adapter", "Small torch", "Cash reserve"
    )
)

data class SurvivalTopic(val title: String, val body: String)

/**
 * Practical reference only. First aid and medical procedures are deliberately NOT included as
 * generated text — add vetted material from a recognised first aid organisation yourself.
 */
val SURVIVAL_TOPICS = listOf(
    SurvivalTopic(
        "Water",
        "Priority order: find, then make safe.\n\n" +
            "• Rain, running streams and springs are better starting points than standing water.\n" +
            "• Boiling is the most reliable treatment available at home: bring to a rolling boil for one minute.\n" +
            "• A filter removes sediment and many organisms, but boiling or chemical treatment is what handles viruses.\n" +
            "• Store treated water in clean, sealed containers away from sunlight.\n" +
            "• Never ration water to the point of dehydration — drink and then find more."
    ),
    SurvivalTopic(
        "Fire",
        "• Gather three grades of fuel before lighting: tinder, kindling, fuel wood.\n" +
            "• Prepare far more kindling than you think you need.\n" +
            "• Build on bare earth or stone, clear a metre around it, never under low branches.\n" +
            "• Wind matters more than technique — build a small windbreak first.\n" +
            "• Never burn charcoal or run a generator indoors."
    ),
    SurvivalTopic(
        "Shelter and warmth",
        "• Cold ground steals more heat than cold air. Insulate underneath yourself first.\n" +
            "• Stay dry: wet clothing loses most of its insulating value.\n" +
            "• Small shelters warm faster than large ones.\n" +
            "• In heat, the priority reverses: shade, airflow, and avoiding activity in peak sun."
    ),
    SurvivalTopic(
        "Navigation without GPS",
        "• Note your direction of travel before you set out, and check it regularly.\n" +
            "• Follow drainage downhill — water leads to settlements more often than not.\n" +
            "• Keep a paper map and know how to orient it against visible landmarks.\n" +
            "• If lost, stop moving, mark your position, and make yourself findable."
    ),
    SurvivalTopic(
        "Earthquake",
        "• During: drop, cover under something solid, hold on. Stay away from windows.\n" +
            "• Do not run outside during shaking; most injuries happen from falling debris at exits.\n" +
            "• After: expect aftershocks, check for gas smell, shut off supply if you suspect a leak.\n" +
            "• Keep sturdy shoes and a torch beside your bed."
    ),
    SurvivalTopic(
        "Flood",
        "• Move to higher ground early; do not wait for confirmation.\n" +
            "• Never walk or drive through moving water — depth and current are impossible to judge.\n" +
            "• Turn off electricity at the mains if water is entering the building.\n" +
            "• Treat all flood water as contaminated afterwards."
    ),
    SurvivalTopic(
        "Power and communication outage",
        "• Conserve phone battery immediately: low power mode, screen dim, data off.\n" +
            "• SMS often gets through when calls fail.\n" +
            "• Agree one out-of-area contact everyone in the family messages.\n" +
            "• Download offline maps before you need them."
    ),
    SurvivalTopic(
        "First aid",
        "This app does not ship generated medical instructions.\n\n" +
            "Get properly trained: take an in-person first aid course, and keep a printed manual from a " +
            "recognised body (Red Crescent, Red Cross, St John Ambulance) in your kit.\n\n" +
            "Add your own vetted notes to this module later, or keep them as notes in the Notes module."
    )
)

val EMERGENCY_NUMBERS = listOf(
    "Rescue 1122" to "1122",
    "Police" to "15",
    "Fire brigade" to "16",
    "Ambulance (Edhi)" to "115"
)

class SurvivalViewModel(private val repository: SurvivalRepository) : ViewModel() {
    val items: StateFlow<List<ChecklistItem>> = repository.items()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { repository.seedIfEmpty() }
    }

    fun toggle(item: ChecklistItem) = viewModelScope.launch { repository.toggle(item) }
}

@Composable
fun SurvivalScreen(container: AppContainer) {
    val vm: SurvivalViewModel = viewModel(factory = vmFactory { SurvivalViewModel(container.survivalRepository) })
    val items by vm.items.collectAsState()
    val context = LocalContext.current
    var topic by remember { mutableStateOf<SurvivalTopic?>(null) }

    val open = topic
    if (open != null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { topic = null }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back to survival guide")
                }
                Text(open.title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(open.body, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(20.dp))
            AiPanel(
                settings = container.settingsRepository,
                label = "Go deeper on ${open.title}",
                contextPrompt = "The user is reading a survival reference on \"${open.title}\". " +
                    "Existing notes:\n${open.body}",
                starterQuestion = "Give me the detailed practical version of this, step by step.",
                system = "You are an experienced survival and preparedness instructor. Be concrete and " +
                    "step-by-step. State clearly when something needs hands-on training or professional " +
                    "help rather than reading."
            )
            Spacer(Modifier.height(40.dp))
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Column(Modifier.fillMaxWidth()) {
        ModuleTitle("Survival")

        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                "Reference only. In a real emergency call emergency services. This is not a substitute for training.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("Emergency numbers", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            EMERGENCY_NUMBERS.forEach { (label, number) ->
                Text(
                    "$label $number   ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(role = Role.Button) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }

            item {
                Spacer(Modifier.height(10.dp))
                Text("Topics", style = MaterialTheme.typography.titleMedium)
            }
            items(SURVIVAL_TOPICS, key = { it.title }) { t ->
                Text(
                    t.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { topic = t }.padding(vertical = 12.dp)
                )
                Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
            item {
                Spacer(Modifier.height(16.dp))
                Text("Checklists", style = MaterialTheme.typography.titleMedium)
            }
            items(items, key = { it.id }) { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = entry.checked, onCheckedChange = { vm.toggle(entry) })
                    Column(Modifier.weight(1f)) {
                        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                        Text(entry.listName, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
    }
}
