package com.nizam.app.ui.nav

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.fillMaxHeight

import com.nizam.app.ui.design.backdropSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Today as OutlinedToday
import androidx.compose.material.icons.outlined.GridView as OutlinedGridView
import androidx.compose.material.icons.outlined.AutoAwesome as OutlinedAutoAwesome
import androidx.compose.material.icons.outlined.Person as OutlinedPerson
import androidx.compose.material.icons.outlined.Search as OutlinedSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.outlined.Apartment as OutlinedApartment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nizam.app.ui.design.frost
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.CuratorScreen
import com.nizam.app.feature.browser.BrowserScreen
import com.nizam.app.feature.council.CouncilScreen
import com.nizam.app.feature.profile.ProfileScreen
import com.nizam.app.feature.town.TownScreen
import com.nizam.app.ui.screens.me.MeScreen
import com.nizam.app.ui.screens.apps.AppsScreen
import com.nizam.app.ui.screens.hub.AtlasHub
import com.nizam.app.feature.finale.FinaleScreen
import com.nizam.app.feature.bots.BotNav
import com.nizam.app.feature.bots.BotThreadScreen
import com.nizam.app.feature.bots.BotsScreen
import com.nizam.app.ui.screens.hub.GrowHub
import com.nizam.app.ui.screens.hub.StoryHub
import com.nizam.app.ui.screens.hub.UnderstandHub
import com.nizam.app.ui.screens.hub.WellbeingHub
import com.nizam.app.feature.podcasts.PodcastNav
import com.nizam.app.feature.podcasts.PodcastShowScreen
import com.nizam.app.feature.podcasts.PodcastsScreen
import com.nizam.app.feature.podcasts.PodcastPlayerScreen
import com.nizam.app.feature.iptv.IptvScreen
import com.nizam.app.feature.iptv.IptvPlayerScreen
import com.nizam.app.feature.insight.UnderstandScreen
import com.nizam.app.feature.brain.BrainScreen
import com.nizam.app.feature.brain.DayScreen
import com.nizam.app.feature.shield.ShieldScreen
import com.nizam.app.feature.planner.PlannerScreen
import com.nizam.app.feature.language.LanguageScreen
import com.nizam.app.feature.scan.ScanScreen
import com.nizam.app.feature.growth.GrowthScreen
import com.nizam.app.feature.presence.DrivingScreen
import com.nizam.app.feature.attention.AttentionScreen
import com.nizam.app.feature.cinema.CinemaNav
import com.nizam.app.feature.mind.MindScreen
import com.nizam.app.feature.daily.DailyScreen
import com.nizam.app.feature.platform.PlatformScreen
import com.nizam.app.feature.wellbeing.CalmScreen
import com.nizam.app.feature.body.BedsideScreen
import com.nizam.app.feature.commit.CommitScreen
import com.nizam.app.feature.work.MeetingScreen
import com.nizam.app.feature.lab.LabScreen
import com.nizam.app.feature.lab.MirrorScreen
import com.nizam.app.feature.presence.PresenceScreen
import com.nizam.app.feature.final.StudioScreen
import com.nizam.app.feature.final.RulesScreen
import com.nizam.app.feature.agent.WorkforceScreen
import com.nizam.app.feature.mcp.PlaygroundScreen
import com.nizam.app.feature.money.AutoMoneyScreen
import com.nizam.app.feature.agent.SchedulesScreen
import com.nizam.app.feature.reflect.ReflectScreen
import com.nizam.app.feature.world.NightCompanionScreen
import com.nizam.app.feature.world.PeopleScreen
import com.nizam.app.feature.body.FlipFocusScreen
import com.nizam.app.feature.body.RepCounterScreen
import com.nizam.app.feature.body.SmartWakeScreen
import com.nizam.app.feature.body.SnapPlateScreen
import com.nizam.app.feature.body.VoiceCoachScreen
import com.nizam.app.feature.wellbeing.FutureYouScreen
import com.nizam.app.feature.wellbeing.PauseSettingsScreen
import com.nizam.app.feature.wellbeing.ReframeScreen
import com.nizam.app.feature.memory.MemoryScreen
import com.nizam.app.feature.cinema.MediaCenterScreen
import com.nizam.app.feature.cinema.MetaScreen
import com.nizam.app.feature.cinema.FilmScreen
import com.nizam.app.feature.cinema.PlayerScreen
import com.nizam.app.feature.presence.RehearsalScreen
import com.nizam.app.feature.presence.VoiceHubScreen
import com.nizam.app.feature.presence.VoiceJournalScreen
import com.nizam.app.feature.phone.PhoneControlScreen
import com.nizam.app.feature.diary.DiaryScreen
import com.nizam.app.feature.diet.DietScreen
import com.nizam.app.feature.finance.FinanceScreen
import com.nizam.app.feature.gym.GymScreen
import com.nizam.app.feature.hadith.HadithScreen
import com.nizam.app.feature.learning.LearningScreen
import com.nizam.app.feature.library.LibraryScreen
import com.nizam.app.feature.learning.CoursesScreen
import com.nizam.app.feature.missions.MissionsScreen
import com.nizam.app.feature.news.NewsScreen
import com.nizam.app.feature.dynamic.DynamicModuleScreen
import com.nizam.app.feature.dynamic.ModuleSettingsScreen
import com.nizam.app.feature.self.SelfScreen
import com.nizam.app.feature.toolkit.ToolkitScreen
import com.nizam.app.feature.vault.VaultScreen
import com.nizam.app.feature.progress.InsightsScreen
import com.nizam.app.feature.notes.NotesScreen
import com.nizam.app.feature.prayer.PrayerScreen
import com.nizam.app.feature.quotes.QuotesScreen
import com.nizam.app.feature.quran.QuranScreen
import com.nizam.app.feature.survival.SurvivalScreen
import com.nizam.app.ui.screens.modules.ModulesScreen
import com.nizam.app.ui.screens.search.SearchScreen
import com.nizam.app.ui.screens.settings.SettingsScreen
import com.nizam.app.ui.screens.tasks.TasksScreen
import com.nizam.app.ui.screens.today.TodayScreen

