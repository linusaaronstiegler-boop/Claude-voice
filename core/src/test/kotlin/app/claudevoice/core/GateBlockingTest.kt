package app.claudevoice.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Der Antwort-Aufruf einer neuen Äußerung darf weder auf das Urteil über die Äußerung davor warten noch
 * hinter einer hängenden Vorprüfung (dem schnellen Modell für beiläufige Aussagen) feststecken.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GateBlockingTest {

    private val answer = "Zweitausendneunhundertzweiundsechzig Meter."

    /** Ein Claude, dessen Vorprüfung hängt, bis der Test sie freigibt: wie ein langsamer Umlauf zum schnellen Modell. */
    private inner class SlowGateClaude(private val gateBlocks: Boolean) : ClaudeApi {
        val gateEntered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val answerStarted = CountDownLatch(1)
        val respondUsers = CopyOnWriteArrayList<String>()

        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
            respondUsers += user
            answerStarted.countDown()
            listOf("SAY kurz\n", answer).forEach(onText)
        }

        override fun quick(system: String, user: String, maxTokens: Long): String {
            gateEntered.countDown()
            if (gateBlocks) releaseGate.await(60, TimeUnit.SECONDS)
            return "NEIN"
        }
    }

    private fun rig(claude: SlowGateClaude) = ThreadRig(claude)

    private val statement = "wir waren letztes Jahr im Sommer oben auf der Zugspitze" // sechs oder mehr Wörter, keine Frage: MAYBE
    private val question = "Wie hoch ist die Zugspitze?" // HOT

    /** Kontrolle: Mit einer Vorprüfung, die sofort antwortet, wird derselbe Wortwechsel beantwortet. Der Aufbau taugt. */
    @Test fun `Kontrolle - Frage nach einer schnellen Vorpruefung wird beantwortet`() {
        val claude = SlowGateClaude(gateBlocks = false)
        val rig = rig(claude)
        try {
            assertEquals(Heuristics.Heat.MAYBE, Heuristics.heat(statement, "Alfred"))
            assertEquals(Heuristics.Heat.HOT, Heuristics.heat(question, "Alfred"))
            rig.pipeline.onCommitted(statement, null, null)
            assertTrue(claude.gateEntered.await(5, TimeUnit.SECONDS))
            rig.clock.set(300)
            rig.pipeline.onCommitted(question, null, null)
            assertTrue(claude.answerStarted.await(5, TimeUnit.SECONDS))
            rig.clock.set(1_400) // die Höflichkeitspause ist vorbei
            assertTrue("Antwort gesprochen", waitFor(5_000) { rig.speaker.spoken.isNotEmpty() })
            assertEquals(listOf(answer), rig.speaker.spoken.toList())
        } finally {
            claude.releaseGate.countDown()
            rig.close()
        }
    }

    @Test fun `Frage hinter einer laufenden Vorpruefung startet ihren Antwort-Aufruf sofort`() {
        val claude = SlowGateClaude(gateBlocks = true)
        val rig = rig(claude)
        try {
            rig.pipeline.onCommitted(statement, null, null) // Aussage eines Freundes: Die Vorprüfung läuft
            assertTrue("Vorprüfung gestartet", claude.gateEntered.await(5, TimeUnit.SECONDS))
            rig.clock.set(300)
            rig.pipeline.onCommitted(question, null, null) // 300 ms später: die Frage des Trägers

            assertTrue(
                "der Antwort-Aufruf für die Frage startete nicht, solange die Vorprüfung der Aussage davor lief " +
                    "(Antwort-Aufrufe bisher: ${claude.respondUsers.size})",
                claude.answerStarted.await(2, TimeUnit.SECONDS),
            )
        } finally {
            claude.releaseGate.countDown()
            rig.close()
        }
    }

    @Test fun `haengende Vorpruefung laesst die naechste Frage nicht veralten`() {
        val claude = SlowGateClaude(gateBlocks = true)
        val rig = rig(claude)
        try {
            rig.pipeline.onCommitted(statement, null, null) // t = 0: Die Vorprüfung läuft und hängt
            assertTrue("Vorprüfung gestartet", claude.gateEntered.await(5, TimeUnit.SECONDS))
            rig.clock.set(300)
            rig.pipeline.onCommitted(question, null, null) // t = 0,3 s: die Frage des Trägers
            rig.clock.set(1_400) // t = 1,4 s: Die Höflichkeitspause ist vorbei, jetzt könnte gesprochen werden
            val spokenDuringStall = waitFor(3_000) { rig.speaker.spoken.isNotEmpty() }

            rig.clock.set(12_300) // t = 12,3 s: Erst jetzt gäbe die hängende Vorprüfung auf
            claude.releaseGate.countDown()
            waitFor(1_000) { rig.speaker.spoken.isNotEmpty() }

            assertTrue("die Antwort kam nicht, solange die Vorprüfung hing (zuletzt: ${rig.state.value.lastDecision})", spokenDuringStall)
            assertEquals(listOf(answer), rig.speaker.spoken.toList())
        } finally {
            claude.releaseGate.countDown()
            rig.close()
        }
    }

    @Test fun `Vorpruefung, die nicht antwortet, zaehlt nach kurzer Frist als weiter`() {
        val claude = SlowGateClaude(gateBlocks = true)
        val rig = rig(claude)
        try {
            rig.pipeline.onCommitted(statement, null, null)
            assertTrue("Vorprüfung gestartet", claude.gateEntered.await(5, TimeUnit.SECONDS))
            // Die Vorprüfung meldet sich nicht. Nach ihrer Frist (1,5 s) entscheidet das starke Modell ohne sie.
            assertTrue("der Antwort-Aufruf kam nie", claude.answerStarted.await(6, TimeUnit.SECONDS))
            assertTrue(claude.respondUsers.single().contains(">> [jetzt] ?: $statement"))
        } finally {
            claude.releaseGate.countDown()
            rig.close()
        }
    }

    @Test fun `Vorpruefung, die scheitert, zaehlt als weiter`() = runTest {
        val claude = FakeClaude(reply = { listOf("SAY kurz\n", answer) }, quickReply = { _, _ -> error("Netz weg") })
        val state = MutableStateFlow(UiState())
        val speaker = FakeSpeaker()
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, backgroundScope,
            StandardTestDispatcher(testScheduler), Pipeline.Config(), FakeEnv(),
        ) { testScheduler.currentTime }.also { it.start() }

        pipeline.onCommitted(statement, null, null)
        runCurrent()
        assertEquals(1, claude.quickCalls.size)
        assertEquals("das starke Modell entscheidet ohne die Vorprüfung", 1, claude.respondCalls.size)
        assertEquals(listOf(answer), speaker.spoken)
        assertEquals("ein Ausfall der Vorprüfung ist keine Fehlermeldung wert", null, state.value.error)
    }

    /** Die Vorprüfung kann schon laufen, während die Transkription noch auf das Satzende wartet. */
    @Test fun `Vorpruefung fuer eine Aussage laeuft schon vor dem Satzende`() = runTest {
        val claude = FakeClaude(reply = { listOf("SAY kurz\n", answer) }, quickReply = { _, _ -> "JA" })
        val speaker = FakeSpeaker()
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, MutableStateFlow(UiState()), backgroundScope,
            StandardTestDispatcher(testScheduler), Pipeline.Config(), FakeEnv(),
        ) { testScheduler.currentTime }.also { it.start() }
        val hidden = "der höchste Berg bei Garmisch na der Dings ich komm gleich drauf"
        assertEquals(Heuristics.Heat.MAYBE, Heuristics.heat(hidden, "Alfred"))

        pipeline.onPartial(hidden, 0.9) // die Transkription ist zu 90 % sicher, dass der Sprecher fertig ist
        runCurrent()
        assertEquals("Vorprüfungen vor der fertigen Äußerung", 1, claude.quickCalls.size)
        assertEquals("und danach gleich das starke Modell", 1, claude.respondCalls.size)
        assertTrue("vor dem Satzende wird nie gesprochen", speaker.spoken.isEmpty())

        pipeline.onCommitted(hidden, null, null)
        runCurrent()
        assertEquals("übernommen: keine zweite Vorprüfung", 1, claude.quickCalls.size)
        assertEquals(1, claude.respondCalls.size)
        assertEquals(listOf(answer), speaker.spoken)
    }

    @Test fun `Vorgriff ohne Vorpruefung wird nicht fuer eine beilaeufige Aussage uebernommen`() = runTest {
        val claude = FakeClaude(reply = { listOf("SAY kurz\n", answer) })
        val speaker = FakeSpeaker()
        val state = MutableStateFlow(UiState())
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, backgroundScope,
            StandardTestDispatcher(testScheduler), Pipeline.Config(), FakeEnv(),
        ) { testScheduler.currentTime }.also { it.start() }

        pipeline.onCommitted("Alfred, wie hoch ist die Zugspitze?", null, null)
        runCurrent()
        assertEquals(listOf(answer), speaker.spoken)

        // Kurz vor Ablauf der Nachfrage-Frist (20 s nach der Antwort) gilt der Satz noch als mögliche Nachfrage:
        // Der Vorgriff geht ohne Vorprüfung ans starke Modell.
        advanceTimeBy(19_900)
        pipeline.onPartial(statement, 0.9)
        runCurrent()
        assertEquals(2, claude.respondCalls.size)
        assertEquals(0, claude.quickCalls.size)

        // Die fertige Äußerung kommt nach der Frist: Jetzt ist es eine beiläufige Aussage, und für die gilt
        // die Vorprüfung. Der Vorgriff passt nicht mehr dazu und wird verworfen.
        advanceTimeBy(200)
        pipeline.onCommitted(statement, null, null)
        runCurrent()
        assertEquals(1, claude.quickCalls.size)
        assertEquals("übersprungen: kein Anlass", state.value.lastDecision)
        assertEquals(listOf(answer), speaker.spoken)
    }

    /** Die Vorprüfung hat eine eigene kurze Frist und keinen zweiten Versuch. Wie lange blockiert eine, die nie antwortet? */
    @Test fun `haengende Vorpruefung gibt nach kurzer Frist auf`() {
        val server = MockWebServer()
        // Erst eine normale Antwort (lädt die Klassen des Clients, damit die Messung danach nur die Frist misst) ...
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"NEIN"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1}}"""
        ))
        // ... dann nimmt der Server Anfragen an, antwortet aber nie.
        repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        server.start()
        try {
            val brain = Brain(AnthropicClaude("sk-test", server.url("/").toString()), Profile())
            val turn = Turn(emptyList(), Line(1, 0, Voice.UNKLAR, statement), Situation("Donnerstag, 21:14", MicMode.AIRPODS, false, null, null))
            assertEquals(false, brain.pregate(turn))
            val sentBefore = server.requestCount

            val t0 = System.nanoTime()
            val outcome = runCatching { brain.pregate(turn) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val attempts = server.requestCount - sentBefore
            assertTrue("eine hängende Vorprüfung muss mit einer Ausnahme enden, nicht mit einem Urteil", outcome.isFailure)
            assertTrue("eine hängende Vorprüfung blockierte $ms ms über $attempts Versuch(e)", ms < 5_000)
            assertEquals("kein zweiter Versuch", 1, attempts)
        } finally {
            runCatching { server.shutdown() }
        }
    }
}
