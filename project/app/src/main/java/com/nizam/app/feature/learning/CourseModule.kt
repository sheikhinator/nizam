package com.nizam.app.feature.learning

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
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
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.Tones
import com.nizam.app.core.glyphFor
import com.nizam.app.ui.read.BlockList
import com.nizam.app.ui.read.BlockParser
import com.nizam.app.core.prettyDate
import com.nizam.app.core.today
import com.nizam.app.core.vmFactory
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.net.Ai
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

// ---------- data ----------

@Entity(tableName = "course")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val title: String,
    val overview: String,
    val level: String,
    val weeklyHours: Int,
    val weeks: Int,
    val startDate: String = today(),
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "lesson")
data class Lesson(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val unitTitle: String,
    val order: Int,
    val title: String,
    val objectives: String,
    val estimatedMinutes: Int,
    val lectureText: String = "",
    val pull: String = "",
    val scheduledDate: String = "",
    val completedAt: Long? = null
)

@Dao
interface CourseDao {
    @Query("SELECT * FROM course ORDER BY createdAt DESC")
    fun observeCourses(): Flow<List<Course>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: Course): Long

    @Query("SELECT * FROM lesson WHERE courseId = :courseId ORDER BY `order` ASC")
    fun observeLessons(courseId: Long): Flow<List<Lesson>>

    @Query("SELECT * FROM lesson WHERE scheduledDate = :date ORDER BY `order` ASC")
    fun observeForDate(date: String): Flow<List<Lesson>>

    @Query("SELECT * FROM lesson")
    fun observeAllLessons(): Flow<List<Lesson>>

    @Query("SELECT * FROM lesson WHERE id = :id")
    suspend fun lessonById(id: Long): Lesson?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLessons(lessons: List<Lesson>)

    @Update
    suspend fun updateLesson(lesson: Lesson)

    @Query("DELETE FROM course WHERE id = :id")
    suspend fun deleteCourse(id: Long)

    @Query("DELETE FROM lesson WHERE courseId = :id")
    suspend fun deleteLessonsFor(id: Long)
}

