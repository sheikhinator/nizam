package com.nizam.app.feature.finance

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.nizam.app.AppContainer
import com.nizam.app.core.InlineAddRow
import com.nizam.app.core.AiPanel
import com.nizam.app.core.BarChart
import com.nizam.app.core.Point
import com.nizam.app.core.StatRow
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.fmtAmount
import com.nizam.app.core.prettyDate
import com.nizam.app.core.today
import com.nizam.app.core.vmFactory
import com.nizam.app.ui.theme.Spine
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@Entity(tableName = "txn")
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val kind: String,               // INCOME | EXPENSE
    val category: String,
    val account: String = "Cash",
    val localDate: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

private val MAX_FINANCE_AMOUNT = BigDecimal("999999999999.99")
private val FINANCE_AMOUNT_INPUT = Regex("""^(?:\d+(?:\.\d{0,2})?|\.\d{1,2})$""")

private fun parseFinanceAmount(raw: String): Double? {
    val input = raw.trim()
    if (!FINANCE_AMOUNT_INPUT.matches(input)) return null
    val amount = runCatching { BigDecimal(input).setScale(2, RoundingMode.UNNECESSARY) }.getOrNull() ?: return null
    if (amount.signum() <= 0 || amount > MAX_FINANCE_AMOUNT) return null
    return amount.toDouble().takeIf { it.isFinite() }
}

private fun normalizedFinanceDate(raw: String): String? =
    if (raw.isBlank()) today()
    else runCatching { LocalDate.parse(raw.trim()).toString() }.getOrNull()

private fun validTransactionKind(kind: String) = kind == "INCOME" || kind == "EXPENSE"

private fun requireFinanceAmount(amount: Double, allowZero: Boolean = false) {
    require(amount.isFinite() && (if (allowZero) amount >= 0 else amount > 0) &&
        amount <= MAX_FINANCE_AMOUNT.toDouble()) {
        if (allowZero) "Enter a finite non-negative amount." else "Enter a finite positive amount."
    }
}

private fun validateTransaction(txn: Txn): Txn {
    requireFinanceAmount(txn.amount)
    require(validTransactionKind(txn.kind)) { "Choose income or expense." }
    val date = runCatching { LocalDate.parse(txn.localDate.trim()).toString() }.getOrNull()
        ?: throw IllegalArgumentException("Use a valid date in YYYY-MM-DD format.")
    return txn.copy(localDate = date)
}

@Entity(tableName = "budget")
data class Budget(
    @PrimaryKey val category: String,
    val monthlyAmount: Double
)

@Dao
interface FinanceDao {
    @Query("SELECT * FROM txn ORDER BY localDate DESC, id DESC LIMIT 300")
    fun observeRecent(): Flow<List<Txn>>

    @Query("SELECT * FROM txn WHERE localDate >= :from ORDER BY localDate DESC")
    fun observeSince(from: String): Flow<List<Txn>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(txn: Txn)

    @Delete
    suspend fun delete(txn: Txn)

    @androidx.room.Update
    suspend fun update(txn: Txn)

    @Query("SELECT DISTINCT category FROM txn ORDER BY category")
    fun observeCategories(): Flow<List<String>>

    @Query("SELECT * FROM budget")
    fun observeBudgets(): Flow<List<Budget>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudget(budget: Budget)
}

class FinanceRepository(private val dao: FinanceDao) {
    fun recent() = dao.observeRecent()
    fun since(date: String) = dao.observeSince(date)
    fun budgets() = dao.observeBudgets()
    suspend fun add(amount: Double, kind: String, category: String, note: String) {
        requireFinanceAmount(amount)
        require(validTransactionKind(kind)) { "Choose income or expense." }
        dao.insert(
            Txn(
                amount = amount,
                kind = kind,
                category = category.ifBlank { "Uncategorised" },
                localDate = today(),
                note = note
            )
        )
    }
    suspend fun remove(txn: Txn) = dao.delete(txn)
    suspend fun restore(txn: Txn) = dao.insert(validateTransaction(txn))
    suspend fun edit(txn: Txn) = dao.update(validateTransaction(txn))
    fun categories() = dao.observeCategories()

