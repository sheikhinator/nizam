package com.nizam.app.feature.insight

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.kilograms
import androidx.health.connect.client.units.milliliters
import com.nizam.app.AppContainer
import com.nizam.app.feature.diet.BodyMetric
import com.nizam.app.feature.diet.WaterIntake
import com.nizam.app.feature.gym.WorkoutSet
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first

object HealthConnectWriteBack {
    enum class Type(val label: String) {
        WORKOUTS("Workouts"),
        WEIGHT("Weight"),
        HYDRATION("Hydration")
    }

    fun permissionsFor(types: Set<Type>): Set<String> = buildSet {
        types.forEach { type ->
            add(
                when (type) {
                    Type.WORKOUTS -> HealthPermission.getWritePermission(ExerciseSessionRecord::class)
                    Type.WEIGHT -> HealthPermission.getWritePermission(WeightRecord::class)
                    Type.HYDRATION -> HealthPermission.getWritePermission(HydrationRecord::class)
                }
            )
        }
    }

    suspend fun send(
        context: Context,
        container: AppContainer,
        types: Set<Type>
    ): String {
        if (types.isEmpty()) return "Choose at least one record type first."
        val locallyEnabled = buildSet {
            if (container.settingsRepository.healthWriteWorkouts.first()) add(Type.WORKOUTS)
            if (container.settingsRepository.healthWriteWeight.first()) add(Type.WEIGHT)
            if (container.settingsRepository.healthWriteHydration.first()) add(Type.HYDRATION)
        }
        if (!locallyEnabled.containsAll(types)) {
            return "Enable local consent for each selected record type before sending."
        }
        if (!HealthLink.available(context)) {
            return "Health Connect isn't installed or up to date on this phone."
        }

        val client = HealthConnectClient.getOrCreate(context)
        val requiredPermissions = permissionsFor(types)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(requiredPermissions)) {
            throw SecurityException("Health Connect write access is missing. Re-enable the selected type to request it again.")
        }

        val version = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val records = mutableListOf<Record>()
        var skippedUntimedWeights = 0
        var processedWaterDeletions = 0

        if (Type.WORKOUTS in types) {
            val sets = container.gymRepository.setsForHealthConnect()
            strengthSessions(sets).forEach { session ->
                val first = session.first()
                val last = session.last()
                val start = Instant.ofEpochMilli(first.createdAt)
                val end = Instant.ofEpochMilli(last.createdAt).plusSeconds(60)
                val setDetails = session.take(10).joinToString("; ") { set ->
                    "${set.exercise.ifBlank { "Exercise" }} ${set.weight} kg × ${set.reps}"
                }
                val omittedSets = session.size - 10
                records += ExerciseSessionRecord(
                    startTime = start,
                    startZoneOffset = zone.rules.getOffset(start),
                    endTime = end,
                    endZoneOffset = zone.rules.getOffset(end),
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
                    title = "Atlas strength session",
                    notes = buildString {
                        append("${session.size} Atlas sets")
                        if (setDetails.isNotEmpty()) append(": $setDetails")
                        if (omittedSets > 0) append("; $omittedSets more sets")
                    },
                    metadata = metadata(
                        clientRecordId = "com.nizam.app:strength:${first.localDate}:${first.createdAt}",
                        version = version
                    )
                )
            }
        }

        if (Type.WEIGHT in types) {
            val weights = container.dietRepository.weightsForHealthConnect()
            skippedUntimedWeights = weights.count { it.recordedAt <= 0L }
            weights.filter { it.recordedAt > 0L && it.weight.isFinite() && it.weight in 0.1..1000.0 }
                .forEach { metric ->
                    val time = Instant.ofEpochMilli(metric.recordedAt)
                    records += weightRecord(metric, time, zone, version)
                }
        }

        if (Type.HYDRATION in types) {
            val deletedEntries = container.dietRepository.deletedWaterIntakes()
            deletedEntries.chunked(100).forEach { batch ->
                client.deleteRecords(
                    recordType = HydrationRecord::class,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = batch.map { hydrationClientId(it) }
                )
                container.dietRepository.markWaterIntakeDeletionsHandled(batch.map { it.id })
                processedWaterDeletions += batch.size
            }
            container.dietRepository.waterIntakes()
                .filter { it.ml in 1..100_000 && it.createdAt > 0L }
                .forEach { intake ->
                    records += hydrationRecord(intake, zone, version)
                }
        }

        var writtenRecords = 0
        records.chunked(100).forEach { batch ->
            writtenRecords += client.insertRecords(batch).recordIdsList.size
        }

        if (writtenRecords == 0 && processedWaterDeletions == 0) {
            return buildString {
                append("No eligible Atlas records were found.")
                if (skippedUntimedWeights > 0) {
                    append(" $skippedUntimedWeights older weight entr")
                    append(if (skippedUntimedWeights == 1) "y has" else "ies have")
                    append(" no saved time and stayed local.")
                }
                append(" Old daily water totals aren't split into individual drinks.")
            }
        }

        return buildString {
            if (writtenRecords > 0) {
                append("Added or updated $writtenRecords Atlas record")
                if (writtenRecords != 1) append('s')
                append(" in Health Connect.")
            }
            if (processedWaterDeletions > 0) {
                if (isNotEmpty()) append(' ')
                append("Processed cleanup for $processedWaterDeletions corrected water entr")
                append(if (processedWaterDeletions == 1) "y." else "ies.")
            }
            if (skippedUntimedWeights > 0) {
                append(" $skippedUntimedWeights older date-only weight entr")
                append(if (skippedUntimedWeights == 1) "y stayed" else "ies stayed")
                append(" local.")
            }
            append(" Repeating this action updates Atlas's existing records instead of adding duplicates.")
        }
    }

    private fun weightRecord(
        metric: BodyMetric,
        time: Instant,
        zone: ZoneId,
        version: Long
    ) = WeightRecord(
        time = time,
        zoneOffset = zone.rules.getOffset(time),
        weight = metric.weight.kilograms,
        metadata = metadata("com.nizam.app:weight:${metric.localDate}", version)
    )

    private fun hydrationRecord(
        intake: WaterIntake,
        zone: ZoneId,
        version: Long
    ): HydrationRecord {
        val start = Instant.ofEpochMilli(intake.createdAt)
        val end = start.plusSeconds(1)
        return HydrationRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            volume = intake.ml.milliliters,
            metadata = metadata(hydrationClientId(intake), version)
        )
    }

    private fun hydrationClientId(intake: WaterIntake) = "com.nizam.app:hydration:${intake.id}"

    private fun metadata(clientRecordId: String, version: Long) =
        Metadata.manualEntry(clientRecordId, version, Device(type = Device.TYPE_PHONE))

    private fun strengthSessions(sets: List<WorkoutSet>): List<List<WorkoutSet>> {
        val maxGapMs = 90L * 60L * 1000L
        return sets.asSequence()
            .filter { it.createdAt > 0L }
            .groupBy { it.localDate }
            .toSortedMap()
            .values
            .flatMap { daySets ->
                val ordered = daySets.sortedBy { it.createdAt }
                val sessions = mutableListOf<List<WorkoutSet>>()
                var current = mutableListOf<WorkoutSet>()
                for (set in ordered) {
                    if (current.isNotEmpty() && set.createdAt - current.last().createdAt > maxGapMs) {
                        sessions += current
                        current = mutableListOf()
                    }
                    current += set
                }
                if (current.isNotEmpty()) sessions += current
                sessions
            }
    }
}