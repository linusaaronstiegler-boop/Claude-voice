package app.claudevoice.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Die Tests spielen ElevenLabs mit einem kleinen lokalen Server nach. Das "Audio" eines Satzes sind dabei
 * einfach seine Buchstaben als Bytes: So lässt sich am Ergebnis ablesen, was in welcher Reihenfolge ankam.
 */
class ElevenTtsTest {
    private lateinit var server: MockWebServer
    private lateinit var tts: ElevenTts
    private val http = OkHttpClient()
    private val mapper = ObjectMapper()
    /** Alle Anfragen, die der Server gesehen hat, in Reihenfolge. */
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    /** Was die Sprachausgabe über [ElevenTts.onTrouble] gemeldet hat. */
    private val troubles = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        tts = ElevenTts("key-123", http, "voice42", server.url("/").toString().removeSuffix("/"))
        tts.onTrouble = { troubles += it }
    }

    @After fun tearDown() {
        tts.close()
        runCatching { server.shutdown() }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    // ------------------------------------------------------------------------------------------
    // Schneller Weg: WebSocket

    @Test fun `WebSocket spricht jeden Satz sofort und liefert das Audio in Reihenfolge`() {
        val fake = FakeSocket()
        serve(fake)
        val heard = StringBuffer()
        runBlocking {
            withTimeout(10_000) {
                val sentences = Channel<String>(Channel.UNLIMITED)
                val firstHeard = CompletableDeferred<Unit>()
                val job = launch {
                    tts.synthesize(sentences) { pcm, n ->
                        heard.append(String(pcm, 0, n))
                        if (heard.toString() == "Erster Satz.") firstHeard.complete(Unit)
                    }
                }
                sentences.send("Erster Satz.")
                // Den zweiten Satz gibt es noch gar nicht: Trotzdem muss der erste schon zu hören sein.
                firstHeard.await()
                sentences.send("Zweiter Satz.")
                sentences.close()
                job.join()
            }
        }
        assertEquals("Erster Satz.Zweiter Satz.", heard.toString())

        val url = requests.single().requestUrl!!
        assertEquals("/v1/text-to-speech/voice42/multi-stream-input", url.encodedPath)
        assertEquals("eleven_flash_v2_5", url.queryParameter("model_id"))
        assertEquals("pcm_24000", url.queryParameter("output_format"))
        assertEquals("180", url.queryParameter("inactivity_timeout"))
        assertEquals("true", url.queryParameter("auto_mode"))
        assertNull(url.queryParameter("language_code")) // Antworten können deutsch oder englisch sein
        assertEquals("key-123", requests.single().getHeader("xi-api-key"))

        val sent = List(5) { fake.messages.poll(5, TimeUnit.SECONDS)!! }
        val id = sent[0]["context_id"].asText()
        assertTrue(sent.all { it["context_id"].asText() == id })
        assertEquals(" ", sent[0]["text"].asText()) // legt den Kontext an
        assertEquals("Erster Satz. ", sent[1]["text"].asText()) // Satz endet mit einem Leerzeichen
        assertTrue(sent[1]["flush"].asBoolean())
        assertEquals("Zweiter Satz. ", sent[2]["text"].asText())
        assertTrue(sent[2]["flush"].asBoolean())
        assertTrue(sent[3]["flush"].asBoolean() && !sent[3].has("text")) // "sprich alles zu Ende"
        assertTrue(sent[4]["close_context"].asBoolean())
        assertTrue(troubles.isEmpty())
    }

    @Test fun `Zwei Antworten nacheinander laufen ueber dieselbe Verbindung`() {
        val fake = FakeSocket()
        serve(fake)
        assertEquals("Erste Antwort.", speak("Erste Antwort."))
        assertEquals("Zweite Antwort.", speak("Zweite Antwort."))
        assertEquals(listOf("ws"), kinds())
        // Jede Antwort hat ihren eigenen Kontext.
        val closedContexts = fake.messages.filter { it.path("close_context").asBoolean(false) }.map { it["context_id"].asText() }
        assertEquals(2, closedContexts.distinct().size)
        assertTrue(troubles.isEmpty())
    }

    @Test fun `Abbruch schliesst den Kontext sofort und liefert nichts mehr`() {
        val firstId = AtomicReference<String>()
        // Für die erste Antwort kommt nur der Anfang. Nach dem Schließen schickt der Server (wie der echte)
        // noch einen Nachzügler hinterher. Alle weiteren Antworten laufen normal.
        val fake = FakeSocket { ws, m ->
            val id = m.path("context_id").asText()
            firstId.compareAndSet(null, id)
            when {
                id != firstId.get() -> echo(ws, m)
                m.path("text").asText("").isNotBlank() -> ws.send(audio(id, "Anfang"))
                m.path("close_context").asBoolean(false) -> {
                    ws.send(audio(id, "Nachzuegler"))
                    ws.send(finalMark(id))
                }
            }
        }
        serve(fake)
        val heard = StringBuffer()
        runBlocking {
            withTimeout(10_000) {
                val sentences = Channel<String>(Channel.UNLIMITED)
                sentences.send("Eine lange Antwort.") // Kanal bleibt offen: Claude "schreibt noch"
                val started = CompletableDeferred<Unit>()
                val job = launch(Dispatchers.Default) {
                    tts.synthesize(sentences) { pcm, n ->
                        heard.append(String(pcm, 0, n))
                        started.complete(Unit)
                    }
                }
                started.await()
                job.cancelAndJoin()
            }
        }
        val closing = fake.awaitMessage { it.path("close_context").asBoolean(false) }
        assertEquals(firstId.get(), closing["context_id"].asText())

        // Die nächste Antwort nutzt dieselbe Verbindung. Der Nachzügler der alten ist bis dahin eingetroffen
        // und darf nirgends zu hören sein.
        assertEquals("Neue Antwort.", speak("Neue Antwort."))
        assertEquals("Anfang", heard.toString())
        assertEquals(listOf("ws"), kinds())
    }

    @Test fun `Nach dem Schliessen durch den Server wird neu verbunden`() {
        // Der Server beendet die Verbindung nach der ersten Antwort (im echten Betrieb: nach 180 s Ruhe).
        val first = FakeSocket { ws, m ->
            echo(ws, m)
            if (m.path("close_context").asBoolean(false)) ws.close(1000, "inactivity")
        }
        val second = FakeSocket()
        serve(first, second)
        assertEquals("Vor der Pause.", speak("Vor der Pause."))
        assertEquals("Nach der Pause.", speak("Nach der Pause."))
        assertEquals(listOf("ws", "ws"), kinds()) // zweite Verbindung, kein Umweg über HTTP
        assertTrue(second.messages.any { it.path("text").asText() == "Nach der Pause. " })
        assertTrue(troubles.isEmpty())
    }

    @Test fun `Eine verstummte alte Verbindung wird einmal durch eine frische ersetzt`() {
        val firstId = AtomicReference<String>()
        // Die erste Verbindung beantwortet nur die erste Antwort und schweigt danach (wie eine Leitung,
        // die unterwegs gekappt wurde, ohne dass es jemand gemerkt hat).
        val first = FakeSocket { ws, m ->
            val id = m.path("context_id").asText()
            firstId.compareAndSet(null, id)
            if (id == firstId.get()) echo(ws, m)
        }
        val second = FakeSocket()
        serve(first, second)
        assertEquals("Eins.", speak("Eins."))
        tts.firstAudioTimeoutMs = 1_000 // nicht 3 s auf die tote Leitung warten
        assertEquals("Zwei.", speak("Zwei."))
        assertEquals(listOf("ws", "ws"), kinds())
        assertTrue(troubles.isEmpty())
    }

    @Test fun `warmUp oeffnet die Verbindung vorab und close schliesst sie`() {
        val fake = FakeSocket()
        serve(fake, http = echoHttp)
        tts.warmUp()
        assertTrue(fake.opened.await(5, TimeUnit.SECONDS)) // steht, bevor es einen Satz gibt
        tts.warmUp() // zweiter Aufruf öffnet nichts Neues
        assertEquals("Hallo.", speak("Hallo."))
        assertEquals(listOf("ws"), kinds())

        tts.close()
        fake.awaitMessage { it.path("close_socket").asBoolean(false) }
        // Danach wird keine neue Verbindung mehr geöffnet; gesprochen wird notfalls über HTTP.
        tts.warmUp()
        assertEquals("Spaeter.", speak("Spaeter."))
        assertEquals(listOf("ws", "http"), kinds())
    }

    @Test fun `Ohne einen einzigen Satz wird kein Kontext angelegt`() {
        val fake = FakeSocket()
        serve(fake)
        assertEquals("", speak())
        assertNull(fake.messages.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `Schlussmarke in zweiter Schreibweise und Audio fremder Kontexte`() {
        val fake = FakeSocket { ws, m ->
            val id = m.path("context_id").asText()
            val text = m.path("text").asText("").trim()
            if (text.isNotEmpty()) {
                ws.send(audio("fremd", "Falsch")) // gehört zu keiner laufenden Antwort
                ws.send("""{"audio":null,"contextId":"$id"}""") // Stück ohne Audio
                ws.send(audio(id, text))
            }
            if (m.path("close_context").asBoolean(false)) ws.send("""{"is_final":true,"context_id":"$id"}""")
        }
        serve(fake)
        assertEquals("Nur das hier.", speak("Nur das hier."))
        assertTrue(troubles.isEmpty())
    }

    @Test fun `Bleibt die Schlussmarke aus endet die Antwort trotzdem`() {
        // Der Server liefert das Audio, meldet aber nie "fertig".
        val fake = FakeSocket { ws, m ->
            val text = m.path("text").asText("").trim()
            if (text.isNotEmpty()) ws.send(audio(m["context_id"].asText(), text))
        }
        serve(fake)
        tts.idleTimeoutMs = 200
        assertEquals("Hallo.", speak("Hallo."))
        assertEquals(1, troubles.size)
        assertEquals(listOf("ws"), kinds())
    }

    @Test fun `Endet die Verbindung nach dem letzten Satz ohne Schlussmarke gilt die Antwort als gesprochen`() {
        val fake = FakeSocket { ws, m ->
            val text = m.path("text").asText("").trim()
            if (text.isNotEmpty()) ws.send(audio(m["context_id"].asText(), text))
            if (m.path("close_context").asBoolean(false)) ws.close(1000, "bye")
        }
        serve(fake)
        assertEquals("Hallo.", speak("Hallo."))
        assertEquals(1, troubles.size)
        assertEquals(listOf("ws"), kinds())
    }

    @Test fun `Reisst die Verbindung mitten in der Antwort ab wird das gemeldet`() {
        val fake = FakeSocket { ws, m ->
            if (m.path("text").asText("").isNotBlank()) {
                ws.send(audio(m["context_id"].asText(), "Halb"))
                ws.close(1011, "kaputt")
            }
        }
        serve(fake, http = echoHttp)
        val heard = StringBuffer()
        val error = assertThrows(IOException::class.java) {
            runBlocking {
                withTimeout(10_000) {
                    val sentences = Channel<String>(Channel.UNLIMITED)
                    sentences.send("Ein Satz.") // Kanal bleibt offen: Claude "schreibt noch"
                    tts.synthesize(sentences) { pcm, n -> heard.append(String(pcm, 0, n)) }
                }
            }
        }
        assertTrue(error.message!!.startsWith("Sprachausgabe unterbrochen"))
        assertEquals("Halb", heard.toString()) // was schon da war, wurde noch ausgeliefert
        assertEquals(listOf("ws"), kinds()) // kein zweiter Anlauf über HTTP: der Anfang wäre doppelt zu hören
    }

    // ------------------------------------------------------------------------------------------
    // Sicherer Weg: HTTP

    @Test fun `Ohne WebSocket laeuft die Antwort ueber HTTP`() {
        serve(http = echoHttp) // kein WebSocket im Angebot: der Aufbau scheitert mit 503
        assertEquals("Hallo Welt.Zweiter Satz.", speak("Hallo Welt.", "Zweiter Satz."))
        assertEquals(listOf("ws", "http", "http"), kinds())

        val request = requests[1]
        assertEquals("/v1/text-to-speech/voice42/stream?output_format=pcm_24000", request.path)
        assertEquals("key-123", request.getHeader("xi-api-key"))
        val body = mapper.readTree(request.body.clone().readUtf8())
        assertEquals("Hallo Welt.", body["text"].asText())
        assertEquals("eleven_flash_v2_5", body["model_id"].asText())
        assertEquals("Zweiter Satz.", mapper.readTree(requests[2].body.clone().readUtf8())["text"].asText())
        assertEquals(1, troubles.size)
    }

    @Test fun `Stummer WebSocket weicht auf HTTP aus und wird eine Weile gemieden`() {
        val mute = FakeSocket { _, _ -> } // nimmt alles an, antwortet nie
        val healthy = FakeSocket()
        serve(mute, healthy, http = echoHttp)
        tts.firstAudioTimeoutMs = 200

        assertEquals("Hallo.", speak("Hallo."))
        assertEquals(listOf("ws", "http"), kinds())
        assertEquals(1, troubles.size)
        // Der Server sollte an der aufgegebenen Antwort nicht weiterarbeiten.
        mute.awaitMessage { it.path("close_context").asBoolean(false) }

        // Während der Pause geht es ohne neuen WebSocket-Versuch direkt über HTTP.
        assertEquals("Nochmal.", speak("Nochmal."))
        assertEquals(listOf("ws", "http", "http"), kinds())

        // Nach der Pause wird der schnelle Weg wieder probiert.
        tts.firstAudioTimeoutMs = 3_000
        tts.pauseAfterFailureMs = 0
        assertEquals("Wieder da.", speak("Wieder da."))
        assertEquals(listOf("ws", "http", "http", "ws"), kinds())
    }

    @Test fun `Fehlermeldung des Servers fuehrt zum Ausweichen auf HTTP`() {
        val fake = FakeSocket { ws, m ->
            if (m.path("text").asText("").isNotBlank()) {
                ws.send("""{"error":"quota_exceeded","message":"Nope","code":1008}""")
            }
        }
        serve(fake, http = echoHttp)
        assertEquals("Hallo.", speak("Hallo."))
        assertEquals(listOf("ws", "http"), kinds())
        assertTrue(troubles.single().contains("quota_exceeded"))
    }

    @Test fun `Abgelehnter Key wird als solcher gemeldet`() {
        val code = AtomicInteger(401)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code.get())
        }
        val unauthorized = assertThrows(IOException::class.java) { speak("Hallo.") }
        assertEquals("ElevenLabs-Key wurde abgelehnt (401)", unauthorized.message)
        code.set(403)
        val forbidden = assertThrows(IOException::class.java) { speak("Hallo.") }
        assertEquals("ElevenLabs-Key wurde abgelehnt (403)", forbidden.message)
    }

    // ------------------------------------------------------------------------------------------
    // Hilfen

    /** Schickt die Sätze durch die Sprachausgabe und gibt zurück, was "zu hören" war. */
    private fun speak(vararg sentences: String): String {
        val heard = StringBuffer()
        runBlocking {
            withTimeout(10_000) {
                val channel = Channel<String>(Channel.UNLIMITED)
                sentences.forEach { channel.send(it) }
                channel.close()
                tts.synthesize(channel) { pcm, n -> heard.append(String(pcm, 0, n)) }
            }
        }
        return heard.toString()
    }

    /**
     * Legt fest, was der Server tut: Jeder WebSocket-Aufbau bekommt die nächste [FakeSocket] aus der Liste
     * (ist keine mehr da, antwortet er mit 503). Satz-Anfragen über HTTP beantwortet [http].
     */
    private fun serve(
        vararg sockets: FakeSocket,
        http: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(500) },
    ) {
        val waiting = ArrayDeque(sockets.asList())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (!isSocket(request)) return http(request)
                val next = synchronized(waiting) { waiting.removeFirstOrNull() }
                return if (next != null) MockResponse().withWebSocketUpgrade(next) else MockResponse().setResponseCode(503)
            }
        }
    }

    /** HTTP-Antwort im Stil des echten Dienstes: Der Körper ist das "Audio" des angefragten Satzes. */
    private val echoHttp: (RecordedRequest) -> MockResponse = { request ->
        val text = mapper.readTree(request.body.clone().readUtf8())["text"].asText()
        MockResponse().setBody(Buffer().writeUtf8(text))
    }

    private fun isSocket(request: RecordedRequest) = "/multi-stream-input" in request.path.orEmpty()

    /** Art der Anfragen in Reihenfolge: "ws" = WebSocket-Aufbau, "http" = Satz-Anfrage. */
    private fun kinds(): List<String> = requests.map { if (isSocket(it)) "ws" else "http" }

    private fun audio(id: String, text: String) =
        """{"audio":"${Base64.getEncoder().encodeToString(text.toByteArray())}","contextId":"$id","alignment":null}"""

    private fun finalMark(id: String) = """{"isFinal":true,"contextId":"$id"}"""

    /**
     * Die Server-Seite einer WebSocket-Verbindung. Sie merkt sich alle Nachrichten und antwortet mit [script].
     * Ohne eigenes Drehbuch verhält sie sich wie der echte Dienst im Normalfall, siehe [echo].
     */
    private inner class FakeSocket(
        private val script: (FakeSocket.(WebSocket, JsonNode) -> Unit)? = null,
    ) : WebSocketListener() {
        val messages = LinkedBlockingQueue<JsonNode>()
        val opened = CountDownLatch(1)

        override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = mapper.readTree(text)
            messages.add(message)
            if (script != null) script.invoke(this, webSocket, message) else echo(webSocket, message)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        /** Normalfall: Jeder Satz kommt als Audio in zwei Stücken zurück, das Schließen mit der Schlussmarke. */
        fun echo(webSocket: WebSocket, message: JsonNode) {
            val id = message.path("context_id").asText()
            val text = message.path("text").asText("").trim()
            if (text.isNotEmpty()) {
                val half = text.length / 2
                webSocket.send(audio(id, text.substring(0, half)))
                webSocket.send(audio(id, text.substring(half)))
            }
            if (message.path("close_context").asBoolean(false)) webSocket.send(finalMark(id))
        }

        /** Wartet auf die nächste Nachricht, auf die [wanted] zutrifft. */
        fun awaitMessage(wanted: (JsonNode) -> Boolean): JsonNode {
            while (true) {
                val message = messages.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("erwartete Nachricht kam nicht")
                if (wanted(message)) return message
            }
        }
    }
}
