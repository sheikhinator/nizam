package com.nizam.app.feature.insight

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/** Steps and sleep from Health Connect — data from your phone or watch, with no logging by hand. */
object HealthLink {
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )
    val contract = PermissionController.createRequestPermissionResultContract()

    fun available(context: Context) =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(context: Context): Boolean = runCatching {
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(permissions)
    }.getOrElse { false }

    /** Pulls the last N days and caches them, so charts and correlations work offline afterwards. */
    suspend fun sync(context: Context, days: Int = 90): String {
        val client = HealthConnectClient.getOrCreate(context)
        val start = LocalDate.now().minusDays(days - 1L).atStartOfDay()
        val end = LocalDate.now().plusDays(1).atStartOfDay()
        val out = HealthCache(context).load().toMutableMap()

        client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end), Period.ofDays(1))
        ).forEach { g ->
            val k = g.startTime.toLocalDate().toString()
            g.result[StepsRecord.COUNT_TOTAL]?.let { out[k] = out[k]?.first to it.toDouble() }
        }
        val zone = ZoneId.systemDefault()
        val sleep = HashMap<String, Double>()
        client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class,
                TimeRangeFilter.between(start.atZone(zone).toInstant(), end.atZone(zone).toInstant()))
        ).records.forEach { r ->
            // a night's sleep belongs to the morning you woke up
            val k = r.endTime.atZone(zone).toLocalDate().toString()
            sleep[k] = (sleep[k] ?: 0.0) + (r.endTime.toEpochMilli() - r.startTime.toEpochMilli()) / 3_600_000.0
        }
        sleep.forEach { (k, h) -> out[k] = h to out[k]?.second }
        HealthCache(context).save(out)
        return "Synced ${out.size} days of steps and sleep"
    }
}
