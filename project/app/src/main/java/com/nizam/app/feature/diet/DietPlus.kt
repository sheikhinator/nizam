package com.nizam.app.feature.diet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Diet, overhauled: one-tap foods (Pakistani staples plus whatever you eat most), "describe your meal"
 * and Atlas estimates each item, what's left of today's calories and protein, quick water sizes,
 * and a weight trend smoothed over a week with the date you'll reach your goal.
 */
object DietPlus {
    /** Typical single servings: name, kcal, protein g. Estimates for quick logging. */
    val STAPLES = listOf(
        Triple("Roti (1)", 120.0, 4.0), Triple("Paratha (1)", 300.0, 5.0), Triple("Naan (1)", 260.0, 8.0),
        Triple("Rice, 1 cup", 205.0, 4.0), Triple("Chicken biryani, plate", 650.0, 28.0), Triple("Daal, bowl", 230.0, 12.0),
        Triple("Chicken karahi, bowl", 450.0, 35.0), Triple("Chicken salan, bowl", 350.0, 28.0), Triple("Aloo bhujia, bowl", 220.0, 4.0),
        Triple("Chana, bowl", 270.0, 12.0), Triple("Egg, boiled", 78.0, 6.0), Triple("Omelette (2 eggs)", 190.0, 13.0),
        Triple("Chai with milk & sugar", 90.0, 2.0), Triple("Milk, glass", 150.0, 8.0), Triple("Lassi, glass", 220.0, 8.0),
        Triple("Yogurt, bowl", 150.0, 9.0), Triple("Banana", 105.0, 1.0), Triple("Apple", 95.0, 0.5), Triple("Dates (3)", 200.0, 2.0),
        Triple("Samosa (1)", 260.0, 4.0), Triple("Pakora, plate", 350.0, 6.0), Triple("Chicken breast, 150 g", 250.0, 46.0),
        Triple("Shami kabab (1)", 150.0, 9.0), Triple("Seekh kabab (1)", 180.0, 12.0), Triple("Nihari, bowl", 550.0, 32.0),
        Triple("Haleem, bowl", 400.0, 25.0), Triple("Zinger burger", 550.0, 25.0), Triple("Pizza slice", 285.0, 12.0),
        Triple("Fries, medium", 365.0, 4.0), Triple("Soft drink, can", 140.0, 0.0), Triple("Whey scoop", 120.0, 24.0), Triple("Peanut butter, tbsp", 95.0, 4.0))

    private fun p(c: android.content.Context) = c.getSharedPreferences("diet_plus", 0)
    fun goal(c: android.content.Context): Double? = p(c).getFloat("goal", 0f).toDouble().takeIf { it > 0 }
    fun setGoal(c: android.content.Context, kg: Double) = p(c).edit().putFloat("goal", kg.toFloat()).apply()

    class Est(val name: String, val kcal: Double, val protein: Double)

    suspend fun estimate(container: AppContainer, meal: String): List<Est> {
        val raw = Ai.generate(container.settingsRepository,
            "Estimate calories and protein for each item in this meal, using typical Pakistani home or restaurant portions unless a size is given. " +
                "Output only JSON: {\"items\":[{\"name\":\"short name with portion\",\"kcal\":0,\"protein\":0}]}.\nMeal: $meal",
            system = "You are a careful nutrition estimator. Output only JSON.", maxOutputTokens = 600)
        val o = com.nizam.app.feature.nx.EngineX.json(raw) ?: return emptyList()
        val a = o.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { x ->
            val k = x.optDouble("kcal", -1.0); val pr = x.optDouble("protein", 0.0)
            if (x.optString("name").isBlank() || k < 0 || k > 5000) null else Est(x.optString("name").take(60), k, if (pr.isNaN()) 0.0 else pr.coerceIn(0.0, 300.0))
        } }.take(10)
    }
}

@Composable
private fun Pill(text: String, on: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(text, Modifier.clip(RoundedCornerShape(50)).background(if (on) cs.primary.copy(alpha = 0.16f) else cs.surfaceVariant.copy(alpha = 0.6f))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge, color = if (on) cs.primary else cs.onSurfaceVariant)
}

