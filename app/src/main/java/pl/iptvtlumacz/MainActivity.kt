package pl.iptvtlumacz

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0B0F14)
private val Panel = Color(0xFF1B222C)
private val Panel2 = Color(0xFF232C38)
private val TopBar = Color(0xFF1E4976)
private val TextC = Color(0xFFDBE4EE)
private val Dim = Color(0xFF8B9AAC)
private val Accent = Color(0xFF4FC3F7)
private val SubYellow = Color(0xFFFFE14D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Czarna skrzynka: zapisz błąd, żeby pokazać go po ponownym uruchomieniu.
        val prefsForCrash = Prefs(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { prefsForCrash.lastCrash = e.stackTraceToString().take(4000) }
            previous?.uncaughtException(thread, e)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val crash = prefsForCrash.lastCrash
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Bg, surface = Panel, primary = Accent,
                onBackground = TextC, onSurface = TextC,
            )) {
                if (crash.isNotBlank()) {
                    CrashScreen(crash) {
                        prefsForCrash.lastCrash = ""
                        recreate()
                    }
                } else {
                    App()
                }
            }
        }
    }
}

@Composable
fun CrashScreen(text: String, onClose: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(16.dp),
    ) {
        Text("Poprzednie uruchomienie zakończyło się błędem",
            color = TextC, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Skopiuj poniższą treść i wyślij ją Claude do naprawy:",
            color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            text, color = Color(0xFFE8B9B9), fontSize = 11.sp,
            modifier = Modifier.weight(1f)
                .background(Panel, RoundedCornerShape(8.dp))
                .padding(10.dp)
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(10.dp))
        Row {
            Button(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
            }) { Text("Kopiuj błąd") }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onClose) { Text("Uruchom aplikację") }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }

    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var m3uUrl by remember { mutableStateOf(prefs.m3uUrl) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    var tab by remember { mutableIntStateOf(1) }          // 0 Ulubione, 1 Wszystkie, 2 Kategorie
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(prefs.favorites) }

    var playing by remember { mutableStateOf<Channel?>(null) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var subOrig by remember { mutableStateOf("") }
    var subPl by remember { mutableStateOf("") }
    var subStamp by remember { mutableLongStateOf(0L) }
    var lang by remember { mutableStateOf(prefs.lang) }
    var showOrig by remember { mutableStateOf(prefs.showOrig) }

    val engine = remember {
        AudioEngine(
            context = context,
            scope = scope,
            config = { EngineConfig(prefs.lang, prefs.openaiKey, prefs.anthropicKey) },
            onSubtitle = { o, p -> subOrig = o; subPl = p; subStamp = System.currentTimeMillis() },
            onStatus = { status = it },
        )
    }

    LaunchedEffect(subStamp) {
        if (subStamp == 0L) return@LaunchedEffect
        delay(9000)
        if (System.currentTimeMillis() - subStamp >= 9000) { subOrig = ""; subPl = "" }
    }

    // Komunikaty (błędy, limity) znikają same po 6 s
    LaunchedEffect(status) {
        if (status.isBlank()) return@LaunchedEffect
        delay(6000)
        status = ""
    }

    fun loadPlaylist() {
        if (m3uUrl.isBlank()) { status = "Podaj adres playlisty M3U"; return }
        loading = true; status = "Pobieram playlistę..."
        scope.launch(Dispatchers.IO) {
            val result = CloudApi.fetchText(m3uUrl.trim()).mapCatching { M3U.parse(it) }
            launch(Dispatchers.Main) {
                loading = false
                result.onSuccess {
                    channels = it
                    prefs.m3uUrl = m3uUrl.trim()
                    status = if (it.isEmpty()) "Playlista nie zawiera kanałów" else "Wczytano ${it.size} kanałów"
                }.onFailure { status = "Błąd playlisty: ${it.message}" }
            }
        }
    }

    fun toggleFav(ch: Channel) {
        favorites = if (ch.url in favorites) favorites - ch.url else favorites + ch.url
        prefs.favorites = favorites
    }

    fun stopPlayback() {
        engine.stop()
        player?.release()
        player = null
        playing = null
        subOrig = ""; subPl = ""; status = ""
    }

    fun tune(ch: Channel) {
        stopPlayback()
        if (ch.lang != "auto") { lang = ch.lang; prefs.lang = ch.lang }
        val p = engine.buildPlayer()
        p.setMediaItem(MediaItem.fromUri(ch.url))
        p.prepare()
        p.playWhenReady = true
        player = p
        playing = ch
        engine.start()
    }

    fun step(delta: Int) {
        val cur = playing ?: return
        if (channels.isEmpty()) return
        val i = channels.indexOfFirst { it.url == cur.url }
        val next = channels[((i + delta) % channels.size + channels.size) % channels.size]
        tune(next)
    }

    LaunchedEffect(Unit) { if (prefs.m3uUrl.isNotBlank()) loadPlaylist() }
    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    if (playing != null && player != null) {
        BackHandler { stopPlayback() }
        PlayerScreen(
            channel = playing!!,
            player = player!!,
            subOrig = if (showOrig) subOrig else "",
            subPl = subPl,
            status = status,
            subSize = prefs.subSize,
            subPos = prefs.subPos,
            subBg = prefs.subBg,
            showOrig = showOrig,
            isFav = playing!!.url in favorites,
            onToggleOrig = { showOrig = !showOrig; prefs.showOrig = showOrig },
            onToggleFav = { toggleFav(playing!!) },
            onPrev = { step(-1) },
            onNext = { step(+1) },
            onBack = { stopPlayback() },
        )
    } else {
        MainScreen(
            channels = channels, favorites = favorites,
            tab = tab, onTab = { tab = it; selectedGroup = null },
            selectedGroup = selectedGroup, onGroup = { selectedGroup = it },
            search = search, onSearch = { search = it },
            searchOpen = searchOpen, onSearchOpen = { searchOpen = it; if (!it) search = "" },
            m3uUrl = m3uUrl, onM3uUrl = { m3uUrl = it },
            loading = loading, status = status,
            onLoad = { loadPlaylist() },
            onPick = { tune(it) },
            onToggleFav = { toggleFav(it) },
            onSettings = { showSettings = true },
        )
    }

    if (showSettings) {
        SettingsDialog(
            prefs = prefs,
            lang = lang, onLang = { lang = it; prefs.lang = it },
            showOrig = showOrig, onShowOrig = { showOrig = it; prefs.showOrig = it },
            onClose = { showSettings = false },
        )
    }
}

