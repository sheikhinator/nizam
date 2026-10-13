package com.nizam.app.feature.prayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import com.nizam.app.ui.design.OutlinedButton
import androidx.compose.foundation.layout.padding
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
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.nizam.app.AppContainer
import com.nizam.app.core.Heatmap
import com.nizam.app.core.StatRow
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.hourToClock
import com.nizam.app.core.today
import com.nizam.app.core.vmFactory
import com.nizam.app.ui.theme.Spine
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

// ---------- data ----------

@Entity(tableName = "prayer_day")
data class PrayerDay(
    @PrimaryKey val localDate: String,
    val fajr: String = "NOT_PRAYED",
    val dhuhr: String = "NOT_PRAYED",
    val asr: String = "NOT_PRAYED",
    val maghrib: String = "NOT_PRAYED",
    val isha: String = "NOT_PRAYED"
)

@Entity(tableName = "prayer_extra")
data class PrayerExtra(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localDate: String,
    val type: String,
    val count: Int
)

@Dao
interface PrayerDao {
    @Query("SELECT * FROM prayer_day WHERE localDate = :date")
    fun observeDay(date: String): Flow<PrayerDay?>

    @Query("SELECT * FROM prayer_day WHERE localDate >= :from ORDER BY localDate DESC")
    fun observeSince(from: String): Flow<List<PrayerDay>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: PrayerDay)

    @Query("SELECT * FROM prayer_extra WHERE localDate = :date")
    fun observeExtras(date: String): Flow<List<PrayerExtra>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExtra(extra: PrayerExtra)
}

class PrayerRepository(private val dao: PrayerDao) {
    fun day(date: String) = dao.observeDay(date)
    fun since(date: String) = dao.observeSince(date)
    suspend fun setStatus(date: String, prayer: String, status: String, current: PrayerDay?) {
        // Never write today's prayer into a row for another day (a screen left open past midnight)
        val base = current?.takeIf { it.localDate == date } ?: PrayerDay(localDate = date)
        val updated = when (prayer) {
            "fajr" -> base.copy(fajr = status)
            "dhuhr" -> base.copy(dhuhr = status)
            "asr" -> base.copy(asr = status)
            "maghrib" -> base.copy(maghrib = status)
            else -> base.copy(isha = status)
        }
        dao.upsert(updated)
        com.nizam.app.core.spine.Pulse.emit(
            "deen", "prayer", "$prayer · ${statusLabel(status)}",
            org.json.JSONObject().put("prayer", prayer).put("status", status).put("date", date)
        )
    }
}

// ---------- view model ----------

val PRAYER_STATUSES = listOf("NOT_PRAYED", "ON_TIME", "LATE", "JAMAAH", "QADA")

fun statusLabel(status: String): String = when (status) {
    "ON_TIME" -> "On time"
    "LATE" -> "Late"
    "JAMAAH" -> "Jamaah"
    "QADA" -> "Qada"
    else -> "Not yet"
}

class PrayerViewModel(
    private val repository: PrayerRepository,
    private val settings: com.nizam.app.data.prefs.SettingsRepository
) : ViewModel() {

    val date: String = today()

    val day: StateFlow<PrayerDay?> = repository.day(date)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val month: StateFlow<List<PrayerDay>> =
        repository.since(LocalDate.now().minusDays(29).toString())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val latitude: StateFlow<Double> = settings.latitude
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 31.5204)
    val longitude: StateFlow<Double> = settings.longitude
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 74.3587)
    val methodName: StateFlow<String> = settings.prayerMethod
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "KARACHI")
    val hanafi: StateFlow<Boolean> = settings.hanafiAsr
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun times(): PrayerTimes.Times = PrayerTimes.calculate(
        date = LocalDate.now(),
        latitude = latitude.value,
        longitude = longitude.value,
        method = runCatching { PrayerTimes.Method.valueOf(methodName.value) }
            .getOrElse { PrayerTimes.Method.KARACHI },
        hanafiAsr = hanafi.value
    )

    fun setStatus(prayer: String, status: String) {
        viewModelScope.launch { repository.setStatus(today(), prayer, status, day.value) }
    }

    fun setLocation(lat: Double, lng: Double) {
        viewModelScope.launch { settings.setLocation(lat, lng) }
    }

    fun setMethod(method: String) {
        viewModelScope.launch { settings.setPrayerMethod(method) }
    }

    fun setHanafi(value: Boolean) {
        viewModelScope.launch { settings.setHanafiAsr(value) }
    }
}

// ---------- screen ----------

