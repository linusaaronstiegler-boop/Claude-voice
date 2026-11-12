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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Eine Äußerung, die die wartende Antwort nicht verdrängen darf, ist nicht verloren: Sie steht zurück und
 * kommt dran, wenn die wartende Antwort ohne Ton endet (das Modell schweigt, ein Mensch antwortet selbst).
 * Wird die wartende Antwort gesprochen, verfällt sie.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParkedTest {

    private val hidden = "der höchste Berg bei Garmisch na der Dings ich komm gleich drauf"
    private val peak = "Die Zugspitze."

    // Die drei folgenden Sätze stehen so (bis auf Großschreibung und Fragezeichen) in der Tabelle von
    // HeuristicsTableTest, ebenso der versteckte Wissenswunsch oben.

    /** Eine Quizfrage aus dem Fernseher: für den Vorfilter eine klare Frage, für den Assistenten Hintergrund. */
    private val tvQuestion = "Wer hat eigentlich das Telefon erfunden?"

    /**
     * Eine Frage am Tisch, die den Assistenten nichts angeht. Der Form nach ist sie eine Wissensfrage; dass es
     * um das Hier und Jetzt geht, sieht der Vorfilter nicht (in der Tabelle als bekannte Abweichung vermerkt).
     */
    private val tableQuestion = "Wann fängt der Film an?"

    /** Sieht nach Frage aus, sagt für sich aber nichts: ein Einwurf ohne Inhalt. */
    private val interjection = "Warum?"

    /**
     * Die Tests hier stellen Lagen nach, die davon abhängen, wie der Vorfilter einen Satz einstuft: Nur eine
     * klare Frage (HOT) geht ohne Vorprüfung ans Modell und hat den Anspruch einer Frage, nur eine beiläufige
     * Aussage (MAYBE) geht in die Vorprüfung. Die Tests prüfen deshalb zuerst die Einstufung ihrer Sätze.
     * Ändert der Vorfilter sie, fällt das hier auf und nicht als rätselhafte Zahl weiter unten.
     */
    private fun assertHeat(expected: Heuristics.Heat, text: String) =
        assertEquals("Einstufung von \"$text\"", expected, Heuristics.heat(text, "Alfred"))

    // --- Mit echten Threads: Der erste Aufruf ist wirklich noch unterwegs --------------------------

    /** Aufrufe, deren markierte Zeile [slowWord] enthält, hängen, bis der Test sie freigibt. Die Vorprüfung sagt immer JA. */
    private class SlowClaude(private val slowWord: String, private val answer: (String) -> List<String>) : ClaudeApi {
        val calls = CopyOnWriteArrayList<String>()
        val gates = CopyOnWriteArrayList<String>()
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
            val trigger = user.lineSequence().first { it.startsWith(">> [jetzt]") }
            calls += trigger
            if (trigger.contains(slowWord)) {
                inFlight.countDown()
                release.await()
            }
            answer(trigger).forEach(onText)
        }

        override fun quick(system: String, user: String, maxTokens: Long): String {
            gates += user.lineSequence().first { it.startsWith(">> [jetzt]") }
            return "JA"
        }
    }

    private val withProfile = Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5))

    /** Nur der versteckte Wissenswunsch des Trägers ist ein Anlass; die Frage aus dem Fernseher nicht. */
    private fun onlyTheWearer(trigger: String) =
        if (trigger.contains("Garmisch")) listOf("SAY kurz\n", peak) else listOf("SKIP hintergrund\n")

    /** Lässt die von Hand gestellte Uhr weiterlaufen (100 ms je Schritt, bis t = 3 s), bis etwas gesprochen wurde. */
    private fun ThreadRig.runUntilSpoken() {
        waitFor(3_000) {
            if (clock.get() < 3_000) clock.addAndGet(100)
            speaker.spoken.isNotEmpty()
        }
        Thread.sleep(200)
    }

    @Test fun `Aussage des Traegers geht nicht hinter einer fernen Frage verloren`() {
        // Eine ferne Stimme wird dem Modell nur mit einer klaren Frage vorgelegt; ihr fernes Geplauder verwirft
        // die Pipeline gleich. Die Aussage des Trägers ist beiläufig: Über sie urteilt erst die Vorprüfung.
        assertHeat(Heuristics.Heat.HOT, tvQuestion)
        assertHeat(Heuristics.Heat.MAYBE, hidden)
        val claude = SlowClaude("Telefon", ::onlyTheWearer)
        val rig = ThreadRig(claude, withProfile)
        try {
            rig.says(180.0, 200.0, 600, tvQuestion) // Fernseher: fern
            assertTrue("der Aufruf für die Frage aus dem Fernseher läuft", claude.inFlight.await(5, TimeUnit.SECONDS))
            rig.clock.set(500)
            rig.wearerSays(hidden)
            rig.clock.set(1_000)
            claude.release.countDown() // zur Frage aus dem Fernseher sagt das Modell SKIP
            rig.runUntilSpoken()
            assertEquals(
                "Aufrufe=${claude.calls} Vorprüfungen=${claude.gates} Mitschrift=${rig.state.value.transcript} zuletzt=${rig.state.value.lastDecision}",
                listOf(peak), rig.speaker.spoken.toList(),
            )
        } finally {
            rig.close()
        }
    }

    @Test fun `ferne Frage nimmt der Aussage des Traegers die Antwort nicht weg`() {
        // Die andere Reihenfolge: Über die Aussage des Trägers wird gerade geurteilt, da fragt der Fernseher etwas.
        // Es muss eine klare Frage sein: Nur die hätte überhaupt einen Anspruch, der die Aussage verdrängen könnte.
        assertHeat(Heuristics.Heat.MAYBE, hidden)
        assertHeat(Heuristics.Heat.HOT, tvQuestion)
        val claude = SlowClaude("Garmisch", ::onlyTheWearer)
        val rig = ThreadRig(claude, withProfile)
        try {
            rig.wearerSays(hidden)
            assertTrue(claude.inFlight.await(5, TimeUnit.SECONDS))
            rig.clock.set(500)
            rig.says(180.0, 200.0, 600, tvQuestion)
            rig.clock.set(1_000)
            claude.release.countDown()
            rig.runUntilSpoken()
            assertEquals(
                "Aufrufe=${claude.calls} Vorprüfungen=${claude.gates} Mitschrift=${rig.state.value.transcript} zuletzt=${rig.state.value.lastDecision}",
                listOf(peak), rig.speaker.spoken.toList(),
            )
        } finally {
            rig.close()
        }
    }

    @Test fun `ferne Frage nimmt dem ratlos gesagten Wissenswunsch des Traegers die Antwort nicht weg`() {
        // "Keine Ahnung, wie das heißt" ist für den Vorfilter eine klare Frage. Als Reaktion auf eine Frage wäre
        // der Satz keine Antwort (Heuristics.isRealContent); für sich genommen sagt er aber etwas und ist kein
        // Einwurf wie "Warum?". Wartet seine Antwort, behält er den Rang einer Frage: Die Quizfrage aus dem
        // Fernseher steht zurück. Verdrängte sie ihn, wäre er verloren, denn nachgerückt wird nur von hinten.
        val wish = "keine Ahnung wie das heißt"
        val answer = "Das ist ein Sextant."
        assertHeat(Heuristics.Heat.HOT, wish)
        assertFalse("als Reaktion wäre \"$wish\" eine Antwort", Heuristics.isRealContent(wish))
        assertTrue("\"$wish\" sagt für sich nichts", Heuristics.saysSomething(wish))
        assertHeat(Heuristics.Heat.HOT, tvQuestion)
        val claude = SlowClaude("Ahnung") { t -> if (t.contains("Ahnung")) listOf("SAY kurz\n", answer) else listOf("SKIP hintergrund\n") }
        val rig = ThreadRig(claude, withProfile)
        try {
            rig.wearerSays(wish)
            assertTrue("der Aufruf für den Wissenswunsch läuft", claude.inFlight.await(5, TimeUnit.SECONDS))
            rig.clock.set(500)
            rig.says(180.0, 200.0, 600, tvQuestion)
            rig.clock.set(1_000)
            claude.release.countDown()
            rig.runUntilSpoken()
            assertEquals(
                "Aufrufe=${claude.calls} Mitschrift=${rig.state.value.transcript} zuletzt=${rig.state.value.lastDecision}",
                listOf(answer), rig.speaker.spoken.toList(),
            )
            assertEquals(listOf("ICH: $wish", "FERN: $tvQuestion"), rig.state.value.transcript)
        } finally {
            rig.close()
        }
    }

    @Test fun `Aussage des Traegers kommt dran, wenn die Frage aus der Runde ohne Ton endet`() {
        // Wie oben, nur fragt ein Freund am Tisch (NAH) etwas, das den Assistenten nichts angeht. Als klare Frage
        // aus der Runde hat sie mehr Anspruch als die beiläufige Aussage des Trägers: Die Aussage steht zurück.
        assertHeat(Heuristics.Heat.HOT, tableQuestion)
        assertHeat(Heuristics.Heat.MAYBE, hidden)
        val claude = SlowClaude("Film") { t -> if (t.contains("Garmisch")) listOf("SAY kurz\n", peak) else listOf("SKIP sozial\n") }
        val rig = ThreadRig(claude, withProfile)
        try {
            rig.says(200.0, 1300.0, 600, tableQuestion)
            assertTrue("der Aufruf für die Frage aus der Runde läuft", claude.inFlight.await(5, TimeUnit.SECONDS))
            assertEquals("die Frage aus der Runde geht ohne Vorprüfung ans Modell", 0, claude.gates.size)
            rig.clock.set(500)
            rig.wearerSays(hidden)
            assertEquals("die Aussage wartet: noch keine Vorprüfung", 0, claude.gates.size)
            rig.clock.set(1_000)
            claude.release.countDown()
            rig.runUntilSpoken()
            assertEquals(
                "Aufrufe=${claude.calls} Vorprüfungen=${claude.gates} zuletzt=${rig.state.value.lastDecision}",
                listOf(peak), rig.speaker.spoken.toList(),
            )
        } finally {
            rig.close()
        }
    }

    // --- Virtuelle Zeit: Die Antwort wartet in der Höflichkeitspause -------------------------------

    private class Rig(scope: TestScope, val claude: FakeClaude, config: Pipeline.Config = Pipeline.Config()) {
        val speaker = RecordingSpeaker()
        val state = MutableStateFlow(UiState())
        /** Zusätzlich verstrichene Zeit, die ein Test von Hand einschiebt ("das Modell hat lange gebraucht"). */
        var lagMs = 0L
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, FakeEnv(),
        ) { scope.testScheduler.currentTime + lagMs }.also { it.start() }

        fun say(text: String) = pipeline.onCommitted(text, null, null)
    }

    private val friend = "wir waren letztes Jahr oben auf der Zugspitze"
    private val remark = "ich hab gestern den ganzen Abend an dem Aufsatz gesessen"
    private val zugspitze = "Zweitausendneunhundertzweiundsechzig Meter."

    /** Die Frage nach der Höhe wird beantwortet, alles andere ist kein Anlass. Die Vorprüfung lässt alles durch. */
    private fun heights() = FakeClaude(
        reply = { user ->
            if (user.lines().last { it.startsWith(">>") }.contains("Wie hoch")) listOf("SAY kurz\n", zugspitze) else listOf("SKIP sozial")
        },
        quickReply = { _, _ -> "JA" },
    )

    /** Ein Freund ist dabei, die Frage ist gestellt, die Antwort wartet in der Höflichkeitspause. */
    private fun TestScope.heldAnswer(claude: FakeClaude): Rig {
        val rig = Rig(this, claude)
        rig.say(friend)
        runCurrent()
        advanceTimeBy(5_000)
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()
        assertEquals("die Antwort liegt bereit", listOf(zugspitze), rig.speaker.received.toList())
        assertTrue("zu hören ist noch nichts", rig.speaker.spoken.isEmpty())
        return rig
    }

    @Test fun `zurueckgezogene Antwort macht Platz fuer die zurueckgestellte Aussage`() = runTest {
        val rig = heldAnswer(heights())
        val gates = rig.claude.quickCalls.size
        // Was stand in der Anzeige, als die Vorprüfung der Aussage begann?
        val decisionAtGate = mutableListOf<String?>()
        rig.claude.quickReply = { _, _ -> decisionAtGate += rig.state.value.lastDecision; "JA" }
        advanceTimeBy(200)
        // Jemand redet inhaltlich weiter: Die Antwort wird zurückgezogen. Die Aussage selbst ist aber auch einen
        // Blick wert (beiläufig, also erst die Vorprüfung). Sie durfte die Frage nicht verdrängen und stand zurück.
        rig.say(remark)
        advanceTimeBy(3_000)
        runCurrent()

        assertTrue("gesprochen=${rig.speaker.spoken}", rig.speaker.spoken.isEmpty())
        assertEquals("die Aussage geht in die Vorprüfung", gates + 1, rig.claude.quickCalls.size)
        assertTrue(rig.claude.quickCalls.last().second.contains(">> [jetzt] ?: $remark"))
        assertEquals(
            "sie kam erst dran, als die Antwort auf die Frage zurückgezogen war",
            listOf<String?>("übersprungen: mensch antwortet"), decisionAtGate,
        )
        assertEquals("übersprungen: sozial", rig.state.value.lastDecision)
    }

    @Test fun `nach einem Stopp kommt auch die zurueckgestellte Aussage nicht mehr dran`() = runTest {
        val rig = heldAnswer(heights())
        val calls = rig.claude.quickCalls.size + rig.claude.respondCalls.size
        advanceTimeBy(200)
        rig.say(remark)
        rig.pipeline.stopSpeaking() // Knopf in der App oder Kopfhörer-Taste
        advanceTimeBy(3_000)
        runCurrent()

        assertTrue(rig.speaker.spoken.isEmpty())
        assertEquals("nach dem Stopp geht nichts mehr ans Modell", calls, rig.claude.quickCalls.size + rig.claude.respondCalls.size)
    }

    @Test fun `eine neuere angenommene Aeusserung loest auch die zurueckgestellte ab`() = runTest {
        val rig = heldAnswer(heights())
        val gates = rig.claude.quickCalls.size
        val answers = rig.claude.respondCalls.size
        advanceTimeBy(200)
        rig.say(remark)                              // steht zurück
        rig.say("Alfred, lass gut sein, ja?")        // direkte Anrede: verdrängt die wartende Antwort
        advanceTimeBy(3_000)
        runCurrent()

        // Es zählt die neueste Äußerung. Die ältere Aussage steht in ihrer Mitschrift und wird nicht eigens geprüft.
        assertEquals(gates, rig.claude.quickCalls.size)
        assertEquals(answers + 1, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls.last().contains("?: $remark"))
        assertTrue(rig.speaker.spoken.isEmpty())
    }

    @Test fun `ein Einwurf nimmt auch einer beilaeufigen Aussage die Vorpruefung nicht weg`() = runTest {
        // Der Einwurf muss für den Vorfilter eine Frage sein, sonst käme er gar nicht bis zur Frage, wer dran ist.
        // "Echt?", "Was?" und "Oder?" stuft der Vorfilter als Füllsel ein (COLD): Ohne Anrede lösen sie keinen
        // Aufruf aus. Übrig bleibt die bloße Nachfrage wie "Warum?": der Form nach eine Frage, für sich aber
        // ohne Inhalt.
        assertHeat(Heuristics.Heat.MAYBE, remark)
        assertHeat(Heuristics.Heat.HOT, interjection)
        assertFalse("\"$interjection\" zählt als Inhalt", Heuristics.isRealContent(interjection))
        val rig = Rig(this, heights())
        rig.say(remark)       // über die Aussage urteilt gleich die Vorprüfung
        rig.say(interjection) // sieht nach Frage aus, sagt aber nichts: steht zurück
        runCurrent()

        assertEquals(1, rig.claude.quickCalls.size)
        assertTrue(rig.claude.quickCalls[0].second.contains(">> [jetzt] ?: $remark"))
        // Zur Aussage schweigt das Modell. Danach kommt der Einwurf dran, wie jede andere Äußerung auch.
        assertEquals(2, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls.last().contains(">> [jetzt] ?: $interjection"))
        assertTrue(rig.speaker.spoken.isEmpty())
    }

    @Test fun `veraltete zurueckgestellte Aussage wird nicht mehr vorgelegt`() = runTest {
        // Die Frage aus der Runde muss eine klare Frage sein: Nur hinter ihr steht die Aussage zurück.
        assertHeat(Heuristics.Heat.HOT, tableQuestion)
        assertHeat(Heuristics.Heat.MAYBE, remark)
        val rig = Rig(this, FakeClaude())
        // Die Frage aus der Runde geht ans Modell; bis es schweigt, sind fünf Sekunden vergangen (langsames Netz).
        rig.claude.reply = { rig.lagMs += 5_000; listOf("SKIP sozial") }
        rig.say(tableQuestion)
        rig.say(remark)
        runCurrent()

        assertEquals(1, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls[0].contains(">> [jetzt] ?: $tableQuestion"))
        assertEquals("wer nach vier Sekunden erst loslegt, stört", 0, rig.claude.quickCalls.size)
    }
}