@Composable
fun DietBoost(container: AppContainer, slot: String, items: List<MealItem>, weights: List<BodyMetric>, kcalTarget: Double, addWater: (Int) -> Unit) {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val recentFlow = remember { container.dietRepository.recentMeals() }
    val recent by recentFlow.collectAsState(initial = emptyList())
    val favourites = remember(recent) {
        recent.groupBy { it.name.trim().lowercase() }.entries.sortedByDescending { it.value.size }.take(10).map { it.value.first() }
    }
    fun log(name: String, kcal: Double, protein: Double) {
        scope.launch { runCatching { container.dietRepository.add(slot, name, kcal, protein) }
            .onSuccess { Feedback.show("$name · ${kcal.toInt()} kcal → $slot") }.onFailure { Feedback.show(it.message ?: "Couldn't log") } }
    }
    val kcal = items.sumOf { it.kcal }; val protein = items.sumOf { it.protein }
    val latestWeight = weights.maxByOrNull { it.localDate }?.weight
    val proteinTarget = (latestWeight?.let { it * 1.6 } ?: 100.0)

    com.nizam.app.ui.design.Group(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("${(kcalTarget - kcal).toInt()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = if (kcal > kcalTarget) cs.error else cs.onSurface); Text("kcal left", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
            Column(horizontalAlignment = Alignment.End) { Text("${(proteinTarget - protein).toInt().coerceAtLeast(0)} g", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("protein to go (of ${proteinTarget.toInt()} g)", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Water", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Pill("Glass 250") { addWater(250) }; Pill("Bottle 500") { addWater(500) }; Pill("1 L") { addWater(1000) }
        }

        if (favourites.isNotEmpty()) {
            Text("Your usuals → $slot", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                favourites.forEach { m -> Pill("${m.name} · ${m.kcal.toInt()}") { log(m.name, m.kcal, m.protein) } }
            }
        }
        Text("Quick add → $slot", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DietPlus.STAPLES.forEach { (n, k, pr) -> Pill("$n · ${k.toInt()}") { log(n, k, pr) } }
        }
        Text("Typical servings; your usuals learn your real portions.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)

        // describe a meal
        var meal by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val ests = remember { mutableStateListOf<DietPlus.Est>() }
        val picked = remember { mutableStateListOf<Boolean>() }
        Text("Describe your meal", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(meal, { meal = it }, Modifier.weight(1f), placeholder = { Text("2 rotis, chicken karahi, raita") }, maxLines = 3,
                trailingIcon = { com.nizam.app.feature.nx.MicButton({ heard -> meal = heard }) })
            TextButton(onClick = {
                busy = true
                scope.launch {
                    runCatching { DietPlus.estimate(container, meal) }.onSuccess { l -> ests.clear(); picked.clear(); ests.addAll(l); picked.addAll(l.map { true })
                        if (l.isEmpty()) Feedback.show("Couldn't estimate that") }.onFailure { Feedback.show(it.message ?: "Couldn't estimate") }
                    busy = false
                }
            }, enabled = meal.isNotBlank() && !busy) { Text(if (busy) "…" else "Estimate") }
        }
        if (ests.isNotEmpty()) {
            ests.forEachIndexed { i, e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(picked.getOrElse(i) { false }, { picked[i] = it })
                    Text(e.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${e.kcal.toInt()} kcal · ${e.protein.toInt()} g", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
            TextButton(onClick = {
                ests.forEachIndexed { i, e -> if (picked.getOrElse(i) { false }) log(e.name, e.kcal, e.protein) }
                ests.clear(); picked.clear(); meal = ""
            }) { Text("Log ${picked.count { it }} to $slot") }
            Text("Estimates, not lab values.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }

        // weight trend and goal
        val sorted = remember(weights) { weights.sortedBy { it.localDate } }
        if (sorted.size >= 2) {
            val last = LocalDate.parse(sorted.last().localDate)
            val week = sorted.filter { it.localDate >= last.minusDays(6).toString() }.map { it.weight }.average()
            val prevW = sorted.filter { it.localDate >= last.minusDays(13).toString() && it.localDate < last.minusDays(6).toString() }.map { it.weight }
            val rate = if (prevW.isNotEmpty()) week - prevW.average() else null
            var goalText by remember { mutableStateOf(DietPlus.goal(c)?.let { "%.1f".format(it) } ?: "") }
            Text("Weight", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Text("7-day average ${"%.1f".format(week)} kg" + (rate?.let { r -> " · ${if (r >= 0) "+" else ""}${"%.2f".format(r)} kg/week" } ?: ""),
                style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(goalText, { goalText = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f), singleLine = true, label = { Text("Goal kg") })
                TextButton(onClick = { goalText.toDoubleOrNull()?.let { DietPlus.setGoal(c, it); Feedback.show("Goal saved") } }) { Text("Save") }
            }
            val goal = goalText.toDoubleOrNull()
            if (goal != null && rate != null && kotlin.math.abs(rate) > 0.02) {
                val weeks = (goal - week) / rate
                Text(if (weeks > 0 && weeks < 520) "At this pace you'll reach $goal kg around ${LocalDate.now().plusDays((weeks * 7).toLong()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))}"
                    else "Your current trend is moving away from the goal", style = MaterialTheme.typography.bodySmall,
                    color = if (weeks > 0) cs.primary else cs.error)
            }
        }
    }
}
