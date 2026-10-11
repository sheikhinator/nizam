package com.nizam.app.feature.work

import android.content.Context
import android.util.AtomicFile
import com.nizam.app.feature.future.Sealed
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Private feature-owned records for the People & Career workspace.
 *
 * The Room schema remains untouched. The complete file is encrypted with Atlas's Keystore-backed
 * key and replaced atomically. Corrupt or undecryptable data is surfaced as an error; it is never
 * treated as an empty collection and overwritten.
 */
class PeopleCareerStore(context: Context) {
    private val baseFile = File(context.applicationContext.filesDir, "atlas_people_career_v1.bin")
    private val atomicFile = AtomicFile(baseFile)

    fun all(collection: String): List<JSONObject> = synchronized(PROCESS_LOCK) {
        val array = records(readRoot(), collection)
        (0 until array.length()).map { JSONObject(array.getJSONObject(it).toString()) }
    }

    fun allCollections(): Map<String, List<JSONObject>> = synchronized(PROCESS_LOCK) {
        val root = readRoot()
        COLLECTIONS.associateWith { collection ->
            val array = records(root, collection)
            (0 until array.length()).map { JSONObject(array.getJSONObject(it).toString()) }
        }
    }

    /** Inserts or corrects one user-reviewed record while preserving its original creation time. */
    fun upsert(collection: String, draft: JSONObject): String = synchronized(PROCESS_LOCK) {
        require(collection in COLLECTIONS) { "Unknown People & Career record collection." }
        val root = readRoot()
        val items = records(root, collection)
        val record = JSONObject(draft.toString())
        val now = System.currentTimeMillis()
        val id = record.optString("id").ifBlank { UUID.randomUUID().toString() }
        val previous = (0 until items.length())
            .map { items.getJSONObject(it) }
            .firstOrNull { it.optString("id") == id }
        record.put("id", id)
        record.put("createdAt", previous?.optLong("createdAt", now) ?: now)
        record.put("updatedAt", now)
        if (record.optString("provenance").isBlank()) record.put("provenance", "user-entered")

        val next = JSONArray()
        for (i in 0 until items.length()) {
            val old = items.getJSONObject(i)
            if (old.optString("id") != id) next.put(old)
        }
        next.put(record)
        root.getJSONObject("collections").put(collection, next)
        writeRoot(root)
        id
    }

    fun delete(collection: String, id: String) = synchronized(PROCESS_LOCK) {
        require(collection in COLLECTIONS) { "Unknown People & Career record collection." }
        val root = readRoot()
        val items = records(root, collection)
        val next = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("id") != id) next.put(item)
        }
        root.getJSONObject("collections").put(collection, next)
        writeRoot(root)
    }

    private fun readRoot(): JSONObject {
        if (!baseFile.exists()) return emptyRoot()
        return try {
            val encrypted = atomicFile.openRead().use { it.readBytes() }
            val plain = String(Sealed.open(encrypted), StandardCharsets.UTF_8)
            require(plain.startsWith(FILE_HEADER)) { "Unrecognized local record file." }
            val root = JSONObject(plain.removePrefix(FILE_HEADER))
            require(root.optInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported record file version." }
            val collections = root.optJSONObject("collections")
                ?: throw IllegalStateException("Record collections are missing.")
            COLLECTIONS.forEach { key ->
                if (!collections.has(key)) collections.put(key, JSONArray())
                require(collections.optJSONArray(key) != null) { "Invalid collection: $key" }
            }
            root
        } catch (error: Exception) {
            throw IllegalStateException(
                "People & Career records could not be read. The encrypted file was left unchanged.",
                error
            )
        }
    }

    private fun writeRoot(root: JSONObject) {
        val bytes = Sealed.seal((FILE_HEADER + root.toString()).toByteArray(StandardCharsets.UTF_8))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let { atomicFile.failWrite(it) }
            throw IllegalStateException("People & Career records could not be saved.", error)
        }
    }

    private fun emptyRoot() = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("collections", JSONObject().apply {
            COLLECTIONS.forEach { put(it, JSONArray()) }
        })

    private fun records(root: JSONObject, collection: String): JSONArray {
        require(collection in COLLECTIONS) { "Unknown People & Career record collection." }
        return root.getJSONObject("collections").getJSONArray(collection)
    }

    companion object {
        const val RELATIONSHIP_EVENTS = "relationshipEvents"
        const val APPLICATIONS = "applications"
        const val OFFERS = "offers"
        const val INTERVIEW_STORIES = "interviewStories"
        const val SKILL_EVIDENCE = "skillEvidence"
        const val CLIENTS = "clients"
        const val ENGAGEMENTS = "engagements"
        const val REIMBURSEMENTS = "reimbursements"
        const val MEETINGS = "meetings"

        private const val SCHEMA_VERSION = 1
        private const val FILE_HEADER = "ATLAS_PEOPLE_CAREER_V1\n"
        private val PROCESS_LOCK = Any()
        private val COLLECTIONS = setOf(
            RELATIONSHIP_EVENTS,
            APPLICATIONS,
            OFFERS,
            INTERVIEW_STORIES,
            SKILL_EVIDENCE,
            CLIENTS,
            ENGAGEMENTS,
            REIMBURSEMENTS,
            MEETINGS
        )
    }
}

