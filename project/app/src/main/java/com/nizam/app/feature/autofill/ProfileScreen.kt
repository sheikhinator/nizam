package com.nizam.app.feature.autofill

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
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
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space

/** The details Atlas fills into forms, and the switch to make Atlas your autofill provider. */
@Composable
fun AutofillProfileScreen() {
    val ctx = LocalContext.current
    AtlasScreen(title = "Autofill", subtitle = "Fill forms in any app from these details. They never leave your phone.") {
        item("enable") {
            ListRow("Make Atlas your autofill service", supporting = "Android asks once. Passwords are never touched.",
                icon = Icons.Outlined.EditNote) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${ctx.packageName}"))) }
            }
        }
        item("h") { SectionHeader("Your details") }
        Profile.FIELDS.forEach { (k, label) ->
            item(k) {
                var v by remember { mutableStateOf(Profile.get(ctx, k)) }
                OutlinedTextField(v, { v = it; Profile.set(ctx, k, it) }, Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.xs),
                    label = { Text(label) }, singleLine = true)
            }
        }
    }
}
