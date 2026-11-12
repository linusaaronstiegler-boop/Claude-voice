package app.claudevoice.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Vorwärmen: Die Leitungen zur Sprachausgabe und zu Claude sollen stehen, bevor die Antwort sie braucht,
 * und das Vorwärmen selbst darf nie einer Antwort im Weg stehen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WarmUpTest {

    private class Rig(scope: TestScope, val claude: FakeClaude = FakeClaude()) {
        val speaker = FakeSpeaker()
        val state = MutableStateFlow(UiState())
        val pipeline = Pipeline(
            Brain(claude, Profile(aliases = listOf("Jarvis"))), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), Pipeline.Config(names = listOf("Alfred", "Jarvis")), FakeEnv(),
        ) { scope.testScheduler.currentTime }.also { it.start() }
    }

    private val minutes = 60_000L
    private val statement = "ich hab gestern den ganzen Abend an dem Aufsatz gesessen"

    // --- Sprachausgabe ----------------------------------------------------------------------------

    @Test fun `Weckwort bereitet die Sprachausgabe vor`() = runTest {
        val rig = Rig(this)
        rig.pipeline.onWakeWord()
        assertEquals(1, rig.speaker.prepares)
    }

    @Test fun `Vorgriff bereitet die Sprachausgabe vor`() = runTest {
        val rig = Rig(this)
        rig.pipeline.onPartial("Wie hoch ist die Zugspitze", 0.3)
        runCurrent()
        assertEquals("noch kein Vorgriff, also auch kein Vorbereiten", 0, rig.speaker.prepares)
        rig.pipeline.onPartial("Wie hoch ist die Zugspitze", 0.8)
        runCurrent()
        assertEquals(1, rig.speaker.prepares)
        // Derselbe Vorgriff läuft weiter: nicht noch einmal.
        rig.pipeline.onPartial("Wie hoch ist die Zugspitze", 0.9)
        runCurrent()
        assertEquals(1, rig.speaker.prepares)
    }

    @Test fun `auch ohne Vorgriff steht die Sprachausgabe bereit, sobald der Antwort-Aufruf startet`() = runTest {
        val rig = Rig(this)
        rig.pipeline.onCommitted("Wie hoch ist die Zugspitze?", null, null)
        runCurrent()
        assertEquals(1, rig.speaker.prepares)
    }

    @Test fun `beilaeufige Aussage ohne Anlass fasst die Sprachausgabe nicht an`() = runTest {
        val rig = Rig(this)
        rig.pipeline.onPartial(statement, 0.9)
        rig.pipeline.onCommitted(statement, null, null)
        runCurrent()
        assertEquals("die Vorprüfung lief", 1, rig.claude.quickCalls.size)
        assertEquals("übersprungen: kein Anlass", rig.state.value.lastDecision)
        assertEquals(0, rig.speaker.prepares)

        // Sagt die Vorprüfung JA, wird vorbereitet, während das starke Modell schreibt.
        rig.claude.quickReply = { _, _ -> "JA" }
        advanceTimeBy(2_000)
        rig.pipeline.onCommitted("der höchste Berg bei Garmisch na der Dings ich komm gleich drauf", null, null)
        runCurrent()
        assertEquals(1, rig.speaker.prepares)
    }

    // --- Claude -----------------------------------------------------------------------------------

    @Test fun `nach vier Minuten Funkstille weckt der erste Zwischenstand die Leitung zu Claude`() = runTest {
        val rig = Rig(this)
        advanceTimeBy(5 * minutes)
        rig.pipeline.onPartial("Wie", null)
        runCurrent()
        assertEquals(1, rig.claude.warmUps)

        // Weitere Zwischenstände derselben Äußerung und die nächste Äußerung kurz danach wecken nicht noch einmal.
        rig.pipeline.onPartial("Wie hoch ist", null)
        rig.pipeline.onCommitted("Wie hoch ist die Zugspitze?", null, null)
        runCurrent()
        advanceTimeBy(1 * minutes)
        rig.pipeline.onPartial("und", null)
        runCurrent()
        assertEquals(1, rig.claude.warmUps)
    }

    @Test fun `ohne Funkstille wird nicht vorgewaermt`() = runTest {
        val rig = Rig(this)
        advanceTimeBy(3 * minutes)
        rig.pipeline.onPartial("Wie hoch", null)
        runCurrent()
        assertEquals(0, rig.claude.warmUps)
    }

    @Test fun `Weckwort nach langer Pause weckt auch die Leitung zu Claude`() = runTest {
        val rig = Rig(this)
        advanceTimeBy(20 * minutes)
        rig.pipeline.onWakeWord()
        runCurrent()
        assertEquals(1, rig.claude.warmUps)
        // Der Zwischenstand gleich danach weckt nicht noch einmal.
        rig.pipeline.onPartial("Hey Jarvis wie", null)
        runCurrent()
        assertEquals(1, rig.claude.warmUps)
    }

    @Test fun `der grosse Prompt wird aufgefrischt, auch wenn das schnelle Modell die Verbindung wach haelt`() = runTest {
        val rig = Rig(this)
        // Alle drei Minuten eine beiläufige Aussage: Die Vorprüfung (schnelles Modell) hält die Verbindung wach,
        // aber der Zwischenspeicher für den Prompt des starken Modells läuft nach einer Stunde ab.
        repeat(16) {
            advanceTimeBy(3 * minutes)
            rig.pipeline.onPartial("ich hab", null)
            rig.pipeline.onCommitted(statement, null, null)
            runCurrent()
        }
        assertEquals("48 Minuten: noch frisch genug", 0, rig.claude.warmUps)
        assertEquals(0, rig.claude.respondCalls.size)

        advanceTimeBy(3 * minutes)
        rig.pipeline.onPartial("ich hab", null)
        runCurrent()
        assertEquals("nach 51 Minuten ohne Aufruf des starken Modells", 1, rig.claude.warmUps)
    }

    @Test fun `scheitert das Vorwaermen, bleibt das ohne Folgen`() = runTest {
        val claude = object : ClaudeApi {
            var warmUps = 0
            override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
                listOf("SAY kurz\n", "Knapp dreitausend Meter.").forEach(onText)
            }
            override fun quick(system: String, user: String, maxTokens: Long) = "NEIN"
            override fun warmUp(system: String) { warmUps++; error("kein Netz") }
        }
        val speaker = FakeSpeaker()
        val state = MutableStateFlow(UiState())
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, backgroundScope,
            StandardTestDispatcher(testScheduler), Pipeline.Config(), FakeEnv(),
        ) { testScheduler.currentTime }.also { it.start() }

        advanceTimeBy(5 * minutes)
        pipeline.onPartial("Wie hoch", null)
        pipeline.onCommitted("Wie hoch ist die Zugspitze?", null, null)
        runCurrent()
        assertEquals(1, claude.warmUps)
        assertEquals(listOf("Knapp dreitausend Meter."), speaker.spoken)
        assertEquals(null, state.value.error)
    }

    /** Mit echten Threads: Das Vorwärmen hängt, die Antwort kommt trotzdem. */
    @Test fun `haengendes Vorwaermen haelt keine Antwort auf`() {
        val warmUpStarted = CountDownLatch(1)
        val releaseWarmUp = CountDownLatch(1)
        val claude = object : ClaudeApi {
            override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
                listOf("SAY kurz\n", "Knapp dreitausend Meter.").forEach(onText)
            }
            override fun quick(system: String, user: String, maxTokens: Long) = "NEIN"
            override fun warmUp(system: String) {
                warmUpStarted.countDown()
                releaseWarmUp.await(30, TimeUnit.SECONDS)
            }
        }
        val rig = ThreadRig(claude)
        try {
            rig.clock.set(5 * minutes)
            rig.pipeline.onPartial("Wie hoch", null)
            assertTrue("das Vorwärmen ist gestartet", warmUpStarted.await(5, TimeUnit.SECONDS))

            rig.pipeline.onCommitted("Wie hoch ist die Zugspitze?", null, null)
            assertTrue("die Antwort wartet nicht auf das Vorwärmen", waitFor(5_000) { rig.speaker.spoken.isNotEmpty() })
            assertEquals(1L, releaseWarmUp.count) // das Vorwärmen hängt immer noch
        } finally {
            releaseWarmUp.countDown()
            rig.close()
        }
    }
}