/** Small deterministic rules that keep captured evidence and derived comparisons reviewable. */
internal object PeopleCareerRules {
    private val explicitSpeakerPattern = Regex("^\\s*([^:\\n]{1,48}):\\s*(.*)$")
    private val nonSpeakerPrefixes = setOf(
        "action", "date", "deadline", "decision", "meeting", "note", "owner", "risk", "speaker", "summary", "task"
    )

    fun explicitSpeakerForEvidence(transcript: String, evidence: String): String {
        if (evidence.isBlank()) return ""
        val speakers = transcript.lineSequence()
            .filter { it.contains(evidence, ignoreCase = true) }
            .mapNotNull { line ->
                val prefix = explicitSpeakerPattern.matchEntire(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                prefix.takeIf {
                    it.any(Char::isLetter) && it.lowercase() !in nonSpeakerPrefixes
                }
            }
            .distinctBy { it.lowercase() }
            .toList()
        // A quote repeated under different explicit labels is ambiguous, not a reason to pick the first.
        return speakers.singleOrNull().orEmpty()
    }

    fun evidenceExists(transcript: String, evidence: String): Boolean =
        evidence.isNotBlank() && transcript.contains(evidence, ignoreCase = true)

    fun attributionSupported(
        transcript: String,
        evidence: String,
        speaker: String,
        owner: String,
        deadline: String
    ): Boolean {
        if (!evidenceExists(transcript, evidence)) return false
        val sourceSpeaker = explicitSpeakerForEvidence(transcript, evidence)
        val speakerSupported = speaker.isBlank() || speaker.equals(sourceSpeaker, ignoreCase = true)
        val firstPersonCommitment = Regex(
            "\\b(I will|I'll|I can|I’ll|I am going to|I’m going to|I have to)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(evidence)
        val escapedOwner = Regex.escape(owner.trim())
        val namedCommitment = Regex(
            "\\b$escapedOwner\\b\\s*(?:,\\s*)?(?:will|shall|must|needs? to|is to|can|should)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(evidence)
        val directAssignment = Regex(
            "\\b$escapedOwner\\b\\s*,\\s*(?:please\\b|can you\\b|could you\\b|will you\\b|you will\\b|you'll\\b)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(evidence)
        val explicitResponsibility = Regex(
            "\\b(?:owner|assigned to|responsible for|handled by)\\s*[:=-]?\\s*$escapedOwner\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(evidence)
        val ownerSupported = owner.isBlank() ||
            namedCommitment ||
            directAssignment ||
            explicitResponsibility ||
            (owner.equals(sourceSpeaker, ignoreCase = true) && firstPersonCommitment)
        val deadlineSupported = deadline.isBlank() || evidence.contains(deadline, ignoreCase = true)
        return speakerSupported && ownerSupported && deadlineSupported
    }

    fun validClaimAmount(claimAmount: Double, expenseAmount: Double): Boolean =
        claimAmount.isFinite() && expenseAmount.isFinite() &&
            claimAmount > 0.0 && expenseAmount > 0.0 && claimAmount <= expenseAmount

    fun monthlyOfferCash(
        monthlyPay: Double,
        annualBonus: Double,
        monthlyBenefitsEstimate: Double
    ): Double? {
        if (monthlyPay < 0 || annualBonus < 0 || monthlyBenefitsEstimate < 0) return null
        if (!monthlyPay.isFinite() || !annualBonus.isFinite() || !monthlyBenefitsEstimate.isFinite()) return null
        return monthlyPay + annualBonus / 12.0 + monthlyBenefitsEstimate
    }

    fun estimatedGrossHourly(monthlyCash: Double, hoursPerWeek: Double): Double? {
        if (monthlyCash < 0 || hoursPerWeek <= 0 || !monthlyCash.isFinite() || !hoursPerWeek.isFinite()) return null
        return monthlyCash / (hoursPerWeek * 52.0 / 12.0)
    }

    fun redact(text: String, terms: List<String>): String =
        terms.map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase() }
            .sortedByDescending(String::length)
            .fold(text) { result, term ->
                Regex(Regex.escape(term), RegexOption.IGNORE_CASE).replace(result, "[redacted]")
            }
}