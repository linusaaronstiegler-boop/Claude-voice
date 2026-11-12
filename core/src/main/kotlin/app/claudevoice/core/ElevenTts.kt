package app.claudevoice.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.util.concurrent.TimeUnit
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
 *
 * Woher die Einzelheiten stammen: aus der Schnittstellen-Beschreibung von ElevenLabs
 * (api-reference/text-to-speech/v-1-text-to-speech-voice-id-multi-stream-input) und dem Leitfaden dazu
 * (guides/how-to/websockets/multi-context-web-socket). Wo die beiden sich widersprechen oder schweigen,
 * steht es an der jeweiligen Stelle dabei, und der Code kommt mit beiden Möglichkeiten zurecht.
 */
class ElevenTts(
    private val apiKey: String,
    private val http: OkHttpClient,
    private val voiceId: String,
    baseUrl: String = "https://api.elevenlabs.io",
) : SpeechSynth {
    private val json = ObjectMapper()
    private val contextNumbers = AtomicInteger()
    private val lock = Any()
    private var current: Link? = null // nur unter [lock] anfassen
    @Volatile private var closed = false
    @Volatile private var pausedAtNanos: Long? = null

    // Entsteht einmal beim Anlegen: So fällt eine kaputte Basisadresse sofort auf und nicht mitten im Betrieb.
    private val root: HttpUrl = baseUrl.toHttpUrl()

    /**
     * Meldung für den Nutzer, wenn sich der Key gar nicht verschicken lässt; sonst null.
     * Warum eine eigene Prüfung: In einem HTTP-Kopf sind nur einfache Zeichen erlaubt. Steckt im Key ein
     * Zeilenumbruch oder ein unsichtbares Zeichen (passiert beim Einfügen), bricht OkHttp mit einem Fehler ab,
     * dessen Text den ganzen Key enthält. Das träfe schon [warmUp] beim Start des Dienstes, und der Key
     * stünde im Absturzbericht. Deshalb fassen wir so einen Key gar nicht erst an.
     */
    private val keyProblem: String? =
        if (apiKey.all { it == '\t' || it in ' '..'~' }) null
        else "ElevenLabs-Key enthält unzulässige Zeichen (etwa einen Zeilenumbruch oder ein unsichtbares Zeichen). Bitte neu eintragen."

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
    /** So lange darf auf dem HTTP-Ausweichweg das nächste Stück der Antwort ausbleiben. */
    internal var httpReadTimeoutMs = 15_000L

    /**
     * Öffnet die Verbindung schon vorab und kehrt sofort zurück (wartet nicht auf den Aufbau).
     * Warum: Der Aufbau (TLS + WebSocket) dauert ein paar Zehntelsekunden. Passiert er, während noch
     * jemand spricht oder Claude nachdenkt, kostet er die Antwort keine Zeit. Mehrfach aufrufen ist harmlos.
     */
    fun warmUp() {
        connection()
    }

    /** Die allgemeine Schnittstelle kennt das Vorwärmen unter diesem Namen. */
    override fun prepare() = warmUp()

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
                // Eine Verbindung, die schon vor dieser Antwort bestand, kann inzwischen unbrauchbar sein:
                // Der Server räumt nach einer Ruhezeit auf (zugesagt ist das nur für Kontexte, siehe
                // inactivity_timeout; was er mit einer Verbindung ganz ohne Kontext tut, steht nirgends),
                // oder das Handy hat das Netz gewechselt. Dann genau ein zweiter Versuch mit einer frischen.
                // Scheitert auch die frische, liegt es nicht am Alter.
                attempt = if (older) connection()?.let { it.first to false } else null
                if (attempt == null) {
                    // Eine Weile direkt HTTP nehmen, statt bei jeder Antwort erneut auf den WebSocket zu warten.
                    pausedAtNanos = System.nanoTime()
                    onTrouble("WebSocket-Sprachausgabe ausgefallen (${e.reason}), nutze HTTP")
                } else if (e.silent) {
                    // Hat die alte Verbindung einfach geschwiegen, hat das Warten die Antwort Zeit gekostet.
                    // Passiert das regelmäßig, soll man es sehen können.
                    onTrouble("Bestehende WebSocket-Verbindung blieb stumm (${e.reason}), neu verbunden")
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
     *
     * Reihenfolge der Nachrichten an den Server (so steht es in Beschreibung und Leitfaden):
     *  1. {"text":" ","context_id":…}                legt den Kontext an
     *  2. {"text":"Satz. ","context_id":…,"flush":true}   je Satz
     *  3. {"context_id":…,"flush":true}              Antwort vollständig: alles zu Ende sprechen
     *  4. {"context_id":…,"close_context":true}      Kontext schließen; der Server liefert den Rest noch aus
     *     ("if it has already been set to flush it will continue flushing") und meldet sich mit der Schlussmarke.
     * Nach 4 bekommt der Kontext keine Nachricht mehr von uns.
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
                        if (!gotAudio) throw SocketTrouble("kein Ton nach $firstAudioTimeoutMs ms", silent = true)
                        // Ton kam, nur die Schlussmarke nicht: nicht ewig hängen bleiben. Einer Leitung, auf der
                        // eine zugesagte Nachricht ausbleibt, trauen wir aber nicht mehr. Die nächste Antwort
                        // bekommt eine frische, statt womöglich erst Sekunden auf eine tote zu warten.
                        onTrouble("Schlussmarke der Sprachausgabe blieb aus")
                        link.drop("Schlussmarke blieb aus")
                        true
                    }
                }
            }
            null
        } catch (e: SocketTrouble) {
            e
        } finally {
            // Läuft auch beim Abbruch (z. B. "Stopp"): ab jetzt wird nichts mehr von diesem Kontext angenommen,
            // und der Server erfährt, dass er nicht mehr gebraucht wird. Laut Beschreibung liefert er trotzdem
            // noch zu Ende, was schon auf "flush" stand. Das findet hier keinen Empfänger mehr und wird verworfen.
            link.forget(id)
            // Höchstens einmal schließen: Einen Kontext, der schon schließt, spricht man nicht noch einmal an.
            if (begun && !closing) link.sendIfAlive(closeContext(id))
        }

        if (trouble != null && !gotAudio) throw trouble
        ready.close() // nichts kommt mehr nach; der Spieler liefert den Rest noch aus
        if (trouble != null) {
            if (closing) {
                // Alle Sätze waren draußen und Ton kam an, nur die Schlussmarke fehlt. Mehr ist nicht zu holen,
                // und ein Fehler würde eine (vermutlich vollständige) Antwort als gescheitert melden.
                onTrouble("Antwort endete ohne Schlussmarke (${trouble.reason})")
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
     * (geschlossen, Key unbrauchbar, oder nach einem Ausfall in der Pause).
     */
    private fun connection(): Pair<Link, Boolean>? = synchronized(lock) {
        val pausedAt = pausedAtNanos
        val paused = pausedAt != null && (System.nanoTime() - pausedAt) / 1_000_000 < pauseAfterFailureMs
        val open = current
        when {
            closed || paused || keyProblem != null -> null
            open != null && open.alive -> open to true
            else -> Link().also { current = it } to false
        }
    }

    /**
     * Adresse eines Endpunkts für unsere Stimme. Die Voice-ID wird als EIN Pfadstück eingesetzt: Enthält
     * sie versehentlich Zeichen wie "/" oder "?", landet die Anfrage trotzdem nicht auf einem anderen Endpunkt.
     */
    private fun endpoint(name: String): HttpUrl.Builder =
        root.newBuilder().addPathSegments("v1/text-to-speech").addPathSegment(voiceId).addPathSegment(name)

    private fun socketRequest(): Request = Request.Builder()
        .url(
            // Eine https-Adresse genügt: OkHttp stellt beim Aufbau selbst auf das WebSocket-Protokoll um.
            endpoint("multi-stream-input")
                .addQueryParameter("model_id", MODEL)
                .addQueryParameter("output_format", "pcm_$SAMPLE_RATE")
                // inactivity_timeout: Ruhezeit, nach der der Server einen Kontext von sich aus schließt
                // (Vorgabe 20 s, erlaubt sind höchstens 180 s). Ob er eine Verbindung ohne offenen Kontext
                // ebenso lange hält, sagt die Dokumentation nicht. Wir verlassen uns nicht darauf: Ist die
                // Verbindung weg, baut die nächste Antwort eine neue auf.
                .addQueryParameter("inactivity_timeout", INACTIVITY_SEC.toString())
                // auto_mode: der Server sammelt nicht erst Text, sondern spricht jeden Satz sofort.
                .addQueryParameter("auto_mode", "true")
                // Keine Sprache vorgegeben: Antworten können deutsch oder englisch sein.
                .build()
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
     * [silent] = die Verbindung hat nichts gemeldet, es kam nur kein Ton (wir haben also gewartet).
     */
    private class SocketTrouble(val reason: String, val silent: Boolean = false) : Exception(reason, null, false, false)

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
                // Haben wir die Leitung selbst gekappt (drop, shutdown), landet das ebenfalls hier. Das ist
                // kein Ausfall und darf den WebSocket nicht für die nächsten Antworten sperren.
                if (!opened && lost == null) pausedAtNanos = System.nanoTime()
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
            // Steht die Verbindung noch gar nicht, gibt es niemanden, von dem man sich verabschieden könnte:
            // Dann den Aufbau abbrechen, statt ihn im Hintergrund weiterlaufen zu lassen.
            if (opened) socket.close(1000, null) else socket.cancel()
        }

        private fun receive(webSocket: WebSocket, raw: String) {
            val msg = try { json.readTree(raw) } catch (e: Exception) { return }
            // Die Schnittstellen-Beschreibung schreibt contextId/isFinal, der Begleittext context_id, der
            // Beispielcode is_final. Wir nehmen jede Schreibweise an (auch wenn die andere leer mitkommt).
            val id = text(msg, "contextId") ?: text(msg, "context_id")
            // Kein Briefkasten heißt: Antwort abgebrochen oder schon fertig. Nachzügler werden verworfen,
            // sonst wäre der Rest der alten Antwort in der neuen zu hören.
            val frames = id?.let { contexts[it] }
            val audio = text(msg, "audio")
            if (frames != null && !audio.isNullOrEmpty()) {
                val pcm = try { Base64.getDecoder().decode(audio) } catch (e: IllegalArgumentException) { null }
                if (pcm != null && pcm.isNotEmpty()) frames.trySend(Frame.Pcm(pcm))
            }
            if (msg.path("isFinal").asBoolean(false) || msg.path("is_final").asBoolean(false)) {
                frames?.trySend(Frame.Final)
            } else if (msg.hasNonNull("error")) {
                // Fehlermeldungen führt die Schnittstellen-Beschreibung nicht auf. Andere Programme, die mit dem
                // Dienst arbeiten, erkennen sie am Feld "error"; darauf stützen wir uns auch. Weitergegeben wird
                // nur dessen Wert (gekürzt), nicht der ausführliche Text in "message".
                val reason = "Server meldet: ${text(msg, "error")?.take(60) ?: "Fehler"}"
                when {
                    // Ohne Kontextnummer gilt die Meldung der ganzen Verbindung (z. B. Zeitüberschreitung,
                    // Guthaben aufgebraucht). Danach trennt der Server ohnehin.
                    id == null -> {
                        lose(reason)
                        webSocket.close(1000, null)
                    }
                    // Sie betrifft die laufende Antwort: Nur die erfährt davon, die Verbindung bleibt.
                    frames != null -> frames.close(SocketTrouble(reason))
                    // Sie betrifft einen Kontext, den wir längst abgeschlossen oder abgebrochen haben:
                    // Das darf die Antwort, die gerade läuft, nicht mitreißen.
                    else -> Unit
                }
            }
        }

        /** Wert eines Textfelds; null, wenn es fehlt, leer gelassen wurde (JSON null) oder kein Text ist. */
        private fun text(msg: JsonNode, field: String): String? = msg.get(field)?.takeIf { it.isTextual }?.textValue()

        /** Verbindung ist hinüber: Alle wartenden Antworten erfahren es über ihren Briefkasten. */
        private fun lose(reason: String) {
            if (lost == null) lost = reason
            val why = SocketTrouble(lost ?: reason)
            for (frames in contexts.values) frames.close(why)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sicherer Weg: eine HTTP-Anfrage pro Satz

    /**
     * Eigener Client für den Ausweichweg; er teilt sich Verbindungen und Threads mit [http].
     * Warum: Die App stellt an ihrem Client das Lese-Zeitlimit ab, weil er auch die dauerhaft offenen
     * WebSockets trägt. Für eine gewöhnliche Anfrage hieße das: Bleibt der Server die Antwort schuldig,
     * wartet sie für immer, und die App bliebe stumm. Hier gilt deshalb wieder eine Grenze.
     */
    private val fallback: OkHttpClient by lazy {
        http.newBuilder().readTimeout(httpReadTimeoutMs, TimeUnit.MILLISECONDS).build()
    }

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
        keyProblem?.let { throw IOException(it) }
        val call = fallback.newCall(sentenceRequest(sentence))
        // Außerhalb angelegt, damit die Antwort in jedem Fall wieder geschlossen wird, auch wenn der
        // Abbruch genau in dem Moment kommt, in dem sie eintrifft.
        var response: Response? = null
        try {
            abortOnCancel(call) {
                val failure = withContext(Dispatchers.IO) {
                    val got = call.execute().also { response = it }
                    if (got.isSuccessful) null else describeFailure(got)
                }
                if (failure != null) throw IOException(failure)
                val input = response!!.body!!.byteStream()
                while (true) {
                    val n = withContext(Dispatchers.IO) { input.read(buffer) }
                    if (n < 0) break
                    if (n > 0) onPcm(buffer, n)
                }
            }
        } catch (e: IOException) {
            // Nach einem Abbruch meldet OkHttp "Canceled". Das ist kein Fehler der Sprachausgabe.
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            response?.close()
        }
    }

    /**
     * Führt [block] aus und bricht [call] ab, sobald die Coroutine währenddessen abgebrochen wird.
     * Warum nicht einfach den wartenden Thread unterbrechen: Ein Thread, der gerade auf Daten vom Netz
     * wartet, lässt sich davon nicht wecken (bei HTTP/1.1 gar nicht). call.cancel() schließt die Leitung,
     * und das Warten endet sofort. So gilt "Stopp" auch dann gleich, wenn der Server gerade hängt.
     */
    private suspend fun <T> abortOnCancel(call: Call, block: suspend () -> T): T = coroutineScope {
        var done = false
        // Der Wächter tut nichts, außer auf den Abbruch zu warten.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!done) call.cancel() }
        }
        try {
            block()
        } finally {
            done = true
            watcher.cancel()
        }
    }

    private fun sentenceRequest(text: String): Request {
        val body = json.writeValueAsString(
            json.createObjectNode().put("text", text).put("model_id", MODEL)
        ).toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(endpoint("stream").addQueryParameter("output_format", "pcm_$SAMPLE_RATE").build())
            .header("xi-api-key", apiKey)
            .post(body)
            .build()
    }

    /**
     * Macht aus einer abgelehnten Anfrage eine Meldung für den Nutzer.
     * ElevenLabs nennt den Grund im Körper der Antwort: {"detail":{"status":"quota_exceeded","message":"…"}}.
     * Das zählt, denn die Codes 400 und 401 haben dort mehrere Ursachen: falscher Key, Guthaben aufgebraucht,
     * unbekannte Stimme (Hilfe-Seite "API - Error Code 400 or 401"). Ohne lesbaren Grund bleibt es bei der
     * naheliegenden Deutung. Weitergegeben wird nur das Kürzel aus "status" (und nur, wenn es wie eines
     * aussieht), nie freier Text.
     */
    private fun describeFailure(response: Response): String {
        val code = response.code
        val status = runCatching {
            json.readTree(response.peekBody(4096).string()).path("detail").path("status").textValue()
        }.getOrNull()?.takeIf { STATUS.matches(it) }
        return when {
            status == "quota_exceeded" -> "ElevenLabs-Guthaben ist aufgebraucht ($code)"
            status == "voice_not_found" -> "ElevenLabs kennt diese Stimme nicht. Bitte die Voice-ID prüfen ($code)"
            status == "invalid_api_key" || (status == null && (code == 401 || code == 403)) ->
                "ElevenLabs-Key wurde abgelehnt ($code)"
            status != null -> "Sprachausgabe antwortet mit $code ($status)"
            else -> "Sprachausgabe antwortet mit $code"
        }
    }

    companion object {
        const val MODEL = "eleven_flash_v2_5"
        const val SAMPLE_RATE = 24000
        /** Höchstwert, den ElevenLabs erlaubt. */
        const val INACTIVITY_SEC = 180
        /** So sehen die Kürzel in "detail.status" aus, z. B. quota_exceeded. */
        private val STATUS = Regex("[a-z0-9_]{1,40}")
    }
}
