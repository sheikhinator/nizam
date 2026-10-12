package com.nizam.app.feature.nx

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.nizam.app.feature.brain2.Calc
import com.nizam.app.feature.insight.ReadinessScreen
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.screens.hubs.Feature
import org.json.JSONObject
import java.time.LocalDate

private fun thisMonth() = LocalDate.now().toString().take(7)
private fun thisYear() = LocalDate.now().year.toString()
private fun date(o: JSONObject, k: String) = runCatching { LocalDate.parse(o.optString(k)) }.getOrNull()
private fun sumOf(l: List<JSONObject>, k: String) = l.sumOf { num(it.optString(k)) }

object Nx {
    private val LANG = listOf("English", "Urdu", "Roman Urdu")

    val all: List<Spec> by lazy { ai + chats + logs + calcs + links + Specials.all + Device.all + Studio.all + System2.all + com.nizam.app.feature.companion.PetWorld.all + com.nizam.app.feature.companion.PetExtras.all +
        com.nizam.app.feature.apps2.Reading.all + com.nizam.app.feature.apps2.Life2.all + com.nizam.app.feature.apps2.Makers.all +
        com.nizam.app.feature.apps2.Games2.all + com.nizam.app.feature.apps2.MailApp.all + com.nizam.app.feature.apps2.KeyboardApp.all + ThemeStudio.all + Experience.all + BrainLab.all + LifeExtras.all + AgentsHub.all + com.nizam.app.feature.wild.WildUi.all + com.nizam.app.feature.wild.AgentPlus.all + com.nizam.app.feature.wild.PhonePlus.all + com.nizam.app.feature.wild.PhonePlus2.all + com.nizam.app.feature.wild.FunDeen.all + BacklogFeatures.all + Mega.all + Mega2.all + SafeArrivalSpecs.all + Mega3.all + PlanEngineSpecs.all + MoneyDeskSpecs.all + MediaDeskSpecs.all + AiLabSpecs.all + com.nizam.app.feature.dynamic.ModuleViewsSpecs.all + com.nizam.app.feature.agent.AutoRulesSpecs.all + com.nizam.app.feature.town.TownExtrasSpecs.all + Mega4.all + Mega5.all + Mega6.all + Mega7.all + Mega8.all + Mega9.all + Mega10.all + Mega11.all + SelfCheckSpecs.all + Mega12.all + com.nizam.app.feature.modes.ModeSpecs.all + com.nizam.app.feature.modes.WizardSpecs.all + Mega13.all + Mega14.all + com.nizam.app.feature.platform2.Insight2Specs.all + com.nizam.app.feature.platform2.Platform1Specs.all + com.nizam.app.feature.platform2.Platform2Specs.all + com.nizam.app.feature.platform2.Platform3Specs.all + com.nizam.app.feature.agentic.AgenticSpecs.all + com.nizam.app.feature.agentic.LlmAppsSpecs.all + com.nizam.app.feature.agentic.Agents2Specs.all + com.nizam.app.feature.joytime.JoytimeSpecs.all + Mega15.all + Mega16.all + Mega17.all + Stage3.all + RoadsSpecs.all + Mega21.all + com.nizam.app.feature.chief.ChiefSpecs.all + com.nizam.app.feature.flows.FlowsSpecs.all + com.nizam.app.feature.appchat.AppChatSpecs.all + Mega22.all + Mega19.all + Mega20.all + com.nizam.app.feature.forecast.ForecastSpecs.all + com.nizam.app.feature.semantic.SemanticSpecs.all }

    fun find(id: String) = all.firstOrNull { it.id == id }
    fun route(id: String) = "nx/$id"
    fun features(): List<Feature> = all.map { Feature(route(it.id), it.title, it.blurb, it.icon, it.hub, words = it.words,
        group = when (it) { is AiSpec -> "Ask AI"; is ChatSpec -> "Talk to"; is LogSpec -> "Logs & trackers"; is CalcSpec -> "Calculators"
            is LinkSpec -> "Official links"; is ScreenSpec -> "Tools" }) }

