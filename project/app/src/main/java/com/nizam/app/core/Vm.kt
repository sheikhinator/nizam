package com.nizam.app.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Small factory helper so every module can build its ViewModel without a DI framework. */
@Suppress("UNCHECKED_CAST")
fun <VM : ViewModel> vmFactory(create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

fun today(): String = LocalDate.now().toString()

fun prettyDate(iso: String): String = runCatching {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE d MMM"))
}.getOrElse { iso }

fun fmtAmount(value: Double): String {
    if (com.nizam.app.feature.platform2.AmountPrivacy.hidden()) return "••••"
    val rounded = Math.round(value).toString()
    val negative = rounded.startsWith("-")
    val digits = if (negative) rounded.drop(1) else rounded
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (negative) "-$grouped" else grouped
}

fun hourToClock(hour: Double): String {
    if (hour.isNaN()) return "--:--"
    var h = hour
    while (h < 0) h += 24.0
    while (h >= 24.0) h -= 24.0
    val totalMinutes = Math.round(h * 60.0).toInt()
    val hh = (totalMinutes / 60) % 24
    val mm = totalMinutes % 60
    val suffix = if (hh < 12) "AM" else "PM"
    val display = when {
        hh == 0 -> 12
        hh > 12 -> hh - 12
        else -> hh
    }
    return String.format("%d:%02d %s", display, mm, suffix)
}
