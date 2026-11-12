package app.claudevoice.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

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

    // --- Stopp, während der gestoppte Auftrag noch aufräumt (echte Threads) ------------------------
    // Der echte Lautsprecher braucht nach einem Abbruch einen Augenblick (Ausgabe anhalten, Audiofokus abgeben).
    // In dieser Spanne ist der alte Auftrag noch nicht fertig, und genau dort lagen zwei Lücken.

    /** Antwortet ohne Verzug: auf die Zugspitze mit drei Sätzen, zur Frage nach dem Film schweigt es, sonst ein Satz. */
    private class QuickClaude : ClaudeApi {
        val calls = CopyOnWriteArrayList<String>()
        val returned = CopyOnWriteArrayList<String>()

        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
            val trigger = user.lineSequence().first { it.startsWith(">> [jetzt]") }
            calls += trigger
            when {
                trigger.contains("Film") -> onText("SKIP sozial\n")
                trigger.contains("Zugspitze") -> { onText("SAY kurz\n"); onText("Antwort A. Und ein zweiter Satz. Und ein dritter Satz.") }
                else -> { onText("SAY kurz\n"); onText("Antwort B.") }
            }
            returned += trigger
        }

        override fun quick(system: String, user: String, maxTokens: Long): String = "JA"
    }

    @Test fun `was gleich nach einem Stopp gesagt wird, geht nicht verloren`() {
        // Der Lautsprecher räumt nach dem Stopp noch 40 ms auf. In dieser Zeit fragt ein Freund etwas. Früher
        // stellte sich die Frage hinter dem gestoppten Auftrag an, und der holt niemanden mehr nach: Über sie
        // urteilte nie jemand. Jetzt ist der Platz mit dem Stopp frei.
        val claude = QuickClaude()
        val out = TimedSpeaker(perSentenceMs = 300, windDownMs = 40)
        val rig = ThreadRig(claude, profile, out = out)
        try {
            rig.wearerSays("Alfred, wie hoch ist die Zugspitze?")
            assertTrue(waitFor(3_000) { out.spoken.isNotEmpty() })
            rig.pipeline.stopSpeaking() // Knopf in der App oder Kopfhörer-Taste
            rig.clock.set(1_000)
            rig.says(200.0, 1300.0, 600, "Wann fängt der Film an?")
            assertTrue(
                "über die Frage nach dem Stopp urteilt niemand: Aufrufe=${claude.calls} zuletzt=${rig.state.value.lastDecision}",
                waitFor(1_500) { claude.calls.size == 2 },
            )
            assertTrue(waitFor(1_500) { rig.state.value.lastDecision == "übersprungen: sozial" })
            assertEquals("nach dem Stopp ist nichts mehr zu hören", listOf("Antwort A."), out.spoken.toList())
        } finally {
            rig.close()
        }
    }

    /**
     * Ein Lautsprecher, dessen Wiedergabe so lange dauert, bis sie abgebrochen wird, und der danach erst
     * aufgeräumt hat, wenn der Test es sagt ([windDown]).
     */
    private class HeldSpeaker : Speaker {
        val spoken = ArrayList<String>()
        val windDown = CompletableDeferred<Unit>()
        private val playing = CompletableDeferred<Unit>()

        override suspend fun speak(sentences: ReceiveChannel<String>, release: Deferred<Boolean>?, onFirstAudio: () -> Unit) {
            if (release != null && !release.await()) return
            var first = true
            try {
                for (s in sentences) {
                    if (first) { onFirstAudio(); first = false }
                    spoken += s
                    playing.await()
                }
            } finally {
                if (!first) withContext(NonCancellable) { windDown.await() }
            }
        }

        override fun stop() {}
    }

    @Test fun `zweites Stopp, waehrend der gestoppte Auftrag noch aufraeumt, laesst die Frage dazwischen gar nicht erst anlaufen`() {
        // Der gestoppte Auftrag räumt noch auf. In dieser Zeit kommt eine neue Frage, deren Antwort sofort
        // bereitliegt, und gleich darauf ein zweites Stopp. Der neue Auftrag darf gar nicht erst anlaufen (LAZY in
        // Pipeline.start). Liefe er los und würde gleich wieder abgebrochen, bliebe ein Augenblick, in dem er schon
        // spricht.
        //
        // Mit echten Threads zeigt sich das nur zufällig (der Abbruch kommt dem Anlaufen fast immer zuvor). Darum
        // läuft hier alles auf dem Thread des Tests, und jede Coroutine läuft in dem Moment los, in dem sie
        // gestartet oder fortgesetzt wird. Das ist der ungünstigste Fall, und er ist jedes Mal derselbe.
        val direct = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        }
        val claude = QuickClaude()
        val out = HeldSpeaker()
        val state = MutableStateFlow(UiState())
        val scope = CoroutineScope(SupervisorJob() + direct)
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val pipeline = Pipeline(Brain(claude, Profile()), out, state, scope, direct, Pipeline.Config(), FakeEnv()) { clock.get() }
            .also { it.start() }
        try {
            pipeline.onCommitted("Alfred, wie hoch ist die Zugspitze?", null, null)
            assertEquals(listOf("Antwort A."), out.spoken)
            assertTrue(state.value.speaking)

            pipeline.stopSpeaking() // der Lautsprecher räumt ab jetzt auf
            clock.set(1_000)
            pipeline.onCommitted("Alfred, wie hoch ist der Watzmann?", null, null)
            assertEquals("die Antwort B liegt bereit", 2, claude.returned.size)
            pipeline.stopSpeaking() // das zweite Stopp gilt auch für die Frage dazwischen
            out.windDown.complete(Unit) // jetzt ist der alte Auftrag fertig, und die Schleife kommt zum neuen

            assertEquals("Aufrufe=${claude.calls}", listOf("Antwort A."), out.spoken)
            assertFalse(state.value.speaking)
            assertEquals("der neue Auftrag lief nicht an, also auch kein neuer Aufruf", 2, claude.calls.size)

            // Danach geht es normal weiter: Die nächste Frage wird beantwortet.
            clock.set(2_000)
            pipeline.onCommitted("Alfred, und wie hoch ist der Watzmann wirklich?", null, null)
            assertEquals(listOf("Antwort A.", "Antwort B."), out.spoken)
        } finally {
            pipeline.stop()
            scope.cancel()
        }
    }

    /**
     * Ein Dispatcher, der nichts von selbst ausführt: Was ansteht, läuft erst, wenn der Test es sagt, und zwar
     * das zuletzt Eingereihte zuerst. Damit lässt sich eine Reihenfolge nachstellen, die mit echten Threads nur
     * unter Last vorkommt: Der abgelöste Auftrag kommt noch einmal dran, bevor die Schleife ihn abbricht.
     */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = java.util.concurrent.ConcurrentLinkedDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runNewestFirst() {
            while (true) (queue.pollLast() ?: return).run()
        }

        fun runUntil(ms: Long, condition: () -> Boolean): Boolean {
            val end = System.nanoTime() + ms * 1_000_000
            while (System.nanoTime() < end) {
                runNewestFirst()
                if (condition()) return true
                Thread.sleep(2)
            }
            return condition()
        }
    }

    @Test fun `trifft die alte Antwort ein, bevor der abgeloeste Auftrag abgebrochen ist, bleibt sie ungesprochen`() {
        // Der Träger verbessert sich, die neue Äußerung ist angenommen. Abgebrochen wird der alte Auftrag erst von
        // der Schleife, einen Augenblick später. Trifft seine Antwort genau in diesem Augenblick ein, wurde früher
        // die alte Frage beantwortet (so gesehen unter Last: zu hören war die Zugspitze, der Watzmann kam "zu spät").
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val claude = object : ClaudeApi {
            override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
                if (user.lineSequence().first { it.startsWith(">> [jetzt]") }.contains("Zugspitze")) {
                    inFlight.countDown()
                    release.await()
                    onText("SAY kurz\n")
                    onText(zugspitzeLong)
                    delivered.countDown()
                } else {
                    onText("SAY kurz\n")
                    onText("Der Watzmann ist zweitausendsiebenhundertdreizehn Meter hoch.")
                }
            }

            override fun quick(system: String, user: String, maxTokens: Long): String = "NEIN"
        }
        val manual = ManualDispatcher()
        val speaker = RecordingSpeaker()
        val state = MutableStateFlow(UiState())
        val scope = CoroutineScope(SupervisorJob() + manual)
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, scope, kotlinx.coroutines.Dispatchers.IO, profile, FakeEnv(),
        ) { clock.get() }.also { it.start() }
        // Beide Äußerungen mit der Stimme des Trägers: Er ist allein, die Antwort wartet keine Höflichkeitspause ab.
        fun wearerSays(text: String) {
            pipeline.onAudio(silence(300), 9600)
            tone(115.0, 8000.0, 600).let { pipeline.onAudio(it, it.size) }
            pipeline.onCommitted(text, null, null)
        }
        try {
            manual.runNewestFirst() // die Schleife wartet auf die erste Äußerung
            wearerSays("Wie hoch ist die Zugspitze?")
            manual.runNewestFirst() // der Auftrag dafür läuft und wartet auf das Modell
            assertTrue("Aufruf A läuft", inFlight.await(5, TimeUnit.SECONDS))

            clock.set(800)
            wearerSays("Nein, wie hoch ist der Watzmann?") // angenommen; die Schleife weiß es noch nicht
            release.countDown() // genau jetzt trifft die alte Antwort ein
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            Thread.sleep(50) // der Aufruf ist zu Ende gelesen, alles liegt für den alten Auftrag bereit

            // Der alte Auftrag kommt vor der Schleife dran (zuletzt eingereiht, zuerst ausgeführt).
            manual.runUntil(3_000) { speaker.spoken.isNotEmpty() && !state.value.speaking && state.value.question?.contains("Watzmann") == true }
            assertEquals(
                "zuletzt=${state.value.lastDecision} Frage=${state.value.question}",
                listOf("Der Watzmann ist zweitausendsiebenhundertdreizehn Meter hoch."), speaker.spoken.toList(),
            )
            assertEquals("Nein, wie hoch ist der Watzmann?", state.value.question)
        } finally {
            pipeline.stop()
            scope.cancel()
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

    @Test fun `Korrektur im Wortlaut der Anleitung fuehrt zur Antwort auf die neue Frage`() = runTest {
        // So steht es in der Anleitung: Wer sich verbessert, bevor die Antwort zu hören ist ("nein, ich meine den
        // Watzmann"), bekommt die Antwort auf die neue Frage. Für den Vorfilter ist der Satz kein Anlass (COLD):
        // Früher sah ihn kein Modell, und beantwortet wurde die alte Frage. Die längere Fassung hielt er für eine
        // beiläufige Aussage: Sie stand zurück und wurde nach der alten Antwort als Nachfrage vorgelegt, zu hören
        // waren dann beide Antworten.
        val corrections = listOf(
            "Nein, ich meine den Watzmann." to Heuristics.Heat.COLD,
            "Ach nee, ich mein den Watzmann." to Heuristics.Heat.MAYBE,
            "Sorry, ich meinte natürlich den Watzmann." to null,
        )
        for (first in listOf("Wie hoch ist die Zugspitze?", "Alfred, wie hoch ist die Zugspitze?")) {
            for ((correction, heat) in corrections) {
                if (heat != null) assertEquals("Einstufung von \"$correction\"", heat, Heuristics.heat(correction, "Alfred"))
                val rig = Rig(this, mountains(), profile)
                rig.own()
                rig.say(first)
                rig.own()
                rig.say(correction)
                runCurrent()
                advanceTimeBy(4_000)
                runCurrent()

                assertEquals("$first / $correction", listOf(watzmann), rig.speaker.spoken.toList())
                // Die alte Frage steht in der Mitschrift des neuen Aufrufs: Das Modell weiß, worauf sich die Korrektur bezieht.
                val seen = rig.claude.respondCalls.last()
                assertTrue(seen, seen.contains("ICH: $first"))
                assertTrue(seen, seen.contains(">> [jetzt] ICH: $correction"))
                assertEquals("eine Korrektur braucht keine Vorprüfung", 0, rig.claude.quickCalls.size)
            }
        }
    }

    @Test fun `was nur wie eine Korrektur beginnt oder von jemand anderem kommt, loest die Frage nicht ab`() = runTest {
        // "Nein, danke." sagt er dem Kellner. Löste das die wartende Frage ab, wäre seine Antwort weg.
        for (other in listOf("Nein, danke.", "Nein, den Watzmann.", "Ich mein ja.")) {
            assertFalse(other, Heuristics.isCorrection(other))
            val rig = Rig(this, mountains(), profile)
            rig.own()
            rig.say("Wie hoch ist die Zugspitze?")
            rig.own()
            rig.say(other)
            runCurrent()
            assertEquals(other, listOf(zugspitze), rig.speaker.spoken.toList())
        }
        // Der Tischnachbar verbessert den Träger: Das ist ein Beitrag zum Gespräch, keine Korrektur der Frage.
        val rig = Rig(this, mountains(), profile)
        rig.own()
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        rig.near()
        rig.say("Nein, ich meine den Watzmann.")
        runCurrent()
        assertEquals(listOf(zugspitze), rig.speaker.spoken.toList())
        assertEquals(1, rig.claude.respondCalls.size)
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
