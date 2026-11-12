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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Eine zweite Äußerung trifft ein, während die Antwort auf die erste noch nicht zu hören ist.
 * Wer wen verdrängen darf, steht in Pipeline.claimOf. Was aus einer Äußerung wird, die nicht verdrängen
 * darf, prüft ParkedTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupersedeTest {

    // --- Mit echten Threads: Der erste Aufruf ist wirklich noch unterwegs --------------------------

    /** Der Zugspitze-Aufruf hängt wie ein echter Netz-Aufruf, bis der Test ihn freigibt; alles andere antwortet sofort. */
    private class SlowClaude(holdWatzmann: Boolean = false) : ClaudeApi {
        val calls = CopyOnWriteArrayList<String>()
        val zugspitzeInFlight = CountDownLatch(1)
        val releaseZugspitze = CountDownLatch(1)
        val releaseWatzmann = CountDownLatch(if (holdWatzmann) 1 else 0)
        val watzmannAnswered = CountDownLatch(1)

        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
            val trigger = user.lineSequence().first { it.startsWith(">> [jetzt]") }
            calls += trigger
            if (trigger.contains("Zugspitze")) {
                zugspitzeInFlight.countDown()
                releaseZugspitze.await() // ein abgebrochener Aufruf beendet das Warten mit einer Ausnahme
                onText("SAY kurz\n")
                onText("Die Zugspitze ist zweitausendneunhundertzweiundsechzig Meter hoch.")
            } else {
                releaseWatzmann.await()
                onText("SAY kurz\n")
                onText("Der Watzmann ist zweitausendsiebenhundertdreizehn Meter hoch.")
                watzmannAnswered.countDown()
            }
        }

        override fun quick(system: String, user: String, maxTokens: Long): String = "NEIN"
    }

    @Test fun `neuere Aeusserung verdraengt eine Antwort, die noch auf das Modell wartet`() {
        val claude = SlowClaude()
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val rig = ThreadRig(claude, speaker = RecordingSpeaker(clock, 4_000), clock = clock)
        try {
            // t = 0: Frage A, ihr Aufruf ist unterwegs.
            rig.wearerSays("Wie hoch ist die Zugspitze?")
            assertTrue("Aufruf A läuft", claude.zugspitzeInFlight.await(5, TimeUnit.SECONDS))

            // t = 800 ms: Der Träger korrigiert sich, bevor etwas gesprochen wurde.
            rig.clock.set(800)
            rig.wearerSays("Nein, ich meine den Watzmann, wie hoch ist der?")
            assertEquals(
                listOf("ICH: Wie hoch ist die Zugspitze?", "ICH: Nein, ich meine den Watzmann, wie hoch ist der?"),
                rig.state.value.transcript,
            )

            // t = 1200 ms: Das Modell hätte jetzt Frage A beantwortet.
            rig.clock.set(1_200)
            claude.releaseZugspitze.countDown()

            claude.watzmannAnswered.await(5, TimeUnit.SECONDS)
            waitFor(3_000) {
                rig.speaker.spoken.any { it.contains("Watzmann") } || rig.state.value.lastDecision?.startsWith("übersprungen") == true
            }
            Thread.sleep(100) // hätte die alte Antwort noch kommen sollen, wäre sie jetzt da

            assertEquals(
                "Aufrufe=${claude.calls}, zuletzt=${rig.state.value.lastDecision}, Uhr=${rig.clock.get()}",
                listOf("Der Watzmann ist zweitausendsiebenhundertdreizehn Meter hoch."),
                rig.speaker.spoken.toList(),
            )
        } finally {
            rig.close()
        }
    }

    @Test fun `Stopp verwirft auch die Frage, die hinter dem laufenden Aufruf wartet`() {
        // Hier ist auch der Watzmann-Aufruf langsam: Er kann nicht antworten, bevor das Stopp da ist.
        val claude = SlowClaude(holdWatzmann = true)
        val rig = ThreadRig(claude)
        try {
            rig.wearerSays("Wie hoch ist die Zugspitze?")
            assertTrue("Aufruf A läuft", claude.zugspitzeInFlight.await(5, TimeUnit.SECONDS))

            rig.clock.set(600)
            rig.wearerSays("Und wie hoch ist der Watzmann?")
            rig.clock.set(1_000)
            rig.wearerSays("Alfred, stopp")
            rig.clock.set(1_100)
            claude.releaseWatzmann.countDown()
            claude.releaseZugspitze.countDown()

            claude.watzmannAnswered.await(1, TimeUnit.SECONDS)
            Thread.sleep(300)

            assertEquals(
                "Aufrufe=${claude.calls}, zuletzt=${rig.state.value.lastDecision}",
                emptyList<String>(),
                rig.speaker.spoken.toList(),
            )
        } finally {
            rig.close()
        }
    }

    // --- Andere Stimmen nehmen dem Träger die Antwort nicht weg (echte Threads) --------------------
    // Die Frage des Trägers ist beim Modell unterwegs, da trifft eine zweite Äußerung von jemand anderem ein.
    // Vor der Umstellung auf "die neuere verdrängt" wurde hier die erste Frage beantwortet. Das muss so bleiben.

    private val zugspitzeLong = "Die Zugspitze ist zweitausendneunhundertzweiundsechzig Meter hoch."

    /**
     * Ein Modell, das sich an die Regeln hält: Es beurteilt die markierte Äußerung (>>). Nur die Frage nach der
     * Zugspitze ist eine für den Assistenten; ihr Aufruf hängt, bis der Test ihn freigibt.
     */
    private inner class RuleClaude : ClaudeApi {
        val calls = CopyOnWriteArrayList<String>()
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
            val trigger = user.lineSequence().first { it.startsWith(">> [jetzt]") }
            calls += trigger
            if (trigger.contains("Zugspitze")) {
                inFlight.countDown()
                release.await()
                onText("SAY kurz\n")
                onText(zugspitzeLong)
            } else {
                onText("SKIP sozial\n")
            }
        }

        override fun quick(system: String, user: String, maxTokens: Long): String = "NEIN"
    }

    /** Ablauf: Frage des Trägers -> das Modell hängt -> zweite Äußerung [other] -> das Modell antwortet auf die erste. */
    private fun whileWaiting(question: String, expectLabel: String, other: ThreadRig.() -> Unit) {
        val claude = RuleClaude()
        val rig = ThreadRig(claude, Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)))
        try {
            rig.wearerSays(question)
            assertTrue("Aufruf für die Frage läuft", claude.inFlight.await(5, TimeUnit.SECONDS))
            rig.clock.set(500)
            rig.other()
            assertEquals(expectLabel, rig.state.value.transcript.last().substringBefore(':'))
            rig.clock.set(1_200)
            claude.release.countDown()
            waitFor(3_000) { rig.speaker.spoken.isNotEmpty() }
            rig.clock.set(2_600) // eine etwaige Höflichkeitspause ist jetzt vorbei
            waitFor(1_500) { rig.speaker.spoken.isNotEmpty() }
            assertEquals(
                "Mitschrift=${rig.state.value.transcript} Aufrufe=${claude.calls} zuletzt=${rig.state.value.lastDecision}",
                listOf(zugspitzeLong), rig.speaker.spoken.toList(),
            )
        } finally {
            rig.close()
        }
    }

    // Die drei Tests mit "?" zeigen, dass eine nicht messbare Stimme wie die Runde zählt und nicht wie der Träger.
    // Das sieht man nur an einer klaren Frage mit Inhalt (für den Vorfilter HOT): Käme sie vom Träger, dürfte sie
    // seine Anrede ablösen. Füllsel ("Was?") und Geplauder ("Wer will noch ein Bier?") stuft der Vorfilter
    // inzwischen niedriger ein: Das eine löst gar keinen Aufruf mehr aus, das andere stünde selbst als Aussage
    // des Trägers zurück. Beides bewiese hier nichts.

    private fun assertClearQuestion(text: String) {
        assertEquals("Einstufung von \"$text\"", Heuristics.Heat.HOT, Heuristics.heat(text, "Alfred"))
        assertTrue("\"$text\" hat keinen Inhalt", Heuristics.isRealContent(text))
    }

    @Test fun `leise fremde Frage ersetzt die direkte Anrede des Traegers nicht`() {
        val question = "Und was kostet das alles zusammen?"
        assertClearQuestion(question)
        // Rund -65 dBFS: Die Transkription versteht den Satz, der Stimm-Messer misst nichts. Das gibt "?".
        whileWaiting("Alfred, wie hoch ist die Zugspitze?", "?") { says(180.0, 40.0, 600, question) }
    }

    @Test fun `Frage ohne jedes Signal ersetzt die direkte Anrede des Traegers nicht`() {
        val question = "Wann fängt der Film an?"
        assertClearQuestion(question)
        whileWaiting("Alfred, wie hoch ist die Zugspitze?", "?") { pipeline.onCommitted(question, null, null) }
    }

    @Test fun `kurzer Einwurf vom Tisch ersetzt die direkte Anrede des Traegers nicht`() {
        val question = "Stimmt das?"
        assertClearQuestion(question)
        // Normale Lautstärke, aber nur 120 ms Stimme: für den Stimm-Messer zu kurz, also ebenfalls "?".
        whileWaiting("Alfred, wie hoch ist die Zugspitze?", "?") { says(200.0, 1300.0, 120, question) }
    }

    @Test fun `der Name aus dem Fernseher ersetzt die direkte Anrede des Traegers nicht`() {
        whileWaiting("Alfred, wie hoch ist die Zugspitze?", "FERN") { says(180.0, 200.0, 600, "Alfred, bringen Sie den Wagen") }
    }

    @Test fun `ein Einwurf ohne Inhalt vom Tisch nimmt der Frage des Traegers die Antwort nicht weg`() {
        // Ohne Anrede, und der Freund ist gut zu hören. "Warum?" ist der Form nach eine Frage (für den Vorfilter
        // HOT), sagt für sich aber nichts und ist kein Anliegen. "Echt?" taugt als Beispiel nicht mehr: Das stuft
        // der Vorfilter als Füllsel ein, es kommt gar nicht bis zur Frage, wer dran ist.
        val interjection = "Warum?"
        assertEquals(Heuristics.Heat.HOT, Heuristics.heat(interjection, "Alfred"))
        assertFalse("\"$interjection\" zählt als Inhalt", Heuristics.isRealContent(interjection))
        whileWaiting("Wie hoch ist die Zugspitze?", "NAH") { says(200.0, 1300.0, 600, interjection) }
    }

    // --- Die Regel, wer verdrängen darf (virtuelle Zeit) -------------------------------------------
    // Die Attrappe antwortet ohne Verzug. "Wartend" ist eine Antwort hier deshalb entweder zwischen dem
    // Eintreffen der Äußerung und dem nächsten runCurrent() oder während der Höflichkeitspause.

    private class Rig(scope: TestScope, val claude: FakeClaude, config: Pipeline.Config = Pipeline.Config()) {
        val speaker = RecordingSpeaker()
        val state = MutableStateFlow(UiState())
        val env = FakeEnv()
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, env,
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

    private val profile = Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5))
    private val zugspitze = "Zweitausendneunhundertzweiundsechzig Meter."
    private val watzmann = "Zweitausendsiebenhundertdreizehn Meter."

    /** Antwortet je nachdem, welche Äußerung dem Modell als die aktuelle (>>) vorgelegt wurde. */
    private fun mountains() = FakeClaude(reply = { user ->
        val trigger = user.lines().last { it.startsWith(">>") }
        when {
            trigger.contains("Watzmann") -> listOf("SAY kurz\n", watzmann)
            trigger.contains("Zugspitze") || trigger.contains("eigentlich") -> listOf("SAY kurz\n", zugspitze)
            else -> listOf("SKIP sozial")
        }
    })

    @Test fun `beilaeufige Aussage eines anderen ersetzt die wartende Frage nicht`() = runTest {
        val rig = Rig(this, mountains(), profile)
        rig.own()
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        rig.near()
        rig.say("ich hab gestern den ganzen Abend an dem Aufsatz gesessen")
        runCurrent()

        assertEquals("die Aussage geht nicht einmal in die Vorprüfung", 0, rig.claude.quickCalls.size)
        assertEquals(1, rig.claude.respondCalls.size)
        assertEquals(listOf(zugspitze), rig.speaker.spoken.toList())
        assertEquals(
            listOf("ICH: Alfred, wie hoch ist die Zugspitze?", "NAH: ich hab gestern den ganzen Abend an dem Aufsatz gesessen"),
            rig.state.value.transcript,
        )
    }

    @Test fun `Frage aus der Runde ersetzt keine direkte Anrede`() = runTest {
        val rig = Rig(this, mountains(), profile)
        rig.own()
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        rig.near()
        rig.say("Und wie hoch ist der Watzmann?")
        runCurrent()

        assertEquals(1, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls[0].contains(">> [jetzt] ICH: Alfred, wie hoch ist die Zugspitze?"))
        assertEquals(listOf(zugspitze), rig.speaker.spoken.toList())
    }

    @Test fun `ferne Stimme ersetzt die wartende Frage des Traegers nicht`() = runTest {
        val rig = Rig(this, mountains(), profile)
        rig.own()
        rig.say("Wie hoch ist die Zugspitze?")
        rig.far()
        rig.say("Und wie hoch ist der Watzmann?") // aus dem Fernseher
        runCurrent()

        assertEquals(listOf("ICH: Wie hoch ist die Zugspitze?", "FERN: Und wie hoch ist der Watzmann?"), rig.state.value.transcript)
        assertEquals(1, rig.claude.respondCalls.size)
        assertEquals(listOf(zugspitze), rig.speaker.spoken.toList())
    }

    @Test fun `Frage des Traegers ersetzt auch seine eigene direkte Anrede`() = runTest {
        val rig = Rig(this, mountains(), profile)
        rig.own()
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        rig.own()
        rig.say("Nein, wie hoch ist der Watzmann?")
        runCurrent()

        assertEquals(listOf(watzmann), rig.speaker.spoken.toList())
        // Die verdrängte Frage steht in der Mitschrift des neuen Aufrufs: Das Modell kennt den Zusammenhang.
        val seen = rig.claude.respondCalls.last()
        assertTrue(seen, seen.contains("ICH: Alfred, wie hoch ist die Zugspitze?"))
        assertTrue(seen, seen.contains(">> [jetzt] ICH: Nein, wie hoch ist der Watzmann?"))
    }

    @Test fun `Frage aus der Runde ersetzt eine unbenannte wartende Frage`() = runTest {
        val rig = Rig(this, mountains(), profile)
        rig.own()
        rig.say("Wie hoch ist die Zugspitze?")
        rig.near()
        rig.say("Und wie hoch ist der Watzmann?")
        runCurrent()
        advanceTimeBy(1_100) // jetzt ist er in Gesellschaft: Die Antwort wartet die Höflichkeitspause ab
        runCurrent()

        assertEquals(listOf(watzmann), rig.speaker.spoken.toList())
    }

    @Test fun `ratlose Reaktion aus der Runde nimmt der Frage des Traegers die Antwort nicht weg`() = runTest {
        // Der Freund weiß es auch nicht. Sein Satz ist für den Vorfilter eine klare Frage, als Reaktion auf die
        // Frage des Trägers aber keine Antwort: Er zieht in der Höflichkeitspause nichts zurück, also verdrängt
        // er die wartende Antwort auch vorher nicht. Derselbe Satz als eigener Wissenswunsch: siehe ParkedTest.
        val shrug = "keine Ahnung wie die heißt"
        assertEquals(Heuristics.Heat.HOT, Heuristics.heat(shrug, "Alfred"))
        assertFalse("\"$shrug\" zählt als Antwort", Heuristics.isRealContent(shrug))
        val ottawa = "Ottawa."
        val claude = FakeClaude(reply = { user ->
            if (user.lines().last { it.startsWith(">>") }.contains("Kanada")) listOf("SAY kurz\n", ottawa) else listOf("SKIP sozial")
        })
        val rig = Rig(this, claude, profile)
        rig.own()
        rig.say("Wie heißt die Hauptstadt von Kanada?")
        rig.near()
        rig.say(shrug)
        runCurrent()
        assertEquals(
            listOf("ICH: Wie heißt die Hauptstadt von Kanada?", "NAH: $shrug"),
            rig.state.value.transcript,
        )
        advanceTimeBy(1_100) // er ist in Gesellschaft: Die Antwort wartet die Höflichkeitspause ab
        runCurrent()

        assertEquals("nur die Frage des Trägers ging ans Modell", 1, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls[0].contains(">> [jetzt] ICH: Wie heißt die Hauptstadt von Kanada?"))
        assertEquals(listOf(ottawa), rig.speaker.spoken.toList())
    }

    @Test fun `beilaeufige Aussage ersetzt eine andere, deren Urteil noch aussteht`() = runTest {
        val rig = Rig(this, FakeClaude())
        rig.say("wir waren letztes Jahr im Sommer oben auf der Zugspitze")
        rig.say("das war echt anstrengend mit dem ganzen schweren Gepäck")
        runCurrent()

        // Nur die neuere geht in die Vorprüfung; die ältere steht als Zusammenhang darüber.
        assertEquals(1, rig.claude.quickCalls.size)
        val seen = rig.claude.quickCalls[0].second
        assertTrue(seen, seen.contains(">> [jetzt] ?: das war echt anstrengend"))
        assertTrue(seen, seen.contains("?: wir waren letztes Jahr im Sommer oben auf der Zugspitze"))
    }

    @Test fun `hoerbare Antwort unterbricht nur die direkte Anrede, und der neue Aufruf kennt das schon Gesagte`() = runTest {
        val playing = CompletableDeferred<Unit>() // hält die Wiedergabe an: Die Antwort ist gerade zu hören
        val claude = mountains()
        val speaker = FakeSpeaker(playing)
        val state = MutableStateFlow(UiState())
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, backgroundScope,
            StandardTestDispatcher(testScheduler), Pipeline.Config(), FakeEnv(),
        ) { testScheduler.currentTime }.also { it.start() }

        pipeline.onCommitted("Wie hoch ist die Zugspitze?", null, null)
        runCurrent()
        assertTrue(state.value.speaking)
        assertEquals(listOf(zugspitze), speaker.spoken)

        // Ohne Anrede: steht in der Mitschrift, unterbricht aber nicht.
        advanceTimeBy(300)
        pipeline.onCommitted("Und wie hoch ist der Watzmann?", null, null)
        runCurrent()
        assertEquals(1, claude.respondCalls.size)
        assertTrue(state.value.speaking)

        // Mit Anrede: Die laufende Antwort bricht ab, die neue Frage ist dran.
        advanceTimeBy(300)
        pipeline.onCommitted("Alfred, wie hoch ist der Watzmann?", null, null)
        runCurrent()
        assertTrue(speaker.stops >= 1)
        assertEquals(2, claude.respondCalls.size)
        val seen = claude.respondCalls[1]
        // Der neue Aufruf startete erst, nachdem die abgebrochene Antwort im Verlauf stand.
        assertTrue(seen, seen.contains("ALFRED: $zugspitze"))
        assertTrue(seen, seen.contains("Du hast zuletzt gesprochen: vor 0 s"))
        assertEquals(listOf(zugspitze, watzmann), speaker.spoken)

        playing.complete(Unit)
        runCurrent()
        assertFalse(state.value.speaking)
    }

    @Test fun `in der Hoeflichkeitspause verdraengte Antwort gilt nie als gesagt`() = runTest {
        val rig = Rig(this, mountains())
        rig.say("wir waren letztes Jahr im Sommer oben auf der Zugspitze") // ein Freund: er ist in Gesellschaft
        runCurrent()
        advanceTimeBy(5_000)

        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()
        // Die Antwort ist fertig und liegt schon bei der Sprachausgabe, zu hören ist noch nichts.
        assertEquals(listOf(zugspitze), rig.speaker.received.toList())
        assertTrue(rig.speaker.spoken.isEmpty())

        advanceTimeBy(400)
        rig.say("Nein, wie hoch ist der Watzmann?")
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()

        assertEquals(listOf(watzmann), rig.speaker.spoken.toList())
        assertEquals("Nein, wie hoch ist der Watzmann?", rig.state.value.question)
        assertFalse(rig.state.value.speaking)
        // Die verdrängte Antwort hat niemand gehört: Für das Modell hat es sie nie gegeben.
        val seen = rig.claude.respondCalls.last()
        assertFalse(seen, seen.contains("ALFRED:"))
        assertTrue(seen, seen.contains("Du hast zuletzt gesprochen: in den letzten Minuten nicht"))
        assertNull(rig.state.value.error)
    }
}
