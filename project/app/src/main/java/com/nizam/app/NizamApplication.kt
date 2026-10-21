package com.nizam.app

import android.app.Application
import kotlinx.coroutines.launch
import android.content.Context
import com.nizam.app.data.db.NizamDatabase
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.data.repo.HabitRepository
import com.nizam.app.data.repo.TaskRepository
import com.nizam.app.feature.diary.DiaryRepository
import com.nizam.app.feature.diet.DietRepository
import com.nizam.app.feature.finance.FinanceRepository
import com.nizam.app.feature.gym.GymRepository
import com.nizam.app.feature.hadith.HadithRepository
import com.nizam.app.feature.learning.LearningRepository
import com.nizam.app.feature.library.BookRepository
import com.nizam.app.feature.learning.CourseRepository
import com.nizam.app.feature.missions.MissionRepository
import com.nizam.app.feature.news.NewsRepository
import com.nizam.app.feature.agent.AgentLoop
import com.nizam.app.feature.agent.AgentMemory
import com.nizam.app.feature.agent.CuratorRepository
import com.nizam.app.feature.dynamic.DynamicRepository
import com.nizam.app.feature.dynamic.ModuleGenerator
import com.nizam.app.feature.self.SelfRepository
import com.nizam.app.feature.vault.VaultRepository
import com.nizam.app.feature.notes.NoteRepository
import com.nizam.app.feature.prayer.PrayerRepository
import com.nizam.app.feature.quotes.QuoteRepository
import com.nizam.app.feature.quran.QuranRepository
import com.nizam.app.feature.survival.SurvivalRepository
import com.nizam.app.feature.backup.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class AppContainer(
    context: Context,
    val settingsRepository: SettingsRepository = SettingsRepository(context)
) {
    val appContext: Context = context.applicationContext
    private val db = NizamDatabase.get(context)

    val taskRepository = TaskRepository(db.taskDao())
    val habitRepository = HabitRepository(db.habitDao())
    val prayerRepository = PrayerRepository(db.prayerDao())
    val financeRepository = FinanceRepository(db.financeDao())
    val dietRepository = DietRepository(db.dietDao())
    val gymRepository = GymRepository(db.gymDao())
    val noteRepository = NoteRepository(db.noteDao())
    val bookRepository = BookRepository(db.bookDao())
    val diaryRepository = DiaryRepository(db.diaryDao())
    val quranRepository = QuranRepository(db.quranDao())
    val hadithRepository = HadithRepository(db.hadithDao(), settingsRepository)
    val learningRepository = LearningRepository(db.learningDao())
    val survivalRepository = SurvivalRepository(db.survivalDao())
    val quoteRepository = QuoteRepository(db.quoteDao())
    val courseRepository = CourseRepository(db.courseDao(), settingsRepository)
    val newsRepository = NewsRepository(db.newsDao(), settingsRepository)
    // Missions read from every other repository to verify themselves, so they get a lazy handle
    // back to the container rather than fifteen constructor arguments.
    val missionRepository = MissionRepository(db.missionDao()) { this }
    val selfRepository = SelfRepository(db.selfDao()) { this }
    val dynamicRepository = DynamicRepository(db.dynamicDao())
    val curatorRepository = CuratorRepository(db.curatorDao()) { this }
    val vaultRepository = VaultRepository(db.vaultDao())
    val moduleGenerator = ModuleGenerator { this }
    val agentMemory = AgentMemory(db.agentDao())
    val agentLoop = AgentLoop { this }
}

class NizamApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.nizam.app.feature.safe.CrashGuard.install(this)
        com.nizam.app.ui.design.Density.load(this)
        val startupSettings = SettingsRepository(this)
        // Restore staged files before Room or DataStore opens them.
        runBlocking(Dispatchers.IO) {
            Backup.applyPendingRestore(this@NizamApplication)
            startupSettings.protectLegacySecrets()
        }
        container = AppContainer(this, startupSettings)
        com.nizam.app.net.Router.appContext = applicationContext
        com.nizam.app.feature.expertise.EngineLog.context = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { com.nizam.app.feature.expertise.Expertise.all(applicationContext) } }
        // Seed the built-in module specs on first launch.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { container.dynamicRepository.seedIfEmpty() }
        }
    }
}
