package com.nizam.app.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.vmFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Entity(tableName = "note")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String = "",
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM note ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM note WHERE title LIKE '%' || :q || '%' OR body LIKE '%' || :q || '%' ORDER BY updatedAt DESC")
    fun search(q: String): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)

    @Query("SELECT * FROM note")
    suspend fun getAll(): List<Note>
}

class NoteRepository(private val dao: NoteDao) {
    fun all() = dao.observeAll()
    fun search(q: String) = dao.search(q)
    suspend fun create(title: String): Long = dao.insert(Note(title = title.trim()))
    suspend fun createFull(title: String, body: String): Long =
        dao.insert(Note(title = title.trim(), body = body))
    suspend fun save(note: Note) = dao.update(note.copy(updatedAt = System.currentTimeMillis()))
    suspend fun remove(note: Note) = dao.delete(note)
    suspend fun everything(): List<Note> = dao.getAll()

    /** Obsidian-style rename: the note's title changes and every [[link]] to it across the vault follows. */
    suspend fun rename(note: Note, newTitle: String) {
        val old = note.title
        val nt = newTitle.trim()
        if (nt.isBlank() || nt == old) return
        dao.update(note.copy(title = nt, updatedAt = System.currentTimeMillis()))
        val link = Regex("\\[\\[" + Regex.escape(old) + "(\\|[^\\]]*)?]]", RegexOption.IGNORE_CASE)
        dao.getAll().filter { it.id != note.id && link.containsMatchIn(it.body) }.forEach { n ->
            dao.update(n.copy(body = n.body.replace(link) { m -> "[[" + nt + m.groupValues[1] + "]]" }, updatedAt = System.currentTimeMillis()))
        }
    }
}

/** Extracts [[wikilinks]] from a note body. */
fun wikilinksIn(body: String): List<String> =
    Regex("\\[\\[([^\\]]+)]]").findAll(body).map { it.groupValues[1].trim() }.toList()

class NotesViewModel(private val repository: NoteRepository) : ViewModel() {
    val notes: StateFlow<List<Note>> = repository.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.create(title) }
    }
    fun save(note: Note) = viewModelScope.launch { repository.save(note) }
    fun delete(note: Note) = viewModelScope.launch { repository.remove(note) }
    fun rename(note: Note, title: String) = viewModelScope.launch { repository.rename(note, title) }
    /** Creates (if needed) and returns the id of a note with this title. */
    suspend fun ensure(title: String, body: String = ""): Long =
        notes.value.firstOrNull { it.title.equals(title.trim(), true) }?.id ?: repository.createFull(title, body)
}