object Routes {
    const val TODAY = "today"
    const val MODULES = "modules"
    const val ASK = "ask"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val TASKS = "tasks"
    const val PRAYER = "prayer"
    const val FINANCE = "finance"
    const val DIET = "diet"
    const val GYM = "gym"
    const val NOTES = "notes"
    const val LIBRARY = "library"
    const val DIARY = "diary"
    const val QURAN = "quran"
    const val HADITH = "hadith"
    const val LEARNING = "learning"
    const val SURVIVAL = "survival"
    const val QUOTES = "quotes"
    const val COURSES = "courses"
    const val ASSISTANT = "assistant"
    const val BROWSER = "browser"
    const val PHONE = "phone"
    const val COUNCIL = "council"
    const val PROFILE = "profile"
    const val TOWN = "town"
    const val ME = "me"
    const val APPS = "apps"
    const val PODCASTS = "podcasts"
    const val PODCAST_SHOW = "podcastshow"
    const val PODCAST_PLAYER = "podcastplayer"
    const val IPTV = "iptv"
    const val IPTV_PLAYER = "iptvplayer"
    const val UNDERSTAND = "understand"
    const val BRAIN = "brain"
    const val DAY = "day"
    const val SHIELD = "shield"
    const val PLAN = "plan"
    const val LANGUAGE = "language"
    const val SCAN = "scan"
    const val GROWTH = "growth"
    const val VOICE = "voice"
    const val DRIVE = "drive"
    const val VOICE_JOURNAL = "voicejournal"
    const val REHEARSE = "rehearse"
    const val ATTENTION = "attention"
    const val CINEMA = "cinema"
    const val FILM = "film"
    const val PLAYER = "player"
    const val META = "meta"
    const val MIND = "mind"
    const val MEMORY = "memory"
    const val DAILY = "daily"
    const val TOOLS = "tools"
    const val PAUSE = "pause"
    const val CALM = "calm"
    const val FUTURE = "future"
    const val REFRAME = "reframe"
    const val REPS = "reps"
    const val PLATE = "plate"
    const val COACH = "coach"
    const val FLIP = "flip"
    const val BEDSIDE = "bedside"
    const val WAKE = "smartwake"
    const val COMMIT = "commit"
    const val MEETING = "meeting"
    const val AUTOMONEY = "automoney"
    const val SCHEDULES = "schedules"
    const val HQ = "hq"
    const val LAB = "lab"
    const val PLAYGROUND = "playground"
    const val MIRROR = "mirror"
    const val PRESENCE = "presence"
    const val STUDIO = "studio"
    const val RULES = "rules"
    const val WORKFORCE = "workforce"
    const val JARVIS = "jarvis"
    const val BARAKAH = "barakah"
    const val LIFESIM = "lifesim"
    const val COURT = "court"
    const val CALLCOACH = "callcoach"
    const val RECEIPTS = "receipts"
    const val DEEN = "deen"
    const val MODELS = "models"
    const val KEYS = "keys"
    const val COMPANION = "companion"
    const val SECURITY = "security"
    const val MAPS = "maps"
    const val APPSTORE = "appstore"
    const val MUSIC = "music"
    const val POWERS = "powers"
    const val ATLAS_HUB = "atlashub"
    const val UNDERSTAND_HUB = "understandhub"
    const val STORY_HUB = "storyhub"
    const val GROW_HUB = "growhub"
    const val WELLBEING_HUB = "wellbeinghub"
    const val YOU = "you"
    const val BOTS = "bots"
    const val BOT_THREAD = "botthread"
    const val REFLECT = "reflect"
    const val PEOPLE = "people"
    const val NIGHT = "night"
    const val NEWS = "news"
    const val INSIGHTS = "insights"
    const val MISSIONS = "missions"
    const val SELF = "self"
    const val AGENT = "agent"
    const val VAULT = "vault"
    const val TOOLKIT = "toolkit"
    const val MODULES_CLASSIC = "modules-classic"
    const val APPEARANCE = "appearance"
    const val AMBIENT = "ambient"
    const val PETCHAT = "petchat"
    const val FIREWALL = "firewall"
    const val SKILLS = "skills"
    const val KNOWSME = "knowsme"
    const val RADAR = "radar"
    const val RESEARCH = "research"
    const val FACTCHECK = "factcheck"
    const val CALC = "calc"
    const val WHEREIS = "whereis"
    const val MEMCARDS = "memcards"
    const val FINTWIN = "fintwin"
    const val UNIINBOX = "uniinbox"
    const val CATCHUP = "catchup"
    const val RAMBLER = "rambler"
    const val SAFETY = "safety"
    const val MYWIDGET = "mywidget"
    const val AUTOFILL = "autofill"
    const val FREETIMES = "freetimes"
    const val PRIVACY_ALERTS = "privacyalerts"
    const val LOADSHEDDING = "loadshedding"
    const val FAMILYBRIEF = "familybrief"
    const val SCREENSHOTS = "screenshots"
    const val BILLSCAN = "billscan"
    const val MAGICCAPTURE = "magiccapture"
    const val DUAS = "duas"
    const val HABITS = "habits2"
    const val TIMER = "timer"
    const val ISLAMICCAL = "islamiccal"
    const val ISLAMLIB = "islamlib"
    const val DELIVERABLES = "deliverables"
    const val DAILYPATH = "dailypath"
    const val MASJIDS = "masjids"
    const val WEATHER = "weather"
    const val QRSTUDIO = "qrstudio"
    const val VEHICLE = "vehicle"
    const val PETS = "pets"
    const val INVENTORY = "inventory"
    const val TRAVELPLAN = "travelplan"
    const val MEDICINES = "medicines"
    const val EMERGENCY = "emergency"
    const val DOCEXPIRY = "docexpiry"
    const val SPLITS = "splits"
    const val RATES = "rates"
    const val COMMITTEES = "committees"
    const val DEBTS = "debts"
    const val SUBSCRIPTIONS = "subscriptions"
    const val PRICEWATCH = "pricewatch"
    const val ACTIVITY = "activity"
    const val CAPTURE = "capture"
    const val NEGOTIATOR = "negotiator"
    const val PASSWORDS = "passwords"
    const val FAMILYBOARD = "familyboard"
    const val ATLASCIRCLE = "atlas-circle"
    const val HUB = "hub/{name}"
    fun hub(name: String) = "hub/$name"
    const val DYNAMIC = "module/{key}"
    const val MODULE_SETTINGS = "module-settings/{key}"
    fun moduleSettings(key: String) = "module-settings/$key"
    fun dynamic(key: String) = "module/$key"
}

