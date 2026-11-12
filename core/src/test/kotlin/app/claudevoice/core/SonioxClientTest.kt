package app.claudevoice.core

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Der Soniox-Client gegen einen nachgebauten Server (MockWebServer). Der echte Dienst wird hier nie
 * angesprochen; geprüft wird, dass wir das Protokoll so sprechen, wie es in der Soniox-Doku steht.
 */
class SonioxClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val mapper = ObjectMapper()
    private val clients = ArrayList<SonioxClient>()

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() {
        clients.forEach { it.close() }
        runCatching { server.shutdown() }
    }

    // ------------------------------------------------------------------------------------------
    // Helfer

    /** Spielt den Soniox-Server: merkt sich, was ankommt, und antwortet beim Öffnen nach Drehbuch. */
    private class Peer(private val script: (WebSocket) -> Unit = {}) : WebSocketListener() {
        val texts = LinkedBlockingQueue<String>()
        val audio = LinkedBlockingQueue<ByteString>()
        val closing = LinkedBlockingQueue<Int>()

        override fun onOpen(webSocket: WebSocket, response: Response) = script(webSocket)
        override fun onMessage(webSocket: WebSocket, text: String) { texts += text }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { audio += bytes }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closing += code
            webSocket.close(1000, null)
        }
    }

    /** Schreibt mit, was der Client meldet, samt Zeitpunkt (für die Wartezeiten beim Neuverbinden). */
    private class Recorder : Transcriber.Listener {
        class Event(val text: String, val atNs: Long)

        val events = LinkedBlockingQueue<Event>()

        private fun add(text: String) { events += Event(text, System.nanoTime()) }
        override fun onPartial(text: String, turnProbability: Double?) = add("partial:$text")
        override fun onCommitted(text: String, speaker: String?, segment: Int?) = add("committed:$segment:$speaker:$text")
        override fun onDiarized(segment: Int, parts: List<SpokenPart>) =
            add("diarized:$segment:" + parts.joinToString(" | ") { "${it.speaker}=${it.text}@${it.startSec}-${it.endSec}" })
        override fun onConnection(connected: Boolean, error: String?, fresh: Boolean) = add("conn:$connected:$error:$fresh")

        fun nextEvent(): Event? = events.poll(10, TimeUnit.SECONDS)
        fun next(): String? = nextEvent()?.text
    }

    private fun upgrade(peer: Peer) = MockResponse().withWebSocketUpgrade(peer)

    private fun client(rec: Recorder, key: String = "key-123", keyterms: List<String> = emptyList(), keepaliveMs: Long = 8_000, retryBaseMs: Long = 1_000) =
        SonioxClient(key, http, rec, server.url("/").toString().removeSuffix("/"), keyterms, keepaliveMs, retryBaseMs).also { clients += it }

    /** Ein Token, wie Soniox es schickt. */
    private fun tok(text: String, final: Boolean, speaker: String? = null, startMs: Int = 0, endMs: Int = 0): String {
        val node = mapper.createObjectNode().put("text", text).put("start_ms", startMs).put("end_ms", endMs)
            .put("confidence", 0.97).put("is_final", final)
        if (speaker != null) node.put("speaker", speaker).put("language", "de")
        return mapper.writeValueAsString(node)
    }

    private val end = """{"text":"<end>","is_final":true}"""

    private fun msg(vararg tokens: String) =
        """{"tokens":[${tokens.joinToString(",")}],"final_audio_proc_ms":760,"total_audio_proc_ms":880}"""

    private fun error(code: Int, type: String) =
        """{"tokens":[],"final_audio_proc_ms":0,"total_audio_proc_ms":0,"error_code":$code,"error_type":"$type",""" +
            """"error_message":"Some English explanation.","more_info":"https://example.invalid/errors#$type","request_id":"r-1"}"""

    private fun ms(from: Recorder.Event, to: Recorder.Event) = (to.atNs - from.atNs) / 1_000_000

    // ------------------------------------------------------------------------------------------
    // Verbindungsaufbau und Audio

    @Test fun `Soniox schickt zuerst die Einstellungen und danach Audio als Binaer-Frames`() {
        val peer = Peer()
        server.enqueue(upgrade(peer))
        val rec = Recorder()
        val client = client(rec, keyterms = listOf("Alfred", " Zugspitze ", "", "Alfred"))

        client.sendAudio(byteArrayOf(7, 7), 2) // vor dem Verbinden: wird verworfen
        client.connect()
        assertEquals("conn:true:null:true", rec.next())
        client.sendAudio(byteArrayOf(1, 2, 3, 4, 99), 4)

        val config = mapper.readTree(peer.texts.poll(10, TimeUnit.SECONDS))
        assertEquals("stt-rt-v6", config["model"].asText())
        assertEquals("pcm_s16le", config["audio_format"].asText())
        assertEquals(16000, config["sample_rate"].asInt())
        assertEquals(1, config["num_channels"].asInt())
        assertEquals(listOf("de", "en"), config["language_hints"].map { it.asText() })
        assertTrue(config["enable_language_identification"].isBoolean && config["enable_language_identification"].asBoolean())
        assertTrue(config["enable_speaker_diarization"].isBoolean && config["enable_speaker_diarization"].asBoolean())
        assertTrue(config["enable_endpoint_detection"].isBoolean && config["enable_endpoint_detection"].asBoolean())
        assertEquals(1000, config["max_endpoint_delay_ms"].asInt())
        // Leere und doppelte Begriffe fallen weg.
        assertEquals(listOf("Alfred", "Zugspitze"), config["context"]["terms"].map { it.asText() })
        // Der Key gehört in den Header, nicht in die Nachricht (dort ist er veraltet).
        assertFalse(config.has("api_key"))

        assertEquals(byteArrayOf(1, 2, 3, 4).toByteString(), peer.audio.poll(10, TimeUnit.SECONDS))

        val request = server.takeRequest()
        assertEquals("/transcribe-websocket", request.path)
        assertEquals("Bearer key-123", request.getHeader("Authorization"))
        // Header UND Subprotokoll zusammen lehnt Soniox ab.
        assertNull(request.getHeader("Sec-WebSocket-Protocol"))
    }

    @Test fun `Ohne Begriffe gibt es keinen Kontext in den Einstellungen`() {
        val peer = Peer()
        server.enqueue(upgrade(peer))
        val rec = Recorder()
        client(rec).connect()
        assertEquals("conn:true:null:true", rec.next())
        assertFalse(mapper.readTree(peer.texts.poll(10, TimeUnit.SECONDS)).has("context"))
    }

    // ------------------------------------------------------------------------------------------
    // Tokens

    @Test fun `Zwischenstand besteht aus festen plus vorlaeufigen Tokens`() {
        val third = msg(tok(" hoch", true), tok(" ist", false), tok(" die", false), tok(" Zug", false))
        server.enqueue(upgrade(Peer { ws ->
            // Vorläufig und noch falsch gehört ...
            ws.send(msg(tok("Wie", false), tok(" hoh", false)))
            // ... das erste Wort steht fest, der Rest wird ersetzt (nicht angehängt).
            ws.send(msg(tok("Wie", true), tok(" hoch", false), tok(" ist", false)))
            ws.send(third)
            // Dieselbe Nachricht noch einmal ändert nichts und wird nicht erneut gemeldet.
            ws.send(msg(tok(" ist", false), tok(" die", false), tok(" Zug", false)))
            ws.send(msg(tok(" ist", true), tok(" die", true), tok(" Zug", false), tok("spitze", false), tok("?", false)))
            ws.send(msg(tok(" Zug", true), tok("spitze", true), tok("?", true), end))
        }))
        val rec = Recorder()
        client(rec).connect()

        assertEquals("conn:true:null:true", rec.next())
        assertEquals("partial:Wie hoh", rec.next())
        assertEquals("partial:Wie hoch ist", rec.next())
        assertEquals("partial:Wie hoch ist die Zug", rec.next())
        assertEquals("partial:Wie hoch ist die Zugspitze?", rec.next())
        // Ohne Sprecher-Angaben bleibt der Sprecher unbekannt (null).
        assertEquals("committed:0:null:Wie hoch ist die Zugspitze?", rec.next())
        assertEquals("diarized:0:null=Wie hoch ist die Zugspitze?@0.0-0.0", rec.next())
    }

    @Test fun `Turn-Ende meldet Aeusserung mit Hauptsprecher und Teilen je Stimme`() {
        server.enqueue(upgrade(Peer { ws ->
            ws.send(msg(
                tok("Wie", true, "1", 600, 700), tok(" geht", true, "1", 700, 900), tok(" es", true, "1", 900, 1000),
                tok(" dir", true, "1", 1000, 1300), tok("?", true, "1", 1300, 1400),
                // Die zweite Stimme beginnt wie im Beispiel der Doku ohne führendes Leerzeichen.
                tok("Gut", false, "2", 1600, 1750),
            ))
            ws.send(msg(
                tok("Gut", true, "2", 1600, 1750), tok(",", true, "2", 1750, 1800),
                tok(" danke", true, "2", 1800, 2100), tok(".", true, "2", 2100, 2200), end,
            ))
            // Nächster Redebeitrag; "<fin>" ist nur eine Bestätigung und gehört nicht zum Text.
            ws.send(msg(
                """{"text":"<fin>","is_final":true}""",
                tok("Und", true, "2", 3000, 3200), tok(" du", true, "2", 3200, 3400), tok("?", true, "2", 3400, 3500), end,
            ))
        }))
        val rec = Recorder()
        client(rec).connect()

        assertEquals("conn:true:null:true", rec.next())
        assertEquals("partial:Wie geht es dir? Gut", rec.next())
        // Sprecher 1 hat mehr gesagt, also gilt die Äußerung als seine; die Teile halten beide Stimmen auseinander.
        assertEquals("committed:0:1:Wie geht es dir? Gut, danke.", rec.next())
        assertEquals("diarized:0:1=Wie geht es dir?@0.6-1.4 | 2=Gut, danke.@1.6-2.2", rec.next())
        assertEquals("committed:1:2:Und du?", rec.next())
        assertEquals("diarized:1:2=Und du?@3.0-3.5", rec.next())
    }

    @Test fun `Redebeitrag ohne Pause wird an einer Wortgrenze geteilt`() {
        // "Hmmmmm" + 59 Wörter sind 596 Zeichen. Mit " Zug" ist die Grenze von 600 erreicht, aber "spitze"
        // gehört noch zum selben Wort. Geteilt wird erst vor dem nächsten Wort.
        val tokens = ArrayList<String>()
        tokens += tok("Hmmmmm", true, "1")
        repeat(65) { tokens += tok(" Zug", true, "1"); tokens += tok("spitze", true, "1") }
        server.enqueue(upgrade(Peer { ws -> ws.send(msg(*tokens.toTypedArray())) }))
        val rec = Recorder()
        client(rec).connect()

        assertEquals("conn:true:null:true", rec.next())
        assertEquals("committed:0:1:Hmmmmm" + " Zugspitze".repeat(60), rec.next())
        assertTrue(rec.next()!!.startsWith("diarized:0:1=Hmmmmm Zugspitze"))
        assertEquals("partial:" + "Zugspitze ".repeat(5).trim(), rec.next())
    }

    // ------------------------------------------------------------------------------------------
    // Fehler und Neuverbinden

    @Test fun `Fehlermeldungen werden kurz auf Deutsch gemeldet und es wird neu verbunden`() {
        // So sieht die Antwort des echten Dienstes auf einen falschen Key aus: Der Handshake gelingt,
        // dann kommt sofort eine Fehlermeldung und der Server schließt mit Code 1000.
        val unauthenticated = """{"tokens":[],"final_audio_proc_ms":0,"total_audio_proc_ms":0,"error_code":401,""" +
            """"error_type":"unauthenticated","error_message":"Incorrect API key provided. You can get an API key at https://console.soniox.com",""" +
            """"more_info":"https://soniox.com/docs/api-reference/errors#unauthenticated","request_id":"00000000-0000-0000-0000-000000000000"}"""
        fun failing(frame: String) = upgrade(Peer { ws -> ws.send(frame); ws.close(1000, null) })
        server.enqueue(failing(unauthenticated))
        server.enqueue(failing(error(402, "organization_balance_exhausted")))
        server.enqueue(failing(error(429, "max_concurrent_connections_reached")))
        server.enqueue(failing(error(400, "invalid_request")))
        server.enqueue(upgrade(Peer { ws -> ws.close(1001, "bye") }))
        val rec = Recorder()
        client(rec, retryBaseMs = 20).connect()

        for (expected in listOf(
            "Soniox-Key wurde abgelehnt (401)",
            "Soniox-Guthaben oder Budget ist aufgebraucht (402)",
            "Soniox-Limit erreicht (429)",
            "Soniox meldet Fehler 400 (invalid_request)",
            "Verbindung beendet (1001 bye)",
        )) {
            assertEquals("conn:true:null:true", rec.next())
            assertEquals("conn:false:$expected:false", rec.next())
        }
    }

    @Test fun `Abgelehnter Handshake wird gemeldet und die Wartezeit waechst`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(upgrade(Peer()))
        val rec = Recorder()
        client(rec, retryBaseMs = 200).connect()

        val first = rec.nextEvent()!!
        assertEquals("conn:false:Soniox-Key wurde abgelehnt (401):false", first.text)
        val second = rec.nextEvent()!!
        assertEquals("conn:false:Soniox ist kurz gestört (503):false", second.text)
        val third = rec.nextEvent()!!
        assertEquals("conn:true:null:true", third.text)
        // Erst 200 ms, dann 400 ms. Nur Untergrenzen prüfen: Auf einem ausgelasteten Rechner dauert alles länger.
        assertTrue("erste Wartezeit ${ms(first, second)} ms", ms(first, second) >= 190)
        assertTrue("zweite Wartezeit ${ms(second, third)} ms", ms(second, third) >= 390)
    }

    @Test fun `Sitzungsgrenze 413 verbindet still und sofort neu`() {
        server.enqueue(upgrade(Peer { ws ->
            ws.send(msg(tok("Moment", true, "1", 100, 400), tok(" noch", true, "1", 400, 600), tok(" ich", false, "1")))
            ws.send(error(413, "max_duration_reached"))
            ws.close(1000, null)
        }))
        server.enqueue(upgrade(Peer { ws -> ws.send(msg(tok("Weiter", true, "1", 100, 400), end)) }))
        val rec = Recorder()
        // Die normale Wartezeit wäre hier eine Minute. Kommt die neue Verbindung trotzdem gleich, ging sie daran vorbei.
        client(rec, retryBaseMs = 60_000).connect()

        assertEquals("conn:true:null:true", rec.next())
        assertEquals("partial:Moment noch ich", rec.next())
        // Was schon feststand, geht nicht verloren, auch wenn der Satz noch nicht zu Ende war.
        assertEquals("committed:0:1:Moment noch", rec.next())
        assertEquals("diarized:0:1=Moment noch@0.1-0.6", rec.next())
        // Still: keine Fehlermeldung für den Nutzer.
        assertEquals("conn:false:null:false", rec.next())
        assertEquals("conn:true:null:true", rec.next())
        // Neue Sitzung: Die Segmentnummern beginnen wieder bei 0.
        assertEquals("committed:0:1:Weiter", rec.next())
    }

    @Test fun `413 gleich nach dem Oeffnen wartet wie ein normaler Abbruch`() {
        // Schutz vor einer Endlosschleife: Lief die Sitzung nie, gibt es kein sofortiges Neuverbinden.
        server.enqueue(upgrade(Peer { ws -> ws.send(error(413, "max_duration_reached")); ws.close(1000, null) }))
        server.enqueue(upgrade(Peer()))
        val rec = Recorder()
        client(rec, retryBaseMs = 300).connect()

        assertEquals("conn:true:null:true", rec.next())
        val dropped = rec.nextEvent()!!
        assertEquals("conn:false:null:false", dropped.text)
        val again = rec.nextEvent()!!
        assertEquals("conn:true:null:true", again.text)
        assertTrue("Wartezeit ${ms(dropped, again)} ms", ms(dropped, again) >= 290)
    }

    @Test fun `Key mit ungueltigen Zeichen wird gemeldet statt abzustuerzen`() {
        val rec = Recorder()
        client(rec, key = "abc\ndef").connect()
        assertEquals("conn:false:Soniox-Key enthält ungültige Zeichen:false", rec.next())
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------------------------------------
    // Lebenszeichen und Beenden

    @Test fun `Lebenszeichen gehen nur raus solange kein Audio fliesst`() {
        val peer = Peer()
        server.enqueue(upgrade(peer))
        val rec = Recorder()
        val client = client(rec, keepaliveMs = 600)
        client.connect()
        assertEquals("conn:true:null:true", rec.next())
        assertEquals("stt-rt-v6", mapper.readTree(peer.texts.poll(10, TimeUnit.SECONDS))["model"].asText())

        // Solange Audio fließt (hier 1,5 s lang alle 20 ms ein Stück), braucht es kein Lebenszeichen.
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500)
        while (System.nanoTime() < until) {
            client.sendAudio(ByteArray(640), 640)
            Thread.sleep(20)
        }
        assertNull("Lebenszeichen trotz Audio", peer.texts.poll())

        // Danach ist es still: Jetzt muss eines kommen, und in genau dieser Form.
        assertEquals("""{"type":"keepalive"}""", peer.texts.poll(10, TimeUnit.SECONDS))
    }

    @Test fun `close schickt ein leeres Text-Frame und beendet die Verbindung`() {
        val peer = Peer()
        server.enqueue(upgrade(peer))
        val rec = Recorder()
        val client = client(rec)
        client.connect()
        assertEquals("conn:true:null:true", rec.next())
        assertEquals("stt-rt-v6", mapper.readTree(peer.texts.poll(10, TimeUnit.SECONDS))["model"].asText())

        client.close()
        // Das leere Text-Frame sagt Soniox "kein Audio mehr", danach folgt der normale Abschied.
        assertEquals("", peer.texts.poll(10, TimeUnit.SECONDS))
        assertEquals(1000, peer.closing.poll(10, TimeUnit.SECONDS))
        // Selbst beendet: keine Meldung "Verbindung weg" und kein neuer Versuch.
        assertNull(rec.events.poll(500, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
        client.sendAudio(byteArrayOf(1, 2), 2) // nach dem Schließen: wird verworfen, kein Absturz
    }
}
