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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bereitschaft: "Hey Jarvis" [Ton] "Wie spät ist es?".
 * Die Pause für den Ton (ab einer halben Sekunde) lässt die Transkription "Hey Jarvis" als eigene Äußerung
 * abschließen. Dieser bloße Ruf darf weder das Weckwort verbrauchen noch ein "Ja, Sir?" auslösen, das
 * über die eigentliche Frage spricht.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeSplitTest {

    private val ack = listOf("SAY kurz\n", "Ja, Sir?")
    private val time = listOf("SAY kurz\n", "Es ist einundzwanzig Uhr vierzehn.")
    private val hint = "Weckwort: Er hat dich unmittelbar"

    private class Rig(scope: TestScope, val speaker: FakeSpeaker, config: Pipeline.Config) {
        val claude = FakeClaude()
        val state = MutableStateFlow(UiState())
        val pipeline = Pipeline(
            Brain(claude, Profile(aliases = listOf("Jarvis"))), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, FakeEnv(),
        ) { scope.testScheduler.currentTime }.also { it.start() }

        fun say(text: String) = pipeline.onCommitted(text, null, null)

        private fun voice(hz: Double, amplitude: Double) {
            pipeline.onAudio(silence(300), 9600)
            val t = tone(hz, amplitude, 600)
            pipeline.onAudio(t, t.size)
        }

        /** Laut und tief: der Träger am Kopfhörer-Mikro. */
        fun own() = voice(115.0, 8000.0)
        /** Mittlere Lautstärke, andere Stimmlage: jemand am Tisch. */
        fun near() = voice(200.0, 1300.0)
        /** Sehr leise: Fernseher oder Nachbartisch. */
        fun far() = voice(180.0, 200.0)
    }

    private val jarvis = Pipeline.Config(names = listOf("Alfred", "Jarvis"))
    /** Mit Stimmprofil, damit der Stimm-Messer ICH, NAH und FERN unterscheidet. */
    private val measured = jarvis.copy(voiceProfile = VoiceProfile(-18.0, 115.0, 5))

    private fun rig(scope: TestScope, speaker: FakeSpeaker = FakeSpeaker(), config: Pipeline.Config = jarvis) =
        Rig(scope, speaker, config).also { r ->
            // Ein Modell, das sich an die Regeln hält: auf den bloßen Namen meldet es sich, auf die Frage antwortet es.
            r.claude.reply = { user -> if (user.contains("Wie spät")) time else ack }
        }

    @Test fun `Frage direkt nach dem allein abgeschlossenen Ruf erreicht das Modell`() = runTest {
        // Die Wiedergabe dauert, bis der Test sie freigibt: So lange liefe ein "Ja, Sir?" (Modell + Sprachausgabe).
        val playing = CompletableDeferred<Unit>()
        val rig = rig(this, FakeSpeaker(playing))
        rig.pipeline.onWakeWord()
        rig.say("Hey Jarvis") // aus dem Vorlauf, durch die Pause für den Ton allein abgeschlossen
        runCurrent()

        // 0,8 s später ist die eigentliche Frage fertig. Ein "Ja, Sir?" liefe jetzt noch und würde sie verschlucken.
        advanceTimeBy(800)
        rig.pipeline.onPartial("Wie spät", null)
        rig.pipeline.onPartial("Wie spät ist es", null)
        rig.say("Wie spät ist es?")
        runCurrent()

        playing.complete(Unit)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(listOf("?: Hey Jarvis", "?: Wie spät ist es?"), rig.state.value.transcript)
        assertTrue(
            "die Frage nach dem Weckwort muss das Modell erreichen: ${rig.claude.respondCalls.size} Aufruf(e), gesprochen ${rig.speaker.spoken}",
            rig.claude.respondCalls.any { it.contains(">> [jetzt] ?: Wie spät ist es?") },
        )
        assertEquals(listOf("Es ist einundzwanzig Uhr vierzehn."), rig.speaker.spoken)
    }

    @Test fun `der Ruf allein verbraucht das Weckwort nicht und bekommt kein Ja Sir`() = runTest {
        val rig = rig(this)
        rig.pipeline.onWakeWord()
        rig.say("Hey Jarvis")
        runCurrent()
        advanceTimeBy(2_500)
        rig.say("Wie spät ist es?")
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        val question = rig.claude.respondCalls.last { it.contains(">> [jetzt] ?: Wie spät ist es?") }
        assertTrue("die Frage trägt den Weckwort-Hinweis:\n$question", question.contains(hint))
        assertFalse("kein Ja, Sir? über der Frage: ${rig.speaker.spoken}", rig.speaker.spoken.contains("Ja, Sir?"))
        assertEquals("nur die Frage geht ans Modell", 1, rig.claude.respondCalls.size)
    }

    @Test fun `auch der verhoerte Ruf gilt nach dem Weckwort als blosser Ruf`() = runTest {
        val rig = rig(this)
        rig.pipeline.onWakeWord()
        rig.say("hey Travis")
        runCurrent()
        assertEquals(0, rig.claude.respondCalls.size)

        advanceTimeBy(1_500)
        rig.say("Wie spät ist es?")
        runCurrent()
        assertTrue(rig.claude.respondCalls.single().contains(hint))
        assertEquals(listOf("Es ist einundzwanzig Uhr vierzehn."), rig.speaker.spoken)
    }

    @Test fun `Vorgriff auf die Frage nach dem Ruf kennt das Weckwort und wird uebernommen`() = runTest {
        val rig = rig(this)
        rig.pipeline.onWakeWord()
        // Auch der Zwischenstand "Hey Jarvis" mit hoher Satzende-Wahrscheinlichkeit bereitet nichts vor.
        rig.pipeline.onPartial("Hey Jarvis", 0.9)
        runCurrent()
        assertEquals(0, rig.claude.respondCalls.size)
        rig.say("Hey Jarvis")
        runCurrent()

        advanceTimeBy(900)
        rig.pipeline.onPartial("Wie spät ist es", 0.9)
        runCurrent()
        assertEquals("Vorgriff ist gestartet", 1, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls[0], rig.claude.respondCalls[0].contains(hint))

        advanceTimeBy(300)
        rig.say("Wie spät ist es?")
        runCurrent()
        assertEquals("Vorgriff wurde übernommen, kein zweiter Aufruf", 1, rig.claude.respondCalls.size)
        assertEquals(listOf("Es ist einundzwanzig Uhr vierzehn."), rig.speaker.spoken)
    }

    @Test fun `nach der Frage ist das Weckwort verbraucht`() = runTest {
        val rig = rig(this)
        rig.claude.reply = { listOf("SKIP sozial") }
        rig.pipeline.onWakeWord()
        rig.say("Hey Jarvis")
        rig.say("Wie spät ist es?")
        runCurrent()
        advanceTimeBy(2_000)
        rig.say("Und wie wird das Wetter morgen?")
        runCurrent()

        assertEquals(2, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls[0].contains(hint))
        assertFalse("der Hinweis gilt nur für die eine Bitte", rig.claude.respondCalls[1].contains(hint))
    }

    // --- Wem das offene Fenster gehört -------------------------------------------------------------
    // Nach dem Ruf denkt der Träger oft noch kurz nach. Wer in dieser Zeit sonst etwas sagt, hat das
    // Weckwort nicht gesprochen und darf es auch nicht einlösen.

    @Test fun `ferne Stimme nach dem Ruf loest das Weckwort nicht ein`() = runTest {
        val rig = rig(this, config = measured.copy(reach = Reach.ICH))
        rig.claude.reply = { listOf("Ich helfe gern.") } // ohne Kopfzeile: gilt nur bei direkter Anrede
        rig.pipeline.onWakeWord()
        rig.own()
        rig.say("Hey Jarvis")
        runCurrent()
        advanceTimeBy(5_000)
        // Der Träger überlegt noch, im Fernseher geht es weiter. Die Reichweite steht auf "Nur ich".
        rig.far()
        rig.say("und jetzt zum Wetter für morgen")
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf("ICH: Hey Jarvis", "FERN: und jetzt zum Wetter für morgen"), rig.state.value.transcript)
        assertTrue(
            "die ferne Zeile ging als direkte Bitte ans Modell: " +
                rig.claude.respondCalls.map { c -> c.lines().first { it.startsWith(">>") } + " / Weckwort=" + c.contains("Weckwort:") },
            rig.claude.respondCalls.isEmpty(),
        )
        assertTrue("gesprochen: ${rig.speaker.spoken}", rig.speaker.spoken.isEmpty())

        // Das Fenster ist noch offen: Die Bitte des Trägers kommt danach an, mit Hinweis.
        advanceTimeBy(1_000)
        rig.claude.reply = { time }
        rig.own()
        rig.say("Wie spät ist es?")
        runCurrent()
        assertTrue(rig.claude.respondCalls.single().contains(hint))
        assertEquals(listOf("Es ist einundzwanzig Uhr vierzehn."), rig.speaker.spoken)
    }

    @Test fun `nach dem Ruf des Traegers loest auch der Tischnachbar das Weckwort nicht ein`() = runTest {
        // Der Freund fragt etwas, das der Vorfilter für eine klare Frage hält (so steht es in HeuristicsTableTest).
        // Nur dann geht seine Äußerung überhaupt ans Modell, und man sieht, ob sie den Hinweis trägt. Eine
        // persönliche Frage wie "Was machst du da eigentlich?" verwirft der Vorfilter inzwischen schon vorher.
        val neighbour = "Wann fängt der Film an?"
        assertEquals(Heuristics.Heat.HOT, Heuristics.heat(neighbour, measured.names))
        val rig = rig(this, config = measured)
        rig.claude.reply = { user -> if (user.contains(">> [jetzt] ICH: Wie spät")) time else listOf("SKIP sozial") }
        rig.pipeline.onWakeWord()
        rig.own()
        rig.say("Hey Jarvis")
        runCurrent()
        advanceTimeBy(2_000)
        rig.near()
        rig.say(neighbour)
        runCurrent()
        assertEquals(listOf("ICH: Hey Jarvis", "NAH: $neighbour"), rig.state.value.transcript)
        // Die Frage des Freundes geht ihren normalen Weg: ohne Hinweis, und das Modell schweigt dazu.
        assertFalse(rig.claude.respondCalls.single().contains(hint))
        assertTrue(rig.speaker.spoken.isEmpty())

        advanceTimeBy(2_000)
        rig.own()
        rig.say("Wie spät ist es?")
        runCurrent()
        assertTrue("die Bitte des Trägers trägt den Hinweis", rig.claude.respondCalls.last().contains(hint))
        assertEquals(listOf("Es ist einundzwanzig Uhr vierzehn."), rig.speaker.spoken)
    }

    @Test fun `nach einer Antwort ist das Weckwort eingeloest, auch wenn die Bitte leiser gemessen wurde`() = runTest {
        val rig = rig(this, config = measured)
        rig.pipeline.onWakeWord()
        rig.own()
        rig.say("Hey Jarvis")
        runCurrent()
        // Der Träger stellt seine Frage leiser als den Ruf: Die Akustik hält ihn für jemanden am Tisch. Die Frage
        // löst das Weckwort deshalb nicht ein, geht aber ihren normalen Weg und wird (nach der Pause) beantwortet.
        advanceTimeBy(1_500)
        rig.near()
        rig.say("Wie spät ist es?")
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(listOf("Es ist einundzwanzig Uhr vierzehn."), rig.speaker.spoken)
        assertFalse(rig.claude.respondCalls.single().contains(hint))

        // Sein nächster Satz ist keine Bitte nach dem Weckwort mehr, obwohl die zwölf Sekunden noch laufen.
        advanceTimeBy(3_000)
        rig.claude.reply = { listOf("SKIP sozial") }
        rig.own()
        rig.say("Und wie wird das Wetter morgen?")
        runCurrent()
        assertFalse("das Weckwort wirkt nach der Antwort noch nach", rig.claude.respondCalls.last().contains(hint))
    }

    @Test fun `ferne Stimme gleich nach dem Weckwort gilt nicht als die Bitte`() = runTest {
        // Der Ruf selbst ist nicht in der Mitschrift gelandet; als Erstes kommt ein Satz aus dem Fernseher.
        val rig = rig(this, config = measured.copy(reach = Reach.ICH))
        rig.pipeline.onWakeWord()
        rig.far()
        rig.say("und jetzt zum Wetter für morgen")
        runCurrent()
        assertEquals(0, rig.claude.respondCalls.size + rig.claude.quickCalls.size)

        advanceTimeBy(1_500)
        rig.own()
        rig.pipeline.onPartial("Wie spät ist es", 0.9)
        runCurrent()
        assertTrue("der Vorgriff auf die Bitte des Trägers kennt das Weckwort", rig.claude.respondCalls.single().contains(hint))
        rig.say("Wie spät ist es?")
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size)
        assertEquals(listOf("Es ist einundzwanzig Uhr vierzehn."), rig.speaker.spoken)
    }

    @Test fun `ein Ja aus dem Fernseher nach dem Weckwort macht ihn nicht zum Rufer`() = runTest {
        // Der Ruf selbst steht nicht in der Mitschrift. Aus dem Fernseher kommt erst ein "Ja.", dann ein Satz.
        // Das "Ja." gilt nach dem Weckwort als bloßer Ruf, damit ein Zögern nichts verbraucht. Wer gerufen hat,
        // sagt es aber nicht: Sonst hätte "der Ruf" fern geklungen, und der nächste ferne Satz gälte als die Bitte.
        assertTrue(isBareCall("Ja.", measured.names, afterWakeWord = true))
        val rig = rig(this, config = measured.copy(reach = Reach.ICH))
        rig.claude.reply = { listOf("Ich helfe gern.") } // ohne Kopfzeile: gilt nur bei direkter Anrede
        rig.pipeline.onWakeWord()
        rig.far()
        rig.say("Ja.")
        runCurrent()
        advanceTimeBy(1_000)
        rig.far()
        rig.say("und jetzt zum Wetter für morgen")
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf("FERN: Ja.", "FERN: und jetzt zum Wetter für morgen"), rig.state.value.transcript)
        assertTrue(
            "die ferne Zeile ging als direkte Bitte ans Modell: " +
                rig.claude.respondCalls.map { c -> c.lines().first { it.startsWith(">>") } + " / Weckwort=" + c.contains("Weckwort:") },
            rig.claude.respondCalls.isEmpty(),
        )
        assertTrue("gesprochen: ${rig.speaker.spoken}", rig.speaker.spoken.isEmpty())

        // Das Fenster ist noch offen: Die Bitte des Trägers kommt danach an, mit Hinweis.
        advanceTimeBy(1_000)
        rig.claude.reply = { time }
        rig.own()
        rig.say("Wie spät ist es?")
        runCurrent()
        assertTrue(rig.claude.respondCalls.single().contains(hint))
        assertEquals(listOf("Es ist einundzwanzig Uhr vierzehn."), rig.speaker.spoken)
    }

    @Test fun `klang schon der Ruf fern, darf auch die Bitte fern klingen`() = runTest {
        // Der Träger ruft und fragt leise (oder das Mikro hört ihn schlecht): Der Abstand sagt dann nichts.
        val rig = rig(this, config = measured)
        rig.pipeline.onWakeWord()
        rig.far()
        rig.say("Hey Jarvis")
        runCurrent()
        assertEquals(listOf("FERN: Hey Jarvis"), rig.state.value.transcript)
        advanceTimeBy(1_500)
        rig.far()
        rig.say("Wie spät ist es?")
        runCurrent()
        assertTrue(rig.claude.respondCalls.single().contains(hint))
        assertEquals(listOf("Es ist einundzwanzig Uhr vierzehn."), rig.speaker.spoken)
    }

    @Test fun `am Handy-Mikro gilt die erste Aeusserung nach dem Weckwort auch, wenn sie fern klingt`() = runTest {
        // Das Handy liegt auf dem Tisch: Dort kommt auch der Träger leise an. Der Ruf steht nicht in der Mitschrift.
        val rig = rig(this, config = jarvis.copy(mic = MicMode.HANDY))
        rig.claude.reply = { user -> if (user.contains(hint)) time else listOf("SKIP sozial") }
        rig.pipeline.onWakeWord()
        rig.pipeline.onAudio(silence(300), 9600)
        val quiet = tone(115.0, 150.0, 600) // rund -53 dBFS: unter der Grenze, ab der eine Stimme als fern gilt
        rig.pipeline.onAudio(quiet, quiet.size)
        rig.say("hey Travis, wie spät ist es?")
        runCurrent()
        assertEquals(listOf("FERN: hey Travis, wie spät ist es?"), rig.state.value.transcript)
        assertEquals(listOf("Es ist einundzwanzig Uhr vierzehn."), rig.speaker.spoken)
    }

    @Test fun `Vorgriff auf einen fernen Satz nach dem Weckwort gilt nicht als direkte Bitte`() = runTest {
        // Eine Quizfrage aus dem Fernseher, als Zwischenstand ohne Satzzeichen. Sie muss für den Vorfilter eine
        // klare Frage sein (so steht sie in HeuristicsTableTest): Nur dafür bekommt eine ferne Stimme überhaupt
        // einen Vorgriff. "Und was sagen Sie dazu" reicht dafür nicht mehr, wer mit "Sie" gemeint ist, bleibt offen.
        val tv = "wer hat eigentlich das Telefon erfunden"
        assertEquals(Heuristics.Heat.HOT, Heuristics.heat(tv, measured.names))
        val rig = rig(this, config = measured)
        rig.claude.reply = { listOf("SKIP hintergrund") }
        rig.pipeline.onWakeWord()
        rig.own()
        rig.say("Hey Jarvis")
        runCurrent()
        advanceTimeBy(2_000)
        rig.far()
        rig.pipeline.onPartial(tv, 0.9)
        runCurrent()
        val call = rig.claude.respondCalls.single()
        assertTrue(call, call.contains(">> [jetzt] FERN: $tv"))
        assertFalse("der Vorgriff hält den fernen Satz für die Bitte", call.contains(hint))
    }

    @Test fun `ohne Weckwort meldet er sich auf den blossen Namen wie bisher`() = runTest {
        val rig = rig(this)
        rig.say("Hey Jarvis")
        runCurrent()
        assertEquals(listOf("Ja, Sir?"), rig.speaker.spoken)
    }

    @Test fun `das Fenster nach dem Weckwort schliesst sich von selbst`() = runTest {
        val rig = rig(this)
        rig.claude.reply = { listOf("SKIP sozial") }
        rig.pipeline.onWakeWord()
        rig.say("Hey Jarvis")
        runCurrent()
        advanceTimeBy(13_000) // länger als das Fenster: Die nächste Äußerung ist keine Anrede mehr
        rig.say("Wie spät ist es?")
        runCurrent()
        assertFalse(rig.claude.respondCalls.single().contains(hint))
    }
}

