package com.nizam.app.feature.missions

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.nizam.app.core.today
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "goal")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val why: String = "",
    val deadline: String = "",
    val strategy: String = "",
    val status: String = "ACTIVE",          // ACTIVE | DONE | ABANDONED
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A mission is one concrete action with a verification rule. Difficulty and XP come from the
 * generator; verification comes from the user's own logged data wherever the app can see it.
 */
@Entity(tableName = "mission")
data class Mission(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long? = null,
    val localDate: String = today(),
    val title: String,
    val detail: String = "",
    val rationale: String = "",
    val difficulty: String = "medium",      // easy | medium | hard | very_hard
    val xp: Int = 20,
    val metric: String = "MANUAL",          // see Verifier
    val target: Double = 0.0,
    val unit: String = "",
    val orderIndex: Int = 0,
    val completedAt: Long? = null,
    val autoVerified: Boolean = false,
    val skipped: Boolean = false
)

@Dao
interface MissionDao {
    @Query("SELECT * FROM goal WHERE status = 'ACTIVE' ORDER BY createdAt DESC")
    fun observeGoals(): Flow<List<Goal>>

    @Query("SELECT * FROM goal ORDER BY createdAt DESC")
    fun observeAllGoals(): Flow<List<Goal>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(goal: Goal): Long

    @Update
    suspend fun updateGoal(goal: Goal)

    @Query("SELECT * FROM mission WHERE localDate = :date ORDER BY orderIndex ASC")
    fun observeForDate(date: String): Flow<List<Mission>>

    @Query("SELECT * FROM mission WHERE goalId = :goalId ORDER BY localDate ASC, orderIndex ASC")
    fun observeForGoal(goalId: Long): Flow<List<Mission>>

    @Query("SELECT * FROM mission WHERE localDate >= :from ORDER BY localDate DESC")
    fun observeSince(from: String): Flow<List<Mission>>

    @Query("SELECT * FROM mission WHERE localDate = :date")
    suspend fun forDateOnce(date: String): List<Mission>

    @Query("SELECT COUNT(*) FROM mission WHERE localDate = :date AND goalId IS NULL")
    suspend fun countDailyFor(date: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(missions: List<Mission>)

    @Update
    suspend fun update(mission: Mission)

    @Query("DELETE FROM mission WHERE id = :id")
    suspend fun deleteMission(id: Long)

    @Query("DELETE FROM mission WHERE goalId = :goalId")
    suspend fun deleteMissionsForGoal(goalId: Long)

    @Query("DELETE FROM goal WHERE id = :id")
    suspend fun deleteGoal(id: Long)

    @Query("SELECT * FROM mission WHERE completedAt IS NULL AND localDate >= :from ORDER BY localDate ASC LIMIT 60")
    suspend fun openMissions(from: String): List<Mission>
}

object Difficulty {
    fun xpFor(difficulty: String): Int = when (difficulty.lowercase()) {
        "easy" -> 10
        "hard" -> 35
        "very_hard" -> 60
        else -> 20
    }

    fun label(difficulty: String): String = when (difficulty.lowercase()) {
        "easy" -> "Easy"
        "hard" -> "Hard"
        "very_hard" -> "Very hard"
        else -> "Medium"
    }
}