    // ───────────────────────── write, think, learn ─────────────────────────
    private val ai = listOf(
        AiSpec("tone", "Tone translator", "Say the same thing politer, firmer or in Urdu", Icons.Outlined.Translate, Hub.Social, "rewrite polite firm tone message",
            listOf(F("text", "Your message", K.LONG), F("tone", "Make it", K.CHOICE, options = listOf("Polite", "Firm", "Friendly", "Formal", "Apologetic", "Shorter", "Roman Urdu", "Urdu"), default = "Polite")),
            "You rewrite messages. Keep the meaning, change only the tone. Give the rewrite first, then one alternative.") { v -> "Rewrite this to be ${v["tone"]}:\n\n${v["text"]}" },
        AiSpec("ghost", "Ghostwriter", "Posts, emails, speeches in your voice", Icons.Outlined.EditNote, Hub.Create, "write email post speech caption",
            listOf(F("what", "What should it say?", K.LONG), F("format", "Format", K.CHOICE, options = listOf("WhatsApp", "Email", "LinkedIn post", "Speech", "Caption", "Story"), default = "WhatsApp"),
                F("lang", "Language", K.CHOICE, options = LANG, default = "English")),
            "You are a skilled ghostwriter. Natural, specific, never generic. No hashtags unless it is a caption.") { v -> "Write a ${v["format"]} in ${v["lang"]}:\n${v["what"]}" },
        AiSpec("letters", "Letters & applications", "Leave, school, bank, complaint, NOC — ready to print", Icons.Outlined.Description, Hub.Work, "application letter leave principal bank noc complaint",
            listOf(F("kind", "Type", K.CHOICE, options = listOf("Leave application", "School application", "Bank letter", "Complaint", "NOC request", "Resignation", "Other"), default = "Leave application"),
                F("to", "To", hint = "The Principal, Beaconhouse School, Johar Town"), F("from", "From (name, details)"), F("details", "What it's about", K.LONG),
                F("lang", "Language", K.CHOICE, options = listOf("English", "Urdu"), default = "English")),
            "You write formal Pakistani letters and applications in the standard format (date, to, subject, salutation, body, closing). Tap PDF to print.") { v ->
            "Write a ${v["kind"]} in ${v["lang"]}.\nTo: ${v["to"]}\nFrom: ${v["from"]}\nDetails: ${v["details"]}\nToday's date: ${LocalDate.now()}" },
        AiSpec("cv", "CV maker", "A clean one-page CV, ready as PDF", Icons.Outlined.Badge, Hub.Work, "resume cv job",
            listOf(F("name", "Name and contact"), F("job", "Job you're applying for"), F("exp", "Experience", K.LONG, "Where, when, what you did"),
                F("edu", "Education", K.LONG), F("skills", "Skills", optional = true)),
            "You write concise, achievement-focused one-page CVs in Markdown. Strong verbs, numbers where given, no invented facts.", tokens = 1800) { v ->
            "CV for: ${v["name"]}\nTarget role: ${v["job"]}\nExperience:\n${v["exp"]}\nEducation:\n${v["edu"]}\nSkills: ${v["skills"]}" },
        AiSpec("jobs", "Job finder", "Current openings that fit you", Icons.Outlined.Work, Hub.Work, "jobs vacancies rozee hiring",
            listOf(F("role", "Role"), F("city", "City", default = "Lahore"), F("level", "Level", K.CHOICE, options = listOf("Fresh", "1–3 years", "3–7 years", "Senior", "Remote"), default = "1–3 years")),
            "You find real, current job openings. List each with company, title, where it's posted and a link. Say if you couldn't verify one.", web = true, button = "Find jobs") { v ->
            "Find current ${v["level"]} job openings for ${v["role"]} in ${v["city"]}, Pakistan (Rozee, LinkedIn, company sites). Also give 3 tips to stand out for this role." },
        AiSpec("cards", "Greeting cards", "Eid, birthday, wedding and thank-you messages", Icons.Outlined.CardGiftcard, Hub.Social, "eid mubarak birthday wish message card",
            listOf(F("occasion", "Occasion", K.CHOICE, options = listOf("Eid", "Birthday", "Wedding", "Ramadan", "Congratulations", "Thank you", "Condolence", "Get well"), default = "Eid"),
                F("to", "For whom", hint = "Chachu, a colleague, my wife"), F("lang", "Language", K.CHOICE, options = LANG, default = "English")),
            "You write warm, personal messages. Offer 3 options: short, medium, heartfelt.") { v -> "${v["occasion"]} message for ${v["to"]} in ${v["lang"]}." },
        AiSpec("movie", "Movie picker", "Tonight's watch, decided", Icons.Outlined.Movie, Hub.Media, "film series watch recommend netflix",
            listOf(F("mood", "Mood", K.CHOICE, options = listOf("Laugh", "Thrill", "Cry", "Think", "Scared", "Family", "Romance"), default = "Thrill"),
                F("with", "Watching with", K.CHOICE, options = listOf("Alone", "Partner", "Family", "Friends"), default = "Family"), F("like", "Something you loved", optional = true)),
            "You recommend films and series. 5 picks, each with year, why it fits, and where it streams in Pakistan if known. Family picks must be clean.", web = true, button = "Pick for me") { v ->
            "Mood: ${v["mood"]}. Watching with: ${v["with"]}. Loved: ${v["like"]}." },
        AiSpec("event", "Event planner", "Dholki, birthday, dawat: plan, budget, timeline", Icons.Outlined.Celebration, Hub.Social, "party wedding dawat mehndi plan budget",
            listOf(F("what", "Event"), F("guests", "Guests", K.NUMBER), F("budget", "Budget (Rs)", K.NUMBER), F("when", "When", optional = true), F("city", "City", default = "Lahore")),
            "You are an organised Pakistani event planner. Give: timeline, a checklist with owners, a budget split in rupees that adds up exactly, menu, and 3 money-saving tips.", tokens = 1800) { v ->
            "Plan a ${v["what"]} for ${v["guests"]} guests with a budget of Rs ${v["budget"]} in ${v["city"]}, ${v["when"]}." },
        AiSpec("pastpaper", "Past-paper coach", "Exam-style questions with marking schemes", Icons.Outlined.School, Hub.Learn, "exam matric fsc mdcat o level practice questions",
            listOf(F("board", "Exam", K.CHOICE, options = listOf("Matric", "FSc", "O Level", "A Level", "MDCAT", "ECAT", "CSS"), default = "Matric"), F("subject", "Subject"), F("topic", "Topic")),
            "You are an experienced examiner. Write 8 exam-style questions (mix of MCQ, short and long), then a marking scheme and common mistakes. Match the exam's real style.", tokens = 2000) { v ->
            "${v["board"]} ${v["subject"]}: ${v["topic"]}" },
        AiSpec("overview", "Audio overview", "Turn notes into a two-voice explainer you can listen to", Icons.Outlined.RecordVoiceOver, Hub.Learn, "podcast listen explain notes notebooklm",
            listOf(F("text", "Paste notes, an article or a chapter", K.LONG)),
            "You turn material into a lively two-host conversation (Host A and Host B) that teaches it clearly. 5 minutes of speech. Accurate to the source.", tokens = 2000, button = "Make overview") { v -> v["text"].orEmpty() },
        AiSpec("incubator", "Idea incubator", "Stress-test an idea, then the first 3 steps", Icons.Outlined.Lightbulb, Hub.Work, "business startup idea validate",
            listOf(F("idea", "Your idea", K.LONG)),
            "You are a sharp, kind startup mentor familiar with Pakistan. Cover: who needs it, why now, biggest risk, cheapest test this week, first 3 steps, a rough cost in rupees.") { v -> v["idea"].orEmpty() },
        AiSpec("collide", "Idea collider", "Smash two fields together for new ideas", Icons.Outlined.Science, Hub.Create, "brainstorm creative combine",
            listOf(F("a", "Field or thing A"), F("b", "Field or thing B")),
            "You generate genuinely novel, specific ideas by combining two domains. 6 ideas, each one line plus why it could work.") { v -> "Combine ${v["a"]} with ${v["b"]}." },
        AiSpec("eli", "Explain like I'm…", "Any topic at the level you need", Icons.Outlined.Psychology, Hub.Learn, "explain simple eli5",
            listOf(F("topic", "Topic"), F("level", "Level", K.CHOICE, options = listOf("5 years old", "Teenager", "Student", "Expert"), default = "Teenager"), F("lang", "Language", K.CHOICE, options = LANG, default = "English")),
            "You explain with vivid everyday examples (Pakistani where natural). Accurate, then a one-line summary.") { v -> "Explain ${v["topic"]} to a ${v["level"]} in ${v["lang"]}." },
        AiSpec("selfdebate", "Self-debate", "The best case for and against, then a verdict", Icons.Outlined.Gavel, Hub.Work, "argue pros cons decide",
            listOf(F("q", "Question or decision", K.LONG)),
            "Argue both sides at full strength (For, Against), then a Judge weighs them and gives a clear verdict and what would change it.") { v -> v["q"].orEmpty() },
        AiSpec("second", "Second opinion", "A careful second look at a diagnosis, contract or quote", Icons.Outlined.FactCheck, Hub.Health, "doctor lawyer quote contract review",
            listOf(F("what", "What you were told or given", K.LONG), F("area", "Area", K.CHOICE, options = listOf("Medical", "Legal", "Money", "Repair quote", "Other"), default = "Medical")),
            "You give a careful second opinion: what seems right, what to double-check, questions to ask the professional, red flags. You never replace a professional; say when to see one urgently.") { v ->
            "${v["area"]}: ${v["what"]}" },
        AiSpec("gift", "Gift genius", "Gift ideas that fit, with where to buy", Icons.Outlined.CardGiftcard, Hub.Social, "present birthday wedding gift",
            listOf(F("who", "For whom"), F("likes", "What they like"), F("budget", "Budget (Rs)", K.NUMBER), F("occasion", "Occasion", optional = true)),
            "You suggest thoughtful, specific gifts available in Pakistan (Daraz, local shops), within budget, with a personal touch idea for each.", web = true) { v ->
            "Gift for ${v["who"]} who likes ${v["likes"]}, budget Rs ${v["budget"]}, occasion ${v["occasion"]}." },
        AiSpec("poetry", "Urdu poetry", "Original ghazals, nazms and couplets", Icons.Outlined.HistoryEdu, Hub.Create, "shayari ghazal sher poem",
            listOf(F("theme", "Theme"), F("form", "Form", K.CHOICE, options = listOf("Sher (couplet)", "Ghazal", "Nazm", "Roman Urdu"), default = "Sher (couplet)")),
            "You are a gifted Urdu poet. Write original poetry in Urdu script (or Roman Urdu if asked), respecting the form's metre and radeef/qafia, then a one-line English meaning.") { v ->
            "Write a ${v["form"]} about ${v["theme"]}." },
        AiSpec("dreamread", "Dream reader", "Reflect on a dream: themes and feelings", Icons.Outlined.NightsStay, Hub.Memory, "dream meaning khwab",
            listOf(F("dream", "The dream", K.LONG)),
            "You help people reflect on dreams: symbols, feelings, what might be on their mind. Gentle and humble; never predict the future. You may mention traditional Islamic etiquette for good and bad dreams.") { v -> v["dream"].orEmpty() },
        AiSpec("exercise", "Exercise library", "Moves for any muscle, with form cues", Icons.Outlined.FitnessCenter, Hub.Health, "workout gym exercises home",
            listOf(F("target", "Target", hint = "Lower back, chest, posture…"), F("kit", "Equipment", K.CHOICE, options = listOf("None", "Dumbbells", "Gym", "Resistance band"), default = "None"),
                F("level", "Level", K.CHOICE, options = listOf("Beginner", "Intermediate", "Advanced"), default = "Beginner")),
            "You are a careful physio-minded coach. 6 exercises with sets, reps, form cues and a common mistake each. Add a safety note.") { v -> "${v["target"]}, equipment: ${v["kit"]}, level ${v["level"]}." },
        AiSpec("recipes", "What can I cook?", "Recipes from what's in the kitchen", Icons.Outlined.Restaurant, Hub.Home, "recipe cook khana ingredients",
            listOf(F("have", "Ingredients you have", K.LONG), F("style", "Style", K.CHOICE, options = listOf("Desi", "Chinese", "Continental", "Quick", "Healthy"), default = "Desi"), F("people", "People", K.NUMBER, default = "4")),
            "You are a practical home cook. 3 recipes using mostly what's listed, with quantities for the number of people, steps and time.") { v ->
            "I have: ${v["have"]}. Style: ${v["style"]}. For ${v["people"]} people." },
        AiSpec("post", "Post maker", "Hook, caption, hashtags and thumbnail text", Icons.Outlined.Campaign, Hub.Create, "instagram tiktok youtube caption hashtags thumbnail creator",
            listOf(F("topic", "What's the post about", K.LONG), F("platform", "Platform", K.CHOICE, options = listOf("Instagram", "TikTok", "YouTube", "Facebook", "LinkedIn"), default = "Instagram"),
                F("lang", "Language", K.CHOICE, options = LANG, default = "English")),
            "You are a creator coach. Give: 3 hooks, the caption, 12 hashtags, thumbnail text (max 5 words), best time to post in Pakistan.") { v -> "${v["platform"]} post in ${v["lang"]}: ${v["topic"]}" },
        AiSpec("scores", "Live scores", "PSL, Pakistan cricket and football, right now", Icons.Outlined.SportsCricket, Hub.Media, "psl cricket score match live",
            listOf(F("sport", "Sport", K.CHOICE, options = listOf("Cricket", "PSL", "Football", "Hockey"), default = "Cricket"), F("team", "Team or match", optional = true)),
            "You report live and recent scores accurately with the time they were updated. Then upcoming fixtures in Pakistan time.", web = true, button = "Check") { v ->
            "Latest ${v["sport"]} scores ${v["team"]} — live now, today's results, and next fixtures (PKT)." },
        AiSpec("fuel", "Petrol & prices", "Today's petrol, diesel, LPG and dollar in Pakistan", Icons.Outlined.LocalGasStation, Hub.Money, "petrol diesel lpg price dollar",
            listOf(F("city", "City", default = "Lahore", optional = true)),
            "You report current official Pakistani prices with the notification date and source. Then when the next revision is expected.", web = true, button = "Check prices") { v ->
            "Current petrol, high-speed diesel, LPG per kg and open-market dollar rate in Pakistan (${v["city"]})." },
        AiSpec("wasiyat", "Wasiyat & inheritance", "Draft a will and see Islamic shares", Icons.Outlined.Gavel, Hub.Deen, "will inheritance wirasat heirs shares",
            listOf(F("heirs", "Surviving heirs", K.LONG, "Wife, 2 sons, 1 daughter, mother"), F("assets", "Assets and debts", K.LONG, optional = true), F("wishes", "Bequests (up to 1/3)", K.LONG, optional = true)),
            "You explain Islamic inheritance (Hanafi fiqh by default) with fractions and percentages that add up, and draft a simple wasiyat. Always recommend confirming with a mufti and a lawyer.", tokens = 1800) { v ->
            "Heirs: ${v["heirs"]}\nAssets: ${v["assets"]}\nWishes: ${v["wishes"]}" },
        ScreenSpec("readiness", "Readiness", "Push, maintain, or recover from your own recent baseline", Icons.Outlined.Bedtime, Hub.Health,
            "sleep recovery energy personal baseline") { container, _ -> ReadinessScreen(container) },
        AiSpec("labels", "Label decoder", "What's really in it: E-numbers, halal, allergens", Icons.Outlined.Science, Hub.Health, "ingredients e number halal allergy",
            listOf(F("text", "Paste the ingredients list", K.LONG)),
            "You explain food and cosmetic ingredients plainly: each additive, halal concerns (gelatin, E120, E471 sources, alcohol), allergens, and an overall verdict.") { v -> v["text"].orEmpty() },
        AiSpec("timetravel", "Time-travel answers", "What was true then: prices, events, rules on a date", Icons.Outlined.History, Hub.Learn, "history past price date",
            listOf(F("q", "Question"), F("when", "When", hint = "March 2019, my birth year…")),
            "You answer historically: what was true at that time, citing sources, and how it compares to now.", web = true) { v -> "${v["q"]} — as of ${v["when"]}" },
    )