class CourseRepository(
    private val dao: CourseDao,
    private val settings: SettingsRepository
) {
    fun courses() = dao.observeCourses()
    fun lessons(courseId: Long) = dao.observeLessons(courseId)
    fun todaysLessons() = dao.observeForDate(today())
    fun allLessons() = dao.observeAllLessons()


    /** Generates a full syllabus, then schedules every lesson across the calendar. */
    suspend fun generateCourse(
        subject: String,
        level: String,
        weeklyHours: Int,
        weeks: Int,
        goal: String
    ): Long {
        val prompt = """
            Design a complete, rigorous self-study course on: $subject
            Learner level: $level
            Available study time: $weeklyHours hours per week for $weeks weeks
            Learner's goal: ${goal.ifBlank { "genuine mastery of the subject" }}

            Build a real curriculum with the depth of a university course, sequenced so each unit
            depends only on what came before. Prefer depth over breadth of buzzwords. Include
            practical work, not only theory.

            Return ONLY valid JSON, no commentary, in exactly this shape:
            {
              "title": "course title",
              "overview": "2-3 sentence description of what the learner will be able to do at the end",
              "units": [
                {
                  "title": "unit title",
                  "lessons": [
                    {
                      "title": "lesson title",
                      "objectives": "what the learner will understand or be able to do, one sentence",
                      "minutes": 60
                    }
                  ]
                }
              ]
            }

            Produce enough lessons to fill the whole $weeks weeks at $weeklyHours hours per week.
            Aim for 6 to 10 units, each with 3 to 8 lessons. Lesson lengths between 30 and 120 minutes.
        """.trimIndent()

        val raw = Ai.generate(
            settings = settings,
            prompt = prompt,
            system = "You are an expert curriculum designer. You return only valid JSON when asked to.",
        )
        val json = JSONObject(Ai.cleanJson(raw))

        val course = Course(
            subject = subject,
            title = json.optString("title", subject),
            overview = json.optString("overview", ""),
            level = level,
            weeklyHours = weeklyHours,
            weeks = weeks
        )
        val courseId = dao.insertCourse(course)

        val units = json.optJSONArray("units") ?: throw IllegalStateException("No units returned.")
        val lessons = mutableListOf<Lesson>()
        var order = 0
        for (u in 0 until units.length()) {
            val unit = units.getJSONObject(u)
            val unitTitle = unit.optString("title", "Unit ${u + 1}")
            val lessonArray = unit.optJSONArray("lessons") ?: continue
            for (l in 0 until lessonArray.length()) {
                val lesson = lessonArray.getJSONObject(l)
                lessons.add(
                    Lesson(
                        courseId = courseId,
                        unitTitle = unitTitle,
                        order = order++,
                        title = lesson.optString("title", "Lesson"),
                        objectives = lesson.optString("objectives", ""),
                        estimatedMinutes = lesson.optInt("minutes", 60)
                    )
                )
            }
        }
        if (lessons.isEmpty()) throw IllegalStateException("No lessons returned.")

        dao.insertLessons(schedule(lessons, weeklyHours))
        return courseId
    }

    /**
     * Spreads lessons across days so each study day stays within the weekly budget.
     * Six study days, Sunday off — the Pakistani working week, rather than a US default.
     */
    private fun schedule(lessons: List<Lesson>, weeklyHours: Int): List<Lesson> {
        val dailyBudget = ((weeklyHours * 60) / 6).coerceAtLeast(30)
        var date = LocalDate.now()
        var used = 0
        return lessons.map { lesson ->
            if (used + lesson.estimatedMinutes > dailyBudget && used > 0) {
                date = date.plusDays(1)
                while (date.dayOfWeek == java.time.DayOfWeek.SUNDAY) date = date.plusDays(1)
                used = 0
            }
            used += lesson.estimatedMinutes
            lesson.copy(scheduledDate = date.toString())
        }
    }

    /** Writes the full lecture as typed content blocks and caches it. */
    suspend fun generateLecture(lesson: Lesson, courseTitle: String, level: String): Lesson {
        val prompt = """
            Write one complete lecture from the course "$courseTitle" for a $level learner.

            Lesson: ${lesson.title}
            Unit: ${lesson.unitTitle}
            Objectives: ${lesson.objectives}
            Target study time: ${lesson.estimatedMinutes} minutes

            This is the learner's only material for this lesson. Teach it properly: define each
            concept, give the intuition, then the precise version, then work through real examples
            step by step. Name the mistakes people actually make here. Where the subject has
            formulas, code or procedures, show them and explain every part. Write like an excellent
            teacher talking to one person — direct, concrete, no filler, no "in this lesson we will".

            Return ONLY a JSON object, no commentary, in exactly this shape:
            {
              "pull": "one sharp sentence that captures the core idea, under 12 words",
              "content": [
                {"type": "paragraph", "text": "..."},
                {"type": "heading", "text": "..."},
                {"type": "example", "title": "Worked example", "body": "..."},
                {"type": "listNum", "items": ["...", "..."]},
                {"type": "formula", "expression": "LaTeX, e.g. \\frac{a}{b} or \\sum_{i=1}^{n} x_i", "explain": "..."},
                {"type": "flow", "title": "...", "steps": ["first step", "second step", "..."]},
                {"type": "table", "headers": ["...", "..."], "rows": [["...", "..."]]},
                {"type": "chart", "title": "...", "labels": ["A", "B"], "values": [12, 30]},
                {"type": "callout", "tone": "tip|warning|info", "text": "..."},
                {"type": "takeaways", "items": ["...", "...", "..."]},
                {"type": "practice", "question": "...", "answer": "..."},
                {"type": "cite", "text": "source or further reading"}
              ]
            }

            Visuals matter. A lesson that is only paragraphs is a failure. Use:
              - "flow" for any process, sequence, cycle or decision path
              - "table" to compare options, contrast concepts, or lay out data
              - "chart" whenever numbers are being compared — with real values
              - "formula" in proper LaTeX for anything mathematical; it is typeset, not shown raw
              - "callout" with tone "warning" for the mistakes people make, "tip" for shortcuts
            Aim for at least one flow and one table per lecture, and a chart when there is data.

            Rules: open with 2-3 paragraphs before the first heading. Use 4 to 7 headings. Use at
            least two "example" blocks and at least one "listNum". End with one "takeaways" block,
            then 4 "practice" blocks, then one "cite". Paragraphs are 3-6 sentences each. Aim for
            roughly ${lesson.estimatedMinutes * 180} words of actual teaching.
        """.trimIndent()

        val raw = Ai.generate(
            settings = settings,
            prompt = prompt,
            system = "You are an outstanding teacher and you return only valid JSON when asked to.",
            maxOutputTokens = 32768
        )
        val cleaned = Ai.cleanJson(raw)
        val pull = runCatching { JSONObject(cleaned).optString("pull", "") }.getOrElse { "" }
        val updated = lesson.copy(lectureText = cleaned, pull = pull)
        dao.updateLesson(updated)
        return updated
    }

    suspend fun deleteCourse(course: Course) {
        dao.deleteLessonsFor(course.id)
        dao.deleteCourse(course.id)
    }

    suspend fun toggleComplete(lesson: Lesson) {
        dao.updateLesson(
            lesson.copy(completedAt = if (lesson.completedAt == null) System.currentTimeMillis() else null)
        )
    }

    /** Turns a finished lecture into flashcards in the Learning module. */
    suspend fun makeFlashcards(lesson: Lesson, trackId: Long, learningDao: LearningDao): Int {
        if (lesson.lectureText.isBlank()) return 0
        val raw = Ai.generate(
            settings = settings,
            prompt = "From this lesson, write 10 flashcards testing real understanding, not trivia.\n" +
                "Return ONLY a JSON array: [{\"front\":\"...\",\"back\":\"...\"}]\n\n" +
                lesson.lectureText.take(12000),
            system = "You return only valid JSON when asked to.",
        )
        val array = org.json.JSONArray(Ai.cleanJson(raw))
        for (i in 0 until array.length()) {
            val card = array.getJSONObject(i)
            learningDao.insertCard(
                Flashcard(
                    trackId = trackId,
                    front = card.optString("front"),
                    back = card.optString("back")
                )
            )
        }
        return array.length()
    }
}

