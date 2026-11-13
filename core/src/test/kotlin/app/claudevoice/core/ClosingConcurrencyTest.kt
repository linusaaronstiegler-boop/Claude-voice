package app.claudevoice.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Gleichzeitigkeit, Schlussrunde: Lagen, in denen zwei Threads (Transkription, Auftrag, Stopp-Knopf, Nachleser)
 * sich in die Quere kommen. Jeder Test hält einen der Threads an einer bestimmten Stelle an und lässt den
 * anderen vorbeiziehen; so wird aus "passiert einmal in hundert Versuchen" ein Ablauf, der jedes Mal gleich ist.
 * Gefunden hat diese Lagen die Gegenprüfung der Reparatur; drei der Tests (Schranke nach der Pause, stumm nach
 * der Pause, veralteter Platz) halten Regeln fest, die vorher kein Test abgesichert hat.
 */
class ClosingConcurrencyTest {

    /** Ein Aufruf beim Modell, wie ihn die Attrappe sieht. */
    class Call(val trigger: String, private val sink: (String) -> Unit) {
        val closed = CountDownLatch(1)
        val returned = CountDownLatch(1)
        fun text(s: String) = sink(s)

        /** Wartet wie ein Lesevorgang im Netz: taub für Thread.interrupt(), endet durch [latch], das Schließen oder nach [maxMs]. */
        fun hang(latch: CountDownLatch, maxMs: Long = 8_000) {
            val giveUp = System.nanoTime() + maxMs * 1_000_000
            while (latch.count > 0 && closed.count > 0 && System.nanoTime() < giveUp) {
                try { latch.await(5, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { /* taub */ }
            }
            if (closed.count == 0L) throw java.io.IOException("Verbindung geschlossen")
        }
    }

    class ScriptedClaude(private val gate: String = "JA", private val script: (Call) -> Unit) : ClaudeApi {
        val calls = CopyOnWriteArrayList<Call>()
        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
            val call = Call(user.lineSequence().first { it.startsWith(">> [jetzt]") }, onText)
            cancel.onCancel { call.closed.countDown() }
            calls += call
            try { script(call) } finally { call.returned.countDown() }
        }
        override fun quick(system: String, user: String, maxTokens: Long): String = gate
    }

    class Notes : Pipeline.Env {
        val notes = CopyOnWriteArrayList<String>()
        override fun localTime() = "Donnerstag, 12. November 2026, 21:14"
        override fun musicPlaying() = false
        override fun saveNote(text: String) { notes += text }
        override fun saveVoiceProfile(profile: VoiceProfile) {}
    }

    /** Lautsprecher mit echter Dauer je Satz und, nach einem Abbruch, einer Aufräumzeit. */
    class Timed(private val perSentenceMs: Long, private val windDownMs: Long = 0) : Speaker {
        val spoken = CopyOnWriteArrayList<String>()
        override suspend fun speak(sentences: ReceiveChannel<String>, release: Deferred<Boolean>?, onFirstAudio: () -> Unit) {
            if (release != null && !release.await()) return
            var first = true
            try {
                for (s in sentences) {
                    if (first) { onFirstAudio(); first = false }
                    spoken += s
                    delay(perSentenceMs)
                }
            } finally {
                if (!first && windDownMs > 0) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(windDownMs) }
            }
        }
        /** Für den Test: was beim Anhalten geschieht (etwa: Es dauert). */
        @Volatile var onStop: (() -> Unit)? = null
        override fun stop() { onStop?.invoke() }
    }

    /** Die Namensliste des Assistenten mit einem Haken: So lässt sich der Thread der Transkription mitten in onCommitted anhalten. */
    class HookedNames(private val base: List<String>) : AbstractList<String>() {
        @Volatile var hook: (() -> Unit)? = null
        override val size: Int get() = base.size
        override fun get(index: Int): String = base[index]
        override fun iterator(): Iterator<String> {
            hook?.invoke()
            return base.iterator()
        }
    }

