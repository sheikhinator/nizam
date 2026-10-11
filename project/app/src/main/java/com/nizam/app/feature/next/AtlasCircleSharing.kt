package com.nizam.app.feature.next

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

internal data class CircleBundle(
    val circleName: String,
    val circleKey: String,
    val shareKey: String,
    val kind: String,
    val title: String,
    val schedule: String,
    val checkIns: List<JSONObject>
)

/** The share envelope deliberately contains one selected item, never a profile, member list, or Atlas history. */
internal object AtlasCircleCodec {
    private const val PREFIX = "ATLAS-CIRCLE-ITEM/1\n"
    private const val FORMAT = "atlas-circle-item"
    const val MAX_CODE_CHARS = 24_000

    fun encode(circle: JSONObject, item: JSONObject): String {
        val cutoff = LocalDate.now().minusDays(30)
        val source = item.optJSONArray("checkIns") ?: JSONArray()
        val checkIns = (0 until source.length()).mapNotNull { index ->
            val event = source.optJSONObject(index) ?: return@mapNotNull null
            val date = event.optString("date")
            val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@mapNotNull null
            if (parsed.isBefore(cutoff) || parsed.isAfter(LocalDate.now())) return@mapNotNull null
            JSONObject()
                .put("id", event.optString("id").take(80))
                .put("member", safeText(event.optString("member"), 40).ifBlank { "Member" })
                .put("memberKey", event.optString("memberKey").take(80))
                .put("date", date)
        }.sortedBy { it.optString("date") }.takeLast(30)

        val payload = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("circleName", safeText(circle.optString("name"), 60).ifBlank { "Atlas Circle" })
            .put("circleKey", circle.optString("shareKey").ifBlank { circle.optString("id") }.take(80))
            .put("shareKey", item.optString("shareKey").ifBlank { item.optString("id") }.take(80))
            .put("kind", item.optString("kind"))
            .put("title", safeText(item.optString("title"), 120))
            .put("schedule", safeText(item.optString("schedule"), 100))
            .put("checkIns", JSONArray().apply { checkIns.forEach { put(it) } })
        return PREFIX + payload.toString()
    }

    fun decode(raw: String): CircleBundle? {
        if (raw.length > MAX_CODE_CHARS) return null
        val jsonText = raw.trim().removePrefix(PREFIX).trim()
        val payload = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        if (payload.optString("format") != FORMAT || payload.optInt("version") != 1) return null

        val circleName = safeText(payload.optString("circleName"), 60)
        val circleKey = payload.optString("circleKey")
        val shareKey = payload.optString("shareKey")
        val kind = payload.optString("kind")
        val title = safeText(payload.optString("title"), 120)
        if (circleName.isBlank() || !circleKey.matches(Regex("[A-Za-z0-9_-]{8,80}")) ||
            !shareKey.matches(Regex("[A-Za-z0-9_-]{8,80}")) ||
            kind !in setOf("goal", "ritual") || title.isBlank()) return null

        val cutoff = LocalDate.now().minusDays(30)
        val source = payload.optJSONArray("checkIns") ?: JSONArray()
        val checkIns = (0 until minOf(source.length(), 30)).mapNotNull { index ->
            val event = source.optJSONObject(index) ?: return@mapNotNull null
            val id = event.optString("id").take(80)
            val member = safeText(event.optString("member"), 40)
            val memberKey = event.optString("memberKey").take(80)
            val date = event.optString("date")
            val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@mapNotNull null
            if (id.isBlank() || member.isBlank() || !memberKey.matches(Regex("[A-Za-z0-9_-]{8,80}")) ||
                parsed.isBefore(cutoff) || parsed.isAfter(LocalDate.now())) return@mapNotNull null
            JSONObject().put("id", id).put("member", member).put("memberKey", memberKey).put("date", date)
        }.distinctBy { it.optString("id") }

        return CircleBundle(
            circleName = circleName,
            circleKey = circleKey,
            shareKey = shareKey,
            kind = kind,
            title = title,
            schedule = safeText(payload.optString("schedule"), 100),
            checkIns = checkIns
        )
    }

    private fun safeText(value: String, maxLength: Int) =
        value.filterNot { Character.isISOControl(it) }.trim().take(maxLength)
}

internal fun localCircleMemberKey(profileStore: JsonStore, circle: JSONObject): String {
    val circleKey = circle.optString("shareKey").ifBlank { circle.optString("id") }
    val rows = profileStore.all()
    val existing = rows.firstOrNull {
        it.optString("type") == "member" && it.optString("circleKey") == circleKey
    }?.optString("memberKey").orEmpty()
    if (existing.matches(Regex("[A-Za-z0-9_-]{8,80}"))) return existing

    val key = UUID.randomUUID().toString()
    profileStore.save(
        rows.filterNot { it.optString("type") == "member" && it.optString("circleKey") == circleKey } +
            JSONObject().put("type", "member").put("circleKey", circleKey).put("memberKey", key)
    )
    return key
}

internal fun existingCircleMemberKey(profileStore: JsonStore, circle: JSONObject): String {
    val circleKey = circle.optString("shareKey").ifBlank { circle.optString("id") }
    return profileStore.all().firstOrNull {
        it.optString("type") == "member" && it.optString("circleKey") == circleKey
    }?.optString("memberKey").orEmpty()
}