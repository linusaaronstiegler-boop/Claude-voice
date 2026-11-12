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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Eine Äußerung, die die wartende Antwort nicht verdrängen darf, ist nicht verloren: Sie steht zurück und
 * kommt dran, wenn die wartende Antwort ohne Ton endet (das Modell schweigt, ein Mensch antwortet selbst).
 * Wird die wartende Antwort gesprochen, verfällt sie. Eine Ausnahme gibt es: Was der Träger hinter seiner
 * eigenen Frage hergeschoben hat ("Und China?"), wird nach der Antwort wie eine Nachfrage vorgelegt.
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

    @Test fun `Anschlussfrage vor der ersten Antwort wird nach ihr beantwortet`() {
        // So beobachtet: Der Träger fragt nach Russland und schiebt "Und China?" nach, bevor die erste Antwort zu
        // hören ist. Früher gab es eine Antwort ("Elf."), und über "Und China?" urteilte nie jemand.
        assertHeat(Heuristics.Heat.HOT, russia)
        assertHeat(Heuristics.Heat.MAYBE, china)
        val claude = SlowClaude("Russland") { t -> listOf("SAY kurz\n", if (t.contains("China")) one else eleven) }
        val rig = ThreadRig(claude, withProfile)
        try {
            rig.wearerSays(russia)
            assertTrue("der Aufruf für die erste Frage läuft", claude.inFlight.await(5, TimeUnit.SECONDS))
            rig.clock.set(500)
            rig.wearerSays(china) // steht hinter der eigenen Frage zurück
            assertEquals("über den Nachsatz urteilt noch niemand", 1, claude.calls.size + claude.gates.size)
            rig.clock.set(1_000)
            claude.release.countDown()
            waitFor(3_000) {
                if (rig.clock.get() < 3_000) rig.clock.addAndGet(100)
                rig.speaker.spoken.size >= 2
            }
            Thread.sleep(200)
            assertEquals(
                "Aufrufe=${claude.calls} Vorprüfungen=${claude.gates} Mitschrift=${rig.state.value.transcript} zuletzt=${rig.state.value.lastDecision}",
                listOf(eleven, one), rig.speaker.spoken.toList(),
            )
            assertEquals(listOf(">> [jetzt] ICH: $russia", ">> [jetzt] ICH: $china"), claude.calls.toList())
            assertEquals("eine Nachfrage braucht keine Vorprüfung", emptyList<String>(), claude.gates.toList())
        } finally {
            rig.close()
        }
    }

    // --- Virtuelle Zeit: Die Antwort wartet in der Höflichkeitspause -------------------------------

    private class Rig(
        scope: TestScope, val claude: FakeClaude, config: Pipeline.Config = Pipeline.Config(),
        /** Ein anderer Lautsprecher als der mitschreibende, etwa einer, dessen Wiedergabe der Test anhält. */
        out: Speaker? = null,
    ) {
        val speaker = RecordingSpeaker()
        val state = MutableStateFlow(UiState())
        /** Zusätzlich verstrichene Zeit, die ein Test von Hand einschiebt ("das Modell hat lange gebraucht"). */
        var lagMs = 0L
        val pipeline = Pipeline(
            Brain(claude, Profile()), out ?: speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, FakeEnv(),
        ) { scope.testScheduler.currentTime + lagMs }.also { it.start() }

        fun say(text: String) = pipeline.onCommitted(text, null, null)

        private fun voice(hz: Double, amplitude: Double, text: String) {
            pipeline.onAudio(silence(300), 9600)
            val t = tone(hz, amplitude, 600)
            pipeline.onAudio(t, t.size)
            say(text)
        }

        /** Laut und tief: der Träger am Kopfhörer-Mikro (ICH). */
        fun wearer(text: String) = voice(115.0, 8000.0, text)
        /** Mittlere Lautstärke, andere Stimmlage: jemand am Tisch (NAH). */
        fun friend(text: String) = voice(200.0, 1300.0, text)
        /** Sehr leise: Fernseher oder Nachbartisch (FERN). */
        fun far(text: String) = voice(180.0, 200.0, text)
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

    // --- Die Anschlussfrage, die zu früh kam ----------------------------------------------------------
    // Der Träger schiebt seiner Frage etwas nach, bevor die Antwort zu hören ist. Für sich genommen ist der
    // Nachsatz eine beiläufige Aussage und steht hinter der eigenen Frage zurück. Ist die Antwort verklungen,
    // wird er vorgelegt wie eine Nachfrage, die direkt nach ihr gestellt wurde.

    private val russia = "Wie viele Zeitzonen hat Russland?"
    private val china = "Und China?"
    private val eleven = "Elf."
    private val one = "Offiziell nur eine."

    /** Die markierte Zeile eines Aufrufs: die Äußerung, über die das Modell entscheidet. */
    private fun asked(user: String) = user.lines().last { it.startsWith(">>") }

    /**
     * Ein Modell, das jede Frage beantwortet ("Elf." zu Russland, sonst "Offiziell nur eine."). Jeder Aufruf
     * dauert [callMs] auf der Uhr des Tests. Die Vorprüfung sagt NEIN: Was dort landet, wird nicht beantwortet.
     */
    private fun TestScope.zones(config: Pipeline.Config = withProfile, out: Speaker? = null, callMs: Long = 800): Rig {
        val rig = Rig(this, FakeClaude(), config, out)
        rig.claude.reply = { user ->
            rig.lagMs += callMs
            listOf("SAY kurz\n", if (asked(user).contains("Russland")) eleven else one)
        }
        return rig
    }

    @Test fun `Anschlussfrage vor der ersten Antwort wird wie eine Nachfrage vorgelegt, mit der Antwort im Verlauf`() = runTest {
        assertHeat(Heuristics.Heat.HOT, russia)
        assertHeat(Heuristics.Heat.MAYBE, china)
        val rig = zones()
        rig.wearer(russia)
        rig.lagMs += 1_500 // anderthalb Sekunden später; der Aufruf für die erste Frage ist noch unterwegs
        rig.wearer(china)
        runCurrent()

        assertEquals(listOf(eleven, one), rig.speaker.spoken.toList())
        assertEquals("die Nachfrage braucht keine Vorprüfung", 0, rig.claude.quickCalls.size)
        assertEquals(2, rig.claude.respondCalls.size)
        // Was das Modell für den Nachsatz zu lesen bekommt, Wort für Wort: Die eben gesprochene Antwort steht im
        // Verlauf, der Nachsatz ist die markierte Zeile, und die Lage sagt, dass der Assistent gerade gesprochen
        // hat. Genau so sieht eine Nachfrage aus, die direkt nach der Antwort gestellt wurde.
        assertEquals(
            """
            <lage>
            Zeit: Donnerstag, 12. November 2026, 21:14
            Mikrofon: Kopfhörer-Mikrofon (seine eigene Stimme ist mit Abstand die lauteste)
            Seine Musik läuft: nein
            Du hast zuletzt gesprochen: vor 0 s
            Szene: noch keine Einschätzung
            Sprecher der letzten Äußerung: nach der Stimme er selbst
            </lage>
            <mitschrift>
            [vor 2 s] ICH: Wie viele Zeitzonen hat Russland?
            [vor 0 s] ALFRED: Elf.
            >> [jetzt] ICH: Und China?
            </mitschrift>
            """.trimIndent(),
            rig.claude.respondCalls[1],
        )
        assertEquals("antwortet (kurz)", rig.state.value.lastDecision)
        assertEquals(china, rig.state.value.question)
    }

    @Test fun `dieselbe Anschlussfrage nach der Antwort bekommt dieselbe Mitschrift`() = runTest {
        // Der Maßstab: So sieht es aus, wenn der Träger erst nach der Antwort nachfragt. Bis auf das Alter der
        // ersten Zeile liest das Modell dasselbe wie im Test davor.
        val rig = zones()
        rig.wearer(russia)
        runCurrent()
        rig.wearer(china)
        runCurrent()

        assertEquals(listOf(eleven, one), rig.speaker.spoken.toList())
        assertEquals(0, rig.claude.quickCalls.size)
        val transcript = rig.claude.respondCalls[1].substringAfter("<mitschrift>\n")
        assertEquals("[vor 0 s] ICH: $russia\n[vor 0 s] ALFRED: $eleven\n>> [jetzt] ICH: $china\n</mitschrift>", transcript)
        assertTrue(rig.claude.respondCalls[1].contains("Du hast zuletzt gesprochen: vor 0 s"))
    }

    @Test fun `ohne hoerbare Antwort gibt es keine Nachfrage - der Nachsatz geht den gewoehnlichen Weg`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP unklar") }), withProfile)
        rig.wearer(russia)
        rig.wearer(china)
        runCurrent()

        assertTrue(rig.speaker.spoken.isEmpty())
        // Nichts war zu hören, also gibt es keine Antwort, auf die sich der Nachsatz beziehen könnte. Er rückt
        // nach wie jede zurückgestellte Äußerung: als beiläufige Aussage, über die erst die Vorprüfung urteilt.
        assertEquals(1, rig.claude.quickCalls.size)
        assertTrue(rig.claude.quickCalls[0].second.contains(">> [jetzt] ICH: $china"))
        assertEquals("die Vorprüfung sagt NEIN, das starke Modell wird nicht gefragt", 1, rig.claude.respondCalls.size)
    }

    @Test fun `ohne hoerbare Antwort beginnt fuer den Nachsatz auch keine neue Frist`() = runTest {
        // Die neue Frist ("wartet erst, seit die Antwort davor verklungen ist") gibt es nur nach einer Antwort,
        // die zu hören war. Schweigt das Modell zur Frage, rückt der Nachsatz nach wie bisher, und seine Frist
        // zählt ab dem Moment, in dem er gesagt wurde.
        val india = "Und wie viele hat Indien?"
        // Ein Einwurf aus zwei Wörtern, der für sich nichts sagt: Nur so einer steht im offenen Fenster für
        // Nachfragen noch hinter einer Frage des Trägers zurück (siehe Rang 0 in Pipeline.claimOf).
        val why = "Ja wieso?"
        assertFalse("\"$why\" zählt für sich als Inhalt", Heuristics.isRealContent(why))
        val rig = Rig(this, FakeClaude(), withProfile)
        rig.claude.reply = { user ->
            when {
                asked(user).contains("Russland") -> listOf("SAY kurz\n", eleven)
                // Zu Indien schweigt das Modell, und es braucht drei Sekunden dafür.
                asked(user).contains("Indien") -> { rig.lagMs += 3_000; listOf("SKIP unklar") }
                else -> { rig.lagMs += 2_000; listOf("SAY kurz\n", one) }
            }
        }
        rig.wearer(russia)
        runCurrent()
        assertEquals(listOf(eleven), rig.speaker.spoken.toList())
        // Fünf Sekunden später, das Fenster für Nachfragen ist offen: Beide Äußerungen gelten als Nachfrage.
        // Der Einwurf steht hinter der Frage nach Indien zurück.
        rig.lagMs += 5_000
        rig.wearer(india)
        rig.lagMs += 500
        rig.wearer(why)
        runCurrent()

        assertEquals("beide gehen ohne Vorprüfung ans Modell", 3 to 0, rig.claude.respondCalls.size to rig.claude.quickCalls.size)
        assertTrue(rig.claude.respondCalls[2].contains(">> [jetzt] ICH: $why"))
        // Als die Antwort auf den Einwurf da ist, liegt er fünf Sekunden zurück: zu spät.
        assertEquals(listOf(eleven), rig.speaker.spoken.toList())
        assertEquals("übersprungen: zu spät", rig.state.value.lastDecision)
    }

    @Test fun `nach einem Stopp wird auch die Anschlussfrage nicht mehr vorgelegt`() = runTest {
        for (stop in listOf<(Rig) -> Unit>({ it.wearer("Stopp") }, { it.pipeline.stopSpeaking() })) {
            // Die Wiedergabe hält an, bis der Test sie freigibt: Die erste Antwort ist zu hören, als das Stopp kommt.
            val playing = CompletableDeferred<Unit>()
            val out = FakeSpeaker(playing)
            val rig = zones(out = out)
            rig.wearer(russia)
            rig.wearer(china)
            runCurrent()
            assertEquals(listOf(eleven), out.spoken)
            stop(rig) // er sagt es selbst, oder er drückt den Knopf
            playing.complete(Unit)
            advanceTimeBy(3_000)
            runCurrent()

            assertEquals("wer Stopp sagt, will auch die Nachfrage nicht mehr hören", listOf(eleven), out.spoken)
            assertEquals(1, rig.claude.respondCalls.size + rig.claude.quickCalls.size)
        }
    }

    @Test fun `hat die Antwort Ruhe zugesagt, wird auch der Nachsatz nicht mehr vorgelegt`() = runTest {
        // Der Nachsatz wurde angenommen, bevor stumm geschaltet war. Nach der Antwort gilt die Ruhe: Ohne Anrede
        // geht dann nichts mehr ans Modell, auch nichts, was schon wartete.
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\nSehr wohl, Sir.\nMUTE: 10") }), withProfile)
        rig.wearer("Alfred, sei bitte zehn Minuten ruhig")
        rig.wearer(remark)
        runCurrent()

        assertEquals(listOf("Sehr wohl, Sir."), rig.speaker.spoken.toList())
        assertTrue("die Ruhe gilt", rig.state.value.mutedUntilMs > 0)
        assertEquals(1, rig.claude.respondCalls.size + rig.claude.quickCalls.size)
    }

    @Test fun `nach zugesagter Ruhe rueckt auch dann nichts nach, wenn die Bestaetigung ohne Ton blieb`() = runTest {
        // Das Modell schaltet stumm, sagt aber nichts dazu ("SAY kurz" und gleich "MUTE: 10"). Weil nichts zu
        // hören war, rückt der Nachsatz auf dem gewöhnlichen Weg nach. Auch dort gilt die Ruhe.
        val rig = Rig(this, FakeClaude(quickReply = { _, _ -> "JA" }), withProfile)
        rig.claude.reply = { user ->
            if (asked(user).contains("ruhig")) listOf("SAY kurz\n", "MUTE: 10") else listOf("SAY kurz\n", "Fleißig, Sir.")
        }
        rig.wearer("Alfred, sei bitte zehn Minuten ruhig")
        rig.wearer(remark)
        runCurrent()

        assertTrue("die Ruhe gilt", rig.state.value.mutedUntilMs > 0)
        assertEquals("gesprochen trotz Ruhe", emptyList<String>(), rig.speaker.spoken.toList())
        assertEquals("nach der Stummschaltung geht nichts mehr ans Modell", 1, rig.claude.respondCalls.size + rig.claude.quickCalls.size)
    }

    // Die beiden folgenden Tests stellten Frage und Nachsatz früher in dieselbe Millisekunde und ließen die
    // Frage ohne Anrede. Das ging nur auf, weil "hat seither ein Mensch geantwortet" bei Gleichzeitigkeit
    // nein sagt. Mit einem echten Abstand zieht der Nachsatz eines anderen die Antwort zurück (das Gespräch
    // geht weiter), und nichts wäre "gesprochen und danach verfallen". Jetzt liegt eine halbe Sekunde
    // dazwischen, und die Frage trägt den Namen: Dann wird die Antwort sicher gesprochen, und der Test zeigt
    // wirklich, was er behauptet.

    @Test fun `was der Tischnachbar dazwischen sagt, wird keine Nachfrage`() = runTest {
        // Derselbe Nachsatz, aber von jemand anderem. Er steht hinter der Frage des Trägers zurück und verfällt
        // wie bisher, sobald die Antwort gesprochen ist.
        val rig = zones()
        rig.wearer("Alfred, wie viele Zeitzonen hat Russland?")
        rig.lagMs += 500
        rig.friend(china)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf("ICH: Alfred, wie viele Zeitzonen hat Russland?", "NAH: $china"), rig.state.value.transcript.take(2))
        assertEquals(listOf(eleven), rig.speaker.spoken.toList())
        assertEquals(1, rig.claude.respondCalls.size + rig.claude.quickCalls.size)
    }

    @Test fun `hinter der Frage eines anderen hergeschoben, verfaellt auch der Nachsatz des Traegers`() = runTest {
        // Die Ausnahme gilt nur hinter der eigenen Frage. Fragt der Tischnachbar, ist "Und China?" vom Träger ein
        // Beitrag zum Gespräch der beiden und keine Nachfrage an den Assistenten.
        val rig = zones()
        rig.friend("Alfred, wie viele Zeitzonen hat Russland?")
        rig.lagMs += 500
        rig.wearer(china)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf("NAH: Alfred, wie viele Zeitzonen hat Russland?", "ICH: $china"), rig.state.value.transcript.take(2))
        assertEquals(listOf(eleven), rig.speaker.spoken.toList())
        assertEquals(1, rig.claude.respondCalls.size + rig.claude.quickCalls.size)
    }

    @Test fun `ohne Anrede zieht der Nachsatz des Tischnachbarn die Antwort zurueck - das Gespraech geht weiter`() = runTest {
        // Dieselbe Lage ohne den Namen. Was jemand anderes inhaltlich nachschiebt, heißt: Die Runde redet weiter.
        // Die Antwort auf die Frage des Trägers wird zurückgezogen, und über den Nachsatz urteilt die Vorprüfung.
        val rig = zones()
        rig.claude.quickReply = { _, _ -> "NEIN" }
        rig.wearer(russia)
        rig.lagMs += 500
        rig.friend(china)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(emptyList<String>(), rig.speaker.spoken.toList())
        assertEquals("der Nachsatz geht in die Vorprüfung", 1, rig.claude.quickCalls.size)
        assertEquals("übersprungen: kein Anlass", rig.state.value.lastDecision)
    }

    @Test fun `eine veraltete Anschlussfrage verfaellt, mit derselben Frist wie jede zurueckgestellte Aeusserung`() = runTest {
        // Die Frist zählt ab dem Nachsatz. Mit Anrede wird die erste Antwort auch nach fünf Sekunden noch
        // gesprochen; der Nachsatz ist dann aber älter als vier Sekunden und kommt nicht mehr dran.
        val named = "Alfred, wie viele Zeitzonen hat Russland?"
        val late = zones(callMs = 5_000)
        late.wearer(named)
        late.lagMs += 500
        late.wearer(china)
        runCurrent()
        assertEquals(listOf(eleven), late.speaker.spoken.toList())
        assertEquals(1, late.claude.respondCalls.size + late.claude.quickCalls.size)

        // Die Gegenprobe mit einer Antwort, die nach drei Sekunden da ist: Der Nachsatz wird vorgelegt.
        val inTime = zones(callMs = 3_000)
        inTime.wearer(named)
        inTime.lagMs += 500
        inTime.wearer(china)
        runCurrent()
        assertEquals(listOf(eleven, one), inTime.speaker.spoken.toList())
    }

    @Test fun `hoechstens eine - von mehreren Nachsaetzen wird nur der juengste vorgelegt`() = runTest {
        val canada = "Und Kanada?"
        assertHeat(Heuristics.Heat.MAYBE, canada)
        val rig = zones()
        rig.wearer(russia)
        rig.wearer(china)
        rig.wearer(canada)
        runCurrent()

        assertEquals(listOf(eleven, one), rig.speaker.spoken.toList())
        assertEquals(2, rig.claude.respondCalls.size)
        assertEquals(0, rig.claude.quickCalls.size)
        // Der ältere Nachsatz steht als gewöhnliche Zeile in der Mitschrift des jüngeren.
        val transcript = rig.claude.respondCalls[1].substringAfter("<mitschrift>\n")
        assertEquals(
            "[vor 0 s] ICH: $russia\n[vor 0 s] ICH: $china\n[vor 0 s] ALFRED: $eleven\n>> [jetzt] ICH: $canada\n</mitschrift>",
            transcript,
        )
    }

    @Test fun `ein einzelnes Wort ist auch als Nachsatz keine Nachfrage`() = runTest {
        // Dieselbe Bedingung wie für eine Nachfrage nach der Antwort: mindestens zwei Wörter.
        assertHeat(Heuristics.Heat.HOT, interjection)
        val rig = zones()
        rig.wearer(russia)
        rig.wearer(interjection) // "Warum?" sagt für sich nichts und steht zurück
        runCurrent()

        assertEquals(listOf(eleven), rig.speaker.spoken.toList())
        assertEquals(1, rig.claude.respondCalls.size + rig.claude.quickCalls.size)
    }

    // Hieß vorher "der Preis - auch ein Satz an jemand anderen kostet nach der Antwort einen Aufruf" und
    // erwartete zwei Aufrufe des starken Modells ohne Vorprüfung. Dieser Preis war zu hoch: Jeder Satz, den der
    // Träger in der Wartezeit zu jemand anderem sagt, stand dem starken Modell als Nachfrage direkt unter seiner
    // eigenen Antwort gegenüber. Ohne Vorprüfung geht jetzt nur noch, was nach einer Anschlussfrage aussieht.
    @Test fun `ein Satz an jemand anderen geht nach der Antwort erst durch die Vorpruefung`() = runTest {
        for (aside in listOf(remark, "Kannst du mir mal das Salz geben?", "Gib mir mal bitte das Salz rüber.")) {
            assertHeat(Heuristics.Heat.MAYBE, aside)
            assertFalse(aside, Heuristics.isShortFollowUp(aside))
            val rig = Rig(this, FakeClaude(), withProfile) // die Vorprüfung sagt NEIN
            rig.claude.reply = { user -> if (asked(user).contains("Russland")) listOf("SAY kurz\n", eleven) else listOf("SKIP sozial") }
            rig.wearer(russia)
            rig.wearer(aside)
            runCurrent()

            assertEquals(listOf(eleven), rig.speaker.spoken.toList())
            assertEquals("$aside: die Vorprüfung urteilt", 1, rig.claude.quickCalls.size)
            assertTrue(rig.claude.quickCalls[0].second.contains(">> [jetzt] ICH: $aside"))
            // Auch die Vorprüfung liest den Satz als das, was er ist: gesagt nach der Antwort des Assistenten.
            assertTrue(rig.claude.quickCalls[0].second.contains("ALFRED: $eleven"))
            assertEquals("$aside: das starke Modell wird nicht mehr gefragt", 1, rig.claude.respondCalls.size)
            assertEquals("übersprungen: kein Anlass", rig.state.value.lastDecision)
        }
    }

    @Test fun `sagt die Vorpruefung Ja, wird der Nachsatz nach der Antwort doch noch vorgelegt`() = runTest {
        // Der versteckte Wissenswunsch, hinter der eigenen Frage hergeschoben: keine Anschlussfrage der Form
        // nach, aber ein Anlass. Die Vorprüfung lässt ihn durch.
        assertHeat(Heuristics.Heat.MAYBE, hidden)
        val rig = Rig(this, FakeClaude(quickReply = { _, _ -> "JA" }), withProfile)
        rig.claude.reply = { user -> listOf("SAY kurz\n", if (asked(user).contains("Russland")) eleven else peak) }
        rig.wearer(russia)
        rig.wearer(hidden)
        runCurrent()

        assertEquals(listOf(eleven, peak), rig.speaker.spoken.toList())
        assertEquals(1, rig.claude.quickCalls.size)
    }

    // --- Die Anschlussfrage in Gesellschaft und in jeder Schreibweise ------------------------------------

    /**
     * Ein Freund hat vor fünf Sekunden etwas gesagt: Der Träger ist in Gesellschaft. Das Modell beantwortet die
     * Frage nach Russland und die nach China und schweigt zu allem anderen; jeder Aufruf dauert [callMs]. Die
     * Vorprüfung sagt JA, an ihr scheitert hier also nichts.
     */
    private fun TestScope.zonesInCompany(callMs: Long = 0): Rig {
        val rig = Rig(this, FakeClaude(quickReply = { _, _ -> "JA" }), withProfile)
        rig.claude.reply = { user ->
            rig.lagMs += callMs
            val q = asked(user)
            when {
                q.contains("Russland") -> listOf("SAY kurz\n", eleven)
                q.contains("China", ignoreCase = true) -> listOf("SAY kurz\n", one)
                else -> listOf("SKIP sozial")
            }
        }
        rig.friend("wir waren letztes Jahr zwei Wochen in Moskau")
        runCurrent()
        advanceTimeBy(5_000)
        return rig
    }

    @Test fun `in Gesellschaft nimmt der eigene Nachsatz der ersten Frage die Antwort nicht weg`() = runTest {
        // Ein Freund sitzt dabei, also wartet die Antwort die Höflichkeitspause ab. In dieser Pause schiebt der
        // Träger "Und China?" nach. Früher galt das als "ein Mensch antwortet": Die Antwort auf Russland wurde
        // zurückgezogen, und zu hören war höchstens die zweite. Auf seine Frage antworten aber nur die anderen.
        val rig = zonesInCompany()
        rig.wearer(russia)
        runCurrent()
        assertEquals("die Antwort liegt bereit und wartet die Höflichkeitspause ab", listOf(eleven), rig.speaker.received.toList())
        assertTrue(rig.speaker.spoken.isEmpty())
        advanceTimeBy(500)
        // Erst der Zwischenstand, dann die fertige Äußerung: Beide dürfen die wartende Antwort nicht zurückziehen.
        rig.pipeline.onAudio(silence(300), 9600)
        tone(115.0, 8000.0, 600).let { rig.pipeline.onAudio(it, it.size) }
        rig.pipeline.onPartial("Und China", null)
        rig.say(china)
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(
            "Aufrufe=${rig.claude.respondCalls.map(::asked)} Vorprüfungen=${rig.claude.quickCalls.size} zuletzt=${rig.state.value.lastDecision}",
            listOf(eleven, one), rig.speaker.spoken.toList(),
        )
        assertEquals("die Anschlussfrage braucht keine Vorprüfung", 1, rig.claude.quickCalls.size) // nur der Satz des Freundes
    }

    @Test fun `ein Satz des Traegers an die Runde nimmt ihm die eigene Antwort nicht weg`() = runTest {
        val aside = "Mal sehen, was er sagt."
        assertHeat(Heuristics.Heat.COLD, aside)
        assertTrue(Heuristics.isRealContent(aside))
        val rig = zonesInCompany(callMs = 1_200)
        rig.wearer(russia)
        rig.lagMs += 600
        rig.wearer(aside)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals("zuletzt=${rig.state.value.lastDecision}", listOf(eleven), rig.speaker.spoken.toList())
    }

    @Test fun `redet der Traeger nach seiner Frage selbst weiter, wartet der Ton auf das Ende seines Satzes`() = runTest {
        // Sein Weiterreden nimmt ihm die Antwort nicht mehr weg. Dafür darf der Assistent ihm aber nicht ins Wort
        // fallen: Solange sein Satz läuft, bleibt die Schranke zu, auch über die Höflichkeitspause hinaus.
        val more = "das wollte ich nämlich schon immer mal wissen"
        assertHeat(Heuristics.Heat.MAYBE, more)
        fun Rig.ownVoice() {
            pipeline.onAudio(silence(300), 9600)
            tone(115.0, 8000.0, 600).let { pipeline.onAudio(it, it.size) }
        }
        val rig = zonesInCompany()
        rig.wearer(russia)
        runCurrent()
        assertEquals(listOf(eleven), rig.speaker.received.toList())
        advanceTimeBy(300)
        rig.ownVoice()
        rig.pipeline.onPartial("das wollte ich nämlich schon", null) // er setzt zu einem längeren Satz an
        advanceTimeBy(1_500) // die Höflichkeitspause ist längst um, er redet noch
        runCurrent()
        assertTrue("dem Träger ins Wort gefallen: ${rig.speaker.spoken}", rig.speaker.spoken.isEmpty())

        rig.pipeline.onPartial(more, null)
        rig.say(more) // der Satz ist fertig: keine neue Frage, er steht hinter der ersten zurück
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(eleven), rig.speaker.spoken.toList())

        // Redet er länger als vier Sekunden, käme die Antwort zu spät: Dann entfällt sie wie bisher.
        val long = zonesInCompany()
        long.wearer(russia)
        runCurrent()
        advanceTimeBy(300)
        long.ownVoice()
        long.pipeline.onPartial("das wollte ich nämlich schon", null)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(long.speaker.spoken.isEmpty())
        assertEquals("übersprungen: mensch antwortet", long.state.value.lastDecision)
    }

    @Test fun `antwortet ein anderer auf die Frage des Traegers, schweigt der Assistent wie bisher`() = runTest {
        // Die Gegenprobe: Nur der Träger selbst ist ausgenommen. Der Freund beantwortet die Frage.
        val rig = zonesInCompany()
        rig.wearer(russia)
        runCurrent()
        advanceTimeBy(500)
        rig.friend("das sind elf glaube ich")
        advanceTimeBy(4_000)
        runCurrent()

        assertTrue("gesprochen=${rig.speaker.spoken}", rig.speaker.spoken.none { it == eleven })
    }

    @Test fun `Anschlussfrage ohne Fragezeichen vor der ersten Antwort wird nach ihr beantwortet`() = runTest {
        // Die Transkription liefert Fragezeichen und Großschreibung nicht verlässlich. Für den Vorfilter ist nur
        // genau "Und China?" ein möglicher Anlass; die anderen Schreibweisen verwarf er, und nach ihnen fragte
        // nie jemand. Dieselben Wörter eine Sekunde NACH der Antwort galten dagegen immer als Nachfrage.
        for (remark in listOf("Und China", "und china?", "Und China.", "und in China", "China auch?")) {
            assertHeat(Heuristics.Heat.COLD, remark)
            val rig = zones()
            rig.wearer(russia)
            rig.lagMs += 500
            rig.wearer(remark)
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()

            assertEquals("\"$remark\": Aufrufe=${rig.claude.respondCalls.size}", listOf(eleven, one), rig.speaker.spoken.toList())
            assertEquals("\"$remark\" braucht keine Vorprüfung", 0, rig.claude.quickCalls.size)
        }
    }

    @Test fun `ohne die eigene Frage davor bleibt derselbe kurze Satz ohne Aufruf`() = runTest {
        // Die großzügigere Regel gilt nur hinter der eigenen, noch wartenden Frage. Für sich genommen ist "Und du"
        // Geplauder, und hinter der Frage eines anderen ist es ein Beitrag zum Gespräch der beiden.
        val alone = zones()
        alone.wearer("Und China")
        runCurrent()
        assertEquals(0, alone.claude.respondCalls.size + alone.claude.quickCalls.size)

        val behindFriend = zones()
        behindFriend.friend("Alfred, wie viele Zeitzonen hat Russland?")
        behindFriend.lagMs += 500
        behindFriend.wearer("Und China")
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf(eleven), behindFriend.speaker.spoken.toList())
        assertEquals(1, behindFriend.claude.respondCalls.size + behindFriend.claude.quickCalls.size)
    }

    @Test fun `hat ein Freund die Anschlussfrage schon beantwortet, schweigt der Assistent dazu`() = runTest {
        val answer = "Nur eine."
        assertHeat(Heuristics.Heat.COLD, answer)
        assertTrue(Heuristics.isRealContent(answer))
        val rig = zonesInCompany(callMs = 1_000)
        val before = rig.claude.respondCalls.size
        rig.wearer("Alfred, wie viele Zeitzonen hat Russland?")
        rig.lagMs += 400
        rig.wearer(china)
        rig.lagMs += 400
        rig.friend(answer) // der Freund weiß es und sagt es, während das Modell noch an der ersten Frage arbeitet
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(
            "Aufrufe=${rig.claude.respondCalls.map(::asked)} zuletzt=${rig.state.value.lastDecision}",
            listOf(eleven), rig.speaker.spoken.toList(),
        )
        assertEquals("die beantwortete Anschlussfrage kostet keinen Aufruf mehr", before + 1, rig.claude.respondCalls.size)
    }

    @Test fun `bekannte Grenze - hinter einer laengeren ersten Antwort verfaellt die Anschlussfrage weiterhin`() = runTest {
        // Die Frist für eine zurückgestellte Äußerung (vier Sekunden) zählt ab dem Moment, in dem sie gesagt
        // wurde, und sie läuft weiter, während die erste Antwort zu hören ist. Hinter "Elf." kommt der Nachsatz
        // dran. Hinter einem gewöhnlichen Satz von dreizehn Wörtern (rund fünf Sekunden Sprache) ist er schon zu
        // alt. Gemessen hat die Hälfte der gesprochenen Antworten dreizehn Wörter oder mehr, nur jede fünfte
        // höchstens sieben. Ob die Zeit des Zuhörens mitzählen soll, ist eine Entscheidung für den Eigentümer;
        // dieser Test hält fest, wie es heute ist.
        val medium = "Russland hat elf Zeitzonen, von Kaliningrad im Westen bis Kamtschatka ganz im Osten."
        assertEquals(13, Heuristics.words(medium).size)
        for ((first, expected) in listOf(eleven to listOf(eleven, one), medium to listOf(medium))) {
            lateinit var rig: Rig
            // Ein Lautsprecher, dessen Wiedergabe auf der Uhr des Tests dauert: 400 ms je Wort.
            val out = object : Speaker {
                val spoken = ArrayList<String>()
                override suspend fun speak(
                    sentences: kotlinx.coroutines.channels.ReceiveChannel<String>, release: kotlinx.coroutines.Deferred<Boolean>?,
                    onFirstAudio: () -> Unit,
                ) {
                    if (release != null && !release.await()) return
                    var any = false
                    for (sentence in sentences) {
                        if (!any) onFirstAudio()
                        any = true
                        spoken += sentence
                        rig.lagMs += Heuristics.words(sentence).size * 400L
                    }
                }
                override fun stop() {}
            }
            rig = Rig(this, FakeClaude(), withProfile, out)
            rig.claude.reply = { user ->
                rig.lagMs += 1_000
                listOf("SAY kurz\n", if (asked(user).contains("Russland")) first else one)
            }
            rig.wearer(russia)
            rig.lagMs += 500
            rig.wearer(china)
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()

            assertEquals("erste Antwort: $first", expected, out.spoken)
        }
    }

    // --- Wer zurücksteht, behält seinen Platz gegen den Fernseher -----------------------------------------

    @Test fun `zurueckgestellte Aussage des Traegers geht nicht verloren, wenn danach der Fernseher eine Frage stellt`() = runTest {
        // Ein Freund fragt etwas (das Modell schweigt dazu), der Träger sagt seinen versteckten Wissenswunsch,
        // und dann fällt im Fernseher eine Quizfrage. Sie hat weniger Anspruch als beide, warf früher aber die
        // Aussage des Trägers aus dem einen Platz für Zurückgestellte.
        assertHeat(Heuristics.Heat.HOT, tableQuestion)
        assertHeat(Heuristics.Heat.MAYBE, hidden)
        assertHeat(Heuristics.Heat.HOT, tvQuestion)
        val rig = Rig(this, FakeClaude(quickReply = { _, _ -> "JA" }), withProfile)
        rig.claude.reply = { user -> if (asked(user).contains("Garmisch")) listOf("SAY kurz\n", peak) else listOf("SKIP sozial") }
        rig.friend(tableQuestion)
        rig.wearer(hidden)
        rig.far(tvQuestion)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(
            "Aufrufe=${rig.claude.respondCalls.map(::asked)} zuletzt=${rig.state.value.lastDecision}",
            listOf(peak), rig.speaker.spoken.toList(),
        )
    }

    @Test fun `der Fernseher stoert die Anschlussfrage des Traegers nicht`() = runTest {
        val rig = zones()
        rig.far("und nun die Nachrichten des Tages aus aller Welt")
        runCurrent()
        advanceTimeBy(3_000)
        rig.wearer(russia)
        rig.lagMs += 400
        rig.wearer(china)
        rig.lagMs += 200
        rig.far(tvQuestion)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals("zuletzt=${rig.state.value.lastDecision}", listOf(eleven, one), rig.speaker.spoken.toList())
    }

    @Test fun `Warum des Traegers nach einer Antwort wird beantwortet, auch wenn danach der Fernseher eine Frage stellt`() = runTest {
        // "Warum?" sagt für sich nichts und hat darum den niedrigsten Anspruch. Direkt nach einer Antwort ist es
        // aber die Nachfrage des Trägers zu dieser Antwort: Die Quizfrage aus dem Fernseher verdrängt sie nicht.
        val because = "Weil das Land so breit ist."
        assertHeat(Heuristics.Heat.HOT, interjection)
        assertHeat(Heuristics.Heat.HOT, tvQuestion)
        val rig = Rig(this, FakeClaude(), withProfile)
        rig.claude.reply = { user ->
            rig.lagMs += 600
            when {
                asked(user).contains("Russland") -> listOf("SAY kurz\n", eleven)
                asked(user).contains("Warum") -> listOf("SAY kurz\n", because)
                else -> listOf("SKIP medien")
            }
        }
        rig.wearer(russia)
        runCurrent()
        assertEquals(listOf(eleven), rig.speaker.spoken.toList())
        advanceTimeBy(2_000)
        rig.wearer(interjection)
        rig.lagMs += 300
        rig.far(tvQuestion)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(
            "Aufrufe=${rig.claude.respondCalls.map(::asked)} zuletzt=${rig.state.value.lastDecision}",
            listOf(eleven, because), rig.speaker.spoken.toList(),
        )
        // Die Frage aus der Runde geht dagegen weiter vor: Das Gespräch ist weitergegangen.
        val table = Rig(this, FakeClaude(), withProfile)
        table.claude.reply = { user ->
            when {
                asked(user).contains("Russland") -> listOf("SAY kurz\n", eleven)
                asked(user).contains("Warum") -> listOf("SAY kurz\n", because)
                else -> listOf("SKIP sozial")
            }
        }
        table.wearer(russia)
        runCurrent()
        advanceTimeBy(2_000)
        table.wearer(interjection)
        table.friend(tableQuestion)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf(eleven), table.speaker.spoken.toList())
        assertTrue(asked(table.claude.respondCalls.last()).contains(tableQuestion))
    }
}
