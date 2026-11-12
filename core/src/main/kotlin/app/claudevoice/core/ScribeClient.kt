package app.claudevoice.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Live-Transkription über die ElevenLabs-Realtime-Schnittstelle (WebSocket), Modell "Scribe v3 Live".
 *
 * Wir schicken laufend kleine PCM-Stücke (16 kHz, mono, 16 Bit). Der Server antwortet mit:
 *  - partial_transcript: Zwischenstand, ändert sich noch. Er trägt turn_probability, also wie sicher das
 *    Modell ist, dass der Sprecher fertig ist. Damit kann die Pipeline schon vor dem Satzende losdenken.
 *  - committed_transcript: fertige Äußerung, ändert sich nicht mehr. Der Server schließt ab, sobald das
 *    Modell das Satzende vorhersagt (commit_strategy=turn_prediction), spätestens nach 0,5 s Stille.
 *    Früher galt der Standard von 1,5 s Stille; diese Sekunde fehlte bei jeder Antwort.
 *  - diarized_transcript und speakers_merged: wer gesprochen hat (Diarisierung). Die Sprecher-IDs sind
 *    anonym ("speaker_0") und vorläufig. Welche davon der Träger ist, lernt die Pipeline.
 *
 * Bricht die Verbindung ab, wird mit wachsender Wartezeit neu verbunden. Lehnt der Server die neuen
 * Einstellungen ab, läuft der Rest der Sitzung mit einer einfachen, bewährten Einstellung weiter.
 * Gespeichert wird hier nichts: Audio und Text werden nur durchgereicht.
 *
 * Damit die App nie unbemerkt taub wird, gelten zwei Fristen:
 *  - Steht die Sitzung nicht innerhalb von [startTimeoutMs], wird der Versuch abgebrochen und wiederholt.
 *    Sonst könnte ein Server, der die Verbindung annimmt und dann schweigt, die App für immer bei
 *    "Verbinde …" festhalten.
 *  - Der Server schickt auf unseren Wunsch alle 3 s ein Lebenszeichen, auch wenn niemand spricht. Bleibt
 *    es trotz laufendem Audio [stallTimeoutMs] lang still, hängt die Sitzung, und es wird neu verbunden.
 *
 * Stand der Prüfung: Namen und Aufbau aller Nachrichten und Parameter stammen aus der ElevenLabs-Doku.
 * Am echten Dienst ließ sich ohne Key nur der Anfang nachstellen (November 2026). Der Server nimmt die
 * Verbindung immer an und prüft dann zuerst Modell, Audioformat, commit_strategy, Stille-Schwelle,
 * Abstand der Lebenszeichen, Sprache und die Schlüsselbegriffe. Passt etwas nicht, schickt er die
 * Nachricht "invalid_request" und beendet die Verbindung. Beide Adressen dieses Clients bestehen diese
 * Prüfung. Erst danach kommt der Key an die Reihe; fehlt er oder ist er falsch, folgt die Nachricht
 * "auth_error". Alles hinter der Anmeldung (Sprechertrennung, Lebenszeichen, Sitzungslimit) ist bisher
 * nur gegen die Doku geprüft, nicht gegen den Dienst.
 *
 * [keyterms] sind Namen und Begriffe, die das Modell bevorzugt richtig schreiben soll, zum Beispiel
 * der Name des Assistenten. Erlaubt sind höchstens 50 Begriffe mit je höchstens 20 Zeichen; was nicht
 * passt, wird weggelassen, damit der Server die Verbindung nicht deswegen ablehnt.
 */
