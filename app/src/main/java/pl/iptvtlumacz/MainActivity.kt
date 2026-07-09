package pl.iptvtlumacz

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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

@Composable
fun Modifier.tvFocusBorder(shape: RoundedCornerShape = RoundedCornerShape(10.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .border(if (focused) 2.dp else 0.dp,
            if (focused) Accent else Color.Transparent, shape)
}

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

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

    var epgTick by remember { mutableIntStateOf(0) }
    var groqInfo by remember { mutableStateOf<String?>(null) }
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

    // Wskaźnik darmowego limitu Groq
    LaunchedEffect(Unit) {
        while (true) {
            groqInfo = CloudApi.GroqStatus.summary()
            delay(5000)
        }
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
                result.onSuccess { data ->
                    channels = data.channels
                    prefs.m3uUrl = m3uUrl.trim()
                    status = if (data.channels.isEmpty()) "Playlista nie zawiera kanałów"
                             else "Wczytano ${data.channels.size} kanałów"
                    if (data.epgUrl.isNotBlank()) {
                        launch(Dispatchers.IO) {
                            val r = Epg.load(data.epgUrl)
                            launch(Dispatchers.Main) {
                                r.onSuccess { n ->
                                    epgTick++
                                    status = "Program TV wczytany ($n kanałów)"
                                }.onFailure {
                                    status = "Nie udało się pobrać programu TV"
                                }
                            }
                        }
                    }
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
        prefs.lastChannel = ch.url + "|" + ch.name
    }

    fun step(delta: Int) {
        val cur = playing ?: return
        if (channels.isEmpty()) return
        val i = channels.indexOfFirst { it.url == cur.url }
        val next = channels[((i + delta) % channels.size + channels.size) % channels.size]
        tune(next)
    }

    var epgLine by remember { mutableStateOf("") }
    LaunchedEffect(playing, epgTick) {
        while (playing != null) {
            val (nowT, nextT) = playing?.let { Epg.nowNext(it) } ?: (null to null)
            epgLine = listOfNotNull(
                nowT?.let { "Teraz: $it" },
                nextT?.let { "Potem: $it" },
            ).joinToString("   •   ")
            delay(60_000)
        }
        epgLine = ""
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
            subColor = prefs.subColor,
            epgLine = epgLine,
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
            lastChannel = prefs.lastChannel,
            epgTick = epgTick, groqInfo = groqInfo,
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
    lastChannel: String,
    epgTick: Int, groqInfo: String?,
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

        if (groqInfo != null)
            Text(groqInfo, color = Dim, fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))

        // Wznów ostatni kanał
        val lastUrl = lastChannel.substringBefore("|")
        val lastName = lastChannel.substringAfter("|", "")
        val lastCh = channels.firstOrNull { it.url == lastUrl }
        if (lastCh != null && lastName.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .background(Panel2, RoundedCornerShape(10.dp))
                    .tvFocusBorder()
                    .clickable { onPick(lastCh) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▶", color = Accent, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                Text("Wznów: $lastName", color = TextC, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }

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
            searching -> ChannelList(channels.filter(::matches), favorites, epgTick, onPick, onToggleFav)
            tab == 0 -> {
                val favs = channels.filter { it.url in favorites }
                if (favs.isEmpty())
                    EmptyInfo("Brak ulubionych — dotknij serduszka przy kanale.")
                else ChannelList(favs, favorites, epgTick, onPick, onToggleFav)
            }
            tab == 1 -> ChannelList(channels, favorites, epgTick, onPick, onToggleFav)
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
                                    .tvFocusBorder()
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
                        favorites, epgTick, onPick, onToggleFav)
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
    list: List<Channel>, favorites: Set<String>, epgTick: Int,
    onPick: (Channel) -> Unit, onToggleFav: (Channel) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        items(list) { ch ->
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .background(Panel, RoundedCornerShape(10.dp))
                    .tvFocusBorder()
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
                    val nowTitle = remember(epgTick, ch) { Epg.nowNext(ch).first }
                    if (nowTitle != null)
                        Text("Teraz: $nowTitle", color = Accent.copy(alpha = 0.85f),
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    subSize: Int, subPos: Int, subBg: Boolean, subColor: Int,
    epgLine: String,
    showOrig: Boolean, isFav: Boolean,
    onToggleOrig: () -> Unit, onToggleFav: () -> Unit,
    onPrev: () -> Unit, onNext: () -> Unit,
    onBack: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }

    // Tryb pełnoekranowy: schowaj paski systemowe na czas odtwarzania
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    controllerShowTimeoutMs = 3500
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { v ->
                            controlsVisible = v == View.VISIBLE
                        }
                    )
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // Pasek górny — widoczny razem z kontrolkami odtwarzacza
        if (controlsVisible)
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
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
        if (epgLine.isNotBlank()) {
            Text(
                epgLine, color = Color(0xFFB9C7D6), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp)
                    .background(Color(0x66000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
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
        val bottomPad = when (subPos) { 1 -> 110.dp; 2 -> 200.dp; 3 -> 8.dp; else -> 46.dp }
        val subShadow = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 2f), blurRadius = 8f))
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
                    subPl, color = Color(subColor), fontSize = subSize.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    lineHeight = (subSize + 6).sp, style = subShadow,
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
    var subColor by remember { mutableIntStateOf(prefs.subColor) }
    var syncMode by remember { mutableIntStateOf(0) }   // 0 nic, 1 odbiór (TV), 2 wysyłka
    var tvIp by remember { mutableStateOf("") }
    var syncMsg by remember { mutableStateOf("") }
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
                prefs.subColor = subColor
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
                val positions = listOf("przy krawędzi" to 3, "nisko" to 0, "średnio" to 1, "wysoko" to 2)
                positions.chunked(2).forEach { rowItems ->
                    Row {
                        rowItems.forEach { (label, v) ->
                            Row(
                                Modifier.weight(1f).clickable { subPos = v },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = subPos == v, onClick = { subPos = v })
                                Text(label, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Text("Napisy — kolor:", color = Dim, fontSize = 13.sp)
                val colors = listOf(
                    0xFFFFE14D.toInt(), 0xFFFFFFFF.toInt(), 0xFF69F0AE.toInt(),
                    0xFF4FC3F7.toInt(), 0xFFFFB74D.toInt(),
                )
                Row {
                    colors.forEach { c ->
                        Box(
                            Modifier.padding(end = 10.dp).size(32.dp)
                                .background(Color(c), RoundedCornerShape(16.dp))
                                .then(
                                    if (subColor == c) Modifier.padding(0.dp) else Modifier
                                )
                                .clickable { subColor = c },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (subColor == c)
                                Text("✓", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = subBg, onCheckedChange = { subBg = it })
                    Text("Ciemne tło pod napisami", fontSize = 14.sp)
                }
                HorizontalDivider()
                Text("Przesyłanie ustawień (telefon ↔ TV, ta sama sieć Wi-Fi):",
                    color = Dim, fontSize = 13.sp)
                Row {
                    OutlinedButton(onClick = {
                        syncMode = if (syncMode == 1) 0 else 1
                    }, modifier = Modifier.weight(1f)) {
                        Text(if (syncMode == 1) "Zatrzymaj odbiór" else "Odbierz (TV)",
                            fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        syncMode = if (syncMode == 2) 0 else 2
                    }, modifier = Modifier.weight(1f)) {
                        Text("Wyślij na TV", fontSize = 12.sp)
                    }
                }
                if (syncMode == 1) {
                    DisposableEffect(Unit) {
                        val port = SettingsSync.startReceiver { payload ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (payload.openai.isNotBlank()) openai = payload.openai
                                if (payload.anthropic.isNotBlank()) anthropic = payload.anthropic
                                if (payload.m3u.isNotBlank()) m3u = payload.m3u
                                prefs.openaiKey = openai.trim()
                                prefs.anthropicKey = anthropic.trim()
                                if (payload.m3u.isNotBlank()) prefs.m3uUrl = payload.m3u.trim()
                                syncMsg = "Odebrano ustawienia! Kliknij Zapisz."
                            }
                        }
                        val ip = SettingsSync.localIp()
                        syncMsg = if (port != null && ip != null)
                            "Czekam... Na telefonie: Ustawienia → Wyślij na TV → wpisz: $ip" +
                            (if (port != 8765) ":$port" else "")
                        else "Nie udało się uruchomić odbiornika"
                        onDispose { SettingsSync.stop() }
                    }
                }
                if (syncMode == 2) {
                    val scope = rememberCoroutineScope()
                    OutlinedTextField(
                        value = tvIp, onValueChange = { tvIp = it },
                        label = { Text("Adres pokazany na TV (np. 192.168.0.15)") },
                        singleLine = true,
                    )
                    Button(onClick = {
                        syncMsg = "Wysyłam..."
                        scope.launch(Dispatchers.IO) {
                            val r = SettingsSync.send(tvIp, m3u.trim(),
                                openai.trim(), anthropic.trim())
                            launch(Dispatchers.Main) {
                                syncMsg = r.fold(
                                    onSuccess = { "Wysłano — sprawdź TV" },
                                    onFailure = { "Błąd: ${'$'}{it.message}" },
                                )
                            }
                        }
                    }) { Text("Wyślij") }
                }
                if (syncMsg.isNotBlank())
                    Text(syncMsg, color = Accent, fontSize = 13.sp)
            }
        },
    )
}
