package pl.iptvtlumacz

import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Program TV (EPG) w formacie XMLTV, pobierany z url-tvg playlisty.
 * Parser strumieniowy — trzyma w pamięci tylko okno od -30 min do +26 h,
 * więc nawet wielodniowe, wielomegabajtowe pliki EPG nie zapchają telefonu.
 */
object Epg {

    data class Prog(val start: Long, val stop: Long, val title: String)

    @Volatile private var byId: Map<String, List<Prog>> = emptyMap()
    @Volatile private var idByName: Map<String, String> = emptyMap()

    fun loaded() = byId.isNotEmpty()

    /** Pobiera i parsuje EPG. Zwraca liczbę kanałów z programem. */
    fun load(url: String): Result<Int> = runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (IPTV-Tlumacz-Android)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            var input: InputStream = BufferedInputStream(resp.body!!.byteStream())
            // Pliki EPG często są spakowane (.xml.gz) — wykryj po nagłówku.
            input.mark(2)
            val b1 = input.read(); val b2 = input.read()
            input.reset()
            if (b1 == 0x1F && b2 == 0x8B) input = GZIPInputStream(input)
            parseXml(input)
        }
        byId.size
    }

    private fun parseXml(input: InputStream) {
        val now = System.currentTimeMillis()
        val minStop = now - 30 * 60_000L
        val maxStart = now + 26 * 3_600_000L
        val fmtZone = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmtPlain = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

        fun parseTime(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            val v = s.trim()
            return runCatching {
                if (v.contains(' ')) fmtZone.parse(v)!!.time
                else fmtPlain.parse(v.take(14))!!.time
            }.getOrNull()
        }

        val progs = HashMap<String, MutableList<Prog>>()
        val names = HashMap<String, String>()
        val parser = Xml.newPullParser()
        parser.setInput(input, null)

        var inChannel = false
        var curChannelId: String? = null
        var inDisplayName = false
        var nameBuf = StringBuilder()
        var inProgramme = false
        var progChannel: String? = null
        var progStart: Long? = null
        var progStop: Long? = null
        var inTitle = false
        var titleBuf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        inChannel = true
                        curChannelId = parser.getAttributeValue(null, "id")
                    }
                    "display-name" -> if (inChannel) {
                        inDisplayName = true; nameBuf = StringBuilder()
                    }
                    "programme" -> {
                        inProgramme = true
                        progChannel = parser.getAttributeValue(null, "channel")
                        progStart = parseTime(parser.getAttributeValue(null, "start"))
                        progStop = parseTime(parser.getAttributeValue(null, "stop"))
                        titleBuf = StringBuilder()
                    }
                    "title" -> if (inProgramme) {
                        inTitle = true; titleBuf = StringBuilder()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTitle) titleBuf.append(parser.text)
                    if (inDisplayName) nameBuf.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "display-name" -> if (inDisplayName) {
                        inDisplayName = false
                        val n = nameBuf.toString().trim().lowercase()
                        val id = curChannelId
                        if (n.isNotBlank() && id != null && n !in names) names[n] = id
                    }
                    "channel" -> { inChannel = false; curChannelId = null }
                    "title" -> inTitle = false
                    "programme" -> {
                        inProgramme = false
                        val id = progChannel; val st = progStart; val sp = progStop
                        if (id != null && st != null && sp != null
                            && sp >= minStop && st <= maxStart) {
                            val title = titleBuf.toString().trim()
                            if (title.isNotBlank())
                                progs.getOrPut(id) { mutableListOf() }.add(Prog(st, sp, title))
                        }
                    }
                }
            }
            event = parser.next()
        }
        progs.values.forEach { list -> list.sortBy { it.start } }
        byId = progs
        idByName = names
    }

    private fun idFor(ch: Channel): String? {
        if (ch.tvgId.isNotBlank() && byId.containsKey(ch.tvgId)) return ch.tvgId
        return idByName[ch.name.trim().lowercase()]
    }

    /** Para (teraz, następny) — tytuły programów albo null. */
    fun nowNext(ch: Channel): Pair<String?, String?> {
        val list = byId[idFor(ch) ?: return null to null] ?: return null to null
        val now = System.currentTimeMillis()
        val i = list.indexOfFirst { now < it.stop }
        if (i < 0) return null to null
        val current = list[i].takeIf { it.start <= now }
        val next = if (current != null) list.getOrNull(i + 1) else list[i]
        return current?.title to next?.title
    }
}