private data class BottomItem(val route: String, val label: String, val icon: ImageVector, val idle: ImageVector)

// Outlined when idle, filled when chosen — the only signal the tab bar needs.
/** Keyboard shortcuts, wired to the live NavController while the app is on screen. */
object KeyNav {
    @Volatile var nav: ((String) -> Unit)? = null
    @Volatile var back: (() -> Boolean)? = null
    fun handle(e: android.view.KeyEvent): Boolean {
        val go = nav ?: return false
        val k = e.keyCode
        return when {
            e.isCtrlPressed && (k == android.view.KeyEvent.KEYCODE_K || k == android.view.KeyEvent.KEYCODE_F) -> { go(Routes.ASK); true }
            e.isCtrlPressed && k == android.view.KeyEvent.KEYCODE_COMMA -> { go(Routes.SETTINGS); true }
            e.isCtrlPressed && k == android.view.KeyEvent.KEYCODE_J -> { go(Routes.ASK); true }
            e.isAltPressed && k in android.view.KeyEvent.KEYCODE_1..android.view.KeyEvent.KEYCODE_9 ->
                bottomItems.getOrNull(k - android.view.KeyEvent.KEYCODE_1)?.let { go(it.route); true } ?: false
            k == android.view.KeyEvent.KEYCODE_ESCAPE -> back?.invoke() ?: false
            else -> false
        }
    }
}

/** Off since the merge: one navigation (spaces + dock). Kept so it can be switched back on in one place. */
private const val SHOW_CLASSIC_TABS = false

private val bottomItems = listOf(
    BottomItem(Routes.TODAY, "Today", Icons.Filled.Today, Icons.Outlined.OutlinedToday),
    BottomItem(Routes.ASK, "Ask", Icons.Filled.AutoAwesome, Icons.Outlined.OutlinedAutoAwesome),
    BottomItem(Routes.MODULES, "Life", Icons.Filled.GridView, Icons.Outlined.OutlinedGridView),
    BottomItem(Routes.ME, "You", Icons.Filled.Person, Icons.Outlined.OutlinedPerson)
)

