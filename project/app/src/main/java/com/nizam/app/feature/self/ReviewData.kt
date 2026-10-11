package com.nizam.app.feature.self

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "review")
data class Review(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,                 // DAILY | WEEKLY
    val localDate: String,
    val went_well: String = "",
    val went_badly: String = "",
    val oneChange: String = "",
    val aiReading: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** Identity statements: who the user is deciding to be, in their own words. */
@Entity(tableName = "identity_line")
data class IdentityLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface SelfDao {
    @Query("SELECT * FROM review ORDER BY localDate DESC, id DESC LIMIT 60")
    fun observeReviews(): Flow<List<Review>>

    @Query("SELECT * FROM review WHERE kind = :kind AND localDate = :date LIMIT 1")
    suspend fun reviewFor(kind: String, date: String): Review?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReview(review: Review)

    @Query("SELECT * FROM identity_line ORDER BY createdAt DESC")
    fun observeIdentity(): Flow<List<IdentityLine>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIdentity(line: IdentityLine)

    @Query("DELETE FROM identity_line WHERE id = :id")
    suspend fun deleteIdentity(id: Long)
}
