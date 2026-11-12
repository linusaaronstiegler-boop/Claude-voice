package app.claudevoice.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Vorgriff muss dem Modell dasselbe vorlegen wie später die fertige Äußerung: Weckwort, Stimme, Sprecher.
 * Stimmt etwas davon nicht überein, darf er nicht übernommen werden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeculationContextTest {

    private class Rig(scope: TestScope, val claude: FakeClaude, config: Pipeline.Config = Pipeline.Config()) {
        val state = MutableStateFlow(UiState())
        val speaker = FakeSpeaker()
        val env = FakeEnv()
        val pipeline = Pipeline(
            Brain(claude, Profile(aliases = config.names.drop(1))), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, env,
        ) { scope.testScheduler.currentTime }.also { it.start() }

        /** Tiefe Stimme (115 Hz) in der gewünschten Lautstärke vor der nächsten Äußerung. */
        fun voice(amplitude: Double) {
            pipeline.onAudio(silence(300), 9600)
            val t = tone(115.0, amplitude, 600)
            pipeline.onAudio(t, t.size)
        }
    }

    private val jarvis = Pipeline.Config(names = listOf("Alfred", "Jarvis"))
    private val answer = listOf("SAY kurz\n", "Knapp dreitausend Meter.")
    private val misheard = "Hey Travis, wie hoch ist die Zugspitze?"
    private val hint = "Weckwort: Er hat dich unmittelbar"

    // --- Weckwort ---------------------------------------------------------------------------------

    /** Kontrolle: Ohne Zwischenstand trägt der Aufruf den Weckwort-Hinweis. */
    @Test fun `ohne Vorgriff traegt der Aufruf den Weckwort-Hinweis`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { answer }), jarvis)
        rig.pipeline.onWakeWord()
        rig.pipeline.onCommitted(misheard, null, null)
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls[0], rig.claude.respondCalls[0].contains(hint))
    }

    @Test fun `uebernommener Vorgriff nach dem Weckwort traegt den Weckwort-Hinweis`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { answer }), jarvis)
        rig.pipeline.onWakeWord()
        rig.pipeline.onPartial(misheard, 0.8)
        runCurrent()
        assertEquals("Vorgriff ist gestartet", 1, rig.claude.respondCalls.size)

        rig.pipeline.onCommitted(misheard, null, null)
        runCurrent()
        assertEquals("Vorgriff wurde übernommen, kein zweiter Aufruf", 1, rig.claude.respondCalls.size)
        assertTrue(
            "der einzige Aufruf an das Modell enthält keinen Weckwort-Hinweis:\n" + rig.claude.respondCalls[0],
            rig.claude.respondCalls[0].contains(hint),
        )
        assertEquals(listOf("Knapp dreitausend Meter."), rig.speaker.spoken)
    }

    /**
     * Die Folge für den Träger, mit einem Modell, das sich an die Regeln hält: Eine Frage an "Travis" ist
     * ohne den Hinweis nicht an den Assistenten gerichtet (SKIP), mit Hinweis wird geantwortet.
     */
    @Test fun `Frage nach dem Weckwort wird auch mit Vorgriff beantwortet`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { user -> if (user.contains("Weckwort:")) answer else listOf("SKIP sozial") }), jarvis)
        rig.pipeline.onWakeWord()
        rig.pipeline.onPartial(misheard, 0.8)
        runCurrent()
        rig.pipeline.onCommitted(misheard, null, null)
        runCurrent()
        assertEquals(
            "nach dem Weckwort bleibt es still (" + rig.state.value.lastDecision + ")",
            listOf("Knapp dreitausend Meter."), rig.speaker.spoken,
        )
    }

    @Test fun `Vorgriff mit anderem Weckwort-Stand wird nicht uebernommen`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP sozial") }), jarvis)
        rig.pipeline.onWakeWord()
        advanceTimeBy(11_900) // kurz bevor sich das Fenster nach dem Weckwort schließt
        rig.pipeline.onPartial("wie hoch ist die Zugspitze", 0.8)
        runCurrent()
        assertTrue("der Vorgriff kennt das Weckwort", rig.claude.respondCalls.single().contains(hint))

        advanceTimeBy(200) // die fertige Äußerung kommt erst, als das Fenster schon zu ist
        rig.pipeline.onCommitted("wie hoch ist die Zugspitze", null, null)
        runCurrent()
        assertEquals("ein frischer Aufruf ersetzt den Vorgriff", 2, rig.claude.respondCalls.size)
        assertFalse(rig.claude.respondCalls[1].contains(hint))
    }

    // --- Stimme und Sprecher ----------------------------------------------------------------------

    @Test fun `Vorgriff ueberlebt, wenn die Diarisierung den Traeger erkennt`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP keine-frage") }))
        // Zwei laute Äußerungen von speaker_0 mit Anrede: Die Pipeline lernt, dass speaker_0 der Träger ist.
        rig.voice(8000.0)
        rig.pipeline.onCommitted("Alfred, wie spät ist es", "speaker_0", 0)
        runCurrent()
        rig.voice(8000.0)
        rig.pipeline.onCommitted("Alfred, wie wird das Wetter morgen", "speaker_0", 1)
        runCurrent()
        assertTrue(rig.claude.respondCalls.last().contains("Sprecher der letzten Äußerung: nach der Stimme er selbst"))
        val before = rig.claude.respondCalls.size

        // Jetzt spricht der Träger 12 dB leiser: Die Akustik allein sagt NAH, die Diarisierung weiter speaker_0.
        rig.voice(2000.0)
        rig.pipeline.onPartial("Wie hoch ist die Zugspitze", 0.8, "speaker_0")
        runCurrent()
        assertEquals("Vorgriff ist gestartet", before + 1, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls.last(), rig.claude.respondCalls.last().contains(">> [jetzt] ICH S1: Wie hoch ist die Zugspitze"))

        rig.pipeline.onCommitted("Wie hoch ist die Zugspitze", "speaker_0", 2)
        runCurrent()
        // Gleicher Text, gleicher Sprecher: Ein Aufruf muss reichen.
        assertEquals(
            "der Vorgriff wurde verworfen und der Prompt ein zweites Mal geschickt; zuletzt=" +
                rig.claude.respondCalls.last().lines().last { it.startsWith(">>") },
            before + 1, rig.claude.respondCalls.size,
        )
    }

    @Test fun `uebernommener Vorgriff zeigt dieselbe Zeile wie ein Aufruf nach dem Satzende`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP keine-frage") }))
        rig.pipeline.onPartial("Wie hoch ist die Zugspitze", 0.8, "speaker_0")
        runCurrent()
        rig.pipeline.onCommitted("Wie hoch ist die Zugspitze", "speaker_0", 0)
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size) // übernommen
        assertTrue(
            "Zeile des übernommenen Aufrufs: " + rig.claude.respondCalls.last().lines().last { it.startsWith(">>") },
            rig.claude.respondCalls.last().contains(">> [jetzt] ? S1: Wie hoch ist die Zugspitze"),
        )
    }

    @Test fun `Vorgriff ohne Sprecher wird verworfen, wenn die fertige Aeusserung einen nennt`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP keine-frage") }))
        rig.pipeline.onPartial("Wie hoch ist die Zugspitze", 0.8)
        runCurrent()
        rig.pipeline.onCommitted("Wie hoch ist die Zugspitze", "speaker_0", 0)
        runCurrent()
        // Lieber ein zweiter Aufruf als einer, der dem Modell etwas anderes gezeigt hat, als gilt.
        assertEquals(2, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls.last().contains(">> [jetzt] ? S1: Wie hoch ist die Zugspitze"))
    }

    @Test fun `Vorgriff mit Anrede rechnet wie die fertige Aeusserung, lernt aber nichts`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Viertel nach neun.") }))
        rig.voice(8000.0)
        rig.pipeline.onPartial("Alfred, wie spät ist es", 0.8)
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls[0].contains("Sprecher der letzten Äußerung: nach der Stimme er selbst"))
        assertTrue("aus einem Zwischenstand wird kein Stimmprofil gelernt", rig.env.profiles.isEmpty())

        rig.pipeline.onCommitted("Alfred, wie spät ist es", null, null)
        runCurrent()
        assertEquals("übernommen", 1, rig.claude.respondCalls.size)
        assertEquals("gelernt wird erst aus der fertigen Äußerung", 1, rig.env.profiles.size)
        assertEquals(listOf("Viertel nach neun."), rig.speaker.spoken)
    }

    @Test fun `Nachfrage ohne Fragewort bekommt ebenfalls einen Vorgriff`() = runTest {
        // Ohne die Antwort davor wäre der Satz für den Vorfilter kein Anlass (COLD); "und seit wann" ist das
        // wegen des Frageworts nicht mehr.
        val followUp = "und in Fuß"
        assertEquals(Heuristics.Heat.COLD, Heuristics.heat(followUp, "Alfred"))
        val rig = Rig(this, FakeClaude(reply = { answer }))
        rig.pipeline.onCommitted("Wie hoch ist die Zugspitze?", null, null)
        runCurrent()
        advanceTimeBy(5_000)

        rig.pipeline.onPartial(followUp, 0.8)
        runCurrent()
        assertEquals("die Nachfrage ist schon vor dem Satzende unterwegs", 2, rig.claude.respondCalls.size)
        rig.pipeline.onCommitted(followUp, null, null)
        runCurrent()
        assertEquals(2, rig.claude.respondCalls.size)
        assertEquals("als Nachfrage geht der Satz ohne Vorprüfung ans Modell", 0, rig.claude.quickCalls.size)
        assertTrue(rig.claude.respondCalls[1].contains("ALFRED: Knapp dreitausend Meter."))
    }

    @Test fun `Vorgriff von vor einer eigenen Antwort wird nicht uebernommen`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { answer }))
        // Die Anrede ist angenommen, ihr Auftrag hat noch nicht begonnen. Da meldet die Transkription
        // schon das wahrscheinliche Ende des nächsten Satzes: Der Vorgriff startet ohne die Antwort von gleich.
        // Der Träger ist dabei gut zu hören: Nur seine gemessene Stimme darf eine wartende Anrede verdrängen.
        rig.pipeline.onCommitted("Alfred, wie hoch ist die Zugspitze?", null, null)
        rig.voice(8000.0)
        rig.pipeline.onPartial("und wie hoch ist der Watzmann", 0.8)
        runCurrent()
        assertEquals(listOf("Knapp dreitausend Meter."), rig.speaker.spoken)
        assertEquals("Anrede und Vorgriff", 2, rig.claude.respondCalls.size)

        advanceTimeBy(500)
        rig.pipeline.onCommitted("und wie hoch ist der Watzmann", null, null)
        runCurrent()
        // Dem Vorgriff fehlt die eben gesprochene Antwort in der Mitschrift: Er wird durch einen frischen Aufruf ersetzt.
        assertEquals(3, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls.last(), rig.claude.respondCalls.last().contains("ALFRED: Knapp dreitausend Meter."))
        advanceTimeBy(1_000) // die erste Zeile stammt von einer unbekannten Stimme: Es gilt die Höflichkeitspause
        runCurrent()
        assertEquals(2, rig.speaker.spoken.size)
    }
}