class BareCallTest {
    private val names = listOf("Alfred", "Jarvis")

    @Test fun `blosser Ruf wird erkannt, eine Bitte nicht`() {
        // "hey dschawis": Die Namenserkennung vergleicht nach Klang und nimmt diese Schreibweise als den Namen.
        for (s in listOf("Hey Jarvis", "Jarvis?", "hallo Alfred", "äh hey Jarvis", "hey jar vis", "hey dschawis")) {
            assertTrue(s, isBareCall(s, names, afterWakeWord = false))
            assertTrue(s, isBareCall(s, names, afterWakeWord = true))
        }
        for (s in listOf("Hey Jarvis, Uhrzeit", "Wie spät?", "also Datum", "Jarvis stopp", "Hey Jarvis wie spät ist es", "")) {
            assertFalse(s, isBareCall(s, names, afterWakeWord = false))
            assertFalse(s, isBareCall(s, names, afterWakeWord = true))
        }
    }

    @Test fun `ein Ruf ohne jeden Namen verraet nicht, wer gerufen hat`() {
        // Gruß und Füllsel allein: nach dem Weckwort ein bloßer Ruf, aber keiner, der den Rufer festlegt.
        for (s in listOf("Ja.", "Hey", "äh hey", "okay", "hm ja")) {
            assertTrue(s, isBareCall(s, names, afterWakeWord = true))
            assertTrue(s, isNamelessCall(s))
        }
        // Fällt ein Name, auch ein verhörter, hat jemand gerufen.
        for (s in listOf("Hey Jarvis", "Jarvis?", "hey dschawis", "hey Travis", "ja Jarvis")) {
            assertTrue(s, isBareCall(s, names, afterWakeWord = true))
            assertFalse(s, isNamelessCall(s))
        }
        assertFalse(isNamelessCall(""))
    }

    @Test fun `verhoerter oder verschluckter Name zaehlt nur direkt nach dem Weckwort`() {
        for (s in listOf("hey Travis", "hey Charvis", "Hey", "äh hey")) {
            assertTrue(s, isBareCall(s, names, afterWakeWord = true))
            assertFalse(s, isBareCall(s, names, afterWakeWord = false))
        }
    }
}
