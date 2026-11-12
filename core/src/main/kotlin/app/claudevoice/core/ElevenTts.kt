package app.claudevoice.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sprachausgabe über ElevenLabs. Geliefert wird rohes PCM (24 kHz, mono, 16 Bit), das der Aufrufer
 * direkt abspielen kann, noch während es eintrifft.
 *
 * Schneller Weg: EIN WebSocket für alle Antworten ("multi-stream-input").
 *  - Früher war jeder Satz eine eigene HTTP-Anfrage, und die zweite ging erst los, wenn die erste fertig
 *    übertragen war. Das kostete pro Satz eine Anlaufzeit und ergab hörbare Lücken zwischen den Sätzen.
 *  - Jetzt bleibt eine Verbindung offen. Jede Antwort ist darin ein "Kontext" mit eigener Nummer. Jeder
 *    Satz geht sofort hinaus, sobald Claude ihn geschrieben hat. Der Server spricht die Sätze eines
 *    Kontexts in einem Zug und schickt die Audiostücke mit der Kontextnummer zurück.
 *  - Wird eine Antwort abgebrochen, schließen wir nur ihren Kontext. Die Verbindung bleibt für die
 *    nächste Antwort stehen.
 *
 * Sicherer Weg: Klappt der WebSocket nicht (kein Aufbau, kein Ton), läuft dieselbe Antwort über die
 * bisherigen HTTP-Anfragen. So wird aus einem Protokollfehler eine etwas langsamere Antwort statt Stille.
 */
