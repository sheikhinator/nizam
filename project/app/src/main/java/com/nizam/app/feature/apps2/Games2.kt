package com.nizam.app.feature.apps2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.delay
import kotlin.math.abs

object Games2 {
    val all: List<Spec> = listOf(
        ScreenSpec("games", "Games", "Every game in Atlas: 2048, snake, tic-tac-toe, chess, quiz and more", Icons.Outlined.SportsEsports, Hub.Media, "games play 2048 snake tic tac toe arcade") { _, open -> GamesHub(open) },
        ScreenSpec("g2048", "2048", "Swipe to merge tiles", Icons.Outlined.GridView, Hub.Media, "2048 puzzle") { _, _ -> Game2048() },
        ScreenSpec("snake", "Snake", "The classic, swipe to steer", Icons.Outlined.Gesture, Hub.Media, "snake arcade nokia") { _, _ -> SnakeGame() },
        ScreenSpec("tictactoe", "Tic-tac-toe", "Against a friend or an AI that never loses", Icons.Outlined.Tag, Hub.Media, "tic tac toe zero kata") { _, _ -> TicTacToe() },
    )
}

@Composable
fun GamesHub(open: (String) -> Unit) {
    AtlasScreen(title = "Games", subtitle = "Quick games, all offline", hub = Hub.Media) {
        item("h1") { SectionHeader("Arcade") }
        listOf(Triple("2048", "nx/g2048", "Swipe to merge"), Triple("Snake", "nx/snake", "Swipe to steer"), Triple("Tic-tac-toe", "nx/tictactoe", "Beat the unbeatable")).forEach { (t, r, d) ->
            item(r) { ListRow(t, supporting = d, icon = Icons.Outlined.SportsEsports, tint = Hub.Media.tint()) { open(r) } } }
        item("h2") { SectionHeader("Brain & party") }
        listOf(Triple("Chess", "power/chess", "Play the computer"), Triple("Quiz", "power/quiz", "Any topic"), Triple("Memory trainer", "nx/memgame", "Digit span"),
            Triple("Speed reading", "nx/speedread", "Train your eyes"), Triple("Party games", "nx/party", "Charades and more"), Triple("Interactive story", "nx/story", "Choose what happens"),
            Triple("Pet games", "nx/petgames", "Play with your pet"), Triple("Cricket scorer", "nx/cricket", "For real matches")).forEach { (t, r, d) ->
            item(r) { ListRow(t, supporting = d, icon = Icons.Outlined.Extension, tint = Hub.Media.tint()) { open(r) } } }
    }
}

// ─────────────────────────────── 2048 ───────────────────────────────
private fun slide(row: List<Int>): Pair<List<Int>, Int> {
    val xs = row.filter { it != 0 }.toMutableList(); var gained = 0
    var i = 0; val out = mutableListOf<Int>()
    while (i < xs.size) { if (i + 1 < xs.size && xs[i] == xs[i + 1]) { out += xs[i] * 2; gained += xs[i] * 2; i += 2 } else { out += xs[i]; i++ } }
    while (out.size < 4) out += 0
    return out to gained
}

