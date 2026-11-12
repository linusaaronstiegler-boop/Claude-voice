package app.claudevoice.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Höflichkeitspause: In Gesellschaft wartet die Antwort eine Sekunde Stille ab. Geprüft wird, ab wann
 * die Sekunde zählt, wer als Gesellschaft gilt, dass die Sprachausgabe in der Pause schon arbeitet und
 * dass nichts als gesagt gilt, wenn die Schranke am Ende zu bleibt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HoldTest {

    private class Rig(scope: TestScope, val claude: FakeClaude, config: Pipeline.Config = Pipeline.Config()) {
        val speaker = RecordingSpeaker()
        val state = MutableStateFlow(UiState())
        val env = FakeEnv()
        /** Zusätzlich verstrichene Zeit, die ein Test von Hand einschiebt (etwa "das Modell hat lange gebraucht"). */
        var lagMs = 0L
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, env,
        ) { scope.testScheduler.currentTime + lagMs }.also { it.start() }

        fun say(text: String) = pipeline.onCommitted(text, null, null)

        private fun voice(hz: Double, amplitude: Double) {
            pipeline.onAudio(silence(300), 9600)
            val t = tone(hz, amplitude, 600)
            pipeline.onAudio(t, t.size)
        }

        /** Laut und tief: der Träger am Kopfhörer-Mikro. */
        fun own() = voice(115.0, 8000.0)
        /** Sehr leise, höhere Stimme: Fernseher oder Nachbartisch (gilt als FERN). */
        fun far() = voice(180.0, 200.0)
        /** Mittlere Lautstärke, andere Stimmlage: jemand am Tisch (gilt als NAH). */
        fun near() = voice(200.0, 1300.0)
    }

    private val first = "Zweitausendneunhundertzweiundsechzig Meter."
    private val second = "Sie ist der höchste Berg Deutschlands."
    private val zugspitze = listOf("SAY kurz\n", "$first ", second)
    private val profile = Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5))
    private val friend = "wir waren letztes Jahr oben auf der Zugspitze"

    /** Rückt die virtuelle Zeit in 10-ms-Schritten vor, bis etwas zu hören ist, und nennt diesen Zeitpunkt. */
    private fun TestScope.timeOfFirstSpeech(rig: Rig, limitMs: Long = 5_000): Long? {
        val end = testScheduler.currentTime + limitMs
        while (testScheduler.currentTime < end) {
            runCurrent()
            if (rig.speaker.spoken.isNotEmpty()) return testScheduler.currentTime
            advanceTimeBy(10)
        }
        return null
    }

    // --- Ab wann die Sekunde zählt ----------------------------------------------------------------

    @Test fun `die Sekunde zaehlt ab dem Ende der Sprache, nicht ab der fertigen Aeusserung`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say(friend) // ein Freund, vor zehn Sekunden
        runCurrent()
        advanceTimeBy(10_000)

        // Das letzte Wort ist gesprochen: Die Transkription zeigt den ganzen Satz als Zwischenstand.
        val speechEnd = testScheduler.currentTime
        rig.pipeline.onPartial("Wie hoch ist die eigentlich?", null)
        runCurrent()
        // Stille-Frist der Transkription: Die fertige Äußerung trifft eine halbe Sekunde später ein.
        advanceTimeBy(500)
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()
        assertEquals("das Modell hat schon geantwortet", 1, rig.claude.respondCalls.size)
        assertTrue("in Gesellschaft wird gewartet", rig.speaker.spoken.isEmpty())

        val spokenAt = timeOfFirstSpeech(rig)!!
        assertTrue("erster Satz ${spokenAt - speechEnd} ms nach dem Ende der Sprache", spokenAt - speechEnd in 950..1_050)
    }

    @Test fun `uebernommener Vorgriff wartet nicht laenger als eine Sekunde Stille`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say(friend)
        runCurrent()
        advanceTimeBy(10_000)

        val speechEnd = testScheduler.currentTime
        rig.pipeline.onPartial("Wie hoch ist die eigentlich?", 0.9) // der Vorgriff startet jetzt
        runCurrent()
        assertEquals("der Vorgriff lief vor der fertigen Äußerung", 1, rig.claude.respondCalls.size)
        advanceTimeBy(500)
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()
        assertEquals("der Vorgriff wurde übernommen", 1, rig.claude.respondCalls.size)

        val spokenAt = timeOfFirstSpeech(rig)!!
        assertTrue("erster Satz ${spokenAt - speechEnd} ms nach dem Ende der Sprache", spokenAt - speechEnd in 950..1_050)
    }

    @Test fun `nach der fertigen Aeusserung wird mindestens kurz gewartet`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say(friend)
        runCurrent()
        advanceTimeBy(10_000)

        rig.pipeline.onPartial("Wie hoch ist die eigentlich?", null)
        advanceTimeBy(2_000) // die fertige Äußerung kommt sehr spät: Die Sekunde Stille ist längst vorbei
        val committedAt = testScheduler.currentTime
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()
        assertTrue("nicht sofort: Ob jemand antwortet, zeigt sich erst mit etwas Verzug", rig.speaker.spoken.isEmpty())

        val spokenAt = timeOfFirstSpeech(rig)!!
        assertTrue("erster Satz ${spokenAt - committedAt} ms nach der fertigen Äußerung", spokenAt - committedAt in 300..450)
    }

    @Test fun `wiederholter gleicher Zwischenstand verschiebt das Ende der Sprache nicht`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say(friend)
        runCurrent()
        advanceTimeBy(10_000)

        val speechEnd = testScheduler.currentTime
        rig.pipeline.onPartial("Wie hoch ist die eigentlich", null)
        advanceTimeBy(300)
        rig.pipeline.onPartial("Wie hoch ist die eigentlich?", null) // derselbe Wortlaut, nur das Satzzeichen ist neu
        advanceTimeBy(200)
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()

        val spokenAt = timeOfFirstSpeech(rig)!!
        assertTrue("erster Satz ${spokenAt - speechEnd} ms nach dem Ende der Sprache", spokenAt - speechEnd in 950..1_050)
    }

    // --- Wer als Gesellschaft gilt ----------------------------------------------------------------

    @Test fun `ferne Stimme in der Mitschrift haelt den Traeger, der allein ist, nicht auf`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }), profile)
        rig.far()
        rig.say("und jetzt die Lottozahlen vom Mittwoch dreizehn siebzehn")
        runCurrent()
        assertEquals(listOf("FERN: und jetzt die Lottozahlen vom Mittwoch dreizehn siebzehn"), rig.state.value.transcript)
        assertEquals(0, rig.claude.respondCalls.size + rig.claude.quickCalls.size)

        advanceTimeBy(5_000)
        rig.own()
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertTrue(rig.claude.respondCalls.single().contains(">> [jetzt] ICH: Wie hoch ist die Zugspitze?"))
        // Niemand ist in seinem Gespräch: Die Antwort ist fertig und wird sofort gesprochen.
        assertEquals("gesprochen=${rig.speaker.spoken} zuletzt=${rig.state.value.lastDecision}", 2, rig.speaker.spoken.size)
    }

    @Test fun `Fernseher, der in der Pause weiterredet, zieht die Antwort nicht zurueck`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }), profile)
        rig.near()
        rig.say(friend) // ein Freund am Tisch: echte Gesellschaft
        runCurrent()
        advanceTimeBy(5_000)
        rig.own()
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()
        assertTrue("zurückgehalten, ein Freund könnte antworten", rig.speaker.spoken.isEmpty())

        // 300 ms später redet der Fernseher weiter: ferne Stimme, zwei Inhaltswörter im Zwischenstand.
        advanceTimeBy(300)
        rig.far()
        rig.pipeline.onPartial("die Gewinnzahlen der Ziehung lauten", null)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("gesprochen=${rig.speaker.spoken} zuletzt=${rig.state.value.lastDecision}", 2, rig.speaker.spoken.size)
    }

    @Test fun `Nachbartisch, der in der Pause einen Satz beendet, zieht die Antwort nicht zurueck`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }), profile)
        rig.near()
        rig.say(friend)
        runCurrent()
        advanceTimeBy(5_000)
        rig.own()
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()
        assertTrue("zurückgehalten, ein Freund könnte antworten", rig.speaker.spoken.isEmpty())

        // Der Nachbartisch: ferne Stimme, erst als Zwischenstand, dann als fertige Äußerung, beides in der Sekunde.
        advanceTimeBy(300)
        rig.far()
        rig.pipeline.onPartial("die Rechnung zahlen wir", null)
        advanceTimeBy(300)
        rig.say("die Rechnung zahlen wir getrennt")
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("gesprochen=${rig.speaker.spoken} zuletzt=${rig.state.value.lastDecision}", 2, rig.speaker.spoken.size)
    }

    @Test fun `Freund am Tisch, der in der Pause antwortet, zieht die Antwort zurueck`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }), profile)
        rig.near()
        rig.say(friend)
        runCurrent()
        advanceTimeBy(5_000)
        rig.own()
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()

        advanceTimeBy(300)
        rig.near()
        rig.pipeline.onPartial("das sind fast dreitausend", null)
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue("gesprochen=${rig.speaker.spoken}", rig.speaker.spoken.isEmpty())
        assertEquals("übersprungen: mensch antwortet", rig.state.value.lastDecision)
    }

    // --- Jemand setzt gerade zur Antwort an ---------------------------------------------------------
    // Dass ein Mensch antwortet, zeigt sich im Text erst mit zwei Inhaltswörtern, also spät. Zu hören ist er
    // früher. Läuft die Pause ab, während jemand hörbar spricht, wartet die Antwort bis eine volle Sekunde
    // nach der fertigen Äußerung, damit seine Worte noch ankommen können.

    /**
     * Die Frage endet, die fertige Äußerung kommt 500 ms später. Ein Freund beginnt 200 ms danach zu sprechen
     * (bei [audible] für den Stimm-Messer hörbar), sein Text mit zwei Inhaltswörtern trifft [textAfterMs] nach
     * der fertigen Äußerung ein. Mit [firstWordAfterMs] meldet die Transkription davor schon sein erstes Wort.
     *
     * @return wann nach der fertigen Äußerung der erste Satz des Assistenten zu hören war; null = nie.
     */
    private fun TestScope.spokenAfterCommit(textAfterMs: Long?, audible: Boolean, firstWordAfterMs: Long? = null): Long? {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", first) }))
        rig.say(friend)
        runCurrent()
        advanceTimeBy(10_000)
        rig.pipeline.onPartial("Wie hoch ist die eigentlich?", null) // das letzte Wort ist gesprochen
        advanceTimeBy(500)
        val committedAt = testScheduler.currentTime
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()

        var spokenAt: Long? = null
        var started = false
        var begun = false
        var sent = false
        while (testScheduler.currentTime < committedAt + 3_000) {
            val t = testScheduler.currentTime - committedAt
            if (audible && !started && t >= 200) { rig.near(); started = true }
            if (firstWordAfterMs != null && !begun && t >= firstWordAfterMs) {
                rig.pipeline.onPartial("das", null)
                begun = true
            }
            if (textAfterMs != null && !sent && t >= textAfterMs) {
                rig.pipeline.onPartial("das sind fast dreitausend", null)
                sent = true
            }
            runCurrent()
            if (spokenAt == null && rig.speaker.spoken.isNotEmpty()) spokenAt = t
            advanceTimeBy(10)
        }
        return spokenAt
    }

    @Test fun `setzt niemand zur Antwort an, bleibt es bei der Sekunde ab dem Ende der Sprache`() = runTest {
        val at = spokenAfterCommit(textAfterMs = null, audible = false)!!
        assertTrue("erster Satz $at ms nach der fertigen Äußerung", at in 450..550)
    }

    @Test fun `Antwort eines Freundes, die kurz vor Ablauf der Pause im Text steht, zieht die Antwort zurueck`() = runTest {
        assertNull(spokenAfterCommit(textAfterMs = 400, audible = true))
    }

    @Test fun `Freund setzt hoerbar an, sein Text kommt erst nach Ablauf der Pause - die Antwort wartet darauf`() = runTest {
        assertNull("nach 600 ms", spokenAfterCommit(textAfterMs = 600, audible = true))
    }

    @Test fun `auch 900 ms nach der fertigen Aeusserung kommt der Text des Freundes noch rechtzeitig`() = runTest {
        assertNull("nach 900 ms", spokenAfterCommit(textAfterMs = 900, audible = true))
    }

    @Test fun `spricht nach der Frage jemand, ohne etwas zu sagen, wird nach einer Sekunde geantwortet`() = runTest {
        // Hörbar ist jemand, aber es kommt kein Text mit Inhalt (ein "Ähm", ein Lachen): Dann ist Hilfe willkommen.
        val at = spokenAfterCommit(textAfterMs = null, audible = true)!!
        assertTrue("erster Satz $at ms nach der fertigen Äußerung", at in 950..1_050)
    }

    @Test fun `Freund ist nicht messbar, aber sein erstes Wort steht schon da - die Antwort wartet auf den Rest`() = runTest {
        // Zu leise für den Stimm-Messer. Die Transkription hat aber vor Ablauf der Pause schon "das" gemeldet.
        assertNull(spokenAfterCommit(textAfterMs = 900, audible = false, firstWordAfterMs = 400))
    }

    @Test fun `Grenze - eine Antwort, die bis zum Ablauf der Pause weder zu hoeren noch zu lesen ist, bleibt unbemerkt`() = runTest {
        // Kein Wunschverhalten, sondern der Preis dafür, dass die Sekunde ab dem Ende der Sprache zählt: Meldet
        // der Stimm-Messer nichts und kommt der erste Text erst nach Ablauf der Pause, spricht der Assistent
        // hinein. Zählte die Sekunde wieder ab der fertigen Äußerung, wäre jede Antwort eine halbe Sekunde später.
        val at = spokenAfterCommit(textAfterMs = 600, audible = false)
        assertTrue("erster Satz $at ms nach der fertigen Äußerung", at != null && at in 450..550)
    }

    // --- Was als gesagt gilt ------------------------------------------------------------------------

    /** Nimmt Sätze sofort an, der erste Ton kommt aber erst, wenn der Test es sagt (die Sprache muss erst erzeugt werden). */
    private class SlowStartSpeaker : Speaker {
        val taken = mutableListOf<String>()
        val heard = mutableListOf<String>()
        val audio = CompletableDeferred<Unit>()
        override suspend fun speak(sentences: ReceiveChannel<String>, release: Deferred<Boolean>?, onFirstAudio: () -> Unit) {
            if (release != null && !release.await()) return
            var first = true
            for (s in sentences) {
                taken += s
                audio.await()
                if (first) { onFirstAudio(); first = false }
                heard += s
            }
        }
        override fun stop() {}
    }

    @Test fun `vor dem ersten Ton gestoppte Antwort gilt nicht als gesagt`() = runTest {
        val speaker = SlowStartSpeaker()
        val claude = FakeClaude(reply = { listOf("SAY kurz\n", first) })
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, MutableStateFlow(UiState()), backgroundScope,
            StandardTestDispatcher(testScheduler), Pipeline.Config(), FakeEnv(),
        ) { testScheduler.currentTime }.also { it.start() }

        pipeline.onCommitted("Wie hoch ist die Zugspitze?", null, null) // allein: keine Pause, sofort freigegeben
        runCurrent()
        assertEquals(listOf(first), speaker.taken)
        assertTrue("noch ist nichts zu hören", speaker.heard.isEmpty())

        advanceTimeBy(100)
        pipeline.stopSpeaking() // Knopf, Kopfhörer-Taste oder "Alfred, stopp", bevor der erste Ton kam
        runCurrent()
        assertTrue(speaker.heard.isEmpty())

        advanceTimeBy(5_000)
        pipeline.onCommitted("Alfred, was hast du eben gesagt?", null, null)
        runCurrent()
        val seen = claude.respondCalls.last()
        assertFalse("dem Modell wird etwas als gesagt vorgelegt, das niemand gehört hat:\n$seen", seen.contains("ALFRED:"))
        assertTrue(seen, seen.contains("Du hast zuletzt gesprochen: in den letzten Minuten nicht"))
    }

    @Test fun `nach dem ersten Ton gestoppte Antwort steht im Verlauf`() = runTest {
        val speaker = SlowStartSpeaker()
        val claude = FakeClaude(reply = { listOf("SAY kurz\n", first) })
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, MutableStateFlow(UiState()), backgroundScope,
            StandardTestDispatcher(testScheduler), Pipeline.Config(), FakeEnv(),
        ) { testScheduler.currentTime }.also { it.start() }

        pipeline.onCommitted("Wie hoch ist die Zugspitze?", null, null)
        runCurrent()
        speaker.audio.complete(Unit) // der erste Ton ist zu hören
        runCurrent()
        assertEquals(listOf(first), speaker.heard)

        advanceTimeBy(5_000)
        pipeline.onCommitted("Alfred, was hast du eben gesagt?", null, null)
        runCurrent()
        assertTrue(claude.respondCalls.last(), claude.respondCalls.last().contains("ALFRED: $first"))
    }

    // --- Die Sprachausgabe arbeitet in der Pause, zu hören ist erst danach etwas -------------------

    @Test fun `Saetze gehen sofort an die Sprachausgabe, zu hoeren sind sie erst nach der Pause`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say(friend)
        runCurrent()
        advanceTimeBy(5_000)
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()

        // Beide Sätze liegen schon bei der Sprachausgabe, die Schranke ist noch zu.
        assertEquals(listOf(first, second), rig.speaker.received.toList())
        assertTrue(rig.speaker.spoken.isEmpty())
        // Solange nichts zu hören ist, gilt auch nichts als gesagt.
        assertFalse(rig.state.value.speaking)
        assertNull(rig.state.value.question)
        assertEquals("", rig.state.value.answer)

        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(listOf(first, second), rig.speaker.spoken.toList())
        assertEquals("Wie hoch ist die eigentlich?", rig.state.value.question)
        assertEquals("$first $second", rig.state.value.answer)
        assertEquals("antwortet (kurz)", rig.state.value.lastDecision)
    }

    @Test fun `bleibt die Schranke zu, gilt nichts als gesagt`() = runTest {
        val request = "merk dir bitte das Kennzeichen M AB 1234, ja?"
        // Ohne Anrede ist die Bitte für den Vorfilter keine klare Frage ("merk dir ..." kann auch dem Freund
        // gelten), sondern eine Aussage, über die erst die Vorprüfung urteilt. Hier lässt sie die Bitte durch.
        assertEquals(Heuristics.Heat.MAYBE, Heuristics.heat(request, "Alfred"))
        val rig = Rig(this, FakeClaude(), profile)
        rig.claude.quickReply = { _, user -> if (user.contains(">> [jetzt] ICH: merk dir")) "JA" else "NEIN" }
        rig.claude.reply = { user ->
            if (user.contains(">> [jetzt] ICH: merk dir")) listOf("SAY kurz\nNotiert.\nMEMO: Kennzeichen M-AB 1234\nMUTE: 5\n")
            else listOf("SKIP sozial")
        }
        rig.near()
        rig.say(friend)
        runCurrent()
        advanceTimeBy(5_000)

        // Der Träger bittet ohne Anrede; das Modell will "Notiert." sagen, sich etwas merken und schweigen.
        rig.own()
        rig.say(request)
        runCurrent()
        assertEquals("die Sprachausgabe hat den Satz schon", listOf("Notiert."), rig.speaker.received.toList())

        // In der Pause redet der Freund weiter: Die Schranke bleibt zu.
        advanceTimeBy(400)
        rig.near()
        rig.say("mit der Seilbahn wieder runter") // zu kurz für eine beiläufige Aussage: Sie wird selbst nicht geprüft
        advanceTimeBy(3_000)
        runCurrent()

        assertTrue("zu hören war nichts", rig.speaker.spoken.isEmpty())
        assertEquals("übersprungen: mensch antwortet", rig.state.value.lastDecision)
        assertFalse(rig.state.value.speaking)
        assertNull("die Frage steht nicht als beantwortet in der Anzeige", rig.state.value.question)
        assertEquals("", rig.state.value.answer)
        assertNull(rig.state.value.latencyMs)
        // Was nie zu hören war, hat auch nichts bewirkt: nichts gemerkt, nicht stumm.
        assertTrue("gemerkt: ${rig.env.notes}", rig.env.notes.isEmpty())
        assertEquals(0L, rig.state.value.mutedUntilMs)

        // Und für das Modell hat es die Antwort nie gegeben.
        rig.own()
        rig.say("Alfred, was habe ich eben gesagt?")
        runCurrent()
        val seen = rig.claude.respondCalls.last()
        assertFalse(seen, seen.contains("ALFRED:"))
        assertTrue(seen, seen.contains("Du hast zuletzt gesprochen: in den letzten Minuten nicht"))
        assertFalse(seen, seen.contains("<heute_gemerkt>"))
    }

    @Test fun `nach einer zurueckgezogenen Antwort gilt das Naechste nicht als Nachfrage`() = runTest {
        // Eine Nachfrage ohne Frageform: Für sich genommen sieht der Vorfilter darin keinen Anlass (in
        // HeuristicsTableTest steht der Satz bei den blinden Flecken). Ans Modell geht er nur als Nachfrage auf
        // eine eigene Antwort. "und seit wann" taugt dafür nicht mehr: Das Fragewort bringt den Satz auch ohne
        // Antwort davor in die Vorprüfung.
        val followUp = "und in Fahrenheit"
        assertEquals(Heuristics.Heat.COLD, Heuristics.heat(followUp, "Alfred"))
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say(friend)
        runCurrent()
        advanceTimeBy(5_000)
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()
        advanceTimeBy(300)
        rig.say("das sind ungefähr dreitausend Meter") // ein Mensch antwortet: Die Antwort wird zurückgezogen
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(rig.speaker.spoken.isEmpty())
        val before = rig.claude.respondCalls.size + rig.claude.quickCalls.size

        // Der Satz wäre nur als Nachfrage auf eine eigene Antwort ein Anlass. Die gab es nicht.
        rig.say(followUp)
        runCurrent()
        assertEquals(before, rig.claude.respondCalls.size + rig.claude.quickCalls.size)

        // Gegenprobe: Nach einer Antwort, die zu hören war, geht derselbe Satz als Nachfrage ans Modell.
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(listOf(first, second), rig.speaker.spoken.toList())
        val answered = rig.claude.respondCalls.size
        advanceTimeBy(5_000)
        rig.say(followUp)
        runCurrent()
        assertEquals(answered + 1, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls.last().contains(">> [jetzt] ?: $followUp"))
    }

    @Test fun `zu spaete Antwort wird nicht mehr gesprochen und nennt den wahren Grund`() = runTest {
        val rig = Rig(this, FakeClaude())
        // Ein langsames Netz: Bis das Modell antwortet, sind fünf Sekunden vergangen.
        rig.claude.reply = { rig.lagMs += 5_000; zugspitze }
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertTrue(rig.speaker.spoken.isEmpty())
        assertEquals("übersprungen: zu spät", rig.state.value.lastDecision)

        // Mit Anrede gilt die Frist nicht: Er hat ausdrücklich gefragt und wartet auf die Antwort.
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(2, rig.speaker.spoken.size)
    }
}
