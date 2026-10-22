package com.nizam.app.feature.atlaschat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nizam.app.feature.nx.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.absoluteValue

/** Shrinks a photo until it is small enough to travel inside a relay event (about 45 KB), and returns it as text. */
private suspend fun encodePhoto(c: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1400) sample *= 2
        val bmp = c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        var edge = 720
        while (edge >= 200) {
            val k = minOf(1f, edge.toFloat() / maxOf(bmp.width, bmp.height))
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
            var q = 70
            while (q >= 30) {
                val out = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= 45_000) return@runCatching Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                q -= 10
            }
            edge -= 120
        }
        null
    }.getOrNull()
}

@Composable
private fun Pic(b64: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val bmp = remember(b64) { runCatching { Base64.decode(b64, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap() }.getOrNull() }
    if (bmp != null) Image(bmp, null, modifier, contentScale = scale)
}

@Composable
private fun Face(name: String, size: Int = 40, ring: Boolean = false) {
    if (ring) {
        Box(Modifier.size((size + 8).dp).clip(CircleShape).background(Brush.sweepGradient(listOf(Color(0xFFFFB347), Color(0xFFE0457B), Color(0xFF8A5BD6), Color(0xFFFFB347)))), contentAlignment = Alignment.Center) {
            Box(Modifier.size((size + 4).dp).clip(CircleShape).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { Face(name, size) }
        }
    } else {
        val h = (name.hashCode().absoluteValue % 360).toFloat()
        Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color.hsv(h, 0.55f, 0.85f), Color.hsv((h + 40f) % 360f, 0.65f, 0.65f)))), contentAlignment = Alignment.Center) {
            Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.42f).sp)
        }
    }
}

private fun ago(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60000
    return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; else -> "${m / 1440}d" }
}

/** Atlas Social: a public feed, photos, 24-hour stories, likes, comments and follows. */
@Composable
fun AtlasSocialScreen() {
    val ctx = LocalContext.current
    val chat = remember { AtlasChat.get(ctx) }
    val chatRev by chat.rev.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                if (!chat.registered) Join(chat, chatRev) else SocialMain(chat)
            }
        }
    }
}

@Composable
private fun Join(chat: ChatEngine, @Suppress("UNUSED_PARAMETER") rev: Int) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.Public, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text("Atlas Social", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Share photos, stories and thoughts. It uses the same Atlas ID as Messenger, so pick your username once.", Modifier.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20); err = "" }, label = { Text("Username") }, prefix = { Text("@") }, singleLine = true, isError = err.isNotEmpty(), supportingText = { Text(err) }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { busy = true; scope.launch { err = chat.register(name).orEmpty(); busy = false } }, enabled = name.length >= 3 && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (busy) "Please wait…" else "Join") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SocialMain(chat: ChatEngine) {
    val ctx = LocalContext.current
    val social = remember { AtlasChat.social(ctx) }
    LaunchedEffect(chat.registered) { com.nizam.app.ui.design.guard { social.start() } }
    val rev by social.rev.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var composing by remember { mutableStateOf(false) }
    var openProfile by remember { mutableStateOf<String?>(null) }
    var viewStories by remember { mutableStateOf<String?>(null) }
    var commentsFor by remember { mutableStateOf<Post?>(null) }
    val prof = openProfile
    if (prof != null) BackHandler { openProfile = null }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { NavigationBar { listOf("Home" to Icons.Filled.Home, "Following" to Icons.Filled.People, "Find" to Icons.Filled.Search, "Me" to Icons.Filled.Person).forEachIndexed { i, (t, ic) -> NavigationBarItem(tab == i && prof == null, { tab = i; openProfile = null }, { Icon(ic, t) }, label = { Text(t) }) } } },
        floatingActionButton = { if (prof == null && tab < 2) FloatingActionButton(onClick = { composing = true }) { Icon(Icons.Filled.Add, "New post") } }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (prof != null) ProfilePage(social, prof, rev, { openProfile = null }, { commentsFor = it }, { viewStories = it })
            else when (tab) {
                0, 1 -> FeedPage(social, rev, tab == 1, { openProfile = it }, { commentsFor = it }, { viewStories = it })
                2 -> FindPage(chat, social, rev) { openProfile = it }
                else -> ProfilePage(social, social.me, rev, null, { commentsFor = it }, { viewStories = it })
            }
        }
    }
    if (composing) Composer(social) { composing = false }
    commentsFor?.let { CommentsSheet(social, it, rev) { commentsFor = null } }
    viewStories?.let { StoryViewer(social, it) { viewStories = null } }
}

