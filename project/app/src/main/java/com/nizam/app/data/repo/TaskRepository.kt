package com.nizam.app.data.repo

import com.nizam.app.data.db.Habit
import com.nizam.app.data.db.HabitDao
import com.nizam.app.data.db.HabitEntry
import com.nizam.app.data.db.Task
import com.nizam.app.data.db.TaskDao
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val dao: TaskDao) {
    fun all(): Flow<List<Task>> = dao.observeAll()
    fun dueBetween(start: Long, end: Long): Flow<List<Task>> = dao.observeDueBetween(start, end)
    fun openThrough(end: Long): Flow<List<Task>> = dao.observeOpenThrough(end)

    /** Saves an edited task (planner times, reflowed dates). */
    suspend fun save(task: Task) = dao.update(task)

    suspend fun add(title: String, due: Long?, priority: Int) {
        dao.insert(Task(title = title.trim(), due = due, priority = priority))
        com.nizam.app.core.spine.Pulse.emit("tasks", "added", title.trim())
    }

    suspend fun toggleComplete(task: Task) {
        val nowDone = task.completedAt == null
        dao.update(
            task.copy(
                completedAt = if (nowDone) System.currentTimeMillis() else null,
                updatedAt = System.currentTimeMillis()
            )
        )
        if (nowDone) com.nizam.app.core.spine.Pulse.emit(
            "tasks", "done", task.title,
            org.json.JSONObject().put("title", task.title).put("priority", task.priority)
        )
    }

    suspend fun softDelete(task: Task) {
        dao.update(task.copy(deletedAt = System.currentTimeMillis()))
    }

    suspend fun hardDelete(task: Task) = dao.deleteById(task.id)

    suspend fun rename(task: Task, title: String) =
        dao.update(task.copy(title = title.trim(), updatedAt = System.currentTimeMillis()))

    suspend fun restore(task: Task) {
        dao.update(task.copy(deletedAt = null))
    }
}

class HabitRepository(private val dao: HabitDao) {
    fun habits(): Flow<List<Habit>> = dao.observeHabits()
    fun entriesFor(localDate: String): Flow<List<HabitEntry>> = dao.observeEntriesForDay(localDate)
    fun entriesSince(from: String): Flow<List<HabitEntry>> = dao.observeSince(from)

    suspend fun addHabit(name: String, targetPerWeek: Int) {
        dao.insertHabit(Habit(name = name.trim(), targetPerWeek = targetPerWeek))
    }

    suspend fun toggle(habitId: Long, localDate: String, currentlyDone: Boolean) {
        if (currentlyDone) {
            dao.clearEntry(habitId, localDate)
        } else {
            dao.insertEntry(HabitEntry(habitId = habitId, localDate = localDate))
            com.nizam.app.core.spine.Pulse.emit(
                "habits", "kept", "habit kept",
                org.json.JSONObject().put("habitId", habitId).put("date", localDate)
            )
        }
    }

    suspend fun archive(habit: Habit) {
        dao.updateHabit(habit.copy(archived = true))
    }
}