class ElevenTts(
    private val apiKey: String,
    private val http: OkHttpClient,
    private val voiceId: String,
    private val baseUrl: String = "https://api.elevenlabs.io",
) : SpeechSynth {
    private val json = ObjectMapper()
    private val contextNumbers = AtomicInteger()
    private val lock = Any()
    private var current: Link? = null // nur unter [lock] anfassen
    @Volatile private var closed = false
    @Volatile private var pausedAtNanos: Long? = null

    override val sampleRate: Int = SAMPLE_RATE

    /**
     * Nur zur Diagnose: wird aufgerufen, wenn eine Antwort nicht (oder nicht sauber) über den WebSocket lief.
     * Der Text nennt den Grund, nie den Inhalt der Antwort.
     */
    @Volatile var onTrouble: (String) -> Unit = {}

    // Geduld in Millisekunden. "internal", damit die Tests sie verkürzen können.
    /** So lange darf nach dem ersten Satz kein Ton kommen, bevor wir auf HTTP ausweichen. */
    internal var firstAudioTimeoutMs = 3_000L
    /** So lange warten wir nach dem letzten Audiostück noch auf die Schlussmarke. */
    internal var idleTimeoutMs = 4_000L
    /** So lange bleibt der WebSocket nach einem Ausfall links liegen. */
    internal var pauseAfterFailureMs = 60_000L

    /**
     * Öffnet die Verbindung schon vorab und kehrt sofort zurück (wartet nicht auf den Aufbau).
     * Warum: Der Aufbau (TLS + WebSocket) dauert ein paar Zehntelsekunden. Passiert er, während noch
     * jemand spricht oder Claude nachdenkt, kostet er die Antwort keine Zeit. Mehrfach aufrufen ist harmlos.
     */
    fun warmUp() {
        connection()
    }

    /** Verbindung schließen, z. B. wenn der Dienst endet. Danach wird keine neue mehr geöffnet. */
    fun close() {
        val last = synchronized(lock) {
            closed = true
            current.also { current = null }
        }
        last?.shutdown()
    }

    override suspend fun synthesize(sentences: ReceiveChannel<String>, onPcm: suspend (ByteArray, Int) -> Unit) {
        // Sätze dieser Antwort, die schon aus dem Kanal genommen wurden. Sie liegen nur im Arbeitsspeicher
        // und nur, damit beim Ausweichen auf HTTP kein Satz verloren geht.
        val taken = ArrayList<String>()
        // Schon hier öffnen (falls nötig): Claude schreibt noch am ersten Satz, bis dahin steht die Leitung.
        var attempt = connection()
        while (attempt != null) {
            val (link, older) = attempt
            try {
                overSocket(link, sentences, taken, onPcm)
                return
            } catch (e: SocketTrouble) {
                // Bis hierher war noch nichts zu hören, wir dürfen also von vorn ansetzen.
                link.drop(e.reason)
                // Eine Verbindung, die schon vor dieser Antwort bestand, kann der Server inzwischen
                // geschlossen haben (das tut er nach 180 s Ruhe). Dann genau ein zweiter Versuch mit einer
                // frischen. Scheitert auch die frische, liegt es nicht am Alter.
                attempt = if (older) connection()?.let { it.first to false } else null
                if (attempt == null) {
                    // Eine Weile direkt HTTP nehmen, statt bei jeder Antwort erneut auf den WebSocket zu warten.
                    pausedAtNanos = System.nanoTime()
                    onTrouble("WebSocket-Sprachausgabe ausgefallen (${e.reason}), nutze HTTP")
                }
            }
        }
        overHttp(taken, sentences, onPcm)
    }

    // ------------------------------------------------------------------------------------------
    // Schneller Weg: WebSocket

    /**
     * Eine Antwort über den WebSocket. Kehrt zurück, wenn die Schlussmarke da ist und alles Audio
     * ausgeliefert wurde. Wirft [SocketTrouble] nur, solange noch nichts zu hören war.
     *
     * Zwei Dinge laufen nebeneinander: Die Schleife unten schickt Sätze hinaus und nimmt Audiostücke
     * entgegen; der "Spieler" reicht die Stücke an [onPcm] weiter. Getrennt, weil [onPcm] bremsen darf
     * (der Lautsprecher nimmt nur so viel an, wie er abspielt) und der nächste Satz trotzdem sofort
     * zum Server soll.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // onTimeout in select: seit Jahren unverändert, nur noch so markiert
    private suspend fun overSocket(
        link: Link,
        sentences: ReceiveChannel<String>,
        taken: MutableList<String>,
        onPcm: suspend (ByteArray, Int) -> Unit,
    ): Unit = coroutineScope {
        val id = "a${contextNumbers.incrementAndGet()}"
        val frames = link.open(id)
        val ready = Channel<ByteArray>(Channel.UNLIMITED)
        val player = launch { for (pcm in ready) onPcm(pcm, pcm.size) }

        var begun = false     // Kontext beim Server angelegt (erster Satz ist draußen)
        var begunAt = 0L
        var gotAudio = false  // mindestens ein Audiostück ist angekommen
        var inputOpen = true  // Claude schreibt noch
        var closing = false   // wir haben den Kontext geschlossen und warten nur noch auf den Rest

        fun say(sentence: String) {
            if (!begun) {
                begun = true
                begunAt = System.nanoTime()
                // Ein einzelnes Leerzeichen legt den Kontext an (so verlangt es das Protokoll).
                link.send(json.createObjectNode().put("text", " ").put("context_id", id))
            }
            // Leerzeichen am Ende: so erkennt der Server das Satzende. flush = "nicht auf mehr Text warten,
            // sofort sprechen".
            link.send(json.createObjectNode().put("text", "$sentence ").put("context_id", id).put("flush", true))
        }

        val trouble: SocketTrouble? = try {
            taken.toList().forEach(::say) // zweiter Versuch: bereits entnommene Sätze noch einmal schicken
            var finished = false
            while (!finished) {
                val patience: Long? = when {
                    begun && !gotAudio -> (firstAudioTimeoutMs - (System.nanoTime() - begunAt) / 1_000_000).coerceAtLeast(0)
                    closing -> idleTimeoutMs
                    else -> null // Claude schreibt noch: beliebig lange auf den nächsten Satz warten
                }
                // select = "warte auf das, was zuerst passiert": neuer Satz, neues Audiostück oder Zeit um.
                finished = select {
                    if (inputOpen) sentences.onReceiveCatching { next ->
                        val sentence = next.getOrNull()?.trim()
                        when {
                            sentence == null -> {
                                next.exceptionOrNull()?.let { throw it }
                                inputOpen = false
                                // Kein einziger Satz (Claude hat sich gegen eine Antwort entschieden): nichts zu tun.
                                if (!begun) true
                                else {
                                    // Antwort ist vollständig: "sprich alles zu Ende" (flush), dann Kontext schließen.
                                    // Der Server liefert den Rest noch aus und meldet sich mit der Schlussmarke.
                                    closing = true
                                    link.send(json.createObjectNode().put("context_id", id).put("flush", true))
                                    link.send(closeContext(id))
                                    false
                                }
                            }
                            sentence.isEmpty() -> false
                            else -> {
                                taken += sentence
                                say(sentence)
                                false
                            }
                        }
                    }
                    frames.onReceiveCatching { got ->
                        when (val frame = got.getOrNull()) {
                            is Frame.Pcm -> {
                                gotAudio = true
                                ready.trySend(frame.bytes)
                                false
                            }
                            // Erst die Schlussmarke sagt, dass wirklich alles da ist.
                            is Frame.Final -> closing
                            null -> throw (got.exceptionOrNull() as? SocketTrouble) ?: SocketTrouble("Verbindung verloren")
                        }
                    }
                    if (patience != null) onTimeout(patience) {
                        if (!gotAudio) throw SocketTrouble("kein Ton nach $firstAudioTimeoutMs ms")
                        // Ton kam, nur die Schlussmarke nicht: nicht ewig hängen bleiben.
                        onTrouble("Schlussmarke der Sprachausgabe blieb aus")
                        true
                    }
                }
            }
            null
        } catch (e: SocketTrouble) {
            e
        } finally {
            // Läuft auch beim Abbruch (z. B. "Stopp"): ab jetzt wird nichts mehr von diesem Kontext angenommen,
            // und der Server soll sofort aufhören, daran zu arbeiten.
            link.forget(id)
            if (begun && !closing) link.sendIfAlive(closeContext(id))
        }

        if (trouble != null && !gotAudio) throw trouble
        ready.close() // nichts kommt mehr nach; der Spieler liefert den Rest noch aus
        if (trouble != null) {
            if (closing) {
                // Alle Sätze waren draußen und Ton kam an, nur die Schlussmarke fehlt. Mehr ist nicht zu holen,
                // und ein Fehler würde eine (vermutlich vollständige) Antwort als gescheitert melden.
                onTrouble("Verbindung endete vor der Schlussmarke (${trouble.reason})")
            } else {
                // Mitten in der Antwort abgerissen, Claude schreibt noch. Von vorn anfangen würde den Anfang
                // doppelt sprechen, also ehrlich abbrechen (so verhielt sich auch der HTTP-Weg).
                player.join()
                throw IOException("Sprachausgabe unterbrochen: ${trouble.reason}")
            }
        }
    }

    private fun closeContext(id: String): ObjectNode =
        json.createObjectNode().put("context_id", id).put("close_context", true)

    /**
     * Liefert die bestehende Verbindung oder beginnt eine neue (ohne auf den Aufbau zu warten).
     * Zweiter Wert: true = es gab sie schon. null = der WebSocket soll gerade nicht benutzt werden
     * (geschlossen, oder nach einem Ausfall in der Pause).
     */
    private fun connection(): Pair<Link, Boolean>? = synchronized(lock) {
        val pausedAt = pausedAtNanos
        val paused = pausedAt != null && (System.nanoTime() - pausedAt) / 1_000_000 < pauseAfterFailureMs
        val open = current
        when {
            closed || paused -> null
            open != null && open.alive -> open to true
            else -> Link().also { current = it } to false
        }
    }

    private fun socketRequest(): Request = Request.Builder()
        .url(
            // Aus https:// wird wss://, aus http:// (in den Tests) ws://.
            baseUrl.replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") +
                "/v1/text-to-speech/$voiceId/multi-stream-input" +
                // auto_mode: der Server sammelt nicht erst Text, sondern spricht jeden Satz sofort.
                // inactivity_timeout: erst nach 180 s Ruhe trennen (Vorgabe wären 20 s).
                // Keine Sprache vorgegeben: Antworten können deutsch oder englisch sein.
                "?model_id=$MODEL&output_format=pcm_$SAMPLE_RATE&inactivity_timeout=$INACTIVITY_SEC&auto_mode=true"
        )
        .header("xi-api-key", apiKey)
        .build()

    /** Ein Audiostück oder die Schlussmarke eines Kontexts. */
    private sealed interface Frame {
        class Pcm(val bytes: ByteArray) : Frame
        object Final : Frame
    }

    /**
     * "Der WebSocket hat für diese Antwort nicht funktioniert." Kein Absturzgrund, sondern das Signal,
     * es anders zu versuchen. Ohne Stacktrace, weil es nie angezeigt wird.
     */
    private class SocketTrouble(val reason: String) : Exception(reason, null, false, false)

    /** Eine WebSocket-Verbindung. Sie verteilt eintreffende Audiostücke an die Antwort, zu der sie gehören. */
    private inner class Link {
        /** Kontextnummer -> Briefkasten der Antwort, die darauf wartet. */
        private val contexts = ConcurrentHashMap<String, Channel<Frame>>()
        @Volatile private var lost: String? = null // Grund, sobald die Verbindung hinüber ist
        @Volatile private var opened = false
        val alive: Boolean get() = lost == null

        // Steht bewusst als Letztes: Sobald newWebSocket läuft, können die Rückrufe schon eintreffen,
        // und die brauchen die Felder darüber. newWebSocket selbst wartet nicht auf den Aufbau.
        private val socket: WebSocket = http.newWebSocket(socketRequest(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) = receive(webSocket, text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                lose("Server hat die Verbindung beendet ($code)")
                webSocket.close(1000, null) // Abschied bestätigen, sonst bleibt die Leitung halb offen
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lose("Verbindung beendet ($code)")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Schon der Aufbau scheitert (kein Netz, Key abgelehnt): nicht bei jedem warmUp() neu anrennen.
                if (!opened) pausedAtNanos = System.nanoTime()
                lose(
                    when (response?.code) {
                        401, 403 -> "ElevenLabs-Key wurde abgelehnt (${response.code})"
                        null -> "keine Verbindung: ${t.message ?: t.javaClass.simpleName}"
                        else -> "Server antwortet mit ${response.code}"
                    }
                )
            }
        })

        fun open(id: String): Channel<Frame> {
            val frames = Channel<Frame>(Channel.UNLIMITED)
            contexts[id] = frames
            lost?.let { frames.close(SocketTrouble(it)) } // war schon vorher weg: sofort Bescheid geben
            return frames
        }

        fun forget(id: String) {
            contexts.remove(id)
        }

        /** Nachricht senden. Solange die Verbindung noch aufgebaut wird, hebt OkHttp sie auf und schickt sie danach. */
        fun send(message: ObjectNode) {
            lost?.let { throw SocketTrouble(it) }
            if (!socket.send(json.writeValueAsString(message))) throw SocketTrouble(lost ?: "Nachricht wurde nicht angenommen")
        }

        fun sendIfAlive(message: ObjectNode) {
            if (alive) socket.send(json.writeValueAsString(message))
        }

        /** Verbindung taugt nicht mehr: sofort kappen, die nächste Antwort bekommt eine neue. */
        fun drop(reason: String) {
            lose(reason)
            socket.cancel()
        }

        fun shutdown() {
            sendIfAlive(json.createObjectNode().put("close_socket", true))
            lose("geschlossen")
            socket.close(1000, null)
        }

        private fun receive(webSocket: WebSocket, raw: String) {
            val msg = try { json.readTree(raw) } catch (e: Exception) { return }
            // Die Schnittstellen-Beschreibung schreibt contextId/isFinal, ihr Beispielcode is_final.
            // Wir nehmen beide Schreibweisen an.
            val id = (msg.get("contextId") ?: msg.get("context_id"))?.asText()
            // Kein Briefkasten heißt: Antwort abgebrochen oder schon fertig. Nachzügler werden verworfen,
            // sonst wäre der Rest der alten Antwort in der neuen zu hören.
            val frames = id?.let { contexts[it] }
            val audio = msg.get("audio")?.takeIf { it.isTextual }?.textValue()
            if (frames != null && !audio.isNullOrEmpty()) {
                val pcm = try { Base64.getDecoder().decode(audio) } catch (e: IllegalArgumentException) { null }
                if (pcm != null && pcm.isNotEmpty()) frames.trySend(Frame.Pcm(pcm))
            }
            if (msg.path("isFinal").asBoolean(false) || msg.path("is_final").asBoolean(false)) {
                frames?.trySend(Frame.Final)
            } else if (msg.hasNonNull("error")) {
                // Fehlermeldung des Servers (z. B. Zeitüberschreitung). Danach trennt er ohnehin.
                lose("Server meldet: ${msg.path("error").asText("Fehler")}")
                webSocket.close(1000, null)
            }
        }

        /** Verbindung ist hinüber: Alle wartenden Antworten erfahren es über ihren Briefkasten. */
        private fun lose(reason: String) {
            if (lost == null) lost = reason
            val why = SocketTrouble(lost ?: reason)
            for (frames in contexts.values) frames.close(why)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sicherer Weg: eine HTTP-Anfrage pro Satz

    private suspend fun overHttp(
        taken: List<String>,
        sentences: ReceiveChannel<String>,
        onPcm: suspend (ByteArray, Int) -> Unit,
    ) {
        val buffer = ByteArray(4800)
        for (sentence in taken) sayOverHttp(sentence, buffer, onPcm)
        for (sentence in sentences) if (sentence.isNotBlank()) sayOverHttp(sentence.trim(), buffer, onPcm)
    }

    private suspend fun sayOverHttp(sentence: String, buffer: ByteArray, onPcm: suspend (ByteArray, Int) -> Unit) {
        val response = withContext(Dispatchers.IO) { runInterruptible { post(sentence) } }
        response.use {
            val input = it.body!!.byteStream()
            while (true) {
                val n = withContext(Dispatchers.IO) { runInterruptible { input.read(buffer) } }
                if (n < 0) break
                if (n > 0) onPcm(buffer, n)
            }
        }
    }

    private fun post(text: String): Response {
        val body = json.writeValueAsString(
            json.createObjectNode().put("text", text).put("model_id", MODEL)
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/v1/text-to-speech/$voiceId/stream?output_format=pcm_$SAMPLE_RATE")
            .header("xi-api-key", apiKey)
            .post(body)
            .build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException(
                if (code == 401 || code == 403) "ElevenLabs-Key wurde abgelehnt ($code)"
                else "Sprachausgabe antwortet mit $code"
            )
        }
        return response
    }

    companion object {
        const val MODEL = "eleven_flash_v2_5"
        const val SAMPLE_RATE = 24000
        /** Höchstwert, den ElevenLabs erlaubt. */
        const val INACTIVITY_SEC = 180
    }
}
