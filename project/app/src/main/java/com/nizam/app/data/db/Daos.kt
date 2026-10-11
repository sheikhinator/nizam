package com.nizam.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM task WHERE deletedAt IS NULL ORDER BY completedAt IS NOT NULL, priority DESC, due IS NULL, due ASC")
    fun observeAll(): Flow<List<Task>>

    @Query("SELECT * FROM task WHERE deletedAt IS NULL AND completedAt IS NULL AND due IS NOT NULL AND due BETWEEN :start AND :end ORDER BY due ASC")
    fun observeDueBetween(start: Long, end: Long): Flow<List<Task>>

    @Query("SELECT * FROM task WHERE deletedAt IS NULL AND completedAt IS NULL AND (due IS NULL OR due <= :end) ORDER BY priority DESC, due ASC")
    fun observeOpenThrough(end: Long): Flow<List<Task>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task)

    @Delete
    suspend fun delete(task: Task)

    @Query("DELETE FROM task WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habit WHERE archived = 0 ORDER BY createdAt ASC")
    fun observeHabits(): Flow<List<Habit>>

    @Query("SELECT * FROM habit_entry WHERE localDate = :localDate")
    fun observeEntriesForDay(localDate: String): Flow<List<HabitEntry>>

    @Query("SELECT * FROM habit_entry WHERE localDate >= :from")
    fun observeSince(from: String): Flow<List<HabitEntry>>

    @Query("SELECT * FROM habit_entry WHERE habitId = :habitId ORDER BY localDate DESC LIMIT :limit")
    fun observeRecent(habitId: Long, limit: Int): Flow<List<HabitEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHabit(habit: Habit): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: HabitEntry)

    @Query("DELETE FROM habit_entry WHERE habitId = :habitId AND localDate = :localDate")
    suspend fun clearEntry(habitId: Long, localDate: String)

    @Update
    suspend fun updateHabit(habit: Habit)
}

@Dao
interface DayLogDao {
    @Query("SELECT * FROM day_log WHERE localDate = :localDate")
    fun observeDay(localDate: String): Flow<DayLog?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dayLog: DayLog)
}
