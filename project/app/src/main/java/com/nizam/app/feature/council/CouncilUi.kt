package com.nizam.app.feature.council

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoFamily

/** Each advisor is drawn pixel by pixel from its sprite — no image assets, and crisp at any size. */
@Composable
fun PixelAvatar(advisor: Advisor, size: Dp = 36.dp, glow: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .background(advisor.colour.copy(alpha = if (glow) 0.28f else 0.14f), RoundedCornerShape(10.dp))
            .border(1.dp, advisor.colour.copy(alpha = if (glow) 0.9f else 0.35f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size * 0.72f)) {
            val rows = advisor.sprite.size
            val cols = advisor.sprite.maxOf { it.length }
            val cell = minOf(this.size.width / cols, this.size.height / rows)
            val offX = (this.size.width - cell * cols) / 2
            val offY = (this.size.height - cell * rows) / 2
            advisor.sprite.forEachIndexed { r, line ->
                line.forEachIndexed { c, ch ->
                    val colour = advisor.palette[ch] ?: return@forEachIndexed
                    // +0.5 overlaps neighbours slightly so no hairline gaps appear between pixels
                    drawRect(
                        colour,
                        topLeft = Offset(offX + c * cell, offY + r * cell),
                        size = Size(cell + 0.5f, cell + 0.5f)
                    )
                }
            }
        }
    }
}

/** Three dots pulsing in sequence — the "someone is typing" signal everyone recognises. */
@Composable
fun TypingDots(colour: Color) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 480, delayMillis = i * 160),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Box(Modifier.size(7.dp).alpha(a).background(colour, CircleShape))
        }
    }
}

@Composable
fun TypingBubble(advisor: Advisor, verb: String = "is thinking") {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        PixelAvatar(advisor, 34.dp, glow = true)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "${advisor.name} $verb",
                style = MaterialTheme.typography.labelMedium,
                color = advisor.colour
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) { TypingDots(advisor.colour) }
        }
    }
}

@Composable
fun StancePill(stance: String) {
    if (stance.isBlank()) return
    val (label, colour) = when (stance) {
        "FOR" -> "FOR" to Color(0xFF4FC3A1)
        "AGAINST" -> "AGAINST" to Color(0xFFE06A4C)
        else -> "MIXED" to Color(0xFFE0B14A)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
        color = colour,
        modifier = Modifier
            .border(1.dp, colour.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                )
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun AdvisorBubble(message: CouncilMessage, onMention: (Advisor) -> Unit) {
    val advisor = Council.byId(message.speaker) ?: Council.ARBITER
    val replyTo = Council.byId(message.respondsTo)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.clickable { onMention(advisor) }) { PixelAvatar(advisor, 34.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    advisor.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = advisor.colour
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    advisor.title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                StancePill(message.stance)
            }
            if (replyTo != null && replyTo.id != advisor.id) {
                Text(
                    "— answering ${replyTo.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = replyTo.colour.copy(alpha = 0.85f)
                )
            }
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .border(
                        1.dp, advisor.colour.copy(alpha = 0.18f),
                        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun VerdictCard(
    message: CouncilMessage,
    onAction: (ProposedAction) -> Unit,
    doneActions: Set<String>
) {
    val arbiter = Council.ARBITER
    val confidence = when (message.confidence) { "HIGH" -> 3; "MEDIUM" -> 2; "LOW" -> 1; else -> 0 }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .border(1.dp, arbiter.colour.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelAvatar(arbiter, 30.dp, glow = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "THE ARBITER · VERDICT",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                    color = arbiter.colour
                )
                if (confidence > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(3) { i ->
                            Box(
                                Modifier
                                    .padding(end = 3.dp, top = 4.dp)
                                    .size(width = 18.dp, height = 4.dp)
                                    .background(
                                        if (i < confidence) arbiter.colour
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${message.confidence.lowercase()} confidence",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            message.text,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFamily)
        )

        if (message.dissent.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE06A4C).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text("UNRESOLVED DISSENT", style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFE06A4C))
                Spacer(Modifier.height(4.dp))
                Text(message.dissent, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (message.nextSteps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("NEXT", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            message.nextSteps.forEachIndexed { i, step ->
                Row(Modifier.padding(top = 6.dp)) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                        color = arbiter.colour,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        ActionRow(message.actions, onAction, doneActions)
    }
}

@Composable
fun ActionRow(actions: List<ProposedAction>, onAction: (ProposedAction) -> Unit, done: Set<String>) {
    if (actions.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    actions.forEach { a ->
        val key = a.action + a.args.toString()
        val isDone = key in done
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(
                    if (isDone) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    RoundedCornerShape(12.dp)
                )
                .clickable(enabled = !isDone) { onAction(a) }
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Text(
                (if (isDone) "✓  " else "＋  ") + a.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}
