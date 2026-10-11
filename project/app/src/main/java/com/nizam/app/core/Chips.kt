package com.nizam.app.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Chips that wrap properly.
 *
 * Manually chunking chips into fixed rows guesses at the screen width, so a long label gets
 * squeezed and breaks mid-word ("Passwor d generator"). FlowRow measures and wraps whole chips.
 */
/**
 * One control for every "pick one" row in the app. Up to four short options become a segmented
 * control; anything longer becomes a calm, horizontally scrolling row of capsules — never a wrapped
 * jumble of pills.
 */
@Composable
fun ChipRow(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    labels: (String) -> String = { it }
) {
    val short = options.size in 2..4 && options.sumOf { labels(it).length } <= 34
    if (short) {
        com.nizam.app.ui.design.Segmented(options, selected, onSelect, labels, modifier.padding(bottom = 6.dp))
    } else {
        androidx.compose.foundation.lazy.LazyRow(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(options.size) { i ->
                val option = options[i]
                com.nizam.app.ui.design.Capsule(labels(option), selected == option) { onSelect(option) }
            }
        }
    }
}

/** Same, for multi-select: capsules that wrap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiChipRow(
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            com.nizam.app.ui.design.Capsule(option, selected.contains(option)) { onToggle(option) }
        }
    }
}

/** A wrapping container for anything, not just chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrapRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) { content() }
}
