successfully downloaded text file (SHA: 3941a1ee263424b9539521e17faca9d52cf0c454)
package com.nizam.app.ui.screens.hubs

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.nav.Routes

/**
 * Every feature in Atlas, placed in exactly one Life hub. The first four `top` entries of a hub are
 * its big tiles; the rest fill its "More" grid. Search and Atlas reach all of them too.
 */
data class Feature(val route: String, val title: String, val blurb: String, val icon: ImageVector, val hub: Hub,
                   val top: Boolean = false, val words: String = "", val group: String = "Essentials")

object HubRegistry {
    private fun power(id: String) = "power/$id"

    // one entry per feature: the same thing reachable under two names is merged (the built-in screen wins)
    val all: List<Feature> by lazy {
        (base + com.nizam.app.feature.nx.Nx.features()).distinctBy { it.title.lowercase().filter(Char::isLetter) }
    }

    /** Section order inside a hub page. */
    val GROUPS = listOf("Essentials", "Logs & trackers", "Tools", "Ask AI", "Talk to", "Calculators", "Official links")

    private val base: List<Feature> = listOf(
        // Money
        Feature(Routes.FINANCE, "Money", "Spending, budgets, accounts", Icons.Outlined.Payments, Hub.Money, true, "expense budget finance"),
        Feature(Routes.AUTOMONEY, "Auto-logging", "Bank, JazzCash and Easypaisa SMS", Icons.Outlined.Sms, Hub.Money, true, "sms bank jazzcash easypaisa"),
        Feature(Routes.RECEIPTS, "Receipts", "Photo a bill: logged, split, warranty kept", Icons.Outlined.Receipt, Hub.Money, true, "bill warranty"),
        Feature(Routes.COMMITTEES, "Committees", "BCs: who's paid, whose turn, reminders", Icons.Outlined.Groups, Hub.Money, words = "bc committee kameti"),
        Feature(Routes.DEBTS, "Lent & borrowed", "Udhaar, loans and instalments", Icons.Outlined.SwapHoriz, Hub.Money, words = "debt loan owe udhaar"),
        Feature(Routes.SUBSCRIPTIONS, "Subscriptions", "Recurring charges and what they cost a year", Icons.Outlined.Autorenew, Hub.Money, words = "netflix recurring subscription"),
        Feature(Routes.PRICEWATCH, "Price tracker", "Alerts when a product gets cheaper", Icons.Outlined.Sell, Hub.Money, words = "daraz priceoye olx price drop deal"),
        Feature(Routes.SPLITS, "Split costs", "Trips and dinners: who pays whom", Icons.Outlined.Luggage, Hub.Money, words = "split trip share dinner"),
        Feature(Routes.RATES, "Rates", "Dollar, riyal, dirham and more in rupees", Icons.Outlined.CurrencyExchange, Hub.Money, words = "dollar rate usd sar aed exchange"),
        Feature(Routes.BILLSCAN, "Bill scanner", "Photo a bill: amount, due date, reminder", Icons.Outlined.ReceiptLong, Hub.Money, words = "wapda lesco sngpl ptcl bill electricity gas"),
        Feature(power("money"), "Money autopilot", "Forecasts, savings rules, alerts", Icons.Outlined.AutoGraph, Hub.Money, true, "savings forecast"),

        // Deen
        Feature(Routes.PRAYER, "Prayer", "Times, qibla, streaks", Icons.Outlined.Mosque, Hub.Deen, true, "salah namaz qibla"),
        Feature(Routes.DAILYPATH, "Daily Quranic path", "Today's passage, tafsir, hadith, lesson and mission", Icons.Outlined.WbSunny, Hub.Deen, true, "daily lesson mission khutbah lecture sermon reminder"),
        Feature(Routes.ISLAMLIB, "Islamic library", "Offline Quran editions, tafsirs and all hadith books", Icons.Outlined.LocalLibrary, Hub.Deen, true, "tafsir ibn kathir maarif bukhari muslim tirmidhi abu dawud nasai ibn majah muwatta offline"),
        Feature(Routes.QURAN, "Quran", "Read, listen, reflect", Icons.Outlined.MenuBook, Hub.Deen, true, "quran recite"),
        Feature(Routes.DEEN, "Deen", "Duas, adhkar, tasbih, hifz, Ramadan, zakat", Icons.Outlined.AutoAwesome, Hub.Deen, true, "dua dhikr tasbih zakat ramadan"),
        Feature(Routes.HADITH, "Hadith", "Collections and daily hadith", Icons.Outlined.AutoStories, Hub.Deen, true, "hadith sunnah"),
        Feature(Routes.ISLAMICCAL, "Islamic calendar", "Hijri dates, events, sunnah fasts", Icons.Outlined.CalendarMonth, Hub.Deen, words = "hijri eid ramadan ashura fast moon"),
        Feature(Routes.DUAS, "Duas", "Everyday supplications with meaning", Icons.Outlined.AutoAwesome, Hub.Deen, words = "dua supplication adhkar"),
        Feature(Routes.MASJIDS, "Masjid finder", "Mosques near you, with directions", Icons.Outlined.Mosque, Hub.Deen, words = "mosque masjid nearby jummah"),
        Feature(Routes.BARAKAH, "Barakah", "What lifts your days, salah-aware time", Icons.Outlined.WbSunny, Hub.Deen, words = "barakah"),

        // Health
        Feature(Routes.DIET, "Diet", "Meals, calories, water", Icons.Outlined.Restaurant, Hub.Health, true, "food calories"),
        Feature(Routes.GYM, "Gym", "Workouts and progress", Icons.Outlined.FitnessCenter, Hub.Health, true, "workout exercise"),
        Feature(Routes.TIMER, "Timer", "Focus blocks and Pomodoro", Icons.Outlined.Timer, Hub.Health, words = "timer pomodoro countdown"),
        Feature(Routes.CALM, "Calm", "Breathing you can feel", Icons.Outlined.SelfImprovement, Hub.Health, true, "breathe meditate"),
        Feature(Routes.WAKE, "Smart wake", "Woken in light sleep", Icons.Outlined.Alarm, Hub.Health, true, "sleep alarm"),
        Feature(Routes.MEDICINES, "Medicines", "Dose reminders and refill warnings", Icons.Outlined.Medication, Hub.Health, words = "medicine tablet dawai pill"),
        Feature(Routes.REPS, "Rep counter", "The camera counts your reps", Icons.Outlined.Videocam, Hub.Health, words = "pushups squats"),
        Feature(Routes.PLATE, "Snap plate", "Photo your food, get the numbers", Icons.Outlined.PhotoCamera, Hub.Health, words = "food photo"),
        Feature(Routes.COACH, "Voice coach", "Spoken workout guidance", Icons.Outlined.RecordVoiceOver, Hub.Health),
        Feature(Routes.BEDSIDE, "Bedside", "A dim clock for the night", Icons.Outlined.Bedtime, Hub.Health, words = "night clock"),
        Feature(Routes.FLIP, "Focus", "Sessions, flip-to-focus, soundscapes", Icons.Outlined.Timer, Hub.Health, words = "pomodoro focus"),
        Feature(Routes.PAUSE, "Pause", "A breath before the feeds", Icons.Outlined.PauseCircle, Hub.Health, words = "screen time"),
        Feature(Routes.SHIELD, "Focus shield", "Block apps for a while", Icons.Outlined.Shield, Hub.Health, words = "block apps"),
        Feature(Routes.WELLBEING_HUB, "Wellbeing", "Attention, rest and your own head", Icons.Outlined.Spa, Hub.Health),
        Feature(Routes.REFRAME, "Thought reframe", "Catch it, check it, rewrite it", Icons.Outlined.Psychology, Hub.Health, words = "cbt anxiety"),
        Feature(Routes.NIGHT, "Night companion", "Set the day down", Icons.Outlined.NightsStay, Hub.Health),

        // Learn
        Feature(Routes.LEARNING, "Learning", "Lessons and practice", Icons.Outlined.School, Hub.Learn, true, "study"),
        Feature(Routes.COURSES, "Courses", "Structured courses", Icons.Outlined.CastForEducation, Hub.Learn, true),
        Feature(Routes.LANGUAGE, "Language", "Speak and learn languages", Icons.Outlined.Translate, Hub.Learn, true, "english arabic urdu"),
        Feature(Routes.LIBRARY, "Library", "Your books", Icons.Outlined.LibraryBooks, Hub.Learn, true, "books read"),
        Feature(power("quiz"), "Quiz", "Test yourself on anything", Icons.Outlined.Quiz, Hub.Learn),
        Feature(power("chess"), "Chess", "Play and learn", Icons.Outlined.Extension, Hub.Learn),
        Feature(Routes.NEWS, "News", "Headlines that matter", Icons.Outlined.Newspaper, Hub.Learn),
        Feature(Routes.QUOTES, "Quotes", "Words worth keeping", Icons.Outlined.FormatQuote, Hub.Learn),

        // Home & daily
        Feature(Routes.TASKS, "Tasks", "Today and upcoming", Icons.Outlined.Checklist, Hub.Home, true, "todo"),
        Feature(Routes.PLAN, "Plan my day", "Time-blocked around prayers", Icons.Outlined.CalendarMonth, Hub.Home, true, "schedule"),
        Feature(Routes.DAILY, "Daily life", "Weather, errands, the day", Icons.Outlined.WbCloudy, Hub.Home, true),
        Feature(power("pakistan"), "Pakistan", "Bills, prayer, rates, essentials", Icons.Outlined.Flag, Hub.Home, true, "wapda gas pta"),
        Feature(Routes.LOADSHEDDING, "Load-shedding", "Reminders before every power cut", Icons.Outlined.BatteryChargingFull, Hub.Home, words = "bijli light power cut wapda lesco"),
        Feature(Routes.PETS, "Pets", "Vaccines, vet visits, care reminders", Icons.Outlined.Pets, Hub.Home, words = "cat dog vet vaccine pet"),
        Feature(Routes.INVENTORY, "Home inventory", "What you own and its warranties", Icons.Outlined.Inventory2, Hub.Home, words = "warranty items insurance"),
        Feature(Routes.FAMILYBOARD, "Family board", "Groceries, chores, errands", Icons.Outlined.ShoppingCart, Hub.Home, words = "grocery sauda list chores"),
        Feature(Routes.ATLASCIRCLE, "Atlas Circle", "Private goals and rituals shared by choice", Icons.Outlined.Groups, Hub.Home, words = "circle trusted people shared goals rituals"),
        Feature(Routes.FAMILYBRIEF, "Family Day Ahead", "Today for everyone, one WhatsApp message", Icons.Outlined.FamilyRestroom, Hub.Home, words = "family group whatsapp plan"),
        Feature(Routes.EMERGENCY, "Emergency", "1122, Edhi, police: one tap, offline", Icons.Outlined.Emergency, Hub.Home, words = "1122 edhi ambulance police fire"),
        Feature(Routes.DOCEXPIRY, "Expiry dates", "CNIC, passport, licence, token tax", Icons.Outlined.Badge, Hub.Home, words = "cnic passport licence expire renew"),
        Feature(Routes.SURVIVAL, "Survival", "Offline know-how for emergencies", Icons.Outlined.HealthAndSafety, Hub.Home, words = "first aid emergency"),
        Feature(Routes.DRIVE, "Driving mode", "Big buttons, spoken answers", Icons.Outlined.DirectionsCar, Hub.Home),

        // Media
        Feature(Routes.MUSIC, "Music", "Stream, download, lyrics", Icons.Outlined.MusicNote, Hub.Media, true, "songs"),
        Feature(Routes.CINEMA, "Cinema", "Films and shows", Icons.Outlined.Movie, Hub.Media, true, "movies series"),
        Feature(Routes.PODCASTS, "Podcasts", "Shows and episodes", Icons.Outlined.Podcasts, Hub.Media, true),
        Feature("nx/flows", "Flows", "Your own automations for apps, AI and this phone", Icons.Outlined.AccountTree, Hub.Tools, true, "automation workflow n8n zapier ifttt trigger relay"),
        Feature("nx/appchat", "Talk to my apps", "Chat with Instagram, Gmail and any app through Atlas", Icons.Outlined.Forum, Hub.Tools, true, "chat apps instagram whatsapp assistant notifications"),
        Feature("nx/chief", "Chief", "Talk to Claude to change this app", Icons.Outlined.Handyman, Hub.Tools, true, "claude build update fix change add feature developer"),
        Feature("nx/joytime", "Joytime", "Stories to read, save and take offline", Icons.Outlined.AutoStories, Hub.Media, true, "stories read books reader"),
        Feature(Routes.IPTV, "Live TV", "Channels from around the world", Icons.Outlined.LiveTv, Hub.Media, true, "iptv tv"),

        // Memory
        Feature(Routes.CAPTURE, "Capture", "Throw anything in; Atlas makes it a task or event", Icons.Outlined.Inbox, Hub.Memory, true, "inbox flyer card event note"),
        Feature(Routes.NOTES, "Notes", "Your linked notes vault", Icons.Outlined.NoteAlt, Hub.Memory, true),
        Feature(Routes.DIARY, "Diary", "Write the day", Icons.Outlined.EditNote, Hub.Memory, true, "journal"),
        Feature(Routes.BRAIN, "Second brain", "Search everything you've saved", Icons.Outlined.Lightbulb, Hub.Memory, true),
        Feature(Routes.MEMORY, "Memories", "Read later, letters, recap, photos", Icons.Outlined.PhotoLibrary, Hub.Memory, true),
        Feature(Routes.SCREENSHOTS, "Screenshots", "Sorted by what's in them, searchable", Icons.Outlined.Screenshot, Hub.Memory, words = "screenshot organise clean receipts codes"),
        Feature(Routes.DAY, "A day", "Any single day, in full", Icons.Outlined.Today, Hub.Memory),
        Feature(Routes.REFLECT, "Reflect", "Wrapped, life in weeks, dreams", Icons.Outlined.AutoGraph, Hub.Memory),
        Feature(Routes.VOICE_JOURNAL, "Voice journal", "Talk it out; Atlas files it", Icons.Outlined.Mic, Hub.Memory),
        Feature(Routes.STORY_HUB, "Story", "The record of your days", Icons.Outlined.MenuBook, Hub.Memory),
        Feature("nx/constellation", "Mnemonic Constellation", "Your memories as a night sky", Icons.Outlined.AutoAwesome, Hub.Memory, words = "memory constellation stars sky drift"),
        Feature("nx/chronoverse", "Chronoverse", "Your whole life on one timeline", Icons.Outlined.Timeline, Hub.Memory, words = "timeline history past when"),
        Feature("nx/anamnesis", "Anamnesis", "The book of you", Icons.Outlined.MenuBook, Hub.Memory, words = "book of you year review"),
        Feature("nx/neurgraph", "Neurgraph", "What Atlas keeps and what it lets fade", Icons.Outlined.Psychology, Hub.Memory, words = "memory graph neurons forgetting"),
        Feature("nx/shadow", "Cognitive Shadow", "The part of you that argues back", Icons.Outlined.Psychology, Hub.Health, words = "shadow honest decide doubt mirror"),
        Feature(Routes.MEETING, "Meeting notes", "Decisions and actions", Icons.Outlined.Groups, Hub.Memory),

        // Work & growth
        Feature(Routes.DELIVERABLES, "Deliverables", "Order anything: images, theses, decks, sheets, apps, audio", Icons.Outlined.Inventory2, Hub.Work, true, "make create thesis report document image picture slides presentation spreadsheet app podcast code file output"),
        Feature(Routes.MISSIONS, "Missions", "Today's, and your goals", Icons.Outlined.Flag, Hub.Work, true, "goals"),
        Feature(Routes.HABITS, "Habits", "One tap a day, with a streak", Icons.Outlined.CheckCircle, Hub.Work, words = "habit streak daily"),
        Feature(Routes.GROWTH, "Growth", "Challenges, streaks, badges", Icons.Outlined.EmojiEvents, Hub.Work, true),
        Feature(Routes.INSIGHTS, "Insights", "What your numbers add up to", Icons.Outlined.Insights, Hub.Work, true),
        Feature(Routes.UNDERSTAND_HUB, "Understand", "Patterns, simulator, mirror", Icons.Outlined.QueryStats, Hub.Work, true),
        Feature(Routes.COMMIT, "Commit", "48-hour cart, stakes", Icons.Outlined.Handshake, Hub.Work),
        Feature(Routes.SELF, "Progress", "Level, XP and history", Icons.Outlined.TrendingUp, Hub.Work),
        Feature(Routes.GROW_HUB, "Grow", "Missions, streaks and commitments", Icons.Outlined.Park, Hub.Work),
        Feature(Routes.LIFESIM, "Life simulator", "Your averages, years forward", Icons.Outlined.Timeline, Hub.Work),
        Feature(Routes.TOWN, "Pixel Town", "Your world, growing with you", Icons.Outlined.Castle, Hub.Work),
        Feature(Routes.COUNCIL, "Council", "Six advisors for a decision", Icons.Outlined.Diversity3, Hub.Work, words = "decide"),
        Feature(Routes.COURT, "Decision court", "Argued, judged, checked later", Icons.Outlined.Gavel, Hub.Work),

        // Social
        Feature(Routes.PETCHAT, "Chat with your pet", "Games, jokes, and a friend who remembers you", Icons.Outlined.Pets, Hub.Social, true, "pet companion buddy chat play game"),
        Feature(Routes.CATCHUP, "Catch me up", "Everything that came in, in five lines", Icons.Outlined.MarkChatUnread, Hub.Social, true, "notifications summary missed"),
        Feature(Routes.FREETIMES, "Share free times", "Open windows around prayers and your calendar", Icons.Outlined.EventAvailable, Hub.Social, words = "meeting availability schedule when free"),
        Feature(Routes.RAMBLER, "Rambler", "Talk messily, get a clean message", Icons.Outlined.RecordVoiceOver, Hub.Social, words = "dictate polish message voice"),
        Feature(Routes.NEGOTIATOR, "Negotiator", "Complaints and refunds, worded to win", Icons.Outlined.Gavel, Hub.Social, words = "complaint refund dispute landlord ptcl"),
        Feature(Routes.PEOPLE, "People", "Promises, mentor, sparring", Icons.Outlined.People, Hub.Social, true, "friends family"),
        Feature(power("twin"), "Reply twin", "Replies in your voice", Icons.Outlined.Forum, Hub.Social, true, "whatsapp reply"),
        Feature(Routes.CALLCOACH, "Call coach", "Prep before, commitments after", Icons.Outlined.Call, Hub.Social, true),
        Feature(Routes.ATTENTION, "Attention", "Digest, smart replies, wind-down", Icons.Outlined.NotificationsActive, Hub.Social, true, "notifications"),
        Feature(Routes.REHEARSE, "Rehearsal coach", "Pace, fillers, structure", Icons.Outlined.RecordVoiceOver, Hub.Social),

        // Tools
        Feature(Routes.SCAN, "Scanner", "Documents, text, codes", Icons.Outlined.DocumentScanner, Hub.Tools, true, "ocr qr"),
        Feature(Routes.TOOLKIT, "Toolkit", "Everyday utilities", Icons.Outlined.Handyman, Hub.Tools, true, "calculator converter"),
        Feature(Routes.BROWSER, "Browser", "Private, ad-free browsing", Icons.Outlined.Public, Hub.Tools, true, "web"),
        Feature(Routes.APPSTORE, "App Store", "Apps from everywhere", Icons.Outlined.Storefront, Hub.Tools, true, "apk"),
        Feature(Routes.TOOLS, "Tools", "More small tools", Icons.Outlined.Build, Hub.Tools),
        Feature(Routes.VAULT, "Vault", "Locked files and notes", Icons.Outlined.Lock, Hub.Tools),
        Feature(power("authenticator"), "Authenticator", "2FA codes", Icons.Outlined.Key, Hub.Tools, words = "2fa otp"),
        Feature(power("documents"), "Documents", "Your IDs and papers", Icons.Outlined.Badge, Hub.Tools, words = "cnic passport"),
        Feature(power("summarise"), "Summarise", "Anything, short", Icons.Outlined.Summarize, Hub.Tools),
        Feature(power("guardian"), "Guardian", "Scam, SOS and crash protection", Icons.Outlined.GppGood, Hub.Tools, words = "sos scam"),
        Feature(power("booster"), "Game booster", "Everything out of the way", Icons.Outlined.SportsEsports, Hub.Tools, words = "games"),
        Feature(Routes.PHONE, "Phone control", "Settings Atlas can change", Icons.Outlined.PhoneAndroid, Hub.Tools),
        Feature(Routes.PRIVACY_ALERTS, "Privacy alerts", "Apps that can use your mic, camera, location", Icons.Outlined.PrivacyTip, Hub.Tools, words = "permissions tracking spy"),
        Feature(Routes.QRSTUDIO, "QR studio", "Make codes for Wi-Fi, contacts, WhatsApp", Icons.Outlined.QrCode2, Hub.Tools, words = "qr code wifi generate"),
        Feature(Routes.FIREWALL, "Agent firewall", "What Atlas is never allowed to do", Icons.Outlined.Shield, Hub.Tools, words = "block permission safety agent"),
        Feature(Routes.SKILLS, "Skills", "Saved action sequences", Icons.Outlined.AutoFixHigh, Hub.Tools, words = "macro routine automation"),
        Feature(Routes.KNOWSME, "What Atlas knows", "Beliefs about you; forget any", Icons.Outlined.Person, Hub.Memory, words = "memory privacy forget profile"),
        Feature(Routes.RESEARCH, "Deep research", "Many searches, one report with sources", Icons.Outlined.TravelExplore, Hub.Learn, true, "research report investigate sources"),
        Feature(Routes.FACTCHECK, "Fact check", "Is that forward true?", Icons.Outlined.FactCheck, Hub.Learn, words = "fake news verify rumour whatsapp forward"),
        Feature(Routes.CALC, "Calculator", "Exact sums, lakh and crore, loans, word problems", Icons.Outlined.Calculate, Hub.Tools, words = "math calculate emi loan percent zakat"),
        Feature(Routes.UNIINBOX, "Inbox", "Every app's messages, what needs you first", Icons.Outlined.Inbox, Hub.Social, true, "messages whatsapp notifications reply unread"),
        Feature(Routes.FINTWIN, "Financial twin", "Your money, run forward with what-ifs", Icons.Outlined.ShowChart, Hub.Money, true, "future savings projection goal forecast"),
        Feature(Routes.MEMCARDS, "Memory palace", "Flashcards that return before you forget", Icons.Outlined.Style, Hub.Learn, words = "flashcards study revise spaced repetition memorise"),
        Feature(Routes.WHEREIS, "Where is it?", "Remember where you put things", Icons.Outlined.Place, Hub.Home, words = "lost keys find object kahan"),
        Feature(Routes.RADAR, "Radar", "Trends caught early", Icons.Outlined.Radar, Hub.Work, words = "trend warning spending"),
        Feature(Routes.PASSWORDS, "Passwords", "Logins, encrypted on this phone", Icons.Outlined.Key, Hub.Tools, words = "password login vault"),
        Feature(Routes.ACTIVITY, "Activity", "Receipts for everything Atlas did", Icons.Outlined.ReceiptLong, Hub.Tools, words = "log history actions"),
        Feature(Routes.SAFETY, "Safety", "Lost mode, caller check, follow-ups", Icons.Outlined.Security, Hub.Tools, true, "lost stolen phone scam call"),
        Feature(Routes.AUTOFILL, "Autofill", "Fill forms in any app from your details", Icons.Outlined.EditNote, Hub.Tools, words = "form fill cnic address profile"),
        Feature(Routes.MYWIDGET, "My widget", "Choose what your home-screen widget shows", Icons.Outlined.Widgets, Hub.Tools, words = "widget home screen"),
        Feature(Routes.APPEARANCE, "Look & comfort", "Text size, true black, reduce motion", Icons.Outlined.FormatSize, Hub.Tools, words = "font dark oled theme accessibility"),
        Feature(Routes.POWERS, "Powers", "Every superpower in one place", Icons.Outlined.Bolt, Hub.Tools),

        // Create
        Feature(power("image"), "Imagine", "Make images from words", Icons.Outlined.Palette, Hub.Create, true, "image generate"),
        Feature(Routes.MAGICCAPTURE, "Magic Capture", "Shoots itself when everyone smiles", Icons.Outlined.CameraEnhance, Hub.Create, true, "camera smile group photo selfie"),
        Feature(Routes.STUDIO, "Studio", "Your book, legacy, drafts", Icons.Outlined.Draw, Hub.Create, true, "write"),
        Feature(Routes.VOICE, "Voice", "Bubble, driving, journal, coach", Icons.Outlined.GraphicEq, Hub.Create, true),
        Feature(Routes.MIND, "Mind", "Charts, life wheel, values, ideas", Icons.Outlined.Psychology, Hub.Create, true),
        Feature(Routes.LAB, "Lab", "Experiments and bets", Icons.Outlined.Science, Hub.Create),
        Feature(Routes.MIRROR, "Mirror", "Compounding, integrity, regret", Icons.Outlined.Flip, Hub.Create),

        // Travel & senses
        Feature(Routes.MAPS, "Maps", "Navigate, explore, offline", Icons.Outlined.Map, Hub.Travel, true, "directions route"),
        Feature(Routes.WEATHER, "Weather", "Forecast, rain chance and smog", Icons.Outlined.WbSunny, Hub.Travel, true, "weather rain smog aqi temperature"),
        Feature(Routes.TRAVELPLAN, "Travel planner", "Itinerary, budget and packing list", Icons.Outlined.Luggage, Hub.Travel, words = "trip hunza murree umrah plan"),
        Feature(Routes.VEHICLE, "Vehicle", "Fuel, mileage and oil changes", Icons.Outlined.DirectionsCar, Hub.Travel, words = "car bike petrol fuel mileage service"),
        Feature(power("translator"), "Translator", "Speak, type or point", Icons.Outlined.Translate, Hub.Travel, true),
        Feature(power("ar"), "AR view", "Look around with labels", Icons.Outlined.ViewInAr, Hub.Travel, true, "qibla"),
        Feature(power("lens"), "Lens", "Understand what the camera sees", Icons.Outlined.CenterFocusStrong, Hub.Travel, true),
        Feature(power("circle"), "Circle to search", "Circle anything on screen", Icons.Outlined.TouchApp, Hub.Travel),
        Feature(power("photos"), "Photo search", "Find photos by what's in them", Icons.Outlined.ImageSearch, Hub.Travel),
        Feature(power("earbuds"), "Earbud mode", "Atlas in your ears", Icons.Outlined.Headphones, Hub.Travel),
        Feature(power("live"), "Live voice", "Talk naturally, hands-free", Icons.Outlined.KeyboardVoice, Hub.Travel),
    )

