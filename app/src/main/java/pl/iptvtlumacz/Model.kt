package pl.iptvtlumacz

import android.content.Context

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String,
    val lang: String,   // "auto" lub kod ISO: de/it/en/fr/es...
)

object M3U {

    private val EXTINF = Regex("""#EXTINF:(-?\d+(?:\.\d+)?)\s*(.*?),(.*)$""")
    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

    private val LANG_HINTS = mapOf(
        "de" to listOf("germany", "deutsch", "german", "|de|", " de:", "ard", "zdf", "rtl", "prosieben", "sat.1"),
        "it" to listOf("italy", "italia", "italian", "|it|", " it:", "rai", "mediaset", "canale 5"),
        "en" to listOf("uk", "usa", "united kingdom", "english", "|en|", "bbc", "itv", "sky news", "cnn"),
        "fr" to listOf("france", "french", "|fr|", " fr:", "tf1", "canal+", "m6"),
        "es" to listOf("spain", "españa", "spanish", "|es|", " es:", "antena 3", "telecinco", "la sexta", "tve"),
    )

    fun guessLang(name: String, group: String): String {
        val hay = "$name $group".lowercase()
        for ((lang, hints) in LANG_HINTS)
            if (hints.any { hay.contains(it) }) return lang
        return "auto"
    }

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF") -> {
                    EXTINF.find(line)?.let { m ->
                        attrs = ATTR.findAll(m.groupValues[2])
                            .associate { it.groupValues[1] to it.groupValues[2] }
                        name = m.groupValues[3].trim()
                    }
                }
                line.startsWith("#") -> {}
                name != null -> {
                    val group = attrs["group-title"] ?: ""
                    out += Channel(
                        name = name!!,
                        url = line,
                        group = group,
                        logo = attrs["tvg-logo"] ?: "",
                        lang = guessLang(name!!, group),
                    )
                    name = null; attrs = emptyMap()
                }
            }
        }
        return out
    }
}

/** Nazwy języków do promptu tłumaczenia. */
val LANG_NAMES = mapOf(
    "de" to "niemiecki", "it" to "włoski", "en" to "angielski",
    "fr" to "francuski", "es" to "hiszpański", "pt" to "portugalski",
    "nl" to "niderlandzki", "tr" to "turecki", "ru" to "rosyjski",
    "uk" to "ukraiński", "cs" to "czeski", "sk" to "słowacki",
)

/** Typowe halucynacje Whispera przy ciszy lub muzyce. */
val HALLUCINATIONS = setOf(
    "thank you.", "thanks for watching.", "thank you for watching.",
    "dziękuję.", "dziękuję za oglądanie.", "...",
    "untertitel im auftrag des zdf für funk, 2017",
    "sottotitoli creati dalla comunità amara.org",
    "sous-titres réalisés para la communauté d'amara.org",
    "subtítulos realizados por la comunidad de amara.org",
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    var m3uUrl: String
        get() = sp.getString("m3u", "") ?: ""
        set(v) = sp.edit().putString("m3u", v).apply()

    var openaiKey: String
        get() = sp.getString("openai", "") ?: ""
        set(v) = sp.edit().putString("openai", v).apply()

    var anthropicKey: String
        get() = sp.getString("anthropic", "") ?: ""
        set(v) = sp.edit().putString("anthropic", v).apply()

    var lang: String
        get() = sp.getString("lang", "auto") ?: "auto"
        set(v) = sp.edit().putString("lang", v).apply()

    var showOrig: Boolean
        get() = sp.getBoolean("showOrig", true)
        set(v) = sp.edit().putBoolean("showOrig", v).apply()

    var favorites: Set<String>
        get() = sp.getStringSet("favs", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("favs", HashSet(v)).apply()

    /** Rozmiar polskiej linii napisów w sp (16–32). */
    var subSize: Int
        get() = sp.getInt("subSize", 21)
        set(v) = sp.edit().putInt("subSize", v).apply()

    /** Pozycja napisów: 0 nisko, 1 średnio, 2 wysoko. */
    var subPos: Int
        get() = sp.getInt("subPos", 0)
        set(v) = sp.edit().putInt("subPos", v).apply()

    /** Ciemne tło pod napisami. */
    var subBg: Boolean
        get() = sp.getBoolean("subBg", true)
        set(v) = sp.edit().putBoolean("subBg", v).apply()

    /** Zapis ostatniej awarii (czarna skrzynka). */
    var lastCrash: String
        get() = sp.getString("lastCrash", "") ?: ""
        set(v) = sp.edit().putString("lastCrash", v).commit().let { }
}
