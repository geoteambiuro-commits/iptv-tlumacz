package pl.iptvtlumacz

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object CloudApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Rozpoznawanie mowy. Dostawca wykrywany po kluczu:
     *  - "gsk_..." → Groq (darmowy tier, whisper-large-v3-turbo)
     *  - "sk-..."  → OpenAI (płatny, whisper-1)
     */
    fun transcribe(wav: ByteArray, language: String?, apiKey: String): Result<String> {
        val groq = apiKey.startsWith("gsk_")
        val url = if (groq) "https://api.groq.com/openai/v1/audio/transcriptions"
                  else "https://api.openai.com/v1/audio/transcriptions"
        val model = if (groq) "whisper-large-v3-turbo" else "whisper-1"
        return try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "json")
                .apply { if (language != null) addFormDataPart("language", language) }
                .addFormDataPart(
                    "file", "chunk.wav",
                    wav.toRequestBody("audio/wav".toMediaType())
                )
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                if (!resp.isSuccessful)
                    return Result.failure(Exception("Whisper HTTP ${resp.code}: ${txt.take(120)}"))
                Result.success(JSONObject(txt).optString("text").trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Tłumaczenie na polski: Claude API (Haiku).
     * history — poprzednie pary (oryginał → polski) jako kontekst rozmowy,
     * dzięki czemu tłumaczenie zachowuje spójność (zaimki, terminologia).
     */
    fun translate(
        text: String, srcLang: String, apiKey: String,
        history: List<Pair<String, String>> = emptyList(),
    ): Result<String> {
        return try {
            val srcName = LANG_NAMES[srcLang] ?: "obcy"
            val messages = JSONArray()
            for ((orig, pl) in history) {
                messages.put(JSONObject().put("role", "user").put("content", orig))
                messages.put(JSONObject().put("role", "assistant").put("content", pl))
            }
            messages.put(JSONObject().put("role", "user").put("content", text))
            val payload = JSONObject()
                .put("model", "claude-haiku-4-5")
                .put("max_tokens", 300)
                .put(
                    "system",
                    "Jesteś tłumaczem napisów telewizyjnych na żywo. Każda wiadomość użytkownika " +
                    "to kolejna kwestia z tej samej audycji w języku ($srcName). Tłumacz każdą " +
                    "na polski, naturalnie i zwięźle, zachowując spójność z poprzednimi kwestiami. " +
                    "Zwracaj WYŁĄCZNIE tłumaczenie, bez komentarzy i cudzysłowów."
                )
                .put("messages", messages)
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                if (!resp.isSuccessful)
                    return Result.failure(Exception("Claude HTTP ${resp.code}: ${txt.take(120)}"))
                val content = JSONObject(txt).optJSONArray("content") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until content.length())
                    sb.append(content.getJSONObject(i).optString("text"))
                Result.success(sb.toString().trim().trim('"', '„', '”'))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Pobranie playlisty M3U z adresu URL. */
    fun fetchText(url: String): Result<String> {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (IPTV-Tlumacz-Android)")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful)
                    return Result.failure(Exception("HTTP ${resp.code}"))
                Result.success(resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/** PCM 16-bit mono → plik WAV w pamięci. */
fun pcmToWav(samples: ShortArray, sampleRate: Int): ByteArray {
    val dataSize = samples.size * 2
    val out = ByteArrayOutputStream(44 + dataSize)
    fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    fun le32(v: Int) { le16(v and 0xFFFF); le16((v ushr 16) and 0xFFFF) }
    out.write("RIFF".toByteArray()); le32(36 + dataSize)
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); le32(16)
    le16(1)                    // PCM
    le16(1)                    // mono
    le32(sampleRate)
    le32(sampleRate * 2)       // byte rate
    le16(2)                    // block align
    le16(16)                   // bity na próbkę
    out.write("data".toByteArray()); le32(dataSize)
    for (s in samples) le16(s.toInt())
    return out.toByteArray()
}