    /** Features of a suite, minus any the user hid in "Tidy my tools" (search still reaches those). */
    fun of(hub: Hub): List<Feature> {
        val hidden = com.nizam.app.net.Router.appContext?.let { com.nizam.app.feature.modes.Pins.hidden(it) }.orEmpty()
        // tools you open most rise to the top of their suite; the rest keep their order
        val used = com.nizam.app.net.Router.appContext?.let { com.nizam.app.feature.nx.Mind.counts(it) }.orEmpty()
        val list = all.filter { it.hub == hub && it.route !in hidden }
        return if (used.isEmpty()) list else list.sortedByDescending { used[it.route.removePrefix("nx/")] ?: 0 }
    }
    fun top(hub: Hub) = of(hub).filter { it.top }.take(4)
    fun more(hub: Hub) = of(hub).let { f -> f - top(hub).toSet() }
    fun grouped(hub: Hub): List<Pair<String, List<Feature>>> = more(hub).groupBy { it.group }
        .toList().sortedBy { GROUPS.indexOf(it.first).let { i -> if (i < 0) 99 else i } }
    fun byRoute(route: String?) = all.firstOrNull { it.route == route }

    fun search(q: String): List<Feature> {
        val t = q.trim().lowercase()
        if (t.isBlank()) return emptyList()
        return all.filter { f -> listOf(f.title, f.blurb, f.words, f.hub.label).any { it.lowercase().contains(t) } }
            .sortedBy { if (it.title.lowercase().startsWith(t)) 0 else 1 }
    }
}

