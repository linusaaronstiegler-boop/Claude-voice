package app.claudevoice.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live-Transkription über die Soniox-Realtime-Schnittstelle (WebSocket): der zweite Anbieter neben
 * [ScribeClient], hinter derselben [Transcriber]-Schnittstelle.
 *
 * Warum ein zweiter Anbieter? Soniox sagt zu jedem Wortstück gleich dazu, WER es gesprochen hat
 * (Diarisierung), und erkennt das Ende eines Redebeitrags am Inhalt statt nur an der Stille.
 *
 * Ablauf einer Verbindung:
 *  1. WebSocket öffnen; der Key steht im Authorization-Header.
 *  2. Als allererstes Text-Frame die Einstellungen schicken (Modell, Audioformat, Sprachen ...).
 *  3. Danach das Mikrofon-Audio als Binär-Frames (rohes PCM, nicht Base64 in JSON wie bei Scribe).
 *
 * Der Server antwortet mit "Tokens". Das sind Wortstücke, das Leerzeichen gehört mit zum Token
 * ("Wie", " hoch", " ist"), man hängt sie also einfach aneinander:
 *  - is_final=false: vorläufig. Jede Nachricht nennt ALLE gerade vorläufigen Tokens neu; die aus der
 *    Nachricht davor werden komplett ersetzt.
 *  - is_final=true: steht fest, kommt genau einmal und wird angehängt.
 *  - Das feste Token "<end>" bedeutet: Der Redebeitrag ist zu Ende. Erst dann wird er gemeldet.
 *
 * Drei Threads greifen zu: der Mikrofon-Thread ([sendAudio]), der Lese-Thread von OkHttp (Antworten
 * des Servers) und ein Zeitgeber (Lebenszeichen, Neuverbinden). Damit sie sich nicht in die Quere kommen:
 *  - Jede Verbindung ist ein eigenes [Session]-Objekt. Bricht sie ab, wird sie ausgehängt; was eine
 *    alte Verbindung danach noch meldet, wird ignoriert.
 *  - [sendAudio] nimmt kein Schloss und der Listener wird nie innerhalb von `synchronized` aufgerufen.
 *    Sonst könnten Mikrofon-Thread und Lese-Thread gegenseitig aufeinander warten (Deadlock), denn der
 *    Aufrufer hält beim Senden selbst ein Schloss.
 */
