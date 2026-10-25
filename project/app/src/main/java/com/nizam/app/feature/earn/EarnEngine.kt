package com.nizam.app.feature.earn

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Atlas Earn. The engine behind the earning platform: income (PKR and USD), the work pipeline,
 * the product factory, the money radar that notices credits, the overnight engine and the morning report.
 *
 * Rules it never breaks: nothing is sent, posted, published, signed or paid without the owner's tap;
 * no bots on freelance or survey sites; no trading; credits are read only from messages the owner opts in to.
 */
object Earn {
    val STREAMS = listOf("Gigs", "Products", "Apps & APIs", "Affiliate", "Content", "Clients", "Fund", "Other")
    val STAGES = listOf("lead", "proposed", "won", "working", "ready", "delivered", "paid")
    val STAGE_LABEL = mapOf("lead" to "Found", "proposed" to "Proposed", "won" to "Won", "working" to "In work",
        "ready" to "Ready for you", "delivered" to "Delivered", "paid" to "Paid")
    val PRODUCT_TYPES = listOf("Excel / HTML template", "Notion-style planner", "Printable planner (KDP)", "Stock image set",
        "Print-on-demand design", "Mini app idea", "Paid API idea", "Chrome extension idea", "Mini course")

    fun income(c: Context) = JsonStore(c, "earn_income")
    fun work(c: Context) = JsonStore(c, "earn_work")
    fun products(c: Context) = JsonStore(c, "earn_products")
    fun feed(c: Context) = JsonStore(c, "earn_log")
    fun fund(c: Context) = JsonStore(c, "earn_fund")
    fun p(c: Context) = c.getSharedPreferences("earn", 0)

    fun goal(c: Context) = p(c).getFloat("goal", 50000f).toDouble()
    fun setGoal(c: Context, v: Double) = p(c).edit().putFloat("goal", v.toFloat()).apply()
    fun skills(c: Context) = p(c).getString("skills", "Chinese to English translation; Excel and HTML dashboards; data analysis and reporting; retail and supply chain; Android app building")!!
    fun setSkills(c: Context, s: String) = p(c).edit().putString("skills", s).apply()
    fun nightOn(c: Context) = p(c).getBoolean("night", true)
    fun setNight(c: Context, v: Boolean) = p(c).edit().putBoolean("night", v).apply()
    fun radarSms(c: Context) = p(c).getBoolean("radar_sms", false)
    fun setRadarSms(c: Context, v: Boolean) = p(c).edit().putBoolean("radar_sms", v).apply()
    fun radarNotif(c: Context) = p(c).getBoolean("radar_notif", true)
    fun setRadarNotif(c: Context, v: Boolean) = p(c).edit().putBoolean("radar_notif", v).apply()
    fun gumroad(c: Context) = p(c).getString("gumroad", "").orEmpty()
    fun setGumroad(c: Context, t: String) = p(c).edit().putString("gumroad", t.trim()).apply()
    fun toMoney(c: Context) = p(c).getBoolean("to_money", true)
    fun setToMoney(c: Context, v: Boolean) = p(c).edit().putBoolean("to_money", v).apply()

    // ───────────── exchange rate ─────────────
    fun rate(c: Context) = p(c).getFloat("usdpkr", 280f).toDouble()
    fun rateAge(c: Context) = p(c).getLong("usdpkr_t", 0L)
    suspend fun refreshRate(c: Context): Double {
        if (System.currentTimeMillis() - rateAge(c) < 6 * 3_600_000L) return rate(c)
        return runCatching {
            val r = JSONObject(Http.getJson("https://open.er-api.com/v6/latest/USD")).getJSONObject("rates").getDouble("PKR")
            p(c).edit().putFloat("usdpkr", r.toFloat()).putLong("usdpkr_t", System.currentTimeMillis()).apply(); r
        }.getOrDefault(rate(c))
    }
    fun pkr(c: Context, amount: Double, cur: String) = if (cur == "USD") amount * rate(c) else amount

    fun log(c: Context, icon: String, text: String) {
        val s = feed(c)
        s.add(JSONObject().put("t", System.currentTimeMillis()).put("icon", icon).put("text", text))
        s.all().let { all -> if (all.size > 400) s.save(all.sortedBy { it.optLong("t") }.takeLast(400)) }
    }

