package com.nizam.app.feature.dynamic

/**
 * Built-in modules, defined as specs. Adding one is a data change, not a code change — which is
 * also how the curator agent adds its own.
 */
object Builtins {

    private fun field(
        key: String, label: String, type: String,
        options: List<String> = emptyList(), suffix: String = ""
    ): String {
        val opts = if (options.isEmpty()) "" else
            ""","options":[""" + options.joinToString(",") { "\"$it\"" } + "]"
        val suf = if (suffix.isBlank()) "" else ""","suffix":"$suffix""""
        return """{"key":"$key","label":"$label","type":"$type"$opts$suf}"""
    }

    private fun fields(vararg f: String) = "[" + f.joinToString(",") + "]"

    val specs: List<ModuleSpec> = listOf(
        ModuleSpec(
            key = "sleep", name = "Sleep", emoji = "🌙",
            tagline = "Hours, quality and what wrecked it",
            colorHex = "#5B6BA8", sortIndex = 1, builtIn = true,
            fieldsJson = fields(
                field("hours", "Hours slept", "NUMBER", suffix = "h"),
                field("quality", "Quality", "RATING"),
                field("bedtime", "Bedtime", "TEXT"),
                field("notes", "What affected it", "LONGTEXT")
            )
        ),
        ModuleSpec(
            key = "focus", name = "Focus", emoji = "⏱️",
            tagline = "Deep work sessions and what you got done",
            colorHex = "#3E7C8A", sortIndex = 2, builtIn = true,
            fieldsJson = fields(
                field("minutes", "Minutes", "NUMBER", suffix = "min"),
                field("task", "Working on", "TEXT"),
                field("depth", "Depth of focus", "RATING"),
                field("distraction", "Biggest distraction", "TEXT")
            )
        ),
        ModuleSpec(
            key = "people", name = "People", emoji = "🤝",
            tagline = "Who matters, and when you last spoke",
            colorHex = "#A8634F", sortIndex = 3, builtIn = true,
            fieldsJson = fields(
                field("relation", "Relationship", "TEXT"),
                field("lastContact", "Last spoke", "DATE"),
                field("cadence", "Check in every", "CHOICE",
                    listOf("Week", "Fortnight", "Month", "Quarter")),
                field("notes", "Notes", "LONGTEXT")
            )
        ),
        ModuleSpec(
            key = "projects", name = "Projects", emoji = "🗂️",
            tagline = "Bigger things with stages",
            colorHex = "#4F6FA8", sortIndex = 4, builtIn = true,
            fieldsJson = fields(
                field("stage", "Stage", "CHOICE", listOf("Idea", "Planned", "Doing", "Blocked", "Done")),
                field("nextAction", "Next action", "TEXT"),
                field("deadline", "Deadline", "DATE"),
                field("notes", "Notes", "LONGTEXT")
            )
        ),
        ModuleSpec(
            key = "subscriptions", name = "Subscriptions", emoji = "🔁",
            tagline = "Recurring money, before it surprises you",
            colorHex = "#B57F4A", sortIndex = 5, builtIn = true,
            fieldsJson = fields(
                field("amount", "Amount", "NUMBER", suffix = "PKR"),
                field("cycle", "Billing cycle", "CHOICE", listOf("Monthly", "Quarterly", "Yearly")),
                field("renews", "Next renewal", "DATE"),
                field("worthIt", "Still worth it", "BOOL")
            )
        ),
        ModuleSpec(
            key = "wins", name = "Wins", emoji = "🏅",
            tagline = "Evidence for the days you feel like nothing works",
            colorHex = "#C08A2E", sortIndex = 6, builtIn = true,
            fieldsJson = fields(
                field("area", "Area", "CHOICE",
                    listOf("Work", "Health", "Learning", "Money", "Faith", "People")),
                field("detail", "What happened", "LONGTEXT"),
                field("size", "How big", "RATING")
            )
        ),
        ModuleSpec(
            key = "azkar", name = "Azkar", emoji = "📿",
            tagline = "Morning, evening and counted dhikr",
            colorHex = "#2F7A66", sortIndex = 7, builtIn = true,
            fieldsJson = fields(
                field("set", "Set", "CHOICE",
                    listOf("Morning", "Evening", "After prayer", "Before sleep", "Custom")),
                field("count", "Count", "NUMBER"),
                field("notes", "Notes", "TEXT")
            )
        ),
        ModuleSpec(
            key = "places", name = "Places", emoji = "📍",
            tagline = "Where you went and whether it was worth it",
            colorHex = "#6E8E4E", sortIndex = 8, builtIn = true,
            fieldsJson = fields(
                field("city", "City or area", "TEXT"),
                field("kind", "Type", "CHOICE",
                    listOf("Restaurant", "Cafe", "Park", "Masjid", "Shop", "Trip", "Other")),
                field("rating", "Rating", "RATING"),
                field("notes", "Notes", "LONGTEXT")
            )
        ),
        ModuleSpec(
            key = "recipes", name = "Recipes", emoji = "🍲",
            tagline = "Things you can actually cook",
            colorHex = "#A85D3C", sortIndex = 9, builtIn = true,
            fieldsJson = fields(
                field("ingredients", "Ingredients", "LONGTEXT"),
                field("method", "Method", "LONGTEXT"),
                field("time", "Time", "NUMBER", suffix = "min"),
                field("rating", "Rating", "RATING")
            )
        ),
        ModuleSpec(
            key = "ideas", name = "Ideas", emoji = "💡",
            tagline = "Catch it before it evaporates",
            colorHex = "#C9A227", sortIndex = 10, builtIn = true,
            fieldsJson = fields(
                field("detail", "The idea", "LONGTEXT"),
                field("area", "Area", "CHOICE",
                    listOf("Business", "App", "Content", "Personal", "Other")),
                field("energy", "Excitement", "RATING")
            )
        ),
        ModuleSpec(
            key = "vocab", name = "Vocabulary", emoji = "🔤",
            tagline = "Words and phrases worth keeping",
            colorHex = "#7A5BA8", sortIndex = 11, builtIn = true,
            fieldsJson = fields(
                field("language", "Language", "CHOICE",
                    listOf("Arabic", "English", "Urdu", "Chinese", "Other")),
                field("meaning", "Meaning", "TEXT"),
                field("example", "Used in a sentence", "LONGTEXT")
            )
        ),
        ModuleSpec(
            key = "inventory", name = "Inventory", emoji = "📦",
            tagline = "What you own, what it cost, where it is",
            colorHex = "#6B7280", sortIndex = 12, builtIn = true,
            fieldsJson = fields(
                field("category", "Category", "CHOICE",
                    listOf("Tech", "Clothing", "Home", "Tools", "Documents", "Other")),
                field("value", "Value", "NUMBER", suffix = "PKR"),
                field("location", "Where", "TEXT"),
                field("warranty", "Warranty until", "DATE")
            )
        ),
        ModuleSpec(
            key = "maintenance", name = "Maintenance", emoji = "🔧",
            tagline = "Bike, home, documents — things with a due date",
            colorHex = "#8A6A3B", sortIndex = 13, builtIn = true,
            fieldsJson = fields(
                field("item", "What", "TEXT"),
                field("due", "Due", "DATE"),
                field("interval", "Repeats", "CHOICE",
                    listOf("Monthly", "Quarterly", "Every 6 months", "Yearly", "One-off")),
                field("notes", "Notes", "LONGTEXT")
            )
        ),
        ModuleSpec(
            key = "watchlist", name = "Watchlist", emoji = "🎬",
            tagline = "Films, series, talks and lectures to watch",
            colorHex = "#8A4F6B", sortIndex = 14, builtIn = true,
            fieldsJson = fields(
                field("kind", "Type", "CHOICE",
                    listOf("Film", "Series", "Documentary", "Lecture", "Talk")),
                field("status", "Status", "CHOICE", listOf("To watch", "Watching", "Finished", "Dropped")),
                field("rating", "Rating", "RATING"),
                field("notes", "Notes", "LONGTEXT")
            )
        ),
        ModuleSpec(
            key = "music", name = "Music", emoji = "🎵",
            tagline = "Your own tracks, playable in the app",
            colorHex = "#8A4F6B", sortIndex = 16, builtIn = true,
            fieldsJson = fields(
                field("file", "Audio file", "FILE"),
                field("artist", "Artist", "TEXT"),
                field("album", "Album", "TEXT"),
                field("rating", "Rating", "RATING")
            )
        ),
        ModuleSpec(
            key = "gallery", name = "Gallery", emoji = "🖼️",
            tagline = "Photos and clips worth keeping",
            colorHex = "#4F7C8A", sortIndex = 17, builtIn = true,
            fieldsJson = fields(
                field("file", "Photo or video", "FILE"),
                field("where", "Where", "TEXT"),
                field("notes", "Notes", "LONGTEXT")
            )
        ),
        ModuleSpec(
            key = "documents", name = "Documents", emoji = "📄",
            tagline = "PDFs and files you need to find again",
            colorHex = "#6B7280", sortIndex = 18, builtIn = true,
            fieldsJson = fields(
                field("file", "File", "FILE"),
                field("category", "Category", "CHOICE",
                    listOf("ID", "Medical", "Work", "Finance", "Warranty", "Other")),
                field("expires", "Expires", "DATE")
            )
        ),
        ModuleSpec(
            key = "career", name = "Career", emoji = "💼",
            tagline = "Wins, projects and skills — your running brag document",
            colorHex = "#3E7C8A", sortIndex = 19, builtIn = true,
            fieldsJson = fields(
                field("type", "Type", "CHOICE",
                    listOf("Win", "Project", "Skill learned", "Feedback", "Certification", "Setback", "Idea")),
                field("impact", "Impact", "LONGTEXT"),
                field("hours", "Hours invested", "NUMBER", suffix = "h"),
                field("skills", "Skills", "TEXT"),
                field("rating", "Significance", "RATING"),
                field("file", "Evidence", "FILE")
            )
        ),
        ModuleSpec(
            key = "people", name = "People", emoji = "🫂",
            tagline = "The people who matter — and when you last showed it",
            colorHex = "#8A4F6B", sortIndex = 20, builtIn = true,
            fieldsJson = fields(
                field("relation", "Relation", "CHOICE",
                    listOf("Family", "Friend", "Colleague", "Mentor", "Neighbour", "Other")),
                field("birthday", "Birthday", "DATE"),
                field("lastContact", "Last spoke", "DATE"),
                field("phone", "Phone", "TEXT"),
                field("closeness", "Closeness", "RATING"),
                field("notes", "Things to remember", "LONGTEXT")
            )
        ),
        ModuleSpec(
            key = "skills", name = "Skill tree", emoji = "🌳",
            tagline = "Real skills, levelled by the hours you put in",
            colorHex = "#6E8E4E", sortIndex = 21, builtIn = true,
            fieldsJson = "[" + listOf(
                field("branch", "Branch", "CHOICE", listOf("Work", "Tech", "Language", "Body", "Faith", "Creative", "Social")),
                field("hours", "Hours practised", "NUMBER", suffix = "h"),
                field("confidence", "Confidence", "RATING"),
                field("next", "Next milestone", "TEXT"),
                "{\"key\":\"level\",\"label\":\"Level\",\"type\":\"COMPUTED\",\"formula\":\"floor(sqrt(hours))\"}"
            ).joinToString(",") + "]"
        ),
        ModuleSpec(
            key = "maintenance", name = "Maintenance", emoji = "🔧",
            tagline = "Bike service, AC filters, renewals — reminded on the day",
            colorHex = "#6B7280", sortIndex = 22, builtIn = true,
            fieldsJson = fields(
                field("area", "What", "CHOICE", listOf("Vehicle", "Home", "Appliance", "Document", "Health check", "Other")),
                field("due", "Next due", "DATE"),
                field("cost", "Cost", "NUMBER", suffix = "PKR"),
                field("notes", "Notes", "LONGTEXT")
            )
        ),
        ModuleSpec(
            key = "vehicle", name = "Vehicle", emoji = "🏍", tagline = "Fuel, service and what it really costs per km",
            colorHex = "#6B7280", sortIndex = 23, builtIn = true,
            fieldsJson = "[" + listOf(
                field("type", "Entry", "CHOICE", listOf("Fuel", "Service", "Repair", "Insurance", "Fine", "Wash")),
                field("litres", "Litres", "NUMBER", suffix = "L"),
                field("cost", "Cost", "NUMBER", suffix = "PKR"),
                field("odometer", "Odometer", "NUMBER", suffix = "km"),
                field("notes", "Notes", "LONGTEXT"),
                "{\"key\":\"perLitre\",\"label\":\"Cost per litre\",\"type\":\"COMPUTED\",\"formula\":\"cost / litres\"}"
            ).joinToString(",") + "]"
        ),
        ModuleSpec(
            key = "hifz", name = "Hifz", emoji = "🕋", tagline = "Memorisation, revision and how strong each portion is",
            colorHex = "#2F8A73", sortIndex = 24, builtIn = true,
            fieldsJson = fields(
                field("surah", "Surah", "TEXT"),
                field("ayat", "Ayat", "TEXT"),
                field("stage", "Stage", "CHOICE", listOf("New", "Memorised", "Revising", "Strong", "Weak")),
                field("lastRevised", "Last revised", "DATE"),
                field("strength", "Strength", "RATING"),
                field("notes", "Mutashabihat / notes", "LONGTEXT")
            )
        ),
        ModuleSpec(
            key = "projects", name = "Projects", emoji = "🗂", tagline = "What you're building, and the next move on each",
            colorHex = "#5B6BA8", sortIndex = 25, builtIn = true,
            fieldsJson = fields(
                field("status", "Status", "CHOICE", listOf("Idea", "Active", "Blocked", "Paused", "Shipped", "Dropped")),
                field("next", "Next action", "TEXT"),
                field("deadline", "Deadline", "DATE"),
                field("hours", "Hours in", "NUMBER", suffix = "h"),
                field("notes", "Notes", "LONGTEXT"),
                field("file", "Files", "FILE")
            )
        ),
        ModuleSpec(key = "sunnah", name = "Sunnah", emoji = "🌙", tagline = "The small ones, one at a time",
            colorHex = "#2F8A73", sortIndex = 26, builtIn = true, fieldsJson = fields(
                field("sunnah", "Which sunnah", "TEXT"),
                field("when", "When", "CHOICE", listOf("Morning", "Before prayer", "After prayer", "Eating", "Sleeping", "Travel", "Other")),
                field("done", "Times done", "NUMBER"),
                field("notes", "Notes", "LONGTEXT"))),
        ModuleSpec(key = "dua", name = "Dua journal", emoji = "🤲", tagline = "What you asked for, and what came of it",
            colorHex = "#4FA88B", sortIndex = 27, builtIn = true, fieldsJson = fields(
                field("dua", "What you asked for", "LONGTEXT"),
                field("for", "For", "CHOICE", listOf("Self", "Family", "Work", "Health", "Ummah", "Other")),
                field("answered", "Outcome", "CHOICE", listOf("Waiting", "Answered", "Answered differently", "Still asking")),
                field("reflection", "Reflection", "LONGTEXT"))),
        ModuleSpec(key = "sadaqah", name = "Sadaqah", emoji = "💚", tagline = "Given quietly, tracked for your own accounting",
            colorHex = "#3E7C8A", sortIndex = 28, builtIn = true, fieldsJson = fields(
                field("amount", "Amount", "NUMBER", suffix = "PKR"),
                field("to", "To", "TEXT"),
                field("kind", "Kind", "CHOICE", listOf("Sadaqah", "Zakat", "Sponsorship", "Time", "Goods")),
                field("notes", "Notes", "LONGTEXT"))),
        ModuleSpec(key = "zakat", name = "Zakat", emoji = "⚖️", tagline = "Assets through the year and what's due",
            colorHex = "#6E8E4E", sortIndex = 29, builtIn = true, fieldsJson = "[" + listOf(
                field("asset", "Asset", "CHOICE", listOf("Cash", "Savings", "Gold", "Silver", "Investments", "Business stock", "Receivable", "Debt owed")),
                field("value", "Value", "NUMBER", suffix = "PKR"),
                field("date", "As of", "DATE"),
                "{\"key\":\"due\",\"label\":\"Zakat due (2.5%)\",\"type\":\"COMPUTED\",\"formula\":\"value * 0.025\"}").joinToString(",") + "]"),
        ModuleSpec(key = "meds", name = "Medication", emoji = "💊", tagline = "What you take, when, and when it runs out",
            colorHex = "#8A4F6B", sortIndex = 30, builtIn = true, fieldsJson = fields(
                field("name", "Name", "TEXT"),
                field("dose", "Dose", "TEXT"),
                field("time", "When", "CHOICE", listOf("Morning", "Afternoon", "Evening", "Night", "As needed")),
                field("refill", "Refill by", "DATE"),
                field("notes", "Notes", "LONGTEXT"))),
        ModuleSpec(key = "symptoms", name = "Symptoms", emoji = "🩺", tagline = "What you felt, when — pairs with sleep and food",
            colorHex = "#9C6B6B", sortIndex = 31, builtIn = true, fieldsJson = fields(
                field("what", "Symptom", "TEXT"),
                field("severity", "Severity", "RATING"),
                field("duration", "Hours", "NUMBER", suffix = "h"),
                field("suspect", "Suspected trigger", "TEXT"),
                field("notes", "Notes", "LONGTEXT"))),
        ModuleSpec(key = "bloodwork", name = "Lab results", emoji = "🧪", tagline = "Your numbers over time",
            colorHex = "#7A5BA8", sortIndex = 32, builtIn = true, fieldsJson = fields(
                field("marker", "Marker", "TEXT"),
                field("value", "Value", "NUMBER"),
                field("unit", "Unit", "TEXT"),
                field("date", "Taken on", "DATE"),
                field("file", "Report", "FILE"))),
        ModuleSpec(key = "subscriptions", name = "Subscriptions", emoji = "🔁", tagline = "What renews, what you've stopped using",
            colorHex = "#D98E4A", sortIndex = 33, builtIn = true, fieldsJson = "[" + listOf(
                field("service", "Service", "TEXT"),
                field("monthly", "Per month", "NUMBER", suffix = "PKR"),
                field("renews", "Renews", "DATE"),
                field("used", "Last used", "DATE"),
                field("keep", "Verdict", "CHOICE", listOf("Keep", "Reviewing", "Cancel")),
                "{\"key\":\"yearly\",\"label\":\"Per year\",\"type\":\"COMPUTED\",\"formula\":\"monthly * 12\"}").joinToString(",") + "]"),
        ModuleSpec(key = "shifts", name = "Work log", emoji = "🕒", tagline = "Hours, overtime and what you're owed",
            colorHex = "#5B6BA8", sortIndex = 34, builtIn = true, fieldsJson = "[" + listOf(
                field("hours", "Hours", "NUMBER", suffix = "h"),
                field("overtime", "Overtime", "NUMBER", suffix = "h"),
                field("rate", "Hourly rate", "NUMBER", suffix = "PKR"),
                field("kind", "Type", "CHOICE", listOf("Regular", "Overtime", "Holiday", "Leave", "Sick")),
                field("notes", "Notes", "LONGTEXT"),
                "{\"key\":\"earned\",\"label\":\"Earned\",\"type\":\"COMPUTED\",\"formula\":\"(hours + overtime * 1.5) * rate\"}").joinToString(",") + "]"),
        ModuleSpec(key = "opportunities", name = "Opportunities", emoji = "🚪", tagline = "Every chance that came — taken or not",
            colorHex = "#B57F4A", sortIndex = 35, builtIn = true, fieldsJson = fields(
                field("what", "Opportunity", "TEXT"),
                field("from", "Came from", "TEXT"),
                field("decision", "You", "CHOICE", listOf("Took it", "Declined", "Missed it", "Still deciding")),
                field("outcome", "What happened", "LONGTEXT"),
                field("rating", "In hindsight", "RATING"))),
        ModuleSpec(key = "gifts", name = "Gifts & occasions", emoji = "🎁", tagline = "Who, when, what you gave last time",
            colorHex = "#E08AA8", sortIndex = 36, builtIn = true, fieldsJson = fields(
                field("person", "Person", "TEXT"),
                field("occasion", "Occasion", "CHOICE", listOf("Birthday", "Eid", "Wedding", "Nikah", "Baby", "Thanks", "Other")),
                field("date", "Date", "DATE"),
                field("gift", "What you gave", "TEXT"),
                field("budget", "Spent", "NUMBER", suffix = "PKR"),
                field("ideas", "Ideas for next time", "LONGTEXT"))),
        ModuleSpec(key = "network", name = "Networking", emoji = "🤝", tagline = "People met, and what you promised them",
            colorHex = "#58A6C9", sortIndex = 37, builtIn = true, fieldsJson = fields(
                field("person", "Person", "TEXT"),
                field("where", "Met at", "TEXT"),
                field("role", "Their role", "TEXT"),
                field("promised", "What you promised", "TEXT"),
                field("followUp", "Follow up by", "DATE"),
                field("notes", "Notes", "LONGTEXT"))),
        ModuleSpec(key = "documents", name = "Documents", emoji = "📄", tagline = "IDs, degrees, insurance — with expiry warnings",
            colorHex = "#6B7280", sortIndex = 38, builtIn = true, fieldsJson = fields(
                field("kind", "Type", "CHOICE", listOf("ID", "Passport", "Licence", "Degree", "Insurance", "Warranty", "Contract", "Bill", "Other")),
                field("number", "Reference", "TEXT"),
                field("expiry", "Expires", "DATE"),
                field("file", "Scan", "FILE"),
                field("notes", "Notes", "LONGTEXT"))),
        ModuleSpec(key = "snippets", name = "Snippets", emoji = "⌨️", tagline = "Code and commands you keep reusing",
            colorHex = "#3E7C8A", sortIndex = 39, builtIn = true, fieldsJson = fields(
                field("language", "Language", "TEXT"),
                field("code", "Snippet", "LONGTEXT"),
                field("use", "What it's for", "TEXT"))),
        ModuleSpec(key = "collections", name = "Collections", emoji = "🗃", tagline = "Things you follow, own or want",
            colorHex = "#8C8478", sortIndex = 40, builtIn = true, fieldsJson = fields(
                field("category", "Collection", "TEXT"),
                field("item", "Item", "TEXT"),
                field("status", "Status", "CHOICE", listOf("Owned", "Wanted", "Watching", "Sold")),
                field("price", "Price", "NUMBER", suffix = "PKR"),
                field("notes", "Notes", "LONGTEXT"))),
        ModuleSpec(key = "recipes2", name = "Recipe box", emoji = "🍲", tagline = "The ones you actually make again",
            colorHex = "#D98E4A", sortIndex = 41, builtIn = true, fieldsJson = fields(
                field("dish", "Dish", "TEXT"),
                field("times", "Times made", "NUMBER"),
                field("rating", "Rating", "RATING"),
                field("tweaks", "Your changes", "LONGTEXT"),
                field("recipe", "Recipe", "LONGTEXT"))),
        ModuleSpec(key = "style", name = "Style log", emoji = "👔", tagline = "What you wore, what worked, what sits unused",
            colorHex = "#9B8CD6", sortIndex = 42, builtIn = true, fieldsJson = fields(
                field("outfit", "Outfit", "TEXT"),
                field("occasion", "Occasion", "TEXT"),
                field("rating", "How it felt", "RATING"),
                field("file", "Photo", "FILE"))),
        ModuleSpec(key = "writing", name = "Writing", emoji = "✍️", tagline = "Drafts, word counts and where each stands",
            colorHex = "#B8A888", sortIndex = 43, builtIn = true, fieldsJson = fields(
                field("piece", "Piece", "TEXT"),
                field("stage", "Stage", "CHOICE", listOf("Idea", "Drafting", "Revising", "Done", "Published")),
                field("words", "Words", "NUMBER"),
                field("draft", "Draft", "LONGTEXT"))),
        ModuleSpec(key = "voicenotes", name = "Voice notes", emoji = "🎤", tagline = "Quick captures, transcribed",
            colorHex = "#4FC3A1", sortIndex = 44, builtIn = true, fieldsJson = fields(
                field("note", "What you said", "LONGTEXT"),
                field("context", "Context", "TEXT"))),
        ModuleSpec(key = "gratitude", name = "Gratitude", emoji = "🤲",
            tagline = "Three things, most days",
            colorHex = "#4F8A6B", sortIndex = 15, builtIn = true,
            fieldsJson = fields(
                field("one", "First", "TEXT"),
                field("two", "Second", "TEXT"),
                field("three", "Third", "TEXT")
            )
        )
    )
}