@Composable
fun NizamApp(container: AppContainer, startOnCurator: Boolean = false, startRoute: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Refresh (F5): when Atlas finishes refreshing, the screen you're on is rebuilt with fresh data
    val refreshTick by com.nizam.app.feature.safe.AtlasRefresh.tick.collectAsState()
    var seenRefresh by remember { androidx.compose.runtime.mutableIntStateOf(refreshTick) }
    androidx.compose.runtime.LaunchedEffect(refreshTick) { com.nizam.app.ui.design.guard {
        if (refreshTick != seenRefresh) {
            seenRefresh = refreshTick
            val entry = navController.currentBackStackEntry
            val pattern = entry?.destination?.route
            if (pattern != null && pattern !in setOf(Routes.TODAY, Routes.PLAYER, Routes.IPTV_PLAYER, Routes.BEDSIDE, Routes.AMBIENT)) {
                var actual: String = pattern
                Regex("\\{(\\w+)\\}").findAll(pattern).forEach { m ->
                    @Suppress("DEPRECATION") val v = entry.arguments?.get(m.groupValues[1])?.toString().orEmpty()
                    actual = actual.replace(m.value, v)
                }
                if (!actual.contains("{")) navController.navigate(actual) { popUpTo(pattern) { inclusive = true }; launchSingleTop = false }
            }
        }
    } }
    var guidedVisible by remember { mutableStateOf(false) }
    var guidedPaused by remember { mutableStateOf(false) }
    var guidedStep by remember { mutableStateOf(0) }
    var guidedTabs by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var lastGuidedRoute by remember { mutableStateOf(currentRoute) }
    val guidedTarget = guidedTabs.getOrNull(guidedStep)?.first
    androidx.compose.runtime.LaunchedEffect(currentRoute, guidedVisible, guidedPaused, guidedStep, guidedTarget) { com.nizam.app.ui.design.guard {
        val changedRoute = currentRoute != lastGuidedRoute
        if (guidedVisible && !guidedPaused && changedRoute && currentRoute == guidedTarget) {
            if (guidedStep + 1 >= guidedTabs.size) guidedVisible = false else guidedStep++
        }
        lastGuidedRoute = currentRoute
    } }

    val tabRoutes = bottomItems.map { it.route }.toSet()
    // tablets, unfolded foldables and landscape: a side rail instead of the bottom bar
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val onSubScreen = currentRoute != null && currentRoute !in tabRoutes

    val snackbarHost = remember { androidx.compose.material3.SnackbarHostState() }
    val feedbackScope = com.nizam.app.ui.design.rememberSafeScope()
    androidx.compose.runtime.LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { com.nizam.app.ui.components.Feedback.attach(snackbarHost, feedbackScope) } }
    val navCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        // reopen where you were: offered once per launch when the last screen was used in the past 12 hours
        if (startRoute != null || startOnCurator) return@LaunchedEffect
        val rp = navCtx.getSharedPreferences("resume", 0)
        val last = rp.getString("route", null) ?: return@LaunchedEffect
        if (System.currentTimeMillis() - rp.getLong("at", 0L) > 12 * 3_600_000L) return@LaunchedEffect
        val name = last.substringAfterLast('/').replace('_', ' ').replaceFirstChar { it.uppercase() }
        val r = snackbarHost.showSnackbar("Continue in $name?", actionLabel = "Open", duration = androidx.compose.material3.SnackbarDuration.Long)
        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) runCatching { navController.navigate(last) }
    } }
    androidx.compose.runtime.DisposableEffect(navController) {
        // what you open feeds "Jump back in" and lets each hub's most-used tools rise
        val l = androidx.navigation.NavController.OnDestinationChangedListener { _, _, args ->
            val r = navController.currentBackStackEntry?.destination?.route
            val concrete = when {
                r == null -> null
                r.contains("{id}") -> r.replace("{id}", args?.getString("id").orEmpty())
                else -> r
            }
            // private areas never appear in Recent, resume prompts or habit suggestions
            val privateArea = concrete == "nx/joytime"
            if (!privateArea) com.nizam.app.ui.screens.hubs.RecentFeatures.record(navCtx, concrete)
            if (!privateArea && concrete != null && concrete !in tabRoutes && concrete != Routes.PLAYER && concrete != Routes.IPTV_PLAYER)
                navCtx.getSharedPreferences("resume", 0).edit().putString("route", concrete).putLong("at", System.currentTimeMillis()).apply()
            if (!privateArea && concrete != null && com.nizam.app.ui.screens.hubs.HubRegistry.byRoute(concrete) != null) com.nizam.app.feature.brain2.Habits2.record(navCtx, concrete)
        }
        navController.addOnDestinationChangedListener(l)
        onDispose { navController.removeOnDestinationChangedListener(l) }
    }
    androidx.compose.runtime.DisposableEffect(navController) {
        com.nizam.app.ui.screens.search.AtlasNav.go = { r -> runCatching { navController.navigate(r) { launchSingleTop = true } } }
        KeyNav.nav = { r -> runCatching { navController.navigate(r) { popUpTo(Routes.TODAY) { saveState = true }; launchSingleTop = true; restoreState = true } } }
        KeyNav.back = { navController.previousBackStackEntry != null && navController.popBackStack() }
        onDispose { com.nizam.app.ui.screens.search.AtlasNav.go = null; KeyNav.nav = null; KeyNav.back = null }
    }
    val backdrop = com.nizam.app.ui.design.rememberBackdrop()
    androidx.compose.runtime.CompositionLocalProvider(com.nizam.app.ui.design.LocalBackdrop provides backdrop) {
    Scaffold(
        snackbarHost = {
            // a frosted pill instead of a flat bar; actions (Undo) sit on the right in the accent colour
            androidx.compose.material3.SnackbarHost(snackbarHost) { data ->
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                        .frost(com.nizam.app.ui.design.Radius.pill).padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    data.visuals.actionLabel?.let { a ->
                        androidx.compose.material3.TextButton(onClick = { data.performAction() }) {
                            Text(a, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        topBar = {
            if (currentRoute != Routes.PLAYER && currentRoute != Routes.BEDSIDE && currentRoute != Routes.IPTV_PLAYER && currentRoute != Routes.AMBIENT && currentRoute != Routes.TODAY) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 12.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onSubScreen) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Back", modifier = Modifier.size(30.dp))
                        }
                    } else {
                        Spacer(Modifier.size(30.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    com.nizam.app.feature.safe.RefreshButton(container, currentRoute)
                    TextButton(onClick = {
                        val currentIndex = bottomItems.indexOfFirst { it.route == currentRoute }
                        guidedTabs = (1..bottomItems.size).map { offset ->
                            val target = (currentIndex + offset + bottomItems.size) % bottomItems.size
                            bottomItems[target].route to bottomItems[target].label
                        }
                        guidedStep = 0
                        guidedPaused = false
                        lastGuidedRoute = currentRoute
                        guidedVisible = true
                    }) { Text("?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (onSubScreen) {
                        IconButton(onClick = {
                            navController.navigate(Routes.TODAY) {
                                popUpTo(Routes.TODAY) { inclusive = false }
                                launchSingleTop = true
                            }
                        }) {
                            Icon(Icons.Outlined.OutlinedToday, contentDescription = "Back to today",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Spacer(Modifier.size(30.dp))
                    }
                }
            }
        },
        bottomBar = {
            if (currentRoute != Routes.PLAYER && currentRoute != Routes.BEDSIDE && currentRoute != Routes.IPTV_PLAYER && currentRoute != Routes.AMBIENT && currentRoute != Routes.TODAY) {
                androidx.compose.foundation.layout.Column(Modifier.padding(start = if (wide) 80.dp else 0.dp)) {
                com.nizam.app.ui.design.ContextStrip(currentRoute) { r -> navController.navigate(r) { launchSingleTop = true } }
                // the classic tab bar is retired: the spaces and the dock are Atlas's one navigation (back returns home)
                if (!wide && SHOW_CLASSIC_TABS) FloatingNav(
                    items = bottomItems.map { NavTab(it.route, it.label, it.icon, it.idle) },
                    current = currentRoute,
                    onSelect = { route ->
                        if (currentRoute != route) navController.navigate(route) {
                            popUpTo(Routes.TODAY) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    // hold the centre tab to talk to Atlas straight away
                    onHold = { route -> if (route == Routes.ASK) navController.navigate(Routes.VOICE) { launchSingleTop = true } },
                    highlightRoute = if (guidedVisible && !guidedPaused) guidedTarget else null
                )
                }
            }
        }
    ) { innerPadding ->
        // the chosen background art, full screen: glass bars blur this
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            if (currentRoute != Routes.PLAYER && currentRoute != Routes.IPTV_PLAYER && currentRoute != Routes.BEDSIDE) com.nizam.app.ui.design.StyleBackground()
        }
        // the living sky, softly behind every screen (not behind video players)
        val skyLat by container.settingsRepository.latitude.collectAsState(initial = 31.5204)
        val skyLng by container.settingsRepository.longitude.collectAsState(initial = 74.3587)
        val ambience = com.nizam.app.ui.design.rememberAmbience(skyLat, skyLng)
        val living = com.nizam.app.ui.design.AmbienceEngine.on(androidx.compose.ui.platform.LocalContext.current)
        if (living && currentRoute != Routes.MAPS && currentRoute != Routes.PLAYER && currentRoute != Routes.IPTV_PLAYER && currentRoute != Routes.BEDSIDE)
            com.nizam.app.ui.design.AmbientSky(ambience, Modifier.fillMaxWidth().height(if (currentRoute == Routes.TODAY) 330.dp else 180.dp),
                strength = if (currentRoute == Routes.TODAY) 1f else 0.45f, animate = currentRoute == Routes.TODAY,
                dark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
        com.nizam.app.ui.design.CurrentAmbience.value = ambience
        val ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
        fun isTab(r: String?) = r in tabRoutes
        NavHost(
            // Between tabs: a soft cross-fade. Into a screen: a short glide with a fade. Nothing bouncy.
            enterTransition = { com.nizam.app.ui.design.NavMotion.enter(isTab(initialState.destination.route) && isTab(targetState.destination.route)) },
            exitTransition = { com.nizam.app.ui.design.NavMotion.exit(isTab(initialState.destination.route) && isTab(targetState.destination.route)) },
            popEnterTransition = { com.nizam.app.ui.design.NavMotion.popEnter() },
            popExitTransition = { com.nizam.app.ui.design.NavMotion.popExit() },
            navController = navController,
            startDestination = startRoute ?: if (startOnCurator) Routes.ASSISTANT else Routes.TODAY,
            modifier = Modifier.padding(innerPadding).padding(start = if (wide && currentRoute != Routes.PLAYER && currentRoute != Routes.IPTV_PLAYER && currentRoute != Routes.BEDSIDE && currentRoute != Routes.AMBIENT) 80.dp else 0.dp)
        ) {
            composable(Routes.TODAY) {
                com.nizam.app.ui.screens.spaces.SpacesHome(container) { navController.navigate(it) }
            }
            composable(Routes.MODULES) {
                com.nizam.app.ui.screens.hubs.LifeScreen(container) { navController.navigate(it) }
            }
            composable(Routes.FIREWALL) { com.nizam.app.feature.brain2.FirewallScreen() }
            composable(Routes.SKILLS) { com.nizam.app.feature.agent.SkillsProScreen(container) }
            composable(Routes.KNOWSME) { com.nizam.app.feature.brain2.PersonalModelScreen() }
            composable(Routes.RESEARCH) { com.nizam.app.feature.brain2.ResearchScreen(container) }
            composable(Routes.FACTCHECK) { com.nizam.app.feature.brain2.FactCheckScreen(container) }
            composable(Routes.CALC) { com.nizam.app.feature.brain2.CalcScreen(container) }
            composable("nx/{id}") { e -> com.nizam.app.feature.nx.NxScreen(e.arguments?.getString("id").orEmpty(), container) { navController.navigate(it) } }
            composable(Routes.WHEREIS) { com.nizam.app.feature.brain2.PlacesScreen() }
            composable(Routes.MEMCARDS) { com.nizam.app.feature.brain2.CardsScreen(container) }
            composable(Routes.FINTWIN) { com.nizam.app.feature.brain2.TwinScreen(container) }
            composable(Routes.UNIINBOX) { com.nizam.app.feature.brain2.InboxScreen(container) }
            composable(Routes.RADAR) { com.nizam.app.feature.brain2.RadarScreen(container) { navController.navigate(it) } }
            composable(Routes.PETCHAT) { com.nizam.app.feature.companion.PetChatScreen(container) }
            composable(Routes.AMBIENT) { com.nizam.app.ui.design.AmbientScreen(null) { navController.popBackStack() } }
            composable(Routes.APPEARANCE) { com.nizam.app.ui.screens.settings.AppearanceScreen { navController.navigate(it) } }
            composable(Routes.CATCHUP) { com.nizam.app.feature.next.CatchUpScreen(container) { navController.navigate(it) } }
            composable(Routes.RAMBLER) { com.nizam.app.feature.next.RamblerScreen(container) }
            composable(Routes.PRIVACY_ALERTS) { com.nizam.app.feature.next.PrivacyAlertsScreen() }
            composable(Routes.LOADSHEDDING) { com.nizam.app.feature.next.LoadSheddingScreen() }
            composable(Routes.SCREENSHOTS) { com.nizam.app.feature.next.ScreenshotsScreen() }
            composable(Routes.COMMITTEES) { com.nizam.app.feature.next.CommitteesScreen() }
            composable(Routes.DEBTS) { com.nizam.app.feature.next.DebtsScreen() }
            composable(Routes.SUBSCRIPTIONS) { com.nizam.app.feature.next.SubscriptionsScreen(container) }
            composable(Routes.PRICEWATCH) { com.nizam.app.feature.next.PriceWatchScreen() }
            composable(Routes.ACTIVITY) { com.nizam.app.feature.next.ActivityScreen() }
            composable(Routes.CAPTURE) { com.nizam.app.feature.next.CaptureScreen(container) }
            composable(Routes.NEGOTIATOR) { com.nizam.app.feature.next.NegotiatorScreen(container) }
            composable(Routes.PASSWORDS) { com.nizam.app.feature.next.PasswordsScreen() }
            composable(Routes.FAMILYBOARD) { com.nizam.app.feature.next.FamilyBoardScreen() }
            composable(Routes.ATLASCIRCLE) { com.nizam.app.feature.next.AtlasCircleScreen() }
            composable(Routes.MEDICINES) { com.nizam.app.feature.next.MedicinesScreen() }
            composable(Routes.EMERGENCY) { com.nizam.app.feature.next.EmergencyScreen { navController.navigate(it) } }
            composable(Routes.DOCEXPIRY) { com.nizam.app.feature.next.DocExpiryScreen() }
            composable(Routes.SPLITS) { com.nizam.app.feature.next.SplitsScreen() }
            composable(Routes.RATES) { com.nizam.app.feature.next.RatesScreen() }
            composable(Routes.ISLAMICCAL) { com.nizam.app.feature.next.IslamicCalendarScreen() }
            composable(Routes.DELIVERABLES) { com.nizam.app.feature.studio.DeliverablesScreen(container) }
            composable(Routes.ISLAMLIB) { com.nizam.app.feature.deen.library.LibraryScreen(container, 5) }
            composable(Routes.DAILYPATH) { com.nizam.app.feature.deen.library.LibraryScreen(container, 0) }
            composable(Routes.MASJIDS) { com.nizam.app.feature.next.MasjidFinderScreen(container) }
            composable(Routes.WEATHER) { com.nizam.app.feature.next.WeatherScreen(container) }
            composable(Routes.QRSTUDIO) { com.nizam.app.feature.next.QrStudioScreen { navController.navigate(it) } }
            composable(Routes.VEHICLE) { com.nizam.app.feature.next.VehicleScreen(container) }
            composable(Routes.PETS) { com.nizam.app.feature.next.PetsScreen(container) }
            composable(Routes.INVENTORY) { com.nizam.app.feature.next.InventoryScreen() }
            composable(Routes.TRAVELPLAN) { com.nizam.app.feature.next.TravelPlannerScreen(container) }
            composable(Routes.DUAS) { com.nizam.app.feature.next.DuasScreen() }
            composable(Routes.HABITS) { com.nizam.app.feature.next.HabitsScreen() }
            composable(Routes.TIMER) { com.nizam.app.feature.next.TimerScreen() }
            composable(Routes.MAGICCAPTURE) { com.nizam.app.feature.next.MagicCaptureScreen() }
            composable(Routes.BILLSCAN) { com.nizam.app.feature.next.BillScanScreen(container) }
            composable(Routes.FAMILYBRIEF) { com.nizam.app.feature.next.FamilyBriefScreen(container) }
            composable(Routes.FREETIMES) { com.nizam.app.feature.next.FreeTimesScreen(container) }
            composable(Routes.AUTOFILL) { com.nizam.app.feature.autofill.AutofillProfileScreen() }
            composable(Routes.MYWIDGET) { com.nizam.app.feature.widget.WidgetSetupScreen() }
            composable(Routes.SAFETY) { com.nizam.app.feature.next.SafetyScreen { navController.navigate(it) } }
            composable(Routes.MODULES_CLASSIC) {
                ModulesScreen(container = container, onOpen = { navController.navigate(it) })
            }
            composable(Routes.HUB) { e ->
                com.nizam.app.ui.screens.hubs.HubPage(e.arguments?.getString("name").orEmpty(), container) { navController.navigate(it) }
            }
            composable(Routes.ASK) { com.nizam.app.feature.ask.AskScreen(container) { navController.navigate(it) } }
            composable(Routes.SEARCH) { SearchScreen(container) { navController.navigate(it) } }
            composable(Routes.SETTINGS) { SettingsScreen(container) { navController.navigate(it) } }
            composable(Routes.TASKS) { TasksScreen(container) }
            composable(Routes.PRAYER) { PrayerScreen(container) }
            composable(Routes.FINANCE) { FinanceScreen(container) }
            composable(Routes.DIET) { DietScreen(container) }
            composable(Routes.GYM) { GymScreen(container) }
            composable(Routes.NOTES) { NotesScreen(container) }
            composable(Routes.LIBRARY) { LibraryScreen(container) }
            composable(Routes.DIARY) { DiaryScreen(container) }
            composable(Routes.QURAN) { QuranScreen(container) }
            composable(Routes.HADITH) { HadithScreen(container) }
            composable(Routes.LEARNING) { LearningScreen(container) }
            composable(Routes.SURVIVAL) { SurvivalScreen(container) }
            composable(Routes.QUOTES) { QuotesScreen(container) }
            composable(Routes.COURSES) { CoursesScreen(container) }
            composable(Routes.ASSISTANT) { CuratorScreen(container) }
            composable(Routes.BROWSER) { BrowserScreen(container) }
            composable(Routes.PHONE) { PhoneControlScreen(container) }
            composable(Routes.COUNCIL) { CouncilScreen(container) }
            composable(Routes.PROFILE) { ProfileScreen(container) }
            composable(Routes.TOWN) { TownScreen(container) }
            composable(Routes.UNDERSTAND) { UnderstandScreen(container) }
            composable(Routes.BRAIN) { BrainScreen(container) }
            composable(Routes.DAY) { DayScreen(container) }
            composable(Routes.SHIELD) { ShieldScreen() }
            composable(Routes.PLAN) { PlannerScreen(container) }
            composable(Routes.LANGUAGE) { LanguageScreen(container) }
            composable(Routes.SCAN) { ScanScreen(container) }
            composable(Routes.GROWTH) { GrowthScreen(container) }
            composable(Routes.VOICE) { VoiceHubScreen(container) { navController.navigate(it) } }
            composable(Routes.DRIVE) { DrivingScreen(container) }
            composable(Routes.VOICE_JOURNAL) { VoiceJournalScreen(container) }
            composable(Routes.REHEARSE) { RehearsalScreen(container) }
            composable(Routes.ATTENTION) { AttentionScreen(container) }
            composable(Routes.CINEMA) {
                MediaCenterScreen(
                    onMeta = { type, id -> CinemaNav.metaType = type; CinemaNav.metaId = id; navController.navigate(Routes.META) },
                    onPlay = { navController.navigate(Routes.PLAYER) },
                    onTmdbFilm = { CinemaNav.filmId = it; navController.navigate(Routes.FILM) }
                )
            }
            composable(Routes.META) { MetaScreen(onPlay = { navController.navigate(Routes.PLAYER) }) }
            composable(Routes.FILM) {
                FilmScreen(
                    onFilm = { CinemaNav.filmId = it; navController.navigate(Routes.FILM) },
                    onPlay = { navController.navigate(Routes.PLAYER) }
                )
            }
            composable(Routes.PLAYER) { PlayerScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.MIND) { MindScreen(container) }
            composable(Routes.MEMORY) { MemoryScreen(container) }
            composable(Routes.DAILY) { DailyScreen(container) }
            composable(Routes.TOOLS) { PlatformScreen(container) }
            composable(Routes.PAUSE) { PauseSettingsScreen() }
            composable(Routes.CALM) { com.nizam.app.feature.wellbeing.MeditateScreen() }
            composable(Routes.FUTURE) { FutureYouScreen(container) }
            composable(Routes.REFRAME) { ReframeScreen(container) }
            composable(Routes.REPS) { RepCounterScreen(container) }
            composable(Routes.PLATE) { SnapPlateScreen(container) }
            composable(Routes.COACH) { VoiceCoachScreen(container) }
            composable(Routes.FLIP) { FlipFocusScreen() }
            composable(Routes.BEDSIDE) { BedsideScreen(container) { navController.popBackStack() } }
            composable(Routes.WAKE) { SmartWakeScreen() }
            composable(Routes.COMMIT) { CommitScreen(container) }
            composable(Routes.MEETING) { MeetingScreen(container) }
            composable(Routes.AUTOMONEY) { AutoMoneyScreen(container) }
            composable(Routes.SCHEDULES) { SchedulesScreen() }
            composable(Routes.HQ) { com.nizam.app.feature.hq.HqScreen(container) { navController.navigate(it) } }
            composable(Routes.LAB) { LabScreen(container) }
            composable(Routes.MIRROR) { MirrorScreen(container) }
            composable(Routes.PRESENCE) { PresenceScreen(container) }
            composable(Routes.STUDIO) { StudioScreen(container) }
            composable(Routes.RULES) { RulesScreen(container) }
            composable(Routes.WORKFORCE) { WorkforceScreen(container) }
            composable(Routes.JARVIS) { com.nizam.app.feature.jarvis.JarvisScreen(container) }
            composable(Routes.BARAKAH) { com.nizam.app.feature.jarvis.BarakahScreen(container) }
            composable(Routes.LIFESIM) { com.nizam.app.feature.jarvis.LifeSimScreen(container) }
            composable(Routes.COURT) { com.nizam.app.feature.jarvis.DecisionCourtScreen(container) }
            composable(Routes.CALLCOACH) { com.nizam.app.feature.jarvis.CallCoachScreen(container) }
            composable(Routes.RECEIPTS) { com.nizam.app.feature.jarvis.ReceiptsScreen(container) }
            composable(Routes.DEEN) { com.nizam.app.feature.deen.DeenScreen(container) }
            composable(Routes.MODELS) { com.nizam.app.ui.screens.settings.ModelsScreen() }
            composable(Routes.MAPS) { com.nizam.app.feature.maps.MapsScreen() }
            composable(Routes.SECURITY) { com.nizam.app.feature.security.SecurityScreen() }
            composable(Routes.COMPANION) { com.nizam.app.feature.companion.CompanionSettings() }
            composable(Routes.KEYS) { com.nizam.app.ui.screens.settings.KeysScreen(container) }
            composable(Routes.APPSTORE) { com.nizam.app.feature.store.AppStoreScreen() }
            composable(Routes.MUSIC) { com.nizam.app.feature.music.MusicScreen() }
            composable("hv") { com.nizam.app.feature.hidden.HvRoot { navController.popBackStack() } }
            composable(Routes.POWERS) { com.nizam.app.feature.future.PowersScreen { navController.navigate(it) } }
            com.nizam.app.feature.future.Powers.ALL.forEach { p ->
                composable("power/${p.id}") { com.nizam.app.feature.future.PowerScreen(p.id, container) { navController.navigate(it) } }
            }
            composable(Routes.PLAYGROUND) { PlaygroundScreen(container) }
            composable(Routes.REFLECT) { ReflectScreen(container) }
            composable(Routes.PEOPLE) { PeopleScreen(container) { navController.navigate(it) } }
            composable(Routes.NIGHT) { NightCompanionScreen(container) }
            composable(Routes.APPS) { AppsScreen { navController.navigate(it) } }
            composable(Routes.ATLAS_HUB) { AtlasHub { navController.navigate(it) } }
            composable(Routes.YOU) { FinaleScreen(container) }
            composable(Routes.BOTS) { BotsScreen { id -> BotNav.openId = id; navController.navigate(Routes.BOT_THREAD) } }
            composable(Routes.BOT_THREAD) { BotThreadScreen(container) { navController.popBackStack() } }
            composable(Routes.UNDERSTAND_HUB) { UnderstandHub { navController.navigate(it) } }
            composable(Routes.STORY_HUB) { StoryHub { navController.navigate(it) } }
            composable(Routes.GROW_HUB) { GrowHub { navController.navigate(it) } }
            composable(Routes.WELLBEING_HUB) { WellbeingHub { navController.navigate(it) } }
            composable(Routes.PODCASTS) {
                PodcastsScreen(
                    onShow = { feed, title -> PodcastNav.feed = feed; PodcastNav.title = title; navController.navigate(Routes.PODCAST_SHOW) },
                    onOpenPlayer = { navController.navigate(Routes.PODCAST_PLAYER) }
                )
            }
            composable(Routes.PODCAST_SHOW) { PodcastShowScreen { navController.navigate(Routes.PODCAST_PLAYER) } }
            composable(Routes.PODCAST_PLAYER) { PodcastPlayerScreen() }
            composable(Routes.IPTV) { IptvScreen { navController.navigate(Routes.IPTV_PLAYER) } }
            composable(Routes.IPTV_PLAYER) { IptvPlayerScreen() }
            composable(Routes.ME) { MeScreen(container) { navController.navigate(it) } }
            composable(Routes.NEWS) { NewsScreen(container) }
            composable(Routes.INSIGHTS) { InsightsScreen(container) }
            composable(Routes.MISSIONS) { MissionsScreen(container) }
            composable(Routes.SELF) { SelfScreen(container) }
            composable(Routes.AGENT) { CuratorScreen(container) }
            composable(Routes.VAULT) { VaultScreen(container) }
            composable(Routes.TOOLKIT) { ToolkitScreen(container) }
            composable(
                route = Routes.DYNAMIC,
                arguments = listOf(androidx.navigation.navArgument("key") {
                    type = androidx.navigation.NavType.StringType
                })
            ) { entry ->
                DynamicModuleScreen(
                    container = container,
                    moduleKey = entry.arguments?.getString("key").orEmpty(),
                    onSettings = { navController.navigate(Routes.moduleSettings(it)) }
                )
            }
            composable(
                route = Routes.MODULE_SETTINGS,
                arguments = listOf(androidx.navigation.navArgument("key") {
                    type = androidx.navigation.NavType.StringType
                })
            ) { entry ->
                ModuleSettingsScreen(
                    container = container,
                    moduleKey = entry.arguments?.getString("key").orEmpty(),
                    onDeleted = {
                        navController.navigate(Routes.MODULES) {
                            popUpTo(Routes.MODULES) { inclusive = true }
                        }
                    }
                )
            }
        }
        // app-wide moments drawn over everything: milestone confetti and the "done" edge glow
        com.nizam.app.ui.design.CelebrationOverlay()
        com.nizam.app.ui.design.EdgeGlowOverlay()
        com.nizam.app.ui.design.PeekOverlay()
        com.nizam.app.feature.wild.FocusLensOverlay()
        // tips sit just above the tab bar, never over a screen's title
        if (!com.nizam.app.feature.cinema.Pip.active) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(bottom = 104.dp), contentAlignment = Alignment.BottomCenter) {
            com.nizam.app.ui.design.TipCard(currentRoute)
        }
        if (!com.nizam.app.feature.cinema.Pip.active) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
            com.nizam.app.ui.design.IslandPill { r -> runCatching { navController.navigate(when (r) { "prayer" -> Routes.PRAYER; "atlas" -> Routes.ATLAS_HUB; else -> r }) { launchSingleTop = true } } }
        }
        if (com.nizam.app.ui.nav.ReAuth.guarded(navCtx, backStackEntry?.let { e -> e.destination.route?.let { r -> if (r.contains("{id}")) r.replace("{id}", e.arguments?.getString("id").orEmpty()) else r } }))
            androidx.compose.foundation.layout.Box(Modifier.padding(innerPadding)) { ReAuthCover { navController.popBackStack() } }
        com.nizam.app.feature.platform2.Profiles.blockedReason(navCtx, backStackEntry?.destination?.route?.let { r -> if (r.contains("{name}")) "hub/" + (backStackEntry?.arguments?.getString("name").orEmpty()) else if (r.contains("{id}")) r.replace("{id}", backStackEntry?.arguments?.getString("id").orEmpty()) else r })?.let { why ->
            androidx.compose.foundation.layout.Box(Modifier.padding(innerPadding).background(MaterialTheme.colorScheme.background)) { com.nizam.app.feature.platform2.BlockedCover(why) { navController.popBackStack() } }
        }
        if (wide && currentRoute != Routes.PLAYER && currentRoute != Routes.IPTV_PLAYER && currentRoute != Routes.BEDSIDE && currentRoute != Routes.AMBIENT)
            androidx.compose.material3.NavigationRail(
                Modifier.padding(top = innerPadding.calculateTopPadding()).fillMaxHeight().width(80.dp),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
            ) {
                Spacer(Modifier.height(8.dp))
                bottomItems.forEach { item ->
                    val sel = currentRoute == item.route
                    androidx.compose.material3.NavigationRailItem(
                        selected = sel,
                        onClick = { if (!sel) navController.navigate(item.route) { popUpTo(Routes.TODAY) { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(if (sel) item.icon else item.idle, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        if (guidedVisible) com.nizam.app.feature.nx.GuidedWalkthroughOverlay(
            tabs = guidedTabs,
            step = guidedStep,
            paused = guidedPaused,
            onStep = { guidedStep = it },
            onPaused = { guidedPaused = it },
            onExit = { guidedVisible = false; guidedPaused = false }
        )
    }
    }
}