@Composable
private fun FeedPage(social: SocialEngine, rev: Int, followingOnly: Boolean, profile: (String) -> Unit, comments: (Post) -> Unit, stories: (String) -> Unit) {
    val list = remember(rev, followingOnly) { social.feed(followingOnly) }
    val st = remember(rev) { social.storiesByAuthor() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        item {
            Text(if (followingOnly) "Following" else "Atlas Social", Modifier.statusBarsPadding().padding(20.dp, 12.dp, 20.dp, 4.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(st.keys.sortedBy { if (it == social.me) 0 else 1 }) { a ->
                    Column(Modifier.clickable { stories(a) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Face(social.name(a), 58, ring = true)
                        Text(if (a == social.me) "Your story" else social.name(a), Modifier.widthIn(max = 70.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            HorizontalDivider()
        }
        if (list.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Public, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                Text(if (followingOnly) "Follow people in Find to see their posts here" else "Nothing here yet. Be the first to post, or give the relays a moment to load.", Modifier.padding(top = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(list, key = { it.id }) { p -> PostCard(social, p, rev, profile, comments) }
    }
}

@Composable
private fun PostCard(social: SocialEngine, p: Post, rev: Int, profile: (String) -> Unit, comments: (Post) -> Unit) {
    val ctx = LocalContext.current
    val liked = social.me in (social.likes[p.id] ?: emptySet<String>())
    val n = social.likes[p.id]?.size ?: 0
    val cn = remember(rev) { social.comments(p.id).size }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp, 4.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { profile(p.author) }, verticalAlignment = Alignment.CenterVertically) {
                Face(social.name(p.author)); Spacer(Modifier.width(10.dp))
                Column { Text(social.name(p.author), fontWeight = FontWeight.SemiBold); Text(ago(p.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    if (p.author == social.me) DropdownMenuItem({ Text("Delete post") }, { social.delete(p); menu = false })
                    else {
                        DropdownMenuItem({ Text("Hide this post") }, { social.hide(p.id); menu = false })
                        DropdownMenuItem({ Text("Report and hide") }, { social.hide(p.id); menu = false })
                        DropdownMenuItem({ Text("Block ${social.name(p.author)}") }, { social.block(p.author); menu = false })
                    }
                }
            }
        }
        p.img?.let { Pic(it, Modifier.fillMaxWidth().heightIn(max = 460.dp), ContentScale.FillWidth) }
        if (p.text.isNotBlank()) Text(p.text, Modifier.padding(14.dp, 8.dp, 14.dp, 0.dp))
        Row(Modifier.padding(4.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { social.toggleLike(p) }) { Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Like", tint = if (liked) Color(0xFFE0457B) else LocalContentColor.current) }
            Text(if (n > 0) "$n" else "", Modifier.widthIn(min = 16.dp))
            IconButton(onClick = { comments(p) }) { Icon(Icons.Filled.ChatBubbleOutline, "Comments") }
            Text(if (cn > 0) "$cn" else "")
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { Share.text(ctx, "${social.name(p.author)} on Atlas Social:\n${p.text}") }) { Icon(Icons.Filled.Share, "Share") }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(social: SocialEngine, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var text by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { busy = true; err = ""; scope.launch { photo = encodePhoto(ctx, uri); if (photo == null) err = "That photo couldn't be prepared."; busy = false } }
    }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New post", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(text, { text = it.take(2000) }, placeholder = { Text("What's on your mind?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            photo?.let { Pic(it, Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(16.dp)), ContentScale.Crop) }
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            Text("Posts are public to everyone on Atlas Social. For private talk use Messenger.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) { Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp)); Text(if (photo == null) "Photo" else "Change") }
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { social.post(text, photo, story = true); close() }, enabled = !busy && (photo != null || text.isNotBlank()), modifier = Modifier.weight(1f)) { Text("Add to story") }
                Button(onClick = { social.post(text, photo); close() }, enabled = !busy && (photo != null || text.isNotBlank()), modifier = Modifier.weight(1f)) { Text("Post") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheet(social: SocialEngine, post: Post, rev: Int, close: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val list = remember(rev) { social.comments(post.id) }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp).navigationBarsPadding().imePadding()) {
            Text("Comments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.heightIn(max = 320.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (list.isEmpty()) item { Text("No comments yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(list, key = { it.id }) { c -> Row { Face(social.name(c.author), 32); Spacer(Modifier.width(10.dp)); Column { Text(social.name(c.author), fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(c.text) } } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it.take(500) }, placeholder = { Text("Add a comment") }, modifier = Modifier.weight(1f), singleLine = true)
                IconButton(onClick = { social.comment(post, text); text = "" }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

@Composable
private fun StoryViewer(social: SocialEngine, author: String, close: () -> Unit) {
    val list = remember(author) { social.storiesByAuthor()[author].orEmpty() }
    var i by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(i) { com.nizam.app.ui.design.guard { progress = 0f; while (progress < 1f) { delay(50); progress += 0.01f }; if (i < list.lastIndex) i++ else close() } }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val s = list.getOrNull(i)
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { if (i < list.lastIndex) i++ else close() }) {
            if (s?.img != null) Pic(s.img, Modifier.fillMaxSize(), ContentScale.Fit)
            if (s != null && s.text.isNotBlank()) Text(s.text, Modifier.align(Alignment.Center).padding(32.dp).background(Color(0x66000000), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White, fontSize = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { list.forEachIndexed { k, _ -> LinearProgressIndicator(progress = { if (k < i) 1f else if (k == i) progress else 0f }, Modifier.weight(1f).height(3.dp), color = Color.White, trackColor = Color(0x55FFFFFF)) } }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Face(social.name(author), 34); Spacer(Modifier.width(8.dp)); Text(social.name(author), color = Color.White, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(8.dp)); Text(s?.let { ago(it.ts) }.orEmpty(), color = Color(0xCCFFFFFF), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f)); IconButton(onClick = close) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun FindPage(chat: ChatEngine, social: SocialEngine, rev: Int, open: (String) -> Unit) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val people = remember(rev) { (social.posts.values.map { it.author } + social.following).distinct().filter { it != social.me && it !in social.blocked }.take(60) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Find people", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it; msg = "" }, placeholder = { Text("@username or Atlas ID") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { busy = true; msg = ""; scope.launch { val f = chat.find(q); if (f == null) msg = "Nobody found." else { social.profiles.putIfAbsent(f.pub, Profile(f.name, "")); open(f.pub) }; busy = false } }, enabled = q.length >= 3 && !busy) { Icon(Icons.Filled.Search, "Search") }
            }
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
            Text("People posting now", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(people, key = { it }) { a -> Row(Modifier.fillMaxWidth().clickable { open(a) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Face(social.name(a), 44); Spacer(Modifier.width(12.dp)); Text(social.name(a), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            if (a in social.following) Text("Following", color = MaterialTheme.colorScheme.primary) else OutlinedButton(onClick = { social.follow(a, true) }) { Text("Follow") }
        } }
    }
}

@Composable
private fun ProfilePage(social: SocialEngine, pub: String, rev: Int, back: (() -> Unit)?, comments: (Post) -> Unit, stories: (String) -> Unit) {
    val mine = pub == social.me
    val list = remember(rev, pub) { social.byAuthor(pub) }
    var editing by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(social.about) }
    val hasStory = remember(rev) { social.storiesByAuthor().containsKey(pub) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (back != null) Row(Modifier.fillMaxWidth()) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
                Box(Modifier.clickable(enabled = hasStory) { stories(pub) }) { Face(social.name(pub), 84, ring = hasStory) }
                Text(social.name(pub), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val bio = if (mine) social.about else social.profiles[pub]?.about.orEmpty()
                if (bio.isNotBlank()) Text(bio, Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("${list.size} posts · ${social.following.size} following", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(10.dp))
                if (mine) {
                    if (editing) { OutlinedTextField(about, { about = it.take(200) }, label = { Text("About you") }, modifier = Modifier.fillMaxWidth()); Button(onClick = { social.setAbout(about); editing = false }, Modifier.padding(top = 8.dp)) { Text("Save") } }
                    else OutlinedButton(onClick = { editing = true }) { Text("Edit profile") }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pub in social.following) OutlinedButton(onClick = { social.follow(pub, false) }) { Text("Unfollow") } else Button(onClick = { social.follow(pub, true) }) { Text("Follow") }
                    OutlinedButton(onClick = { social.block(pub); back?.invoke() }) { Text("Block") }
                }
            }
            HorizontalDivider()
        }
        items(list, key = { it.id }) { p -> PostCard(social, p, rev, {}, comments) }
    }
}
