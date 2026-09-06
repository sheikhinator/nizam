package com.nizam.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nizam.app.feature.news.data.Article
import com.nizam.app.feature.news.data.NewsDao

@Database(
    entities = [Article::class],
    version = 1,
    exportSchema = false
)
abstract class NizamDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao

    companion object {
        @Volatile
        private var INSTANCE: NizamDatabase? = null

        fun getDatabase(context: Context): NizamDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NizamDatabase::class.java,
                    "nizam_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
