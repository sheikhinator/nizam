package com.nizam.app.feature.next

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Phone
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
import com.nizam.app.feature.daily.Sos
import com.nizam.app.feature.future.Guardian
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space

private fun dialableNumber(value: String): String? {
    val number = value.filter { it.isDigit() || it == '+' }
    return number.takeIf { it.count(Char::isDigit) >= 6 }
}

private fun openDialer(context: android.content.Context, value: String) {
    val number = dialableNumber(value)
    if (number == null) {
        Feedback.show("This contact has no dialable number. Add one in Guardian or Daily › SOS.")
        return
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    }.onFailure { Feedback.show("Couldn't open the dialer.") }
}

@Composable
fun OfflineHelpCard() {
    val context = LocalContext.current
    val guardianContacts = remember(context) { Guardian.contacts(context) }
    val dailyContacts = remember(context) { Sos.contacts(context) }
    var medicalDetails by remember(context) { mutableStateOf(Sos.card(context)) }
    var localInstructions by remember(context) { mutableStateOf(Sos.instructions(context)) }
    var status by remember(context) { mutableStateOf<String?>(null) }

    Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
        Text("My offline help card", style = MaterialTheme.typography.titleMedium)
        Text(
            "Available here without internet. It stays on this phone and is visible to anyone holding it unlocked. Nothing is sent automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionHeader("Guardian emergency contacts")
        if (guardianContacts.isEmpty()) {
            Text("No Guardian contacts saved.", style = MaterialTheme.typography.bodySmall)
        } else {
            guardianContacts.forEach { contact ->
                ListRow(
                    contact.name.ifBlank { contact.phone },
                    supporting = "${contact.phone} · tap to open dialer",
                    icon = Icons.Outlined.Phone,
                    tint = MaterialTheme.colorScheme.error
                ) { openDialer(context, contact.phone) }
            }
        }

        SectionHeader("Daily SOS recipients")
        if (dailyContacts.isEmpty()) {
            Text("No Daily SOS recipients saved.", style = MaterialTheme.typography.bodySmall)
        } else {
            dailyContacts.forEach { contact ->
                ListRow(
                    contact,
                    supporting = if (dialableNumber(contact) != null) "Tap to open dialer" else "No phone number saved",
                    icon = Icons.Outlined.Phone,
                    tint = MaterialTheme.colorScheme.error
                ) { openDialer(context, contact) }
            }
        }
        Text(
            "Manage Guardian contacts in Guardian and Daily SOS recipients in Daily › SOS. The two lists remain separate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionHeader("Medical details")
        OutlinedTextField(
            value = medicalDetails,
            onValueChange = { medicalDetails = it.take(1500) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Medical card") },
            placeholder = { Text("Blood group, allergies, medicines, or other details") },
            minLines = 2
        )
        OutlinedTextField(
            value = localInstructions,
            onValueChange = { localInstructions = it.take(1500) },
            modifier = Modifier.fillMaxWidth().padding(top = Space.s),
            label = { Text("Local instructions") },
            placeholder = { Text("What should a helper nearby know?") },
            minLines = 2
        )
        Button(
            onClick = {
                Sos.setCard(context, medicalDetails.trim())
                Sos.setInstructions(context, localInstructions.trim())
                status = "Help card saved on this phone."
            },
            modifier = Modifier.fillMaxWidth().padding(top = Space.s)
        ) { Text("Save help card") }
        status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    }
}