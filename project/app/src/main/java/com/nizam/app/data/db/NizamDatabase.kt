package com.nizam.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.nizam.app.feature.diary.DiaryDao
import com.nizam.app.feature.diary.DiaryEntry
import com.nizam.app.feature.diary.MoodCheckIn
import com.nizam.app.feature.diet.BodyMetric
import com.nizam.app.feature.diet.DietDao
import com.nizam.app.feature.diet.MealItem
import com.nizam.app.feature.diet.WaterIntake
import com.nizam.app.feature.diet.WaterLog
import com.nizam.app.feature.finance.Budget
import com.nizam.app.feature.finance.FinanceDao
import com.nizam.app.feature.finance.Txn
import com.nizam.app.feature.gym.Cardio
import com.nizam.app.feature.gym.GymDao
import com.nizam.app.feature.gym.WorkoutSet
import com.nizam.app.feature.hadith.Hadith
import com.nizam.app.feature.hadith.HadithDao
import com.nizam.app.feature.learning.Course
import com.nizam.app.feature.learning.CourseDao
import com.nizam.app.feature.learning.Flashcard
import com.nizam.app.feature.learning.Lesson
import com.nizam.app.feature.learning.LearningDao
import com.nizam.app.feature.learning.StudySession
import com.nizam.app.feature.learning.Track
import com.nizam.app.feature.library.Book
import com.nizam.app.feature.library.BookDao
import com.nizam.app.feature.missions.Goal
import com.nizam.app.feature.missions.Mission
import com.nizam.app.feature.missions.MissionDao
import com.nizam.app.feature.news.Article
import com.nizam.app.feature.news.NewsDao
import com.nizam.app.feature.notes.Note
import com.nizam.app.feature.notes.NoteDao
import com.nizam.app.feature.prayer.PrayerDao
import com.nizam.app.feature.prayer.PrayerDay
import com.nizam.app.feature.prayer.PrayerExtra
import com.nizam.app.feature.quotes.Quote
import com.nizam.app.feature.quotes.QuoteDao
import com.nizam.app.feature.quran.Ayah
import com.nizam.app.feature.quran.AyahBookmark
import com.nizam.app.feature.quran.QuranDao
import com.nizam.app.feature.agent.AgentDao
import com.nizam.app.feature.agent.AgentNote
import com.nizam.app.feature.agent.AgentTurn
import com.nizam.app.feature.agent.CuratorDao
import com.nizam.app.feature.agent.Suggestion
import com.nizam.app.feature.dynamic.DynamicDao
import com.nizam.app.feature.dynamic.ModuleEntry
import com.nizam.app.feature.dynamic.ModuleSpec
import com.nizam.app.feature.self.IdentityLine
import com.nizam.app.feature.self.Review
import com.nizam.app.feature.self.SelfDao
import com.nizam.app.feature.survival.ChecklistItem
import com.nizam.app.feature.survival.SurvivalDao
import com.nizam.app.feature.vault.VaultDao
import com.nizam.app.feature.vault.VaultItem

@Database(
    entities = [
        DayLog::class, Task::class, Project::class, Habit::class, HabitEntry::class,
        PrayerDay::class, PrayerExtra::class,
        Txn::class, Budget::class,
        MealItem::class, WaterLog::class, WaterIntake::class, BodyMetric::class,
        WorkoutSet::class, Cardio::class,
        Note::class,
        Book::class,
        DiaryEntry::class, MoodCheckIn::class,
        Ayah::class, AyahBookmark::class,
        Hadith::class,
        Track::class, StudySession::class, Flashcard::class,
        ChecklistItem::class,
        Quote::class,
        Course::class, Lesson::class,
        Article::class,
        Goal::class, Mission::class,
        Review::class, IdentityLine::class,
        ModuleSpec::class, ModuleEntry::class,
        Suggestion::class, VaultItem::class,
        AgentTurn::class, AgentNote::class
    ],
    version = 9,
    exportSchema = false
)
abstract class NizamDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun habitDao(): HabitDao
    abstract fun dayLogDao(): DayLogDao
    abstract fun prayerDao(): PrayerDao
    abstract fun financeDao(): FinanceDao
    abstract fun dietDao(): DietDao
    abstract fun gymDao(): GymDao
    abstract fun noteDao(): NoteDao
    abstract fun bookDao(): BookDao
    abstract fun diaryDao(): DiaryDao
    abstract fun quranDao(): QuranDao
    abstract fun hadithDao(): HadithDao
    abstract fun learningDao(): LearningDao
    abstract fun survivalDao(): SurvivalDao
    abstract fun quoteDao(): QuoteDao
    abstract fun courseDao(): CourseDao
    abstract fun newsDao(): NewsDao
    abstract fun missionDao(): MissionDao
    abstract fun selfDao(): SelfDao
    abstract fun dynamicDao(): DynamicDao
    abstract fun curatorDao(): CuratorDao
    abstract fun agentDao(): AgentDao
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: NizamDatabase? = null

        fun get(context: Context): NizamDatabase {
            // Screenshot tests run on a plain JVM with no SQLCipher: they get a throwaway in-memory database.
            if (android.os.Build.FINGERPRINT == "robolectric") return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.inMemoryDatabaseBuilder(context.applicationContext, NizamDatabase::class.java)
                    .allowMainThreadQueries().build().also { INSTANCE = it }
            }
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                val key = EncryptedDatabaseBootstrap.prepare(appContext)
                val database = Room.databaseBuilder(
                    appContext,
                    NizamDatabase::class.java,
                    "nizam.db"
                )
                    .openHelperFactory(SupportOpenHelperFactory(key))
                    // No destructive fallback. Real data lives here now: a schema change without
                    // a Migration must fail loudly in development, never silently wipe a user's
                    // history. Only a downgrade (installing an older build) may reset.
                    .addMigrations(*MIGRATIONS)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()

                try {
                    // Room opens lazily; force the encrypted file open before declaring migration
                    // complete or allowing the rest of the app to access stores.
                    database.openHelper.writableDatabase
                    EncryptedDatabaseBootstrap.markReady(appContext)
                    database.also { INSTANCE = it }
                } catch (failure: Exception) {
                    database.close()
                    key.fill(0)
                    throw failure
                }
            }
        }
    }
}
