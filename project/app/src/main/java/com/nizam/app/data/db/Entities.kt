package com.nizam.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "day_log")
data class DayLog(
    @PrimaryKey val localDate: String,
    val dayRating: Int? = null,
    val energy: Int? = null,
    val note: String? = null
)

@Entity(tableName = "task", indices = [Index("due"), Index("completedAt")])
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val due: Long? = null,
    val priority: Int = 1,
    val projectId: Long? = null,
    val recurrenceRule: String? = null,
    val completedAt: Long? = null,
    val estimateMin: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)

@Entity(tableName = "project")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: String = "#2E7D6F",
    val archived: Boolean = false
)

@Entity(tableName = "habit")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String = "check",
    val targetPerWeek: Int = 7,
    val cadence: String = "DAILY",
    val unit: String = "COUNT",
    val color: String = "#2E7D6F",
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "habit_entry",
    indices = [Index(value = ["habitId", "localDate"], unique = true)]
)
data class HabitEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val localDate: String,
    val value: Double = 1.0,
    val note: String? = null
)
