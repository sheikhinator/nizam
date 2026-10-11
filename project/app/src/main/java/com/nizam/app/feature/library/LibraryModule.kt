package com.nizam.app.feature.library

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.nizam.app.core.ChipRow
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import com.nizam.app.ui.design.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.nizam.app.AppContainer
import com.nizam.app.core.StatRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.displayNameFor
import com.nizam.app.core.vmFactory
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Entity(tableName = "book")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val format: String = "TXT",
    val fileUri: String = "",
    val status: String = "TO_READ",
    val progressPercent: Int = 0,
    val lastPage: Int = 0,
    val rating: Int = 0,
    val notes: String = "",
    @ColumnInfo(defaultValue = "") val coverUrl: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface BookDao {
    @Query("SELECT * FROM book ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book)

    @Update
    suspend fun update(book: Book)

    @Delete
    suspend fun delete(book: Book)
}

class BookRepository(private val dao: BookDao) {
    fun all() = dao.observeAll()
    suspend fun add(book: Book) = dao.insert(book)
    suspend fun save(book: Book) = dao.update(book)
    suspend fun remove(book: Book) = dao.delete(book)
}

class LibraryViewModel(private val repository: BookRepository) : ViewModel() {
    val books: StateFlow<List<Book>> = repository.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addBook(book: Book) = viewModelScope.launch { repository.add(book) }

    fun add(title: String, uri: String, format: String) {
        viewModelScope.launch {
            repository.add(Book(title = title, fileUri = uri, format = format, status = "READING"))
        }
    }

    fun save(book: Book) = viewModelScope.launch { repository.save(book) }
    fun delete(book: Book) = viewModelScope.launch { repository.remove(book) }
}

private val STATUSES = listOf("TO_READ" to "To read", "READING" to "Reading",
    "FINISHED" to "Finished", "DNF" to "Dropped")

@Composable
fun LibraryScreen(container: AppContainer) {
    val vm: LibraryViewModel = viewModel(factory = vmFactory { LibraryViewModel(container.bookRepository) })
    val books by vm.books.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var reading by remember { mutableStateOf<Book?>(null) }
    var editing by remember { mutableStateOf<Book?>(null) }
    var filter by remember { mutableStateOf("ALL") }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<BookMatch>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<Book?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = displayNameFor(context, uri)
        val format = when {
            name.endsWith(".pdf", true) -> "PDF"
            name.endsWith(".md", true) -> "MD"
            else -> "TXT"
        }
        val target = pendingFile
        if (target != null) { vm.save(target.copy(fileUri = uri.toString(), format = format)); pendingFile = null }
        else scope.launch {
            val title = name.substringBeforeLast('.')
            val cover = runCatching { Covers.find(title, "") }.getOrNull()
            vm.addBook(Book(title = title, fileUri = uri.toString(), format = format, status = "READING", coverUrl = cover ?: ""))
        }
    }

    reading?.let { book ->
        if (book.format == "PDF") PdfReader(book, onBack = { reading = null }, onSave = { vm.save(it) })
        else TextReader(book, onBack = { reading = null })
        return
    }
    editing?.let { book ->
        BookEditor(book, onBack = { editing = null }, onSave = { vm.save(it); editing = null }, onDelete = { vm.delete(book); editing = null })
        return
    }

    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                ModuleTitle("Library", "Your shelf — covers, progress, and PDFs read in-app")
                ChipRow(listOf("ALL", "TO_READ", "READING", "FINISHED"), filter, { filter = it },
                    labels = { mapOf("ALL" to "All", "TO_READ" to "To read", "READING" to "Reading", "FINISHED" to "Finished")[it] ?: it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    Button(onClick = { searching = !searching }) { Text("＋ Add a book") }
                    OutlinedButton(onClick = { pendingFile = null; picker.launch(arrayOf("application/pdf", "text/*", "*/*")) }) { Text("From file") }
                }
                if (searching) {
                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search by title or author") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            scope.launch { busy = true; matches = runCatching { Covers.search(query) }.getOrElse { emptyList() }; busy = false }
                        }))
                    Text(if (busy) "Searching Open Library…" else "Tap a result to add it to your shelf",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        matches.forEach { m ->
                            Column(Modifier.width(96.dp).clickable(role = Role.Button) {
                                vm.addBook(Book(title = m.title, author = m.author, coverUrl = m.cover ?: "", status = "TO_READ"))
                                searching = false; query = ""; matches = emptyList()
                            }) {
                                Cover(m.cover, m.title, Modifier.fillMaxWidth())
                                Text(m.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                Text(listOf(m.author, m.year).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (books.isNotEmpty()) {
                    val finished = books.count { it.status == "FINISHED" }
                    Text("${books.size} books · $finished finished · ${books.count { it.status == "READING" }} reading",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        val visible = books.filter { filter == "ALL" || it.status == filter }
        if (visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Nothing here yet. Search for a book, or add a PDF from your files.", style = MaterialTheme.typography.bodyMedium)
        }
        items(visible, key = { it.id }) { book ->
            Column {
                Box {
                    Cover(book.coverUrl.ifBlank { null }, book.title, Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        if (book.fileUri.isNotBlank()) reading = book else editing = book
                    })
                    if (book.progressPercent > 0) LinearProgressIndicator(
                        progress = { book.progressPercent / 100f },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
                }
                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                Text(book.author.ifBlank { book.status.lowercase().replace('_', ' ') }, maxLines = 1,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    Text("edit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(role = Role.Button) { editing = book }.padding(end = 8.dp, top = 2.dp))
                    if (book.fileUri.isBlank()) Text("file", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(role = Role.Button) { pendingFile = book; picker.launch(arrayOf("application/pdf", "text/*", "*/*")) }
                            .padding(end = 8.dp, top = 2.dp))
                    if (book.coverUrl.isBlank()) Text("cover", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(role = Role.Button) {
                            scope.launch { Covers.find(book.title, book.author)?.let { vm.save(book.copy(coverUrl = it)) } }
                        }.padding(top = 2.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(60.dp)) }
    }
}

/** A book cover, or a tidy spine-coloured placeholder when there isn't one. */
@Composable
private fun Cover(url: String?, title: String, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center) {
        if (url != null) coil.compose.AsyncImage(url, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(title.take(40), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
    }
}


@Composable
private fun BookEditor(
    book: Book,
    onBack: () -> Unit,
    onSave: (Book) -> Unit,
    onDelete: (Book) -> Unit
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author) }
    var status by remember(book.id) { mutableStateOf(book.status) }
    var rating by remember(book.id) { mutableStateOf(book.rating) }
    var notes by remember(book.id) { mutableStateOf(book.notes) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to library")
            }
            Text("Edit book", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = author, onValueChange = { author = it },
            label = { Text("Author") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))
        Text("Status", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STATUSES.take(2).forEach { (value, label) ->
                FilterChip(selected = status == value, onClick = { status = value }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STATUSES.drop(2).forEach { (value, label) ->
                FilterChip(selected = status == value, onClick = { status = value }, label = { Text(label) })
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Rating", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { n ->
                Text(
                    if (rating >= n) "★" else "☆",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.clickable(role = Role.Button) { rating = if (rating == n) 0 else n }
                )
            }
        }

        OutlinedTextField(
            value = notes, onValueChange = { notes = it },
            label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().height(120.dp)
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onSave(book.copy(
                    title = title.trim(), author = author.trim(),
                    status = status, rating = rating, notes = notes
                ))
            }) { Text("Save") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Remove") }
        }
        if (confirmDelete) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Remove \"${book.title}\" from the library? The file on your device is untouched.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDelete(book) }) { Text("Yes, remove") }
                OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

/** Renders PDF pages natively with PdfRenderer — no external app, no extra dependency. */
@Composable
private fun PdfReader(book: Book, onBack: () -> Unit, onSave: (Book) -> Unit) {
    val context = LocalContext.current
    var page by remember(book.id) { mutableStateOf(book.lastPage) }
    var pageCount by remember(book.id) { mutableStateOf(0) }
    var bitmap by remember(book.id) { mutableStateOf<Bitmap?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    var loading by remember(book.id) { mutableStateOf(true) }

    LaunchedEffect(book.id, page) {
        loading = true
        error = null
        val rendered = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(book.fileUri), "r")
                    ?.use { descriptor: ParcelFileDescriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            pageCount = renderer.pageCount
                            val index = page.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                            renderer.openPage(index).use { pdfPage ->
                                val scale = 2
                                val bmp = Bitmap.createBitmap(
                                    pdfPage.width * scale,
                                    pdfPage.height * scale,
                                    Bitmap.Config.ARGB_8888
                                )
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp
                            }
                        }
                    }
            }.getOrElse { e ->
                error = "Could not open this PDF: ${com.nizam.app.ui.components.Feedback.friendly(e)}"
                null
            }
        }
        bitmap = rendered
        loading = false
        if (pageCount > 0) {
            onSave(
                book.copy(
                    lastPage = page,
                    progressPercent = ((page + 1) * 100 / pageCount).coerceIn(0, 100)
                )
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to library")
            }
            Text(book.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (pageCount > 0) {
                Text("${page + 1}/$pageCount", style = MonoNumber)
            }
        }

        if (loading) {
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator()
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        bitmap?.let { bmp ->
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${page + 1}",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (pageCount > 1) {
            Slider(
                value = page.toFloat(),
                onValueChange = { page = it.toInt() },
                valueRange = 0f..(pageCount - 1).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(
                enabled = page > 0,
                onClick = { page-- }
            ) { Text("<  Previous") }
            OutlinedButton(
                enabled = pageCount > 0 && page < pageCount - 1,
                onClick = { page++ }
            ) { Text("Next  >") }
        }
    }
}

@Composable
private fun TextReader(book: Book, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember(book.id) { mutableStateOf("Loading…") }
    var fontSize by remember { mutableStateOf(17f) }

    LaunchedEffect(book.id) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(book.fileUri))?.use { stream ->
                    stream.bufferedReader().readText()
                } ?: "Could not open this file."
            }.getOrElse { "Could not open this file. It may have been moved or deleted." }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to library")
            }
            Text(book.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 14f..28f)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = fontSize.sp, lineHeight = (fontSize * 1.6).sp
                )
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