/** Remembers which features you open, so the Life tab can offer them back and hubs can rise. */
object RecentFeatures {
    private fun p(c: Context) = c.getSharedPreferences("recent_features", 0)

    fun record(c: Context, route: String?) {
        val f = HubRegistry.byRoute(route) ?: return
        val list = listOf(f.route) + recent(c).filter { it != f.route }
        val counts = p(c).getInt("n:${f.route}", 0) + 1
        // which hour of the day it was opened — Today learns what you want when
        val hk = "h:${f.route}:${java.time.LocalTime.now().hour}"
        p(c).edit().putString("list", list.take(12).joinToString("\u0001")).putInt("n:${f.route}", counts)
            .putInt(hk, p(c).getInt(hk, 0) + 1).apply()
    }

    fun recent(c: Context): List<String> = p(c).getString("list", "").orEmpty().split("\u0001").filter { it.isNotBlank() }

    fun uses(c: Context, route: String) = p(c).getInt("n:$route", 0)

    /** How often a route is opened around this hour (the hour itself counts double). */
    fun around(c: Context, route: String, hour: Int = java.time.LocalTime.now().hour): Int =
        (-1..1).sumOf { d -> p(c).getInt("h:$route:${(hour + d + 24) % 24}", 0) * (if (d == 0) 2 else 1) }
}
