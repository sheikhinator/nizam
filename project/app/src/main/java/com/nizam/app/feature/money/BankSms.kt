package com.nizam.app.feature.money

import android.content.Context
import android.net.Uri
import com.nizam.app.AppContainer
import java.time.Instant
import java.time.ZoneId

/**
 * Pakistani banks text you on every card swipe and transfer. Reading those messages turns Finance
 * from something you maintain into something that maintains itself.
 *
 * Parsing is done here on the phone with plain patterns — no AI call, no data leaving the device.
 */
data class BankTxn(val amount: Double, val merchant: String, val kind: String, val date: String, val raw: String, val id: Long)

object BankSms {
    private val BANK_WORDS = listOf("debit", "credit", "spent", "withdrawn", "deposited", "transferred", "purchase",
        "transaction", "payment", "received", "sent", "atm", "pos", "card", "account")
    private val AMOUNT = Regex("""(?:PKR|Rs\.?|RS\.?|Amount:?)\s*:?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val MERCHANT = Regex("""\bat\s+([A-Za-z0-9&'.\- ]{3,36})""", RegexOption.IGNORE_CASE)
    private val CREDIT = listOf("credited", "received", "deposit", "salary", "refund", "cashback of", "you have received")

    private val NOT_A_TXN = Regex("\\b(otp|one[- ]time|do not share|verification code|offer|discount|win |cashback offer|failed|declined|unsuccessful|insufficient|reversed)\\b", RegexOption.IGNORE_CASE)

    fun looksLikeBank(body: String): Boolean {
        val b = body.lowercase()
        if (NOT_A_TXN.containsMatchIn(body)) return false
        return AMOUNT.containsMatchIn(body) && BANK_WORDS.count { b.contains(it) } >= 1
    }

    /**
     * Reads a bank, JazzCash or Easypaisa message: the transaction amount (not the balance quoted after it),
     * which way the money went, and who it was with.
     */
    fun parse(body: String, at: Long, id: Long): BankTxn? {
        if (!looksLikeBank(body)) return null
        // the first amount that isn't a balance or a limit
        val amount = AMOUNT.findAll(body).firstOrNull { m ->
            val before = body.substring(maxOf(0, m.range.first - 22), m.range.first).lowercase()
            listOf("bal", "balance", "limit", "avail", "remaining").none { before.contains(it) }
        }?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (amount <= 0) return null
        val b = body.lowercase()
        val kind = if (CREDIT.any { b.contains(it) } && !b.contains("debited")) "INCOME" else "EXPENSE"
        val who = listOf(
            Regex("""(?:paid to|sent to|transferred to|payment to|to)\s+([A-Za-z][A-Za-z0-9&'.\- ]{2,34}?)(?=\s*(?:\(|,|\.|on |via |ref|trx|tid|a/c|acc|\d{4}|$))""", RegexOption.IGNORE_CASE),
            Regex("""(?:received from|from)\s+([A-Za-z][A-Za-z0-9&'.\- ]{2,34}?)(?=\s*(?:\(|,|\.|on |via |ref|trx|tid|a/c|acc|\d{4}|$))""", RegexOption.IGNORE_CASE),
            MERCHANT,
            Regex("""for\s+([A-Za-z][A-Za-z0-9&'.\- ]{2,30}?)\s+bill""", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { r -> r.find(body)?.groupValues?.get(1)?.trim()?.trim('.', ',')?.takeIf { it.length in 3..36 && !it.lowercase().startsWith("atm ") && !it.lowercase().startsWith("your ") } }
        val merchant = who ?: when {
            b.contains("mobile load") || b.contains("top-up") || b.contains("topup") -> "Mobile load"
            b.contains("atm") -> "ATM withdrawal"
            else -> body.split(Regex("[.\\n]")).firstOrNull { it.length in 6..40 }?.trim().orEmpty()
        }
        return BankTxn(amount, merchant.ifBlank { "Bank transaction" }, kind,
            Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString(), body.take(220), id)
    }

    /** Category guessed from the merchant name — no AI needed for the common cases. */
    fun category(merchant: String): String {
        val m = merchant.lowercase()
        return when {
            listOf("netflix", "spotify", "youtube", "google", "apple", "icloud", "prime", "canva", "chatgpt", "openai", "microsoft").any { m.contains(it) } -> "Subscriptions"
            listOf("foodpanda", "kfc", "mcdonald", "pizza", "restaurant", "cafe", "bakery", "hotel", "cheetay", "hardee", "subway", "chaaye", "biryani", "burger").any { m.contains(it) } -> "Food"
            listOf("school", "university", "college", "academy", "tuition", "fee").any { m.contains(it) } -> "Education"
            listOf("rent", "landlord").any { m.contains(it) } -> "Rent"
            listOf("zakat", "sadqa", "donation", "edhi", "shaukat", "saylani", "akhuwat").any { m.contains(it) } -> "Charity"
            listOf("mobile load", "load", "easyload").any { m.contains(it) } -> "Bills"
            listOf("careem", "uber", "indrive", "bykea", "yango", "petrol", "shell", "pso", "total", "fuel", "attock").any { m.contains(it) } -> "Transport"
            listOf("k-electric", "sui", "ptcl", "jazz", "zong", "ufone", "telenor", "bill", "wapda").any { m.contains(it) } -> "Bills"
            listOf("mart", "store", "grocer", "imtiaz", "metro", "carrefour", "al-fatah").any { m.contains(it) } -> "Groceries"
            listOf("pharmacy", "clinic", "hospital", "lab", "medic").any { m.contains(it) } -> "Health"
            listOf("daraz", "shop", "outfitters", "khaadi", "sapphire").any { m.contains(it) } -> "Shopping"
            else -> "Other"
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("banksms", 0)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun setEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("on", v).apply()
    fun auto(c: Context) = prefs(c).getBoolean("auto", false)
    fun setAuto(c: Context, v: Boolean) = prefs(c).edit().putBoolean("auto", v).apply()
    private fun done(c: Context) = prefs(c).getStringSet("done", emptySet())!!.toMutableSet()
    fun markDone(c: Context, id: Long) = prefs(c).edit().putStringSet("done", done(c).apply { add("$id") }).apply()
    fun isDone(c: Context, id: Long) = "$id" in done(c)

    /** Reads the SMS inbox for bank messages not yet logged. Requires the SMS read permission. */
    fun scan(c: Context, days: Int = 30): List<BankTxn> {
        com.nizam.app.feature.platform2.PermLog.note(c, "SMS", "bank message scan")
        val since = System.currentTimeMillis() - days * 86_400_000L
        val out = mutableListOf<BankTxn>()
        runCatching {
            c.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("_id", "body", "date"),
                "date >= ?", arrayOf("$since"), "date DESC")?.use { cur ->
                while (cur.moveToNext() && out.size < 200) {
                    val id = cur.getLong(0)
                    if (isDone(c, id)) continue
                    parse(cur.getString(1).orEmpty(), cur.getLong(2), id)?.let { out += it }
                }
            }
        }
        return out
    }

    suspend fun log(container: AppContainer, c: Context, t: BankTxn) {
        container.financeRepository.addOn(t.date, t.amount, t.kind, category(t.merchant), t.merchant)
        markDone(c, t.id)
    }
}
