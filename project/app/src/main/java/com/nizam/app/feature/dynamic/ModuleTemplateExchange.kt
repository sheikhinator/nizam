package com.nizam.app.feature.dynamic

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

data class ModuleTemplatePreview(
    val name: String,
    val tagline: String,
    val emoji: String,
    val colorHex: String,
    val listStyle: String,
    val fields: List<SpecField>,
    val syntheticExample: Map<String, String>
)

/**
 * Portable templates contain module metadata only. The preview example is generated from the
 * schema, never from entries in the user's database, and is not imported as a record.
 */
object ModuleTemplateExchange {
    const val MAX_TEMPLATE_BYTES = 256 * 1024
    private const val FORMAT = "ATLAS_MODULE_TEMPLATE"
    private const val VERSION = 1
    private const val MAX_FIELDS = 80
    private const val MAX_OPTIONS = 100
    private val fieldTypes = setOf(
        "TEXT", "LONGTEXT", "NUMBER", "DATE", "CHOICE", "MULTI_SELECT", "TAGS",
        "RATING", "BOOL", "FILE", "COMPUTED"
    )
    private val permissionNames = setOf("network", "deviceData", "agentActions")

    fun encode(spec: ModuleSpec): String {
        require(!spec.builtIn) { "Only user-created modules can be exported as templates." }
        require(spec.name.trim().isNotBlank() && spec.name.length <= 80) {
            "The module name must contain 1–80 characters."
        }
        require(spec.tagline.length <= 240) { "The module description is too long to export." }
        require(spec.emoji.isNotBlank() && spec.emoji.length <= 16) {
            "The module icon cannot be exported safely."
        }
        require(spec.colorHex.matches(Regex("#[0-9a-fA-F]{6}"))) {
            "The module colour cannot be exported safely."
        }
        require(spec.listStyle in setOf("CARD", "ROW", "GRID")) {
            "This module uses an unsupported layout."
        }
        validateFields(spec.fields)
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put(
                "module",
                JSONObject()
                    .put("name", spec.name)
                    .put("tagline", spec.tagline)
                    .put("emoji", spec.emoji)
                    .put("colorHex", spec.colorHex)
                    .put("listStyle", spec.listStyle)
                    .put("fields", fieldsJson(spec.fields))
            )
            .put("permissions", JSONArray())
            .put("syntheticExample", JSONObject().apply {
                spec.fields.forEach { field -> put(field.key, syntheticValue(field)) }
            })
        val encoded = root.toString(2)
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEMPLATE_BYTES) {
            "This module schema is too large to export safely."
        }
        return encoded
    }

    fun parse(encoded: String): ModuleTemplatePreview {
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEMPLATE_BYTES) {
            "This template is larger than the safe import limit."
        }
        val root = JSONObject(encoded)
        require(root.optString("format") == FORMAT && root.optInt("version") == VERSION) {
            "This is not a supported Atlas module template."
        }
        val module = root.optJSONObject("module")
            ?: error("The template is missing its module schema.")
        val name = module.optString("name").trim()
        val tagline = module.optString("tagline").trim()
        val emoji = module.optString("emoji").trim()
        val colorHex = module.optString("colorHex").trim()
        val listStyle = module.optString("listStyle", "CARD").uppercase(Locale.ROOT)
        require(name.isNotBlank() && name.length <= 80) { "The template needs a name of 1–80 characters." }
        require(tagline.length <= 240) { "The template description is too long." }
        require(emoji.isNotBlank() && emoji.length <= 16) { "The template icon is invalid." }
        require(colorHex.matches(Regex("#[0-9a-fA-F]{6}"))) { "The template colour is invalid." }
        require(listStyle in setOf("CARD", "ROW", "GRID")) { "The template uses an unsupported module layout." }

        val fieldsArray = module.optJSONArray("fields")
            ?: error("The template is missing its field definitions.")
        require(fieldsArray.length() in 1..MAX_FIELDS) {
            "A template must contain between 1 and $MAX_FIELDS fields."
        }
        val fields = (0 until fieldsArray.length()).map { index ->
            val item = fieldsArray.optJSONObject(index)
                ?: error("Field ${index + 1} is malformed.")
            val key = item.optString("key").trim()
            val label = item.optString("label").trim()
            val type = item.optString("type").uppercase(Locale.ROOT)
            val suffix = item.optString("suffix").trim()
            val formula = item.optString("formula").trim()
            val requiredValue = item.opt("required")
            require(requiredValue == null || requiredValue is Boolean) {
                "Field “$label” has an invalid required declaration."
            }
            val conditionFieldKey = item.optString("conditionFieldKey").trim()
            val conditionValue = item.optString("conditionValue").trim()
            require(key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,39}"))) {
                "Field ${index + 1} has an invalid key."
            }
            require(label.isNotBlank() && label.length <= 100) {
                "Field ${index + 1} needs a label of 1–100 characters."
            }
            require(type in fieldTypes) { "Field “$label” uses unsupported type “$type”." }
            require(suffix.length <= 24 && formula.length <= 512) {
                "Field “$label” contains metadata that is too long."
            }
            val optionsJson = item.optJSONArray("options") ?: JSONArray()
            require(optionsJson.length() <= MAX_OPTIONS) { "Field “$label” has too many options." }
            val options = (0 until optionsJson.length()).map { optionIndex ->
                optionsJson.optString(optionIndex).trim().also { option ->
                    require(option.isNotBlank() && option.length <= 100) {
                        "Field “$label” has an empty or oversized choice."
                    }
                }
            }
            require(type !in setOf("CHOICE", "MULTI_SELECT") || options.isNotEmpty()) {
                "Choice field “$label” needs at least one option."
            }
            SpecField(
                key = key,
                label = label,
                type = type,
                options = options,
                suffix = suffix,
                formula = formula,
                required = item.optBoolean("required", false),
                conditionFieldKey = conditionFieldKey,
                conditionValue = conditionValue
            )
        }
        require(fields.map { it.key.lowercase(Locale.ROOT) }.distinct().size == fields.size) {
            "The template contains duplicate field keys."
        }
        validateConditions(fields)

        val permissions = root.optJSONArray("permissions")
            ?: error("The template is missing its permission declaration.")
        val declaredPermissions = (0 until permissions.length()).map { index ->
            permissions.optString(index).trim()
        }
        require(declaredPermissions.distinct().size == declaredPermissions.size) {
            "The template repeats a permission declaration."
        }
        val unsupportedPermissions = declaredPermissions.filterNot { it in permissionNames }
        require(unsupportedPermissions.isEmpty()) {
            "The template declares unsupported access: ${unsupportedPermissions.joinToString()}. No access was granted."
        }
        require(declaredPermissions.isEmpty()) {
            "This module engine cannot grant requested permissions. Import a template with no permissions requested."
        }

        return ModuleTemplatePreview(
            name = name,
            tagline = tagline,
            emoji = emoji,
            colorHex = colorHex,
            listStyle = listStyle,
            fields = fields,
            syntheticExample = fields.associate { it.key to syntheticValue(it) }
        )
    }

    fun toModuleSpec(preview: ModuleTemplatePreview, sortIndex: Int): ModuleSpec {
        val slug = preview.name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(24)
            .ifBlank { "module" }
        val key = "${slug}_${UUID.randomUUID().toString().replace("-", "")}"
        return ModuleSpec(
            key = key,
            name = preview.name,
            emoji = preview.emoji,
            tagline = preview.tagline,
            colorHex = preview.colorHex,
            fieldsJson = fieldsJson(preview.fields).toString(),
            listStyle = preview.listStyle,
            builtIn = false,
            enabled = true,
            sortIndex = sortIndex
        )
    }

    fun suggestedFileName(name: String): String {
        val slug = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "module" }
        return "atlas-$slug-template.json"
    }

    private fun validateFields(fields: List<SpecField>) {
        require(fields.size in 1..MAX_FIELDS) {
            "A template must contain between 1 and $MAX_FIELDS fields."
        }
        fields.forEachIndexed { index, field ->
            require(field.key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,39}"))) {
                "Field ${index + 1} has an invalid key."
            }
            require(field.label.isNotBlank() && field.label.length <= 100) {
                "Field ${index + 1} needs a label of 1–100 characters."
            }
            require(field.type in fieldTypes) {
                "Field “${field.label}” uses a type that templates do not support."
            }
            require(field.suffix.length <= 24 && field.formula.length <= 512) {
                "Field “${field.label}” contains metadata that is too long."
            }
            require(field.options.size <= MAX_OPTIONS && field.options.all {
                it.isNotBlank() && it.length <= 100
            }) {
                "Field “${field.label}” has an empty or oversized choice."
            }
            require(field.type !in setOf("CHOICE", "MULTI_SELECT") || field.options.isNotEmpty()) {
                "Choice field “${field.label}” needs at least one option."
            }
            require(field.conditionFieldKey.length <= 40 && field.conditionValue.length <= 100) {
                "Field “${field.label}” has an oversized visibility condition."
            }
        }
        require(fields.map { it.key.lowercase(Locale.ROOT) }.distinct().size == fields.size) {
            "This module contains duplicate field keys and cannot be exported safely."
        }
        validateConditions(fields)
    }

    private fun validateConditions(fields: List<SpecField>) {
        val byKey = fields.associateBy { it.key }
        fields.forEach { field ->
            if (field.conditionFieldKey.isBlank()) {
                require(field.conditionValue.isBlank()) {
                    "Field “${field.label}” has a condition value but no source field."
                }
            } else {
                val source = byKey[field.conditionFieldKey]
                require(source != null && source.key != field.key) {
                    "Field “${field.label}” refers to a missing or itself as its condition."
                }
                require(source.type != "FILE" && source.type != "COMPUTED") {
                    "Field “${field.label}” cannot depend on a file or computed field."
                }
                require(field.conditionValue.isNotBlank()) {
                    "Field “${field.label}” needs a condition value."
                }
            }
        }
        require(!ModuleFieldConditions.hasConditionCycle(fields)) {
            "The template contains circular field visibility conditions."
        }
    }

    private fun fieldsJson(fields: List<SpecField>) = JSONArray().apply {
        fields.forEach { field ->
            put(
                JSONObject()
                    .put("key", field.key)
                    .put("label", field.label)
                    .put("type", field.type)
                    .put("options", JSONArray().apply { field.options.forEach { option -> put(option) } })
                    .put("suffix", field.suffix)
                    .put("formula", field.formula)
                    .put("required", field.required)
                    .put("conditionFieldKey", field.conditionFieldKey)
                    .put("conditionValue", field.conditionValue)
            )
        }
    }

    private fun syntheticValue(field: SpecField): String = when (field.type) {
        "NUMBER" -> "12.5"
        "DATE" -> "2030-01-15"
        "CHOICE" -> field.options.firstOrNull().orEmpty()
        "MULTI_SELECT" -> StructuredFieldValues.encodeMultiSelect(field.options.take(2))
        "TAGS" -> "example; sample"
        "RATING" -> "4"
        "BOOL" -> "true"
        "FILE" -> "Example attachment (not included)"
        "COMPUTED" -> "Calculated from its formula"
        else -> "Example ${field.label.lowercase(Locale.ROOT)}"
    }
}