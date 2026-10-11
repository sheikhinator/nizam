package com.nizam.app.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoNumber

/**
 * PIN gate. Shown before anything else when a PIN is set. Nothing leaves the device, so this is
 * about the person who picks up your phone, not about encryption.
 */
@Composable
fun LockScreen(correctPin: String, onUnlocked: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    fun press(digit: String) {
        if (entered.length >= 8) return
        wrong = false
        entered += digit
        if (entered.length >= correctPin.length) {
            if (entered == correctPin) onUnlocked() else {
                wrong = true
                entered = ""
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("ATLAS", style = MaterialTheme.typography.displaySmall.copy(fontFamily = DisplayFamily))
        Spacer(Modifier.height(8.dp))
        Text(
            if (wrong) "Wrong PIN" else "Enter your PIN",
            style = MaterialTheme.typography.bodyMedium,
            color = if (wrong) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(correctPin.length.coerceAtLeast(4)) { index ->
                Box(
                    Modifier
                        .size(14.dp)
                        .background(
                            if (index < entered.length) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                )
            }
        }

        Spacer(Modifier.height(36.dp))
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { char -> Key(char.toString()) { press(char.toString()) } }
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(64.dp))
            Key("0") { press("0") }
            Key("⌫") { entered = entered.dropLast(1); wrong = false }
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MonoNumber.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
            textAlign = TextAlign.Center
        )
    }
}
