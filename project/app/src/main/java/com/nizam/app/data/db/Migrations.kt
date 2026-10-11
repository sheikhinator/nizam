package com.nizam.app.data.db

import androidx.room.migration.Migration

/**
 * Every schema change from v7 onward MUST add a Migration here, in the same build that bumps the
 * version. There is no destructive fallback any more — a missing migration crashes loudly on
 * launch rather than silently deleting your history. That is the point.
 *
 *   val MIGRATION_7_8 = object : Migration(7, 8) {
 *       override fun migrate(db: SupportSQLiteDatabase) {
 *           db.execSQL("CREATE TABLE IF NOT EXISTS `x` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ...)")
 *       }
 *   }
 */
/** v8: books remember their cover image. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `book` ADD COLUMN `coverUrl` TEXT NOT NULL DEFAULT ''")
    }
}

/** v9: preserve exact weight and individual water-entry times for Health Connect write-back. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `body_metric` ADD COLUMN `recordedAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `water_intake` (" +
                "`id` TEXT NOT NULL, `localDate` TEXT NOT NULL, `ml` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL DEFAULT 0, " +
                "`deletionHandled` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
        )
    }
}

val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_7_8, MIGRATION_8_9)