// ---------- view model ----------

class CourseViewModel(
    private val repository: CourseRepository,
    private val learningRepository: LearningRepository
) : ViewModel() {

    val courses: StateFlow<List<Course>> = repository.courses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todaysLessons: StateFlow<List<Lesson>> = repository.todaysLessons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _openCourseId = MutableStateFlow<Long?>(null)
    val openCourseId: StateFlow<Long?> = _openCourseId.asStateFlow()

    fun lessons(courseId: Long) = repository.lessons(courseId)

    fun open(courseId: Long?) { _openCourseId.value = courseId }

    fun create(subject: String, level: String, hours: String, weeks: String, goal: String) {
        if (subject.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                val id = repository.generateCourse(
                    subject = subject.trim(),
                    level = level,
                    weeklyHours = hours.toIntOrNull() ?: 5,
                    weeks = weeks.toIntOrNull() ?: 12,
                    goal = goal
                )
                _openCourseId.value = id
            } catch (e: Ai.MissingKeyException) {
                _error.value = "Add an AI key first — Settings › API keys."
            } catch (e: Exception) {
                _error.value = "Could not build the course: ${com.nizam.app.ui.components.Feedback.friendly(e)}"
            }
            _busy.value = false
        }
    }

    fun writeLecture(lesson: Lesson, courseTitle: String, level: String, onDone: (Lesson) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                onDone(repository.generateLecture(lesson, courseTitle, level))
            } catch (e: Ai.MissingKeyException) {
                _error.value = "Add an AI key first — Settings › API keys."
            } catch (e: Exception) {
                _error.value = "Could not write the lecture: ${com.nizam.app.ui.components.Feedback.friendly(e)}"
            }
            _busy.value = false
        }
    }

    fun toggle(lesson: Lesson) = viewModelScope.launch { repository.toggleComplete(lesson) }

    fun deleteCourse(course: Course) = viewModelScope.launch {
        repository.deleteCourse(course)
        _openCourseId.value = null
    }
}

// ---------- screens ----------

