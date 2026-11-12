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
 * Dieselben Abweichungen wie in [ReplyProtocolTest], aber durch die ganze Pipeline: Was hört der Träger,
 * was wird gespeichert, wie lange herrscht Ruhe? Ob eine Sonderzeile wirken darf, entscheidet weiterhin
 * die Pipeline an der Stimme (`trusted`); hier spricht deshalb, wo es darauf ankommt, der Träger selbst.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplyProtocolPipelineTest {

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

    private val zugspitze = listOf("SAY kurz\n", "Zweitausendneunhundertzweiundsechzig Meter.")

    // ------------------------------------------------------------------------------------------
    // Kopfzeile

    @Test fun `einzeilige SAY-Antwort ist zu hoeren`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz Zweitausendneunhundertzweiundsechzig Meter.") }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(listOf("Zweitausendneunhundertzweiundsechzig Meter."), rig.speaker.spoken)
        assertEquals("antwortet (kurz)", rig.state.value.lastDecision)
    }

    @Test fun `fett gesetztes SAY wird beantwortet, auch ohne Anrede`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("**SAY kurz**\n", "Zweitausend Meter.") }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(listOf("Zweitausend Meter."), rig.speaker.spoken)
    }

    @Test fun `angesprochen und fett gesetztes SKIP bleibt still`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("**SKIP medien**") }))
        rig.say("Alfred, wer hat gestern das Spiel gewonnen?")
        runCurrent()
        assertEquals(emptyList<String>(), rig.speaker.spoken)
        assertEquals("übersprungen: medien", rig.state.value.lastDecision)
    }

    @Test fun `angesprochen und SKIP nach einer Begruendungszeile bleibt still und oeffnet kein Nachfragefenster`() = runTest {
        val rig = Rig(
            this,
            FakeClaude(reply = { listOf("Die Frage kommt aus dem Fernseher, nicht von ihm.\n", "SKIP medien") }),
        )
        rig.say("Alfred, wer hat gestern das Spiel gewonnen?")
        runCurrent()
        // Nachfragefenster: Eine Aussage ohne Fragewort und ohne Namen löst sonst keinen Antwort-Aufruf aus.
        advanceTimeBy(5_000)
        val before = rig.claude.respondCalls.size
        rig.say("na gut dann eben nicht")
        runCurrent()
        assertEquals("Kopfzeile oder Begründung wurden vorgelesen", emptyList<String>(), rig.speaker.spoken)
        assertEquals("Nachfragefenster wurde geöffnet", before, rig.claude.respondCalls.size)
    }

    @Test fun `Kontrolle - sauberes SKIP oeffnet kein Nachfragefenster`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP medien") }))
        rig.say("Alfred, wer hat gestern das Spiel gewonnen?")
        runCurrent()
        advanceTimeBy(5_000)
        val before = rig.claude.respondCalls.size
        rig.say("na gut dann eben nicht")
        runCurrent()
        assertEquals(before, rig.claude.respondCalls.size)
        assertEquals(emptyList<String>(), rig.speaker.spoken)
    }

    @Test fun `Code-Block um die Antwort und Satzzeichen hinter der Kopfzeile werden nicht gesprochen`() = runTest {
        // Ein Stück ohne Buchstaben und Ziffern ist kein Satz: weder der Zaun am Ende noch ein einzelnes "!".
        for (reply in listOf("```\nSAY kurz\nZweitausend Meter.\n```", "SAY kurz!\nZweitausend Meter.", "SAY kurz …\nZweitausend Meter.")) {
            val rig = Rig(this, FakeClaude(reply = { listOf(reply) }))
            rig.say("Alfred, wie hoch ist die Zugspitze?")
            runCurrent()
            assertEquals(reply, listOf("Zweitausend Meter."), rig.speaker.spoken)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Ein langer erster Satz und der Längendeckel

    /** 36 Wörter in einem Satz, mehr als der Deckel für "kurz" (22 Wörter) und über 120 Zeichen. */
    private val longSentence = "Nun, Sir, die Sache ist die, dass der Mond um die Erde kreist und die Erde um die Sonne, " +
        "und weil das Licht der Sonne den Mond von der Seite trifft, sehen wir nur einen Teil."

    /** Eine Antwort aus einem früheren Auswertungslauf: "kurz", ein Satz, 24 Wörter, 156 Zeichen. */
    private val recorded = "Hoch dreiundzwanzig: Die Konstante ist sechs Komma null zwei mal zehn hoch dreiundzwanzig, " +
        "mal null Komma fünf also etwa drei mal zehn hoch dreiundzwanzig."

    @Test fun `ein langer erster Satz ist bis zu seinem Ende zu hoeren`() = runTest {
        // Der Längendeckel lässt den ersten Satz immer durch. Das gilt nur, solange der Satz als ein Stück
        // ankommt: Wurde er am Komma geteilt, galt die zweite Hälfte als weiterer Satz und fiel weg.
        for (sentence in listOf(recorded, longSentence)) for (pieces in listOf(listOf("SAY kurz\n", sentence), "SAY kurz\n$sentence".chunked(5))) {
            val rig = Rig(this, FakeClaude(reply = { pieces }))
            rig.say("Alfred, erklär mir das bitte kurz")
            runCurrent()
            assertEquals(listOf(sentence), rig.speaker.spoken)
        }
    }

    @Test fun `MUTE und MEMO hinter einem langen ersten Satz wirken`() = runTest {
        val mute = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", longSentence, "\nMUTE: 10") }))
        mute.own()
        mute.say("Alfred, erklär das kurz und sei dann zehn Minuten still")
        runCurrent()
        assertEquals(listOf(longSentence), mute.speaker.spoken)
        assertEquals(10L, (mute.state.value.mutedUntilMs - testScheduler.currentTime) / 60_000)

        val memo = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", longSentence, "\nMEMO: Mondphasen erklärt") }))
        memo.own()
        memo.say("Alfred, erklär das kurz und merk dir, dass du es erklärt hast")
        runCurrent()
        assertEquals(listOf(longSentence), memo.speaker.spoken)
        assertEquals(listOf("Mondphasen erklärt"), memo.env.notes.toList())
    }

    // ------------------------------------------------------------------------------------------
    // Sonderzeilen

    @Test fun `Merken vor einer verspaeteten Kopfzeile speichert die Notiz`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("MEMO: Zahnarzt am Dienstag\n", "SAY kurz\n", "Notiert, Sir.") }))
        rig.own()
        rig.say("Alfred, merk dir Zahnarzt am Dienstag")
        runCurrent()
        assertEquals(
            "gesprochen / gespeichert",
            listOf("Notiert, Sir.") to listOf("Zahnarzt am Dienstag"),
            rig.speaker.spoken.toList() to rig.env.notes.toList(),
        )
    }

    @Test fun `Stummschalten vor einer verspaeteten Kopfzeile schaltet stumm, aber nur bei der Stimme des Traegers`() = runTest {
        val reply = listOf("MUTE: 10\nSAY kurz\nSehr wohl, Sir.")
        val wearer = Rig(this, FakeClaude(reply = { reply }))
        wearer.own()
        wearer.say("Alfred, sei bitte zehn Minuten ruhig")
        runCurrent()
        assertEquals(listOf("Sehr wohl, Sir."), wearer.speaker.spoken)
        assertEquals(10L, (wearer.state.value.mutedUntilMs - testScheduler.currentTime) / 60_000)

        // Ohne own(): Die Stimme ist nicht als die des Trägers gesichert, also bleibt die Anweisung ohne Wirkung.
        val stranger = Rig(this, FakeClaude(reply = { reply }))
        stranger.say("Alfred, sei bitte zehn Minuten ruhig")
        runCurrent()
        assertEquals(listOf("Sehr wohl, Sir."), stranger.speaker.spoken)
        assertEquals(0L, stranger.state.value.mutedUntilMs)
    }

    @Test fun `Sonderzeile mit Hervorhebung vor dem Doppelpunkt wird ausgefuehrt und nicht vorgelesen`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\nNotiert, Sir.\n", "**MEMO**: Kennzeichen M-AB 1234\n**MUTE** 10") }))
        rig.own()
        rig.say("Alfred, merk dir das Kennzeichen und sei zehn Minuten still")
        runCurrent()
        assertEquals(listOf("Notiert, Sir.") to listOf("Kennzeichen M-AB 1234"), rig.speaker.spoken.toList() to rig.env.notes.toList())
        assertEquals(10L, (rig.state.value.mutedUntilMs - testScheduler.currentTime) / 60_000)
    }

    @Test fun `ein Satz ueber die Taste schaltet nicht stumm und speichert nichts`() = runTest {
        // Auch bei der Stimme des Trägers: Mitten im Satz ist MUTE: oder MEMO: Sprechtext.
        val reply = "SAY mittel\nDie Taste heißt MUTE: damit schaltest du stumm. Notizen hänge ich mit MEMO: und dem Text an."
        val rig = Rig(this, FakeClaude(reply = { listOf(reply) }))
        rig.own()
        rig.say("Alfred, wie heißt die Taste zum Stummschalten und wie merkst du dir etwas?")
        runCurrent()
        assertEquals(
            listOf("Die Taste heißt MUTE: damit schaltest du stumm.", "Notizen hänge ich mit MEMO: und dem Text an."),
            rig.speaker.spoken,
        )
        assertEquals(0L, rig.state.value.mutedUntilMs)
        assertEquals(emptyList<String>(), rig.env.notes)
    }

    @Test fun `Merken in derselben Zeile wie der Sprechtext speichert die Notiz und liest sie nicht vor`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Notiert, Sir. MEMO: Kennzeichen M-AB 1234") }))
        rig.own()
        rig.say("Alfred, merk dir das Kennzeichen M AB 1234")
        runCurrent()
        assertTrue(rig.claude.respondCalls.last().contains("Sprecher der letzten Äußerung: nach der Stimme er selbst"))
        assertEquals(
            "gesprochen / gespeichert",
            listOf("Notiert, Sir.") to listOf("Kennzeichen M-AB 1234"),
            rig.speaker.spoken.toList() to rig.env.notes.toList(),
        )
    }

    @Test fun `Stummschalten in derselben Zeile wie der Sprechtext schaltet stumm und liest es nicht vor`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\nSehr wohl, Sir. MUTE: 10") }))
        rig.own()
        rig.say("Alfred, sei bitte zehn Minuten ruhig, ich muss lernen")
        runCurrent()
        assertEquals(
            "gesprochen / Ruhe in Minuten",
            listOf("Sehr wohl, Sir.") to 10L,
            rig.speaker.spoken.toList() to (rig.state.value.mutedUntilMs - testScheduler.currentTime) / 60_000,
        )
    }

    @Test fun `eine abweichend geschriebene Sonderzeile wirkt weiterhin nur bei der Stimme des Traegers`() = runTest {
        // Ohne rig.own(): Die Stimme ist nicht als die des Trägers gesichert. Der Parser meldet die
        // Anweisung, die Pipeline lehnt sie ab; vorgelesen wird sie trotzdem nicht.
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\nSehr wohl, Sir. MUTE: 10\n- Memo: Kennzeichen M-AB 1234") }))
        rig.say("Alfred, sei bitte zehn Minuten ruhig und merk dir das Kennzeichen")
        runCurrent()
        assertEquals(listOf("Sehr wohl, Sir."), rig.speaker.spoken)
        assertEquals(0L, rig.state.value.mutedUntilMs)
        assertEquals(emptyList<String>(), rig.env.notes)
    }

    // ------------------------------------------------------------------------------------------
    // Dauer der Ruhe

    @Test fun `zwei Stunden Ruhe dauern laenger als drei Minuten`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\nSehr wohl, Sir.\nMUTE: 2 Stunden") }))
        rig.own()
        rig.say("Alfred, sei bitte zwei Stunden ruhig, ich bin im Meeting")
        runCurrent()
        assertEquals(listOf("Sehr wohl, Sir."), rig.speaker.spoken)
        assertEquals(120L, (rig.state.value.mutedUntilMs - testScheduler.currentTime) / 60_000)

        // Drei Minuten später fragt jemand am Tisch etwas: Der Assistent muss still bleiben.
        val before = rig.claude.respondCalls.size
        rig.claude.reply = { zugspitze }
        advanceTimeBy(3 * 60_000L)
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("Das Modell wurde trotz zugesagter Ruhe gerufen", before, rig.claude.respondCalls.size)
        assertEquals(listOf("Sehr wohl, Sir."), rig.speaker.spoken)
    }

    @Test fun `in Worten zugesagte Ruhe schaltet wirklich stumm`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\nSehr wohl, Sir.\nMUTE: eine Stunde") }))
        rig.own()
        rig.say("Alfred, sei bitte eine Stunde ruhig, ich bin im Meeting")
        runCurrent()
        assertEquals(listOf("Sehr wohl, Sir."), rig.speaker.spoken)
        assertEquals(60L, (rig.state.value.mutedUntilMs - testScheduler.currentTime) / 60_000)
    }
}
