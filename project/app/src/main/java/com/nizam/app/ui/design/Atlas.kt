package com.nizam.app.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.History
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot

/*
 * Atlas's design language, in one file. Minimal and quiet: soft filled surfaces instead of outlines,
 * one accent colour, space instead of dividers, and a light spring when something is pressed.
 * Screens use these through the same names they always did, so the whole app changes at once.
 */

/**
 * A soft, filled text field — no outline, no underline. Drop-in for Material's OutlinedTextField.
 *
 * It also suggests as you type, like a search engine: what you've entered in this same box before,
 * then anything you've typed elsewhere in Atlas, and — in search boxes — live web suggestions.
 * Never for passwords or numbers.
 */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1
) {
    val c = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val key = androidx.compose.runtime.currentCompositeKeyHash.toString()
    val kt = keyboardOptions.keyboardType
    val suggests = enabled && !readOnly && visualTransformation == VisualTransformation.None &&
        kt != androidx.compose.ui.text.input.KeyboardType.Password && kt != androidx.compose.ui.text.input.KeyboardType.NumberPassword &&
        kt != androidx.compose.ui.text.input.KeyboardType.Number && kt != androidx.compose.ui.text.input.KeyboardType.Phone &&
        kt != androidx.compose.ui.text.input.KeyboardType.Decimal
    val isSearch = keyboardOptions.imeAction == androidx.compose.ui.text.input.ImeAction.Search
    var focused by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<List<String>>(emptyList()) }
    val typing = value.trim()
    if (suggests && isSearch) LaunchedEffect(typing, focused) { com.nizam.app.ui.design.guard {
        web = emptyList()
        if (focused && typing.length >= 2) { kotlinx.coroutines.delay(250); web = TypingMemory.web(typing) }
    } }
    val options = if (!suggests || !focused || typing.isEmpty() || typing.length > 80 || picked == value) emptyList()
        else (TypingMemory.suggest(ctx, key, typing) + web).distinctBy { it.lowercase() }.filter { !it.equals(typing, true) }.take(6)
    fun keep() { if (suggests) TypingMemory.keep(ctx, key, value) }
    val actions = KeyboardActions(
        onDone = { keep(); keyboardActions.onDone?.invoke(this) ?: defaultKeyboardAction(androidx.compose.ui.text.input.ImeAction.Done) },
        onGo = { keep(); keyboardActions.onGo?.invoke(this) ?: defaultKeyboardAction(androidx.compose.ui.text.input.ImeAction.Go) },
        onNext = { keep(); keyboardActions.onNext?.invoke(this) ?: defaultKeyboardAction(androidx.compose.ui.text.input.ImeAction.Next) },
        onPrevious = { keyboardActions.onPrevious?.invoke(this) ?: defaultKeyboardAction(androidx.compose.ui.text.input.ImeAction.Previous) },
        onSearch = { keep(); keyboardActions.onSearch?.invoke(this) ?: defaultKeyboardAction(androidx.compose.ui.text.input.ImeAction.Search) },
        onSend = { keep(); keyboardActions.onSend?.invoke(this) ?: defaultKeyboardAction(androidx.compose.ui.text.input.ImeAction.Send) }
    )
    Box(modifier, propagateMinConstraints = true) {
        TextField(
            value = value, onValueChange = { picked = null; onValueChange(it) },
            modifier = Modifier.padding(vertical = 4.dp).onFocusChanged { f ->
                if (focused && !f.isFocused) keep()
                focused = f.isFocused
            },
            enabled = enabled, readOnly = readOnly, textStyle = textStyle,
            label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
            isError = isError, visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = actions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines,
            shape = MaterialTheme.shapes.small,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.surfaceVariant, unfocusedContainerColor = c.surfaceVariant,
                disabledContainerColor = c.surfaceVariant.copy(alpha = 0.5f), errorContainerColor = c.errorContainer,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
                focusedLabelColor = c.primary, unfocusedLabelColor = c.onSurfaceVariant,
                focusedPlaceholderColor = c.onSurfaceVariant.copy(alpha = 0.7f), unfocusedPlaceholderColor = c.onSurfaceVariant.copy(alpha = 0.7f),
                cursorColor = c.primary
            )
        )
        androidx.compose.material3.DropdownMenu(
            expanded = options.isNotEmpty(), onDismissRequest = { picked = value },
            properties = androidx.compose.ui.window.PopupProperties(focusable = false)
        ) {
            options.forEach { s ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(s, maxLines = 1, style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { androidx.compose.material3.Icon(
                        if (s in web) androidx.compose.material.icons.Icons.Outlined.Search else androidx.compose.material.icons.Icons.Outlined.History,
                        null, tint = c.onSurfaceVariant) },
                    onClick = { picked = s; onValueChange(s); TypingMemory.keep(ctx, key, s) }
                )
            }
        }
    }
}