@Composable
fun MainScreen(
    channels: List<Channel>, favorites: Set<String>,
    tab: Int, onTab: (Int) -> Unit,
    selectedGroup: String?, onGroup: (String?) -> Unit,
    search: String, onSearch: (String) -> Unit,
    searchOpen: Boolean, onSearchOpen: (Boolean) -> Unit,
    m3uUrl: String, onM3uUrl: (String) -> Unit,
    loading: Boolean, status: String,
    onLoad: () -> Unit, onPick: (Channel) -> Unit,
    onToggleFav: (Channel) -> Unit, onSettings: () -> Unit,
) {
    val groups = remember(channels) {
        channels.groupBy { it.group.ifBlank { "Inne" } }.toSortedMap()
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // Pasek górny
        Column(Modifier.background(TopBar).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("IPTV ", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp))
                Text("Tłumacz", color = Accent, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onSearchOpen(!searchOpen) }) {
                    Icon(Icons.Filled.Search, "Szukaj", tint = Color.White)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, "Ustawienia", tint = Color.White)
                }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = search, onValueChange = onSearch,
                    placeholder = { Text("Szukaj kanału...", color = Dim) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            TabRow(selectedTabIndex = tab, containerColor = TopBar, contentColor = Color.White) {
                listOf("ULUBIONE", "WSZYSTKIE", "KATEGORIE").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { onTab(i) },
                        text = { Text(t, fontSize = 13.sp,
                            color = if (tab == i) Color.White else Color(0xFFB9C7D6)) })
                }
            }
        }

        if (status.isNotBlank())
            Text(status, color = Dim, fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))

        // Brak playlisty → pole na adres
        if (channels.isEmpty()) {
            Column(Modifier.padding(14.dp)) {
                Text("Wczytaj playlistę M3U, aby zobaczyć kanały:", color = Dim, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = m3uUrl, onValueChange = onM3uUrl,
                        label = { Text("Adres playlisty M3U") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onLoad, enabled = !loading) {
                        Text(if (loading) "..." else "Wczytaj")
                    }
                }
            }
            return@Column
        }

        val searching = search.isNotBlank()
        fun matches(c: Channel) =
            "${c.name} ${c.group}".contains(search, ignoreCase = true)

        when {
            searching -> ChannelList(channels.filter(::matches), favorites, onPick, onToggleFav)
            tab == 0 -> {
                val favs = channels.filter { it.url in favorites }
                if (favs.isEmpty())
                    EmptyInfo("Brak ulubionych — dotknij serduszka przy kanale.")
                else ChannelList(favs, favorites, onPick, onToggleFav)
            }
            tab == 1 -> ChannelList(channels, favorites, onPick, onToggleFav)
            else -> {
                if (selectedGroup == null) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(groups.keys.toList()) { g ->
                            val n = groups[g]?.size ?: 0
                            Column(
                                Modifier.background(Panel, RoundedCornerShape(10.dp))
                                    .clickable { onGroup(g) }
                                    .padding(14.dp),
                            ) {
                                Text(g, color = TextC, fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(if (n == 1) "1 kanał" else "$n kanałów",
                                    color = Dim, fontSize = 13.sp)
                            }
                        }
                    }
                } else {
                    BackHandler { onGroup(null) }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { onGroup(null) }) {
                            Icon(Icons.Filled.KeyboardArrowLeft, "Wstecz", tint = Accent)
                        }
                        Text(selectedGroup, color = TextC, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    ChannelList(groups[selectedGroup] ?: emptyList(),
                        favorites, onPick, onToggleFav)
                }
            }
        }
    }
}

