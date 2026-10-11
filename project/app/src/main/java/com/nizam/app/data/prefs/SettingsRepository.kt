package com.nizam.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nizam.app.feature.future.Sealed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.nio.charset.StandardCharsets

private val Context.dataStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(name = "nizam_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private const val MIN_DAILY_TARGET = 1
private const val MAX_DAILY_TARGET = 100_000

class SettingsRepository(private val context: Context) {
    private val _secretProtectionError = MutableStateFlow<String?>(null)
    val secretProtectionError = _secretProtectionError.asStateFlow()

    private val themeKey = stringPreferencesKey("theme_mode")
    private val currencyKey = stringPreferencesKey("currency")
    private val latKey = doublePreferencesKey("latitude")
    private val lngKey = doublePreferencesKey("longitude")
    private val methodKey = stringPreferencesKey("prayer_method")
    private val hanafiKey = booleanPreferencesKey("hanafi_asr")
    private val hadithKeyKey = stringPreferencesKey("hadith_api_key")
    private val geminiKeyKey = stringPreferencesKey("gemini_api_key")
    private val geminiModelKey = stringPreferencesKey("gemini_model")
    private val nameKey = stringPreferencesKey("display_name")
    private val focusKey = stringPreferencesKey("focus_areas")
    private val kcalKey = intPreferencesKey("kcal_target")
    private val waterKey = intPreferencesKey("water_target")
    private val healthWriteWorkoutsKey = booleanPreferencesKey("health_write_workouts")
    private val healthWriteWeightKey = booleanPreferencesKey("health_write_weight")
    private val healthWriteHydrationKey = booleanPreferencesKey("health_write_hydration")
    private val healthReadSkinTemperatureKey = booleanPreferencesKey("health_read_skin_temperature")
    private val onboardedKey = booleanPreferencesKey("onboarded")
    private val providerKey = stringPreferencesKey("ai_provider")
    private val openRouterKeyKey = stringPreferencesKey("openrouter_key")
    private val openRouterModelKey = stringPreferencesKey("openrouter_model")
    private val pinKey = stringPreferencesKey("app_pin")
    private val biometricKey = booleanPreferencesKey("biometric_lock")
    private val sectionOrderKey = stringPreferencesKey("home_section_order")
    private val accentKey = stringPreferencesKey("accent_hex")
    private val customPaletteKey = stringPreferencesKey("custom_palette")
    private val notifyKey = booleanPreferencesKey("notifications_on")
    private val customUrlKey = stringPreferencesKey("custom_url")
    private val customKeyKey = stringPreferencesKey("custom_key")
    private val customModelKey = stringPreferencesKey("custom_model")
    private val screenControlKey = booleanPreferencesKey("screen_control")
    private val profileKey = stringPreferencesKey("profile_json")
    private val faceKey = stringPreferencesKey("avatar_face")
    private val hiddenTilesKey = stringPreferencesKey("hidden_tiles")
    private val tileOrderKey = stringPreferencesKey("tile_order")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        when (prefs[themeKey]) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    val currency: Flow<String> = context.dataStore.data.map { it[currencyKey] ?: "PKR" }
    val latitude: Flow<Double> = context.dataStore.data.map { it[latKey] ?: 31.5204 }
    val longitude: Flow<Double> = context.dataStore.data.map { it[lngKey] ?: 74.3587 }
    val prayerMethod: Flow<String> = context.dataStore.data.map { it[methodKey] ?: "KARACHI" }
    val hanafiAsr: Flow<Boolean> = context.dataStore.data.map { it[hanafiKey] ?: true }
    val hadithApiKey: Flow<String> = secretFlow(hadithKeyKey)
    val geminiApiKey: Flow<String> = secretFlow(geminiKeyKey)
    // "gemini-flash-latest" is an alias Google repoints at the current Flash model, so this keeps
    // working when model names change.
    val geminiModel: Flow<String> = context.dataStore.data.map { it[geminiModelKey] ?: "gemini-flash-lite-latest" }
    val displayName: Flow<String> = context.dataStore.data.map { it[nameKey] ?: "" }
    val focusAreas: Flow<String> = context.dataStore.data.map { it[focusKey] ?: "" }
    val kcalTarget: Flow<Int> = context.dataStore.data.map { it[kcalKey] ?: 2200 }
    val waterTarget: Flow<Int> = context.dataStore.data.map { it[waterKey] ?: 3000 }
    val healthWriteWorkouts: Flow<Boolean> = context.dataStore.data.map { it[healthWriteWorkoutsKey] ?: false }
    val healthWriteWeight: Flow<Boolean> = context.dataStore.data.map { it[healthWriteWeightKey] ?: false }
    val healthWriteHydration: Flow<Boolean> = context.dataStore.data.map { it[healthWriteHydrationKey] ?: false }
    val healthReadSkinTemperature: Flow<Boolean> = context.dataStore.data.map { it[healthReadSkinTemperatureKey] ?: false }
    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[onboardedKey] ?: false }
    val aiProvider: Flow<String> = context.dataStore.data.map { it[providerKey] ?: "GEMINI" }
    val openRouterKey: Flow<String> = secretFlow(openRouterKeyKey)
    val openRouterModel: Flow<String> = context.dataStore.data.map { it[openRouterModelKey] ?: "deepseek/deepseek-chat-v3.1:free" }
    val appPin: Flow<String> = context.dataStore.data.map { it[pinKey] ?: "" }
    val biometricLock: Flow<Boolean> = context.dataStore.data.map { it[biometricKey] ?: false }
    val homeSectionOrder: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[sectionOrderKey]?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_SECTIONS
    }
    val accentHex: Flow<String> = context.dataStore.data.map { it[accentKey] ?: "" }
    val customPalette: Flow<String> = context.dataStore.data.map { it[customPaletteKey] ?: "" }
    val notificationsOn: Flow<Boolean> = context.dataStore.data.map { it[notifyKey] ?: false }
    val customUrl: Flow<String> = context.dataStore.data.map { it[customUrlKey] ?: "" }
    val customKey: Flow<String> = secretFlow(customKeyKey)
    val customModel: Flow<String> = context.dataStore.data.map { it[customModelKey] ?: "" }
    val screenControl: Flow<Boolean> = context.dataStore.data.map { it[screenControlKey] ?: false }
    val profileJson: Flow<String> = context.dataStore.data.map { it[profileKey] ?: "{}" }
    val face: Flow<String> = context.dataStore.data.map { it[faceKey] ?: "2-1-0-2-0-0-0" }
    val hiddenTiles: Flow<Set<String>> = context.dataStore.data.map {
        it[hiddenTilesKey]?.split(",")?.filter { s -> s.isNotBlank() }?.toSet() ?: emptySet()
    }
    val tileOrder: Flow<List<String>> = context.dataStore.data.map {
        it[tileOrderKey]?.split(",")?.filter { s -> s.isNotBlank() } ?: emptyList()
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }
    suspend fun setCurrency(code: String) {
        context.dataStore.edit { it[currencyKey] = code }
    }
    suspend fun setLocation(lat: Double, lng: Double) {
        context.dataStore.edit {
            it[latKey] = lat
            it[lngKey] = lng
        }
    }
    suspend fun setPrayerMethod(method: String) {
        context.dataStore.edit { it[methodKey] = method }
    }
    suspend fun setHanafiAsr(value: Boolean) {
        context.dataStore.edit { it[hanafiKey] = value }
    }
    suspend fun setHadithApiKey(key: String) {
        context.dataStore.edit { it[hadithKeyKey] = encodeSecret(key.trim()) }
    }
    suspend fun setGeminiApiKey(key: String) {
        context.dataStore.edit { it[geminiKeyKey] = encodeSecret(key.trim()) }
    }
    suspend fun setGeminiModel(model: String) {
        context.dataStore.edit { it[geminiModelKey] = model.trim() }
    }
    fun targetsAreValid(kcal: Int, waterMl: Int): Boolean =
        kcal in MIN_DAILY_TARGET..MAX_DAILY_TARGET &&
            waterMl in MIN_DAILY_TARGET..MAX_DAILY_TARGET

    suspend fun setTargets(kcal: Int, waterMl: Int) {
        require(targetsAreValid(kcal, waterMl)) {
            "Daily calorie and water targets must be between 1 and 100,000."
        }
        context.dataStore.edit {
            it[kcalKey] = kcal
            it[waterKey] = waterMl
        }
    }
    suspend fun setHealthWriteWorkouts(enabled: Boolean) {
        context.dataStore.edit { it[healthWriteWorkoutsKey] = enabled }
    }
    suspend fun setHealthWriteWeight(enabled: Boolean) {
        context.dataStore.edit { it[healthWriteWeightKey] = enabled }
    }
    suspend fun setHealthWriteHydration(enabled: Boolean) {
        context.dataStore.edit { it[healthWriteHydrationKey] = enabled }
    }
    suspend fun setHealthReadSkinTemperature(enabled: Boolean) {
        context.dataStore.edit { it[healthReadSkinTemperatureKey] = enabled }
    }
    suspend fun completeOnboarding(name: String, kcal: Int, waterMl: Int, focus: String) {
        require(targetsAreValid(kcal, waterMl)) {
            "Daily calorie and water targets must be between 1 and 100,000."
        }
        context.dataStore.edit {
            it[nameKey] = name.trim()
            it[kcalKey] = kcal
            it[waterKey] = waterMl
            it[focusKey] = focus
            it[onboardedKey] = true
        }
    }
    suspend fun setProvider(provider: String) {
        context.dataStore.edit { it[providerKey] = provider }
    }
    suspend fun setCustomKey(key: String) {
        context.dataStore.edit { it[customKeyKey] = encodeSecret(key.trim()) }
    }
    suspend fun setOpenRouterKey(key: String) {
        context.dataStore.edit { it[openRouterKeyKey] = encodeSecret(key.trim()) }
    }
    suspend fun setOpenRouterModel(model: String) {
        context.dataStore.edit { it[openRouterModelKey] = model.trim() }
    }
    suspend fun setPin(pin: String) {
        context.dataStore.edit { it[pinKey] = pin.trim() }
    }
    suspend fun setBiometricLock(enabled: Boolean) {
        context.dataStore.edit { it[biometricKey] = enabled }
    }
    suspend fun setSectionOrder(order: List<String>) {
        context.dataStore.edit { it[sectionOrderKey] = order.joinToString(",") }
    }
    suspend fun setAccent(hex: String) {
        context.dataStore.edit { it[accentKey] = hex.trim() }
    }
    suspend fun setCustomPalette(json: String) {
        context.dataStore.edit { it[customPaletteKey] = json }
    }
    suspend fun setCustomEndpoint(url: String, key: String, model: String) {
        context.dataStore.edit {
            it[customUrlKey] = url.trim()
            it[customKeyKey] = encodeSecret(key.trim())
            it[customModelKey] = model.trim()
        }
    }
    suspend fun setProfile(json: String) { context.dataStore.edit { it[profileKey] = json } }
    suspend fun setFace(code: String) { context.dataStore.edit { it[faceKey] = code } }
    /** Name only — touches nothing else, unlike completeOnboarding which also sets targets. */
    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { it[nameKey] = name.trim(); it[onboardedKey] = true }
    }
    suspend fun setHiddenTiles(set: Set<String>) {
        context.dataStore.edit { it[hiddenTilesKey] = set.joinToString(",") }
    }
    suspend fun setTileOrder(order: List<String>) {
        context.dataStore.edit { it[tileOrderKey] = order.joinToString(",") }
    }
    suspend fun wipe() { context.dataStore.edit { it.clear() } }
    suspend fun setScreenControl(enabled: Boolean) {
        context.dataStore.edit { it[screenControlKey] = enabled }
    }
    suspend fun setNotifications(enabled: Boolean) {
        context.dataStore.edit { it[notifyKey] = enabled }
    }
    suspend fun resetOnboarding() {
        context.dataStore.edit { it[onboardedKey] = false }
    }

    /** Encrypts legacy plaintext credentials before the settings flows are consumed. */
    suspend fun protectLegacySecrets() {
        try {
            context.dataStore.edit { prefs ->
                secretPreferenceKeys().forEach { key ->
                    val value = prefs[key].orEmpty()
                    if (value.isBlank()) return@forEach
                    if (value.startsWith(SECRET_PREFIX)) {
                        decryptSecret(value)
                    } else {
                        prefs[key] = encodeSecret(value)
                    }
                }
            }
            _secretProtectionError.value = null
        } catch (_: Exception) {
            _secretProtectionError.value =
                "A saved API credential could not be protected. Its stored value was left unchanged."
        }
    }

    /** Exports API credentials separately so a restore can re-seal them for the destination device. */
    suspend fun exportCredentialBundle(): ByteArray {
        val prefs = context.dataStore.data.first()
        val bundle = JSONObject()
            .put("format", CREDENTIAL_BUNDLE_FORMAT)
            .put("version", CREDENTIAL_BUNDLE_VERSION)
            .put("hadithApiKey", decryptSecret(prefs[hadithKeyKey].orEmpty()))
            .put("geminiApiKey", decryptSecret(prefs[geminiKeyKey].orEmpty()))
            .put("openRouterKey", decryptSecret(prefs[openRouterKeyKey].orEmpty()))
            .put("customKey", decryptSecret(prefs[customKeyKey].orEmpty()))
        val bytes = bundle.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_CREDENTIAL_BUNDLE_BYTES) {
            "Saved API credentials exceed the safe backup size limit."
        }
        return bytes
    }

    suspend fun importCredentialBundle(bytes: ByteArray) {
        val bundle = parseCredentialBundle(bytes)
        context.dataStore.edit { prefs ->
            prefs[hadithKeyKey] = encodeSecret(bundle.getString("hadithApiKey"))
            prefs[geminiKeyKey] = encodeSecret(bundle.getString("geminiApiKey"))
            prefs[openRouterKeyKey] = encodeSecret(bundle.getString("openRouterKey"))
            prefs[customKeyKey] = encodeSecret(bundle.getString("customKey"))
        }
        _secretProtectionError.value = null
    }

    private fun secretFlow(key: Preferences.Key<String>): Flow<String> =
        context.dataStore.data.map { prefs ->
            runCatching { decryptSecret(prefs[key].orEmpty()) }
                .getOrElse {
                    _secretProtectionError.value =
                        "A saved API credential could not be opened. Its stored value was left unchanged."
                    ""
                }
        }

    private fun encodeSecret(value: String): String =
        if (value.isBlank()) "" else SECRET_PREFIX + Sealed.sealText(value)

    private fun decryptSecret(value: String): String =
        if (value.startsWith(SECRET_PREFIX)) Sealed.openTextStrict(value.removePrefix(SECRET_PREFIX)) else value

    private fun secretPreferenceKeys(): List<Preferences.Key<String>> =
        listOf(hadithKeyKey, geminiKeyKey, openRouterKeyKey, customKeyKey)

    companion object {
        private const val SECRET_PREFIX = "sealed-v1:"
        private const val CREDENTIAL_BUNDLE_FORMAT = "ATLAS_CREDENTIALS"
        private const val CREDENTIAL_BUNDLE_VERSION = 1
        const val MAX_CREDENTIAL_BUNDLE_BYTES = 64 * 1024

        fun validateCredentialBundle(bytes: ByteArray) {
            parseCredentialBundle(bytes)
        }

        private fun parseCredentialBundle(bytes: ByteArray): JSONObject {
            require(bytes.size in 1..MAX_CREDENTIAL_BUNDLE_BYTES) {
                "The API credential bundle is missing or too large."
            }
            val bundle = JSONObject(String(bytes, StandardCharsets.UTF_8))
            require(
                bundle.optString("format") == CREDENTIAL_BUNDLE_FORMAT &&
                    bundle.optInt("version") == CREDENTIAL_BUNDLE_VERSION
            ) { "The API credential bundle uses an unsupported format." }
            listOf("hadithApiKey", "geminiApiKey", "openRouterKey", "customKey").forEach { field ->
                require(bundle.has(field) && bundle.getString(field).length <= 8192) {
                    "The API credential bundle is incomplete or invalid."
                }
            }
            return bundle
        }

        val DEFAULT_SECTIONS = listOf(
            "MISSIONS", "PRAYER", "COURSE", "TASKS", "HABITS", "DIET",
            "GYM", "FINANCE", "LEARNING", "SELF", "NEWS"
        )
    }
}