@Composable
fun Game2048() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("g2048", 0) }
    fun fresh(): List<Int> { val b = MutableList(16) { 0 }; repeat(2) { b[b.indices.filter { b[it] == 0 }.random()] = 2 }; return b }
    var board by remember { mutableStateOf(fresh()) }
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(p.getInt("best", 0)) }
    fun move(dir: Int) { // 0 left 1 right 2 up 3 down
        val b = board.toMutableList(); var gained = 0
        for (k in 0 until 4) {
            val idx = (0 until 4).map { j -> when (dir) { 0 -> k * 4 + j; 1 -> k * 4 + 3 - j; 2 -> j * 4 + k; else -> (3 - j) * 4 + k } }
            val (r, g) = slide(idx.map { b[it] }); gained += g; idx.forEachIndexed { j, i -> b[i] = r[j] }
        }
        if (b != board) {
            b.indices.filter { b[it] == 0 }.randomOrNull()?.let { b[it] = if ((0..9).random() == 0) 4 else 2 }
            board = b; score += gained; if (score > best) { best = score; p.edit().putInt("best", best).apply() }
        }
    }
    val colours = mapOf(0 to Color(0xFFCDC1B4), 2 to Color(0xFFEEE4DA), 4 to Color(0xFFEDE0C8), 8 to Color(0xFFF2B179), 16 to Color(0xFFF59563), 32 to Color(0xFFF67C5F),
        64 to Color(0xFFF65E3B), 128 to Color(0xFFEDCF72), 256 to Color(0xFFEDCC61), 512 to Color(0xFFEDC850), 1024 to Color(0xFFEDC53F), 2048 to Color(0xFFEDC22E))
    val over = board.none { it == 0 } && (0 until 16).none { i -> (i % 4 < 3 && board[i] == board[i + 1]) || (i < 12 && board[i] == board[i + 4]) }
    AtlasScreen(title = "2048", subtitle = "Score $score · best $best", hub = Hub.Media) {
        item("b") {
            var acc by remember { mutableStateOf(Offset.Zero) }
            Column(Modifier.fillMaxWidth().padding(Space.page).aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFFBBADA0)).padding(6.dp)
                .pointerInput(Unit) { detectDragGestures(onDragStart = { acc = Offset.Zero }, onDragEnd = {
                    if (abs(acc.x) > 40 || abs(acc.y) > 40) move(if (abs(acc.x) > abs(acc.y)) (if (acc.x < 0) 0 else 1) else (if (acc.y < 0) 2 else 3)) }) { _, d -> acc += d } }) {
                (0 until 4).forEach { r -> Row(Modifier.weight(1f)) {
                    (0 until 4).forEach { c -> val v = board[r * 4 + c]
                        Box(Modifier.weight(1f).fillMaxWidth().padding(4.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(colours[v] ?: Color(0xFF3C3A32)), contentAlignment = Alignment.Center) {
                            if (v > 0) Text("$v", fontSize = if (v < 100) 30.sp else if (v < 1000) 24.sp else 18.sp, fontWeight = FontWeight.Bold, color = if (v <= 4) Color(0xFF776E65) else Color.White)
                        }
                    }
                } }
            }
        }
        if (over) item("o") { Text("No moves left!", Modifier.padding(horizontal = Space.page), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error) }
        item("n") { Button(onClick = { board = fresh(); score = 0 }, modifier = Modifier.padding(Space.page)) { Text("New game") } }
    }
}

// ─────────────────────────────── Snake ───────────────────────────────
@Composable
fun SnakeGame() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("snake", 0) }
    val n = 18
    var snake by remember { mutableStateOf(listOf(Pair(9, 9), Pair(8, 9), Pair(7, 9))) }
    var dir by remember { mutableStateOf(Pair(1, 0)) }
    var nextDir by remember { mutableStateOf(Pair(1, 0)) }
    var food by remember { mutableStateOf(Pair(13, 9)) }
    var alive by remember { mutableStateOf(false) }
    var best by remember { mutableIntStateOf(p.getInt("best", 0)) }
    LaunchedEffect(alive) { com.nizam.app.ui.design.guard {
        while (alive) {
            delay((170 - snake.size * 2).coerceAtLeast(70).toLong())
            if (nextDir.first != -dir.first || nextDir.second != -dir.second) dir = nextDir
            val h = snake.first(); val nh = Pair((h.first + dir.first + n) % n, (h.second + dir.second + n) % n)
            if (nh in snake.dropLast(1)) { alive = false; val s = snake.size - 3; if (s > best) { best = s; p.edit().putInt("best", s).apply() }; break }
            snake = if (nh == food) { var f: Pair<Int, Int>; do { f = Pair((0 until n).random(), (0 until n).random()) } while (f in snake || f == nh); food = f; listOf(nh) + snake }
                    else listOf(nh) + snake.dropLast(1)
        }
    } }
    AtlasScreen(title = "Snake", subtitle = "Score ${snake.size - 3} · best $best", hub = Hub.Media) {
        item("b") {
            var acc by remember { mutableStateOf(Offset.Zero) }
            Canvas(Modifier.fillMaxWidth().padding(Space.page).aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C2A1C))
                .pointerInput(Unit) { detectDragGestures(onDragStart = { acc = Offset.Zero }, onDragEnd = {
                    nextDir = if (abs(acc.x) > abs(acc.y)) Pair(if (acc.x > 0) 1 else -1, 0) else Pair(0, if (acc.y > 0) 1 else -1) }) { _, d -> acc += d } }) {
                val c = size.width / n
                drawRoundRect(Color(0xFFE84A3C), Offset(food.first * c + 2, food.second * c + 2), Size(c - 4, c - 4), CornerRadius(c / 2))
                snake.forEachIndexed { i, (x, y) -> drawRoundRect(if (i == 0) Color(0xFFB6F09C) else Color(0xFF6CC24A), Offset(x * c + 1, y * c + 1), Size(c - 2, c - 2), CornerRadius(c / 4)) }
            }
        }
        item("go") { Button(onClick = { snake = listOf(Pair(9, 9), Pair(8, 9), Pair(7, 9)); dir = Pair(1, 0); nextDir = dir; food = Pair(13, 9); alive = true }, enabled = !alive,
            modifier = Modifier.padding(horizontal = Space.page)) { Text(if (snake.size > 3 && !alive) "Play again" else "Start") } }
    }
}

