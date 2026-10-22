package com.nizam.app.feature.news

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.vmFactory
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

@Entity(tableName = "article")
data class Article(
    @PrimaryKey val link: String,
    val section: String,
    val title: String,
    val summary: String,
    val pubDate: String,
    val fetchedAt: Long = System.currentTimeMillis()
)

@Dao
interface NewsDao {
    @Query("SELECT * FROM article WHERE section = :section ORDER BY fetchedAt DESC, rowid ASC LIMIT 60")
    fun observeSection(section: String): Flow<List<Article>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Article>)

    @Query("DELETE FROM article WHERE section = :section")
    suspend fun clearSection(section: String)
}

data class NewsSection(val label: String, val slug: String, val feed: String)

val DAWN_SECTIONS = listOf(
    NewsSection("Home", "home", "https://www.dawn.com/feeds/home"),
    NewsSection("Latest", "latest", "https://www.dawn.com/feeds/latest-news"),
    NewsSection("Pakistan", "pakistan", "https://www.dawn.com/feeds/pakistan"),
    NewsSection("World", "world", "https://www.dawn.com/feeds/world"),
    NewsSection("Business", "business", "https://www.dawn.com/feeds/business"),
    NewsSection("Sport", "sport", "https://www.dawn.com/feeds/sport"),
    NewsSection("Opinion", "opinion", "https://www.dawn.com/feeds/opinion")
)

class NewsRepository(private val dao: NewsDao, private val settings: SettingsRepository) {

    fun section(slug: String) = dao.observeSection(slug)

    suspend fun refresh(section: NewsSection): String? = withContext(Dispatchers.IO) {
        try {
            val xml = Http.getText(section.feed)
            val items = parseRss(xml, section.slug)
            if (items.isEmpty()) return@withContext "Dawn returned no items for ${section.label}."
            dao.clearSection(section.slug)
            dao.insertAll(items)
            null
        } catch (e: Exception) {
            "Could not load ${section.label}: ${com.nizam.app.ui.components.Feedback.friendly(e)}"
        }
    }

    private fun parseRss(xml: String, section: String): List<Article> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val out = mutableListOf<Article>()
        var inItem = false
        var title = ""
        var link = ""
        var description = ""
        var pubDate = ""
        var tag = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name.lowercase()
                    if (tag == "item") {
                        inItem = true
                        title = ""; link = ""; description = ""; pubDate = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inItem) {
                        val text = parser.text ?: ""
                        when (tag) {
                            "title" -> title += text
                            "link" -> link += text
                            "description" -> description += text
                            "pubdate" -> pubDate += text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.lowercase() == "item" && inItem) {
                        inItem = false
                        if (link.isNotBlank() && title.isNotBlank()) {
                            out.add(
                                Article(
                                    link = link.trim(),
                                    section = section,
                                    title = title.trim(),
                                    summary = stripHtml(description),
                                    pubDate = pubDate.trim()
                                )
                            )
                        }
                    }
                    tag = ""
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun stripHtml(input: String): String =
        input.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()

    suspend fun explain(article: Article, question: String): String {
        val key = settings.geminiApiKey.first()
        val model = settings.geminiModel.first()
        return Ai.generate(
            settings = settings,
            prompt = "Pakistani news article headline: ${article.title}\n" +
                "Summary from Dawn: ${article.summary}\n\n$question",
            system = "You explain news context for a Pakistani reader: background, who the actors are, " +
                "why it matters. Be factual and balanced, present different perspectives on contested " +
                "issues, and say clearly when you are unsure or when your knowledge may be out of date.",
        )
    }
}

class NewsViewModel(private val repository: NewsRepository) : ViewModel() {
    private val _section = MutableStateFlow(DAWN_SECTIONS.first())
    val section: StateFlow<NewsSection> = _section.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun articles(slug: String) = repository.section(slug)

    fun select(section: NewsSection) {
        _section.value = section
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            _error.value = repository.refresh(_section.value)
            _busy.value = false
        }
    }
}

@Composable
fun NewsScreen(container: AppContainer) {
    val vm: NewsViewModel = viewModel(factory = vmFactory { NewsViewModel(container.newsRepository) })
    val section by vm.section.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val articles by vm.articles(section.slug).collectAsState(initial = emptyList())
    val context = LocalContext.current

    var open by remember { mutableStateOf<Article?>(null) }

    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { vm.refresh() } }

    val current = open
    if (current != null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { open = null }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back to headlines")
                }
                Text("Dawn", style = MaterialTheme.typography.titleMedium)
            }
            Text(current.title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(current.pubDate, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(current.summary, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.link)))
                }
            }) { Text("Read full story on Dawn") }

            Spacer(Modifier.height(20.dp))
            com.nizam.app.core.AiPanel(
                settings = container.settingsRepository,
                label = "Understand this story",
                contextPrompt = "Headline: ${current.title}\nDawn's summary: ${current.summary}",
                starterQuestion = "What's the background here, and why does it matter?",
                system = "You explain news context for a Pakistani reader. Be factual and balanced, " +
                    "give the main perspectives on contested issues rather than your own opinion, and " +
                    "flag when your knowledge may be out of date."
            )
            Spacer(Modifier.height(40.dp))
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Column(Modifier.fillMaxWidth()) {
        ModuleTitle("News", "Dawn — Pakistan's English daily")

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAWN_SECTIONS.take(4).forEach { s ->
                FilterChip(
                    selected = section.slug == s.slug,
                    onClick = { vm.select(s) },
                    label = { Text(s.label) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAWN_SECTIONS.drop(4).forEach { s ->
                FilterChip(
                    selected = section.slug == s.slug,
                    onClick = { vm.select(s) },
                    label = { Text(s.label) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !busy, onClick = { vm.refresh() }) {
                Text(if (busy) "Refreshing…" else "Refresh")
            }
            if (busy) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator()
            }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(8.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }

            items(articles, key = { it.link }) { article ->
                Column(
                    Modifier.fillMaxWidth().clickable { open = article }.padding(vertical = 12.dp)
                ) {
                    Text(article.title, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        article.summary.take(120),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
            item { Spacer(Modifier.height(40.dp)) }
    }
}