    /** Logs on a given day rather than always today, so past spending can be entered honestly. */
    suspend fun addOn(date: String, amount: Double, kind: String, category: String, note: String) {
        requireFinanceAmount(amount)
        require(validTransactionKind(kind)) { "Choose income or expense." }
        val validDate = normalizedFinanceDate(date)
            ?: throw IllegalArgumentException("Use a valid date in YYYY-MM-DD format.")
        dao.insert(
            Txn(
                amount = amount, kind = kind,
                category = category.ifBlank { "Uncategorised" },
                localDate = validDate, note = note
            )
        )
    }
    suspend fun setBudget(category: String, amount: Double) {
        requireFinanceAmount(amount, allowZero = true)
        dao.upsertBudget(Budget(category, amount))
    }
}

val DEFAULT_CATEGORIES = listOf("Food", "Transport", "Bills", "Groceries", "Shopping", "Health", "Family", "Other")

class FinanceViewModel(private val repository: FinanceRepository) : ViewModel() {

    private val monthStart = LocalDate.now().withDayOfMonth(1).toString()

    val recent: StateFlow<List<Txn>> = repository.recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val thisMonth: StateFlow<List<Txn>> = repository.since(monthStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(amount: String, kind: String, category: String, note: String) {
        val value = parseFinanceAmount(amount) ?: return
        if (!validTransactionKind(kind)) return
        viewModelScope.launch { repository.add(value, kind, category, note) }
    }

    val categories: StateFlow<List<String>> = repository.categories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addOn(date: String, amount: String, kind: String, category: String, note: String): Boolean {
        val value = parseFinanceAmount(amount) ?: return false
        if (!validTransactionKind(kind)) return false
        val validDate = normalizedFinanceDate(date) ?: return false
        viewModelScope.launch { repository.addOn(validDate, value, kind, category, note) }
        return true
    }

    fun delete(txn: Txn) {
        viewModelScope.launch { repository.remove(txn) }
        com.nizam.app.ui.components.Feedback.undo("Entry deleted") { viewModelScope.launch { repository.restore(txn) } }
    }
}

@Composable
fun FinanceScreen(container: AppContainer) {
    val vm: FinanceViewModel = viewModel(factory = vmFactory { FinanceViewModel(container.financeRepository) })
    val recent by vm.recent.collectAsState()
    val month by vm.thisMonth.collectAsState()

    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("EXPENSE") }
    var category by remember { mutableStateOf(DEFAULT_CATEGORIES.first()) }
    var customCategory by remember { mutableStateOf("") }
    var backdate by remember { mutableStateOf("") }
    var entryError by remember { mutableStateOf<String?>(null) }
    val usedCategories by vm.categories.collectAsState()
    val allCategories = remember(usedCategories) {
        (DEFAULT_CATEGORIES + usedCategories).distinct()
    }

    // one pass over the month, recomputed only when the month's records change (not on every keystroke)
    val roll = remember(month) {
        var income = 0.0; var spent = 0.0; var todaySpend = 0.0
        val t = today(); val perDay = HashMap<String, Double>(); val expenses = ArrayList<Double>()
        month.forEach { x -> when (x.kind) {
            "INCOME" -> income += x.amount
            "EXPENSE" -> { spent += x.amount; expenses += x.amount; perDay[x.localDate] = (perDay[x.localDate] ?: 0.0) + x.amount; if (x.localDate == t) todaySpend += x.amount }
        } }
        expenses.sort()
        // A single hotel bill is not a daily rate. Anything over 3x the median transaction is treated
        // as a one-off: counted in the total, but kept out of the burn rate used for projection.
        val median = if (expenses.isEmpty()) 0.0 else expenses[expenses.size / 2]
        val oneOffs = if (median > 0) expenses.filter { it > median * 3 } else emptyList()
        listOf(income, spent, todaySpend, oneOffs.sum(), oneOffs.size.toDouble()) to perDay
    }
    val (income, spent, todaySpend, oneOffTotal, oneOffCountD) = roll.first
    val oneOffCount = oneOffCountD.toInt()
    val daysElapsed = java.time.LocalDate.now().dayOfMonth
    val daysInMonth = java.time.LocalDate.now().lengthOfMonth()
    val routine = spent - oneOffTotal
    val dailyAverage = if (daysElapsed > 0) routine / daysElapsed else 0.0
    val projected = dailyAverage * daysInMonth + oneOffTotal
    val dailySpend = remember(roll) {
        (1..daysElapsed).map { day ->
            val date = java.time.LocalDate.now().withDayOfMonth(day).toString()
            Point(day.toString(), roll.second[date] ?: 0.0)
        }
    }

