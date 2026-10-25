package com.nizam.app.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nizam.app.data.db.Habit
import com.nizam.app.data.db.HabitEntry
import com.nizam.app.data.db.Task
import com.nizam.app.data.repo.HabitRepository
import com.nizam.app.data.repo.TaskRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class TasksViewModel(
    private val taskRepository: TaskRepository,
    private val habitRepository: HabitRepository
) : ViewModel() {

    val today: String = LocalDate.now().toString()

    val tasks: StateFlow<List<Task>> = taskRepository.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val habits: StateFlow<List<Habit>> = habitRepository.habits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todayEntries: StateFlow<List<HabitEntry>> = habitRepository.entriesFor(today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTask(title: String) {
        if (title.isBlank()) return
        val p = TaskParse.parse(title)
        viewModelScope.launch {
            taskRepository.insert(Task(title = p.title, due = p.due(), priority = p.priority, recurrenceRule = p.rule, estimateMin = p.estimate))
        }
    }

    fun addPlain(title: String, due: Long? = null) {
        if (title.isBlank()) return
        viewModelScope.launch { taskRepository.add(title, due, 1) }
    }

    fun save(task: Task) { viewModelScope.launch { taskRepository.save(task.copy(updatedAt = System.currentTimeMillis())) } }

    fun toggleTask(task: Task) {
        val lastOpen = task.completedAt == null && tasks.value.count { it.completedAt == null } == 1
        val finishing = task.completedAt == null
        viewModelScope.launch {
            taskRepository.toggleComplete(task)
            // a repeating task brings its next one the moment you tick it
            if (finishing) TaskSmart.next(task)?.let { taskRepository.insert(it) }
        }
        if (lastOpen && task.recurrenceRule == null) com.nizam.app.ui.design.Celebrate.now()      // cleared the whole list
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch { taskRepository.softDelete(task) }
        com.nizam.app.ui.components.Feedback.undo("Task deleted") { viewModelScope.launch { taskRepository.restore(task) } }
    }

    fun addHabit(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { habitRepository.addHabit(name, 7) }
    }

    fun toggleHabit(habitId: Long, done: Boolean) {
        // the date at the moment of the tap — a screen left open past midnight must not tick yesterday
        viewModelScope.launch { habitRepository.toggle(habitId, LocalDate.now().toString(), done) }
    }

    companion object {
        fun factory(
            taskRepository: TaskRepository,
            habitRepository: HabitRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TasksViewModel(taskRepository, habitRepository) as T
            }
        }
    }
}