    class Rig(
        val claude: ClaudeApi,
        val speaker: Timed = Timed(0),
        config: Pipeline.Config = Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)),
    ) {
        val env = Notes()
        val clock = AtomicLong(0)
        val state = MutableStateFlow(UiState())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pipeline = Pipeline(Brain(claude, Profile()), speaker, state, scope, Dispatchers.IO, config, env) { clock.get() }
            .also { it.start() }
        val spoken: List<String> get() = speaker.spoken.toList()

        fun audio(hz: Double, amplitude: Double) {
            pipeline.onAudio(silence(300), 9600)
            val t = tone(hz, amplitude, 600)
            pipeline.onAudio(t, t.size)
        }
        fun says(hz: Double, amplitude: Double, text: String) { audio(hz, amplitude); pipeline.onCommitted(text, null, null) }
        fun wearer(text: String) = says(115.0, 8000.0, text)
        fun friend(text: String) = says(200.0, 1300.0, text)
        fun far(text: String) = says(180.0, 200.0, text)

        private fun field(name: String): Any? = Pipeline::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(pipeline)
        val pendingSet: Boolean get() = (field("pending") as AtomicReference<*>).get() != null
        val parkedSet: Boolean get() = (field("parked") as AtomicReference<*>).get() != null
        val speakingFlag: Boolean get() = field("speaking") as Boolean
        val mutedUntil: Long get() = field("mutedUntilMs") as Long

        fun close() { pipeline.stop(); scope.cancel() }
    }

    private val long = "Dazu kommen noch sehr viele weitere Einzelheiten die an dieser Stelle niemand hören will " +
        "weil sie die Antwort nur unnötig in die Länge ziehen würden und nichts Neues bringen."

    private fun idOf(text: String): String = Regex("Nummer ([A-Z][a-z]+)").find(text)?.groupValues?.get(1) ?: "?"

    // ------------------------------------------------------------------------------------------
    // 1. Die fertige Äußerung meldet "er redet nicht mehr", bevor sie angenommen ist

    @Test fun `neue Frage des Traegers loest die alte ab, auch wenn ihre fertige Aeusserung gerade erst verarbeitet wird`() {
        // Der Träger fragt in Gesellschaft ohne Anrede und redet gleich weiter. Die Antwort liegt hinter der
        // Schranke bereit; die Höflichkeitspause ist um, der Ton wartet nur noch, weil er selbst spricht
        // (wearerGoesOn). Dann trifft seine neue Frage als fertige Äußerung ein. onCommitted meldet als Erstes
        // "die Äußerung ist zu Ende" (utteranceOpen = false) und nimmt die neue Frage erst viel später an
        // (pending). Fragt der wartende Auftrag genau dazwischen nach, gibt er die alte Antwort frei.
        val names = HookedNames(listOf("Alfred"))
        val claude = ScriptedClaude(gate = "NEIN") { call ->
            when {
                call.trigger.contains("Zugspitze") -> { call.text("SAY kurz\n"); call.text("Antwort Zugspitze.") }
                call.trigger.contains("Watzmann") -> { call.text("SAY kurz\n"); call.text("Antwort Watzmann.") }
                else -> call.text("SKIP sozial\n")
            }
        }
        val rig = Rig(claude, Timed(perSentenceMs = 400), Pipeline.Config(names = names, voiceProfile = VoiceProfile(-18.0, 115.0, 5)))
        try {
            // Ein Freund sagt etwas: Der Träger ist in Gesellschaft, Antworten ohne Anrede warten die Pause ab.
            rig.friend("ich hab gestern den ganzen Abend an dem Aufsatz gesessen")
            assertTrue(waitFor(3_000) { rig.state.value.lastDecision == "übersprungen: kein Anlass" })

            rig.clock.set(5_000)
            rig.wearer("Wie hoch ist die Zugspitze?")
            assertTrue(waitFor(3_000) { claude.calls.size == 1 && claude.calls[0].returned.count == 0L })

            // Er redet weiter (eigene Stimme, erster Zwischenstand), die Pause läuft ab.
            rig.clock.set(5_400)
            rig.audio(115.0, 8000.0)
            rig.pipeline.onPartial("Nein wie hoch ist", null)
            rig.clock.set(6_500)
            Thread.sleep(200)
            assertEquals("solange er selbst redet, wartet der Ton", emptyList<String>(), rig.spoken)

            // Die fertige Äußerung trifft ein. Der Thread der Transkription wird zwischen "Äußerung zu Ende" und
            // dem Annehmen angehalten, so lange wie eine Abfrage des wartenden Auftrags höchstens entfernt ist.
            val reached = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val transcriber = Thread {
                val me = Thread.currentThread()
                names.hook = { if (Thread.currentThread() === me && reached.count > 0) { reached.countDown(); proceed.await() } }
                rig.pipeline.onCommitted("Nein, wie hoch ist der Watzmann?", null, null)
            }
            transcriber.start()
            assertTrue(reached.await(3, TimeUnit.SECONDS))
            waitFor(300) { rig.spoken.isNotEmpty() }
            proceed.countDown()
            transcriber.join(3_000)
            waitFor(3_000) { rig.clock.addAndGet(100); !rig.pendingSet && !rig.speakingFlag && claude.calls.all { it.returned.count == 0L } }
            Thread.sleep(100)

            assertEquals(
                "beantwortet wurde die alte Frage: Aufrufe=${claude.calls.map { it.trigger }} zuletzt=${rig.state.value.lastDecision}",
                listOf("Antwort Watzmann."), rig.spoken,
            )
        } finally {
            rig.close()
        }
    }

    @Test fun `Kontrolle - ohne die Unterbrechung loest die neue Frage die alte ab`() {
        val claude = ScriptedClaude(gate = "NEIN") { call ->
            when {
                call.trigger.contains("Zugspitze") -> { call.text("SAY kurz\n"); call.text("Antwort Zugspitze.") }
                call.trigger.contains("Watzmann") -> { call.text("SAY kurz\n"); call.text("Antwort Watzmann.") }
                else -> call.text("SKIP sozial\n")
            }
        }
        val rig = Rig(claude, Timed(perSentenceMs = 400))
        try {
            rig.friend("ich hab gestern den ganzen Abend an dem Aufsatz gesessen")
            assertTrue(waitFor(3_000) { rig.state.value.lastDecision == "übersprungen: kein Anlass" })
            rig.clock.set(5_000)
            rig.wearer("Wie hoch ist die Zugspitze?")
            assertTrue(waitFor(3_000) { claude.calls.size == 1 && claude.calls[0].returned.count == 0L })
            rig.clock.set(5_400)
            rig.audio(115.0, 8000.0)
            rig.pipeline.onPartial("Nein wie hoch ist", null)
            rig.clock.set(6_500)
            Thread.sleep(200)
            assertEquals(emptyList<String>(), rig.spoken)
            rig.pipeline.onCommitted("Nein, wie hoch ist der Watzmann?", null, null)
            waitFor(3_000) { rig.clock.addAndGet(100); rig.spoken.isNotEmpty() }
            waitFor(3_000) { rig.clock.addAndGet(100); !rig.pendingSet && !rig.speakingFlag }
            assertEquals(listOf("Antwort Watzmann."), rig.spoken)
        } finally {
            rig.close()
        }
    }

    // ------------------------------------------------------------------------------------------
    // 1b. Die Prüfung in open() auf dem Weg über die Schranke (Ende der Höflichkeitspause)

    /** Ein Dispatcher, der nichts von selbst ausführt; der Test bestimmt, was als Nächstes läuft. */
    private class Manual : kotlinx.coroutines.CoroutineDispatcher() {
        val queue = java.util.concurrent.ConcurrentLinkedDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runOldestOne(): Boolean = queue.pollFirst()?.also { it.run() } != null
        fun runNewestFirst() { while (true) (queue.pollLast() ?: return).run() }
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

    @Test fun `endet die Hoeflichkeitspause, bevor der abgeloeste Auftrag abgebrochen ist, bleibt die alte Antwort ungesprochen`() {
        // Das Gegenstück zu SupersedeTest "trifft die alte Antwort ein, bevor der abgeloeste Auftrag abgebrochen
        // ist ...", nur auf dem zweiten Weg zum Ton: Die alte Antwort liegt schon hinter der Schranke, die neue
        // Frage ist angenommen, und die Höflichkeitspause läuft ab, bevor die Schleife den alten Auftrag abbricht.
        val claude = ScriptedClaude(gate = "NEIN") { call ->
            when {
                call.trigger.contains("Zugspitze") -> { call.text("SAY kurz\n"); call.text("Antwort Zugspitze.") }
                call.trigger.contains("Watzmann") -> { call.text("SAY kurz\n"); call.text("Antwort Watzmann.") }
                else -> call.text("SKIP sozial\n")
            }
        }
        val manual = Manual()
        val speaker = RecordingSpeaker()
        val state = MutableStateFlow(UiState())
        val scope = CoroutineScope(SupervisorJob() + manual)
        val clock = AtomicLong(0)
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, scope, Dispatchers.IO,
            Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)), Notes(),
        ) { clock.get() }.also { it.start() }
        fun says(hz: Double, amplitude: Double, text: String) {
            pipeline.onAudio(silence(300), 9600)
            tone(hz, amplitude, 600).let { pipeline.onAudio(it, it.size) }
            pipeline.onCommitted(text, null, null)
        }
        try {
            manual.runNewestFirst()
            says(200.0, 1300.0, "ich hab gestern den ganzen Abend an dem Aufsatz gesessen") // ein Freund: Gesellschaft
            assertTrue(manual.runUntil(3_000) { state.value.lastDecision == "übersprungen: kein Anlass" })

            clock.set(5_000)
            says(115.0, 8000.0, "Wie hoch ist die Zugspitze?")
            assertTrue("die Antwort liegt hinter der Schranke", manual.runUntil(3_000) { speaker.received.isNotEmpty() })
            manual.runUntil(100) { false } // alles zur Ruhe: Der Auftrag wartet nur noch auf das Ende der Pause
            assertTrue(speaker.spoken.isEmpty())

            // Die nächste Abfrage der Pause steht an (ihr Zeitgeber ist abgelaufen), läuft aber noch nicht.
            assertTrue("die Abfrage der Pause steht an", waitFor(2_000) { manual.queue.size == 1 })
            clock.set(5_800)
            says(115.0, 8000.0, "Nein, wie hoch ist der Watzmann?") // angenommen; die Schleife weiß es noch nicht
            assertEquals("Abfrage der Pause und Schleife stehen an", 2, manual.queue.size)
            clock.set(6_100) // die Pause ist um
            assertTrue(manual.runOldestOne()) // die Pause endet: Der alte Auftrag wird geweckt ...
            manual.runNewestFirst() // ... und kommt vor der Schleife dran
            val heardOld = speaker.spoken.toList()
            // Jetzt läuft die Uhr weiter: Auch die neue Frage wartet ihre Höflichkeitspause ab.
            manual.runUntil(3_000) { clock.addAndGet(20); speaker.spoken.contains("Antwort Watzmann.") && !state.value.speaking }
            assertEquals("gleich nach dem Ende der Pause war die alte Antwort zu hören", emptyList<String>(), heardOld)
            assertEquals("zuletzt=${state.value.lastDecision}", listOf("Antwort Watzmann."), speaker.spoken.toList())
        } finally {
            pipeline.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------------------------------
    // 1c. Stumm am Ende der Höflichkeitspause

    @Test fun `wird die Ruhe waehrend der Hoeflichkeitspause eingetragen, bleibt die wartende Antwort ungesprochen`() {
        // Die Bitte ist nicht als Bitte um Ruhe zu erkennen; MUTE steht hinter dem Deckel und trifft erst ein,
        // während die Antwort auf eine spätere Frage ohne Anrede hinter der Schranke wartet.
        val tail = CountDownLatch(1)
        val claude = ScriptedClaude(gate = "NEIN") { call ->
            when {
                call.trigger.contains("konzentrieren") -> {
                    call.text("SAY kurz\n"); call.text("Sehr wohl, Sir. "); call.text("$long ")
                    call.hang(tail)
                    call.text("\nMUTE: 10\n")
                }
                call.trigger.contains("Zugspitze") -> { call.text("SAY kurz\n"); call.text("Antwort Zugspitze.") }
                else -> call.text("SKIP sozial\n")
            }
        }
        val rig = Rig(claude)
        try {
            assertTrue(!Heuristics.asksForQuiet("Alfred, ich muss mich jetzt konzentrieren"))
            rig.wearer("Alfred, ich muss mich jetzt konzentrieren")
            assertTrue(waitFor(3_000) { rig.spoken == listOf("Sehr wohl, Sir.") && !rig.speakingFlag && !rig.pendingSet })
            rig.clock.set(2_000)
            rig.friend("ich hab gestern den ganzen Abend an dem Aufsatz gesessen") // Gesellschaft
            assertTrue(waitFor(3_000) { rig.state.value.lastDecision == "übersprungen: kein Anlass" })
            rig.clock.set(3_000)
            rig.wearer("Wie hoch ist die Zugspitze?")
            assertTrue(waitFor(3_000) { claude.calls.any { it.trigger.contains("Zugspitze") && it.returned.count == 0L } })
            Thread.sleep(120)
            assertEquals("die Antwort wartet hinter der Schranke", listOf("Sehr wohl, Sir."), rig.spoken)

            tail.countDown() // jetzt erst schreibt das Modell MUTE
            assertTrue(waitFor(3_000) { rig.state.value.mutedUntilMs > 0 })
            rig.clock.set(4_200) // die Höflichkeitspause ist um
            waitFor(1_500) { rig.spoken.size > 1 || rig.state.value.lastDecision == "übersprungen: stumm" }
            Thread.sleep(100)
            assertEquals("stumm geschaltet und trotzdem gesprochen", listOf("Sehr wohl, Sir."), rig.spoken)
            assertEquals("übersprungen: stumm", rig.state.value.lastDecision)
        } finally {
            tail.countDown()
            rig.close()
        }
    }

    // ------------------------------------------------------------------------------------------
    // 1d. Stopp setzt `speaking` ganz am Ende zurück, ohne zu prüfen, wessen Antwort inzwischen zu hören ist

    @Test fun `Stopp, dessen Lautsprecher langsam anhaelt, nimmt der naechsten Antwort nicht das Merkmal hoerbar`() {
        val claude = ScriptedClaude { call ->
            call.text("SAY kurz\n"); call.text("Antwort ${idOf(call.trigger)}. Zweiter Satz dazu. Dritter Satz dazu.")
        }
        val out = Timed(perSentenceMs = 500)
        val rig = Rig(claude, out)
        try {
            rig.wearer("Alfred, wie hoch ist der Berg Nummer Ba?")
            assertTrue(waitFor(3_000) { rig.spoken.isNotEmpty() })
            // Der Knopf: stopSpeaking() hängt im Anhalten des Lautsprechers fest (auf dem Handy: AudioTrack.pause/flush).
            val entered = CountDownLatch(1)
            val leave = CountDownLatch(1)
            out.onStop = { if (entered.count > 0) { entered.countDown(); leave.await() } }
            val button = Thread { rig.pipeline.stopSpeaking() }
            button.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            // Währenddessen fragt der Träger neu, und die neue Antwort beginnt.
            rig.clock.set(1_000)
            rig.wearer("Alfred, wie hoch ist der Berg Nummer Du?")
            assertTrue(waitFor(3_000) { rig.spoken.contains("Antwort Du.") })
            leave.countDown() // jetzt erst kehrt das alte Stopp zurück und setzt speaking = false
            button.join(3_000)
            assertTrue("die neue Antwort ist zu hören", rig.state.value.speaking)
            val flag = rig.speakingFlag
            // Folge: Sein bloßes "Stopp" gilt nur, solange der Assistent als sprechend gilt.
            rig.clock.set(1_200)
            rig.wearer("Stopp")
            Thread.sleep(700)
            assertTrue(
                "die Pipeline hält die hörbare Antwort für nicht hörbar (speaking=$flag), und das bloße Stopp des Trägers " +
                    "blieb ohne Wirkung: ${rig.spoken}",
                flag && !rig.spoken.contains("Zweiter Satz dazu."),
            )
        } finally {
            rig.close()
        }
    }

    // ------------------------------------------------------------------------------------------
    // 1e. Der eine Platz für Zurückgestelltes: Eine veraltete Äußerung weicht

    @Test fun `eine veraltete zurueckgestellte Aeusserung macht einer neueren Platz, auch wenn die weniger Anspruch hat`() {
        val release = CountDownLatch(1)
        val claude = ScriptedClaude(gate = "JA") { call ->
            when {
                call.trigger.contains("Zugspitze") -> { call.hang(release); call.text("SKIP unklar\n") }
                call.trigger.contains("Aufsatz") -> { call.text("SAY kurz\n"); call.text("Fleißig, Sir.") }
                else -> call.text("SKIP sozial\n")
            }
        }
        val rig = Rig(claude)
        try {
            rig.wearer("Alfred, wie hoch ist die Zugspitze?") // wartet auf das Modell
            assertTrue(waitFor(3_000) { claude.calls.size == 1 })
            rig.clock.set(500)
            rig.friend("Wann fängt der Film an?") // steht zurück (Rang 4)
            rig.clock.set(5_000) // die Frage des Freundes ist jetzt veraltet (mehr als vier Sekunden)
            rig.wearer("ich hab gestern den ganzen Abend an dem Aufsatz gesessen") // Rang 3: weniger, aber frisch
            rig.clock.set(5_200)
            release.countDown() // der erste Auftrag endet ohne Ton: Wer zurückstand, rückt nach
            assertTrue(
                "über die frische Aussage urteilt niemand: Aufrufe=${claude.calls.map { it.trigger }} zurückgestellt=${rig.parkedSet}",
                waitFor(2_000) { claude.calls.any { it.trigger.contains("Aufsatz") } },
            )
        } finally {
            release.countDown()
            rig.close()
        }
    }

    // ------------------------------------------------------------------------------------------
    // 1f. Der Aufruf scheitert hinter dem Deckel, bevor die Sonderzeile da ist

    @Test fun `scheitert der Aufruf hinter dem Deckel vor der Sonderzeile, steht das in der Anzeige`() {
        // "Notiert, Sir." war zu hören, das Modell schreibt weiter, und die Verbindung reißt ab, bevor MEMO dasteht.
        // Ohne Deckel wird derselbe Abbruch gemeldet ("Antwort fehlgeschlagen"); die Gegenprobe steht darunter.
        for (capped in listOf(false, true)) { // erst die Gegenprobe ohne Deckel
            val tail = CountDownLatch(1)
            val claude = ScriptedClaude { call ->
                call.text("SAY kurz\n"); call.text("Notiert, Sir. ")
                if (capped) call.text("$long ")
                call.hang(tail)
                throw java.io.IOException("Connection reset")
            }
            val rig = Rig(claude)
            try {
                rig.wearer("Alfred, merk dir das Kennzeichen M-AB 1234")
                assertTrue(waitFor(3_000) { rig.spoken == listOf("Notiert, Sir.") })
                if (capped) assertTrue(waitFor(3_000) { !rig.speakingFlag && !rig.pendingSet })
                tail.countDown()
                waitFor(1_500) { rig.state.value.error != null }
                assertTrue("nichts gemerkt", rig.env.notes.isEmpty())
                assertTrue(
                    "mit Deckel=$capped: \"Notiert\" war zu hören, gemerkt ist nichts, und die Anzeige meldet nichts (error=${rig.state.value.error})",
                    rig.state.value.error != null,
                )
            } finally {
                tail.countDown()
                rig.close()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 1g. Der Nachleser prüft "wurde er wieder angesprochen?" und trägt die Ruhe danach ein: zwei Schritte

    @Test fun `hebt der Traeger die Ruhe auf, waehrend der Nachleser sie gerade eintraegt, bleibt sie aufgehoben`() {
        // Der Nachleser hat MUTE gelesen und geprüft, dass der Träger den Assistenten seither nicht angesprochen
        // hat. Bevor er die Ruhe einträgt, sagt der Träger "Alfred, du darfst wieder". Angehalten wird der Leser
        // über die Uhr des Tests: Pipeline.directive fragt sie genau zwischen Prüfung und Eintrag.
        val tail = CountDownLatch(1)
        val claude = ScriptedClaude { call ->
            when {
                call.trigger.contains("ruhig") -> {
                    call.text("SAY kurz\n"); call.text("Sehr wohl, Sir. "); call.text("$long ")
                    call.hang(tail)
                    call.text("\nMUTE: 10\n")
                }
                call.trigger.contains("darfst wieder") -> { call.text("SAY kurz\n"); call.text("Gern, Sir.") }
                else -> { call.text("SAY kurz\n"); call.text("Um acht.") }
            }
        }
        val reached = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val clock = AtomicLong(0)
        val speaker = Timed(0)
        val state = MutableStateFlow(UiState())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, scope, Dispatchers.IO,
            Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)), Notes(),
        ) {
            if (reached.count > 0 && tail.count == 0L && Thread.currentThread().stackTrace.any { it.methodName == "directive" }) {
                reached.countDown()
                proceed.await()
            }
            clock.get()
        }.also { it.start() }
        fun wearer(text: String) {
            pipeline.onAudio(silence(300), 9600)
            tone(115.0, 8000.0, 600).let { pipeline.onAudio(it, it.size) }
            pipeline.onCommitted(text, null, null)
        }
        try {
            wearer("Alfred, sei bitte zehn Minuten ruhig")
            assertTrue(waitFor(3_000) { speaker.spoken.toList() == listOf("Sehr wohl, Sir.") && !state.value.speaking })
            Thread.sleep(100)

            tail.countDown() // das Modell schreibt MUTE; der Leser hält zwischen Prüfung und Eintrag
            assertTrue(reached.await(3, TimeUnit.SECONDS))
            clock.set(2_000)
            // Die Anrede kommt auf dem Thread der Transkription. Mit der Sperre um das Ein- und Austragen der Ruhe
            // wartet sie, bis der Leser fertig ist; ohne sie zog sie am angehaltenen Leser vorbei, und der trug
            // die Ruhe danach doch noch ein. Der Test gibt ihr einen Vorsprung und lässt dann den Leser weiter.
            val transcriber = Thread { wearer("Alfred, du darfst wieder") }
            transcriber.start()
            transcriber.join(300)
            proceed.countDown()
            transcriber.join(3_000)
            assertTrue(waitFor(3_000) { speaker.spoken.contains("Gern, Sir.") })
            Thread.sleep(200)
            assertEquals("nach \"Alfred, du darfst wieder\" ist doch stumm geschaltet", 0L, state.value.mutedUntilMs)
        } finally {
            tail.countDown()
            proceed.countDown()
            pipeline.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------------------------------
    // 1h. Was unter turnLock geschieht, hält seit der Reparatur auch den Stopp-Knopf auf

    @Test fun `Stopp vom Knopf wartet nicht hinter einer Aeusserung, die gerade angenommen wird`() {
        // onCommitted holt unter turnLock die Lage für den Aufruf (Uhrzeit, läuft Musik?). Auf dem Handy ist
        // "läuft Musik?" ein Aufruf in einen anderen Prozess. Hier dauert er so lange, wie der Test will.
        val inEnv = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val env = object : Pipeline.Env {
            override fun localTime() = "Donnerstag, 12. November 2026, 21:14"
            override fun musicPlaying(): Boolean {
                if (armed.get() && inEnv.count > 0) { inEnv.countDown(); leave.await() }
                return false
            }
            override fun saveNote(text: String) {}
            override fun saveVoiceProfile(profile: VoiceProfile) {}
        }
        val claude = ScriptedClaude { call -> call.text("SAY kurz\n"); call.text("Antwort.") }
        val state = MutableStateFlow(UiState())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pipeline = Pipeline(Brain(claude, Profile()), Timed(0), state, scope, Dispatchers.IO, Pipeline.Config(), env) { 0L }
            .also { it.start() }
        try {
            armed.set(true)
            val transcriber = Thread { pipeline.onCommitted("Alfred, wie hoch ist die Zugspitze?", null, null) }
            transcriber.start()
            assertTrue(inEnv.await(3, TimeUnit.SECONDS))
            val button = Thread { pipeline.stopSpeaking() } // auf dem Handy: der Haupt-Thread
            button.start()
            button.join(500)
            val blocked = button.isAlive
            leave.countDown()
            button.join(3_000)
            transcriber.join(3_000)
            assertTrue("stopSpeaking() hing eine halbe Sekunde hinter der Sperre fest, bis die Äußerung angenommen war", !blocked)
        } finally {
            leave.countDown()
            pipeline.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Ein abgebrochener Auftrag meldet keinen Fehler

    /**
     * Lautsprecher, dem beim Abbruch die Verbindung wegbricht: Wie die echte Sprachausgabe, deren Socket beim
     * Stopp geschlossen wird, wirft er dann keine CancellationException, sondern eine IOException.
     * Mit [failsAtOnce] scheitert er schon am ersten Satz, ohne dass jemand abgebrochen hätte.
     */
    private class Breaking(private val failsAtOnce: Boolean = false) : Speaker {
        val spoken = CopyOnWriteArrayList<String>()
        val broke = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun speak(sentences: ReceiveChannel<String>, release: Deferred<Boolean>?, onFirstAudio: () -> Unit) {
            if (release != null && !release.await()) return
            try {
                for (s in sentences) {
                    if (failsAtOnce) throw java.io.IOException("Verbindung zur Sprachausgabe verloren")
                    onFirstAudio()
                    spoken += s
                    delay(60_000) // die Wiedergabe "läuft", bis jemand abbricht
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                broke.incrementAndGet()
                throw java.io.IOException("Socket closed")
            }
        }
        override fun stop() {}
    }

    private fun answers() = ScriptedClaude { call -> call.text("SAY kurz\n"); call.text("Antwort ${idOf(call.trigger)}.") }

    @Test fun `Stopp - wirft die Sprachausgabe beim Abbruch eine IOException, steht kein Fehler in der Anzeige`() {
        val out = Breaking()
        val rig = ThreadRig(answers(), Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)), out = out)
        try {
            rig.wearerSays("Alfred, wie hoch ist der Berg Nummer Ba?")
            assertTrue(waitFor(3_000) { out.spoken.toList() == listOf("Antwort Ba.") })
            rig.pipeline.stopSpeaking()
            assertTrue("die Sprachausgabe wurde abgebrochen", waitFor(3_000) { out.broke.get() == 1 })
            assertTrue(waitFor(3_000) { !rig.state.value.speaking })
            Thread.sleep(150)
            assertEquals("ein gestoppter Auftrag meldet nichts", null, rig.state.value.error)
        } finally { rig.close() }
    }

    @Test fun `neue Anrede - wirft die unterbrochene Sprachausgabe eine IOException, steht kein Fehler in der Anzeige`() {
        val out = Breaking()
        val rig = ThreadRig(answers(), Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)), out = out)
        try {
            rig.wearerSays("Alfred, wie hoch ist der Berg Nummer Ba?")
            assertTrue(waitFor(3_000) { out.spoken.toList() == listOf("Antwort Ba.") })
            rig.clock.set(1_000)
            rig.wearerSays("Alfred, wie hoch ist der Berg Nummer Du?") // löst die hörbare Antwort ab
            assertTrue("die neue Antwort ist zu hören", waitFor(3_000) { out.spoken.toList() == listOf("Antwort Ba.", "Antwort Du.") })
            assertEquals(1, out.broke.get())
            Thread.sleep(150)
            assertEquals("ein abgelöster Auftrag meldet nichts", null, rig.state.value.error)
        } finally { rig.close() }
    }

    @Test fun `Gegenprobe - scheitert die Sprachausgabe ohne Abbruch, steht der Fehler in der Anzeige`() {
        val out = Breaking(failsAtOnce = true)
        val rig = ThreadRig(answers(), Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)), out = out)
        try {
            rig.wearerSays("Alfred, wie hoch ist der Berg Nummer Ba?")
            assertTrue("gemeldet: ${rig.state.value.error}", waitFor(3_000) { rig.state.value.error != null })
            assertEquals("Antwort fehlgeschlagen: Verbindung zur Sprachausgabe verloren", rig.state.value.error)
            assertEquals(0, out.broke.get())
        } finally { rig.close() }
    }
}
