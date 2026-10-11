package com.nizam.app.feature.prayer

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Offline prayer time calculation (standard solar geometry, same approach as PrayTimes).
 * Accurate to roughly a minute for most locations. Per-prayer manual adjustments exist in
 * settings for fine-tuning against your local masjid.
 */
object PrayerTimes {

    enum class Method(val label: String, val fajrAngle: Double, val ishaAngle: Double) {
        KARACHI("Karachi", 18.0, 18.0),
        MWL("Muslim World League", 18.0, 17.0),
        ISNA("ISNA", 15.0, 15.0),
        EGYPT("Egyptian", 19.5, 17.5),
        UMM_AL_QURA("Umm al-Qura", 18.5, 18.0)
    }

    data class Times(
        val fajr: Double,
        val sunrise: Double,
        val dhuhr: Double,
        val asr: Double,
        val maghrib: Double,
        val isha: Double
    )

    private fun dToR(d: Double) = d * Math.PI / 180.0
    private fun rToD(r: Double) = r * 180.0 / Math.PI
    private fun sinD(d: Double) = sin(dToR(d))
    private fun cosD(d: Double) = cos(dToR(d))
    private fun tanD(d: Double) = tan(dToR(d))
    private fun arcsinD(x: Double) = rToD(asin(x))
    private fun arccosD(x: Double) = rToD(acos(x))
    private fun arctan2D(y: Double, x: Double) = rToD(atan2(y, x))
    private fun arccotD(x: Double) = rToD(atan(1.0 / x))

    private fun fixAngle(a: Double): Double {
        var v = a - 360.0 * floor(a / 360.0)
        if (v < 0) v += 360.0
        return v
    }

    private fun fixHour(a: Double): Double {
        var v = a - 24.0 * floor(a / 24.0)
        if (v < 0) v += 24.0
        return v
    }

    private fun julianDay(date: LocalDate): Double {
        var year = date.year
        var month = date.monthValue
        val day = date.dayOfMonth
        if (month <= 2) {
            year -= 1
            month += 12
        }
        val a = floor(year / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (year + 4716)) + floor(30.6001 * (month + 1)) + day + b - 1524.5
    }

    /** Returns declination and equation of time for a Julian day. */
    private fun sunPosition(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * sinD(g) + 0.020 * sinD(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = fixHour(arctan2D(cosD(e) * sinD(l), cosD(l)) / 15.0)
        val eqt = q / 15.0 - ra
        val decl = arcsinD(sinD(e) * sinD(l))
        return Pair(decl, eqt)
    }

    fun calculate(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        method: Method = Method.KARACHI,
        hanafiAsr: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault()
    ): Times {
        val timeZoneOffset = zone.rules.getOffset(date.atStartOfDay()).totalSeconds / 3600.0
        val baseJd = julianDay(date) - longitude / (15.0 * 24.0)

        fun declAndEqt(portion: Double) = sunPosition(baseJd + portion)

        fun sunAngleTime(angle: Double, portion: Double, clockwise: Boolean): Double {
            val (decl, eqt) = declAndEqt(portion)
            val noon = fixHour(12.0 - eqt)
            val numerator = -sinD(angle) - sinD(decl) * sinD(latitude)
            val denominator = cosD(decl) * cosD(latitude)
            val ratio = numerator / denominator
            if (ratio > 1.0 || ratio < -1.0) return Double.NaN
            val t = arccosD(ratio) / 15.0
            return if (clockwise) noon + t else noon - t
        }

        fun asrTime(factor: Double, portion: Double): Double {
            val (decl, _) = declAndEqt(portion)
            val angle = -arccotD(factor + tanD(abs(latitude - decl)))
            return sunAngleTime(angle, portion, true)
        }

        fun midDay(portion: Double): Double {
            val (_, eqt) = declAndEqt(portion)
            return fixHour(12.0 - eqt)
        }

        // First pass with rough day portions, then one refinement pass.
        var fajr = sunAngleTime(method.fajrAngle, 5.0 / 24.0, false)
        var sunrise = sunAngleTime(0.833, 6.0 / 24.0, false)
        var dhuhr = midDay(12.0 / 24.0)
        var asr = asrTime(if (hanafiAsr) 2.0 else 1.0, 13.0 / 24.0)
        var maghrib = sunAngleTime(0.833, 18.0 / 24.0, true)
        var isha = sunAngleTime(method.ishaAngle, 18.0 / 24.0, true)

        fajr = sunAngleTime(method.fajrAngle, fajr / 24.0, false)
        sunrise = sunAngleTime(0.833, sunrise / 24.0, false)
        dhuhr = midDay(dhuhr / 24.0)
        asr = asrTime(if (hanafiAsr) 2.0 else 1.0, asr / 24.0)
        maghrib = sunAngleTime(0.833, maghrib / 24.0, true)
        isha = sunAngleTime(method.ishaAngle, isha / 24.0, true)

        fun toLocal(t: Double) = if (t.isNaN()) t else t + timeZoneOffset - longitude / 15.0

        return Times(
            fajr = toLocal(fajr),
            sunrise = toLocal(sunrise),
            dhuhr = toLocal(dhuhr) + 1.0 / 60.0,
            asr = toLocal(asr),
            maghrib = toLocal(maghrib) + 1.0 / 60.0,
            isha = toLocal(isha)
        )
    }
}
