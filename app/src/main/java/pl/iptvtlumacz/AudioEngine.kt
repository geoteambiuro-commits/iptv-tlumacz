package pl.iptvtlumacz

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class EngineConfig(
    val lang: String,          // "auto" lub kod języka
    val openaiKey: String,
    val anthropicKey: String,
)

/**
 * Odtwarzacz z podsłuchem audio:
 * ExoPlayer → TeeAudioProcessor → porcje PCM (6 s) → mono 16 kHz
 * → Whisper API (tekst) → Claude API (polski) → napisy na ekranie.
 */
@OptIn(UnstableApi::class)
class AudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: () -> EngineConfig,
    private val onSubtitle: (orig: String, pl: String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        const val CHUNK_SECONDS = 6
        const val TARGET_RATE = 16000
    }

    private var inRate = 48000
    private var inChannels = 2
    private var pcm16 = true
    private val pending = ByteArrayOutputStream()

    // Kolejka porcji mono 16 kHz; gdy sieć nie nadąża, najstarsze wypadają.
    private val chunks = Channel<ShortArray>(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var worker: Job? = null

    private val sink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            inRate = sampleRateHz
            inChannels = channelCount
            pcm16 = encoding == C.ENCODING_PCM_16BIT
            synchronized(pending) { pending.reset() }
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!pcm16) return
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            var full: ByteArray? = null
            val need = inRate * inChannels * 2 * CHUNK_SECONDS
            synchronized(pending) {
                pending.write(bytes)
                if (pending.size() >= need) {
                    full = pending.toByteArray()
                    pending.reset()
                }
            }
            full?.let { chunks.trySend(toMono16k(it, inRate, inChannels)) }
        }
    }

    fun buildPlayer(): ExoPlayer {
        val factory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(sink)))
                .build()
        }
        return ExoPlayer.Builder(context, factory).build()
    }

    fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.IO) {
            for (mono in chunks) process(mono)
        }
    }

    fun stop() {
        worker?.cancel()
        worker = null
        history.clear()
        synchronized(pending) { pending.reset() }
        while (chunks.tryReceive().isSuccess) { /* opróżnij */ }
    }

    // Ostatnie pary (oryginał → polski) jako kontekst dla tłumacza.
    private val history = ArrayDeque<Pair<String, String>>()

    private fun <T> withRetry(block: () -> Result<T>): Result<T> {
        val first = block()
        if (first.isSuccess) return first
        val msg = first.exceptionOrNull()?.message ?: ""
        if ("429" in msg) return first          // limit — ponawianie nie pomoże
        Thread.sleep(1200)
        return block()
    }

    private fun process(mono: ShortArray) {
        // Pomiń ciszę — oszczędza wywołania API.
        var acc = 0L
        for (s in mono) acc += abs(s.toInt())
        if (acc / mono.size < 130) return

        val cfg = config()
        if (cfg.openaiKey.isBlank()) {
            onStatus("Uzupełnij klucz Groq lub OpenAI (mowa) w ustawieniach")
            return
        }

        val wav = pcmToWav(mono, TARGET_RATE)
        val lang = cfg.lang.takeIf { it != "auto" }
        val text = withRetry { CloudApi.transcribe(wav, lang, cfg.openaiKey) }.getOrElse {
            val m = it.message ?: "Błąd rozpoznawania mowy"
            onStatus(if ("429" in m) "Limit darmowego Groq chwilowo wyczerpany — napisy wrócą za parę minut" else m)
            return
        }
        if (text.length < 2 || text.lowercase() in HALLUCINATIONS) return

        val polish = if (cfg.anthropicKey.isBlank()) {
            onStatus("Uzupełnij klucz Claude w ustawieniach — pokazuję oryginał")
            null
        } else {
            withRetry {
                CloudApi.translate(text, cfg.lang, cfg.anthropicKey, history.toList())
            }.getOrElse {
                onStatus(it.message ?: "Błąd tłumaczenia"); null
            }
        }
        if (polish != null) {
            history.addLast(text to polish)
            while (history.size > 3) history.removeFirst()
        }
        onSubtitle(text, polish ?: text)
    }
}

/** PCM 16-bit (przeplot kanałów) → mono 16 kHz. */
fun toMono16k(bytes: ByteArray, rate: Int, channels: Int): ShortArray {
    val src = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val frames = src.remaining() / channels
    val mono = ShortArray(frames)
    for (i in 0 until frames) {
        var sum = 0
        for (c in 0 until channels) sum += src.get(i * channels + c).toInt()
        mono[i] = (sum / channels).toShort()
    }
    if (rate == 16000) return mono
    val outLen = (frames.toLong() * 16000 / rate).toInt()
    val out = ShortArray(outLen)
    val step = rate.toDouble() / 16000.0
    var pos = 0.0
    for (i in 0 until outLen) {
        out[i] = mono[pos.toInt().coerceAtMost(frames - 1)]
        pos += step
    }
    return out
}
