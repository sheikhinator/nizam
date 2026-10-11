package com.nizam.app.feature.nx

import android.content.Context
import android.util.AtomicFile
import com.nizam.app.feature.future.Sealed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

data class AtlasModeProfile(
    val id: String,
    val name: String,
    val visibleCards: Set<String> = DEFAULT_TODAY_CARDS.toSet(),
    val missionIntensity: String = "NORMAL",
    val notificationsEnabled: Boolean? = null,
    val offlineContent: Set<String> = emptySet()
)

data class AtlasSavedSearch(
    val id: String,
    val title: String,
    val query: String,
    val moduleKey: String = "",
    val pinned: Boolean = false
)

data class ModeNavigationState(
    val modes: List<AtlasModeProfile> = DEFAULT_MODES,
    val activeModeId: String = "full",
    val notificationsBeforeMode: Boolean? = null,
    val customGroups: List<String> = emptyList(),
    val moduleGroups: Map<String, String> = emptyMap(),
    val pinnedDestinations: List<String> = emptyList(),
    val recentDestinations: List<String> = emptyList(),
    val savedSearches: List<AtlasSavedSearch> = emptyList(),
    val todayCardOrder: List<String> = DEFAULT_TODAY_CARDS,
    val visibleTodayCards: Set<String> = DEFAULT_TODAY_CARDS.toSet(),
    val compactTodayCards: Set<String> = emptySet(),
    val language: String = "English",
    val urduNumerals: Boolean = false,
    val hijriDates: Boolean = false,
    val lowCognitiveLoad: Boolean = false,
    val savedDrafts: Map<String, String> = emptyMap(),
    val storageError: String? = null
) {
    val activeMode: AtlasModeProfile
        get() = modes.firstOrNull { it.id == activeModeId } ?: DEFAULT_MODES.first()
}

val ATLAS_TODAY_CARD_LABELS = listOf(
    "day-rings" to "Day at a glance",
    "usually-now" to "Usually now",
    "wind-down" to "Wind down",
    "bento" to "Today tiles",
    "companion" to "Companion",
    "next-prayer" to "Next prayer",
    "briefing" to "Morning briefing"
)
private val DEFAULT_TODAY_CARDS = ATLAS_TODAY_CARD_LABELS.map { it.first }

private val DEFAULT_MODES = listOf(
    AtlasModeProfile("full", "Full Atlas"),
    AtlasModeProfile("deep-work", "Deep Work", visibleCards = setOf("day-rings", "bento", "next-prayer"), missionIntensity = "FOCUSED"),
    AtlasModeProfile("low-energy", "Low Energy", visibleCards = setOf("day-rings", "next-prayer"), missionIntensity = "GENTLE"),
    AtlasModeProfile("travel", "Travel", visibleCards = setOf("day-rings", "usually-now", "next-prayer"), missionIntensity = "NORMAL"),
    AtlasModeProfile("study", "Study", visibleCards = setOf("day-rings", "bento", "briefing"), missionIntensity = "FOCUSED"),
    AtlasModeProfile("recovery", "Recovery", visibleCards = setOf("next-prayer", "companion"), missionIntensity = "GENTLE")
)

/**
 * Feature-owned preferences for modes and navigation. The encrypted file is separate from Atlas's
 * shared settings and Room schema, so feature setup never changes central persistence contracts.
 */
class ModeNavigationStore(context: Context) {
    private val baseFile = File(context.applicationContext.filesDir, FILE_NAME)
    private val atomicFile = AtomicFile(baseFile)
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<ModeNavigationState> = mutableState

    @Synchronized
    fun update(change: (ModeNavigationState) -> ModeNavigationState) {
        val current = mutableState.value
        check(current.storageError == null) {
            current.storageError ?: "Mode and navigation settings could not be read."
        }
        val next = change(current).copy(storageError = null)
        persist(next)
        mutableState.value = next
    }

    fun selectMode(id: String, currentNotifications: Boolean): Boolean? {
        var notificationChange: Boolean? = null
        update { current ->
            val target = current.modes.firstOrNull { it.id == id } ?: return@update current
            when {
                target.notificationsEnabled != null -> {
                    notificationChange = target.notificationsEnabled
                    current.copy(
                        activeModeId = id,
                        notificationsBeforeMode = current.notificationsBeforeMode ?: currentNotifications
                    )
                }
                current.activeMode.notificationsEnabled != null && current.notificationsBeforeMode != null -> {
                    notificationChange = current.notificationsBeforeMode
                    current.copy(activeModeId = id, notificationsBeforeMode = null)
                }
                else -> current.copy(activeModeId = id)
            }
        }
        return notificationChange
    }

