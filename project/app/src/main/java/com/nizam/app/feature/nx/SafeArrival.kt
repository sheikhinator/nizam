package com.nizam.app.feature.nx

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShareLocation
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Safe-arrival check-in: arm a timer for a journey and tap "I'm safe" when you arrive. If the timer
 * runs out you're asked first; only if you still haven't answered is a prewritten message offered to
 * your chosen contact, and it opens in your SMS app for you to send. Nothing is sent automatically and
 * no location is tracked.
 */
object SafeArrival {
    private const val WORK = "safe_arrival"
    private fun p(c: Context) = c.getSharedPreferences("safe_arrival", Context.MODE_PRIVATE)

    fun armedUntil(c: Context) = p(c).getLong("until", 0L)
    fun contact(c: Context) = p(c).getString("name", "").orEmpty() to p(c).getString("phone", "").orEmpty()
    fun message(c: Context) = p(c).getString("msg", "").orEmpty()

    fun arm(c: Context, minutes: Int, name: String, phone: String, msg: String) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        p(c).edit().putLong("until", until).putString("name", name).putString("phone", phone).putString("msg", msg).apply()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SafeArrivalWorker>().setInitialDelay(minutes.toLong(), TimeUnit.MINUTES).setInputData(workDataOf("stage" to 1)).build())
    }

    fun safe(c: Context) {
        p(c).edit().putLong("until", 0L).apply()
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
        NotificationManagerCompat.from(c).cancel(NOTE)
    }

    fun extend(c: Context, minutes: Int) { val (n, ph) = contact(c); arm(c, minutes, n, ph, message(c)) }

    internal const val NOTE = 7310

    internal fun smsIntent(c: Context): Intent? {
        val (_, phone) = contact(c)
        if (phone.isBlank()) return null
        return Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone.filter { it.isDigit() || it == '+' }))
            .putExtra("sms_body", message(c).ifBlank { "I haven't checked in as safe yet. Please call me." }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

class SafeArrivalWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val c = applicationContext
        if (SafeArrival.armedUntil(c) == 0L) return Result.success()
        val stage = inputData.getInt("stage", 1)
        com.nizam.app.feature.notify.Notifications.createChannels(c)
        val open = PendingIntent.getActivity(c, 7311, c.packageManager.getLaunchIntentForPackage(c.packageName)?.putExtra("route", "nx/safearrival"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(c, com.nizam.app.feature.notify.Notifications.CHANNEL_PRAYER)
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open)
        if (stage == 1) {
            b.setContentTitle("Are you safe?").setContentText("Your arrival timer ran out. Open Atlas and tap I'm safe.")
            WorkManager.getInstance(c).enqueueUniqueWork("safe_arrival", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SafeArrivalWorker>().setInitialDelay(10, TimeUnit.MINUTES).setInputData(workDataOf("stage" to 2)).build())
        } else {
            val sms = SafeArrival.smsIntent(c)
            val (name, _) = SafeArrival.contact(c)
            b.setContentTitle("Still no check-in").setContentText(if (sms != null) "Tap to send your prepared message to $name." else "Open Atlas to check in.")
            if (sms != null) b.setContentIntent(PendingIntent.getActivity(c, 7312, sms, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        runCatching { NotificationManagerCompat.from(c).notify(SafeArrival.NOTE, b.build()) }
        return Result.success()
    }
}

object SafeArrivalSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("safearrival", "Safe arrival", "A journey timer: tap 'I'm safe' when you get there", Icons.Outlined.ShareLocation, Hub.Travel,
            "safe arrival check in journey timer travel alone night") { _, _ -> SafeArrivalScreen() }
    )
}

@Composable
private fun SafeArrivalScreen() {
    val c = LocalContext.current
    var until by remember { mutableLongStateOf(SafeArrival.armedUntil(c)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var mins by remember { mutableIntStateOf(45) }
    val saved = remember { SafeArrival.contact(c) }
    var name by remember { mutableStateOf(saved.first) }
    var phone by remember { mutableStateOf(saved.second) }
    var msg by remember { mutableStateOf(SafeArrival.message(c).ifBlank { "Assalamu alaikum, I set a safe-arrival timer on my trip and haven't checked in. Please call me." }) }
    LaunchedEffect(until) { while (until > 0) { now = System.currentTimeMillis(); delay(1000) } }

    Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (until > now) {
            val left = (until - now) / 1000
            Text("Timer running", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("%d:%02d:%02d".format(left / 3600, left / 60 % 60, left % 60), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
            Button(onClick = { SafeArrival.safe(c); until = 0L }, modifier = Modifier.fillMaxWidth()) { Text("✓ I'm safe") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { SafeArrival.extend(c, 15); until = SafeArrival.armedUntil(c) }) { Text("+15 min") }
                OutlinedButton(onClick = { SafeArrival.extend(c, 30); until = SafeArrival.armedUntil(c) }) { Text("+30 min") }
            }
        } else {
            Text("How long is the journey?", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 45, 60, 120).forEach { m -> FilterChip(selected = mins == m, onClick = { mins = m }, label = { Text(if (m < 60) "$m min" else "${m / 60} h") }) }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Trusted contact (optional)") }, singleLine = true)
            OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("Their number") }, singleLine = true)
            OutlinedTextField(msg, { msg = it }, Modifier.fillMaxWidth(), label = { Text("Message offered if you don't check in") }, minLines = 2)
            Button(onClick = { SafeArrival.arm(c, mins, name, phone, msg); until = SafeArrival.armedUntil(c) }, modifier = Modifier.fillMaxWidth()) { Text("Start the timer") }
            Text("When time's up, Atlas asks you first. Ten minutes later, if you still haven't checked in, it offers your message in your SMS app for you to send. Nothing is sent on its own and your location isn't tracked.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