    val byCategory = month.filter { it.kind == "EXPENSE" }
        .groupBy { it.category }
        .map { it.key to it.value.sumOf { t -> t.amount } }
        .sortedByDescending { it.second }
        .take(5)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Column(Modifier.fillMaxWidth()) {
        ModuleTitle("Finance", "This month")

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                "In" to income,
                "Out" to spent,
                "Net" to (income - spent)
            ).forEach { (label, value) ->
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(fmtAmount(value), style = MonoNumber)
                        Text(label, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("DAILY SPEND THIS MONTH", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                BarChart(points = dailySpend, accent = Spine.Finance)
                StatRow(
                    listOf(
                        "today" to fmtAmount(todaySpend),
                        "daily avg" to fmtAmount(dailyAverage),
                        "projected" to fmtAmount(projected)
                    )
                )
                if (oneOffCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$oneOffCount one-off expense${if (oneOffCount > 1) "s" else ""} " +
                            "(${fmtAmount(oneOffTotal)}) excluded from the daily rate.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (projected > income && income > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "At this rate you'll spend ${fmtAmount(projected - income)} more than you " +
                            "earned this month.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = kind == "EXPENSE", onClick = { kind = "EXPENSE"; entryError = null }, label = { Text("Expense") })
            FilterChip(selected = kind == "INCOME", onClick = { kind = "INCOME"; entryError = null }, label = { Text("Income") })
        }

        Spacer(Modifier.height(8.dp))
        InlineAddRow(
            value = amount,
            label = "Amount",
            numeric = true,
            onValueChange = { amount = it; entryError = null },
            onSubmit = {
                val chosen = customCategory.trim().ifBlank { category }
                val error = when {
                    parseFinanceAmount(amount) == null ->
                        "Enter a positive amount with no more than two decimal places."
                    !validTransactionKind(kind) -> "Choose income or expense."
                    normalizedFinanceDate(backdate.trim()) == null -> "Enter a valid date in YYYY-MM-DD format."
                    else -> null
                }
                if (error != null) entryError = error
                else if (vm.addOn(backdate.trim(), amount, kind, chosen, note)) {
                    amount = ""; note = ""; customCategory = ""; backdate = ""; entryError = null
                } else entryError = "This entry could not be validated. Check the amount and date."
            }
        )
        entryError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        ChipRow(
            options = allCategories,
            selected = category,
            onSelect = { category = it }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = customCategory, onValueChange = { customCategory = it },
                label = { Text("New category") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = backdate, onValueChange = { backdate = it; entryError = null },
                label = { Text("Date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }

        if (byCategory.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Top categories", style = MaterialTheme.typography.titleMedium)
            val biggest = byCategory.firstOrNull()?.second ?: 1.0
            byCategory.forEach { (cat, total) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(cat, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            fmtAmount(total) + "  (" +
                                "%.0f%%".format(if (spent > 0) total / spent * 100 else 0.0) + ")",
                            style = MonoNumber
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(((total / biggest).toFloat()).coerceIn(0.02f, 1f))
                            .height(4.dp)
                            .background(Spine.Finance, MaterialTheme.shapes.small)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        AiPanel(
            settings = container.settingsRepository,
            label = "Money help",
            contextPrompt = "This month: income ${fmtAmount(income)}, spent ${fmtAmount(spent)}. " +
                "By category: " + byCategory.joinToString(", ") { "${it.first} ${fmtAmount(it.second)}" } +
                ". Currency is PKR.",
            starterQuestion = "Where am I overspending, and what would you cut first?",
            system = "You are a blunt, practical personal finance advisor familiar with Pakistani costs " +
                "and PKR. Work from the actual numbers. You are not a licensed advisor — say so if the " +
                "question moves into investment or tax advice."
        )
        Spacer(Modifier.height(14.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)

        if (recent.isEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                "No transactions yet. Log one above, or just say \"spent 500 on food\" to the Curator.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
            }
        }

            items(recent, key = { it.id }) { txn ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(txn.category, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            prettyDate(txn.localDate) + if (txn.note.isBlank()) "" else " · ${txn.note}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        (if (txn.kind == "EXPENSE") "-" else "+") + fmtAmount(txn.amount),
                        style = MonoNumber,
                        color = if (txn.kind == "EXPENSE") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { vm.delete(txn) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete transaction")
                    }
                }
                Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
    }
}