    /** Adds earned money. Credited money also lands in the Money module unless turned off. */
    suspend fun addIncome(c: Context, container: AppContainer?, source: String, stream: String, amount: Double, cur: String, status: String = "credited",
                          note: String = "", auto: Boolean = false, key: String = ""): JSONObject? {
        if (amount <= 0) return null
        if (key.isNotBlank() && income(c).all().any { it.optString("key") == key }) return null
        val pk = pkr(c, amount, cur)
        val o = income(c).add(JSONObject().put("source", source).put("stream", stream).put("amount", amount).put("cur", cur).put("pkr", pk)
            .put("date", LocalDate.now().toString()).put("t", System.currentTimeMillis()).put("status", status).put("note", note).put("auto", auto).put("key", key))
        if (status == "credited" && container != null && toMoney(c)) runCatching {
            container.financeRepository.add(Math.round(pk * 100) / 100.0, "INCOME", "Earnings", "$source${if (cur == "USD") " ($" + "%.2f".format(amount) + ")" else ""}")
        }
        log(c, if (status == "credited") "💰" else "⏳", "${if (status == "credited") "Credited" else "Expected"} ${fmt(pk)} from $source")
        return o
    }

    fun fmt(pk: Double) = "Rs " + "%,d".format(Math.round(pk))
    fun usd(v: Double) = "$" + "%,.2f".format(v)

    // ───────────── stats ─────────────
    class Stats(val month: Double, val today: Double, val week: Double, val lastMonth: Double, val usdMonth: Double, val byStream: Map<String, Double>,
                val daily: List<Double>, val pending: Double, val allTime: Double)

    fun stats(c: Context): Stats {
        val all = income(c).all()
        val credited = all.filter { it.optString("status") == "credited" }
        val today = LocalDate.now()
        val ym = today.toString().take(7)
        val lm = today.minusMonths(1).toString().take(7)
        fun sum(l: List<JSONObject>) = l.sumOf { it.optDouble("pkr") }
        val month = credited.filter { it.optString("date").startsWith(ym) }
        return Stats(
            month = sum(month),
            today = sum(credited.filter { it.optString("date") == today.toString() }),
            week = sum(credited.filter { it.optString("date") >= today.minusDays(6).toString() }),
            lastMonth = sum(credited.filter { it.optString("date").startsWith(lm) }),
            usdMonth = month.filter { it.optString("cur") == "USD" }.sumOf { it.optDouble("amount") },
            byStream = month.groupBy { it.optString("stream") }.mapValues { e -> sum(e.value) },
            daily = (29 downTo 0).map { d -> sum(credited.filter { it.optString("date") == today.minusDays(d.toLong()).toString() }) },
            pending = sum(all.filter { it.optString("status") == "pending" }),
            allTime = sum(credited))
    }

    // ───────────── money radar: credits from SMS and notifications ─────────────
    private val PLATFORMS = listOf("payoneer", "fiverr", "upwork", "gumroad", "paypal", "wise", "transferwise", "etsy", "payhip", "rapidapi",
        "adobe", "kdp", "amazon", "daraz", "freelancer", "toptal", "contra", "skrill", "stripe", "lemon squeezy", "printful", "printify")
    private val CREDIT_WORDS = Regex("\\b(received|credited|deposit(?:ed)?|paid you|payment of|new sale|you sold|you earned|earnings|payout|funds? (?:have|has) arrived|incoming)\\b", RegexOption.IGNORE_CASE)
    private val NOT_EARN = Regex("\\b(salary|otp|verification|refund|reversal|debited|spent|withdrawn|purchase at|failed|declined|offer)\\b", RegexOption.IGNORE_CASE)
    private val USD = Regex("""(?:US\$|USD\s?|\$)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val RS = Regex("""(?:PKR|Rs\.?)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)

    fun streamFor(text: String): String {
        val t = text.lowercase()
        return when {
            listOf("fiverr", "upwork", "freelancer", "toptal", "contra").any { t.contains(it) } -> "Gigs"
            listOf("gumroad", "payhip", "etsy", "adobe", "kdp", "printful", "printify", "lemon squeezy").any { t.contains(it) } -> "Products"
            listOf("rapidapi", "google play", "play console", "admob").any { t.contains(it) } -> "Apps & APIs"
            listOf("daraz", "affiliate").any { t.contains(it) } -> "Affiliate"
            else -> "Other"
        }
    }