@Composable
fun CoursesScreen(container: AppContainer) {
    val vm: CourseViewModel = viewModel(
        factory = vmFactory { CourseViewModel(container.courseRepository, container.learningRepository) }
    )
    val courses by vm.courses.collectAsState()
    val todayLessons by vm.todaysLessons.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val openId by vm.openCourseId.collectAsState()

    val open = courses.firstOrNull { it.id == openId }
    if (open != null) {
        CourseDetail(vm = vm, container = container, course = open, onBack = { vm.open(null) })
        return
    }

    var subject by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("Beginner") }
    var hours by remember { mutableStateOf("6") }
    var weeks by remember { mutableStateOf("12") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Courses", "AI builds the syllabus, schedule and every lecture")

        if (todayLessons.isNotEmpty()) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Today's study plan", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    todayLessons.forEach {
                        Text(
                            "${if (it.completedAt != null) "✓ " else "• "}${it.title} · ${it.estimatedMinutes} min",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        OutlinedTextField(
            value = subject, onValueChange = { subject = it },
            label = { Text("Subject to master") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = goal, onValueChange = { goal = it },
            label = { Text("Your goal (optional)") }, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Beginner", "Intermediate", "Advanced").forEach {
                FilterChip(selected = level == it, onClick = { level = it }, label = { Text(it) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = hours, onValueChange = { hours = it },
                label = { Text("Hours / week") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = weeks, onValueChange = { weeks = it },
                label = { Text("Weeks") }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(
            enabled = !busy && subject.isNotBlank(),
            onClick = { vm.create(subject, level, hours, weeks, goal) }
        ) { Text(if (busy) "Designing…" else "Build my course") }

        if (busy) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator()
            Text("Designing the full syllabus — this takes 20 to 60 seconds.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        if (courses.isEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                "Or build yourself instead of a subject",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Same engine, pointed at how you work rather than what you know.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            container.selfRepository.growthTopics.forEach { topic ->
                Text(
                    topic,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { subject = topic }
                        .padding(vertical = 8.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(8.dp))

        courses.forEach { course ->
            Column(
                Modifier.fillMaxWidth().clickable { vm.open(course.id) }.padding(vertical = 12.dp)
            ) {
                Text(course.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${course.level} · ${course.weeklyHours}h/week · ${course.weeks} weeks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun CourseDetail(
    vm: CourseViewModel,
    container: AppContainer,
    course: Course,
    onBack: () -> Unit
) {
    val lessons by vm.lessons(course.id).collectAsState(initial = emptyList())
    var openLesson by remember { mutableStateOf<Lesson?>(null) }

    val current = openLesson
    if (current != null) {
        val fresh = lessons.firstOrNull { it.id == current.id } ?: current
        LessonScreen(
            vm = vm,
            container = container,
            course = course,
            lesson = fresh,
            onBack = { openLesson = null }
        )
        return
    }

    val done = lessons.count { it.completedAt != null }
    val byUnit = lessons.groupBy { it.unitTitle }
    var confirmDelete by remember(course.id) { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to courses")
            }
            Text(course.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Text(course.overview, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text("$done / ${lessons.size} lessons done", style = MonoNumber)
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (lessons.isEmpty()) 0f else done.toFloat() / lessons.size },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        if (!confirmDelete) {
            Text(
                "delete this course",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(role = Role.Button) { confirmDelete = true }
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.deleteCourse(course); onBack() }) { Text("Delete everything") }
                OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        }
        Spacer(Modifier.height(12.dp))
            }
        }

            byUnit.forEach { (unit, unitLessons) ->
                item {
                    Spacer(Modifier.height(10.dp))
                    Text(unit, style = MaterialTheme.typography.titleMedium)
                }
                items(unitLessons, key = { it.id }) { lesson ->
                    Row(
                        Modifier.fillMaxWidth().clickable { openLesson = lesson }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = lesson.completedAt != null, onCheckedChange = { vm.toggle(lesson) })
                        Column(Modifier.weight(1f)) {
                            Text(lesson.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${prettyDate(lesson.scheduledDate)} · ${lesson.estimatedMinutes} min" +
                                    if (lesson.lectureText.isBlank()) "" else " · lecture ready",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun LessonScreen(
    vm: CourseViewModel,
    container: AppContainer,
    course: Course,
    lesson: Lesson,
    onBack: () -> Unit
) {
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var text by remember(lesson.id) { mutableStateOf(lesson.lectureText) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to syllabus")
            }
            Text(lesson.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Text(lesson.objectives, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))

        if (text.isBlank()) {
            Button(
                enabled = !busy,
                onClick = { vm.writeLecture(lesson, course.title, course.level) { text = it.lectureText } }
            ) { Text(if (busy) "Writing…" else "Write this lecture") }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                CircularProgressIndicator()
                Text(
                    "Writing the full lecture — this is a long generation, give it a minute.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val tones = Tones.forKey(lesson.title)
            if (lesson.pull.isNotBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = tones.tone.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp)) {
                        Text(
                            glyphFor(lesson.title),
                            style = MaterialTheme.typography.titleLarge,
                            color = tones.accent
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(lesson.pull, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            BlockList(
                blocks = remember(text) { BlockParser.parse(text) },
                tone = tones.tone,
                accent = tones.accent
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.toggle(lesson) }) {
                    Text(if (lesson.completedAt == null) "Mark done" else "Mark not done")
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { vm.writeLecture(lesson, course.title, course.level) { text = it.lectureText } }
                ) { Text("Rewrite") }
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        if (text.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            com.nizam.app.core.AiPanel(
                settings = container.settingsRepository,
                label = "Ask about this lesson",
                contextPrompt = "The learner is studying \"${lesson.title}\" from the course " +
                    "\"${course.title}\". Here is the lesson material:\n\n${text.take(12000)}",
                starterQuestion = "Explain the hardest part of this lesson again, more simply."
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
