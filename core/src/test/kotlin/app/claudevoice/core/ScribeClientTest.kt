package app.claudevoice.core

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Der Client spricht hier mit einem Testserver auf dem eigenen Rechner (MockWebServer), der sich wie
 * ElevenLabs verhält. Geprüft wird also unser Umgang mit dem dokumentierten Protokoll, nicht der echte Dienst.
 */
class ScribeClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private var client: ScribeClient? = null

    /** Alles, was der Client an die App meldet, in Reihenfolge und als gut lesbarer Text. */
    private val events = LinkedBlockingQueue<String>()
    private val diarized = LinkedBlockingQueue<Pair<Int, List<SpokenPart>>>()

    private val recorder = object : Transcriber.Listener {
        override fun onPartial(text: String, turnProbability: Double?) { events += "partial:$text:$turnProbability" }
        override fun onCommitted(text: String, speaker: String?, segment: Int?) { events += "committed:$text:$speaker:$segment" }
        override fun onDiarized(segment: Int, parts: List<SpokenPart>) { diarized += segment to parts }
        override fun onSpeakersMerged(from: String, to: String) { events += "merged:$from>$to" }
        override fun onConnection(connected: Boolean, error: String?, fresh: Boolean) {
            events += "conn:$connected:$error" + if (fresh) ":fresh" else ""
        }
    }

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() {
        client?.close()
        runCatching { server.shutdown() }
        http.dispatcher.executorService.shutdown()
    }

    private fun connect(
        keyterms: List<String> = emptyList(),
        listener: Transcriber.Listener = recorder,
    ): ScribeClient = ScribeClient(
        "key-123", http, listener,
        baseUrl = server.url("/").toString().removeSuffix("/"),
        keyterms = keyterms,
    ).also { client = it; it.connect() }

    /** Legt fest, wie sich der Testserver bei der nächsten Verbindung verhält. */
    private fun serve(onOpen: (WebSocket) -> Unit = {}, onAudio: (WebSocket, String) -> Unit = { _, _ -> }) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)
            override fun onMessage(webSocket: WebSocket, text: String) = onAudio(webSocket, text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
    }

    private fun next(): String? = events.poll(5, TimeUnit.SECONDS)

    /** Ein Eintrag der Wortliste, wie ihn der Server bei eingeschalteter Sprechertrennung schickt. */
    private fun word(text: String, speaker: String?, start: Double? = null, end: Double? = null): String {
        val type = if (text.isBlank()) "spacing" else "word"
        val id = if (speaker == null) "null" else "\"$speaker\""
        val times = if (start != null && end != null) ""","start":$start,"end":$end""" else ""
        return """{"text":"$text","type":"$type","speaker_id":$id,"logprob":-0.1$times}"""
    }

    private fun words(vararg w: String) = w.joinToString(",", "[", "]")

    // ------------------------------------------------------------------------------------------
    // Verbindungsaufbau und Audio

    @Test fun `Verbindungsadresse nennt Modell Satzende-Erkennung Sprachen und Schluesselbegriffe`() {
        serve(onOpen = { it.send(STARTED) })
        connect(keyterms = listOf("Alfred", " Claude Voice ", "", "alfred", "Donaudampfschifffahrtsgesellschaft"))
        assertEquals("conn:true:null:fresh", next())

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        val url = request.requestUrl!!
        assertEquals("key-123", request.getHeader("xi-api-key"))
        assertEquals("/v1/speech-to-text/realtime", url.encodedPath)
        assertEquals("scribe_v3_live", url.queryParameter("model_id"))
        assertEquals("pcm_16000", url.queryParameter("audio_format"))
        assertEquals("turn_prediction", url.queryParameter("commit_strategy"))
        assertEquals("0.5", url.queryParameter("vad_silence_threshold_secs"))
        assertEquals("de", url.queryParameter("language_code"))
        assertEquals(listOf("en"), url.queryParameterValues("secondary_languages"))
        assertEquals("true", url.queryParameter("diarize"))
        assertEquals("3000", url.queryParameter("keepalive_interval_ms"))
        // Leere, doppelte und zu lange Begriffe (mehr als 20 Zeichen) fallen weg; je Begriff ein eigener Parameter.
        assertEquals(listOf("Alfred", "Claude Voice"), url.queryParameterValues("keyterms"))
        assertTrue(request.path, request.path!!.contains("keyterms=Alfred&keyterms=Claude%20Voice"))
    }

    @Test fun `Standardadresse mit wss wird zu einer gueltigen Anfrage`() {
        assertEquals(
            "https://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v3_live&audio_format=pcm_16000" +
                "&commit_strategy=turn_prediction&vad_silence_threshold_secs=0.5&language_code=de" +
                "&secondary_languages=en&diarize=true&keepalive_interval_ms=3000&keyterms=Alfred&keyterms=J%C3%B6rg",
            ScribeClient.url("wss://api.elevenlabs.io", listOf("Alfred", "Jörg"), conservative = false).toString(),
        )
        assertEquals(
            "http://localhost:8080/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&audio_format=pcm_16000" +
                "&commit_strategy=vad&vad_silence_threshold_secs=0.45",
            ScribeClient.url("ws://localhost:8080/", listOf("Alfred"), conservative = true).toString(),
        )
    }

    @Test fun `Schluesselbegriffe werden auf die Grenzen der Schnittstelle gekuerzt`() {
        val many = (1..60).map { "Wort$it" }
        assertEquals(many.take(50), ScribeClient.cleanKeyterms(many))
        assertEquals(listOf("genau zwanzig Zeiche"), ScribeClient.cleanKeyterms(listOf("genau zwanzig Zeiche", "einundzwanzig Zeichen")))
    }

    @Test fun `Audio wird erst gesendet wenn die Sitzung steht und nur in der angegebenen Laenge`() {
        val received = LinkedBlockingQueue<String>()
        val serverSide = LinkedBlockingQueue<WebSocket>()
        serve(onOpen = { serverSide += it }, onAudio = { _, text -> received += text })
        val client = connect()
        val ws = serverSide.poll(5, TimeUnit.SECONDS)!!

        client.sendAudio(byteArrayOf(9, 9), 2) // die Sitzung steht noch nicht: wird verworfen
        ws.send(STARTED)
        assertEquals("conn:true:null:fresh", next())
        client.sendAudio(byteArrayOf(1, 2, 3, 4, 99), 4)

        val sent = ObjectMapper().readTree(received.poll(5, TimeUnit.SECONDS))
        assertEquals("input_audio_chunk", sent["message_type"].asText())
        assertEquals(16000, sent["sample_rate"].asInt())
        assertFalse(sent["commit"].asBoolean())
        assertTrue(Base64.getDecoder().decode(sent["audio_base_64"].asText()).contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertNull(received.poll(200, TimeUnit.MILLISECONDS))
    }

    // ------------------------------------------------------------------------------------------
    // Text und Sprecher

    @Test fun `Zwischenstand traegt die Wahrscheinlichkeit fuers Satzende`() {
        serve(onOpen = {
            it.send(STARTED)
            it.send("""{"message_type":"partial_transcript","text":"Wie hoch ist","turn_probability":0.08}""")
            it.send("""{"message_type":"partial_transcript","text":"Wie hoch ist die Zugspitze","turn_probability":0.83}""")
            // Ohne Angabe (Vorgängermodell) und als leeres Lebenszeichen in einer Pause:
            it.send("""{"message_type":"partial_transcript","text":"Wie hoch ist die Zugspitze"}""")
            it.send("""{"message_type":"partial_transcript","text":""}""")
        })
        connect()
        assertEquals("conn:true:null:fresh", next())
        assertEquals("partial:Wie hoch ist:0.08", next())
        assertEquals("partial:Wie hoch ist die Zugspitze:0.83", next())
        assertEquals("partial:Wie hoch ist die Zugspitze:null", next())
        assertEquals("partial::null", next())
    }

    @Test fun `Fertige Aeusserung traegt Segmentnummer und den Sprecher des letzten Zwischenstands`() {
        serve(onOpen = {
            it.send(STARTED)
            // speaker_1 hat den größeren Anteil am Text und gilt deshalb als Sprecher der Äußerung.
            val draft = words(word("Ja", "speaker_0"), word(" ", "speaker_0"), word("wie", "speaker_1"),
                word(" ", "speaker_1"), word("hoch", "speaker_1"), word(" ", null), word("ist", null))
            it.send("""{"message_type":"partial_transcript","text":"Ja wie hoch ist","turn_probability":0.2,"words":$draft}""")
            it.send("""{"message_type":"committed_transcript","text":"Ja, wie hoch ist die Zugspitze?","segment_index":4}""")
            // Kein Zwischenstand davor: Sprecher unbekannt.
            it.send("""{"message_type":"committed_transcript","text":"Keine Ahnung.","segment_index":5}""")
            // Ein Lebenszeichen, das nur den Text wiederholt, lässt den gemerkten Sprecher stehen ...
            it.send("""{"message_type":"partial_transcript","text":"Fast dreitausend","words":${words(word("Fast", "speaker_2"))}}""")
            it.send("""{"message_type":"partial_transcript","text":"Fast dreitausend"}""")
            it.send("""{"message_type":"committed_transcript","text":"Fast dreitausend Meter.","segment_index":6}""")
            // ... ein leeres dagegen heißt "nichts offen" und vergisst ihn.
            it.send("""{"message_type":"partial_transcript","text":"Aha","words":${words(word("Aha", "speaker_0"))}}""")
            it.send("""{"message_type":"partial_transcript","text":""}""")
            it.send("""{"message_type":"committed_transcript","text":"Aha."}""")
        })
        connect()
        assertEquals("conn:true:null:fresh", next())
        assertEquals("partial:Ja wie hoch ist:0.2", next())
        assertEquals("committed:Ja, wie hoch ist die Zugspitze?:speaker_1:4", next())
        assertEquals("committed:Keine Ahnung.:null:5", next())
        assertEquals("partial:Fast dreitausend:null", next())
        assertEquals("partial:Fast dreitausend:null", next())
        assertEquals("committed:Fast dreitausend Meter.:speaker_2:6", next())
        assertEquals("partial:Aha:null", next())
        assertEquals("partial::null", next())
        assertEquals("committed:Aha.:null:null", next())
    }

    @Test fun `Diarisierung fasst aufeinanderfolgende Woerter eines Sprechers zusammen`() {
        serve(onOpen = {
            it.send(STARTED)
            // Das Leerzeichen an der Nahtstelle trägt schon die neue Sprecher-ID, bleibt aber am alten Stück.
            val two = words(
                word("hallo", "speaker_0", 0.0, 0.3), word(" ", "speaker_0", 0.3, 0.35), word("zusammen", "speaker_0", 0.35, 0.8),
                word(" ", "speaker_1", 0.8, 0.9), word("wie", "speaker_1", 0.9, 1.0), word(" ", "speaker_1", 1.0, 1.05),
                word("gehts", "speaker_1", 1.05, 1.3), word(" ", "speaker_1", 1.3, 1.35), word("euch", "speaker_1", 1.35, 1.6),
            )
            it.send("""{"message_type":"diarized_transcript","segment_index":7,"text":"hallo zusammen wie gehts euch","words":$two}""")
            // Knappe Form wie im Beispiel der Doku: ohne Zeiten, ohne Typ, ein Sprecher noch unbekannt.
            it.send(
                """{"message_type":"diarized_transcript","segment_index":8,"text":"Hm. Genau.","words":[""" +
                    """{"text":"Hm.","speaker_id":null},{"text":" "},{"text":"Genau.","speaker_id":"speaker_0"}]}"""
            )
            // Ohne Segmentnummer lässt sich nichts zuordnen, ohne Wörter gibt es nichts zu melden.
            it.send("""{"message_type":"diarized_transcript","text":"verloren","words":${words(word("verloren", "speaker_0"))}}""")
            it.send("""{"message_type":"diarized_transcript","segment_index":9,"text":"","words":[]}""")
        })
        connect()
        assertEquals("conn:true:null:fresh", next())
        assertEquals(
            7 to listOf(SpokenPart("speaker_0", "hallo zusammen ", 0.0, 0.8), SpokenPart("speaker_1", "wie gehts euch", 0.9, 1.6)),
            diarized.poll(5, TimeUnit.SECONDS),
        )
        assertEquals(
            8 to listOf(SpokenPart(null, "Hm. ", 0.0, 0.0), SpokenPart("speaker_0", "Genau.", 0.0, 0.0)),
            diarized.poll(5, TimeUnit.SECONDS),
        )
        assertNull(diarized.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `Zusammengelegte Sprecher werden gemeldet und gelten auch fuer den laufenden Zwischenstand`() {
        serve(onOpen = {
            it.send(STARTED)
            it.send("""{"message_type":"partial_transcript","text":"Wie hoch","turn_probability":0.4,"words":${words(word("Wie", "speaker_1"))}}""")
            it.send("""{"message_type":"speakers_merged","from_speaker_id":"speaker_1","to_speaker_id":"speaker_0"}""")
            it.send("""{"message_type":"committed_transcript","text":"Wie hoch ist das?","segment_index":2}""")
        })
        connect()
        assertEquals("conn:true:null:fresh", next())
        assertEquals("partial:Wie hoch:0.4", next())
        assertEquals("merged:speaker_1>speaker_0", next())
        assertEquals("committed:Wie hoch ist das?:speaker_0:2", next())
    }

    @Test fun `Hinweise und commit_throttled und unbekannte Nachrichten werden uebergangen`() {
        serve(onOpen = {
            it.send(STARTED)
            it.send("""{"message_type":"warning","warning":"Etwas ist ungewöhnlich."}""")
            it.send("""{"message_type":"commit_throttled","error":"Too many commits."}""")
            // Darf nicht als zweite fertige Äußerung zählen:
            it.send("""{"message_type":"committed_transcript_with_timestamps","text":"Hallo.","words":[]}""")
            it.send("""{"message_type":"etwas_neues","text":"?"}""")
            it.send("kein JSON")
            it.send("""{"message_type":"committed_transcript","text":"Hallo."}""")
        })
        connect()
        assertEquals("conn:true:null:fresh", next())
        assertEquals("committed:Hallo.:null:null", next())
        assertNull(events.poll(200, TimeUnit.MILLISECONDS))
    }

    // ------------------------------------------------------------------------------------------
    // Fehler und Neuverbinden

    @Test fun `Nach invalid_request laeuft die Sitzung einmalig mit der einfachen Einstellung weiter`() {
        serve(onOpen = {
            it.send("""{"message_type":"invalid_request","error":"The 'diarize' parameter is only supported by scribe_v3_live."}""")
            it.close(1008, "invalid")
        })
        serve(onOpen = { it.send(STARTED) }, onAudio = { ws, _ ->
            ws.send("""{"message_type":"partial_transcript","text":"Wie hoch"}""")
            ws.send("""{"message_type":"committed_transcript","text":"Wie hoch ist die Zugspitze?"}""")
            // Ein weiteres invalid_request ist ein gewöhnlicher Fehler: Es gibt nur einen Rückfall.
            ws.send("""{"message_type":"invalid_request","error":"kaputt"}""")
        })
        serve(onOpen = { it.send(STARTED) })
        val client = connect(keyterms = listOf("Alfred"))

        // Kein Fehler vorweg: Die erste Meldung ist schon die stehende Sitzung, mit einem Hinweis.
        val notice = "conn:true:Transkription läuft im einfachen Modus ohne Sprechertrennung " +
            "(Server: The 'diarize' parameter is only supported by scribe_v3_live.):fresh"
        assertEquals(notice, next())
        client.sendAudio(byteArrayOf(1, 2), 2)
        assertEquals("partial:Wie hoch:null", next())
        assertEquals("committed:Wie hoch ist die Zugspitze?:null:null", next())
        assertEquals("conn:false:Transkription meldet: kaputt", next())
        assertEquals(notice, next()) // nach der Wartezeit: weiterhin die einfache Einstellung

        val first = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!
        assertEquals("scribe_v3_live", first.queryParameter("model_id"))
        assertEquals(listOf("Alfred"), first.queryParameterValues("keyterms"))
        for (attempt in 2..3) {
            val url = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!
            assertEquals(
                "model_id=scribe_v2_realtime&audio_format=pcm_16000&commit_strategy=vad&vad_silence_threshold_secs=0.45",
                url.encodedQuery,
            )
        }
    }

    @Test fun `Antwortet der Server schon beim Aufbau mit 400 greift ebenfalls die einfache Einstellung`() {
        server.enqueue(MockResponse().setResponseCode(400))
        serve(onOpen = { it.send(STARTED) })
        connect()
        assertEquals(
            "conn:true:Transkription läuft im einfachen Modus ohne Sprechertrennung (Server: HTTP 400):fresh",
            next(),
        )
        assertEquals("scribe_v3_live", server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("model_id"))
        assertEquals("scribe_v2_realtime", server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("model_id"))
    }

    @Test fun `Abgelehnter Key beim Verbindungsaufbau wird gemeldet`() {
        server.enqueue(MockResponse().setResponseCode(401))
        connect()
        assertEquals("conn:false:ElevenLabs-Key wurde abgelehnt (401)", next())
    }

    @Test fun `Fehlermeldung auth_error wird als abgelehnter Key gemeldet`() {
        serve(onOpen = {
            it.send("""{"message_type":"auth_error","error":"Invalid API key."}""")
            it.close(1008, "auth")
        })
        connect()
        assertEquals("conn:false:ElevenLabs-Key wurde abgelehnt", next())
        // Nur eine Meldung, auch wenn der Server danach noch die Verbindung schließt.
        assertNull(events.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `Jede Nachricht mit error-Feld gilt als Fehler auch mit unbekanntem Typ`() {
        serve(onOpen = {
            it.send(STARTED)
            it.send("""{"message_type":"etwas_neues","error":"Server  sagt\nnein"}""")
        })
        connect()
        assertEquals("conn:true:null:fresh", next())
        assertEquals("conn:false:Transkription meldet: Server sagt nein", next())
    }

    @Test fun `Fehlerarten werden in kurze deutsche Meldungen uebersetzt`() {
        assertEquals("ElevenLabs-Key wurde abgelehnt", ScribeClient.errorText("auth_error", "x"))
        assertEquals("ElevenLabs-Guthaben ist aufgebraucht", ScribeClient.errorText("quota_exceeded", "x"))
        assertEquals(
            "ElevenLabs: Nutzungsbedingungen für die Transkription im Konto noch nicht bestätigt",
            ScribeClient.errorText("unaccepted_terms", "x"),
        )
        assertEquals("Transkription: zu viele Anfragen, neuer Versuch folgt", ScribeClient.errorText("rate_limited", "x"))
        assertEquals("Transkription ist gerade überlastet, neuer Versuch folgt", ScribeClient.errorText("resource_exhausted", "x"))
        assertEquals("Transkription meldet: boom", ScribeClient.errorText("transcriber_error", "boom"))
        assertEquals("Transkription meldet: queue_overflow", ScribeClient.errorText("queue_overflow", ""))
    }

    @Test fun `Nach session_time_limit_exceeded wird still und sofort neu verbunden`() {
        val limitAt = AtomicLong()
        val gapMs = LinkedBlockingQueue<Long>()
        serve(onOpen = { it.send(STARTED) }, onAudio = { ws, _ ->
            limitAt.set(System.nanoTime())
            ws.send("""{"message_type":"session_time_limit_exceeded","error":"Maximum session time has been reached."}""")
            ws.close(1000, "limit")
        })
        serve(
            onOpen = { gapMs += (System.nanoTime() - limitAt.get()) / 1_000_000; it.send(STARTED) },
            onAudio = { ws, _ -> ws.send("""{"message_type":"committed_transcript","text":"weiter gehts"}""") },
        )
        val client = connect()
        assertEquals("conn:true:null:fresh", next())
        client.sendAudio(byteArrayOf(1, 2), 2)

        // Die App erfährt nur, dass kurz keine Sitzung steht (damit sie Audio zurückhält), aber keinen Fehler.
        assertEquals("conn:false:null", next())
        assertEquals("conn:true:null:fresh", next())
        client.sendAudio(byteArrayOf(3, 4), 2)
        assertEquals("committed:weiter gehts:null:null", next())

        // Die gewöhnliche Wartezeit vor einem neuen Versuch wäre mindestens eine Sekunde.
        val gap = gapMs.poll(5, TimeUnit.SECONDS)!!
        assertTrue("Neuverbinden dauerte $gap ms", gap < 900)
        assertEquals(2, server.requestCount)
    }

    @Test fun `Meldet der Server das Zeitlimit gleich wieder wird es als Fehler angezeigt`() {
        val limit = """{"message_type":"session_time_limit_exceeded","error":"Maximum session time has been reached."}"""
        serve(onOpen = { it.send(STARTED); it.send(limit) })
        serve(onOpen = { it.send(STARTED); it.send(limit) })
        connect()
        assertEquals("conn:true:null:fresh", next())
        assertEquals("conn:false:null", next()) // beim ersten Mal still
        assertEquals("conn:true:null:fresh", next())
        // Eine Sitzung, die sofort wieder am Limit ist, wäre sonst eine stille Dauerschleife.
        assertEquals("conn:false:Transkription meldet: Maximum session time has been reached.", next())
    }

    @Test fun `Beendet der Server die Verbindung wird das gemeldet und neu verbunden`() {
        serve(onOpen = { it.send(STARTED); it.close(1001, "Wartung") })
        serve(onOpen = { it.send(STARTED) })
        connect()
        assertEquals("conn:true:null:fresh", next())
        assertEquals("conn:false:Verbindung beendet (1001 Wartung)", next())
        assertEquals("conn:true:null:fresh", next()) // nach rund einer Sekunde Wartezeit
    }

    @Test fun `Wartezeit vor dem Neuverbinden waechst bis hoechstens 15 Sekunden`() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L),
            listOf(0, 1, 2, 3, 4, 5, 40).map { ScribeClient.reconnectDelayMs(it, atOnce = false) },
        )
        // "Sofort" gilt nur für den ersten Versuch, sonst könnte daraus eine Dauerschleife werden.
        assertEquals(0L, ScribeClient.reconnectDelayMs(0, atOnce = true))
        assertEquals(2_000L, ScribeClient.reconnectDelayMs(1, atOnce = true))
    }

    // ------------------------------------------------------------------------------------------
    // Beenden und Threads

    @Test fun `Nach close kommt nichts mehr und es wird nicht neu verbunden`() {
        serve(onOpen = { it.send(STARTED) })
        val client = connect()
        assertEquals("conn:true:null:fresh", next())

        client.close()
        client.sendAudio(byteArrayOf(1, 2), 2) // darf weder werfen noch etwas senden
        client.connect()                       // wirkungslos
        assertNull(events.poll(400, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test fun `Ein Fehler im Listener beendet die Sitzung nicht`() {
        serve(onOpen = {
            it.send(STARTED)
            it.send("""{"message_type":"partial_transcript","text":"Wie"}""")
            it.send("""{"message_type":"committed_transcript","text":"Wie hoch?"}""")
        })
        connect(listener = object : Transcriber.Listener by recorder {
            override fun onPartial(text: String, turnProbability: Double?) { error("Fehler in der App") }
        })
        assertEquals("conn:true:null:fresh", next())
        assertEquals("conn:true:Transkription: interner Fehler (IllegalStateException)", next())
        assertEquals("committed:Wie hoch?:null:null", next())
    }

    @Test fun `Audio aus dem Mikrofon-Thread stoert weder Neuverbinden noch close`() {
        val limitSent = AtomicBoolean()
        serve(onOpen = { it.send(STARTED) }, onAudio = { ws, _ ->
            if (limitSent.compareAndSet(false, true)) {
                ws.send("""{"message_type":"session_time_limit_exceeded","error":"limit"}""")
            }
        })
        serve(onOpen = { it.send(STARTED) })
        val client = connect()
        assertEquals("conn:true:null:fresh", next())

        val running = AtomicBoolean(true)
        val failure = AtomicReference<Throwable>()
        val chunk = ByteArray(3200)
        val mic = thread {
            try {
                while (running.get()) { client.sendAudio(chunk, chunk.size); Thread.sleep(1) }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        assertEquals("conn:false:null", next())
        assertEquals("conn:true:null:fresh", next())
        client.close()
        Thread.sleep(50) // das Mikrofon läuft nach dem Schließen noch kurz weiter
        running.set(false)
        mic.join(2_000)
        assertNull(failure.get())
        assertFalse(mic.isAlive)
    }

    private companion object {
        const val STARTED = """{"message_type":"session_started","session_id":"s1","config":{"model_id":"scribe_v3_live"}}"""
    }
}