// ─────────────────────────────── Tic-tac-toe ───────────────────────────────
private val LINES = listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8), listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8), listOf(0, 4, 8), listOf(2, 4, 6))
private fun winner(b: List<Char>): Char? = LINES.firstOrNull { (a, x, y) -> b[a] != ' ' && b[a] == b[x] && b[a] == b[y] }?.let { b[it[0]] } ?: if (' ' !in b) 'D' else null
private fun minimax(b: MutableList<Char>, ai: Boolean): Int {
    when (winner(b)) { 'O' -> return 10; 'X' -> return -10; 'D' -> return 0 }
    var best = if (ai) -100 else 100
    for (i in 0 until 9) if (b[i] == ' ') { b[i] = if (ai) 'O' else 'X'; val s = minimax(b, !ai); b[i] = ' '; best = if (ai) maxOf(best, s) else minOf(best, s) }
    return best
}

@Composable
fun TicTacToe() {
    var board by remember { mutableStateOf(List(9) { ' ' }) }
    var vsAi by remember { mutableStateOf(true) }
    var xTurn by remember { mutableStateOf(true) }
    var tally by remember { mutableStateOf(Triple(0, 0, 0)) }
    val w = winner(board)
    fun record(r: Char?) { when (r) { 'X' -> tally = tally.copy(first = tally.first + 1); 'O' -> tally = tally.copy(second = tally.second + 1); 'D' -> tally = tally.copy(third = tally.third + 1) } }
    fun play(i: Int) {
        if (board[i] != ' ' || w != null) return
        var b = board.toMutableList(); b[i] = if (xTurn) 'X' else 'O'
        if (vsAi && winner(b) == null) {
            val move = (0 until 9).filter { b[it] == ' ' }.maxByOrNull { m -> b[m] = 'O'; val s = minimax(b, false); b[m] = ' '; s }
            if (move != null) b[move] = 'O'
        } else xTurn = !xTurn
        board = b; record(winner(b))
    }
    AtlasScreen(title = "Tic-tac-toe", subtitle = "X ${tally.first} · O ${tally.second} · draws ${tally.third}", hub = Hub.Media) {
        item("m") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Capsule("vs AI", vsAi) { vsAi = true; board = List(9) { ' ' }; xTurn = true }; Capsule("2 players", !vsAi) { vsAi = false; board = List(9) { ' ' }; xTurn = true } } }
        item("b") { Column(Modifier.fillMaxWidth().padding(Space.page).aspectRatio(1f)) {
            (0 until 3).forEach { r -> Row(Modifier.weight(1f)) { (0 until 3).forEach { c -> val i = r * 3 + c
                Box(Modifier.weight(1f).fillMaxWidth().aspectRatio(1f).padding(4.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).pressable { play(i) },
                    contentAlignment = Alignment.Center) { Text("${board[i]}".trim(), fontSize = 48.sp, fontWeight = FontWeight.Bold, color = if (board[i] == 'X') Hub.Media.tint() else MaterialTheme.colorScheme.error) }
            } } }
        } }
        item("r") { Text(when (w) { 'X' -> "X wins!"; 'O' -> if (vsAi) "The AI wins." else "O wins!"; 'D' -> "Draw."; else -> if (vsAi || xTurn) "Your move (X)" else "O's move" },
            Modifier.padding(horizontal = Space.page), style = MaterialTheme.typography.titleLarge) }
        item("n") { Button(onClick = { board = List(9) { ' ' }; xTurn = true }, modifier = Modifier.padding(Space.page)) { Text("New round") } }
    }
}
