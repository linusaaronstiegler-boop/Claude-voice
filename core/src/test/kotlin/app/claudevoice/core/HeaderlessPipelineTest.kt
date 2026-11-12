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

/**
 * Antworten ohne Kopfzeile durch die ganze Pipeline: Was hört der Träger?
 *
 * Früher galt: ohne Kopfzeile nur nach direkter Anrede. Die Messung mit dem echten Modell hat gezeigt, dass
 * dabei gute Antworten verloren gehen ([MeasuredHeaderless]). Jetzt wird auch ohne Anrede gesprochen, mit
 * drei Sicherungen, die hier geprüft werden: höchstens so lang wie "kurz", durch dieselbe Höflichkeitspause
 * wie jede Antwort, und nicht, wenn der Text nach einem Kommentar zur Entscheidung aussieht ([Headerless]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeaderlessPipelineTest {

    private class Rig(scope: TestScope, val claude: FakeClaude) {
        val speaker = FakeSpeaker()
        val state = MutableStateFlow(UiState())
        val env = FakeEnv()
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), Pipeline.Config(), env,
        ) { scope.testScheduler.currentTime }.also { it.start() }

        fun say(text: String) = pipeline.onCommitted(text, null, null)

        /** Laute, tiefe Stimme vor der nächsten Äußerung: so klingt der Träger am Kopfhörer-Mikro. */
        fun own() {
            pipeline.onAudio(silence(300), 9600)
            val t = tone(115.0, 8000.0, 600)
            pipeline.onAudio(t, t.size)
        }
    }

    /** Eine klare Wissensfrage ohne Anrede: Sie geht ohne Vorprüfung ans Modell. */
    private val question = "Wie hoch ist die Zugspitze?"
    private val named = "Alfred, wie hoch ist die Zugspitze?"

    @Test fun `Antwort ohne Kopfzeile wird auch ohne Anrede gesprochen`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("Die Zugspitze ist knapp ", "dreitausend Meter hoch.") }))
        rig.say(question)
        runCurrent()
        assertEquals(listOf("Die Zugspitze ist knapp dreitausend Meter hoch."), rig.speaker.spoken)
        // Eine Länge hat das Modell nicht gewählt, also steht auch keine in der Anzeige.
        assertEquals("antwortet", rig.state.value.lastDecision)
        assertEquals(question, rig.state.value.question)
    }

    @Test fun `alle gemessenen Antworten ohne Kopfzeile sind ohne Anrede zu hoeren`() = runTest {
        // Dieselben 31 Antworten des echten Modells, an denen die Prüfung abgeleitet ist. Nach der alten Regel
        // wären die meisten davon verworfen worden ("übersprungen: format").
        for (reply in MeasuredHeaderless.REPLIES) {
            val where = "${reply.run}/${reply.mode}/${reply.id}"
            // In kleinen Stücken, so wie die Antwort vom Modell eintrifft.
            val rig = Rig(this, FakeClaude(reply = { reply.raw.chunked(6) }))
            rig.say(question)
            runCurrent()
            assertTrue("$where: nichts zu hören, zuletzt ${rig.state.value.lastDecision}", rig.speaker.spoken.isNotEmpty())
            assertTrue("$where: ${rig.state.value.lastDecision}", rig.state.value.lastDecision!!.startsWith("antwortet"))
            // Zu hören ist die Antwort von ihrem ersten Wort an, und keine Kopfzeile. Verglichen wird mit der
            // Anzeige: Sie behält den Text, wie das Modell ihn schrieb (die Stimme liest "403" als Wort).
            val shown = rig.state.value.answer!!
            assertTrue("$where: $shown", reply.raw.replace(Regex("\\s+"), " ").startsWith(shown))
            assertTrue("$where: ${rig.speaker.spoken}", rig.speaker.spoken.none { it.contains("SAY") })
        }
    }

    @Test fun `ohne Anrede gilt der Deckel von kurz, nach Anrede der von mittel`() = runTest {
        // So gemessen (A5/ohne/call-06): drei Sätze, 27 Wörter. Der Deckel für "kurz" liegt bei 22 Wörtern und
        // lässt nur ganze Sätze durch: Nach den ersten zwei (21 Wörter) ist Schluss.
        val first = "Kilowatt ist die Leistung, also wie stark ein Gerät gerade zieht."
        val second = "Kilowattstunde ist die verbrauchte Energie: ein Kilowatt, eine Stunde lang."
        val third = "Auf dem Zähler steht die Kilowattstunde."
        val reply = listOf("$first $second $third")
        assertEquals(27, Heuristics.words(reply.single()).size)

        val unnamed = Rig(this, FakeClaude(reply = { reply }))
        unnamed.say("was ist eigentlich der Unterschied zwischen Kilowatt und Kilowattstunde?")
        runCurrent()
        assertEquals("die Frage geht ohne Vorprüfung ans Modell", 1 to 0, unnamed.claude.respondCalls.size to unnamed.claude.quickCalls.size)
        assertEquals(listOf(first, second), unnamed.speaker.spoken)

        // Nach direkter Anrede gilt wie bisher "mittel" (50 Wörter): Alle drei Sätze sind zu hören.
        val addressed = Rig(this, FakeClaude(reply = { reply }))
        addressed.say("Alfred, was ist der Unterschied zwischen Kilowatt und Kilowattstunde?")
        runCurrent()
        assertEquals(listOf(first, second, third), addressed.speaker.spoken)
    }

    @Test fun `ein Kommentar zur Entscheidung bleibt ohne Anrede still und wird nach Anrede gesprochen`() = runTest {
        // Beide Formen stammen aus der Messung: die Ankündigung vor einer Kopfzeile und der SKIP-Grund,
        // hier ohne das SKIP davor.
        val announcement = listOf("Korrektur für die Aussprache:\n\n", "Einundzwanzig Komma neun sieben Kilometer.")
        for (reply in listOf(announcement, listOf("hintergrund"), listOf("**Keine Frage.**\n"))) {
            val rig = Rig(this, FakeClaude(reply = { reply }))
            rig.say(question)
            runCurrent()
            assertEquals("$reply", emptyList<String>(), rig.speaker.spoken)
            assertEquals("$reply", "übersprungen: format", rig.state.value.lastDecision)
        }
        // Wer den Assistenten beim Namen nennt, will eine Antwort hören, auch eine verunglückte. Das war
        // schon vorher so und bleibt so.
        val rig = Rig(this, FakeClaude(reply = { announcement }))
        rig.say(named)
        runCurrent()
        assertEquals(listOf("Korrektur für die Aussprache:", "Einundzwanzig Komma neun sieben Kilometer."), rig.speaker.spoken)
    }

    @Test fun `Antwort ohne Kopfzeile wartet im Gespraech die Hoeflichkeitspause ab und entfaellt, wenn ein Mensch antwortet`() = runTest {
        val answer = "Zweitausendneunhundertzweiundsechzig Meter."
        val rig = Rig(this, FakeClaude(reply = { user ->
            if (user.contains(">> [jetzt] ?: das sind ungefähr")) listOf("SKIP beantwortet") else listOf(answer)
        }))
        rig.say("wir waren letztes Jahr oben auf der Zugspitze")
        runCurrent()
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()
        assertTrue("noch zurückgehalten", rig.speaker.spoken.isEmpty())
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(listOf(answer), rig.speaker.spoken)

        // Zweiter Durchgang: diesmal antwortet jemand am Tisch selbst.
        advanceTimeBy(10_000)
        rig.say("und wie hoch ist der Watzmann?")
        runCurrent()
        advanceTimeBy(400)
        rig.say("das sind ungefähr zweitausendsiebenhundert Meter glaube ich")
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("die Antwort ohne Kopfzeile kam trotz der Antwort eines Menschen", listOf(answer), rig.speaker.spoken)
    }

    @Test fun `MEMO und MUTE in einer Antwort ohne Kopfzeile wirken weiterhin nur bei der Stimme des Traegers`() = runTest {
        // Ein Modell, das an eine Antwort ohne Kopfzeile auch noch Sonderzeilen hängt. Ob die Frage danach
        // verlangt hat, spielt hier keine Rolle: Geprüft wird, wer sie auslösen darf.
        val reply = listOf("Zweitausend Meter.\n", "MEMO: Kennzeichen M-AB 1234\n", "MUTE: 10")

        // Ohne own(): Die Stimme ist nicht als die des Trägers gesichert. Gesprochen wird jetzt auch ohne Anrede,
        // die Anweisungen bleiben aber ohne Wirkung, und vorgelesen werden sie nicht.
        val stranger = Rig(this, FakeClaude(reply = { reply }))
        stranger.say(question)
        runCurrent()
        assertEquals(listOf("Zweitausend Meter."), stranger.speaker.spoken)
        assertEquals(emptyList<String>(), stranger.env.notes)
        assertEquals(0L, stranger.state.value.mutedUntilMs)
        assertTrue(stranger.state.value.lastDecision!!.startsWith("abgelehnt"))

        // Dieselbe Äußerung mit der Stimme des Trägers: Die Schranke ist dieselbe wie bei jeder anderen Antwort.
        val wearer = Rig(this, FakeClaude(reply = { reply }))
        wearer.own()
        wearer.say(question)
        runCurrent()
        assertTrue(wearer.claude.respondCalls.last().contains("Sprecher der letzten Äußerung: nach der Stimme er selbst"))
        assertEquals(listOf("Zweitausend Meter."), wearer.speaker.spoken)
        assertEquals(listOf("Kennzeichen M-AB 1234"), wearer.env.notes)
        assertEquals(10L, (wearer.state.value.mutedUntilMs - testScheduler.currentTime) / 60_000)
    }

    @Test fun `die Antwort vor einer nachgetragenen Kopfzeile ist zu hoeren, mit deren Laenge`() = runTest {
        // So gemessen (A5/ohne/vor-20): erst die Antwort, dann als letzte Zeile "SAY kurz". Früher blieb davon
        // nur die Kopfzeile übrig: "antwortet (kurz)" in der Anzeige, im Ohr nichts.
        val raw = MeasuredHeaderless.REPLIES.single { it.id == "vor-20" }.raw
        assertTrue(raw.endsWith("\n\nSAY kurz"))
        for (pieces in listOf(listOf(raw), raw.chunked(5), raw.map { it.toString() }, listOf(raw, "\n"))) {
            val rig = Rig(this, FakeClaude(reply = { pieces }))
            rig.say("Ist die Venus eigentlich heißer als der Merkur?")
            runCurrent()
            assertEquals(1, rig.claude.respondCalls.size)
            assertEquals(
                listOf("Yes.", "Venus averages about four hundred sixty-four degrees Celsius, hotter even than Mercury."),
                rig.speaker.spoken,
            )
            assertEquals("antwortet (kurz)", rig.state.value.lastDecision)
        }
    }

    @Test fun `gemessene Antwort mit Kommentar vor der Kopfzeile - die Antwort ist zu hoeren`() = runTest {
        // So gemessen (A5/korrekt/bef-01). Die erste Zeile beginnt mit SKIP: Früher war damit entschieden, der
        // Aufruf wurde abgebrochen, und auf die Frage des Trägers kam nichts. In der Anzeige stand
        // "übersprungen: entscheidung entfällt, ich antworte".
        val raw = MeasuredHeaderless.AROUND_HEADER.single { it.id == "bef-01" }.raw
        for (pieces in listOf(listOf(raw), raw.chunked(7), raw.map { it.toString() })) {
            val rig = Rig(this, FakeClaude(reply = { pieces }))
            rig.say("wie viele Einwohner hat Lissabon überhaupt")
            runCurrent()
            assertEquals(
                "zuletzt=${rig.state.value.lastDecision}",
                listOf("Etwa fünfhunderttausend in der Stadt selbst, im Großraum knapp drei Millionen."), rig.speaker.spoken,
            )
            assertEquals("antwortet (kurz)", rig.state.value.lastDecision)
        }
    }

    @Test fun `gemessene Antwort mit zweiter Kopfzeile - weder SAY kurz noch die Ankuendigung sind zu hoeren`() = runTest {
        // So gemessen (A5/korrekt/mus-03): Das Modell verbessert seine eigene Aussprache. Früher waren vier Stücke
        // zu hören, darunter "Korrektur für die Aussprache:" und "SAY kurz". Der erste Versuch ist schon
        // gesprochen, wenn die Korrektur kommt; zurücknehmen lässt er sich nicht.
        val raw = MeasuredHeaderless.AROUND_HEADER.single { it.id == "mus-03" }.raw
        for (pieces in listOf(listOf(raw), raw.chunked(7), raw.map { it.toString() })) {
            val rig = Rig(this, FakeClaude(reply = { pieces }))
            rig.say("kurze Frage, wie lang ist ein Halbmarathon noch mal genau, nur die Zahl bitte")
            runCurrent()
            assertEquals(
                listOf(
                    "Einundzwanzig Komma null siebenundneunzig Kilometer.",
                    "Einundzwanzig Komma neun sieben Kilometer, also gerundet einundzwanzig Komma einen Kilometer.",
                ),
                rig.speaker.spoken,
            )
            assertEquals("antwortet (kurz)", rig.state.value.lastDecision)
        }
    }

    @Test fun `ein Satz mit SKIP am Anfang und keine Kopfzeile dahinter bleibt still, auch nach Anrede`() = runTest {
        // Die Gegenprobe zu bef-01: Folgt dem Kommentar keine Kopfzeile, darf er nicht als Antwort ohne
        // Kopfzeile durchgehen. Nach direkter Anrede würde sonst "SKIP-Entscheidung ..." vorgelesen.
        for (ask in listOf(question, named)) {
            val rig = Rig(this, FakeClaude(reply = { "SKIP weil die Frage an den Tischnachbarn ging.\nEr hat sie selbst beantwortet.".chunked(5) }))
            rig.say(ask)
            runCurrent()
            assertEquals(ask, emptyList<String>(), rig.speaker.spoken)
            assertEquals("übersprungen: weil die frage an den tischnachbarn ging", rig.state.value.lastDecision)
        }
    }

    @Test fun `steht hinter der verspaeteten Kopfzeile die Antwort, bleibt der Text davor ungesprochen`() = runTest {
        // Die Gegenprobe: Hier war der Text davor wirklich eine Begründung.
        val rig = Rig(this, FakeClaude(reply = { "Er fragt selbst nach der Höhe.\nSAY kurz\nZweitausend Meter.".chunked(4) }))
        rig.say(question)
        runCurrent()
        assertEquals(listOf("Zweitausend Meter."), rig.speaker.spoken)
    }
}
