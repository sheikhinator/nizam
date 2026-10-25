package com.nizam.app.feature.diet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.nizam.app.AppContainer
import com.nizam.app.core.AiPanel
import com.nizam.app.core.LineChart
import com.nizam.app.core.Point
import com.nizam.app.core.StatRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.today
import com.nizam.app.core.vmFactory
import com.nizam.app.ui.theme.Spine
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Entity(tableName = "meal_item")
data class MealItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localDate: String,
    val slot: String,
    val name: String,
    val kcal: Double,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "water_log")
data class WaterLog(
    @PrimaryKey val localDate: String,
    val ml: Int
)

@Entity(tableName = "water_intake")
data class WaterIntake(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val localDate: String,
    val ml: Int,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val deletionHandled: Boolean = false
)

@Entity(tableName = "body_metric")
data class BodyMetric(
    @PrimaryKey val localDate: String,
    val weight: Double,
    @ColumnInfo(defaultValue = "0") val recordedAt: Long = System.currentTimeMillis()
)

@Dao
interface DietDao {
    @Query("SELECT * FROM meal_item WHERE localDate = :date ORDER BY id ASC")
    fun observeDay(date: String): Flow<List<MealItem>>

    @Query("SELECT DISTINCT localDate FROM meal_item WHERE localDate >= :from")
    fun observeLoggedDays(from: String): Flow<List<String>>

    @Query("SELECT * FROM meal_item ORDER BY id DESC LIMIT 300")
    fun observeRecent(): Flow<List<MealItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MealItem)

    @Delete
    suspend fun delete(item: MealItem)

    @Query("SELECT * FROM water_log WHERE localDate = :date")
    fun observeWater(date: String): Flow<WaterLog?>

    @Query("SELECT * FROM water_log WHERE localDate = :date LIMIT 1")
    suspend fun getWaterLog(date: String): WaterLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWater(log: WaterLog)

    @Insert
    suspend fun insertWaterIntake(intake: WaterIntake)

    @Query("SELECT * FROM water_intake WHERE localDate = :date AND deleted = 0 ORDER BY createdAt DESC, id DESC")
    suspend fun activeWaterIntakesForDate(date: String): List<WaterIntake>

    @Query("UPDATE water_intake SET ml = :ml WHERE id = :id")
    suspend fun updateWaterIntakeMl(id: String, ml: Int)

    @Query("UPDATE water_intake SET deleted = 1 WHERE id = :id")
    suspend fun markWaterIntakeDeleted(id: String)

    @Transaction
    suspend fun applyWaterChange(localDate: String, deltaMl: Int) {
        val currentMl = getWaterLog(localDate)?.ml ?: 0
        val updatedMl = (currentMl.toLong() + deltaMl)
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val log = WaterLog(localDate, updatedMl)
        upsertWater(log)
        when {
            deltaMl > 0 -> insertWaterIntake(WaterIntake(localDate = log.localDate, ml = deltaMl))
            deltaMl < 0 -> {
                var remaining = -deltaMl.toLong()
                for (intake in activeWaterIntakesForDate(log.localDate)) {
                    val removed = minOf(remaining, intake.ml.toLong())
                    if (removed <= 0L) continue
                    val afterCorrection = intake.ml - removed.toInt()
                    if (afterCorrection == 0) markWaterIntakeDeleted(intake.id)
                    else updateWaterIntakeMl(intake.id, afterCorrection)
                    remaining -= removed
                    if (remaining == 0L) break
                }
            }
        }
    }

    @Query("SELECT * FROM water_intake WHERE deleted = 1 AND deletionHandled = 0 ORDER BY id ASC")
    suspend fun pendingWaterIntakeDeletions(): List<WaterIntake>

    @Query("SELECT * FROM water_intake WHERE deleted = 0 ORDER BY createdAt DESC, id DESC")
    suspend fun recentActiveWaterIntakes(): List<WaterIntake>

    @Query("UPDATE water_intake SET deletionHandled = 1 WHERE id IN (:ids)")
    suspend fun markWaterIntakeDeletionsHandled(ids: List<String>)

    @Query("SELECT * FROM body_metric ORDER BY localDate DESC LIMIT 30")
    fun observeWeights(): Flow<List<BodyMetric>>

    @Query("SELECT * FROM body_metric ORDER BY localDate DESC")
    suspend fun allWeightsForHealthConnect(): List<BodyMetric>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWeight(metric: BodyMetric)
}

