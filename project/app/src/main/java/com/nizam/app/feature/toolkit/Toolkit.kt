package com.nizam.app.feature.toolkit

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.fmtAmount
import com.nizam.app.ui.theme.MonoNumber
import java.security.SecureRandom
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.roundToInt

private data class Tool(val emoji: String, val name: String)

private val TOOLS = listOf(
    Tool("🧮", "Percentages"),
    Tool("💳", "Loan / EMI"),
    Tool("📐", "Units"),
    Tool("⚖️", "Discounts"),
    Tool("📅", "Dates"),
    Tool("🔐", "Passwords"),
    Tool("🎲", "Random"),
    Tool("🧾", "Bill split"),
    Tool("📊", "Averages"),
    Tool("🕌", "Zakat")
)

@Composable
fun ToolkitScreen(container: AppContainer) {
    var selected by remember { mutableStateOf(TOOLS.first().name) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
    ) {
        ModuleTitle("Toolkit", "Small things you'd otherwise open a browser for")

        ChipRow(
            options = TOOLS.map { it.name },
            selected = selected,
            onSelect = { selected = it },
            labels = { name -> TOOLS.first { it.name == name }.let { "${it.emoji} ${it.name}" } }
        )

        Spacer(Modifier.height(18.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(18.dp))

        when (selected) {
            "Percentages" -> Percentages()
            "Loan / EMI" -> Loan()
            "Units" -> Units()
            "Discounts" -> Discount()
            "Dates" -> DateDiff()
            "Passwords" -> PasswordGen()
            "Random" -> RandomPicker()
            "Bill split" -> BillSplit()
            "Averages" -> Averages()
            else -> Zakat()
        }
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun ToolResult(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = com.nizam.app.ui.theme.MonoFamily
            ))
        }
    }
}

@Composable
private fun Num(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true, modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun Percentages() {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val x = a.toDoubleOrNull(); val y = b.toDoubleOrNull()
    Num(a, "Value", { a = it })
    Num(b, "Percent", { b = it })
    if (x != null && y != null) {
        ToolResult("$y% of $x", fmtAmount(x * y / 100))
        ToolResult("$x increased by $y%", fmtAmount(x * (1 + y / 100)))
        ToolResult("$x decreased by $y%", fmtAmount(x * (1 - y / 100)))
    }
}

@Composable
private fun Loan() {
    var principal by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("") }
    var years by remember { mutableStateOf("") }
    val p = principal.toDoubleOrNull(); val r = rate.toDoubleOrNull(); val n = years.toDoubleOrNull()
    Num(principal, "Amount (PKR)", { principal = it })
    Num(rate, "Annual interest %", { rate = it })
    Num(years, "Years", { years = it })
    if (p != null && r != null && n != null && n > 0) {
        val monthlyRate = r / 100 / 12
        val months = n * 12
        val emi = if (monthlyRate == 0.0) p / months
        else p * monthlyRate * (1 + monthlyRate).pow(months) / ((1 + monthlyRate).pow(months) - 1)
        ToolResult("Monthly payment", fmtAmount(emi) + " PKR")
        ToolResult("Total repaid", fmtAmount(emi * months) + " PKR")
        ToolResult("Total interest", fmtAmount(emi * months - p) + " PKR")
    }
}

