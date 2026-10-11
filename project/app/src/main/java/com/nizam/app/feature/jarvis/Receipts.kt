package com.nizam.app.feature.jarvis

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.attach.Attachment
import com.nizam.app.net.Ai
import com.nizam.app.net.Streaming
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Receipt → everything. One photo of a bill becomes: the expense logged on the right day and in the
 * right category, a split worked out and a message drafted for each person, and reminders for the
 * return window and the warranty — so the fridge's warranty is found in 2027 without a drawer search.
 */
data class Receipt(
    val merchant: String, val date: String, val total: Double, val currency: String, val category: String,
    val items: List<String>, val warrantyMonths: Int, val returnDays: Int
)

object Receipts {
    private fun f(c: Context) = File(c.filesDir, "receipts.json")

    /** Reads the bill with a vision model; falls back to on-device OCR + a text model. */
    suspend fun read(container: AppContainer, image: Attachment): Receipt {
        val ask = """Read this receipt or bill. JSON only:
{"merchant":"","date":"YYYY-MM-DD or empty","total":number,"currency":"PKR unless shown otherwise",
 "category":"one of Food, Groceries, Transport, Fuel, Shopping, Electronics, Bills, Health, Education, Charity, Home, Entertainment, Other",
 "items":["up to 8 main items with prices"],
 "warrantyMonths":number (0 unless it's an appliance/electronics or a warranty is printed; typical 12 for electronics),
 "returnDays":number (return/exchange window if printed or typical for the store, else 0)}"""
        val raw = runCatching {
            Streaming.reply(container.settingsRepository, ask, "You extract data from receipts exactly.", 700,
                attachments = listOf(image), context = container.appContext) { }
        }.getOrElse {
            // No vision model available: read the text on the phone, then parse it
            val text = com.nizam.app.feature.scan.recognise(container.appContext, android.net.Uri.fromFile(image.file), false)
            Ai.generate(container.settingsRepository, "$ask\n\nReceipt text:\n$text", "You extract data from receipts exactly.", 700)
        }
        val o = JSONObject(Ai.cleanJson(raw))
        val items = o.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return Receipt(o.optString("merchant").ifBlank { "Receipt" },
            o.optString("date").takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: LocalDate.now().toString(),
            o.optDouble("total", 0.0).takeIf { !it.isNaN() } ?: 0.0, o.optString("currency", "PKR"), o.optString("category", "Other"),
            items, o.optInt("warrantyMonths", 0), o.optInt("returnDays", 0))
    }

    /** Does everything a receipt implies. Returns what was done, line by line. */
    suspend fun file(container: AppContainer, r: Receipt, splitWith: List<String>, image: File? = null): List<String> {
        val c = container.appContext
        val done = mutableListOf<String>()
        if (r.total > 0) {
            container.financeRepository.addOn(r.date, r.total, "EXPENSE", r.category, "${r.merchant}" +
                if (r.items.isNotEmpty()) " — " + r.items.take(4).joinToString(", ") else "")
            done += "Logged ${com.nizam.app.core.fmtAmount(r.total)} at ${r.merchant} under ${r.category} on ${r.date}"
        }
        val zone = ZoneId.systemDefault()
        val bought = LocalDate.parse(r.date)
        if (r.returnDays > 0) {
            val last = bought.plusDays(r.returnDays.toLong() - 1)
            if (!last.isBefore(LocalDate.now())) {
                container.taskRepository.add("Last day to return from ${r.merchant}", last.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), 1)
                done += "Return window reminder for $last"
            }
        }
        if (r.warrantyMonths > 0) {
            val end = bought.plusMonths(r.warrantyMonths.toLong())
            container.taskRepository.add("Warranty ends: ${r.items.firstOrNull()?.substringBefore(" ")?.ifBlank { null } ?: r.merchant} " +
                "(${r.merchant}, bought ${r.date})", end.minusDays(14).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), 1)
            done += "Warranty reminder two weeks before $end"
        }
        if (splitWith.isNotEmpty() && r.total > 0) {
            val share = r.total / (splitWith.size + 1)
            splitWith.forEach { who ->
                done += "Split: ${who.trim()} owes ${com.nizam.app.core.fmtAmount(share)}"
            }
            container.taskRepository.add("Collect ${com.nizam.app.core.fmtAmount(share)} each from ${splitWith.joinToString()} (${r.merchant})", null, 1)
        }
        // keep the record, with the photo, so the Brain and warranty claims can find it
        val saved = image?.let { src ->
            runCatching { File(File(c.filesDir, "receipts").apply { mkdirs() }, "${System.currentTimeMillis()}.jpg").also { src.copyTo(it, true) } }.getOrNull()
        }
        val list = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
        list.put(JSONObject().put("merchant", r.merchant).put("date", r.date).put("total", r.total).put("category", r.category)
            .put("items", JSONArray(r.items)).put("warrantyMonths", r.warrantyMonths).put("returnDays", r.returnDays)
            .put("photo", saved?.path ?: "").put("split", JSONArray(splitWith)))
        f(c).writeText(list.toString())
        Timeline.log(c, "receipt", "Receipt: ${r.merchant} ${com.nizam.app.core.fmtAmount(r.total)}", done.joinToString("; "))
        com.nizam.app.feature.agent.Snapshot.invalidate()
        return done
    }

    fun all(c: Context): List<JSONObject> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.reversed()

    /** The message each person gets for their share. */
    fun splitMessage(r: Receipt, share: Double) =
        "Assalam o alaikum! Your share for ${r.merchant} (${r.date}) comes to ${com.nizam.app.core.fmtAmount(share)}. JazakAllah!"
}