class DietRepository(private val dao: DietDao) {
    fun day(date: String) = dao.observeDay(date)
    fun recentMeals() = dao.observeRecent()
    fun loggedDaysSince(from: String) = dao.observeLoggedDays(from)
    fun water(date: String) = dao.observeWater(date)
    fun weights() = dao.observeWeights()
    suspend fun weightsForHealthConnect() = dao.allWeightsForHealthConnect()

    suspend fun add(slot: String, name: String, kcal: Double, protein: Double) {
        require(validMealEntry(slot, name, kcal, protein)) {
            "Enter a food name, valid meal slot, and finite non-negative nutrition values."
        }
        dao.insert(MealItem(localDate = today(), slot = slot, name = name, kcal = kcal, protein = protein))
    }
    suspend fun remove(item: MealItem) = dao.delete(item)
    suspend fun addWater(deltaMl: Int) {
        require(deltaMl in -MAX_WATER_CHANGE_ML..MAX_WATER_CHANGE_ML) {
            "A single water adjustment must be between -10,000 and 10,000 ml."
        }
        if (deltaMl == 0) return
        dao.applyWaterChange(today(), deltaMl)
    }
    suspend fun waterIntakes() = dao.recentActiveWaterIntakes()
    suspend fun deletedWaterIntakes() = dao.pendingWaterIntakeDeletions()
    suspend fun markWaterIntakeDeletionsHandled(ids: List<String>) =
        dao.markWaterIntakeDeletionsHandled(ids)
    suspend fun setWeight(kg: Double) {
        require(validWeight(kg)) { "Enter a finite weight greater than 0 and no more than 1,000 kg." }
        dao.upsertWeight(BodyMetric(today(), kg, System.currentTimeMillis()))
    }
}

val SLOTS = listOf("Breakfast", "Lunch", "Dinner", "Snack")
private const val MAX_MEAL_KCAL = 100_000.0
private const val MAX_PROTEIN_GRAMS = 10_000.0
private const val MAX_WEIGHT_KG = 1_000.0
private const val MAX_WATER_CHANGE_ML = 10_000

private fun validMealEntry(slot: String, name: String, kcal: Double, protein: Double): Boolean =
    slot in SLOTS && name.trim().length in 1..120 &&
        kcal.isFinite() && kcal in 0.0..MAX_MEAL_KCAL &&
        protein.isFinite() && protein in 0.0..MAX_PROTEIN_GRAMS

private fun validWeight(kg: Double): Boolean = kg.isFinite() && kg > 0.0 && kg <= MAX_WEIGHT_KG

class DietViewModel(private val repository: DietRepository) : ViewModel() {
    val items: StateFlow<List<MealItem>> = repository.day(today())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val water: StateFlow<WaterLog?> = repository.water(today())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val weights: StateFlow<List<BodyMetric>> = repository.weights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(slot: String, name: String, kcal: String, protein: String): Boolean {
        val k = kcal.toDoubleOrNull() ?: return false
        val p = if (protein.isBlank()) 0.0 else protein.toDoubleOrNull() ?: return false
        if (!validMealEntry(slot, name, k, p)) return false
        viewModelScope.launch { repository.add(slot, name.trim(), k, p) }
        return true
    }
    fun delete(item: MealItem) = viewModelScope.launch { repository.remove(item) }
    fun addWater(ml: Int) = viewModelScope.launch { repository.addWater(ml) }
    fun setWeight(kg: String): Boolean {
        val value = kg.toDoubleOrNull() ?: return false
        if (!validWeight(value)) return false
        viewModelScope.launch { repository.setWeight(value) }
        return true
    }
}

