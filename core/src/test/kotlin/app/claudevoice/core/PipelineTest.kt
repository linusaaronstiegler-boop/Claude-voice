package app.claudevoice.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineTest {

    private class Rig(
        scope: TestScope,
        val claude: FakeClaude = FakeClaude(),
        val speaker: FakeSpeaker = FakeSpeaker(),
        config: Pipeline.Config = Pipeline.Config(),
    ) {
        val state = MutableStateFlow(UiState())
        val env = FakeEnv()
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, env,
        ) { scope.testScheduler.currentTime }.also { it.start() }

        fun say(text: String) = pipeline.onCommitted(text, null, null)
    }

    private val zugspitze = listOf("SAY kurz\n", "Zweitausendneunhundertzweiundsechzig Meter. ", "Sie ist der höchste Berg Deutschlands.")

    @Test fun `Frage wird in einem Aufruf entschieden, beantwortet und satzweise gesprochen`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()

        assertEquals(
            listOf("Zweitausendneunhundertzweiundsechzig Meter.", "Sie ist der höchste Berg Deutschlands."),
            rig.speaker.spoken,
        )
        assertEquals("Wie hoch ist die Zugspitze?", rig.state.value.question)
        assertEquals("antwortet (kurz)", rig.state.value.lastDecision)
        assertNotNull(rig.state.value.latencyMs)
        assertFalse(rig.state.value.speaking)
        assertEquals(0, rig.claude.quickCalls.size) // offensichtliche Frage: keine Vorprüfung
        assertTrue(rig.claude.systemSeen!!.contains("Du bist Alfred"))
    }

    @Test fun `eigene Antwort steht im Verlauf und Nachfrage loest ohne Fragewort aus`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        advanceTimeBy(5_000)
        rig.say("und seit wann")
        runCurrent()

        assertEquals(2, rig.claude.respondCalls.size)
        val second = rig.claude.respondCalls[1]
        assertTrue(second, second.contains("ALFRED: Zweitausendneunhundertzweiundsechzig Meter."))
        assertTrue(second, second.contains("Du hast zuletzt gesprochen: vor 5 s"))
        assertTrue(second, second.contains(">> [jetzt] ?: und seit wann"))
    }

    @Test fun `SKIP spricht nichts und nennt den Grund`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP hinter", "grund\nDas darf nie gesprochen werden.") }))
        rig.say("Wer hat eigentlich das Spiel gewonnen?")
        runCurrent()

        assertTrue(rig.speaker.spoken.isEmpty())
        assertEquals("übersprungen: hintergrund", rig.state.value.lastDecision)
        assertNull(rig.state.value.question)
        assertNull(rig.state.value.error)
    }

    @Test fun `Fuellsel kostet keinen Aufruf, steht aber in der Mitschrift`() = runTest {
        val rig = Rig(this)
        rig.say("ja genau")
        runCurrent()
        assertEquals(0, rig.claude.respondCalls.size + rig.claude.quickCalls.size)
        assertEquals(listOf("?: ja genau"), rig.state.value.transcript)
    }

    @Test fun `Aussage geht erst durch die billige Vorpruefung`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say("ich hab gestern den ganzen Abend an dem Aufsatz gesessen")
        runCurrent()
        assertEquals(1, rig.claude.quickCalls.size)
        assertEquals(0, rig.claude.respondCalls.size)
        assertEquals("übersprungen: kein Anlass", rig.state.value.lastDecision)

        rig.claude.quickReply = { _, _ -> "JA" }
        rig.say("mir fällt einfach nicht ein wie dieser höchste Berg bei Garmisch heißt")
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size)
    }

    @Test fun `ferne Stimme loest nichts aus, ausser sie nennt den Namen`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }), config = Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)))
        fun quiet() {
            rig.pipeline.onAudio(silence(300), 9600)
            val t = tone(180.0, 200.0, 600)
            rig.pipeline.onAudio(t, t.size)
        }
        quiet()
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(0, rig.claude.respondCalls.size)
        assertEquals(listOf("FERN: Wie hoch ist die Zugspitze?"), rig.state.value.transcript)

        quiet()
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size) // das Modell sieht FERN und entscheidet selbst
        assertTrue(rig.claude.respondCalls[0].contains(">> [jetzt] FERN: Alfred"))
    }

    @Test fun `im Gespraech wartet die Antwort eine Sekunde und entfaellt, wenn ein Mensch antwortet`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { user ->
            if (user.contains(">> [jetzt] ?: das sind ungefähr")) listOf("SKIP beantwortet") else zugspitze
        }))
        rig.say("wir waren letztes Jahr oben auf der Zugspitze")
        runCurrent()
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()
        assertTrue("noch zurückgehalten", rig.speaker.spoken.isEmpty())
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, rig.speaker.spoken.size)

        // Zweiter Durchgang: diesmal antwortet jemand am Tisch selbst.
        advanceTimeBy(10_000)
        rig.say("und wie hoch ist der Watzmann?")
        runCurrent()
        advanceTimeBy(400)
        rig.say("das sind ungefähr zweitausendsiebenhundert Meter glaube ich")
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(2, rig.speaker.spoken.size)
        assertEquals(3, rig.claude.respondCalls.size) // Watzmann-Antwort war fertig, wurde aber nicht gesprochen
    }

    @Test fun `verfehltes Format wird nur bei direkter Anrede gesprochen`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("Die Zugspitze ist knapp dreitausend Meter hoch.") }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertTrue(rig.speaker.spoken.isEmpty())
        assertEquals("übersprungen: format", rig.state.value.lastDecision)

        rig.say("Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(listOf("Die Zugspitze ist knapp dreitausend Meter hoch."), rig.speaker.spoken)
    }

    @Test fun `merken und stummschalten, Anrede hebt die Stummschaltung auf`() = runTest {
        val rig = Rig(this)
        rig.claude.reply = { listOf("SAY kurz\nNotiert.\nMEMO: Kennzeichen M-AB 1234\n") }
        rig.say("Alfred, merk dir das Kennzeichen M AB 1234")
        runCurrent()
        assertEquals(listOf("Kennzeichen M-AB 1234"), rig.env.notes)
        assertEquals(listOf("Notiert."), rig.speaker.spoken)

        rig.claude.reply = { listOf("SAY kurz\nSehr wohl.\nMUTE: 10") }
        rig.say("Alfred, sei bitte zehn Minuten ruhig, ich muss lernen")
        runCurrent()
        assertTrue(rig.state.value.mutedUntilMs > 0)

        val before = rig.claude.respondCalls.size
        rig.claude.reply = { zugspitze }
        advanceTimeBy(60_000)
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(before, rig.claude.respondCalls.size)

        rig.say("Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(before + 1, rig.claude.respondCalls.size)
        assertEquals(0L, rig.state.value.mutedUntilMs)
        assertTrue(rig.claude.respondCalls.last().contains("<heute_gemerkt>\n- Kennzeichen M-AB 1234"))
    }

    @Test fun `Vorgriff startet vor dem Satzende und wird bei gleichem Text uebernommen`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.pipeline.onPartial("Wie hoch ist die Zugspitze", 0.3)
        runCurrent()
        assertEquals(0, rig.claude.respondCalls.size)
        rig.pipeline.onPartial("Wie hoch ist die Zugspitze?", 0.8)
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size)
        assertTrue("vor dem Satzende wird nie gesprochen", rig.speaker.spoken.isEmpty())

        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size)
        assertEquals(2, rig.speaker.spoken.size)
    }

    @Test fun `Vorgriff wird verworfen, wenn der Satz anders endet`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.pipeline.onPartial("Wie hoch ist", 0.8)
        runCurrent()
        rig.say("Wie hoch ist der Eiffelturm?")
        runCurrent()
        assertEquals(2, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls[1].contains("Eiffelturm"))
        assertEquals(2, rig.speaker.spoken.size)
    }

    @Test fun `Alfred stopp bricht die laufende Antwort ab`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(this, FakeClaude(reply = { zugspitze }), FakeSpeaker(gate))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertTrue(rig.state.value.speaking)
        assertEquals(1, rig.speaker.spoken.size)

        rig.say("Alfred, stopp")
        runCurrent()
        assertTrue(rig.speaker.stops >= 1)
        assertFalse(rig.state.value.speaking)
        assertEquals(1, rig.claude.respondCalls.size) // der Stoppbefehl selbst geht nicht ans Modell
    }

    @Test fun `Ordner liefert Szene und markiert fremde Zeilen`() = runTest {
        val rig = Rig(this, FakeClaude(
            reply = { zugspitze },
            quickReply = { system, _ -> if (system.startsWith("Du ordnest")) "SZENE: WG-Party, er spricht mit Jonas über Berge.\nABSEITS: 2" else "NEIN" },
        ))
        advanceTimeBy(20_000)
        rig.say("wir waren letztes Jahr oben auf der Zugspitze")
        rig.say("und jetzt die Lottozahlen vom Mittwoch dreizehn siebzehn")
        rig.say("das war echt anstrengend mit dem ganzen Gepäck")
        rig.say("aber die Aussicht war jeden Schritt wert sag ich dir")
        runCurrent()
        assertEquals("WG-Party, er spricht mit Jonas über Berge.", rig.state.value.scene)

        rig.say("Alfred, wie hoch ist die eigentlich?")
        runCurrent()
        val seen = rig.claude.respondCalls.last()
        assertTrue(seen, seen.contains("Szene: WG-Party, er spricht mit Jonas über Berge."))
        assertTrue(seen, seen.contains("(abseits) ?: und jetzt die Lottozahlen"))
        assertFalse(seen, seen.contains("(abseits) ?: wir waren"))
    }

    @Test fun `Diarisierung traegt Sprecher nach und trennt zwei Stimmen in einer Zeile`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.pipeline.onCommitted("hallo zusammen wie gehts euch", "speaker_0", 7)
        rig.pipeline.onDiarized(7, listOf(
            SpokenPart("speaker_0", "hallo zusammen ", 0.0, 0.8),
            SpokenPart("speaker_1", "wie gehts euch", 0.9, 1.6),
        ))
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        val seen = rig.claude.respondCalls.last()
        assertTrue(seen, seen.contains("S1: hallo zusammen / S2: wie gehts euch"))
    }

    @Test fun `Fehler der API landet als Meldung im Zustand`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { error("kaputt") }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertTrue(rig.state.value.error!!.contains("kaputt"))
        assertFalse(rig.state.value.speaking)
    }
}
