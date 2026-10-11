package com.nizam.app.feature.quotes

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
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.vmFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@Entity(tableName = "quote")
data class Quote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val source: String = "",
    val favourite: Boolean = false
)

@Dao
interface QuoteDao {
    @Query("SELECT * FROM quote ORDER BY id DESC")
    fun observeAll(): Flow<List<Quote>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(quote: Quote)

    @Delete
    suspend fun delete(quote: Quote)
}

class QuoteRepository(private val dao: QuoteDao) {
    fun all() = dao.observeAll()
    suspend fun add(text: String, source: String) = dao.insert(Quote(text = text.trim(), source = source.trim()))
    suspend fun remove(quote: Quote) = dao.delete(quote)
}

class QuotesViewModel(private val repository: QuoteRepository) : ViewModel() {
    val quotes: StateFlow<List<Quote>> = repository.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(text: String, source: String) {
        if (text.isBlank()) return
        viewModelScope.launch { repository.add(text, source) }
    }
    fun delete(quote: Quote) = viewModelScope.launch { repository.remove(quote) }
}

/** Deterministic pick so the quote of the day stays the same all day. */
fun quoteOfTheDay(quotes: List<Quote>): Quote? {
    if (quotes.isEmpty()) return null
    val index = (LocalDate.now().toEpochDay() % quotes.size).toInt()
    return quotes[index]
}

@Composable
fun QuotesScreen(container: AppContainer) {
    val vm: QuotesViewModel = viewModel(factory = vmFactory { QuotesViewModel(container.quoteRepository) })
    val quotes by vm.quotes.collectAsState()
    var text by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }

    val todays = quoteOfTheDay(quotes)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Column(Modifier.fillMaxWidth()) {
        ModuleTitle("Quotes", "Your own collection — nothing invented, nothing fetched")

        if (todays != null) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Today", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(todays.text, style = MaterialTheme.typography.titleLarge)
                    if (todays.source.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(todays.source, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("Quote") }, modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = source, onValueChange = { source = it },
                label = { Text("Source") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.add(text, source); text = ""; source = "" }) { Text("Add") }
        }

        Spacer(Modifier.height(12.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)

        if (quotes.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Empty on purpose. Add lines you actually want to reread — from books you finish, or people you trust.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
            }
        }

            items(quotes, key = { it.id }) { q ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(q.text, style = MaterialTheme.typography.bodyLarge)
                    if (q.source.isNotBlank()) {
                        Text(q.source, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "delete",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(role = Role.Button) { vm.delete(q) }
                    )
                }
                Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
    }
}
