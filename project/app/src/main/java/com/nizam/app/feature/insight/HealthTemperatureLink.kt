package com.nizam.app.feature.insight

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.feature.next.JsonStore
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import org.json.JSONObject

object HealthTemperatureLink {
    val readPermission = HealthPermission.getReadPermission(SkinTemperatureRecord::class)

    fun supported(context: Context): Boolean {
        if (!HealthLink.available(context)) return false
        return runCatching {
            HealthConnectClient.getOrCreate(context).features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)
    }

    suspend fun hasReadPermission(context: Context): Boolean {
        if (!supported(context)) return false
        return runCatching {
            HealthConnectClient.getOrCreate(context).permissionController
                .getGrantedPermissions().contains(readPermission)
        }.getOrDefault(false)
    }

    suspend fun sync(context: Context, settings: SettingsRepository): Map<String, Double> {
        if (!settings.healthReadSkinTemperature.first()) {
            throw SecurityException("Skin-temperature consent is off.")
        }
        if (!supported(context)) {
            throw IllegalStateException("Skin temperature isn't supported by this Health Connect provider.")
        }
        val client = HealthConnectClient.getOrCreate(context)
        if (!client.permissionController.getGrantedPermissions().contains(readPermission)) {
            throw SecurityException("Skin-temperature read permission is missing.")
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusDays(29).atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        return client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(SkinTemperatureRecord.TEMPERATURE_DELTA_AVG),
                timeRangeFilter = TimeRangeFilter.between(start, end),
                timeRangeSlicer = Period.ofDays(1)
            )
        ).mapNotNull { day ->
            val date = day.startTime.toLocalDate().toString()
            val delta = day.result[SkinTemperatureRecord.TEMPERATURE_DELTA_AVG]?.inCelsius
            if (delta == null || !delta.isFinite() || delta !in -20.0..20.0) null
            else date to delta
        }.toMap()
    }
}

class SkinTemperatureCache(context: Context) {
    private val store = JsonStore(context, "health_skin_temperature", sealed = true)

    fun load(): Map<String, Double> = store.all().mapNotNull { record ->
        val date = record.optString("date")
        if (runCatching { LocalDate.parse(date) }.isFailure) return@mapNotNull null
        val delta = record.optDouble("deltaCelsius", Double.NaN)
        if (!delta.isFinite() || delta !in -20.0..20.0) null else date to delta
    }.toMap()

    fun save(dailyDeltas: Map<String, Double>) {
        store.save(dailyDeltas.toSortedMap().map { (date, delta) ->
            JSONObject().put("id", date).put("date", date).put("deltaCelsius", delta)
        })
    }

    fun clear() = store.save(emptyList())
}