/** What you've typed, remembered per text box and across Atlas, for suggestions. Stays on the phone. */
object TypingMemory {
    private fun p(c: android.content.Context) = c.getSharedPreferences("typing_memory", 0)
    private fun list(c: android.content.Context, k: String) = p(c).getString(k, "").orEmpty().split("\u0001").filter { it.isNotBlank() }

    fun keep(c: android.content.Context, field: String, value: String) {
        val v = value.trim()
        if (v.length < 2 || v.length > 120 || v.contains('\n')) return
        fun add(k: String, max: Int) = p(c).edit().putString(k, (listOf(v) + list(c, k).filter { !it.equals(v, true) }).take(max).joinToString("\u0001")).apply()
        add("f:$field", 30); add("all", 300)
    }

    fun suggest(c: android.content.Context, field: String, typed: String): List<String> {
        val here = list(c, "f:$field"); val all = list(c, "all")
        return (here.filter { it.startsWith(typed, true) } + all.filter { it.startsWith(typed, true) } +
            here.filter { it.contains(typed, true) } + all.filter { typed.length >= 3 && it.contains(typed, true) })
    }

    /** Google's public autocomplete — the same suggestions its search box shows. */
    suspend fun web(q: String): List<String> = runCatching {
        val arr = org.json.JSONArray(com.nizam.app.net.Http.getJson(
            "https://suggestqueries.google.com/complete/search?client=firefox&q=" + java.net.URLEncoder.encode(q, "UTF-8")))
        arr.optJSONArray(1)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().take(5)
    }.getOrElse { emptyList() }
}

/**
 * Every button answers a tap: a light tick, a small press, and a check mark for a moment — so a tap
 * that saves quietly still shows it happened. Drop-in for Material's Button.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = ButtonDefaults.shape,
    colors: androidx.compose.material3.ButtonColors = ButtonDefaults.buttonColors(),
    elevation: androidx.compose.material3.ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: androidx.compose.foundation.BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit
) {
    var ack by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "press")
    LaunchedEffect(ack) { com.nizam.app.ui.design.guard { if (ack) { kotlinx.coroutines.delay(900); ack = false } } }
    androidx.compose.material3.Button(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); ack = true; onClick() },
        modifier = modifier.scale(scale), enabled = enabled, shape = shape, colors = colors, elevation = elevation,
        border = border, contentPadding = contentPadding, interactionSource = interactionSource
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(Modifier.alpha(if (ack) 0f else 1f), verticalAlignment = Alignment.CenterVertically, content = content)
            if (ack) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, "Done",
                Modifier.height(18.dp).width(18.dp))
        }
    }
}

/** The secondary button: a soft tint of the accent, no border. Drop-in for Material's OutlinedButton. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val c = MaterialTheme.colorScheme
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.primaryContainer, contentColor = c.primary,
            disabledContainerColor = c.surfaceVariant, disabledContentColor = c.onSurfaceVariant
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        content = content
    )
}

/** Pressed things shrink a touch and spring back, with a light tick — feedback without decoration. */
fun Modifier.pressable(enabled: Boolean = true, haptic: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "press")
    val feel = LocalHapticFeedback.current
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val screen = androidx.compose.ui.platform.LocalConfiguration.current
    val dens = androidx.compose.ui.platform.LocalDensity.current
    this.onGloballyPositioned { c -> val b = c.boundsInRoot(); origin = b.center }
        .scale(s).clickable(interactionSource = source, indication = null, enabled = enabled, role = role) {
        if (haptic) feel.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        UiSound.tick()
        with(dens) { LastTap.x = (origin.x / screen.screenWidthDp.dp.toPx()).coerceIn(0f, 1f); LastTap.y = (origin.y / screen.screenHeightDp.dp.toPx()).coerceIn(0f, 1f) }
        LastTap.at = System.currentTimeMillis()
        onClick()
    }
}

