package com.nizam.app.feature.quran

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * Quran, overhauled. The text and the recitation always come from their sources (Al Quran Cloud and
 * the Islamic Network audio CDN, Mishary Alafasy), never generated. Atlas only keeps your place:
 * continue reading, a khatm plan with a daily target, a streak, Arabic size, and listening ayah by ayah.
 */
object QuranPlus {
    const val TOTAL = 6236
    private fun p(c: Context) = c.getSharedPreferences("quran_plus", 0)
    fun lastSurah(c: Context) = p(c).getInt("last_s", 0)
    fun lastAyah(c: Context) = p(c).getInt("last_a", 0)
    fun fontSize(c: Context) = p(c).getFloat("font", 26f)
    fun setFont(c: Context, v: Float) = p(c).edit().putFloat("font", v.coerceIn(18f, 44f)).apply()
    fun targetDays(c: Context) = p(c).getInt("khatm_days", 0)
    fun khatmStart(c: Context) = p(c).getString("khatm_start", LocalDate.now().toString())!!
    fun setKhatm(c: Context, days: Int) = p(c).edit().putInt("khatm_days", days).putString("khatm_start", LocalDate.now().toString()).apply {
        SURAHS.forEach { remove("max_${it.number}") } }.apply()

    /** Called as you scroll: remembers your place and counts newly read ayat for today. */
    fun seen(c: Context, surah: Int, ayah: Int) {
        val e = p(c).edit()
        e.putInt("last_s", surah).putInt("last_a", ayah)
        val max = p(c).getInt("max_$surah", 0)
        if (ayah > max) {
            val today = LocalDate.now().toString()
            e.putInt("max_$surah", ayah).putInt("day_$today", p(c).getInt("day_$today", 0) + (ayah - max))
        }
        e.apply()
    }
    fun readTotal(c: Context) = SURAHS.sumOf { p(c).getInt("max_${it.number}", 0) }
    fun readOn(c: Context, d: LocalDate) = p(c).getInt("day_$d", 0)
    fun streak(c: Context): Int { var n = 0; var d = LocalDate.now(); if (readOn(c, d) == 0) d = d.minusDays(1); while (readOn(c, d) > 0 && n < 2000) { n++; d = d.minusDays(1) }; return n }

    /** Global ayah number, as the audio CDN numbers them. */
    fun global(surah: Int, ayah: Int) = SURAHS.filter { it.number < surah }.sumOf { it.ayahCount } + ayah
    fun audioUrl(surah: Int, ayah: Int) = "https://cdn.islamic.network/quran/audio/128/ar.alafasy/${global(surah, ayah)}.mp3"
}

/** One recitation player for the reader: plays an ayah, and optionally keeps going. */
class Reciter(private val onAyah: (Int) -> Unit) {
    private var mp: MediaPlayer? = null
    var playing by mutableStateOf<Int?>(null)
        private set
    fun play(surah: Int, ayah: Int, last: Int, continuous: Boolean) {
        stop()
        playing = ayah; onAyah(ayah)
        mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            runCatching { setDataSource(QuranPlus.audioUrl(surah, ayah)); setOnPreparedListener { it.start() }
                setOnCompletionListener { if (continuous && ayah < last) play(surah, ayah + 1, last, true) else stop() }
                setOnErrorListener { _, _, _ -> com.nizam.app.ui.components.Feedback.show("Recitation needs internet"); stop(); true }
                prepareAsync() }.onFailure { stop() }
        }
    }
    fun stop() { runCatching { mp?.release() }; mp = null; playing = null }
}

@Composable
fun QuranHome(open: (Int) -> Unit) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cs = MaterialTheme.colorScheme
    val last = QuranPlus.lastSurah(c)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (last > 0) SURAHS.firstOrNull { it.number == last }?.let { s ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.primary.copy(alpha = 0.12f)).clickable { open(s.number) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Continue reading", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    Text("${s.nameEn} · ayah ${QuranPlus.lastAyah(c)}", fontWeight = FontWeight.SemiBold)
                }
                Text(s.nameAr, style = MaterialTheme.typography.titleLarge)
            }
        }
        val read = remember(tick) { QuranPlus.readTotal(c) }
        val days = QuranPlus.targetDays(c)
        val streak = remember(tick) { QuranPlus.streak(c) }
        val today = remember(tick) { QuranPlus.readOn(c, LocalDate.now()) }
        Text("Khatm", fontWeight = FontWeight.SemiBold)
        if (days == 0) {
            Text("Set a plan and Atlas tells you how much to read each day.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(30, 60, 90, 180, 365).forEach { d -> TextButton(onClick = { QuranPlus.setKhatm(c, d); tick++ }) { Text("$d days") } }
            }
        } else {
            val elapsed = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(QuranPlus.khatmStart(c)), LocalDate.now()).toInt() + 1
            val left = (days - elapsed + 1).coerceAtLeast(1)
            val perDay = ((QuranPlus.TOTAL - read).coerceAtLeast(0) + left - 1) / left
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant)) {
                Box(Modifier.fillMaxWidth((read.toFloat() / QuranPlus.TOTAL).coerceIn(0.005f, 1f)).height(8.dp).background(cs.primary))
            }
            Text("$read of ${QuranPlus.TOTAL} ayat · ${"%.1f".format(read * 100f / QuranPlus.TOTAL)}% · day $elapsed of $days", style = MaterialTheme.typography.bodySmall)
            Text(if (read >= QuranPlus.TOTAL) "Khatm complete. MashaAllah." else "Today: $today of about $perDay ayat" + if (streak > 0) " · 🔥 $streak days" else "",
                style = MaterialTheme.typography.bodyMedium, color = if (today >= perDay) cs.primary else cs.onSurface)
            TextButton(onClick = { QuranPlus.setKhatm(c, 0); tick++ }) { Text("Reset plan") }
        }
    }
}
