package app.claudevoice.core

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live-Transkription über die ElevenLabs-Realtime-Schnittstelle (WebSocket).
 *
 * Wir schicken laufend kleine PCM-Stücke (16 kHz, mono, 16 Bit). Der Server erkennt Sprechpausen
 * selbst (commit_strategy=vad) und liefert zwei Arten von Text:
 *  - partial_transcript: Zwischenstand, ändert sich noch
 *  - committed_transcript: fertige Äußerung, ändert sich nicht mehr
 * Bricht die Verbindung ab, wird mit wachsender Wartezeit neu verbunden.
 */
class ScribeClient(
    private val apiKey: String,
    private val http: OkHttpClient,
    private val listener: Listener,
    private val baseUrl: String = "wss://api.elevenlabs.io",
) {
    interface Listener {
        fun onPartial(text: String)
        fun onCommitted(text: String)
        fun onConnection(connected: Boolean, error: String?)
    }

    private val json = ObjectMapper()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var closed = false
    private var attempt = 0

    fun connect() {
        if (closed) return
        val request = Request.Builder()
            .url("$baseUrl/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&audio_format=pcm_16000&commit_strategy=vad")
            .header("xi-api-key", apiKey)
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                dropped(if (code == 1000) null else "Verbindung beendet ($code $reason)")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                dropped(
                    when (response?.code) {
                        401, 403 -> "ElevenLabs-Key wurde abgelehnt (${response.code})"
                        null -> "Keine Verbindung zur Transkription: ${t.message ?: t.javaClass.simpleName}"
                        else -> "Transkription antwortet mit ${response.code}"
                    }
                )
        })
    }

    /** Ein Stück Mikrofon-Audio senden. Solange keine Sitzung steht, wird es verworfen. */
    fun sendAudio(pcm: ByteArray, length: Int) {
        if (!ready) return
        val node = json.createObjectNode()
            .put("message_type", "input_audio_chunk")
            .put("audio_base_64", Base64.getEncoder().encodeToString(pcm.copyOf(length)))
            .put("commit", false)
            .put("sample_rate", 16000)
        socket?.send(json.writeValueAsString(node))
    }

    fun close() {
        closed = true
        ready = false
        socket?.close(1000, null)
        scheduler.shutdownNow()
    }

    private fun handle(raw: String) {
        val msg = try { json.readTree(raw) } catch (e: Exception) { return }
        val type = msg.path("message_type").asText("")
        when {
            type == "session_started" -> {
                ready = true
                attempt = 0
                listener.onConnection(true, null)
            }
            type == "partial_transcript" -> listener.onPartial(msg.path("text").asText(""))
            type == "committed_transcript" -> listener.onCommitted(msg.path("text").asText(""))
            type.contains("error", ignoreCase = true) ->
                listener.onConnection(ready, "Transkription meldet: ${msg.path("error").asText(type)}")
        }
    }

    private fun dropped(error: String?) {
        ready = false
        if (closed) return
        listener.onConnection(false, error)
        // Wartezeit 1 s, 2 s, 4 s ... höchstens 15 s, damit ein Funkloch nicht zur Dauerschleife wird.
        val delaySec = minOf(15L, 1L shl minOf(attempt, 4))
        attempt++
        try {
            scheduler.schedule({ connect() }, delaySec, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // close() kam dazwischen
        }
    }
}
