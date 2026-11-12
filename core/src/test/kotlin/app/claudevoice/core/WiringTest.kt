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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Die Nahtstellen zwischen den Bauteilen: Zahlen-Aussprache vor dem Lautsprecher, Längendeckel und
 * Sonderzeilen, leere Antworten, der umbenannte Assistent und die beiden Uhren.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WiringTest {

    private class Rig(
        scope: TestScope,
        val claude: FakeClaude,
        profile: Profile = Profile(),
        config: Pipeline.Config = Pipeline.Config(),
    ) {
        val speaker = FakeSpeaker()
        val state = MutableStateFlow(UiState())
        val env = FakeEnv()
        val pipeline = Pipeline(
            Brain(claude, profile), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, env,
        ) { scope.testScheduler.currentTime }.also { it.start() }

        fun say(text: String) = pipeline.onCommitted(text, null, null)

        /** Laute, tiefe Stimme vor der nächsten Äußerung: so klingt der Träger am Kopfhörer-Mikro. */
        fun own() {
            pipeline.onAudio(silence(300), 9600)
            val t = tone(115.0, 8000.0, 600)
            pipeline.onAudio(t, t.size)
        }
    }

    @Test fun `Ziffern und Einheiten werden fuer die Stimme ausgeschrieben, Anzeige und Verlauf behalten den Text`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Die Zugspitze ist 2962 m hoch.") }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()

        assertEquals(listOf("Die Zugspitze ist zweitausendneunhundertzweiundsechzig Meter hoch."), rig.speaker.spoken)
        assertEquals("Die Zugspitze ist 2962 m hoch.", rig.state.value.answer)

        advanceTimeBy(5_000)
        rig.say("und seit wann")
        runCurrent()
        val second = rig.claude.respondCalls[1]
        assertTrue(second, second.contains("ALFRED: Die Zugspitze ist 2962 m hoch."))
    }

    @Test fun `eine Antwort ohne jeden Satz gilt nicht als gesagt`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n") }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()

        assertEquals(emptyList<String>(), rig.speaker.spoken)
        assertEquals("übersprungen: leere antwort", rig.state.value.lastDecision)
        assertFalse(rig.state.value.speaking)
    }

    @Test fun `eine Sonderzeile hinter einer zu langen Antwort wird trotzdem ausgefuehrt`() = runTest {
        // "kurz" erlaubt 22 Wörter. Der zweite Satz sprengt den Deckel und wird nicht mehr gesprochen;
        // die Notiz dahinter muss trotzdem gespeichert werden, denn "Notiert" war schon zu hören.
        val long = "Dazu kommen noch sehr viele weitere Einzelheiten die an dieser Stelle niemand hören will " +
            "weil sie die Antwort nur unnötig in die Länge ziehen würden und nichts Neues bringen."
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Notiert, Sir. ", "$long\n", "MEMO: Kennzeichen M-AB 1234\n") }))
        rig.own()
        rig.say("Alfred, merk dir das Kennzeichen M-AB 1234")
        runCurrent()

        assertEquals(listOf("Notiert, Sir."), rig.speaker.spoken)
        assertEquals(listOf("Kennzeichen M-AB 1234"), rig.env.notes)
    }

    @Test fun `das eigene Echo wird auch in der ausgeschriebenen Fassung erkannt`() = runTest {
        // Das Modell hat Ziffern und Einheiten geschrieben, zu hören war die ausgeschriebene Fassung. Schallt sie
        // ins Mikrofon zurück, schreibt die Transkription sie in Wörtern, in Ziffern oder gemischt. In jeder
        // dieser Schreibweisen ist es das eigene Echo und gehört nicht in die Mitschrift.
        for (echo in listOf(
            "das sind drei Kilometer und zweihundertfünfzig Meter",
            "Das sind drei Kilometer und 250 Meter",
            "Das sind 3 km und 250 m",
        )) {
            val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Das sind 3 km und 250 m.") }))
            rig.say("Wie weit ist das?")
            runCurrent()
            assertEquals(listOf("Das sind drei Kilometer und zweihundertfünfzig Meter."), rig.speaker.spoken)

            advanceTimeBy(1_000)
            rig.say(echo)
            runCurrent()
            assertEquals(echo, listOf("?: Wie weit ist das?"), rig.state.value.transcript)

            // Was jemand anderes dazu sagt, ist kein Echo.
            val reaction = "das ist ja ganz schön weit weg"
            rig.say(reaction)
            runCurrent()
            assertEquals(listOf("?: Wie weit ist das?", "?: $reaction"), rig.state.value.transcript)
        }
    }

    @Test fun `Rueckfrage des Traegers mit den Woertern der Antwort ist kein Echo`() = runTest {
        // Die Gegenprobe zum Test darüber. Der Träger fragt mit seiner eigenen, lauten Stimme zurück und
        // wiederholt dabei zwei von drei Wörtern der Antwort. Was aus dem Kopfhörer ins Mikro zurückschallt,
        // kommt nie so laut an wie er selbst: Seine Stimme ist kein Echo. Früher verschwand die Rückfrage spurlos,
        // und seit beide Fassungen der Antwort zählen, auch dann, wenn das Modell Ziffern geschrieben hatte.
        val withProfile = Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5))
        for (written in listOf("Das sind 3 km.", "Das sind drei Kilometer.")) {
            val rig = Rig(this, FakeClaude(), config = withProfile)
            rig.claude.reply = { user ->
                if (user.lines().last { it.startsWith(">>") }.contains("Wie weit")) listOf("SAY kurz\n", written) else listOf("SAY kurz\n", "Ja, Luftlinie.")
            }
            rig.own()
            rig.say("Wie weit ist das?")
            runCurrent()
            assertEquals(listOf("Das sind drei Kilometer."), rig.speaker.spoken)

            advanceTimeBy(1_500)
            rig.own()
            rig.say("Wirklich drei Kilometer?")
            runCurrent()
            assertEquals(written, listOf("ICH: Wie weit ist das?", "ICH: Wirklich drei Kilometer?"), rig.state.value.transcript)
            assertEquals(written, listOf("Das sind drei Kilometer.", "Ja, Luftlinie."), rig.speaker.spoken)

            // Dieselben Wörter ohne messbare Stimme bleiben ein Echo.
            advanceTimeBy(1_000)
            rig.say("ja Luftlinie also")
            runCurrent()
            assertEquals(written, 2, rig.state.value.transcript.size)
        }
        // Ohne Kopfhörer (echoRisk) trennt die Lautstärke nicht: Dort bleibt es bei der Prüfung am Wortlaut.
        val open = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Das sind drei Kilometer.") }), config = withProfile.copy(echoRisk = true))
        open.own()
        open.say("Wie weit ist das?")
        runCurrent()
        advanceTimeBy(1_500)
        open.own()
        open.say("das sind drei Kilometer")
        runCurrent()
        assertEquals(listOf("ICH: Wie weit ist das?"), open.state.value.transcript)
    }

    @Test fun `bricht der Aufruf hinter dem Deckel ab, bleibt die Antwort stehen`() = runTest {
        // Hinter dem Längendeckel wird nur noch auf eine Sonderzeile gewartet. Reißt die Verbindung dort ab,
        // fehlt nichts, was zu sprechen wäre: kein Fehler in der Anzeige, und der Satz wird nicht abgeschnitten.
        val long = "Dazu kommen noch sehr viele weitere Einzelheiten die an dieser Stelle niemand hören will " +
            "weil sie die Antwort nur unnötig in die Länge ziehen würden und nichts Neues bringen."
        val claude = object : ClaudeApi {
            override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
                listOf("SAY kurz\n", "Notiert, Sir. ", "$long\n").forEach(onText)
                throw java.io.IOException("Verbindung abgerissen")
            }
            override fun quick(system: String, user: String, maxTokens: Long): String = "NEIN"
        }
        val speaker = FakeSpeaker()
        val state = MutableStateFlow(UiState())
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, backgroundScope,
            StandardTestDispatcher(testScheduler), Pipeline.Config(), FakeEnv(),
        ) { testScheduler.currentTime }.also { it.start() }

        pipeline.onCommitted("Alfred, merk dir das Kennzeichen M-AB 1234", null, null)
        runCurrent()

        assertEquals(listOf("Notiert, Sir."), speaker.spoken)
        assertNull(state.value.error)
        assertEquals("antwortet (kurz)", state.value.lastDecision)
        assertFalse(state.value.speaking)
    }

    /**
     * Ein Modell, das auf die Bitte ums Merken weit über den Deckel hinaus schreibt und danach noch lange nicht
     * fertig ist: Der Rest samt Sonderzeile kommt erst, wenn der Test ihn freigibt. Alles andere beantwortet es sofort.
     */
    private class Rambling(private val tail: String) : ClaudeApi {
        val calls = CopyOnWriteArrayList<String>()
        val rambling = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
            val trigger = user.lineSequence().first { it.startsWith(">> [jetzt]") }
            calls += trigger
            if (trigger.contains("merk dir")) {
                onText("SAY kurz\n")
                onText("Notiert, Sir. ")
                onText(
                    "Dazu kommen noch sehr viele weitere Einzelheiten die an dieser Stelle niemand hören will " +
                        "weil sie die Antwort nur unnötig in die Länge ziehen würden und nichts Neues bringen. "
                )
                rambling.countDown()
                release.await() // ein abgebrochener Aufruf beendet das Warten mit einer Ausnahme
                onText(tail)
            } else {
                onText("SAY kurz\n")
                onText("Zweitausendsiebenhundertdreizehn Meter.")
            }
        }

        override fun quick(system: String, user: String, maxTokens: Long): String = "NEIN"
    }

    @Test fun `nach dem Deckel hoert der Assistent wieder zu, sobald der Ton zu Ende ist`() {
        // Echte Threads: Der Aufruf ist wirklich noch unterwegs, während der gekürzte Text längst verklungen ist.
        // Solange gälte der Assistent als sprechend und überhörte jede Frage ohne Anrede. Der Auftrag endet
        // deshalb mit dem Ton; auf die Sonderzeile am Schluss wartet ein eigener Leser.
        val claude = Rambling(tail = "Und das war dann alles.\nMEMO: Kennzeichen M-AB 1234\n")
        val rig = ThreadRig(claude, Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)))
        try {
            rig.wearerSays("Alfred, merk dir das Kennzeichen M-AB 1234")
            assertTrue("das Modell schreibt hinter dem Deckel weiter", claude.rambling.await(5, TimeUnit.SECONDS))
            assertTrue("gesprochen=${rig.speaker.spoken}", waitFor(3_000) { rig.speaker.spoken.isNotEmpty() && !rig.state.value.speaking })
            assertEquals(listOf("Notiert, Sir."), rig.speaker.spoken.toList())

            // Der Träger fragt etwas ohne Anrede, während das Modell noch am Rest der alten Antwort schreibt.
            rig.clock.set(10_000)
            rig.wearerSays("Wie hoch ist der Watzmann?")
            assertTrue(
                "Aufrufe=${claude.calls} gesprochen=${rig.speaker.spoken} zuletzt=${rig.state.value.lastDecision}",
                waitFor(3_000) { rig.speaker.spoken.size == 2 },
            )
            assertEquals(listOf("Notiert, Sir.", "Zweitausendsiebenhundertdreizehn Meter."), rig.speaker.spoken.toList())
            assertTrue("vor der Sonderzeile ist nichts gemerkt: ${rig.env.notes}", rig.env.notes.isEmpty())

            // Jetzt erst schreibt das Modell die alte Antwort zu Ende. Die Notiz dahinter wird noch gespeichert.
            claude.release.countDown()
            assertTrue("gemerkt=${rig.env.notes}", waitFor(3_000) { rig.env.notes.isNotEmpty() })
            assertEquals(listOf("Kennzeichen M-AB 1234"), rig.env.notes.toList())
            assertNull(rig.state.value.error)
        } finally {
            rig.close()
        }
    }

    @Test fun `auch die nachgelesene Sonderzeile gilt nur fuer die Stimme des Traegers`() {
        // Derselbe Ablauf, nur kommt die Bitte vom Tischnachbarn (mittlere Lautstärke, andere Stimmlage: NAH).
        // Der Leser, der den Rest des Aufrufs zu Ende liest, führt nichts an der Schranke vorbei aus.
        val claude = Rambling(tail = "Und das war dann alles.\nMEMO: Kennzeichen M-AB 1234\nMUTE: 30\n")
        val rig = ThreadRig(claude, Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)))
        try {
            rig.says(200.0, 1300.0, 600, "Alfred, merk dir das Kennzeichen M-AB 1234")
            assertEquals(listOf("NAH: Alfred, merk dir das Kennzeichen M-AB 1234"), rig.state.value.transcript)
            assertTrue("das Modell schreibt hinter dem Deckel weiter", claude.rambling.await(5, TimeUnit.SECONDS))
            assertTrue("gesprochen=${rig.speaker.spoken}", waitFor(3_000) { rig.speaker.spoken.isNotEmpty() && !rig.state.value.speaking })

            claude.release.countDown()
            assertTrue(
                "zuletzt=${rig.state.value.lastDecision}",
                waitFor(3_000) { rig.state.value.lastDecision?.startsWith("abgelehnt") == true },
            )
            Thread.sleep(100) // die zweite Sonderzeile folgt gleich dahinter
            assertTrue("gemerkt=${rig.env.notes}", rig.env.notes.isEmpty())
            assertEquals(0L, rig.state.value.mutedUntilMs)
        } finally {
            rig.close()
        }
    }

    @Test fun `heisst der Assistent anders, stehen seine Zeilen unter diesem Namen in der Mitschrift`() = runTest {
        val claude = FakeClaude(reply = { listOf("SAY kurz\n", "Zweitausendneunhundertzweiundsechzig Meter.") })
        val rig = Rig(this, claude, Profile(name = "Jarvis"), Pipeline.Config(names = listOf("Jarvis")))
        rig.say("Jarvis, wie hoch ist die Zugspitze?")
        runCurrent()
        advanceTimeBy(5_000)
        rig.say("und seit wann")
        runCurrent()

        // Legende und Mitschrift benutzen dasselbe Wort; "ALFRED" kommt nirgends mehr vor.
        assertTrue(claude.systemSeen!!.contains("JARVIS (du selbst)"))
        val second = claude.respondCalls[1]
        assertTrue(second, second.contains("JARVIS: Zweitausendneunhundertzweiundsechzig Meter."))
        assertFalse(second, second.contains("ALFRED"))
    }

    @Test fun `die eigene Uhr beginnt bei null und laeuft nur vorwaerts`() {
        val clock = monotonicClock()
        val a = clock()
        Thread.sleep(15)
        val b = clock()
        assertTrue("Start war $a", a in 0..1_000)
        assertTrue("$a -> $b", b >= a + 10)
    }
}