@Composable
fun PrayerScreen(container: AppContainer) {
    val vm: PrayerViewModel = viewModel(
        factory = vmFactory { PrayerViewModel(container.prayerRepository, container.settingsRepository) }
    )
    val day by vm.day.collectAsState()
    val month by vm.month.collectAsState()
    val lat by vm.latitude.collectAsState()
    val lng by vm.longitude.collectAsState()
    val method by vm.methodName.collectAsState()
    val hanafi by vm.hanafi.collectAsState()

    val times = remember(lat, lng, method, hanafi) { vm.times() }

    val rows = listOf(
        Triple("fajr", "Fajr", times.fajr),
        Triple("dhuhr", "Dhuhr", times.dhuhr),
        Triple("asr", "Asr", times.asr),
        Triple("maghrib", "Maghrib", times.maghrib),
        Triple("isha", "Isha", times.isha)
    )

    fun statusOf(key: String): String = when (key) {
        "fajr" -> day?.fajr
        "dhuhr" -> day?.dhuhr
        "asr" -> day?.asr
        "maghrib" -> day?.maghrib
        else -> day?.isha
    } ?: "NOT_PRAYED"

    val completeDays = month.count {
        listOf(it.fajr, it.dhuhr, it.asr, it.maghrib, it.isha).all { s -> s != "NOT_PRAYED" }
    }
    val onTime = month.sumOf { d ->
        listOf(d.fajr, d.dhuhr, d.asr, d.maghrib, d.isha).count { it == "ON_TIME" || it == "JAMAAH" }
    }
    val prayerActivity = remember(month) {
        (29 downTo 0).map { offset ->
            val day = java.time.LocalDate.now().minusDays(offset.toLong()).toString()
            month.firstOrNull { it.localDate == day }?.let { d ->
                listOf(d.fajr, d.dhuhr, d.asr, d.maghrib, d.isha).count { it != "NOT_PRAYED" }.toDouble()
            } ?: 0.0
        }
    }
    val weakest = remember(month) {
        listOf(
            "Fajr" to month.count { it.fajr == "NOT_PRAYED" },
            "Dhuhr" to month.count { it.dhuhr == "NOT_PRAYED" },
            "Asr" to month.count { it.asr == "NOT_PRAYED" },
            "Maghrib" to month.count { it.maghrib == "NOT_PRAYED" },
            "Isha" to month.count { it.isha == "NOT_PRAYED" }
        ).filter { it.second > 0 }.maxByOrNull { it.second }
    }
    val logged = month.sumOf { d ->
        listOf(d.fajr, d.dhuhr, d.asr, d.maghrib, d.isha).count { it != "NOT_PRAYED" }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
    ) {
        ModuleTitle("Prayer", "Times calculated on device — no internet needed")

        Text("Sunrise ${hourToClock(times.sunrise)}", style = MonoNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))

        com.nizam.app.ui.design.Group {
            rows.forEachIndexed { i, (key, label, time) ->
                val status = statusOf(key)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(hourToClock(time), style = MonoNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ChipRow(
                    options = PRAYER_STATUSES.drop(1),
                    selected = status,
                    onSelect = { candidate ->
                        vm.setStatus(key, if (status == candidate) "NOT_PRAYED" else candidate)
                    },
                    labels = { statusLabel(it) }
                )
                if (i < rows.lastIndex) Spacer(Modifier.height(14.dp))
            }
        }

        Spacer(Modifier.height(28.dp))

        Text("Last 30 days", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        StatRow(
            listOf(
                "logged" to "$logged/150",
                "on time" to if (logged > 0) "${onTime * 100 / logged}%" else "—",
                "complete" to "$completeDays d"
            )
        )
        Spacer(Modifier.height(8.dp))
        Text("CONSISTENCY", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Heatmap(values = prayerActivity, accent = Spine.Prayer)

        // Which prayer you drop most is the actionable number here
        weakest?.let { (name, misses) ->
            Spacer(Modifier.height(8.dp))
            Text(
                "$name is the one you miss most — $misses of the last 30 days.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(28.dp))

        Text("Calculation", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        ChipRow(
            options = PrayerTimes.Method.entries.map { it.name },
            selected = method,
            onSelect = { vm.setMethod(it) },
            labels = { name -> PrayerTimes.Method.valueOf(name).label }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = hanafi, onClick = { vm.setHanafi(true) }, label = { Text("Hanafi Asr") })
            FilterChip(selected = !hanafi, onClick = { vm.setHanafi(false) }, label = { Text("Shafi'i Asr") })
        }

        Spacer(Modifier.height(14.dp))
        var latText by remember(lat) { mutableStateOf(lat.toString()) }
        var lngText by remember(lng) { mutableStateOf(lng.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it },
                label = { Text("Latitude") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = lngText,
                onValueChange = { lngText = it },
                label = { Text("Longitude") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val la = latText.toDoubleOrNull()
                val lo = lngText.toDoubleOrNull()
                if (la != null && lo != null) vm.setLocation(la, lo)
            }) {
                Text("Save location")
            }
            // The phone already knows where it is — no need to look coordinates up by hand
            OutlinedButton(onClick = {
                val lm = ctx.getSystemService(android.location.LocationManager::class.java)
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val last = if (!granted || lm == null) null else runCatching {
                    lm.getProviders(true).mapNotNull { p -> lm.getLastKnownLocation(p) }.maxByOrNull { it.time }
                }.getOrNull()
                if (last == null) com.nizam.app.ui.components.Feedback.show(
                    if (!granted) "Allow location for Atlas in Phone control first" else "No recent location yet — open Maps once, then try again")
                else {
                    vm.setLocation(last.latitude, last.longitude)
                    latText = "%.4f".format(last.latitude); lngText = "%.4f".format(last.longitude)
                    com.nizam.app.ui.components.Feedback.show("Prayer times now follow your location")
                }
            }) { Text("Use my location") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Defaults to Lahore. Times are computed from your coordinates; check them against your masjid once and adjust the method if they differ.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))
    }
}