    // ───────────────────────── conversations ─────────────────────────
    private val chats = listOf(
        ChatSpec("mentor", "Mentors", "Talk with a great mind from history", Icons.Outlined.HistoryEdu, Hub.Learn, "iqbal jinnah ibn khaldun einstein history",
            listOf(F("who", "Who", hint = "Allama Iqbal, Ibn Khaldun, Quaid-e-Azam, Al-Khwarizmi…")), opener = "Assalam o alaikum. Please introduce yourself and ask what I'm working on.") { v ->
            "You are ${v["who"]}, speaking as they would, drawing on their actual works, era and views. When you speculate beyond the record, say so briefly." },
        ChatSpec("book", "Book chat", "Discuss any book with someone who's read it closely", Icons.Outlined.MenuBook, Hub.Learn, "book discuss reading",
            listOf(F("title", "Book")), opener = "Let's talk about {0}. Start with a question for me.") { v ->
            "You are a thoughtful reading partner who knows '${v["title"]}' well. Discuss themes, characters and ideas; avoid spoilers unless the user says they've finished." },
        ChatSpec("expert", "Instant expert", "An expert in anything, with current sources", Icons.Outlined.Psychology, Hub.Learn, "ask expert advice",
            listOf(F("field", "Field", hint = "Solar systems, property law in Punjab, dog training…")), web = true, opener = "I need help with {0}. Ask me what my situation is.") { v ->
            "You are a top practitioner in ${v["field"]} who explains practically for someone in Pakistan. Ask clarifying questions, cite sources for facts." },
        ChatSpec("interview", "Reverse interview", "Practise interviews; get scored", Icons.Outlined.Work, Hub.Work, "interview practice job mock",
            listOf(F("role", "Role you're interviewing for"), F("company", "Company", optional = true)), opener = "Start the interview.") { v ->
            "You are a demanding but fair interviewer for ${v["role"]} ${v["company"]}. Ask one question at a time. After each answer give a score out of 10, what was good, and a stronger answer." },
        ChatSpec("story", "Interactive story", "You choose what happens next", Icons.Outlined.AutoStories, Hub.Media, "adventure game story kids",
            listOf(F("genre", "Genre", K.CHOICE, options = listOf("Adventure", "Mystery", "Fantasy", "Sci-fi", "Kids", "Horror (mild)"), default = "Adventure"),
                F("hero", "Hero's name", default = "Zara")), opener = "Begin the story.", persistent = true) { v ->
            "You narrate an interactive ${v["genre"]} story starring ${v["hero"]}. 120 words per turn, vivid, then offer 3 numbered choices. Keep it clean." },
        ChatSpec("tutor", "Homework tutor", "Guides you to the answer instead of giving it", Icons.Outlined.School, Hub.Learn, "homework study help socratic",
            listOf(F("subject", "Subject"), F("grade", "Class", default = "9")), opener = "I need help with {0}.") { v ->
            "You are a patient Socratic tutor for class ${v["grade"]} ${v["subject"]} (Pakistani curriculum). Never just give the answer; guide step by step with hints and check understanding." },
    )

