package pl.iptvtlumacz

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Przesyłanie ustawień (klucze API + playlista) między urządzeniami
 * w tej samej sieci Wi-Fi — telefon wysyła, telewizor odbiera.
 * Prosty jednorazowy serwer HTTP na porcie 8765/8766.
 */
object SettingsSync {

    data class Payload(val m3u: String, val openai: String, val anthropic: String)

    @Volatile private var server: ServerSocket? = null

    /** Lokalny adres IPv4 urządzenia w sieci domowej. */
    fun localIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address
                        && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) { null }
    }

    /**
     * Tryb odbiornika (na TV). Zwraca port nasłuchu albo null.
     * onReceived wywoływane z wątku roboczego.
     */
    fun startReceiver(onReceived: (Payload) -> Unit): Int? {
        stop()
        val socket = try { ServerSocket(8765) } catch (e: Exception) {
            try { ServerSocket(8766) } catch (e2: Exception) { return null }
        }
        server = socket
        Thread {
            try {
                while (server != null) {
                    val client = socket.accept()
                    client.soTimeout = 10_000
                    try {
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true))
                                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                        val body = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = reader.read(body, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                        val json = JSONObject(String(body, 0, read))
                        val payload = Payload(
                            m3u = json.optString("m3u"),
                            openai = json.optString("openai"),
                            anthropic = json.optString("anthropic"),
                        )
                        val resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                        client.getOutputStream().write(resp.toByteArray())
                        client.getOutputStream().flush()
                        if (payload.openai.isNotBlank() || payload.anthropic.isNotBlank()
                            || payload.m3u.isNotBlank()) {
                            onReceived(payload)
                        }
                    } catch (e: Exception) {
                        // zignoruj błędne połączenie, czekaj na kolejne
                    } finally {
                        runCatching { client.close() }
                    }
                }
            } catch (e: Exception) {
                // socket zamknięty przez stop()
            }
        }.apply { isDaemon = true }.start()
        return socket.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** Wysłanie ustawień (z telefonu) na podany adres TV. */
    fun send(ip: String, m3u: String, openai: String, anthropic: String): Result<Unit> {
        return runCatching {
            val body = JSONObject()
                .put("m3u", m3u)
                .put("openai", openai)
                .put("anthropic", anthropic)
                .toString()
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val target = ip.trim().removePrefix("http://").substringBefore("/")
            val url = if (":" in target) "http://$target/" else "http://$target:8765/"
            val req = Request.Builder().url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            }
        }
    }
}
