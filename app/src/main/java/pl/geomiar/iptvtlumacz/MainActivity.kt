package pl.geomiar.iptvtlumacz

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0B0F14)
private val Panel = Color(0xFF121820)
private val Panel2 = Color(0xFF182230)
private val TextC = Color(0xFFDBE4EE)
private val Dim = Color(0xFF7B8A9C)
private val Accent = Color(0xFF4FC3F7)
private val SubYellow = Color(0xFFFFE14D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Bg, surface = Panel, primary = Accent,
                onBackground = TextC, onSurface = TextC,
            )) {
                App()
            }
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
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

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

    // Automatyczne chowanie napisów po 9 s
    LaunchedEffect(subStamp) {
        if (subStamp == 0L) return@LaunchedEffect
        delay(9000)
        if (System.currentTimeMillis() - subStamp >= 9000) { subOrig = ""; subPl = "" }
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
        status = "Nasłuchuję i tłumaczę..."
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
            onBack = { stopPlayback() },
        )
    } else {
        ChannelListScreen(
            channels = channels, search = search, onSearch = { search = it },
            m3uUrl = m3uUrl, onM3uUrl = { m3uUrl = it },
            loading = loading, status = status,
            onLoad = { loadPlaylist() },
            onPick = { tune(it) },
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
fun ChannelListScreen(
    channels: List<Channel>, search: String, onSearch: (String) -> Unit,
    m3uUrl: String, onM3uUrl: (String) -> Unit,
    loading: Boolean, status: String,
    onLoad: () -> Unit, onPick: (Channel) -> Unit, onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("IPTV ", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Tłumacz", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("Ustawienia", color = Accent) }
        }
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
        if (status.isNotBlank())
            Text(status, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        OutlinedTextField(
            value = search, onValueChange = onSearch,
            label = { Text("Szukaj kanału") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            val filtered = channels.filter {
                search.isBlank() || "${it.name} ${it.group}".contains(search, ignoreCase = true)
            }
            items(filtered) { ch ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(Panel, RoundedCornerShape(10.dp))
                        .clickable { onPick(ch) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, color = TextC, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (ch.group.isNotBlank())
                            Text(ch.group, color = Dim, fontSize = 12.sp, maxLines = 1)
                    }
                    Text(
                        if (ch.lang == "auto") "?" else ch.lang.uppercase(),
                        color = Bg, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(if (ch.lang == "auto") Dim else Accent, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
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
            Modifier.align(Alignment.TopStart).systemBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Kanały", color = Accent) }
            Text(channel.name, color = TextC, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (status.isNotBlank()) {
            Text(
                status, color = Dim, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopEnd)
                    .systemBarsPadding().padding(12.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // Napisy
        Column(
            Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 42.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (subOrig.isNotBlank()) {
                Text(
                    subOrig, color = Color(0xFFCFD8E3), fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color(0x73000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
                Spacer(Modifier.height(5.dp))
            }
            if (subPl.isNotBlank()) {
                Text(
                    subPl, color = SubYellow, fontSize = 21.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    lineHeight = 27.sp,
                    modifier = Modifier
                        .background(Color(0x80000000), RoundedCornerShape(8.dp))
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
                onClose()
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Anuluj") } },
        title = { Text("Ustawienia") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        },
    )
}
