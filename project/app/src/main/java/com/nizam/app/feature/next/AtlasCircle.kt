package com.nizam.app.feature.next

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.Segmented
import com.nizam.app.ui.design.Space
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

@Composable
fun AtlasCircleScreen() {
    val context = LocalContext.current
    val circleStore = remember(context) { JsonStore(context, "atlas_circle_groups", sealed = true) }
    val itemStore = remember(context) { JsonStore(context, "atlas_circle_items", sealed = true) }
    val profileStore = remember(context) { JsonStore(context, "atlas_circle_profile", sealed = true) }

    var circles by remember { mutableStateOf(circleStore.all()) }
    var activities by remember { mutableStateOf(itemStore.all()) }
    var selectedCircleId by remember { mutableStateOf(circles.firstOrNull()?.optString("id").orEmpty()) }
    var displayName by remember {
        mutableStateOf(profileStore.all().firstOrNull { it.optString("type") == "profile" }?.optString("name").orEmpty().ifBlank { "Me" })
    }
    var displayNameDraft by remember { mutableStateOf(displayName) }
    var newCircleName by remember { mutableStateOf("") }
    var newItemTitle by remember { mutableStateOf("") }
    var newItemSchedule by remember { mutableStateOf("") }
    var newItemKind by remember { mutableStateOf("goal") }
    var importCode by remember { mutableStateOf("") }
    var importPreview by remember { mutableStateOf<CircleBundle?>(null) }

    fun reload() {
        circles = circleStore.all()
        activities = itemStore.all()
    }

    val selectedCircle = circles.firstOrNull { it.optString("id") == selectedCircleId } ?: circles.firstOrNull()
    val selectedItems = activities.filter { it.optString("circleId") == selectedCircle?.optString("id") }
        .sortedByDescending { it.optLong("createdAt") }
    val sameNamedCircle = circles.firstOrNull {
        it.optString("name").trim().equals(newCircleName.trim(), ignoreCase = true)
    }
    val today = LocalDate.now().toString()

    AtlasScreen(
        title = "Atlas Circle",
        subtitle = "A few trusted people. Share only what you choose.",
        hub = Hub.Home
    ) {
        item("circle-privacy") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text(
                    "Circles and check-ins stay encrypted on this phone. Nothing syncs automatically; imports require review. Each share contains one goal or ritual and up to 30 recent check-ins with their names—never your contact list, other Circle items, or Atlas history. Anyone who receives a code can forward it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item("circle-profile") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Name on shared check-ins", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This name stays on this phone unless you share an item that includes your check-ins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    displayNameDraft,
                    { displayNameDraft = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your Circle name") },
                    singleLine = true
                )
                Button(
                    onClick = {
                        val clean = displayNameDraft.filterNot { Character.isISOControl(it) }.trim().take(40)
                        if (clean.isNotBlank()) {
                            displayName = clean
                            profileStore.save(
                                profileStore.all().filterNot { it.optString("type") == "profile" } +
                                    JSONObject().put("type", "profile").put("name", clean)
                            )
                        }
                    },
                    enabled = displayNameDraft.isNotBlank()
                ) { Text("Save name") }
            }
        }

        item("circle-create") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Your circles", style = MaterialTheme.typography.titleMedium)
                if (circles.isEmpty()) {
                    Text(
                        "Create a local circle, then add only the goals or rituals you want to share.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                circles.forEach { circle ->
                    val active = circle.optString("id") == selectedCircle?.optString("id")
                    val duplicateName = circles.count {
                        it.optString("name").equals(circle.optString("name"), ignoreCase = true)
                    } > 1
                    OutlinedButton(
                        onClick = { selectedCircleId = circle.optString("id") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${if (active) "✓ " else ""}${circle.optString("name", "Untitled circle")}" +
                                (if (duplicateName) " · ${circle.optString("shareKey").takeLast(4)}" else "")
                        )
                    }
                }
                OutlinedTextField(
                    newCircleName,
                    { newCircleName = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New circle name") },
                    singleLine = true
                )
                if (sameNamedCircle != null && newCircleName.isNotBlank()) {
                    Text("A circle with this name already exists on this phone.", style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = {
                        val name = newCircleName.filterNot { Character.isISOControl(it) }.trim().take(60)
                        if (name.isNotBlank() && sameNamedCircle == null) {
                            val created = circleStore.add(
                                JSONObject().put("name", name).put("shareKey", UUID.randomUUID().toString())
                                    .put("createdAt", System.currentTimeMillis())
                            )
                            selectedCircleId = created.optString("id")
                            newCircleName = ""
                            reload()
                        }
                    },
                    enabled = newCircleName.isNotBlank() && sameNamedCircle == null
                ) { Text("Create circle") }
            }
        }

        if (selectedCircle != null) {
            item("circle-add-item") {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                    Text("Add to ${selectedCircle.optString("name")}", style = MaterialTheme.typography.titleMedium)
                    Segmented(
                        listOf("goal", "ritual"),
                        newItemKind,
                        { newItemKind = it },
                        { if (it == "goal") "Goal" else "Ritual" }
                    )
                    OutlinedTextField(
                        newItemTitle,
                        { newItemTitle = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (newItemKind == "goal") "Shared goal" else "Shared ritual") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        newItemSchedule,
                        { newItemSchedule = it.take(100) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Target or cadence (optional)") },
                        placeholder = { Text(if (newItemKind == "ritual") "Every Friday" else "Finish by June") },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val title = newItemTitle.filterNot { Character.isISOControl(it) }.trim().take(120)
                            if (title.isNotBlank()) {
                                val shareKey = UUID.randomUUID().toString()
                                itemStore.add(
                                    JSONObject()
                                        .put("shareKey", shareKey)
                                        .put("circleId", selectedCircle.optString("id"))
                                        .put("kind", newItemKind)
                                        .put("title", title)
                                        .put("schedule", newItemSchedule.filterNot { Character.isISOControl(it) }.trim().take(100))
                                        .put("createdAt", System.currentTimeMillis())
                                        .put("checkIns", JSONArray())
                                )
                                newItemTitle = ""
                                newItemSchedule = ""
                                reload()
                            }
                        },
                        enabled = newItemTitle.isNotBlank()
                    ) { Text("Add selected item") }
                }
            }

            if (selectedItems.isEmpty()) {
                item("circle-empty-items") {
                    Text(
                        "No goals or rituals in this circle yet.",
                        modifier = Modifier.padding(horizontal = Space.page, vertical = Space.s),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            selectedItems.forEach { activity ->
                item("circle-item-${activity.optString("id")}") {
                    val checkIns = activity.optJSONArray("checkIns") ?: JSONArray()
                    val memberKey = selectedCircle?.let { existingCircleMemberKey(profileStore, it) }.orEmpty()
                    val checkedInToday = (0 until checkIns.length()).any { index ->
                        val checkIn = checkIns.optJSONObject(index)
                        checkIn != null && memberKey.isNotBlank() &&
                            checkIn.optString("memberKey") == memberKey && checkIn.optString("date") == today
                    }
                    Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                        Text(activity.optString("title"), style = MaterialTheme.typography.titleMedium)
                        val details = listOfNotNull(
                            if (activity.optString("kind") == "ritual") "Ritual" else "Goal",
                            activity.optString("schedule").takeIf { it.isNotBlank() },
                            "${checkIns.length()} check-in${if (checkIns.length() == 1) "" else "s"}"
                        ).joinToString(" · ")
                        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(Space.s)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (!checkedInToday) {
                                        val fresh = itemStore.all().firstOrNull { it.optString("id") == activity.optString("id") }
                                        if (fresh != null) {
                                            val old = fresh.optJSONArray("checkIns") ?: JSONArray()
                                            val updated = (0 until old.length()).mapNotNull { old.optJSONObject(it) }.toMutableList()
                                            val localKey = localCircleMemberKey(profileStore, selectedCircle)
                                            val alreadyAdded = updated.any {
                                                it.optString("memberKey") == localKey && it.optString("date") == today
                                            }
                                            if (!alreadyAdded) updated.add(
                                                JSONObject().put("id", UUID.randomUUID().toString())
                                                    .put("member", displayName).put("memberKey", localKey).put("date", today)
                                            )
                                            itemStore.update(activity.optString("id")) {
                                                it.put("checkIns", JSONArray(updated.takeLast(365)))
                                            }
                                            reload()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !checkedInToday
                            ) { Text(if (checkedInToday) "Checked in today" else "Check in") }
                            OutlinedButton(
                                onClick = {
                                    val code = AtlasCircleCodec.encode(selectedCircle, activity)
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND)
                                                .setType("text/plain")
                                                .putExtra(Intent.EXTRA_TEXT, code),
                                            "Share one Circle item"
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Share this item") }
                        }
                    }
                }
            }
        }

        item("circle-import") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Import one shared item", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Paste an Atlas Circle code. Review the single item before it is added to this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    importCode,
                    {
                        importCode = it.take(AtlasCircleCodec.MAX_CODE_CHARS)
                        importPreview = AtlasCircleCodec.decode(importCode)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Atlas Circle share code") },
                    minLines = 3,
                    maxLines = 6
                )
                val incoming = importPreview
                if (incoming == null && importCode.isNotBlank()) {
                    Text(
                        "That code is incomplete or not a supported Atlas Circle item.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (incoming != null) {
                    val matchingCircle = circles.firstOrNull {
                        it.optString("shareKey").ifBlank { it.optString("id") } == incoming.circleKey
                    }
                    val sameNamedOtherCircle = matchingCircle == null && circles.any {
                        it.optString("name").equals(incoming.circleName, ignoreCase = true)
                    }
                    Text(
                        "${if (incoming.kind == "ritual") "Ritual" else "Goal"} · ${incoming.title}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Circle: ${matchingCircle?.optString("name") ?: incoming.circleName}" +
                            (incoming.schedule.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                            " · ${incoming.checkIns.size} recent check-ins" +
                            (if (sameNamedOtherCircle) " · separate circle; its share ID differs" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    incoming.checkIns.forEach { checkIn ->
                        Text(
                            "• ${checkIn.optString("member")} · ${checkIn.optString("date")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            val targetCircle = matchingCircle ?: circleStore.add(
                                JSONObject().put("name", incoming.circleName).put("shareKey", incoming.circleKey)
                                    .put("createdAt", System.currentTimeMillis())
                            )
                            val targetId = targetCircle.optString("id")
                            val existing = itemStore.all().firstOrNull {
                                it.optString("circleId") == targetId && it.optString("shareKey") == incoming.shareKey
                            }
                            if (existing == null) {
                                itemStore.add(
                                    JSONObject()
                                        .put("shareKey", incoming.shareKey)
                                        .put("circleId", targetId)
                                        .put("kind", incoming.kind)
                                        .put("title", incoming.title)
                                        .put("schedule", incoming.schedule)
                                        .put("createdAt", System.currentTimeMillis())
                                        .put("checkIns", JSONArray().apply { incoming.checkIns.forEach { put(it) } })
                                )
                            } else {
                                val current = existing.optJSONArray("checkIns") ?: JSONArray()
                                val merged = (0 until current.length()).mapNotNull { current.optJSONObject(it) }.toMutableList()
                                val knownIds = merged.mapTo(mutableSetOf()) { it.optString("id") }
                                incoming.checkIns.filter { knownIds.add(it.optString("id")) }.forEach(merged::add)
                                itemStore.update(existing.optString("id")) {
                                    it.put("checkIns", JSONArray(merged.takeLast(365)))
                                }
                            }
                            selectedCircleId = targetId
                            importCode = ""
                            importPreview = null
                            reload()
                        }
                    ) { Text("Import this item") }
                }
            }
        }
    }
}