class ScribeClient(
    private val apiKey: String,
    private val http: OkHttpClient,
    private val listener: Transcriber.Listener,
    baseUrl: String = "wss://api.elevenlabs.io",
    keyterms: List<String> = emptyList(),
    /** So lange darf es vom Verbindungsaufbau bis zur stehenden Sitzung dauern. Nur Tests stellen das kürzer. */
    private val startTimeoutMs: Long = 15_000,
    /** So lange darf der Server trotz laufendem Audio schweigen. Nur Tests stellen das kürzer. */
    private val stallTimeoutMs: Long = 15_000,
    /** Erste Wartezeit vor dem Neuverbinden. Nur Tests stellen sie kürzer. */
    private val retryBaseMs: Long = 1_000,
) : Transcriber {

    private val json = ObjectMapper()

    // Beide Adressen entstehen einmal beim Anlegen. So fällt eine kaputte Basisadresse sofort auf
    // und nicht erst mitten im Betrieb.
    private val liveUrl = url(baseUrl, cleanKeyterms(keyterms), conservative = false)
    private val fallbackUrl = url(baseUrl, emptyList(), conservative = true)

    // Threads: Drei Seiten greifen gleichzeitig zu. Das Mikrofon ruft sendAudio(), OkHttp liefert die
    // Nachrichten des Servers auf eigenen Threads, und close() kommt vom Hauptthread. Damit sich dabei
    // nichts überkreuzt, laufen alle Zustandsänderungen und alle Rückrufe an den listener über einen
    // einzigen Hintergrund-Thread, der Reihe nach wie an einem Schalter. Der listener sieht die
    // Ereignisse dadurch garantiert in der Reihenfolge, in der sie eingetroffen sind.
    private val worker = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "scribe").apply { isDaemon = true } }
        .apply { executeExistingDelayedTasksAfterShutdownPolicy = false }

    // Nur diese vier Felder werden auch von anderen Threads gelesen oder geschrieben, deshalb @Volatile.
    @Volatile private var closed = false
    @Volatile private var socket: WebSocket? = null // die aktuelle Verbindung, auch während des Aufbaus
    @Volatile private var live: WebSocket? = null   // dieselbe Verbindung, sobald die Sitzung steht
    @Volatile private var audioAtNanos = 0L         // wann das Mikrofon zuletzt etwas gesendet hat

    // Alles ab hier gehört allein dem worker-Thread.
    private var begun = false
    private var link = 0                 // Nummer der aktuellen Verbindung; Ereignisse älterer werden verworfen
    private var attempt = 0              // Fehlversuche in Folge, bestimmt die Wartezeit
    private var conservative = false     // true = Rückfall auf die einfache Einstellung
    private var fallbackReason = ""
    private var startedAtNanos = 0L      // 0 = die Sitzung steht (noch) nicht
    private var heardTranscript = false
    private var draftSpeaker: String? = null
    private var heardAtNanos = 0L        // wann der Server zuletzt irgendetwas geschickt hat
    private var watching = false         // true = in dieser Sitzung wird auf Lebenszeichen geachtet
    private var stallRestarts = 0        // wie oft schon wegen Schweigens neu verbunden wurde

    override fun connect() = post {
        // Nur der erste Aufruf zählt; ums Neuverbinden kümmert sich der Client danach selbst.
        if (!begun) {
            begun = true
            open()
        }
    }

    /** Ein Stück Mikrofon-Audio senden. Solange keine Sitzung steht, wird es verworfen. */
    override fun sendAudio(pcm: ByteArray, length: Int) {
        // Kommt vom Mikrofon-Thread und darf nie warten: ein Blick auf das Feld, dann raus damit.
        val ws = live ?: return
        val n = minOf(length, pcm.size)
        if (n <= 0) return
        val node = json.createObjectNode()
            .put("message_type", "input_audio_chunk")
            .put("audio_base_64", Base64.getEncoder().encodeToString(pcm.copyOf(n)))
            .put("commit", false)
            .put("sample_rate", 16000)
        audioAtNanos = System.nanoTime()
        ws.send(json.writeValueAsString(node))
    }

    /**
     * Beendet alles. Ein Rückruf, der in genau diesem Moment schon läuft, kann noch ankommen;
     * danach kommt nichts mehr, und neu verbunden wird auch nicht.
     */
    override fun close() {
        closed = true // zuerst, siehe started()
        val wasLive = live != null
        live = null
        worker.shutdown() // wartende Aufgaben (auch ein geplantes Neuverbinden) verfallen
        // Eine stehende Sitzung wird höflich beendet, ein halbfertiger Aufbau einfach abgebrochen.
        if (wasLive) socket?.close(1000, null) else socket?.cancel()
    }

    // ------------------------------------------------------------------------------------------
    // Verbindung

    private fun open() {
        hangUp()
        val id = link
        val request = try {
            Request.Builder()
                .url(if (conservative) fallbackUrl else liveUrl)
                .header("xi-api-key", apiKey.trim())
                .build()
        } catch (e: IllegalArgumentException) {
            // Zum Beispiel ein Zeilenumbruch mitten im eingefügten Key. Ein neuer Versuch würde nichts
            // ändern, also bleibt es bei dieser einen Meldung. Den Text der Ausnahme geben wir bewusst
            // nicht weiter, denn er enthält den Key.
            listener.onConnection(false, "ElevenLabs-Key enthält ungültige Zeichen")
            return
        }
        val ws = try {
            http.newWebSocket(request, object : WebSocketListener() {
                // OkHttp ruft diese Funktionen auf seinen Threads auf. Wir reichen alles an den worker weiter
                // und prüfen dort, ob das Ereignis noch zur aktuellen Verbindung gehört (id == link).
                override fun onMessage(webSocket: WebSocket, text: String) = post { if (id == link) handle(text) }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Der Server verabschiedet sich. Ohne unsere Antwort bliebe die Verbindung halb offen hängen.
                    webSocket.close(1000, null)
                    ended(id, code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(id, code, reason)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val status = response?.code
                    val why = t.message ?: t.javaClass.simpleName
                    post { if (id == link) failed(status, why) }
                }
            })
        } catch (e: Exception) {
            // OkHttp meldet Fehler sonst über onFailure. Wirft es hier doch einmal, darf der Client danach
            // nicht für immer still bleiben: also wie jeder andere gescheiterte Versuch behandeln.
            reconnect("Keine Verbindung zur Transkription: ${e.javaClass.simpleName}")
            return
        }
        socket = ws
        // close() kann genau zwischen Aufbau und Zuweisung gekommen sein und hat diese Verbindung dann
        // nicht gesehen. Dann räumen wir sie hier selbst ab.
        if (closed) ws.cancel()
        // Frist für den Aufbau. Der Server meldet "session_started" sofort nach dem Verbinden. Bleibt das
        // aus, gibt es sonst niemanden, der es merkt: OkHttp wartet auf eine geöffnete WebSocket-Verbindung
        // beliebig lange, und die App stellt auch die Lesefrist für den Aufbau ab.
        post(startTimeoutMs) {
            if (id == link && live == null) reconnect("Transkription meldet sich nicht, neuer Versuch folgt")
        }
    }

    private fun ended(id: Int, code: Int, reason: String) = post {
        if (id != link) return@post
        reconnect(closeText(code, reason, sessionStood = live != null))
    }

    /** Die Verbindung kam gar nicht zustande oder ist abgerissen. */
    private fun failed(status: Int?, why: String) {
        when (status) {
            null -> reconnect("Keine Verbindung zur Transkription: $why")
            // Am echten Dienst nachgestellt: Ein fehlender oder falscher Key wird NICHT schon hier abgelehnt.
            // Der Server nimmt die Verbindung an und schickt danach die Nachricht "auth_error". Ein 401 kann
            // also nur von einer Zwischenstation kommen; gemeint ist trotzdem dasselbe.
            401 -> reconnect("ElevenLabs-Key wurde abgelehnt (401)")
            // Abgelehnte Einstellungen kommen als Nachricht "invalid_request" (siehe serverError). Nur wenn
            // der Server einen Wert gar nicht lesen kann (etwa diarize=vielleicht), weist er schon den
            // Verbindungsaufbau mit 403 ab. Auch das heißt: mit diesen Einstellungen geht es nicht.
            400, 403, 422 ->
                if (conservative) reconnect("Transkription lehnt die Verbindung ab ($status)") else fallBack("HTTP $status")
            else -> reconnect("Transkription antwortet mit $status")
        }
    }

    /** Trennt sich von der aktuellen Verbindung. Was sie später noch meldet, wird verworfen. */
    private fun hangUp() {
        link++
        val old = socket
        val wasLive = live != null
        live = null
        socket = null
        startedAtNanos = 0L
        heardTranscript = false
        draftSpeaker = null
        watching = false
        if (wasLive) old?.close(1000, null) else old?.cancel()
    }

    /**
     * Die Verbindung ist weg: einmal melden und neu verbinden.
     * [atOnce] = ohne Wartezeit, für Fälle, in denen der Server erreichbar ist und nur eine neue Sitzung will.
     */
    private fun reconnect(error: String?, atOnce: Boolean = false) {
        val wasLive = live != null
        creditHealthySession()
        hangUp()
        // Erst einplanen, dann melden. Der worker arbeitet der Reihe nach, also beginnt der neue Aufbau
        // trotzdem erst nach der Meldung. Und er findet auch dann statt, wenn der listener einen Fehler wirft.
        post(reconnectDelayMs(attempt++, atOnce, retryBaseMs)) { open() }
        if (wasLive || error != null) listener.onConnection(false, error)
    }

    /**
     * Hat die Sitzung eine Weile gehalten, war sie gesund, und die Zählung der Fehlversuche beginnt neu.
     * "Sitzung gestartet" allein reicht dafür nicht: Sonst würde ein Server, der jede Sitzung sofort
     * wieder beendet (etwa bei aufgebrauchtem Guthaben), im Sekundentakt neu angefragt.
     */
    private fun creditHealthySession() {
        if (startedAtNanos != 0L && System.nanoTime() - startedAtNanos >= STABLE_NANOS) attempt = 0
    }

    /**
     * Der Server lehnt die neuen Einstellungen ab. Statt gar nichts zu hören, läuft der Rest der Sitzung
     * mit dem Vorgängermodell und ohne alle Extras. Das passiert höchstens einmal.
     */
    private fun fallBack(reason: String) {
        conservative = true
        fallbackReason = reason.replace(Regex("\\s+"), " ").trim().take(120)
        attempt = 0 // der Server ist erreichbar und hat geantwortet, es gibt nichts abzuwarten
        reconnect(null, atOnce = true)
    }

    // ------------------------------------------------------------------------------------------
    // Nachrichten des Servers

    private fun handle(raw: String) {
        // Jede Nachricht zeigt, dass der Server noch arbeitet (siehe watch()).
        heardAtNanos = System.nanoTime()
        val msg = try { json.readTree(raw) } catch (e: Exception) { return }
        if (msg == null || !msg.isObject) return
        val type = msg.path("message_type").asText("")
        // Hinweise und die Bremse für zu häufiges manuelles Abschließen sind harmlos, die Sitzung läuft weiter.
        if (type == "warning" || type == "commit_throttled") return
        // Es gibt über ein Dutzend Fehlerarten, und nicht alle tragen "error" im Namen (quota_exceeded,
        // rate_limited ...). Verlässlich ist nur: Jede Fehlermeldung hat ein Feld "error".
        val error = msg.get("error")
        if (error != null && !error.isNull) return serverError(type, error.asText(""))
        when (type) {
            "session_started" -> started()
            "partial_transcript" -> partial(msg)
            "committed_transcript" -> committed(msg)
            "diarized_transcript" -> diarized(msg)
            "speakers_merged" -> merged(msg)
        }
    }

    private fun started() {
        val ws = socket ?: return
        if (live != null) return // eine zweite Meldung derselben Sitzung ändert nichts
        live = ws
        // close() kann genau jetzt von einem anderen Thread kommen. Es setzt erst closed und leert dann
        // live. Mit der Reihenfolge hier (erst live setzen, dann closed prüfen) bleibt live danach in
        // jedem Fall leer, und das Mikrofon sendet nicht in eine beendete Verbindung.
        if (closed) {
            live = null
            return
        }
        startedAtNanos = System.nanoTime()
        val notice = if (conservative) {
            "Transkription läuft im einfachen Modus ohne Sprechertrennung (Server: $fallbackReason)"
        } else null
        listener.onConnection(true, notice, fresh = true)
    }

    private fun partial(msg: JsonNode) {
        heardTranscript = true
        val text = msg.path("text").asText("")
        val words = msg.get("words")
        // Wer gerade spricht, steht nur im Zwischenstand; die fertige Äußerung nennt keinen Sprecher.
        // Also merken wir uns den Sprecher des jeweils letzten Zwischenstands. Ein leerer Zwischenstand
        // (Lebenszeichen in einer Pause) heißt "nichts offen". Wiederholt der Server nur den Text ohne
        // Wortliste, bleibt der gemerkte Sprecher stehen.
        when {
            text.isBlank() -> {
                draftSpeaker = null
                watchKeepalives()
            }
            words != null && words.isArray -> draftSpeaker = dominantSpeaker(words)
        }
        val probability = msg.get("turn_probability")?.takeIf { it.isNumber }?.asDouble()?.coerceIn(0.0, 1.0)
        listener.onPartial(text, probability)
    }

    private fun committed(msg: JsonNode) {
        heardTranscript = true
        val speaker = draftSpeaker
        draftSpeaker = null // die nächste Äußerung beginnt ohne Vorwissen
        val segment = msg.get("segment_index")?.takeIf { it.isIntegralNumber }?.asInt()
        listener.onCommitted(msg.path("text").asText(""), speaker, segment)
    }

    private fun diarized(msg: JsonNode) {
        val segment = msg.get("segment_index")?.takeIf { it.isIntegralNumber }?.asInt() ?: return
        val parts = spokenParts(msg.get("words"))
        if (parts.isNotEmpty()) listener.onDiarized(segment, parts)
    }

    private fun merged(msg: JsonNode) {
        val from = msg.path("from_speaker_id").asText("")
        val to = msg.path("to_speaker_id").asText("")
        if (from.isEmpty() || to.isEmpty()) return
        // Gilt rückwirkend auch für den gemerkten Sprecher des laufenden Zwischenstands.
        if (draftSpeaker == from) draftSpeaker = to
        listener.onSpeakersMerged(from, to)
    }

    private fun serverError(type: String, detail: String) {
        creditHealthySession()
        when {
            // Jede Sitzung hat eine Höchstdauer, die vom Abo abhängt. Das ist kein Fehler, den der
            // Nutzer sehen muss: einfach sofort eine neue Sitzung beginnen. Meldet der Server das Limit
            // aber gleich bei der nächsten Sitzung wieder (attempt > 0), stimmt etwas nicht, und es wird
            // wie jeder andere Fehler angezeigt, statt still im Kreis zu laufen.
            type == "session_time_limit_exceeded" && attempt == 0 -> reconnect(null, atOnce = true)
            // "Direkt nach dem Verbinden" heißt: noch bevor der erste Text kam.
            type == "invalid_request" && !conservative && !heardTranscript -> fallBack(detail.ifBlank { type })
            // Laut Doku schließt der Server nach jeder Fehlermeldung die Verbindung. Wir warten nicht
            // darauf, sondern melden den Fehler einmal und verbinden mit Wartezeit neu.
            else -> reconnect(errorText(type, detail))
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lebenszeichen

    /**
     * Der Server hat ein Lebenszeichen geschickt: einen leeren Zwischenstand in einer Sprechpause. Damit
     * ist belegt, dass in dieser Sitzung alle paar Sekunden etwas kommt, auch wenn niemand spricht. Erst
     * ab hier wird das überwacht. Ohne diesen Beleg wäre jede längere Stille ein falscher Alarm.
     *
     * Dass die Lebenszeichen wirklich so regelmäßig kommen, wissen wir nur aus der Doku. Darum ist der
     * Schaden begrenzt, falls sie es nicht tun: Nach [MAX_STALL_RESTARTS] Neustarts wegen Schweigens
     * gibt der Wächter auf, und es bleibt beim Verhalten ohne ihn.
     */
    private fun watchKeepalives() {
        // In der einfachen Einstellung haben wir keine Lebenszeichen bestellt.
        if (watching || conservative || stallRestarts >= MAX_STALL_RESTARTS) return
        watching = true
        watch(link)
    }

    private fun watch(id: Int) {
        val tickMs = maxOf(1L, stallTimeoutMs / STALL_CHECKS)
        post(tickMs) {
            if (id != link) return@post
            val now = System.nanoTime()
            when {
                // Lebenszeichen kommen nur, solange wir Audio schicken (Stille genügt). Liefert das Mikrofon
                // gerade nichts, ist Schweigen des Servers normal, und die Frist beginnt von vorn.
                now - audioAtNanos > tickMs * 1_000_000 -> heardAtNanos = now
                now - heardAtNanos >= stallTimeoutMs * 1_000_000 -> {
                    stallRestarts++
                    reconnect("Transkription antwortet nicht mehr, neue Verbindung wird aufgebaut")
                    return@post
                }
            }
            watch(id)
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Reiht eine Aufgabe beim worker ein. Nach close() passiert nichts mehr. */
    private fun post(delayMs: Long = 0, task: () -> Unit) {
        val guarded = Runnable {
            if (closed) return@Runnable
            try {
                task()
            } catch (e: Exception) {
                // Ein Fehler beim Verarbeiten soll weder still verschwinden noch die Sitzung beenden.
                runCatching {
                    listener.onConnection(live != null, "Transkription: interner Fehler (${e.javaClass.simpleName})")
                }
            }
        }
        try {
            worker.schedule(guarded, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // close() kam dazwischen
        }
    }

    companion object {
        /** Grenzen der Schnittstelle für Schlüsselbegriffe im Live-Betrieb. */
        const val MAX_KEYTERMS = 50
        const val MAX_KEYTERM_CHARS = 20

        /** So lange muss eine Sitzung gehalten haben, damit sie als gesund gilt. */
        private const val STABLE_NANOS = 10_000_000_000L

        /** So oft je Frist schaut der Wächter nach, ob der Server noch etwas schickt. */
        private const val STALL_CHECKS = 5

        /** So oft verbindet der Wächter höchstens neu, solange dieser Client lebt. */
        private const val MAX_STALL_RESTARTS = 3

        /**
         * Baut die Adresse mit allen Einstellungen. HttpUrl kodiert die Werte selbst, sodass auch ein
         * Schlüsselbegriff mit Leerzeichen oder Umlaut heil ankommt. Listen (secondary_languages, keyterms)
         * erwartet der Server als mehrfach wiederholten Parameter.
         */
        internal fun url(baseUrl: String, keyterms: List<String>, conservative: Boolean): HttpUrl {
            // HttpUrl kennt nur http und https; OkHttp baut daraus selbst wieder eine WebSocket-Verbindung.
            val base = baseUrl.replaceFirst(Regex("^ws", RegexOption.IGNORE_CASE), "http")
            val b = base.toHttpUrl().newBuilder().addPathSegments("v1/speech-to-text/realtime")
            if (conservative) {
                // Rückfall: Vorgängermodell, Abschluss allein nach Stille, Sprache automatisch erkannt.
                // Bewusst ohne Schlüsselbegriffe und Lebenszeichen, denn hier zählt nur, dass es sicher läuft.
                b.addQueryParameter("model_id", "scribe_v2_realtime")
                    .addQueryParameter("audio_format", "pcm_16000")
                    .addQueryParameter("commit_strategy", "vad")
                    .addQueryParameter("vad_silence_threshold_secs", "0.45")
            } else {
                b.addQueryParameter("model_id", "scribe_v3_live")
                    .addQueryParameter("audio_format", "pcm_16000")
                    .addQueryParameter("commit_strategy", "turn_prediction")
                    // Stille bleibt das Sicherheitsnetz, falls die Vorhersage des Satzendes zögert.
                    .addQueryParameter("vad_silence_threshold_secs", "0.5")
                    // Deutsch als Hauptsprache, Englisch als einzige Alternative: Das macht die
                    // Spracherkennung verlässlicher, als wenn alle Sprachen der Welt in Frage kämen.
                    .addQueryParameter("language_code", "de")
                    .addQueryParameter("secondary_languages", "en")
                    .addQueryParameter("diarize", "true")
                    // Alle 3 s ein Lebenszeichen (leerer Zwischenstand), auch wenn niemand spricht.
                    // Daran erkennt watch(), ob die Sitzung noch arbeitet. Die Doku rät, höchstens ein
                    // Drittel der eigenen Frist zu wählen; bei 15 s Frist sind 3 s reichlich Abstand.
                    .addQueryParameter("keepalive_interval_ms", "3000")
                keyterms.forEach { b.addQueryParameter("keyterms", it) }
            }
            return b.build()
        }

        internal fun cleanKeyterms(raw: List<String>): List<String> =
            raw.map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= MAX_KEYTERM_CHARS }
                .distinctBy { it.lowercase() }
                .take(MAX_KEYTERMS)

        /**
         * Wartezeit vor dem nächsten Verbindungsversuch: 1 s, 2 s, 4 s, 8 s, dann höchstens 15 s,
         * damit ein Funkloch nicht zur Dauerschleife wird. [atOnce] gilt nur für den ersten Versuch;
         * klappt der nicht, wird ebenfalls gewartet.
         */
        internal fun reconnectDelayMs(attempt: Int, atOnce: Boolean, baseMs: Long = 1_000L): Long =
            if (atOnce && attempt <= 0) 0L else minOf(15 * baseMs, baseMs shl attempt.coerceIn(0, 4))

        /**
         * Was die App erfährt, wenn der Server die Verbindung von sich aus beendet (null = nichts, was der
         * Nutzer lesen muss). [sessionStood] sagt, ob vorher eine Sitzung stand.
         */
        internal fun closeText(code: Int, reason: String, sessionStood: Boolean): String? {
            val detail = if (reason.isBlank()) "$code" else "$code $reason"
            return when {
                // Der Server geht ohne Fehlermeldung, bevor je eine Sitzung stand. Das darf nicht lautlos
                // bleiben, sonst stünde in der App für immer nur "Verbinde …".
                !sessionStood -> "Transkription hat die Verbindung beendet, bevor die Sitzung stand ($detail)"
                // 1000 ist der ordentliche Abschied, 1005 ein Abschied ohne Angabe eines Grundes.
                code == 1000 || code == 1005 -> null
                else -> "Verbindung beendet ($detail)"
            }
        }

        /** Kurze, klare Meldung für die Oberfläche statt des rohen Fehlertexts vom Server. */
        internal fun errorText(type: String, detail: String): String = when (type) {
            "auth_error" -> "ElevenLabs-Key wurde abgelehnt"
            "quota_exceeded" -> "ElevenLabs-Guthaben ist aufgebraucht"
            "unaccepted_terms" -> "ElevenLabs: Nutzungsbedingungen für die Transkription im Konto noch nicht bestätigt"
            "rate_limited" -> "Transkription: zu viele Anfragen, neuer Versuch folgt"
            "resource_exhausted" -> "Transkription ist gerade überlastet, neuer Versuch folgt"
            else -> "Transkription meldet: " + detail.replace(Regex("\\s+"), " ").trim().take(160).ifEmpty { type }
        }

        /** Die Sprecher-ID, auf die der meiste Text eines Zwischenstands entfällt (null = noch keine bekannt). */
        internal fun dominantSpeaker(words: JsonNode?): String? {
            if (words == null || !words.isArray) return null
            val weight = LinkedHashMap<String, Int>()
            for (w in words) {
                val text = w.path("text").asText("")
                if (isSpacing(w, text)) continue
                val id = w.path("speaker_id").takeIf { it.isTextual }?.asText() ?: continue
                weight[id] = (weight[id] ?: 0) + text.length
            }
            return weight.maxByOrNull { it.value }?.key
        }

        /**
         * Fasst die Wortliste einer Äußerung zu Stücken je Sprecher zusammen: aufeinanderfolgende Wörter
         * derselben Sprecher-ID ergeben ein Stück. Die Liste enthält auch die Leerzeichen als eigene
         * Einträge; sie bleiben am vorherigen Wort hängen und lösen nie einen Sprecherwechsel aus.
         * So liest sich der Text jedes Stücks wie gesprochen.
         */
        internal fun spokenParts(words: JsonNode?): List<SpokenPart> {
            if (words == null || !words.isArray) return emptyList()
            class Draft(var speaker: String?) {
                val text = StringBuilder()
                var start: Double? = null
                var end: Double? = null
                var hasWord = false
            }
            val drafts = ArrayList<Draft>()
            for (w in words) {
                val text = w.path("text").asText("")
                if (text.isEmpty()) continue
                val spacing = isSpacing(w, text)
                val speaker = w.path("speaker_id").takeIf { it.isTextual }?.asText()
                var current = drafts.lastOrNull()
                if (current == null || (!spacing && current.hasWord && current.speaker != speaker)) {
                    current = Draft(speaker).also { drafts += it }
                }
                if (!spacing) {
                    if (!current.hasWord) {
                        current.speaker = speaker
                        current.hasWord = true
                    }
                    // Zeiten können fehlen, solange der Server ein Wort noch nicht eingeordnet hat.
                    w.get("start")?.takeIf { it.isNumber }?.asDouble()?.let { if (current.start == null) current.start = it }
                    w.get("end")?.takeIf { it.isNumber }?.asDouble()?.let { current.end = it }
                }
                current.text.append(text)
            }
            var clock = 0.0
            return drafts.filter { it.hasWord }.map { d ->
                // Ohne eigene Zeitangabe schließt ein Stück an das vorherige an.
                val start = d.start ?: clock
                val end = maxOf(d.end ?: start, start)
                clock = end
                SpokenPart(d.speaker, d.text.toString(), start, end)
            }
        }

        private fun isSpacing(word: JsonNode, text: String) =
            word.path("type").asText("") == "spacing" || text.isBlank()
    }
}