    fun setModeNotifications(id: String, value: Boolean?, currentNotifications: Boolean): Boolean? {
        var notificationChange: Boolean? = null
        update { current ->
            val target = current.modes.firstOrNull { it.id == id } ?: return@update current
            val modes = current.modes.map { if (it.id == id) it.copy(notificationsEnabled = value) else it }
            if (id != current.activeModeId) {
                current.copy(modes = modes)
            } else if (value == null) {
                if (target.notificationsEnabled != null) notificationChange = current.notificationsBeforeMode
                current.copy(modes = modes, notificationsBeforeMode = null)
            } else {
                notificationChange = value
                current.copy(
                    modes = modes,
                    notificationsBeforeMode = current.notificationsBeforeMode ?: currentNotifications
                )
            }
        }
        return notificationChange
    }

    fun deleteMode(id: String): Boolean? {
        var notificationChange: Boolean? = null
        update { current ->
            if (id == "full" || current.modes.none { it.id == id }) return@update current
            val deletingActive = current.activeModeId == id
            if (deletingActive && current.activeMode.notificationsEnabled != null) {
                notificationChange = current.notificationsBeforeMode
            }
            current.copy(
                modes = current.modes.filterNot { it.id == id },
                activeModeId = if (deletingActive) "full" else current.activeModeId,
                notificationsBeforeMode = if (deletingActive) null else current.notificationsBeforeMode
            )
        }
        return notificationChange
    }

    fun recordRecent(route: String) = update { current ->
        if (route.isBlank()) current else current.copy(
            recentDestinations = (listOf(route) + current.recentDestinations.filterNot { it == route })
                .take(MAX_RECENT)
        )
    }

    fun togglePin(route: String) = update { current ->
        if (route.isBlank()) current else current.copy(
            pinnedDestinations = if (route in current.pinnedDestinations) {
                current.pinnedDestinations - route
            } else {
                (current.pinnedDestinations + route).take(MAX_PINNED)
            }
        )
    }