/** A quiet grouped block: rounded, a whisper lighter than the page, no border. */
@Composable
fun Group(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().styledCard(Radius.card).padding(padding),
        content = content
    )
}

/** Small, calm section label. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier) = Text(
    text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(top = 20.dp, bottom = 8.dp)
)

/**
 * A segmented control: the options sit in one soft track and a raised thumb slides to the chosen
 * one. Used for up to four short options; longer lists become a scrolling row of capsules.
 */
@Composable
fun Segmented(options: List<String>, selected: String?, onSelect: (String) -> Unit, labels: (String) -> String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(vertical = 6.dp).height(38.dp)
            .clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant).padding(3.dp)
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * index, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "thumb")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(10.dp))
            .background(if (selected in options) c.surface else Color.Transparent))
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { o ->
                val on = o == selected
                val tint by animateColorAsState(if (on) c.onSurface else c.onSurfaceVariant, label = "seg")
                Box(Modifier.weight(1f).fillMaxHeight().pressable(haptic = true, role = Role.Tab) { onSelect(o) },
                    contentAlignment = Alignment.Center) {
                    Text(labels(o), style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold
                        else androidx.compose.ui.text.font.FontWeight.Medium), color = tint, maxLines = 1)
                }
            }
        }
    }
}

/** A capsule: filled when chosen, a soft tint when not. */
@Composable
fun Capsule(text: String, on: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (on) c.onSurface else c.surfaceVariant, label = "cap")
    val fg by animateColorAsState(if (on) c.background else c.onSurface, label = "capText")
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).pressable(role = Role.Tab, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = fg, maxLines = 1)
    }
}

/**
 * A scope for work that must finish even if you leave the screen — an AI answer, a plan, a verdict.
 * Unlike rememberCoroutineScope, leaving or redrawing the screen doesn't cancel it mid-flight.
 */
@Composable
fun rememberWorkScope(): kotlinx.coroutines.CoroutineScope =
    remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob() +
        com.nizam.app.feature.safe.CoroutineSafetyNet.inScope) }

/**
 * Like rememberCoroutineScope (cancelled when the screen leaves, animations work), but a failed job — a lost
 * connection, a bad file — is caught, logged and said in one line instead of closing Atlas.
 */
@Composable
fun rememberSafeScope(): kotlinx.coroutines.CoroutineScope {
    val base = androidx.compose.runtime.rememberCoroutineScope()
    return remember(base) {
        kotlinx.coroutines.CoroutineScope(base.coroutineContext + kotlinx.coroutines.SupervisorJob(base.coroutineContext[kotlinx.coroutines.Job]) +
            com.nizam.app.feature.safe.CoroutineSafetyNet.inScope)
    }
}

/** One-tap audio (a tool's reply, a recitation): one player at a time, released when it finishes. Files or URLs. */
object QuickAudio {
    private var player: android.media.MediaPlayer? = null
    fun play(path: String) {
        stop()
        player = runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { mp -> mp.release(); if (player === mp) player = null }
                // async: a streamed recitation must not freeze the screen while it buffers
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }.getOrNull()
    }
    fun stop() { runCatching { player?.release() }; player = null }
}

/**
 * Runs a screen's start-up work (inside LaunchedEffect) so a failure — no internet, a corrupt file, a missing
 * key — is caught, logged and said in one line instead of closing Atlas. Cancellation passes through as normal.
 */
inline fun guard(block: () -> Unit) {
    try { block() }
    catch (c: kotlinx.coroutines.CancellationException) { throw c }
    catch (e: Exception) { com.nizam.app.feature.safe.CoroutineSafetyNet.report(e) }
}