@Composable
private fun Units() {
    var value by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("Length") }
    val v = value.toDoubleOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Length", "Weight", "Temp").forEach {
            FilterChip(selected = kind == it, onClick = { kind = it }, label = { Text(it) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Num(value, "Value", { value = it })
    if (v != null) {
        when (kind) {
            "Length" -> {
                ToolResult("$v km in miles", "%.3f".format(v * 0.621371))
                ToolResult("$v m in feet", "%.3f".format(v * 3.28084))
                ToolResult("$v cm in inches", "%.3f".format(v * 0.393701))
            }
            "Weight" -> {
                ToolResult("$v kg in pounds", "%.3f".format(v * 2.20462))
                ToolResult("$v g in ounces", "%.3f".format(v * 0.035274))
                ToolResult("$v kg in tola", "%.2f".format(v * 85.735))
            }
            else -> {
                ToolResult("$v °C in °F", "%.1f".format(v * 9 / 5 + 32))
                ToolResult("$v °F in °C", "%.1f".format((v - 32) * 5 / 9))
            }
        }
    }
}

@Composable
private fun Discount() {
    var price by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var pct by remember { mutableStateOf("") }
    val p = price.toDoubleOrNull(); val c = cost.toDoubleOrNull(); val d = pct.toDoubleOrNull()
    Num(price, "Selling price", { price = it })
    Num(cost, "Cost price", { cost = it })
    Num(pct, "Discount %", { pct = it })
    if (p != null && d != null) {
        ToolResult("After $d% off", fmtAmount(p * (1 - d / 100)))
        ToolResult("You save", fmtAmount(p * d / 100))
    }
    if (p != null && c != null && p > 0) {
        ToolResult("Margin", "%.1f%%".format((p - c) / p * 100))
        ToolResult("Markup", "%.1f%%".format(if (c > 0) (p - c) / c * 100 else 0.0))
    }
}

@Composable
private fun DateDiff() {
    var from by remember { mutableStateOf(LocalDate.now().toString()) }
    var to by remember { mutableStateOf(LocalDate.now().plusDays(30).toString()) }
    Num(from, "From (YYYY-MM-DD)", { from = it })
    Num(to, "To (YYYY-MM-DD)", { to = it })

    val parsed = remember(from, to) {
        runCatching {
            val a = LocalDate.parse(from)
            val b = LocalDate.parse(to)
            Triple(
                ChronoUnit.DAYS.between(a, b),
                ChronoUnit.MONTHS.between(a, b),
                ChronoUnit.YEARS.between(a, b)
            )
        }.getOrNull()
    }

    if (parsed != null) {
        val (days, months, years) = parsed
        ToolResult("Days between", days.toString())
        ToolResult("Weeks", "%.1f".format(days / 7.0))
        ToolResult("Months", months.toString())
        ToolResult("Years", years.toString())
    } else {
        Text(
            "Use the format YYYY-MM-DD.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PasswordGen() {
    var length by remember { mutableStateOf("16") }
    var symbols by remember { mutableStateOf(true) }
    var generated by remember { mutableStateOf("") }
    Num(length, "Length", { length = it })
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = symbols, onClick = { symbols = !symbols }, label = { Text("Symbols") })
    }
    Spacer(Modifier.height(10.dp))
    Button(onClick = {
        val base = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val pool = base + if (symbols) "!@#\$%^&*-_=+?" else ""
        val random = SecureRandom()
        generated = (1..(length.toIntOrNull() ?: 16).coerceIn(6, 64))
            .map { pool[random.nextInt(pool.length)] }.joinToString("")
    }) { Text("Generate") }
    if (generated.isNotBlank()) ToolResult("Password", generated)
}

@Composable
private fun RandomPicker() {
    var options by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf("") }
    OutlinedTextField(
        value = options, onValueChange = { options = it },
        label = { Text("Options, one per line") },
        modifier = Modifier.fillMaxWidth().height(140.dp)
    )
    Spacer(Modifier.height(10.dp))
    Button(onClick = {
        val list = options.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        picked = if (list.isEmpty()) "" else list[SecureRandom().nextInt(list.size)]
    }) { Text("Pick one") }
    if (picked.isNotBlank()) ToolResult("Decision", picked)
}

@Composable
private fun BillSplit() {
    var total by remember { mutableStateOf("") }
    var people by remember { mutableStateOf("") }
    var tip by remember { mutableStateOf("0") }
    val t = total.toDoubleOrNull(); val n = people.toIntOrNull(); val p = tip.toDoubleOrNull() ?: 0.0
    Num(total, "Bill total", { total = it })
    Num(people, "People", { people = it })
    Num(tip, "Tip %", { tip = it })
    if (t != null && n != null && n > 0) {
        val withTip = t * (1 + p / 100)
        ToolResult("Total with tip", fmtAmount(withTip))
        ToolResult("Each person pays", fmtAmount(withTip / n))
    }
}

@Composable
private fun Averages() {
    var numbers by remember { mutableStateOf("") }
    OutlinedTextField(
        value = numbers, onValueChange = { numbers = it },
        label = { Text("Numbers, separated by spaces or commas") },
        modifier = Modifier.fillMaxWidth().height(120.dp)
    )
    val list = numbers.split(",", " ", "\n").mapNotNull { it.trim().toDoubleOrNull() }
    if (list.isNotEmpty()) {
        val sorted = list.sorted()
        ToolResult("Count", list.size.toString())
        ToolResult("Sum", fmtAmount(list.sum()))
        ToolResult("Mean", "%.2f".format(list.average()))
        ToolResult("Median", "%.2f".format(
            if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        ))
        ToolResult("Range", "${sorted.first()} to ${sorted.last()}")
    }
}

@Composable
private fun Zakat() {
    var cash by remember { mutableStateOf("") }
    var gold by remember { mutableStateOf("") }
    var silver by remember { mutableStateOf("") }
    var debts by remember { mutableStateOf("") }
    var nisab by remember { mutableStateOf("") }
    Num(cash, "Cash and bank (PKR)", { cash = it })
    Num(gold, "Gold value (PKR)", { gold = it })
    Num(silver, "Silver value (PKR)", { silver = it })
    Num(debts, "Debts you owe (PKR)", { debts = it })
    Num(nisab, "Nisab threshold today (PKR)", { nisab = it })
    val assets = listOf(cash, gold, silver).sumOf { it.toDoubleOrNull() ?: 0.0 }
    val owed = debts.toDoubleOrNull() ?: 0.0
    val net = assets - owed
    val threshold = nisab.toDoubleOrNull()
    if (assets > 0) {
        ToolResult("Net zakatable wealth", fmtAmount(net) + " PKR")
        ToolResult("Zakat at 2.5%", fmtAmount(net * 0.025) + " PKR")
        if (threshold != null) {
            ToolResult(
                "Above nisab?",
                if (net >= threshold) "Yes — zakat is due" else "No — below the threshold"
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Arithmetic only. Nisab changes with gold and silver prices, and scholars differ on what " +
            "counts as zakatable and which nisab applies. Check the ruling with someone qualified.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
