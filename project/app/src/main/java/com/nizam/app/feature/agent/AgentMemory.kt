package com.nizam.app.feature.agent

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.nizam.app.core.today
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Chat that survives closing the app. */
@Entity(tableName = "agent_turn")
data class AgentTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromUser: Boolean,
    val text: String,
    val localDate: String = today(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * What the agent knows between sessions. `stated` is what you told it; `inferred` is what it
 * worked out. Keeping those apart is what stops an agent treating its own guesses as fact.
 */
@Entity(tableName = "agent_note")
data class AgentNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,              // stated | inferred | procedural
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface AgentDao {
    @Query("SELECT * FROM agent_turn ORDER BY id ASC LIMIT 400")
    fun observeTurns(): Flow<List<AgentTurn>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: AgentTurn)

    @Query("DELETE FROM agent_turn")
    suspend fun clearTurns()

    @Query("SELECT * FROM agent_note ORDER BY createdAt DESC LIMIT 60")
    fun observeNotes(): Flow<List<AgentNote>>

    @Query("SELECT * FROM agent_note ORDER BY createdAt DESC LIMIT 40")
    suspend fun notesOnce(): List<AgentNote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: AgentNote)

    @Query("DELETE FROM agent_note WHERE id = :id")
    suspend fun deleteNote(id: Long)
}

class AgentMemory(private val dao: AgentDao) {
    fun turns() = dao.observeTurns()
    fun notes() = dao.observeNotes()

    suspend fun addTurn(fromUser: Boolean, text: String) =
        dao.insertTurn(AgentTurn(fromUser = fromUser, text = text))

    suspend fun clearChat() = dao.clearTurns()

    suspend fun remember(text: String, kind: String = "stated") =
        dao.insertNote(AgentNote(kind = kind, text = text.take(2000)))

    suspend fun forget(note: AgentNote) = dao.deleteNote(note.id)

    suspend fun recall(): String {
        val notes = dao.notesOnce()
        if (notes.isEmpty()) return ""
        return "What you remember about this user:\n" +
            notes.joinToString("\n") { "  [${it.kind}] ${it.text}" }
    }

    suspend fun history(limit: Int = 14): String {
        val turns = dao.observeTurns().first().takeLast(limit)
        return turns.joinToString("\n") { (if (it.fromUser) "User: " else "Atlas: ") + it.text }
    }
}