@Composable
fun DietScreen(container: AppContainer) {
    val vm: DietViewModel = viewModel(factory = vmFactory { DietViewModel(container.dietRepository) })
    val items by vm.items.collectAsState()
    val water by vm.water.collectAsState()
    val weights by vm.weights.collectAsState()

    var slot by remember { mutableStateOf(SLOTS.first()) }
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var mealError by remember { mutableStateOf<String?>(null) }
    var weightError by remember { mutableStateOf<String?>(null) }

    val kcalTargetInt by container.settingsRepository.kcalTarget.collectAsState(initial = 2200)
    val waterTarget by container.settingsRepository.waterTarget.collectAsState(initial = 3000)
    val kcalTarget = kcalTargetInt.toDouble()
    val weightTrend = remember(weights) {
        weights.sortedBy { it.localDate }.map { Point(it.localDate.takeLast(5), it.weight) }
    }
    val weightChange = remember(weights) {
        if (weights.size < 2) "—" else {
            val sorted = weights.sortedBy { it.localDate }
            val delta = sorted.last().weight - sorted.first().weight
            (if (delta >= 0) "+" else "") + "%.1f kg".format(delta)
        }
    }
    val totalKcal = items.sumOf { it.kcal }
    val totalProtein = items.sumOf { it.protein }
    val ml = water?.ml ?: 0

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Column(Modifier.fillMaxWidth()) {
        ModuleTitle("Diet", "Today")

        Text("${totalKcal.toInt()} / ${kcalTarget.toInt()} kcal", style = MonoNumber)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { (totalKcal / kcalTarget).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text("Protein ${totalProtein.toInt()} g", style = MonoNumber,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Water $ml / $waterTarget ml", style = MonoNumber, modifier = Modifier.weight(1f))
            Button(onClick = { vm.addWater(250) }) { Text("+250") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.addWater(-250) }) { Text("-250") }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SLOTS.forEach { s ->
                FilterChip(selected = slot == s, onClick = { slot = s; mealError = null }, label = { Text(s) })
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it; mealError = null },
            label = { Text("Food") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row {
            OutlinedTextField(
                value = kcal, onValueChange = { kcal = it; mealError = null },
                label = { Text("kcal") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = protein, onValueChange = { protein = it; mealError = null },
                label = { Text("Protein g") }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            if (vm.add(slot, name, kcal, protein)) {
                name = ""; kcal = ""; protein = ""; mealError = null
            } else mealError = "Enter a food name and valid non-negative calories and protein."
        }) { Text("Log food") }
        mealError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        DietBoost(container, slot, items, weights, kcalTarget) { vm.addWater(it) }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = weight, onValueChange = { weight = it; weightError = null },
                label = { Text("Weight kg") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                if (vm.setWeight(weight)) {
                    weight = ""; weightError = null
                } else weightError = "Enter a finite weight greater than 0 and no more than 1,000 kg."
            }) { Text("Save") }
        }
        weightError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        weights.firstOrNull()?.let {
            Text("Last logged: ${it.weight} kg on ${it.localDate}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (weights.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Weight", style = MaterialTheme.typography.titleMedium)
                    LineChart(points = weightTrend, accent = Spine.Diet)
                    StatRow(
                        listOf(
                            "latest" to "%.1f kg".format(weights.first().weight),
                            "change" to weightChange,
                            "logged" to weights.size.toString()
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        AiPanel(
            settings = container.settingsRepository,
            label = "Nutrition help",
            contextPrompt = "Today the user logged: " +
                items.joinToString("; ") { "${it.name} ${it.kcal.toInt()} kcal" } +
                ". Total ${totalKcal.toInt()} kcal, ${totalProtein.toInt()} g protein, water $ml ml.",
            starterQuestion = "What should I eat for dinner to hit my protein target?",
            system = "You are a practical nutrition coach who knows Pakistani and South Asian food well. " +
                "Give specific, realistic suggestions with rough macros. Recommend seeing a doctor or " +
                "dietitian for medical conditions rather than guessing."
        )
        Spacer(Modifier.height(12.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)

        if (items.isEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                "Nothing logged today. Add a meal above, or tell the Curator what you ate.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
            }
        }

            items(items, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        Text(item.slot, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${item.kcal.toInt()} kcal", style = MonoNumber)
                    IconButton(onClick = { vm.delete(item) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove food")
                    }
                }
                Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
    }
}