    /** Called for each notification (from the notification listener). Known earning platforms are logged; anything else unknown waits for a tap. */
    fun onNotification(c: Context, pkg: String, title: String, text: String) {
        if (!radarNotif(c)) return
        val all = "$title $text"
        val low = (pkg + " " + all).lowercase()
        if (!CREDIT_WORDS.containsMatchIn(all) || NOT_EARN.containsMatchIn(all)) return
        val platform = PLATFORMS.firstOrNull { low.contains(it) } ?: return
        val usd = USD.find(all)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        val rs = RS.find(all)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        val (amt, cur) = when { usd != null -> usd to "USD"; rs != null -> rs to "PKR"; else -> return }
        val key = "n:" + (pkg + all).hashCode()
        val container = (c.applicationContext as? com.nizam.app.NizamApplication)?.container
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + com.nizam.app.feature.safe.CoroutineSafetyNet.inScope).launch {
            addIncome(c, container, platform.replaceFirstChar { it.uppercase() }, streamFor(low), amt, cur, note = all.take(160), auto = true, key = key)
        }
    }

    /** Reads credit SMS (only when switched on). Known platforms go straight in; other credits wait in "To sort" so salary never counts as earnings. */
    suspend fun scanSms(c: Context, container: AppContainer) {
        if (!radarSms(c)) return
        val since = System.currentTimeMillis() - 3 * 86_400_000L
        runCatching {
            c.contentResolver.query(android.net.Uri.parse("content://sms/inbox"), arrayOf("_id", "body", "date"), "date >= ?", arrayOf("$since"), "date DESC")?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0); val body = cur.getString(1).orEmpty()
                    val key = "s:$id"
                    if (income(c).all().any { it.optString("key") == key } || p(c).getStringSet("sorted", emptySet())!!.contains(key)) continue
                    val t = com.nizam.app.feature.money.BankSms.parse(body, cur.getLong(2), id) ?: continue
                    if (t.kind != "INCOME" || NOT_EARN.containsMatchIn(body)) continue
                    val low = body.lowercase()
                    val platform = PLATFORMS.firstOrNull { low.contains(it) }
                    if (platform != null) addIncome(c, container, platform.replaceFirstChar { it.uppercase() }, streamFor(low), t.amount, "PKR", note = body.take(160), auto = true, key = key)
                    else addIncome(c, null, t.merchant.ifBlank { "Credit" }, "Other", t.amount, "PKR", status = "unsorted", note = body.take(160), auto = true, key = key)
                }
            }
        }
    }

    /** Gumroad sales through its official API, with a token the owner pastes once. */
    suspend fun syncGumroad(c: Context, container: AppContainer): Int {
        val token = gumroad(c); if (token.isBlank()) return 0
        val j = JSONObject(Http.getJson("https://api.gumroad.com/v2/sales?access_token=" + java.net.URLEncoder.encode(token, "UTF-8")))
        val sales = j.optJSONArray("sales") ?: JSONArray()
        var n = 0
        for (i in 0 until sales.length()) {
            val s = sales.optJSONObject(i) ?: continue
            val cents = s.optDouble("price", 0.0)
            val amt = cents / 100.0
            if (addIncome(c, container, "Gumroad: " + s.optString("product_name").take(40), "Products", amt, "USD", note = "Sale", auto = true, key = "g:" + s.optString("id")) != null) n++
        }
        if (n > 0) log(c, "🛒", "$n new Gumroad sale${if (n > 1) "s" else ""}")
        return n
    }

    // ───────────── the overnight engine ─────────────
    private fun did(c: Context, step: String) = p(c).getString("did_$step", "") == LocalDate.now().toString()
    private fun mark(c: Context, step: String) = p(c).edit().putString("did_$step", LocalDate.now().toString()).apply()

    suspend fun huntGigs(container: AppContainer, c: Context): Int {
        val raw = Ai.generate(container.settingsRepository, """Find 6 real, currently open paid work opportunities for a freelancer in Lahore, Pakistan with these skills: ${skills(c)}.
Look at freelance boards, remote job boards, LinkedIn posts and Pakistani job sites. Prefer remote, paid in USD or PKR, doable on a phone + cloud computer.
Output only JSON: {"jobs":[{"title":"","client":"","platform":"","link":"https://…","pay":0,"cur":"USD|PKR","brief":"what they need, 2-3 lines","score":1-10,"why":"why it fits"}]}""",
            system = "You are a sharp freelance scout. Only real listings you found; never invent links. Output only JSON.", maxOutputTokens = 1800, useWeb = true)
        val jobs = com.nizam.app.feature.nx.EngineX.json(raw)?.optJSONArray("jobs") ?: return 0
        val existing = work(c).all().map { it.optString("title").lowercase() }.toSet()
        var n = 0
        for (i in 0 until jobs.length()) {
            val o = jobs.optJSONObject(i) ?: continue
            if (o.optString("title").isBlank() || o.optString("title").lowercase() in existing) continue
            work(c).add(JSONObject().put("title", o.optString("title").take(90)).put("client", o.optString("client").take(60)).put("platform", o.optString("platform").take(40))
                .put("link", o.optString("link").takeIf { it.startsWith("http") }.orEmpty()).put("pay", o.optDouble("pay", 0.0)).put("cur", if (o.optString("cur") == "PKR") "PKR" else "USD")
                .put("brief", o.optString("brief").take(600)).put("score", o.optInt("score", 5)).put("why", o.optString("why").take(200)).put("stage", "lead").put("t", System.currentTimeMillis()))
            n++
        }
        if (n > 0) log(c, "🎯", "Found $n new gigs")
        return n
    }

    suspend fun writeProposal(container: AppContainer, c: Context, job: JSONObject): String {
        val r = Ai.generate(container.settingsRepository, "Write a winning, short freelance proposal for this job.\nJob: ${job.optString("title")}\nClient: ${job.optString("client")}\nBrief: ${job.optString("brief")}\n" +
            "My skills: ${skills(c)}. Based in Lahore, Pakistan.\nStructure: one line that shows I understood their exact need, 2-3 lines on how I'll do it, a relevant proof point, a clear price and timeline, and a simple question. Under 140 words. No fluff.",
            system = "You write freelance proposals that win. Natural, specific, confident.", maxOutputTokens = 500)
        work(c).update(job.optString("id")) { it.put("proposal", r.trim()) }
        log(c, "✍", "Proposal ready: ${job.optString("title").take(50)}")
        return r
    }

    suspend fun doWork(container: AppContainer, c: Context, job: JSONObject): String {
        val r = Ai.generate(container.settingsRepository, "Do this paid job to a professional standard and deliver the finished work (not a plan).\nJob: ${job.optString("title")}\n" +
            "Client brief: ${job.optString("brief")}\nNotes from me: ${job.optString("notes")}\nIf it's translation, translate fully and accurately. If it's a dashboard or tool, give complete, working single-file HTML. " +
            "If it's writing or a report, give the finished document. End with a short 'Delivery note' to the client.",
            system = "You are an expert freelancer producing deliverables. Accurate, complete, no placeholders.", maxOutputTokens = 4000)
        work(c).update(job.optString("id")) { it.put("deliverable", r).put("stage", "ready").put("ready_t", System.currentTimeMillis()) }
        log(c, "✅", "Deliverable ready: ${job.optString("title").take(50)}")
        return r
    }

    suspend fun makeProduct(container: AppContainer, c: Context, type: String? = null, idea: String = ""): JSONObject? {
        val kind = type ?: PRODUCT_TYPES[(LocalDate.now().toEpochDay() % 5).toInt()]
        val have = products(c).all().joinToString("; ") { it.optString("title") }.take(800)
        val raw = Ai.generate(container.settingsRepository, """Create one sellable digital product of this kind: $kind. ${if (idea.isNotBlank()) "Idea from the owner: $idea." else "Pick something with real demand that a buyer in the US, UK or Pakistan would pay for."}
Not already made: $have
The owner's strengths: ${skills(c)}.
Output only JSON: {"title":"","tagline":"","price":0,"cur":"USD","platforms":["Gumroad","Etsy","Payhip","Adobe Stock","Amazon KDP","Play Store","RapidAPI","Chrome Web Store"],
"listing":"a full store listing: description, what's included, who it's for, 5 bullet benefits","keywords":"comma separated",
"content":"THE PRODUCT ITSELF: for a template give complete single-file HTML (or CSV); for a planner give the full page-by-page interior; for stock images give 10 detailed image prompts with titles and keywords; for a design give the design brief and exact text; for an app, API or extension give a precise build spec; for a course give the full outline and lesson scripts",
"launch":"3 short launch posts for social media"}""",
            system = "You are a top digital-product creator. Make it genuinely useful and complete. Output only JSON.", maxOutputTokens = 6000)
        val o = com.nizam.app.feature.nx.EngineX.json(raw) ?: return null
        if (o.optString("title").isBlank()) return null
        val plats = o.optJSONArray("platforms") ?: JSONArray()
        val rec = products(c).add(JSONObject().put("type", kind).put("title", o.optString("title").take(80)).put("tagline", o.optString("tagline").take(140))
            .put("price", o.optDouble("price", 9.0)).put("cur", o.optString("cur", "USD")).put("platforms", plats).put("listing", o.optString("listing"))
            .put("keywords", o.optString("keywords")).put("content", o.optString("content")).put("launch", o.optString("launch")).put("stage", "built")
            .put("sales", 0).put("revenue", 0.0).put("t", System.currentTimeMillis()))
        log(c, "📦", "New product built: ${o.optString("title").take(50)}")
        return rec
    }

    suspend fun coach(container: AppContainer, c: Context): String {
        val s = stats(c)
        val w = work(c).all(); val pr = products(c).all()
        val r = Ai.generate(container.settingsRepository, "My earning numbers. This month: ${fmt(s.month)} (goal ${fmt(goal(c))}). Last month: ${fmt(s.lastMonth)}. By stream: " +
            s.byStream.entries.joinToString { "${it.key} ${fmt(it.value)}" } + ". Pipeline: " + STAGES.joinToString { st -> "${STAGE_LABEL[st]} ${w.count { it.optString("stage") == st }}" } +
            ". Products: ${pr.size} built, ${pr.count { it.optString("stage") == "published" }} published, ${pr.sumOf { it.optInt("sales") }} sales. Skills: ${skills(c)}.\n" +
            "As my earning coach: what's working, what to stop, what to raise prices on, the single best move this week, and one skill that would pay most. Short, specific, honest.",
            system = "You are a blunt, practical income coach for a Pakistani freelancer and product maker. No hype.", maxOutputTokens = 900)
        p(c).edit().putString("coach", r).putLong("coach_t", System.currentTimeMillis()).apply()
        log(c, "🧭", "Weekly coaching ready")
        return r
    }

    /** Every 15 minutes from the background loop. Night: hunt, write, work, build. Morning: report. */
    suspend fun tick(c: Context, container: AppContainer, now: Double) {
        runCatching { refreshRate(c) }
        runCatching { scanSms(c, container) }
        if (now in 0.0..23.99 && (System.currentTimeMillis() - p(c).getLong("gum_t", 0)) > 2 * 3_600_000L && gumroad(c).isNotBlank()) {
            p(c).edit().putLong("gum_t", System.currentTimeMillis()).apply(); runCatching { syncGumroad(c, container) }
        }
        if (nightOn(c) && now in 1.0..5.5) {
            // one step per pass, so the night's work is spread out and a failure never blocks the rest
            when {
                !did(c, "hunt") -> { mark(c, "hunt"); runCatching { huntGigs(container, c) } }
                !did(c, "proposals") -> { mark(c, "proposals")
                    work(c).all().filter { it.optString("stage") == "lead" && it.optString("proposal").isBlank() && it.optInt("score") >= 7 }
                        .sortedByDescending { it.optInt("score") }.take(3).forEach { runCatching { writeProposal(container, c, it) } } }
                !did(c, "work") -> { mark(c, "work")
                    work(c).all().filter { it.optString("stage") == "working" }.take(2).forEach { runCatching { doWork(container, c, it) } } }
                !did(c, "product") -> { mark(c, "product"); runCatching { makeProduct(container, c) } }
                LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY && !did(c, "coach") -> { mark(c, "coach"); runCatching { coach(container, c) } }
            }
        }
        if (now in 7.0..10.5 && !did(c, "report")) {
            mark(c, "report")
            val since = System.currentTimeMillis() - 12 * 3_600_000L
            val night = feed(c).all().filter { it.optLong("t") > since }
            val credited = income(c).all().filter { it.optLong("t") > since && it.optString("status") == "credited" }.sumOf { it.optDouble("pkr") }
            val s = stats(c)
            val ready = work(c).all().count { it.optString("stage") == "ready" || (it.optString("stage") == "lead" && it.optString("proposal").isNotBlank()) } +
                products(c).all().count { it.optString("stage") == "built" }
            if (night.isNotEmpty() || credited > 0)
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 470, "☀ Earn: ${if (credited > 0) "${fmt(credited)} credited overnight" else "your overnight report"}",
                    "${night.size} things done while you slept · $ready waiting for your tap · ${fmt(s.month)} this month (${(s.month / goal(c) * 100).toInt()}% of goal)", route = "nx/earn")
        }
    }
}
