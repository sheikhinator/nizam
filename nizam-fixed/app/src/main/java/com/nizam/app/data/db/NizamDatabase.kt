package com.nizam.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nizam.app.feature.news.data.Article as NewsArticle
import com.nizam.app.data.db.NewsDao

@Database(
    entities = [
        NewsArticle::class,
        // Add other entities here
    ],
    version = 1,
    exportSchema = false
)
abstract class NizamDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao
    // Add other dao methods here
}