class SonioxClient(
    private val apiKey: String,
    private val http: OkHttpClient,
    private val listener: Transcriber.Listener,
    private val baseUrl: String = "wss://stt-rt.soniox.com",
    /** Namen und Fachwörter, die richtig geschrieben werden sollen (z. B. der Name des Assistenten). */
    private val keyterms: List<String> = emptyList(),
    /** Abstand der Lebenszeichen. Nur Tests stellen ihn kürzer. */
    private val keepaliveMs: Long = 8_000,
    /** Erste Wartezeit vor dem Neuverbinden. Nur Tests stellen sie anders. */
    private val retryBaseMs: Long = 1_000,
) : Transcriber {

    private val json = ObjectMapper()
    // Daemon-Thread: Er hält das Programm nicht am Leben, falls jemand close() vergisst.
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "soniox-timer").apply { isDaemon = true } }
    private val lock = Any()

    /** Die aktuelle Verbindung; null, solange keine besteht oder aufgebaut wird. */
    @Volatile private var session: Session? = null
    /** Wie viele Verbindungsversuche in Folge gescheitert sind (bestimmt die Wartezeit). */
    @Volatile private var attempt = 0
    private var closed = false

    override fun connect() {
        val request = try {
            Request.Builder()
                .url("$baseUrl/transcribe-websocket")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .build()
        } catch (e: IllegalArgumentException) {
            // Zum Beispiel ein Zeilenumbruch mitten im eingefügten Key. Ein neuer Versuch würde nichts ändern.
            // Die Meldung der Ausnahme geben wir bewusst nicht weiter, damit der Key nirgends auftaucht.
            listener.onConnection(false, "Soniox-Key enthält ungültige Zeichen")
            return
        }
        synchronized(lock) {
            if (closed || session != null) return
            val s = Session()
            session = s
            s.socket = http.newWebSocket(request, s)
        }
    }

    /** Ein Stück Mikrofon-Audio senden. Solange keine Sitzung steht, wird es verworfen. */
    override fun sendAudio(pcm: ByteArray, length: Int) {
        val s = session ?: return
        val n = minOf(length, pcm.size)
        if (!s.ready || n <= 0) return
        s.audioSent.set(true)
        s.socket?.send(pcm.toByteString(0, n))
    }

    override fun close() {
        val s = synchronized(lock) {
            if (closed) return
            closed = true
            session.also { session = null }
        }
        timer.shutdownNow()
        s?.end()
    }

    /**
     * Die Verbindung [s] ist weg. Meldet es dem Listener und plant den nächsten Versuch.
     * [error] null = nichts, was der Nutzer lesen muss. [retryNow] = ohne Wartezeit neu verbinden.
     */
    private fun dropped(s: Session, error: String?, retryNow: Boolean = false) {
        val delayMs = synchronized(lock) {
            // Veraltet (es gibt längst eine neuere Verbindung) oder close() kam zuvor: nichts tun.
            if (session !== s) return
            session = null
            // Wartezeit 1 s, 2 s, 4 s ... höchstens 15 s, damit ein Funkloch nicht zur Dauerschleife wird.
            if (retryNow) 0L else minOf(15 * retryBaseMs, retryBaseMs shl minOf(attempt++, 4))
        }
        s.stop()
        listener.onConnection(false, error)
        try {
            timer.schedule({ connect() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // close() kam dazwischen
        }
    }

    /** Die Einstellungen der Sitzung: das erste Frame nach dem Öffnen. */
    private fun config(): String {
        val node = json.createObjectNode()
            .put("model", "stt-rt-v6")
            .put("audio_format", "pcm_s16le")
            .put("sample_rate", 16000)
            .put("num_channels", 1)
            // Sprache je Token erkennen: Deutsch und Englisch dürfen sich mitten im Satz mischen.
            .put("enable_language_identification", true)
            .put("enable_speaker_diarization", true)
            // Der Server meldet das Ende eines Redebeitrags selbst (Token "<end>"),
            // spätestens eine Sekunde nach dem letzten Wort.
            .put("enable_endpoint_detection", true)
            .put("max_endpoint_delay_ms", 1000)
        // Nur ein Hinweis, keine Einschränkung: Andere Sprachen werden trotzdem erkannt.
        node.putArray("language_hints").add("de").add("en")
        val terms = keyterms.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TERMS)
        if (terms.isNotEmpty()) {
            val array = node.putObject("context").putArray("terms")
            terms.forEach { array.add(it) }
        }
        return json.writeValueAsString(node)
    }

    /** Fehlernummer von Soniox (gleich den HTTP-Statuscodes) in einen kurzen deutschen Satz übersetzen. */
    private fun describe(code: Int, type: String): String = when (code) {
        401 -> "Soniox-Key wurde abgelehnt (401)"
        402 -> "Soniox-Guthaben oder Budget ist aufgebraucht (402)"
        403 -> "Soniox verweigert den Zugriff (403)"
        429 -> "Soniox-Limit erreicht (429)"
        408, 500, 503 -> "Soniox ist kurz gestört ($code)"
        else -> "Soniox meldet Fehler $code" + if (type.isNotEmpty()) " ($type)" else ""
    }

    private class Token(val text: String, val speaker: String?, val startMs: Double, val endMs: Double)

    /** Eine einzelne Verbindung samt allem, was nur für sie gilt (Sprecher-IDs, Segmentnummern, laufender Text). */
    private inner class Session : WebSocketListener() {
        @Volatile var socket: WebSocket? = null
        /** true, sobald die Einstellungen geschickt sind. Vorher darf kein Audio raus. */
        @Volatile var ready = false
        /** Merkt sich, ob seit dem letzten Takt des Zeitgebers Audio gesendet wurde. */
        val audioSent = AtomicBoolean(false)
        @Volatile private var keepalive: ScheduledFuture<*>? = null

        // Alles ab hier fasst nur der Lese-Thread dieser Verbindung an, darum ohne Schloss.
        private val finals = ArrayList<Token>()
        private var finalChars = 0
        private var lastPartial = ""
        private var nextSegment = 0
        private var healthy = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (session !== this) {
                    webSocket.cancel()
                    return
                }
                socket = webSocket
                // Reihenfolge zählt: erst die Einstellungen in die Sendeschlange, dann gibt `ready` das Audio frei.
                webSocket.send(config())
                keepalive = timer.scheduleWithFixedDelay({ keepAlive() }, keepaliveMs, keepaliveMs, TimeUnit.MILLISECONDS)
                ready = true
            }
            listener.onConnection(true, null, fresh = true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (session !== this) return
            val msg = try { json.readTree(text) } catch (e: Exception) { return }
            if (msg == null || !msg.isObject) return
            val code = msg.path("error_code")
            if (code.isMissingNode || code.isNull) {
                // Eine normale Antwort: Die Sitzung läuft, ein späterer Abbruch beginnt wieder mit kurzer Wartezeit.
                healthy = true
                if (attempt != 0) attempt = 0
                read(msg.path("tokens"))
                return
            }
            // Fehlermeldung. Soniox schließt die Verbindung direkt danach von sich aus.
            flushTurn()
            webSocket.close(1000, null)
            // 413 = Höchstdauer einer Sitzung erreicht. Das ist kein Fehler, den der Nutzer sehen muss:
            // gleich neu verbinden. Nur wenn die Sitzung vorher wirklich lief; meldet der Server das sofort
            // nach dem Öffnen, greift die normale Wartezeit, damit keine Endlosschleife entsteht.
            if (code.asInt() == 413) dropped(this, null, retryNow = healthy)
            else dropped(this, describe(code.asInt(), msg.path("error_type").asText("")))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Den Abschied bestätigen, sonst bleibt die Verbindung halb offen hängen.
            webSocket.close(1000, null)
            if (session !== this) return
            flushTurn()
            dropped(this, if (code == 1000) null else "Verbindung beendet (${"$code $reason".trim()})")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            dropped(
                this,
                when (val status = response?.code) {
                    null -> "Keine Verbindung zur Transkription: ${t.message ?: t.javaClass.simpleName}"
                    else -> describe(status, "")
                },
            )

        /**
         * Soniox beendet eine Sitzung, in der 20 Sekunden lang weder Audio noch ein Lebenszeichen ankommt.
         * Der Zeitgeber schaut darum regelmäßig nach: Lief seit dem letzten Takt Audio, ist nichts zu tun;
         * sonst geht ein Lebenszeichen raus. Im ungünstigsten Fall liegen so knapp zwei Takte (16 s) zwischen
         * letztem Audio und Lebenszeichen, also immer noch unter der Grenze.
         */
        private fun keepAlive() {
            if (ready && !audioSent.getAndSet(false)) socket?.send(KEEPALIVE)
        }

        /** Verbindung ist weg: kein Audio und keine Lebenszeichen mehr. */
        fun stop() {
            ready = false
            keepalive?.cancel(false)
        }

        /** Sauber beenden (close()). */
        fun end() {
            val wasReady = ready
            stop()
            val ws = socket ?: return
            if (wasReady) {
                // Ein leeres Text-Frame heißt bei Soniox "es kommt kein Audio mehr". Danach verabschieden wir uns.
                ws.send("")
                ws.close(1000, null)
            } else {
                // Noch im Aufbau: Es gibt nichts zu beenden, also einfach abbrechen.
                ws.cancel()
            }
        }

        /** Eine Antwort des Servers auswerten: feste Tokens anhängen, vorläufige ersetzen, bei "<end>" melden. */
        private fun read(tokens: JsonNode) {
            // Die vorläufigen Tokens gelten nur bis zur nächsten Nachricht, darum eine frische Liste je Nachricht.
            val pending = ArrayList<Token>()
            for (t in tokens) {
                val text = t.path("text").asText("")
                val final = t.path("is_final").asBoolean(false)
                when {
                    text == END -> if (final) endTurn()
                    // "<fin>" bestätigt nur ein von Hand angefordertes Abschließen; das nutzen wir nicht.
                    text == FIN || text.isEmpty() -> Unit
                    !final -> pending += token(t)
                    else -> {
                        // Sicherung: Redet jemand ohne Pause (Vortrag, Fernseher), kommt lange kein "<end>".
                        // Damit der Zwischenstand nicht endlos wächst, wird dann an einer Wortgrenze geteilt
                        // (ein neues Wort beginnt mit Leerzeichen; mitten im Wort wird nie getrennt).
                        if (finalChars >= MAX_TURN_CHARS && text.startsWith(" ")) endTurn()
                        finals += token(t)
                        finalChars += text.length
                    }
                }
            }
            // Zwischenstand = alles Feste des laufenden Redebeitrags plus die vorläufigen Tokens dieser Nachricht.
            val partial = textOf(bySpeaker(finals + pending))
            if (partial != lastPartial) {
                lastPartial = partial
                listener.onPartial(partial, null)
            }
        }

        private fun token(t: JsonNode): Token {
            val speaker = t.path("speaker")
            return Token(
                text = t.path("text").asText(""),
                speaker = if (speaker.isMissingNode || speaker.isNull) null else speaker.asText("").ifEmpty { null },
                startMs = t.path("start_ms").asDouble(0.0),
                endMs = t.path("end_ms").asDouble(0.0),
            )
        }

        /** Der Redebeitrag ist zu Ende: als fertige Äußerung melden, gleich danach wer welchen Teil sprach. */
        private fun endTurn() {
            val parts = bySpeaker(finals)
            finals.clear()
            finalChars = 0
            if (parts.isEmpty()) return
            // Wer am meisten Text beigetragen hat, gilt als Sprecher der Äußerung (so rechnet auch die Pipeline).
            val dominant = parts.groupBy({ it.speaker }, { it.text.length }).maxByOrNull { it.value.sum() }?.key
            val segment = nextSegment++
            lastPartial = ""
            listener.onCommitted(textOf(parts), dominant, segment)
            listener.onDiarized(segment, parts)
        }

        /**
         * Beendet der Server die Sitzung mitten im Satz, sind die schon festen Wörter trotzdem gesagt worden.
         * Sie werden als Äußerung gemeldet, statt stillschweigend zu verschwinden.
         */
        private fun flushTurn() {
            if (finals.isNotEmpty()) endTurn()
        }

        /**
         * Aufeinanderfolgende Tokens desselben Sprechers zu einem Teil zusammenfassen. So bleiben zwei
         * Stimmen in einem Redebeitrag unterscheidbar ("Wie geht es dir?" / "Gut, danke.").
         */
        private fun bySpeaker(tokens: List<Token>): List<SpokenPart> {
            val parts = ArrayList<SpokenPart>()
            // Ein Token ohne Sprecher-Angabe zählt zum Sprecher davor (ganz am Anfang: zum ersten bekannten).
            var speaker = tokens.firstNotNullOfOrNull { it.speaker }
            var from = 0
            while (from < tokens.size) {
                speaker = tokens[from].speaker ?: speaker
                var to = from + 1
                while (to < tokens.size && (tokens[to].speaker == null || tokens[to].speaker == speaker)) to++
                val text = tokens.subList(from, to).joinToString("") { it.text }.trim()
                if (text.isNotEmpty()) {
                    parts += SpokenPart(speaker, text, tokens[from].startMs / 1000.0, tokens[to - 1].endMs / 1000.0)
                }
                from = to
            }
            return parts
        }

        /**
         * Der Text über alle Teile hinweg. Innerhalb eines Teils stehen die Leerzeichen schon in den Tokens.
         * Beim Sprecherwechsel fehlt im Beispiel der Soniox-Doku das führende Leerzeichen, darum kommt hier
         * eines dazwischen (die Teile selbst sind an den Rändern schon von Leerzeichen befreit).
         */
        private fun textOf(parts: List<SpokenPart>): String = parts.joinToString(" ") { it.text }
    }

    private companion object {
        const val END = "<end>"
        const val FIN = "<fin>"
        const val KEEPALIVE = """{"type":"keepalive"}"""
        /** Ab dieser Länge (etwa 40 Sekunden Sprache) wird ein Redebeitrag ohne Pause geteilt. */
        const val MAX_TURN_CHARS = 600
        /** Der Kontext darf bei Soniox nicht beliebig groß werden; so viele Begriffe braucht hier niemand. */
        const val MAX_TERMS = 100
    }
}
