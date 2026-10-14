package com.nizam.app.ui.screens.today

import androidx.compose.ui.graphics.asImageBitmap

import androidx.compose.material.icons.outlined.NewReleases

import androidx.compose.material.icons.outlined.EventRepeat

import androidx.compose.material.icons.outlined.AutoFixHigh

import androidx.compose.material.icons.outlined.Notifications

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nizam.app.AppContainer
import com.nizam.app.core.Tones
import com.nizam.app.core.fmtAmount
import com.nizam.app.core.hourToClock
import com.nizam.app.core.today
import com.nizam.app.core.vmFactory
import com.nizam.app.feature.diary.DiaryViewModel
import com.nizam.app.feature.diet.DietViewModel
import com.nizam.app.feature.finance.FinanceViewModel
import com.nizam.app.feature.gym.GymViewModel
import com.nizam.app.feature.learning.CourseViewModel
import com.nizam.app.feature.learning.LearningViewModel
import com.nizam.app.feature.missions.MissionsViewModel
import com.nizam.app.feature.prayer.PrayerViewModel
import com.nizam.app.feature.progress.Insight
import com.nizam.app.feature.progress.Insights
import com.nizam.app.feature.progress.ProgressSummary
import com.nizam.app.feature.progress.Xp
import com.nizam.app.feature.quotes.QuotesViewModel
import com.nizam.app.feature.quotes.quoteOfTheDay
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.screens.tasks.TasksViewModel
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoNumber
import com.nizam.app.ui.theme.Spine
import java.time.LocalDate
import java.time.LocalTime
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val modeContext = androidx.compose.ui.platform.LocalContext.current
    val modeStore = remember(modeContext) { com.nizam.app.feature.nx.ModeNavigationStore.get(modeContext) }
    val modeState by modeStore.state.collectAsState()
    fun showTodayCard(id: String): Boolean =
        id in modeState.visibleTodayCards &&
            id in modeState.activeMode.visibleCards &&
            (!modeState.lowCognitiveLoad || id in setOf("day-rings", "next-prayer"))
    fun localizeNumerals(value: String): String {
        if (!modeState.urduNumerals) return value
        val western = "0123456789"
        val urdu = "۰۱۲۳۴۵۶۷۸۹"
        return buildString(value.length) {
            value.forEach { char ->
                val index = western.indexOf(char)
                append(if (index >= 0) urdu[index] else char)
            }
        }
    }
    val dateLocale = when (modeState.language) {
        "Urdu" -> java.util.Locale.forLanguageTag("ur-PK")
        "Roman Urdu" -> java.util.Locale.ENGLISH
        else -> java.util.Locale.getDefault()
    }
    val tasksVm: TasksViewModel = viewModel(
        factory = TasksViewModel.factory(container.taskRepository, container.habitRepository)
    )
    val prayerVm: PrayerViewModel = viewModel(
        key = "prayer",
        factory = vmFactory { PrayerViewModel(container.prayerRepository, container.settingsRepository) }
    )
    val financeVm: FinanceViewModel = viewModel(
        key = "finance", factory = vmFactory { FinanceViewModel(container.financeRepository) }
    )
    val dietVm: DietViewModel = viewModel(
        key = "diet", factory = vmFactory { DietViewModel(container.dietRepository) }
    )
    val gymVm: GymViewModel = viewModel(
        key = "gym", factory = vmFactory { GymViewModel(container.gymRepository) }
    )
    val diaryVm: DiaryViewModel = viewModel(
        key = "diary", factory = vmFactory { DiaryViewModel(container.diaryRepository) }
    )
    val learningVm: LearningViewModel = viewModel(
        key = "learning", factory = vmFactory { LearningViewModel(container.learningRepository) }
    )
    val quotesVm: QuotesViewModel = viewModel(
        key = "quotes", factory = vmFactory { QuotesViewModel(container.quoteRepository) }
    )
    val missionsVm: MissionsViewModel = viewModel(
        key = "missions", factory = vmFactory { MissionsViewModel(container.missionRepository) }
    )
    val courseVm: CourseViewModel = viewModel(
        key = "courses",
        factory = vmFactory { CourseViewModel(container.courseRepository, container.learningRepository) }
    )

    val tasks by tasksVm.tasks.collectAsState()
    val habits by tasksVm.habits.collectAsState()
    val habitEntries by tasksVm.todayEntries.collectAsState()
    val prayerDay by prayerVm.day.collectAsState()
    val lat by prayerVm.latitude.collectAsState()
    val lng by prayerVm.longitude.collectAsState()
    val method by prayerVm.methodName.collectAsState()
    val hanafi by prayerVm.hanafi.collectAsState()
    val month by financeVm.thisMonth.collectAsState()
    val meals by dietVm.items.collectAsState()
    val water by dietVm.water.collectAsState()
    val sets by gymVm.sets.collectAsState()
    val entries by diaryVm.entries.collectAsState()
    val due by learningVm.due.collectAsState()
    val quotes by quotesVm.quotes.collectAsState()
    val todaysLessons by courseVm.todaysLessons.collectAsState()
    val missions by missionsVm.todayMissions.collectAsState()
    val name by container.settingsRepository.displayName.collectAsState(initial = "")
    val kcalTarget by container.settingsRepository.kcalTarget.collectAsState(initial = 2200)
    val waterTarget by container.settingsRepository.waterTarget.collectAsState(initial = 3000)

    var summary by remember { mutableStateOf<ProgressSummary?>(null) }
    var insights by remember { mutableStateOf<List<Insight>>(emptyList()) }

    LaunchedEffect(tasks.size, sets.size, entries.size, todaysLessons.size, prayerDay, habitEntries.size) {
        val computed = Xp.summarise(container)
        summary = computed
        insights = Insights.compute(computed)
    }

    val date = LocalDate.now()
    val hijri = if (modeState.hijriDates) runCatching {
        HijrahDate.from(date).format(DateTimeFormatter.ofPattern("d MMMM y", dateLocale))
    }.getOrElse { "" } else ""
    val hour = LocalTime.now().hour
    val greeting = com.nizam.app.ui.design.Greeting.of(name)

    // keyed on the date too, so an app left open past midnight doesn't keep yesterday's times
    val times = remember(lat, lng, method, hanafi, LocalDate.now()) { prayerVm.times() }
    val nowHours = hour + LocalTime.now().minute / 60.0
    val upcoming = listOf(
        "Fajr" to times.fajr, "Dhuhr" to times.dhuhr, "Asr" to times.asr,
        "Maghrib" to times.maghrib, "Isha" to times.isha
    ).firstOrNull { !it.second.isNaN() && it.second > nowHours }

    val openTasks = tasks.filter { it.completedAt == null }
    val prayerStatuses = prayerDay?.let {
        listOf(it.fajr, it.dhuhr, it.asr, it.maghrib, it.isha)
    } ?: List(5) { "NOT_PRAYED" }
    val spentToday = month.filter { it.kind == "EXPENSE" && it.localDate == today() }.sumOf { it.amount }
    val kcal = meals.sumOf { it.kcal }.toInt()
    val ml = water?.ml ?: 0
    val todaySets = sets.filter { it.localDate == today() }
    val quote = quoteOfTheDay(quotes)
    var showChart by remember { mutableStateOf(false) }

    val comfort = com.nizam.app.ui.design.Comfort.state(androidx.compose.ui.platform.LocalContext.current)
    if (comfort.elder) { com.nizam.app.ui.design.ElderHome(greeting, onOpen); return }
    val zen = comfort.zen
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(18.dp))

        // optional photo behind the header (chosen in Today header photo)
        com.nizam.app.feature.platform2.TodayPhoto.uri(modeContext)?.let { u ->
            val bmp = remember(u) { runCatching { modeContext.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) } }.getOrNull() }
            if (bmp != null) Box(Modifier.fillMaxWidth().height(110.dp).clip(com.nizam.app.ui.design.Radius.card)) {
                androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color(0xAA000000)))))
            }
        }
        // ── Header: who, when, and two quiet doors (past days, settings) ──
        val faceCode by container.settingsRepository.face.collectAsState(initial = "2-1-0-2-0-0-0")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    localizeNumerals(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", dateLocale))) +
                        if (hijri.isBlank()) "" else "  ·  ${localizeNumerals(hijri)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(greeting, style = MaterialTheme.typography.displaySmall)
            }
            val unreadCtx = androidx.compose.ui.platform.LocalContext.current
            val unread = remember { com.nizam.app.feature.notify.Inbox.unread(unreadCtx) }
            androidx.compose.material3.IconButton(onClick = { onOpen("nx/inbox") }) {
                androidx.compose.material3.BadgedBox(badge = { if (unread > 0) androidx.compose.material3.Badge { Text(if (unread > 9) "9+" else "$unread") } }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Notifications, contentDescription = "Inbox", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            androidx.compose.material3.IconButton(onClick = { onOpen(Routes.DAY) }) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.CalendarMonth,
                    contentDescription = "Past days", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Settings lives under You (and via Profile) — kept off the home header to keep it calm.
            Box(Modifier.clickable(role = Role.Button) { onOpen(Routes.PROFILE) }) {
                com.nizam.app.feature.profile.FaceAvatar(com.nizam.app.feature.profile.Face.decode(faceCode), 40.dp)
            }
        }

        com.nizam.app.ui.design.StatusStrip(com.nizam.app.ui.design.CurrentAmbience.value,
            upcoming?.let { it.first to ((it.second - nowHours) * 60).toInt() }, Modifier.padding(top = 10.dp))

        // ── say or type a command; Atlas does it ──
        com.nizam.app.feature.agent.VoiceCommandBar(container, onOpen, Modifier.padding(top = 12.dp))

        if (!com.nizam.app.feature.modes.Wizard.done(modeContext) && !zen) com.nizam.app.ui.design.ListRow("Set up Atlas in a minute", Modifier.padding(top = 8.dp),
            supporting = "Pick who it's for, language, modes and backup", icon = androidx.compose.material.icons.Icons.Outlined.AutoFixHigh) { onOpen("nx/setupwizard") }
        if (com.nizam.app.feature.platform2.whatsNewUnseen(modeContext) && !zen) com.nizam.app.ui.design.ListRow("What's new in Atlas", Modifier.padding(top = 8.dp), supporting = "A short tour of this version",
            icon = androidx.compose.material.icons.Icons.Outlined.NewReleases) { onOpen("nx/whatsnew") }
        // ── which mode you're in (switches by itself from prayer times, calendar and charger) and one-tap actions for it ──
        com.nizam.app.feature.modes.ModeBar(container, onOpen, Modifier.padding(top = 8.dp))
        com.nizam.app.feature.modes.QuickActionsBar(onOpen, Modifier.padding(top = 6.dp))
        if (!zen) com.nizam.app.feature.platform2.SuggestionsCard(container, onOpen, Modifier.padding(top = 4.dp))
        if (!zen) com.nizam.app.feature.nx.AtlasPicks(container, onOpen, Modifier.padding(top = 8.dp))

        // ── the day at a glance: rings that fill as the day goes well ──
        if (showTodayCard("day-rings")) run {
            val c = MaterialTheme.colorScheme
            val prayed = prayerStatuses.count { it != "NOT_PRAYED" }
            val doneTasks = tasks.count { it.completedAt != null && it.completedAt >= java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
            val taskTotal = doneTasks + openTasks.size
            com.nizam.app.ui.design.DayRings(listOf(
                com.nizam.app.ui.design.DayRing("Prayers", prayed / 5f, com.nizam.app.ui.design.Hub.Deen.tint(), "$prayed/5") { onOpen(Routes.PRAYER) },
                com.nizam.app.ui.design.DayRing("Tasks", if (taskTotal == 0) 0f else doneTasks.toFloat() / taskTotal, com.nizam.app.ui.design.Hub.Work.tint(),
                    "$doneTasks/$taskTotal") { onOpen(Routes.TASKS) },
                com.nizam.app.ui.design.DayRing("Water", ml.toFloat() / waterTarget.coerceAtLeast(1), com.nizam.app.ui.design.Hub.Travel.tint(),
                    "${ml / 100 / 10.0}L") { onOpen(Routes.DIET) },
                com.nizam.app.ui.design.DayRing("Move", (todaySets.size / 12f), com.nizam.app.ui.design.Hub.Health.tint(),
                    "${todaySets.size} sets") { onOpen(Routes.GYM) }
            ), Modifier.padding(top = 12.dp))
            if (prayed == 0 && doneTasks == 0 && ml == 0 && todaySets.isEmpty())
                Text("A fresh day. Tap a ring to log your first prayer, glass of water or task.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }

        // ── instruments: live figures, trend and your day on one strip ──
        if (!zen && !modeState.lowCognitiveLoad && com.nizam.app.feature.modes.AutoModes.current(modeContext) !in setOf("worship", "driving", "guest")) {
            var dash by remember { mutableStateOf<com.nizam.app.feature.dash.DashData?>(null) }
            androidx.compose.runtime.LaunchedEffect(Unit) { dash = com.nizam.app.feature.dash.Dash.load(container) }
            dash?.let { d -> Box(Modifier.padding(top = 12.dp)) { com.nizam.app.feature.dash.Dash.Today(d, onOpen) } }
        }
        // ── after 8pm: what's left from today ──
        if (hour >= 20 && !zen) com.nizam.app.ui.design.ListRow("Carry over", Modifier.padding(top = 8.dp), supporting = "Move, reschedule or drop what's unfinished",
            icon = androidx.compose.material.icons.Icons.Outlined.EventRepeat) { onOpen("nx/carryover") }

        // ── what you usually open around now, one tap away ──
        val likelyCtx = androidx.compose.ui.platform.LocalContext.current
        val likely = remember { com.nizam.app.feature.brain2.Habits2.likelyNow(likelyCtx) }
            .mapNotNull { com.nizam.app.ui.screens.hubs.HubRegistry.byRoute(it) }
        if (likely.isNotEmpty() && !zen && showTodayCard("usually-now")) androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Usually now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)) }
            items(likely.size) { i -> com.nizam.app.ui.design.Capsule(likely[i].title, false) { onOpen(likely[i].route) } }
        }

        // ── after Isha the day softens: one card to set it down ──
        if ((hour >= 21 || hour < 3) && showTodayCard("wind-down")) {
            Spacer(Modifier.height(10.dp))
            com.nizam.app.ui.design.ListRow("Wind down", Modifier.clip(com.nizam.app.ui.design.Radius.card)
                .background(com.nizam.app.ui.design.wash(com.nizam.app.ui.design.Hub.Memory.tint(), 0.14f)),
                supporting = "Close the day, tomorrow's plan, the bedside clock",
                icon = androidx.compose.material.icons.Icons.Outlined.Bedtime, tint = com.nizam.app.ui.design.Hub.Memory.tint()) { onOpen(Routes.NIGHT) }
        }

        // ── the bento: live tiles you can arrange ──
        androidx.compose.runtime.SideEffect { com.nizam.app.ui.design.Island.nextPrayer = upcoming?.let { it.first to ((it.second - nowHours) * 60).toInt() } }
        if (!zen && showTodayCard("bento")) com.nizam.app.ui.design.TodayBento(com.nizam.app.ui.design.BentoData(
            upcoming?.let { it.first to ((it.second - nowHours) * 60).toInt() }, spentToday, ml, waterTarget, openTasks.size,
            openTasks.take(3).map { it.title }, quotes.getOrNull((java.time.LocalDate.now().dayOfYear) % quotes.size.coerceAtLeast(1))?.text), onOpen)

        // ── your companion, and the sky it's reacting to ──
        if (!zen && showTodayCard("companion")) com.nizam.app.feature.companion.CompanionCard(summary, com.nizam.app.ui.design.CurrentAmbience.value,
            upcoming?.let { it.first to ((it.second - nowHours) * 60).toInt() }, onChat = { onOpen(Routes.PETCHAT) }) { onOpen(Routes.COMPANION) }

        Spacer(Modifier.height(10.dp))

        // ── The one thing that anchors the day: the next prayer ──
        if (showTodayCard("next-prayer") && (zen || !showTodayCard("bento"))) com.nizam.app.ui.design.DepthCard(spine = com.nizam.app.ui.design.Hub.Deen.tint(), modifier = Modifier.clickable(role = Role.Button) { onOpen(Routes.PRAYER) }, gauge = prayerStatuses.count { it != "NOT_PRAYED" } / 5f) {
            val nextName = upcoming?.first ?: "Fajr"
            val nextAt = upcoming?.second ?: times.fajr
            val minsLeft = if (upcoming != null) ((upcoming.second - nowHours) * 60).toInt()
                else (((24 - nowHours) + times.fajr) * 60).toInt()
            Text(if (upcoming == null) "Next prayer · tomorrow" else "Next prayer",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(nextName, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Text(if (nextAt.isNaN()) "—" else hourToClock(nextAt), style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(if (minsLeft >= 60) "in ${minsLeft / 60} h ${minsLeft % 60} min" else "in $minsLeft min",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("F", "D", "A", "M", "I").forEachIndexed { i, l ->
                    val done = prayerStatuses[i] != "NOT_PRAYED"
                    Box(Modifier.width(28.dp).height(28.dp).background(
                        if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                        Text(l, style = MaterialTheme.typography.labelSmall,
                            color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text("${prayerStatuses.count { it != "NOT_PRAYED" }} of 5 today", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (!zen && showTodayCard("briefing")) {
        // ── This morning's briefing, written overnight ──
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val brief = remember { com.nizam.app.feature.jarvis.Briefing.today(ctx) }
        var briefOpen by remember { mutableStateOf(false) }
        if (brief != null && hour < 14) {
            Spacer(Modifier.height(12.dp))
            com.nizam.app.ui.design.Group(Modifier.clickable(role = Role.Button) { briefOpen = !briefOpen }) {
                Text("This morning", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (briefOpen) brief else brief.lines().firstOrNull { it.isNotBlank() }.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge, maxLines = if (briefOpen) Int.MAX_VALUE else 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        if (java.time.LocalTime.now().hour in 4..13) com.nizam.app.feature.body.EnergyCheck()

        // ── Today, as one calm list ──
        com.nizam.app.ui.design.Label("Today")
        val order by container.settingsRepository.homeSectionOrder.collectAsState(
            initial = com.nizam.app.data.prefs.SettingsRepository.DEFAULT_SECTIONS
        )
        val known = com.nizam.app.data.prefs.SettingsRepository.DEFAULT_SECTIONS
        // Honour the saved order; anything the saved list omits still appears at the end.
        val sections = order.filter { it in known } + known.filterNot { it in order }
        com.nizam.app.ui.design.Group(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            sections.forEachIndexed { i, section ->
                val last = i == sections.lastIndex
                when (section) {
                    "MISSIONS" -> Line(
                        spine = Spine.Survival,
                        label = missions.firstOrNull { it.completedAt == null }?.title
                            ?: if (missions.isEmpty()) "No missions set for today" else "All missions cleared",
                        value = if (missions.isEmpty()) "—" else "${missions.count { it.completedAt != null }}/${missions.size}",
                        last = last, onClick = { onOpen(Routes.MISSIONS) }
                    )
                    "PRAYER" -> Line(
                        spine = Spine.Prayer,
                        label = if (upcoming != null) "${upcoming.first} at ${hourToClock(upcoming.second)}" else "Fajr tomorrow",
                        value = "${prayerStatuses.count { it != "NOT_PRAYED" }}/5",
                        last = last, onClick = { onOpen(Routes.PRAYER) }
                    )
                    "COURSE" -> Line(
                        spine = Spine.Learning,
                        label = todaysLessons.firstOrNull { it.completedAt == null }?.title
                            ?: if (todaysLessons.isEmpty()) "No lesson scheduled" else "Today's lessons done",
                        value = if (todaysLessons.isEmpty()) "—"
                        else "${todaysLessons.count { it.completedAt != null }}/${todaysLessons.size}",
                        last = last, onClick = { onOpen(Routes.COURSES) }
                    )
                    "TASKS" -> Line(
                        spine = Spine.Tasks,
                        label = openTasks.firstOrNull()?.title ?: "Nothing open",
                        value = "${openTasks.size}",
                        last = last, onClick = { onOpen(Routes.TASKS) }
                    )
                    "HABITS" -> Line(
                        spine = Spine.Tasks, label = "Habits",
                        value = "${habitEntries.size}/${habits.size}",
                        last = last, onClick = { onOpen(Routes.TASKS) }
                    )
                    "DIET" -> Line(
                        spine = Spine.Diet, label = "Food and water",
                        value = "$kcal/$kcalTarget · $ml/$waterTarget",
                        last = last, onClick = { onOpen(Routes.DIET) }
                    )
                    "GYM" -> Line(
                        spine = Spine.Gym,
                        label = if (todaySets.isEmpty()) "No sets logged" else "Training logged",
                        value = "${todaySets.size}",
                        last = last, onClick = { onOpen(Routes.GYM) }
                    )
                    "FINANCE" -> Line(
                        spine = Spine.Finance, label = "Spent today",
                        value = fmtAmount(spentToday),
                        last = last, onClick = { onOpen(Routes.FINANCE) }
                    )
                    "LEARNING" -> Line(
                        spine = Spine.Learning, label = "Cards due",
                        value = "${due.size}",
                        last = last, onClick = { onOpen(Routes.LEARNING) }
                    )
                    "SELF" -> Line(
                        spine = Spine.Diary,
                        label = if (hour >= 20) "Close the day — evening review" else "Character and review",
                        value = "",
                        last = last, onClick = { onOpen(Routes.SELF) }
                    )
                    "NEWS" -> Line(
                        spine = Spine.Finance, label = "Headlines from Dawn",
                        value = "",
                        last = last, onClick = { onOpen(Routes.NEWS) }
                    )
                }
            }
        }

        // ── Progress, in one line; the 30-day chart opens on tap ──
        summary?.let { s ->
            com.nizam.app.ui.design.Label("Progress")
            com.nizam.app.ui.design.Group(Modifier.clickable(role = Role.Button) { showChart = !showChart }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Level ${s.level} · ${s.title}", style = MaterialTheme.typography.titleMedium)
                        Text("${s.streak}-day streak · +${s.today.xp} XP today", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${s.levelProgress}/${s.levelNeed}", style = MonoNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { s.levelProgress.toFloat() / s.levelNeed.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                androidx.compose.animation.AnimatedVisibility(showChart) {
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                        s.last30.reversed().forEach { day ->
                            Box(
                                Modifier.weight(1f)
                                    .height(if (day.active) (8 + (day.xp / 12).coerceAtMost(26)).dp else 4.dp)
                                    .background(
                                        if (day.active) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }

        // ── One insight, quietly ──
        insights.firstOrNull()?.let { insight ->
            Spacer(Modifier.height(12.dp))
            com.nizam.app.ui.design.Group(Modifier.clickable { onOpen(Routes.INSIGHTS) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.nizam.app.ui.design.PulseDot(com.nizam.app.ui.design.Hub.Memory.tint())
                    Text(insight.headline, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(4.dp))
                Text(insight.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        quote?.let {
            Spacer(Modifier.height(32.dp))
            Text(
                "“${it.text.trim('"', '“', '”')}”",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.clickable(role = Role.Button) { onOpen(Routes.QUOTES) }
            )
            if (it.source.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("— ${it.source}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        }
        // ── shortcuts you arranged in Modes & Navigation, kept below your day ──
        com.nizam.app.ui.design.Label("Shortcuts")
        val quickActions = listOf(
            Triple("day-rings", "Progress", Routes.INSIGHTS),
            Triple("usually-now", "Usually now", Routes.TASKS),
            Triple("wind-down", "Wind down", Routes.NIGHT),
            Triple("companion", "Companion", Routes.COMPANION),
            Triple("next-prayer", "Next prayer", Routes.PRAYER),
            Triple("briefing", "Briefing", Routes.ASSISTANT)
        )
        val orderedActions = modeState.todayCardOrder.distinct()
            .mapNotNull { id -> quickActions.firstOrNull { it.first == id } }
            .filter { showTodayCard(it.first) }
        if (orderedActions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                orderedActions.forEach { (id, label, route) ->
                    val compact = id in modeState.compactTodayCards
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (compact) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpen(route) }
                    ) {
                        Text(
                            label,
                            style = if (compact) MaterialTheme.typography.labelMedium
                                else MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(
                                horizontal = if (compact) 10.dp else 14.dp,
                                vertical = if (compact) 6.dp else 10.dp
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(56.dp))
    }
}

/** A row of the Today list: a small colour dot for the area, the label, and the number that matters. */
@Composable
private fun Line(spine: androidx.compose.ui.graphics.Color, label: String, value: String, last: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(6.dp).height(6.dp).background(spine.copy(alpha = 0.85f), androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (value.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(value, style = MonoNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (!last) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 36.dp),
        thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}
