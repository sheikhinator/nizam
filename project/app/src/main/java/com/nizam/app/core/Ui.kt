package com.nizam.app.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoNumber

/**
 * One header for every screen: a glyph rail, a serif title and a quiet subtitle. Using this
 * everywhere is what makes the app feel like one thing rather than twenty screens.
 */
@Composable
fun ModuleTitle(title: String, subtitle: String? = null) {
    // Every screen's header. Emoji prefixes that screens pass in are stripped: the title stands
    // alone, with generous air above and a single quiet line beneath.
    val clean = title.trimStart().dropWhile { !it.isLetterOrDigit() && it != '"' }.trim().ifBlank { title }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp)) {
        Text(clean, style = MaterialTheme.typography.displaySmall)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InlineAddRow(
    value: String,
    label: String,
    numeric: Boolean = false,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() })
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onSubmit) {
            Icon(Icons.Filled.Add, contentDescription = label)
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MonoNumber)
    }
}