    // ───────────────────────── ledgers and logs ─────────────────────────
    private val logs = listOf(
        LogSpec("shopkhata", "Shop khata", "Customers' udhaar and payments, balance per person", Icons.Outlined.Store, Hub.Money, "khata udhaar customer shop credit ledger",
            listOf(F("who", "Customer"), F("kind", "Entry", K.CHOICE, options = listOf("Udhaar given", "Payment received"), default = "Udhaar given"), F("amt", "Amount", K.NUMBER),
                F("note", "Items / note", optional = true), F("d", "Date", K.DATE)),
            line = { o -> o.optString("who") to "${if (o.optString("kind").startsWith("Pay")) "+" else "−"} ${rs(num(o.optString("amt")))} · ${o.optString("d")} ${o.optString("note")}" },
            summary = { l ->
                val bal = l.groupBy { it.optString("who").trim().lowercase() }.mapValues { (_, e) -> e.sumOf { if (it.optString("kind").startsWith("Pay")) -num(it.optString("amt")) else num(it.optString("amt")) } }
                listOf("Outstanding: ${rs(bal.values.filter { it > 0 }.sum())}") + bal.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(6).map { "${it.key.replaceFirstChar { c -> c.uppercase() }}: ${rs(it.value)}" }
            }),
        LogSpec("salami", "Salami ledger", "Eidi and shagun given and received", Icons.Outlined.Redeem, Hub.Money, "eidi salami shagun gift money",
            listOf(F("who", "Person"), F("kind", "", K.CHOICE, options = listOf("Given", "Received"), default = "Given"), F("amt", "Amount", K.NUMBER),
                F("occ", "Occasion", K.CHOICE, options = listOf("Eid ul Fitr", "Eid ul Adha", "Wedding", "Birth", "Other"), default = "Eid ul Fitr"), F("yr", "Year", K.NUMBER, default = thisYear())),
            line = { o -> o.optString("who") to "${o.optString("kind")} ${rs(num(o.optString("amt")))} · ${o.optString("occ")} ${o.optString("yr")}" },
            summary = { l -> val y = l.filter { it.optString("yr") == thisYear() }
                listOf("This year: given ${rs(sumOf(y.filter { it.optString("kind") == "Given" }, "amt"))}, received ${rs(sumOf(y.filter { it.optString("kind") == "Received" }, "amt"))}") }),
        LogSpec("rent", "Rent", "Monthly rent: paid, due, receipts", Icons.Outlined.Home, Hub.Home, "rent landlord tenant kiraya",
            listOf(F("m", "Month", default = thisMonth()), F("amt", "Amount", K.NUMBER), F("st", "Status", K.CHOICE, options = listOf("Paid", "Due"), default = "Paid"),
                F("d", "Paid on", K.DATE, optional = true), F("note", "Note", optional = true)),
            line = { o -> "${o.optString("m")} · ${rs(num(o.optString("amt")))}" to "${o.optString("st")} ${o.optString("d")} ${o.optString("note")}" },
            summary = { l -> val due = l.filter { it.optString("st") == "Due" }
                listOf(if (due.isEmpty()) "All paid" else "Due: ${rs(sumOf(due, "amt"))} (${due.joinToString { it.optString("m") }})", "Paid this year: ${rs(sumOf(l.filter { it.optString("st") == "Paid" && it.optString("m").startsWith(thisYear()) }, "amt"))}") }),
        LogSpec("pricebook", "Price book", "What things cost at which shop; cheapest wins", Icons.Outlined.Sell, Hub.Money, "grocery price compare shop cheapest",
            listOf(F("item", "Item"), F("shop", "Shop"), F("price", "Price", K.NUMBER), F("unit", "Per", hint = "kg, dozen, pack", optional = true)),
            line = { o -> o.optString("item") to "${rs(num(o.optString("price")))} ${o.optString("unit").let { if (it.isBlank()) "" else "/ $it" }} at ${o.optString("shop")}" },
            summary = { l -> l.groupBy { it.optString("item").trim().lowercase() }.filter { it.value.size > 1 }.map { (item, e) ->
                val best = e.minBy { num(it.optString("price")) }; "Cheapest $item: ${rs(num(best.optString("price")))} at ${best.optString("shop")}" }.take(8).ifEmpty { listOf("Add the same item at two shops to compare") } }),
        LogSpec("ration", "Ration budget", "Monthly groceries against your budget", Icons.Outlined.ShoppingCart, Hub.Money, "grocery ration monthly budget sauda",
            listOf(F("item", "Item"), F("qty", "Quantity", optional = true), F("cost", "Cost", K.NUMBER), F("m", "Month", default = thisMonth())),
            line = { o -> o.optString("item") to "${rs(num(o.optString("cost")))} · ${o.optString("qty")} · ${o.optString("m")}" },
            summary = { l -> val now = thisMonth(); val prev = LocalDate.now().minusMonths(1).toString().take(7)
                listOf("This month: ${rs(sumOf(l.filter { it.optString("m") == now }, "cost"))}", "Last month: ${rs(sumOf(l.filter { it.optString("m") == prev }, "cost"))}") }),
        LogSpec("genlog", "Generator & UPS log", "Hours, fuel and running cost", Icons.Outlined.ElectricBolt, Hub.Home, "generator ups fuel petrol hours solar",
            listOf(F("d", "Date", K.DATE), F("hrs", "Hours run", K.NUMBER), F("fuel", "Fuel (litres)", K.NUMBER, optional = true), F("cost", "Cost", K.NUMBER, optional = true)),
            line = { o -> o.optString("d") to "${o.optString("hrs")} h · ${o.optString("fuel")} L · ${rs(num(o.optString("cost")))}" },
            summary = { l -> val m = l.filter { it.optString("d").startsWith(thisMonth()) }; val h = sumOf(m, "hrs"); val c = sumOf(m, "cost")
                listOf("This month: ${Calc.fmt(h)} hours, ${rs(c)}", if (h > 0) "Cost per hour: ${rs(c / h)}" else "") }),
        LogSpec("upkeep", "Home maintenance", "Services done and what's due next", Icons.Outlined.Handyman, Hub.Home, "ac service plumber repair maintenance tank cleaning",
            listOf(F("task", "Task", hint = "AC service, tank cleaning"), F("d", "Done on", K.DATE), F("cost", "Cost", K.NUMBER, optional = true), F("next", "Next due", K.DATE, optional = true)),
            line = { o -> o.optString("task") to "Done ${o.optString("d")} · ${rs(num(o.optString("cost")))}${o.optString("next").let { if (it.isBlank()) "" else " · next $it" }}" },
            summary = { l -> l.mapNotNull { o -> date(o, "next")?.let { it to o.optString("task") } }.sortedBy { it.first }.take(4).map { (d, t) ->
                val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), d); "$t: ${if (days < 0) "overdue ${-days} days" else "in $days days"}" }.ifEmpty { listOf("Add a next-due date to see what's coming") } }),
        LogSpec("sadqa", "Sadqa & giving", "Sadqa, zakat, fitrana and donations by year", Icons.Outlined.VolunteerActivism, Hub.Deen, "charity sadqa zakat fitrana donation khairat",
            listOf(F("amt", "Amount", K.NUMBER), F("to", "To", optional = true), F("kind", "Kind", K.CHOICE, options = listOf("Sadqa", "Zakat", "Fitrana", "Donation", "Qurbani"), default = "Sadqa"), F("d", "Date", K.DATE)),
            line = { o -> "${o.optString("kind")} · ${rs(num(o.optString("amt")))}" to "${o.optString("to")} · ${o.optString("d")}" },
            summary = { l -> val y = l.filter { it.optString("d").startsWith(thisYear()) }
                listOf("This year: ${rs(sumOf(y, "amt"))}") + y.groupBy { it.optString("kind") }.map { (k, e) -> "$k: ${rs(sumOf(e, "amt"))}" } }),
        LogSpec("school", "School marks", "Tests and results per child", Icons.Outlined.School, Hub.Learn, "marks result test report card kids",
            listOf(F("child", "Child"), F("subject", "Subject"), F("test", "Test", hint = "Monthly test, Mid-term"), F("marks", "Marks", K.NUMBER), F("total", "Out of", K.NUMBER, default = "100")),
            line = { o -> val p = num(o.optString("marks")) / num(o.optString("total")).coerceAtLeast(1.0) * 100
                "${o.optString("child")} · ${o.optString("subject")}" to "${o.optString("test")}: ${o.optString("marks")}/${o.optString("total")} (${p.toInt()}%)" },
            summary = { l -> l.groupBy { it.optString("child") }.map { (c, e) ->
                val avg = e.map { num(it.optString("marks")) / num(it.optString("total")).coerceAtLeast(1.0) * 100 }.average()
                val weakest = e.groupBy { it.optString("subject") }.minByOrNull { (_, x) -> x.map { num(it.optString("marks")) / num(it.optString("total")).coerceAtLeast(1.0) }.average() }?.key
                "$c: average ${avg.toInt()}%${if (weakest != null) " · needs help in $weakest" else ""}" } }),
        LogSpec("birthdays", "Birthdays & anniversaries", "Never miss one; Atlas reminds you on the day", Icons.Outlined.Cake, Hub.Social, "birthday anniversary salgirah reminder",
            listOf(F("name", "Name"), F("d", "Date", K.DATE, hint = "yyyy-mm-dd (any year)"), F("kind", "", K.CHOICE, options = listOf("Birthday", "Anniversary", "Other"), default = "Birthday")),
            line = { o -> o.optString("name") to "${o.optString("kind")} · ${date(o, "d")?.let { "${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }}" } ?: o.optString("d")}" },
            summary = { l -> Specials.upcoming(l).take(4).map { (o, days) -> "${o.optString("name")}'s ${o.optString("kind").lowercase()}: ${if (days == 0L) "today!" else "in $days days"}" } }),
        LogSpec("chores", "Chores", "Who does what, and what's overdue", Icons.Outlined.CleaningServices, Hub.Home, "chores cleaning family tasks rota",
            listOf(F("chore", "Chore"), F("who", "Who"), F("every", "Every (days)", K.NUMBER, default = "7"), F("last", "Last done", K.DATE)),
            line = { o -> o.optString("chore") to "${o.optString("who")} · every ${o.optString("every")} days · last ${o.optString("last")}" },
            summary = { l -> l.mapNotNull { o -> date(o, "last")?.plusDays(num(o.optString("every")).toLong())?.let { o to it } }
                .filter { !it.second.isAfter(LocalDate.now()) }.map { (o, _) -> "Due: ${o.optString("chore")} (${o.optString("who")})" }.ifEmpty { listOf("Nothing overdue") } }),
        LogSpec("loyalty", "Loyalty cards", "Card numbers in one place", Icons.Outlined.CreditCard, Hub.Money, "loyalty card membership points",
            listOf(F("store", "Store"), F("no", "Card number"), F("note", "Note", optional = true)),
            line = { o -> o.optString("store") to "${o.optString("no")} ${o.optString("note")}" }),
        LogSpec("pantry", "Pantry", "What's at home and what's expiring", Icons.Outlined.Kitchen, Hub.Home, "pantry fridge expiry food stock",
            listOf(F("item", "Item"), F("qty", "Quantity", optional = true), F("exp", "Expires", K.DATE, optional = true)),
            line = { o -> o.optString("item") to "${o.optString("qty")}${o.optString("exp").let { if (it.isBlank()) "" else " · expires $it" }}" },
            summary = { l -> l.mapNotNull { o -> date(o, "exp")?.let { o to it } }.filter { it.second.isBefore(LocalDate.now().plusDays(8)) }
                .sortedBy { it.second }.map { (o, d) -> "Use soon: ${o.optString("item")} (${d})" }.ifEmpty { listOf("Nothing expiring this week") } }),
        LogSpec("networth", "Net worth", "Everything you own minus everything you owe", Icons.Outlined.AccountBalance, Hub.Money, "assets liabilities wealth net worth",
            listOf(F("name", "Name", hint = "Plot, gold, car, bank, loan"), F("kind", "", K.CHOICE, options = listOf("Asset", "Liability"), default = "Asset"), F("amt", "Value", K.NUMBER)),
            line = { o -> o.optString("name") to "${o.optString("kind")} · ${rs(num(o.optString("amt")))}" },
            summary = { l -> val a = sumOf(l.filter { it.optString("kind") == "Asset" }, "amt"); val d = sumOf(l.filter { it.optString("kind") == "Liability" }, "amt")
                listOf("Net worth: ${rs(a - d)}", "Assets ${rs(a)} · Liabilities ${rs(d)}") }),
        LogSpec("envelopes", "Envelope budget", "Give every rupee a job; see what's left in each", Icons.Outlined.Mail, Hub.Money, "budget envelope cash category",
            listOf(F("env", "Envelope", hint = "Groceries, Fuel, Eating out"), F("kind", "", K.CHOICE, options = listOf("Budget", "Spent"), default = "Spent"), F("amt", "Amount", K.NUMBER),
                F("m", "Month", default = thisMonth())),
            line = { o -> o.optString("env") to "${o.optString("kind")} ${rs(num(o.optString("amt")))} · ${o.optString("m")}" },
            summary = { l -> l.filter { it.optString("m") == thisMonth() }.groupBy { it.optString("env").trim() }.map { (e, x) ->
                val b = sumOf(x.filter { it.optString("kind") == "Budget" }, "amt"); val s = sumOf(x.filter { it.optString("kind") == "Spent" }, "amt")
                "$e: ${rs(b - s)} left of ${rs(b)}" }.ifEmpty { listOf("Add a Budget entry for each envelope") } }),
        LogSpec("bills", "Bill calendar", "Which bill is due when; reminded the day before", Icons.Outlined.CalendarMonth, Hub.Money, "bills due electricity gas internet reminder",
            listOf(F("bill", "Bill"), F("day", "Due day of month", K.NUMBER), F("amt", "Usual amount", K.NUMBER, optional = true)),
            line = { o -> o.optString("bill") to "Due on the ${num(o.optString("day")).toInt()}th · ${rs(num(o.optString("amt")))}" },
            summary = { l -> val today = LocalDate.now().dayOfMonth
                l.sortedBy { (num(it.optString("day")).toInt() - today + 31) % 31 }.take(4).map { o ->
                    val d = (num(o.optString("day")).toInt() - today + 31) % 31; "${o.optString("bill")}: ${if (d == 0) "due today" else "in $d days"}" } }),
        LogSpec("goals", "Savings goals", "Put money towards goals; watch them fill", Icons.Outlined.Savings, Hub.Money, "save goal target hajj car wedding",
            listOf(F("goal", "Goal"), F("amt", "Added", K.NUMBER), F("target", "Target", K.NUMBER, optional = true)),
            line = { o -> o.optString("goal") to "+${rs(num(o.optString("amt")))}" },
            summary = { l -> l.groupBy { it.optString("goal").trim() }.map { (g, x) -> val s = sumOf(x, "amt"); val t = x.maxOf { num(it.optString("target")) }
                if (t > 0) "$g: ${rs(s)} of ${rs(t)} (${(s / t * 100).toInt()}%)" else "$g: ${rs(s)}" } }),
        LogSpec("side", "Side income", "Freelance, tuition, reselling: what it brings in", Icons.Outlined.TrendingUp, Hub.Money, "freelance tuition income earning fiverr upwork",
            listOf(F("src", "Source"), F("amt", "Amount (Rs)", K.NUMBER), F("d", "Date", K.DATE)),
            line = { o -> o.optString("src") to "${rs(num(o.optString("amt")))} · ${o.optString("d")}" },
            summary = { l -> listOf("This month: ${rs(sumOf(l.filter { it.optString("d").startsWith(thisMonth()) }, "amt"))}", "This year: ${rs(sumOf(l.filter { it.optString("d").startsWith(thisYear()) }, "amt"))}") }),
        LogSpec("scoreboard", "Life scoreboard", "Rate each part of life daily; see the trend", Icons.Outlined.Leaderboard, Hub.Memory, "score rating life balance wheel",
            listOf(F("d", "Date", K.DATE), F("deen", "Deen (1–10)", K.NUMBER), F("health", "Health", K.NUMBER), F("work", "Work", K.NUMBER), F("family", "Family", K.NUMBER), F("money", "Money", K.NUMBER)),
            line = { o -> o.optString("d") to "Deen ${o.optString("deen")} · Health ${o.optString("health")} · Work ${o.optString("work")} · Family ${o.optString("family")} · Money ${o.optString("money")}" },
            summary = { l -> val keys = listOf("deen", "health", "work", "family", "money"); val recent = l.take(7); val before = l.drop(7).take(7)
                keys.map { k -> val a = recent.map { num(it.optString(k)) }.average(); val b = before.map { num(it.optString(k)) }.average()
                    "${k.replaceFirstChar { it.uppercase() }}: ${"%.1f".format(a)}${if (!b.isNaN()) (if (a > b + 0.3) " ↑" else if (a < b - 0.3) " ↓" else " →") else ""}" } }),
        LogSpec("dreams", "Dream journal", "Private, encrypted dream log", Icons.Outlined.NightsStay, Hub.Memory, "dream journal khwab",
            listOf(F("d", "Date", K.DATE), F("dream", "Dream", K.LONG), F("feel", "Feeling", K.CHOICE, options = listOf("Peaceful", "Happy", "Strange", "Anxious", "Scary"), default = "Strange")),
            sealed = true, line = { o -> o.optString("d") + " · " + o.optString("feel") to o.optString("dream").take(100) }),
    )

    // ───────────────────────── calculators (exact, on the phone) ─────────────────────────
    private val calcs = listOf(
        CalcSpec("salarytax", "Salary tax", "Monthly tax and take-home on a salary", Icons.Outlined.RequestQuote, Hub.Money, "income tax fbr salary slab",
            listOf(F("salary", "Monthly salary", K.NUMBER, default = "150000")),
            listOf("Annual income" to "salary * 12",
                "Annual tax" to "band(salary*12, 600000,0, 1200000,1, 2200000,11, 3200000,23, 4100000,30, 35)",
                "Monthly tax" to "band(salary*12, 600000,0, 1200000,1, 2200000,11, 3200000,23, 4100000,30, 35) / 12",
                "Take-home per month" to "salary - band(salary*12, 600000,0, 1200000,1, 2200000,11, 3200000,23, 4100000,30, 35) / 12"),
            "Salaried slabs for tax year 2026 (0% to 6 lakh, 1%, 11%, 23%, 30%, 35%). A surcharge applies above 1 crore a year. Check FBR if the budget changed them."),
        CalcSpec("bill", "Electricity bill", "Estimate this month's bill", Icons.Outlined.ElectricBolt, Hub.Home, "bijli bill lesco kelectric units",
            listOf(F("units", "Units", K.NUMBER, default = "250"), F("rate", "Rate per unit", K.NUMBER, default = "48"), F("fixed", "Fixed charges", K.NUMBER, default = "0"), F("tax", "Taxes & duties %", K.NUMBER, default = "20")),
            listOf("Energy cost" to "units * rate", "Taxes" to "(units*rate + fixed) * tax / 100", "Estimated bill" to "(units*rate + fixed) * (1 + tax/100)"),
            "Rates differ by company, slab and month (fuel adjustment). Put your bill's own per-unit rate for a close estimate."),
        CalcSpec("slab", "Meter slab warning", "Stay under 200 units (or your slab)", Icons.Outlined.Speed, Hub.Home, "protected 200 units slab meter reading",
            listOf(F("used", "Units used so far", K.NUMBER, default = "120"), F("days", "Days since reading", K.NUMBER, default = "18"), F("cycle", "Days in the billing cycle", K.NUMBER, default = "30"), F("limit", "Slab limit", K.NUMBER, default = "200")),
            listOf("Daily average" to "used / days", "Projected for the month" to "used / days * cycle", "Units left under the slab" to "limit - used",
                "Max per day to stay under" to "(limit - used) / clamp(cycle - days, 1, 99)"),
            "Protected consumers who stay at or under 200 units for 6 months keep lower rates. Crossing it by one unit can raise the whole bill."),
        CalcSpec("plot", "Plot area", "Feet to marla, kanal, square yards", Icons.Outlined.SquareFoot, Hub.Home, "marla kanal plot land area",
            listOf(F("l", "Length (ft)", K.NUMBER, default = "45"), F("w", "Width (ft)", K.NUMBER, default = "25")),
            listOf("Square feet" to "l*w", "Square yards" to "l*w/9", "Square metres" to "l*w*0.092903", "Marla (225 sq ft)" to "l*w/225", "Marla (272.25 sq ft)" to "l*w/272.25", "Kanal (20 marla of 225)" to "l*w/4500"),
            "Most housing societies use 225 sq ft per marla; revenue records often use 272.25."),
        CalcSpec("units", "Desi units", "Tola, maund, seer and more", Icons.Outlined.Scale, Hub.Tools, "tola maund seer gram kg convert",
            listOf(F("kg", "Kilograms", K.NUMBER, default = "1"), F("g", "Grams (for tola)", K.NUMBER, default = "11.66")),
            listOf("Seer" to "kg / 0.933", "Maund (40 kg)" to "kg / 40", "Pounds" to "kg * 2.20462", "Tola" to "g / 11.6638", "Masha" to "g / 0.972", "Ratti" to "g / 0.1215")),
        CalcSpec("gold", "Gold value", "What your gold is worth today", Icons.Outlined.Diamond, Hub.Money, "gold tola karat sona price",
            listOf(F("tola", "Tola", K.NUMBER, default = "2"), F("grams", "Plus grams", K.NUMBER, default = "0"), F("k", "Purity", K.CHOICE, options = listOf("24K", "22K", "21K", "18K"), default = "22K"),
                F("rate", "24K rate per tola", K.NUMBER, default = "3.6 lakh"), F("cut", "Dealer deduction %", K.NUMBER, default = "5")),
            listOf("Weight (tola)" to "tola + grams/11.6638", "Purity factor" to "if(lt(k,0.5), 24, if(lt(k,1.5), 22, if(lt(k,2.5), 21, 18))) / 24",
                "Market value" to "(tola + grams/11.6638) * rate * if(lt(k,0.5), 24, if(lt(k,1.5), 22, if(lt(k,2.5), 21, 18))) / 24",
                "If you sell (after deduction)" to "(tola + grams/11.6638) * rate * if(lt(k,0.5), 24, if(lt(k,1.5), 22, if(lt(k,2.5), 21, 18))) / 24 * (1 - cut/100)"),
            "Put today's sarafa rate. Check Fact check or Petrol & prices for the current rate."),
        CalcSpec("loans", "Loan compare", "Two offers side by side", Icons.Outlined.CompareArrows, Hub.Money, "loan emi compare bank car finance",
            listOf(F("p", "Amount", K.NUMBER, default = "20 lakh"), F("ra", "Offer A rate % / year", K.NUMBER, default = "18"), F("ma", "Offer A months", K.NUMBER, default = "36"),
                F("rb", "Offer B rate % / year", K.NUMBER, default = "15"), F("mb", "Offer B months", K.NUMBER, default = "60")),
            listOf("A: monthly" to "emi(p, ra, ma)", "A: total interest" to "emi(p, ra, ma)*ma - p", "B: monthly" to "emi(p, rb, mb)", "B: total interest" to "emi(p, rb, mb)*mb - p",
                "B costs this much more (−: less)" to "(emi(p, rb, mb)*mb) - (emi(p, ra, ma)*ma)")),
        CalcSpec("qurbani", "Qurbani share", "Cost per hissa", Icons.Outlined.Pets, Hub.Deen, "qurbani hissa cow share eid ul adha",
            listOf(F("price", "Animal price", K.NUMBER, default = "3.5 lakh"), F("extra", "Transport, fodder, butcher", K.NUMBER, default = "40000"), F("shares", "Shares", K.NUMBER, default = "7")),
            listOf("Total" to "price + extra", "Per share" to "(price + extra) / shares")),
        CalcSpec("trip", "Trip fuel cost", "Fuel and cost per person", Icons.Outlined.DirectionsCar, Hub.Travel, "petrol trip mileage fuel cost",
            listOf(F("km", "Distance (km, one way)", K.NUMBER, default = "380"), F("kmpl", "Car mileage (km/l)", K.NUMBER, default = "13"), F("price", "Petrol per litre", K.NUMBER, default = "265"),
                F("tolls", "Tolls", K.NUMBER, default = "1500"), F("people", "People", K.NUMBER, default = "4")),
            listOf("Fuel (litres, round trip)" to "km*2/kmpl", "Fuel cost" to "km*2/kmpl*price", "Total with tolls" to "km*2/kmpl*price + tolls*2", "Per person" to "(km*2/kmpl*price + tolls*2) / people")),
        CalcSpec("freelance", "Freelance earnings", "Dollars to rupees after fees", Icons.Outlined.Payments, Hub.Money, "fiverr upwork payoneer usd dollar freelance",
            listOf(F("usd", "Earned (USD)", K.NUMBER, default = "500"), F("fee", "Platform fee %", K.NUMBER, default = "20"), F("wd", "Withdrawal fee (USD)", K.NUMBER, default = "3"), F("rate", "Rate (Rs per USD)", K.NUMBER, default = "282")),
            listOf("After platform fee (USD)" to "usd * (1 - fee/100)", "Received (USD)" to "usd*(1 - fee/100) - wd", "In rupees" to "(usd*(1 - fee/100) - wd) * rate"),
            "Registered IT exporters pay 0.25% final tax on remittances through banks; keep proceeds records."),
        CalcSpec("body", "Body numbers", "BMI, calories and water", Icons.Outlined.MonitorWeight, Hub.Health, "bmi calories weight bmr",
            listOf(F("w", "Weight (kg)", K.NUMBER, default = "75"), F("h", "Height (cm)", K.NUMBER, default = "172"), F("age", "Age", K.NUMBER, default = "30"),
                F("sex", "Sex", K.CHOICE, options = listOf("Male", "Female"), default = "Male")),
            listOf("BMI" to "w / (h/100)^2", "Healthy weight range (kg)" to "round(18.5*(h/100)^2) ", "…up to" to "round(24.9*(h/100)^2)",
                "Calories at rest" to "10*w + 6.25*h - 5*age + if(lt(sex,0.5), 5, -161)", "Water per day (litres)" to "round(w*0.033, 1)"),
            "BMI for South Asians: above 23 is already overweight in most guidelines."),
        CalcSpec("solar", "Solar payback", "When does solar pay for itself?", Icons.Outlined.WbSunny, Hub.Home, "solar panels net metering payback",
            listOf(F("cost", "System cost", K.NUMBER, default = "12 lakh"), F("save", "Monthly bill saving", K.NUMBER, default = "35000")),
            listOf("Payback (months)" to "cost / save", "Payback (years)" to "cost / save / 12", "Saved over 10 years" to "save*120 - cost")),
        CalcSpec("margin", "Profit margin", "For shops and resellers", Icons.Outlined.Store, Hub.Money, "profit margin markup business price",
            listOf(F("cost", "Cost price", K.NUMBER, default = "800"), F("sale", "Sale price", K.NUMBER, default = "1100")),
            listOf("Profit" to "sale - cost", "Markup %" to "(sale - cost) / cost * 100", "Margin %" to "(sale - cost) / sale * 100")),
        CalcSpec("savegoal", "Savings plan", "How much a month to reach a goal", Icons.Outlined.Savings, Hub.Money, "save goal monthly plan",
            listOf(F("goal", "Goal", K.NUMBER, default = "10 lakh"), F("have", "Saved so far", K.NUMBER, default = "1 lakh"), F("months", "Months", K.NUMBER, default = "24"), F("ret", "Return % / year", K.NUMBER, default = "0")),
            listOf("Still needed" to "goal - have", "Per month" to "if(gt(ret,0), (goal - have*(1+ret/1200)^months) * (ret/1200) / ((1+ret/1200)^months - 1 + (1 - gt(ret,0))), (goal - have)/months)")),
        CalcSpec("rentup", "Rent increase", "Where rent goes with yearly increases", Icons.Outlined.Home, Hub.Home, "rent increase 10 percent agreement",
            listOf(F("rent", "Rent now", K.NUMBER, default = "60000"), F("pct", "Increase % per year", K.NUMBER, default = "10"), F("yrs", "Years", K.NUMBER, default = "3")),
            listOf("Rent then" to "grow(rent, pct, yrs)", "Paid over the period" to "rent*12*((1+pct/100)^yrs - 1)/(pct/100)")),
    )

    private val links = listOf(
        LinkSpec("govlinks", "Official services", "E-challan, NADRA, FBR, PTA and more", Icons.Outlined.AccountBalance, Hub.Tools, "challan nadra fbr pta passport bill government",
            listOf(
                Triple("Punjab e-challan", "Check and pay traffic challans (Safe City)", "https://echallan.psca.gop.pk"),
                Triple("Islamabad traffic police", "E-tickets and licences", "https://islamabadtrafficpolice.gov.pk"),
                Triple("Punjab vehicle verification", "Owner, tax and registration", "https://mtmis.excise.punjab.gov.pk"),
                Triple("NADRA Pak-ID", "CNIC, FRC, family registration online", "https://id.nadra.gov.pk"),
                Triple("FBR IRIS", "File returns", "https://iris.fbr.gov.pk"),
                Triple("FBR active taxpayer check", "Filer or non-filer", "https://e.fbr.gov.pk"),
                Triple("Passport status", "DGIP tracking", "https://www.dgip.gov.pk"),
                Triple("PTA device check (DIRBS)", "Is a phone registered?", "https://dirbs.pta.gov.pk"),
                Triple("Pakistan Citizen Portal", "Complaints to any department", "https://citizenportal.gov.pk"),
                Triple("Electricity bills (all DISCOs)", "Duplicate bill by reference", "https://bill.pitc.com.pk"),
                Triple("SNGPL gas bill", "Duplicate bill", "https://www.sngpl.com.pk"),
            )),
    )
}