    fun saveSearch(title: String, query: String, moduleKey: String = "") = update { current ->
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) current else {
            val saved = AtlasSavedSearch(
                id = UUID.randomUUID().toString(),
                title = title.trim().ifBlank { trimmedQuery.take(40) },
                query = trimmedQuery,
                moduleKey = moduleKey.trim()
            )
            current.copy(savedSearches = (listOf(saved) + current.savedSearches).take(MAX_SEARCHES))
        }
    }

    fun deleteSearch(id: String) = update { current ->
        current.copy(savedSearches = current.savedSearches.filterNot { it.id == id })
    }

    fun setSearchPinned(id: String, pinned: Boolean) = update { current ->
        current.copy(savedSearches = current.savedSearches.map {
            if (it.id == id) it.copy(pinned = pinned) else it
        })
    }

    fun saveDraft(key: String, value: String) = update { current ->
        if (key.isBlank()) current else current.copy(savedDrafts = current.savedDrafts + (key to value))
    }

    fun discardDraft(key: String) = update { current ->
        current.copy(savedDrafts = current.savedDrafts - key)
    }

    private fun load(): ModeNavigationState {
        if (!baseFile.exists()) return ModeNavigationState()
        return try {
            val encrypted = atomicFile.openRead().use { it.readBytes() }
            val plain = String(Sealed.open(encrypted), StandardCharsets.UTF_8)
            require(plain.startsWith(FILE_HEADER)) { "Unrecognized mode settings file." }
            decode(JSONObject(plain.removePrefix(FILE_HEADER)))
        } catch (failure: Exception) {
            ModeNavigationState(storageError =
                "Mode and navigation settings could not be read. The encrypted file was left unchanged.")
        }
    }

    private fun persist(state: ModeNavigationState) {
        val bytes = Sealed.seal((FILE_HEADER + encode(state).toString()).toByteArray(StandardCharsets.UTF_8))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (failure: Exception) {
            stream?.let { atomicFile.failWrite(it) }
            throw IllegalStateException("Mode and navigation settings could not be saved.", failure)
        }
    }

    private fun encode(state: ModeNavigationState) = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("activeModeId", state.activeModeId)
        put("notificationsBeforeMode", state.notificationsBeforeMode ?: JSONObject.NULL)
        put("modes", JSONArray().apply {
            state.modes.forEach { mode ->
                put(JSONObject()
                    .put("id", mode.id)
                    .put("name", mode.name)
                    .put("visibleCards", strings(mode.visibleCards))
                    .put("missionIntensity", mode.missionIntensity)
                    .put("notificationsEnabled", mode.notificationsEnabled ?: JSONObject.NULL)
                    .put("offlineContent", strings(mode.offlineContent)))
            }
        })
        put("customGroups", strings(state.customGroups))
        put("moduleGroups", JSONObject().apply { state.moduleGroups.forEach { (key, value) -> put(key, value) } })
        put("pinnedDestinations", strings(state.pinnedDestinations))
        put("recentDestinations", strings(state.recentDestinations))
        put("savedSearches", JSONArray().apply {
            state.savedSearches.forEach { search ->
                put(JSONObject()
                    .put("id", search.id)
                    .put("title", search.title)
                    .put("query", search.query)
                    .put("moduleKey", search.moduleKey)
                    .put("pinned", search.pinned))
            }
        })
        put("todayCardOrder", strings(state.todayCardOrder))
        put("visibleTodayCards", strings(state.visibleTodayCards))
        put("compactTodayCards", strings(state.compactTodayCards))
        put("language", state.language)
        put("urduNumerals", state.urduNumerals)
        put("hijriDates", state.hijriDates)
        put("lowCognitiveLoad", state.lowCognitiveLoad)
        put("savedDrafts", JSONObject().apply { state.savedDrafts.forEach { (key, value) -> put(key, value) } })
    }

    private fun decode(root: JSONObject): ModeNavigationState {
        require(root.optInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported mode settings version." }
        val modesJson = root.optJSONArray("modes") ?: JSONArray()
        val modes = (0 until modesJson.length()).mapNotNull { index ->
            val item = modesJson.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            AtlasModeProfile(
                id = id,
                name = item.optString("name").ifBlank { id },
                visibleCards = item.optJSONArray("visibleCards").toStringSet(DEFAULT_TODAY_CARDS.toSet()),
                missionIntensity = item.optString("missionIntensity", "NORMAL").ifBlank { "NORMAL" },
                notificationsEnabled = if (!item.has("notificationsEnabled") || item.isNull("notificationsEnabled")) null
                    else item.optBoolean("notificationsEnabled"),
                offlineContent = item.optJSONArray("offlineContent").toStringSet()
            )
        }.ifEmpty { DEFAULT_MODES }
        val drafts = root.optJSONObject("savedDrafts")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.optString(it) }
        } ?: emptyMap()
        val moduleGroups = root.optJSONObject("moduleGroups")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.optString(it) }.filterValues(String::isNotBlank)
        } ?: emptyMap()
        return ModeNavigationState(
            modes = modes,
            activeModeId = root.optString("activeModeId").takeIf { id -> modes.any { it.id == id } } ?: modes.first().id,
            notificationsBeforeMode = if (!root.has("notificationsBeforeMode") || root.isNull("notificationsBeforeMode")) null
                else root.optBoolean("notificationsBeforeMode"),
            customGroups = root.optJSONArray("customGroups").toStringList().distinct().take(MAX_GROUPS),
            moduleGroups = moduleGroups,
            pinnedDestinations = root.optJSONArray("pinnedDestinations").toStringList().distinct().take(MAX_PINNED),
            recentDestinations = root.optJSONArray("recentDestinations").toStringList().distinct().take(MAX_RECENT),
            savedSearches = root.optJSONArray("savedSearches").toSavedSearches().take(MAX_SEARCHES),
            todayCardOrder = root.optJSONArray("todayCardOrder").toStringList()
                .filter { it in DEFAULT_TODAY_CARDS }.distinct()
                .let { saved -> saved + DEFAULT_TODAY_CARDS.filterNot { it in saved } },
            visibleTodayCards = root.optJSONArray("visibleTodayCards").toStringSet(DEFAULT_TODAY_CARDS.toSet()),
            compactTodayCards = root.optJSONArray("compactTodayCards").toStringSet(),
            language = root.optString("language", "English").takeIf { it in LANGUAGES } ?: "English",
            urduNumerals = root.optBoolean("urduNumerals", false),
            hijriDates = root.optBoolean("hijriDates", false),
            lowCognitiveLoad = root.optBoolean("lowCognitiveLoad", false),
            savedDrafts = drafts
        )
    }

    companion object {
        const val FILE_NAME = "atlas_modes_navigation_v1.bin"
        const val SCHEMA_VERSION = 1
        const val FILE_HEADER = "ATLAS_MODES_NAVIGATION_V1\n"
        const val MAX_PINNED = 8
        const val MAX_RECENT = 8
        const val MAX_SEARCHES = 20
        const val MAX_GROUPS = 12
        val LANGUAGES = setOf("English", "Urdu", "Roman Urdu")
        @Volatile
        private var singleton: ModeNavigationStore? = null

        fun get(context: Context): ModeNavigationStore = synchronized(this) {
            singleton ?: ModeNavigationStore(context.applicationContext).also { singleton = it }
        }

        private fun strings(values: Iterable<String>) = JSONArray().apply {
            values.forEach { value -> if (value.isNotBlank()) put(value) }
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { optString(it).trim() }.filter(String::isNotBlank)
        }

        private fun JSONArray?.toStringSet(fallback: Set<String> = emptySet()): Set<String> =
            this?.toStringList()?.toSet() ?: fallback

        private fun JSONArray?.toSavedSearches(): List<AtlasSavedSearch> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { index ->
                val item = optJSONObject(index) ?: return@mapNotNull null
                val query = item.optString("query").trim()
                if (query.isBlank()) null else AtlasSavedSearch(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = item.optString("title").ifBlank { query.take(40) },
                    query = query,
                    moduleKey = item.optString("moduleKey"),
                    pinned = item.optBoolean("pinned", false)
                )
            }
        }
    }
}