@Composable
fun EmptyInfo(msg: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(msg, color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun ChannelList(
    list: List<Channel>, favorites: Set<String>,
    onPick: (Channel) -> Unit, onToggleFav: (Channel) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        items(list) { ch ->
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .background(Panel, RoundedCornerShape(10.dp))
                    .clickable { onPick(ch) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ch.logo.isNotBlank()) {
                    AsyncImage(
                        model = ch.logo, contentDescription = null,
                        modifier = Modifier.size(42.dp)
                            .background(Color(0x33000000), RoundedCornerShape(6.dp))
                            .padding(3.dp),
                    )
                } else {
                    Box(Modifier.size(42.dp).background(Panel2, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center) {
                        Text(ch.name.take(2).uppercase(), color = Dim,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch.name, color = TextC, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (ch.group.isNotBlank())
                        Text(ch.group, color = Dim, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                }
                if (ch.lang != "auto")
                    Text(ch.lang.uppercase(), color = Bg, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Accent, RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp))
                IconButton(onClick = { onToggleFav(ch) }) {
                    Icon(
                        if (ch.url in favorites) Icons.Filled.Favorite
                        else Icons.Filled.FavoriteBorder,
                        contentDescription = "Ulubione",
                        tint = if (ch.url in favorites) Color(0xFFE0526A) else Dim,
                    )
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    channel: Channel, player: ExoPlayer,
    subOrig: String, subPl: String, status: String,
    subSize: Int, subPos: Int, subBg: Boolean,
    showOrig: Boolean, isFav: Boolean,
    onToggleOrig: () -> Unit, onToggleFav: () -> Unit,
    onPrev: () -> Unit, onNext: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    controllerShowTimeoutMs = 2500
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // Pasek górny
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹", color = Accent, fontSize = 22.sp) }
            Text(channel.name, color = TextC, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onPrev) {
                Icon(Icons.Filled.KeyboardArrowLeft, "Poprzedni", tint = TextC)
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.KeyboardArrowRight, "Następny", tint = TextC)
            }
            IconButton(onClick = onToggleFav) {
                Icon(if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    "Ulubione", tint = if (isFav) Color(0xFFE0526A) else TextC)
            }
            TextButton(onClick = onToggleOrig) {
                Text("Or.", color = if (showOrig) Accent else Dim,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (status.isNotBlank()) {
            Text(
                status, color = Dim, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .navigationBarsPadding().padding(10.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // Napisy
        val bottomPad = when (subPos) { 1 -> 110.dp; 2 -> 200.dp; else -> 46.dp }
        val bgOrig = if (subBg) Color(0x73000000) else Color.Transparent
        val bgPl = if (subBg) Color(0x80000000) else Color.Transparent
        Column(
            Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = bottomPad, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (subOrig.isNotBlank()) {
                Text(
                    subOrig, color = Color(0xFFCFD8E3),
                    fontSize = (subSize * 2 / 3).coerceAtLeast(12).sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(bgOrig, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
                Spacer(Modifier.height(5.dp))
            }
            if (subPl.isNotBlank()) {
                Text(
                    subPl, color = SubYellow, fontSize = subSize.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    lineHeight = (subSize + 6).sp,
                    modifier = Modifier
                        .background(bgPl, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
fun SettingsDialog(
    prefs: Prefs,
    lang: String, onLang: (String) -> Unit,
    showOrig: Boolean, onShowOrig: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var openai by remember { mutableStateOf(prefs.openaiKey) }
    var anthropic by remember { mutableStateOf(prefs.anthropicKey) }
    var m3u by remember { mutableStateOf(prefs.m3uUrl) }
    var subSize by remember { mutableIntStateOf(prefs.subSize) }
    var subPos by remember { mutableIntStateOf(prefs.subPos) }
    var subBg by remember { mutableStateOf(prefs.subBg) }
    val langs = listOf(
        "auto" to "wykryj automatycznie", "de" to "niemiecki", "it" to "włoski",
        "en" to "angielski", "fr" to "francuski", "es" to "hiszpański",
    )

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = {
                prefs.openaiKey = openai.trim()
                prefs.anthropicKey = anthropic.trim()
                prefs.m3uUrl = m3u.trim()
                prefs.subSize = subSize
                prefs.subPos = subPos
                prefs.subBg = subBg
                onClose()
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Anuluj") } },
        title = { Text("Ustawienia") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = m3u, onValueChange = { m3u = it },
                    label = { Text("Adres playlisty M3U") }, singleLine = true,
                )
                OutlinedTextField(
                    value = openai, onValueChange = { openai = it },
                    label = { Text("Klucz Groq (darmowy) lub OpenAI — mowa") }, singleLine = true,
                )
                OutlinedTextField(
                    value = anthropic, onValueChange = { anthropic = it },
                    label = { Text("Klucz Claude (tłumaczenie)") }, singleLine = true,
                )
                Text("Język kanału:", color = Dim, fontSize = 13.sp)
                langs.forEach { (code, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onLang(code) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = lang == code, onClick = { onLang(code) })
                        Text(label, fontSize = 14.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showOrig, onCheckedChange = onShowOrig)
                    Text("Pokazuj tekst oryginalny", fontSize = 14.sp)
                }
                HorizontalDivider()
                Text("Napisy — rozmiar: ${'$'}{subSize}", color = Dim, fontSize = 13.sp)
                Slider(
                    value = subSize.toFloat(),
                    onValueChange = { subSize = it.toInt() },
                    valueRange = 16f..32f, steps = 15,
                )
                Text("Napisy — pozycja:", color = Dim, fontSize = 13.sp)
                Row {
                    listOf("nisko", "średnio", "wysoko").forEachIndexed { i, label ->
                        Row(
                            Modifier.clickable { subPos = i }.padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = subPos == i, onClick = { subPos = i })
                            Text(label, fontSize = 13.sp)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = subBg, onCheckedChange = { subBg = it })
                    Text("Ciemne tło pod napisami", fontSize = 14.sp)
                }
            }
        },
    )
}
