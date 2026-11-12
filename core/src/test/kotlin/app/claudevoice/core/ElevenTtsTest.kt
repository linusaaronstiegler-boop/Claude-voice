package app.claudevoice.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Die Tests spielen ElevenLabs mit einem kleinen lokalen Server nach. Das "Audio" eines Satzes sind dabei
 * einfach seine Buchstaben als Bytes: So lässt sich am Ergebnis ablesen, was in welcher Reihenfolge ankam.
 *
 * Wichtig für die Aussagekraft: Der nachgespielte Server kann nur zeigen, dass der Code tut, was wir vom
 * echten Dienst annehmen. Die Annahmen selbst stehen in der Dokumentation von ElevenLabs (Nachrichten,
 * Feldnamen, Reihenfolge); wo sie schweigt, prüfen die Tests beide denkbaren Verhaltensweisen.
 */
class ElevenTtsTest {
    /** Hängt ein Test (zum Beispiel an einer Leitung, die nie antwortet), bricht er ab, statt den Lauf zu blockieren. */
    @get:Rule val limit: Timeout = Timeout.seconds(90)

    private lateinit var server: MockWebServer
    private lateinit var tts: ElevenTts

    // Eingestellt wie in der App (ListenService): Dort gibt es kein Lese-Zeitlimit, weil derselbe Client
    // auch die dauerhaft offenen WebSockets trägt. Genau damit muss die Sprachausgabe zurechtkommen.
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val mapper = ObjectMapper()
    /** Alle Anfragen, die der Server gesehen hat, in Reihenfolge. */
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    /** Was die Sprachausgabe über [ElevenTts.onTrouble] gemeldet hat. */
    private val troubles = CopyOnWriteArrayList<String>()
    /** Für Antworten des nachgespielten Servers, die erst nach einer Weile kommen sollen. */
    private val later = Executors.newSingleThreadScheduledExecutor()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        tts = ElevenTts("key-123", http, "voice42", baseUrl())
        tts.onTrouble = { troubles += it }
    }

    @After fun tearDown() {
        tts.close()
        later.shutdownNow()
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

        // Adresse und Parameter wie in der Schnittstellen-Beschreibung von "multi-stream-input".
        val url = requests.single().requestUrl!!
        assertEquals("/v1/text-to-speech/voice42/multi-stream-input", url.encodedPath)
        assertEquals("eleven_flash_v2_5", url.queryParameter("model_id"))
        assertEquals("pcm_24000", url.queryParameter("output_format"))
        assertEquals("180", url.queryParameter("inactivity_timeout"))
        assertEquals("true", url.queryParameter("auto_mode"))
        assertNull(url.queryParameter("language_code")) // Antworten können deutsch oder englisch sein
        assertEquals(setOf("model_id", "output_format", "inactivity_timeout", "auto_mode"), url.queryParameterNames)
        assertEquals("key-123", requests.single().getHeader("xi-api-key"))

        // Die Nachrichten Feld für Feld: Jede darf nur enthalten, was die Beschreibung für sie vorsieht.
        val sent = List(5) { fake.messages.poll(5, TimeUnit.SECONDS)!! }
        val id = sent[0]["context_id"].asText()
        assertTrue(sent.all { it["context_id"].asText() == id })
        assertEquals(mapper.readTree("""{"text":" ","context_id":"$id"}"""), sent[0]) // legt den Kontext an
        // Satz endet mit einem Leerzeichen; flush = sofort sprechen
        assertEquals(mapper.readTree("""{"text":"Erster Satz. ","context_id":"$id","flush":true}"""), sent[1])
        assertEquals(mapper.readTree("""{"text":"Zweiter Satz. ","context_id":"$id","flush":true}"""), sent[2])
        assertEquals(mapper.readTree("""{"context_id":"$id","flush":true}"""), sent[3]) // "sprich alles zu Ende"
        assertEquals(mapper.readTree("""{"context_id":"$id","close_context":true}"""), sent[4])
        assertNull(fake.messages.poll(200, TimeUnit.MILLISECONDS)) // danach kommt für diesen Kontext nichts mehr
        assertTrue(troubles.isEmpty())
    }

    @Test fun `Langsamer Lautsprecher bremst das Senden nicht und verliert nichts`() {
        val fake = FakeSocket()
        serve(fake)
        val heard = StringBuffer()
        val allSentWhileHeard = AtomicReference<String>()
        runBlocking {
            withTimeout(10_000) {
                val sentences = Channel<String>(Channel.UNLIMITED)
                listOf("Eins.", "Zwei.", "Drei.", "Vier.").forEach { sentences.send(it) }
                sentences.close()
                tts.synthesize(sentences) { pcm, n ->
                    delay(40) // der Lautsprecher nimmt nur so schnell an, wie er abspielt
                    heard.append(String(pcm, 0, n))
                    // Sobald der Server alle vier Sätze hat, merken wir uns, wie weit die Wiedergabe da war.
                    if (allSentWhileHeard.get() == null && fake.messages.count { it.path("text").asText("").isNotBlank() } == 4) {
                        allSentWhileHeard.set(heard.toString())
                    }
                }
            }
        }
        // Zurückgekehrt wird erst, wenn wirklich alles ausgeliefert ist, und zwar in der Reihenfolge der Sätze.
        assertEquals("Eins.Zwei.Drei.Vier.", heard.toString())
        // Alle Sätze waren beim Server, lange bevor der Lautsprecher fertig war.
        assertTrue(allSentWhileHeard.get().length < "Eins.Zwei.Drei.".length)
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
        // Für die erste Antwort kommt nur der Anfang. Nach dem Schließen arbeitet der Server (wie der echte,
        // wenn ein Kontext schon auf "flush" stand) weiter: Er schickt den Rest der alten Antwort noch,
        // und zwar mitten zwischen die Stücke der neuen. Erst danach meldet er die alte als beendet.
        val fake = FakeSocket { ws, m ->
            val id = m.path("context_id").asText()
            firstId.compareAndSet(null, id)
            val old = firstId.get()
            when {
                id != old -> {
                    val text = m.path("text").asText("").trim()
                    if (text.isNotEmpty()) {
                        ws.send(audio(old, "ALT"))
                        ws.send(audio(id, text.substring(0, 4)))
                        ws.send(audio(old, "ALT"))
                        ws.send(finalMark(old))
                        ws.send(audio(id, text.substring(4)))
                    }
                    if (m.path("close_context").asBoolean(false)) ws.send(finalMark(id))
                }
                m.path("text").asText("").isNotBlank() -> ws.send(audio(id, "Anfang"))
                m.path("close_context").asBoolean(false) -> ws.send(audio(id, "Nachzuegler"))
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

        // Die nächste Antwort nutzt dieselbe Verbindung. Was der Server zur alten noch schickt, darf
        // nirgends zu hören sein.
        assertEquals("Neue Antwort.", speak("Neue Antwort."))
        assertEquals("Anfang", heard.toString())
        assertEquals(listOf("ws"), kinds())
        // Der abgebrochene Kontext wurde genau einmal geschlossen: Die eine Nachricht hat awaitMessage oben
        // schon entnommen, eine zweite darf es nicht geben (einen schließenden Kontext spricht man nicht mehr an).
        assertEquals(0, fake.messages.count { it.path("close_context").asBoolean(false) && it["context_id"].asText() == firstId.get() })
    }

    @Test fun `Abbruch zu beliebigen Zeitpunkten laesst nichts in die naechste Antwort durch`() {
        // Dieser Server passt auf, dass wir uns an die dokumentierten Regeln halten, und schreibt jeden
        // Verstoß auf. Ein Kontext ist offen von seiner ersten Nachricht bis zur Schlussmarke. Audio und
        // Schlussmarke kommen mit etwas Verzug, sodass der Abbruch in jede denkbare Lücke fällt.
        val open = ConcurrentHashMap.newKeySet<String>()
        val closedByUs = ConcurrentHashMap.newKeySet<String>()
        val violations = CopyOnWriteArrayList<String>()
        val fake = FakeSocket { ws, m ->
            val id = m.path("context_id").asText("")
            val text = m.path("text").asText("")
            if (id.isEmpty()) violations += "Nachricht ohne Kontextnummer: $m"
            // Einen Kontext, der schon schließt, darf man nicht mehr ansprechen.
            if (id in closedByUs) violations += "Nachricht an einen geschlossenen Kontext: $m"
            if (open.add(id)) {
                // Höchstens fünf offene Kontexte je Verbindung; der erste Text eines Kontexts ist ein Leerzeichen.
                if (open.size > 5) violations += "mehr als fünf offene Kontexte"
                if (text != " ") violations += "Kontext beginnt nicht mit einem einzelnen Leerzeichen: $m"
            }
            if (m.has("text") && !text.endsWith(" ")) violations += "Text endet nicht mit einem Leerzeichen: $m"
            if (text.isNotBlank()) later.schedule({ ws.send(audio(id, text.trim())) }, 4, TimeUnit.MILLISECONDS)
            if (m.path("close_context").asBoolean(false)) {
                closedByUs += id
                later.schedule({ ws.send(finalMark(id)); open.remove(id) }, 12, TimeUnit.MILLISECONDS)
            }
        }
        serve(fake)
        repeat(30) { round ->
            runBlocking {
                withTimeout(10_000) {
                    val sentences = Channel<String>(Channel.UNLIMITED)
                    val job = launch(Dispatchers.Default) { tts.synthesize(sentences) { _, _ -> } }
                    sentences.send("Alt eins.")
                    sentences.send("Alt zwei.")
                    delay(round % 10L)
                    if (round % 3 == 0) sentences.close() // manchmal ist die Antwort schon vollständig abgeschickt
                    delay(round % 4L)
                    job.cancelAndJoin()
                }
            }
            assertEquals("Neu $round.", speak("Neu $round."))
        }
        assertEquals(listOf("ws"), kinds()) // alles über eine einzige Verbindung
        assertEquals(emptyList<String>(), violations.toList())
        assertTrue(troubles.isEmpty())
        // Jeder Kontext, der Text bekommen hat, wurde auch geschlossen, und keiner zweimal.
        val all = fake.messages.toList()
        val begun = all.filter { it.path("text").asText("").isNotBlank() }.map { it["context_id"].asText() }.toSet()
        val closed = all.filter { it.path("close_context").asBoolean(false) }.map { it["context_id"].asText() }
        assertEquals(begun, closed.toSet())
        assertEquals(closed.size, closed.toSet().size)
    }

    @Test fun `Nach dem Schliessen durch den Server wird neu verbunden`() {
        // Der Server beendet die Verbindung nach der ersten Antwort. Wann der echte das tut, ist nur für
        // Kontexte dokumentiert (nach der Ruhezeit); eine Verbindung ohne Kontext kann er jederzeit schließen.
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
        // Das Warten hat die Antwort Zeit gekostet, also wird es gemeldet (einmal, mit dem Grund).
        assertTrue(troubles.single().contains("kein Ton"))
    }

    @Test fun `Fehlermeldung auf einer alten Verbindung fuehrt zu einem zweiten Versuch ueber WebSocket`() {
        val firstId = AtomicReference<String>()
        // Die erste Verbindung nimmt nach der ersten Antwort nichts mehr an und sagt das auch (ohne
        // Kontextnummer, also für die ganze Verbindung, etwa nach einer Zeitüberschreitung).
        val first = FakeSocket { ws, m ->
            val id = m.path("context_id").asText()
            firstId.compareAndSet(null, id)
            if (id == firstId.get()) echo(ws, m)
            else if (m.path("text").asText("").isNotBlank()) {
                ws.send("""{"message":"Have not received a new text input within the timeout.","error":"input_timeout_exceeded","code":1008}""")
            }
        }
        val second = FakeSocket()
        serve(first, second)
        assertEquals("Eins.", speak("Eins."))
        assertEquals("Zwei.", speak("Zwei."))
        assertEquals(listOf("ws", "ws"), kinds())
        assertTrue(second.messages.any { it.path("text").asText() == "Zwei. " })
    }

    @Test fun `Eine selbst gekappte Verbindung im Aufbau loest keine Pause aus`() {
        // Der erste Aufbau bleibt hängen: Der Server nimmt die Anfrage an und antwortet nie.
        val healthy = FakeSocket()
        serve(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE), healthy, http = echoHttp)
        tts.warmUp()
        awaitRequests(1)
        tts.firstAudioTimeoutMs = 300
        assertEquals("Eins.", speak("Eins."))
        tts.firstAudioTimeoutMs = 3_000
        // Dass wir die hängende Verbindung selbst gekappt haben, ist kein Ausfall des WebSockets:
        // Auch die nächste Antwort nimmt den schnellen Weg.
        assertEquals("Zwei.", speak("Zwei."))
        assertEquals(listOf("ws", "ws"), kinds())
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
        val bye = fake.awaitMessage { it.path("close_socket").asBoolean(false) }
        assertEquals(mapper.readTree("""{"close_socket":true}"""), bye)
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

    @Test fun `Audio mit Kontextnummer in Unterstrich-Schreibweise wird zugeordnet`() {
        // Die Beschreibung der Schnittstelle nennt das Feld contextId, der Begleittext context_id.
        // Welche Schreibweise der Dienst wirklich schickt, wissen wir nicht sicher, also müssen beide gehen,
        // auch wenn das jeweils andere Feld leer mitkommt.
        val fake = FakeSocket { ws, m ->
            val id = m.path("context_id").asText()
            val text = m.path("text").asText("").trim()
            if (text.isNotEmpty()) {
                val half = text.length / 2
                ws.send("""{"audio":"${b64(text.substring(0, half))}","context_id":"$id"}""")
                ws.send("""{"audio":"${b64(text.substring(half))}","contextId":null,"context_id":"$id","isFinal":null}""")
            }
            if (m.path("close_context").asBoolean(false)) ws.send("""{"isFinal":true,"contextId":null,"context_id":"$id"}""")
        }
        serve(fake)
        tts.firstAudioTimeoutMs = 1_000
        assertEquals("Unterstrich.", speak("Unterstrich."))
        assertEquals(listOf("ws"), kinds())
        assertTrue(troubles.isEmpty())
    }

    @Test fun `Eine verfruehte Schlussmarke beendet die Antwort nicht`() {
        // Der Server meldet den Kontext schon nach dem ersten Satz als beendet (etwa weil er ihn nach einer
        // Ruhezeit von sich aus geschlossen hat). Claude schreibt aber noch: Der zweite Satz muss trotzdem
        // gesprochen werden. Laut Beschreibung legt Text für eine bekannte Nummer den Kontext einfach neu an.
        val finals = AtomicInteger()
        val fake = FakeSocket { ws, m ->
            echo(ws, m)
            val id = m.path("context_id").asText()
            if (m.path("text").asText("").isNotBlank() && finals.getAndIncrement() == 0) ws.send(finalMark(id))
        }
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
                firstHeard.await()
                delay(100) // die verfrühte Schlussmarke ist längst da
                sentences.send("Zweiter Satz.")
                sentences.close()
                job.join()
            }
        }
        assertEquals("Erster Satz.Zweiter Satz.", heard.toString())
        assertTrue(troubles.isEmpty())
    }

    @Test fun `Bleibt die Schlussmarke aus endet die Antwort trotzdem`() {
        // Der Server liefert das Audio, meldet aber nie "fertig".
        val fake = FakeSocket { ws, m ->
            val text = m.path("text").asText("").trim()
            if (text.isNotEmpty()) ws.send(audio(m["context_id"].asText(), text))
        }
        val next = FakeSocket()
        serve(fake, next)
        tts.idleTimeoutMs = 200
        assertEquals("Hallo.", speak("Hallo."))
        assertEquals(1, troubles.size)
        assertEquals(listOf("ws"), kinds())

        // Einer Leitung, auf der eine zugesagte Nachricht ausbleibt, wird nicht mehr getraut: Die nächste
        // Antwort bekommt eine frische, statt womöglich erst Sekunden auf eine tote zu warten.
        assertEquals("Weiter.", speak("Weiter."))
        assertEquals(listOf("ws", "ws"), kinds())
        assertEquals(1, troubles.size)
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

    @Test fun `Fehlermeldung zu einem laengst geschlossenen Kontext stoert die neue Antwort nicht`() {
        val firstId = AtomicReference<String>()
        // Die erste Antwort wird abgebrochen. Während die zweite läuft, meldet der Server zur ersten noch
        // einen Fehler. Der betrifft einen Kontext, den es für uns nicht mehr gibt, und nicht die Verbindung.
        val fake = FakeSocket { ws, m ->
            val id = m.path("context_id").asText()
            firstId.compareAndSet(null, id)
            val old = firstId.get()
            when {
                id != old -> {
                    if (m.path("text").asText("").isNotBlank()) {
                        ws.send("""{"error":"context_closed","message":"Context is closed.","contextId":"$old"}""")
                    }
                    echo(ws, m)
                }
                m.path("text").asText("").isNotBlank() -> ws.send(audio(id, "Anfang"))
            }
        }
        serve(fake, http = echoHttp)
        runBlocking {
            withTimeout(10_000) {
                val sentences = Channel<String>(Channel.UNLIMITED)
                sentences.send("Eine lange Antwort.")
                val started = CompletableDeferred<Unit>()
                val job = launch(Dispatchers.Default) { tts.synthesize(sentences) { _, _ -> started.complete(Unit) } }
                started.await()
                job.cancelAndJoin()
            }
        }
        assertEquals("Neue Antwort.", speak("Neue Antwort."))
        assertEquals(listOf("ws"), kinds()) // weder neue Verbindung noch HTTP
        assertTrue(troubles.isEmpty())
    }

    @Test fun `Fehlermeldung zum laufenden Kontext trifft nur diese Antwort`() {
        val failing = AtomicReference<String>()
        // Nur der erste Kontext scheitert, und zwar nachdem schon Ton kam. Die Verbindung selbst bleibt heil.
        val fake = FakeSocket { ws, m ->
            val id = m.path("context_id").asText()
            failing.compareAndSet(null, id)
            if (id != failing.get()) echo(ws, m)
            else if (m.path("text").asText("").isNotBlank()) {
                ws.send(audio(id, "Halb"))
                ws.send("""{"error":"generation_failed","message":"Something went wrong.","context_id":"$id"}""")
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
        assertTrue(error.message!!.contains("generation_failed"))
        assertFalse(error.message!!.contains("Something went wrong")) // nur das Kürzel, kein freier Text des Servers
        assertEquals("Halb", heard.toString())
        // Die nächste Antwort läuft über dieselbe Verbindung weiter.
        assertEquals("Danach.", speak("Danach."))
        assertEquals(listOf("ws"), kinds())
    }

    // ------------------------------------------------------------------------------------------
    // Sicherer Weg: HTTP

    @Test fun `Ohne WebSocket laeuft die Antwort ueber HTTP`() {
        serve(http = echoHttp) // kein WebSocket im Angebot: der Aufbau scheitert mit 503
        assertEquals("Hallo Welt.Zweiter Satz.", speak("Hallo Welt.", "Zweiter Satz."))
        assertEquals(listOf("ws", "http", "http"), kinds())

        val request = requests[1]
        assertEquals("POST", request.method)
        assertEquals("/v1/text-to-speech/voice42/stream?output_format=pcm_24000", request.path)
        assertEquals("key-123", request.getHeader("xi-api-key"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = mapper.readTree(request.body.clone().readUtf8())
        assertEquals(mapper.readTree("""{"text":"Hallo Welt.","model_id":"eleven_flash_v2_5"}"""), body)
        assertEquals("Zweiter Satz.", mapper.readTree(requests[2].body.clone().readUtf8())["text"].asText())
        assertEquals(1, troubles.size)
    }

    @Test fun `Stummer WebSocket weicht auf HTTP aus und wird eine Weile gemieden`() {
        // Nimmt alles an und antwortet zu spät: erst, wenn wir längst ausgewichen sind.
        val mute = FakeSocket { ws, m ->
            val text = m.path("text").asText("").trim()
            if (text.isNotEmpty()) later.schedule({ ws.send(audio(m["context_id"].asText(), text)) }, 600, TimeUnit.MILLISECONDS)
        }
        val healthy = FakeSocket()
        serve(mute, healthy, http = echoHttp)
        tts.firstAudioTimeoutMs = 200

        assertEquals("Hallo.", speak("Hallo."))
        assertEquals(listOf("ws", "http"), kinds())
        assertEquals(1, troubles.size)
        // Der Server sollte an der aufgegebenen Antwort nicht weiterarbeiten.
        mute.awaitMessage { it.path("close_context").asBoolean(false) }

        // Während der Pause geht es ohne neuen WebSocket-Versuch direkt über HTTP. Dabei trifft das
        // verspätete Audio der aufgegebenen Antwort ein; es darf nicht doppelt zu hören sein.
        Thread.sleep(600)
        assertEquals("Nochmal.", speak("Nochmal."))
        assertEquals(listOf("ws", "http", "http"), kinds())

        // Nach der Pause wird der schnelle Weg wieder probiert.
        tts.firstAudioTimeoutMs = 3_000
        tts.pauseAfterFailureMs = 0
        assertEquals("Wieder da.", speak("Wieder da."))
        assertEquals(listOf("ws", "http", "http", "ws"), kinds())
    }

    @Test fun `Beim Ausweichen auf HTTP bleibt die Reihenfolge und kein Satz kommt doppelt`() {
        val mute = FakeSocket { _, _ -> } // nimmt alles an, antwortet nie
        serve(mute, http = echoHttp)
        tts.firstAudioTimeoutMs = 300
        val heard = StringBuffer()
        runBlocking {
            withTimeout(10_000) {
                val sentences = Channel<String>(Channel.UNLIMITED)
                // Zwei Sätze sind schon an den stummen WebSocket gegangen, bevor wir ausweichen.
                sentences.send("Eins.")
                sentences.send("Zwei.")
                val job = launch(Dispatchers.Default) { tts.synthesize(sentences) { pcm, n -> heard.append(String(pcm, 0, n)) } }
                awaitRequests(2) // die erste HTTP-Anfrage ist unterwegs
                sentences.send("Drei.") // Claude schreibt derweil weiter
                sentences.close()
                job.join()
            }
        }
        assertEquals("Eins.Zwei.Drei.", heard.toString())
        val spoken = requests.drop(1).map { mapper.readTree(it.body.clone().readUtf8())["text"].asText() }
        assertEquals(listOf("Eins.", "Zwei.", "Drei."), spoken)
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

    @Test fun `HTTP-Fehler nennen den Grund aus der Antwort des Servers`() {
        // ElevenLabs benutzt 401 nicht nur für einen falschen Key. Der Grund steht im Körper der Antwort
        // unter detail.status (Hilfe-Seite "API - Error Code 400 or 401").
        fun failure(code: Int, body: String): String {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code).setBody(body)
            }
            return assertThrows(IOException::class.java) { speak("Hallo.") }.message!!
        }

        val quota = failure(401, """{"detail":{"status":"quota_exceeded","message":"This request exceeds your quota."}}""")
        assertTrue(quota, quota.contains("Guthaben"))
        assertFalse(quota, quota.contains("Key"))

        val key = failure(401, """{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}""")
        assertEquals("ElevenLabs-Key wurde abgelehnt (401)", key)

        val voice = failure(404, """{"detail":{"status":"voice_not_found","message":"A voice with that id was not found."}}""")
        assertTrue(voice, voice.contains("Voice-ID"))

        val busy = failure(429, """{"detail":{"status":"system_busy","message":"Please try again."}}""")
        assertEquals("Sprachausgabe antwortet mit 429 (system_busy)", busy)

        // Unbekannte oder unlesbare Körper: nur der Code, nie freier Text des Servers.
        assertEquals("Sprachausgabe antwortet mit 500", failure(500, "Internal Server Error"))
        assertEquals(
            "Sprachausgabe antwortet mit 422",
            failure(422, """{"detail":[{"loc":["body","text"],"msg":"field required","type":"value_error.missing"}]}"""),
        )
        assertEquals("Sprachausgabe antwortet mit 400", failure(400, """{"detail":{"status":"Bitte <b>nicht</b> anzeigen"}}"""))
    }

    @Test fun `Ein Key mit unzulaessigen Zeichen fuehrt zu einer klaren Meldung statt zu einem Absturz`() {
        serve(FakeSocket(), http = echoHttp)
        // Beim Einfügen mitkopiert: ein unsichtbares Zeichen mitten im Key (hier das "Leerzeichen ohne Breite",
        // Unicode 200B; als Zahl geschrieben, damit es im Quelltext zu sehen ist). So etwas darf in keinem
        // HTTP-Kopf stehen.
        val invisible = 0x200B.toChar()
        val broken = ElevenTts("sk_geheim${invisible}geheim", http, "voice42", baseUrl())
        try {
            broken.warmUp() // läuft beim Start des Dienstes; ein Fehler hier würde die App beenden
            val error = assertThrows(IOException::class.java) { speak("Hallo.", via = broken) }
            assertTrue(error.message!!, error.message!!.contains("Key"))
            assertFalse(error.message!!.contains("geheim")) // der Key selbst darf in keiner Meldung stehen
            assertEquals("", speak(via = broken)) // ohne Satz gibt es auch nichts zu beanstanden
            assertTrue(requests.isEmpty()) // nichts wurde verschickt
        } finally {
            broken.close()
        }
    }

    @Test fun `Voice-ID wird als ein Pfadstueck verschickt`() {
        serve(http = echoHttp)
        // Vertippt oder falsch eingefügt: Sonderzeichen dürfen die Adresse nicht auf einen anderen Endpunkt umbiegen.
        val odd = ElevenTts("key-123", http, "ab c/d?e#f", baseUrl())
        try {
            assertEquals("Hallo.", speak("Hallo.", via = odd))
            assertEquals("/v1/text-to-speech/ab%20c%2Fd%3Fe%23f/multi-stream-input", requests[0].requestUrl!!.encodedPath)
            assertEquals("/v1/text-to-speech/ab%20c%2Fd%3Fe%23f/stream", requests[1].requestUrl!!.encodedPath)
            assertEquals("pcm_24000", requests[1].requestUrl!!.queryParameter("output_format"))
        } finally {
            odd.close()
        }
    }

    @Test fun `Abbruch beendet auch eine haengende HTTP-Anfrage sofort`() {
        // Kein WebSocket, und die HTTP-Anfrage bekommt nie eine Antwort (Funkloch nach dem Absenden).
        serve(http = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) })
        val scope = CoroutineScope(Dispatchers.Default)
        val sentences = Channel<String>(Channel.UNLIMITED)
        sentences.trySend("Hallo.")
        val job = scope.launch { tts.synthesize(sentences) { _, _ -> } }
        awaitRequests(2) // WebSocket-Versuch und die HTTP-Anfrage
        val stopped = runBlocking { withTimeoutOrNull(3_000) { job.cancelAndJoin() } }
        assertNotNull("Der Abbruch kam bei der hängenden Anfrage nicht an", stopped)
    }

    @Test fun `Haengt der HTTP-Ausweichweg endet die Antwort mit einem Fehler statt nie`() {
        // Wie oben, nur bricht diesmal niemand ab. Der Client der App kennt kein Lese-Zeitlimit; ohne eigene
        // Grenze würde die Antwort für immer "sprechen" und die App wäre stumm, bis jemand "Stopp" sagt.
        serve(http = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) })
        tts.httpReadTimeoutMs = 300
        val scope = CoroutineScope(Dispatchers.Default)
        val sentences = Channel<String>(Channel.UNLIMITED)
        sentences.trySend("Hallo.")
        sentences.close()
        val outcome = scope.async { runCatching { tts.synthesize(sentences) { _, _ -> } } }
        val result = runBlocking { withTimeoutOrNull(5_000) { outcome.await() } }
        assertNotNull("Die Antwort hängt für immer", result)
        assertTrue(result!!.exceptionOrNull() is IOException)
        outcome.cancel()
    }

    @Test fun `Bricht der HTTP-Ausweichweg mitten im Satz ab wird das als Fehler gemeldet`() {
        // Der Server kündigt mehr Audio an, als er liefert, und trennt dann.
        serve(http = {
            MockResponse().setBody(Buffer().writeUtf8("Halb")).setHeader("Content-Length", "400")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        })
        val heard = StringBuffer()
        assertThrows(IOException::class.java) {
            runBlocking {
                withTimeout(10_000) {
                    val sentences = Channel<String>(Channel.UNLIMITED)
                    sentences.send("Ein ganzer Satz.")
                    sentences.close()
                    tts.synthesize(sentences) { pcm, n -> heard.append(String(pcm, 0, n)) }
                }
            }
        }
        assertEquals("Halb", heard.toString())
        assertEquals(listOf("ws", "http"), kinds()) // kein zweiter Anlauf: der Anfang wäre doppelt zu hören
    }

    // ------------------------------------------------------------------------------------------
    // Echter Dienst: läuft nur mit Schlüssel

    /**
     * Alle Tests oben spielen den Server nach. Dieser hier fragt den echten Dienst und prüft genau die
     * Annahmen, die sich aus der Dokumentation allein nicht sichern lassen:
     *  - Der Ton kommt über den WebSocket (kein Ausweichen auf HTTP), und die Stücke tragen eine Kontextnummer,
     *    die wir zuordnen können.
     *  - Nach "flush" und "close_context" kommt der Rest des Tons und danach die Schlussmarke.
     *  - Nach einem Abbruch ist vom Rest der alten Antwort in der nächsten nichts zu hören.
     *
     * Ohne Schlüssel wird er übersprungen. Aufruf (verbraucht ein paar hundert Zeichen Guthaben):
     *   ELEVENLABS_LIVE=1 ELEVENLABS_API_KEY=... ./gradlew :core:test --tests "app.claudevoice.core.ElevenTtsTest" -PohneWeckwort -i
     * Optional: ELEVENLABS_VOICE_ID. Die gemessenen Zeiten stehen in der Ausgabe (-i) und im Testbericht.
     */
    @Test fun `Echter Dienst spricht ueber den WebSocket und laesst nach Abbruch nichts durch`() {
        val key = System.getenv("ELEVENLABS_API_KEY").orEmpty()
        assumeTrue("übersprungen: ELEVENLABS_LIVE=1 und ELEVENLABS_API_KEY setzen", System.getenv("ELEVENLABS_LIVE") == "1" && key.isNotBlank())
        // Vorgabe ist dieselbe öffentliche Standardstimme wie in der App und im Rauchtest.
        val voice = System.getenv("ELEVENLABS_VOICE_ID")?.takeIf { it.isNotBlank() } ?: "JBFqnCBsd6RMkjVDRZzb"
        val live = ElevenTts(key, http, voice)
        val noted = CopyOnWriteArrayList<String>()
        live.onTrouble = { noted += it }
        try {
            probe(live, noted, "Echter Dienst")
        } finally {
            live.close()
        }
    }

    @Test fun `Die Probe fuer den echten Dienst besteht gegen den nachgespielten Server`() {
        // Damit die Probe nicht ungeprüft herumliegt, bis jemand einen Schlüssel hat: Hier läuft sie gegen
        // einen Server, der pro Satz gut eine Sekunde "Ton" in drei Stücken liefert und nach einem Abbruch
        // (wie der echte) erst noch zu Ende liefert, was er schon in Arbeit hat.
        val tone = Base64.getEncoder().encodeToString(ByteArray(20_000))
        val fake = FakeSocket { ws, m ->
            val id = m.path("context_id").asText()
            if (m.path("text").asText("").isNotBlank()) {
                for (i in 1..3) later.schedule({ ws.send("""{"audio":"$tone","contextId":"$id"}""") }, 10L * i, TimeUnit.MILLISECONDS)
            }
            if (m.path("close_context").asBoolean(false)) later.schedule({ ws.send(finalMark(id)) }, 60, TimeUnit.MILLISECONDS)
        }
        serve(fake)
        probe(tts, troubles, "Nachgespielter Server")
        assertEquals(listOf("ws"), kinds())
    }

    /**
     * Drei Schritte gegen eine Sprachausgabe, deren Meldungen in [noted] landen:
     * zwei Sätze über eine neue Verbindung, eine lange Antwort mit Abbruch, sofort danach eine kurze Antwort.
     */
    private fun probe(live: ElevenTts, noted: List<String>, label: String) {
        /** Spricht die Sätze und misst: Bytes insgesamt, Zeit bis zum ersten Ton, Zeit bis zum Ende. */
        fun take(vararg sentences: String): Triple<Int, Long, Long> {
            var bytes = 0
            var first = -1L
            val started = System.nanoTime()
            runBlocking {
                withTimeout(60_000) {
                    val channel = Channel<String>(Channel.UNLIMITED)
                    sentences.forEach { channel.send(it) }
                    channel.close()
                    live.synthesize(channel) { _, n ->
                        if (first < 0) first = (System.nanoTime() - started) / 1_000_000
                        bytes += n
                    }
                }
            }
            return Triple(bytes, first, (System.nanoTime() - started) / 1_000_000)
        }
        val second = 2 * ElevenTts.SAMPLE_RATE // Bytes je Sekunde Ton (16 Bit, mono)

        // 1. Neue Verbindung, zwei Sätze.
        val (coldBytes, coldFirst, coldTotal) = take("Guten Tag.", "Das hier ist ein kurzer Test der Sprachausgabe.")
        println("$label, neue Verbindung: erster Ton nach $coldFirst ms, fertig nach $coldTotal ms, ${coldBytes * 1000L / second} ms Ton")
        assertTrue("Der WebSocket-Weg hat nicht sauber funktioniert: $noted", noted.isEmpty())
        assertTrue("zu wenig Ton: $coldBytes Bytes", coldBytes > second)
        assertEquals("PCM mit 16 Bit hat immer eine gerade Zahl von Bytes", 0, coldBytes % 2)

        // 2. Eine lange Antwort, abgebrochen nach dem ersten Ton. Claude "schreibt noch" (Kanal bleibt offen).
        runBlocking {
            withTimeout(60_000) {
                val sentences = Channel<String>(Channel.UNLIMITED)
                repeat(4) {
                    sentences.send("Dies ist ein absichtlich langer Satz, der mehrere Sekunden dauert, damit beim Abbruch noch viel Ton aussteht.")
                }
                val started = CompletableDeferred<Unit>()
                val job = launch(Dispatchers.Default) { live.synthesize(sentences) { _, _ -> started.complete(Unit) } }
                started.await()
                job.cancelAndJoin()
            }
        }

        // 3. Sofort danach eine sehr kurze Antwort über dieselbe Verbindung. Käme der Rest der alten
        // Antwort durch, wäre sie viele Sekunden lang statt weniger als drei.
        val (warmBytes, warmFirst, warmTotal) = take("Ja.")
        println("$label, bestehende Verbindung nach Abbruch: erster Ton nach $warmFirst ms, fertig nach $warmTotal ms, ${warmBytes * 1000L / second} ms Ton")
        assertTrue("Nach dem Abbruch lief etwas nicht sauber: $noted", noted.isEmpty())
        assertTrue("kein Ton für die neue Antwort", warmBytes > second / 10)
        assertTrue("Ton der abgebrochenen Antwort ist in die neue geraten: ${warmBytes * 1000L / second} ms", warmBytes < 3 * second)
    }

    // ------------------------------------------------------------------------------------------
    // Hilfen

    /** Schickt die Sätze durch die Sprachausgabe und gibt zurück, was "zu hören" war. */
    private fun speak(vararg sentences: String, via: ElevenTts = tts): String {
        val heard = StringBuffer()
        runBlocking {
            withTimeout(10_000) {
                val channel = Channel<String>(Channel.UNLIMITED)
                sentences.forEach { channel.send(it) }
                channel.close()
                via.synthesize(channel) { pcm, n -> heard.append(String(pcm, 0, n)) }
            }
        }
        return heard.toString()
    }

    private fun baseUrl() = server.url("/").toString().removeSuffix("/")

    /**
     * Legt fest, was der Server tut: Jeder WebSocket-Aufbau bekommt den nächsten Eintrag aus der Liste,
     * entweder eine [FakeSocket] oder eine fertige Antwort (ist keiner mehr da, antwortet er mit 503).
     * Satz-Anfragen über HTTP beantwortet [http].
     */
    private fun serve(
        vararg sockets: Any,
        http: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(500) },
    ) {
        val waiting = ArrayDeque(sockets.asList())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (!isSocket(request)) return http(request)
                return when (val next = synchronized(waiting) { waiting.removeFirstOrNull() }) {
                    is FakeSocket -> MockResponse().withWebSocketUpgrade(next)
                    is MockResponse -> next
                    else -> MockResponse().setResponseCode(503)
                }
            }
        }
    }

    /** Wartet, bis der Server mindestens [count] Anfragen gesehen hat. */
    private fun awaitRequests(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (requests.size < count) {
            if (System.nanoTime() > deadline) throw AssertionError("nur ${requests.size} von $count Anfragen kamen an")
            Thread.sleep(5)
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

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    /** Audiostück, wie es die Schnittstellen-Beschreibung zeigt (AudioOutputMulti). */
    private fun audio(id: String, text: String) = """{"audio":"${b64(text)}","contextId":"$id","alignment":null}"""

    /** Schlussmarke, wie sie die Schnittstellen-Beschreibung zeigt (FinalOutputMulti). */
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
