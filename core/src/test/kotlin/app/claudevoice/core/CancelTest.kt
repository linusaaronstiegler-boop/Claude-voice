package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ein Abbruch muss die Netzverbindung in dem Moment schließen, in dem abgebrochen wird, nicht erst,
 * wenn der Aufruf von selbst zurückgekehrt ist.
 */
class CancelTest {

    /**
     * Der erste Aufruf verhält sich wie ein Thread, der in einem Netz-Lesevorgang steckt: Auf Thread.interrupt()
     * reagiert er nicht, nur auf das Schließen der Verbindung (das Abbruch-Signal). Kommt keines, gibt er nach
     * drei Sekunden von selbst auf. Alle weiteren Aufrufe antworten sofort.
     */
    private class DeafClaude : ClaudeApi {
        val started = CountDownLatch(1)
        val returned = CountDownLatch(1)
        @Volatile var returnedAtNanos = 0L
        @Volatile var closedBySignal = false
        val calls = CopyOnWriteArrayList<String>()

        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
            calls += user.lineSequence().first { it.startsWith(">> [jetzt]") }
            if (calls.size > 1) {
                listOf("SAY kurz\n", "Dreihundertdreißig Meter.").forEach(onText)
                return
            }
            val closed = CountDownLatch(1)
            cancel.onCancel { closed.countDown() }
            started.countDown()
            val giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (closed.count > 0 && System.nanoTime() < giveUp) {
                try {
                    closed.await(20, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // taub: Ein Lesevorgang im Netz bemerkt das nicht
                }
            }
            closedBySignal = closed.count == 0L
            returnedAtNanos = System.nanoTime()
            returned.countDown()
        }

        override fun quick(system: String, user: String, maxTokens: Long): String = "NEIN"
    }

    private fun msSince(t0: Long, t1: Long) = (t1 - t0) / 1_000_000

    @Test fun `Stopp schliesst die Verbindung des laufenden Aufrufs sofort`() {
        val claude = DeafClaude()
        val rig = ThreadRig(claude)
        try {
            rig.pipeline.onCommitted("Wie hoch ist die Zugspitze?", null, null)
            assertTrue("Aufruf läuft", claude.started.await(5, TimeUnit.SECONDS))

            val t0 = System.nanoTime()
            rig.pipeline.stopSpeaking()
            assertTrue(claude.returned.await(10, TimeUnit.SECONDS))

            assertTrue("der Aufruf endete, weil er selbst aufgab, nicht durch das Abbruch-Signal", claude.closedBySignal)
            val took = msSince(t0, claude.returnedAtNanos)
            assertTrue("der Aufruf lief nach dem Stopp noch $took ms weiter", took < 1_000)
            assertTrue(rig.speaker.spoken.isEmpty())
            assertNull("ein gewollter Abbruch ist kein Fehler", rig.state.value.error)
        } finally {
            rig.close()
        }
    }

    @Test fun `verworfener Vorgriff schliesst seine Verbindung sofort`() {
        val claude = DeafClaude()
        val rig = ThreadRig(claude)
        try {
            rig.pipeline.onPartial("Wie hoch ist", 0.8)
            assertTrue("Vorgriff läuft", claude.started.await(5, TimeUnit.SECONDS))

            // Der Satz endet anders: Der Vorgriff ist wertlos, ein frischer Aufruf übernimmt.
            val t0 = System.nanoTime()
            rig.pipeline.onCommitted("Wie hoch ist der Eiffelturm?", null, null)
            assertTrue(claude.returned.await(10, TimeUnit.SECONDS))

            assertTrue("der Vorgriff endete, weil er selbst aufgab, nicht durch das Abbruch-Signal", claude.closedBySignal)
            val took = msSince(t0, claude.returnedAtNanos)
            assertTrue("der verworfene Vorgriff lief noch $took ms weiter", took < 1_000)
            assertTrue("die Antwort auf die echte Frage kommt", waitFor(5_000) { rig.speaker.spoken.isNotEmpty() })
            assertEquals(listOf("Dreihundertdreißig Meter."), rig.speaker.spoken.toList())
        } finally {
            rig.close()
        }
    }

    @Test fun `verdraengte Frage schliesst ihre Verbindung sofort`() {
        val claude = DeafClaude()
        val rig = ThreadRig(claude)
        try {
            rig.pipeline.onCommitted("Wie hoch ist die Zugspitze?", null, null)
            assertTrue("Aufruf läuft", claude.started.await(5, TimeUnit.SECONDS))

            val t0 = System.nanoTime()
            rig.clock.set(600)
            rig.pipeline.onCommitted("Nein, wie hoch ist der Eiffelturm?", null, null)
            assertTrue(claude.returned.await(10, TimeUnit.SECONDS))

            assertTrue("der alte Aufruf endete, weil er selbst aufgab, nicht durch das Abbruch-Signal", claude.closedBySignal)
            val took = msSince(t0, claude.returnedAtNanos)
            assertTrue("der verdrängte Aufruf lief noch $took ms weiter", took < 1_000)
            rig.clock.set(2_000) // die Höflichkeitspause der neuen Frage ist vorbei
            assertTrue(waitFor(5_000) { rig.speaker.spoken.isNotEmpty() })
            assertEquals(listOf("Dreihundertdreißig Meter."), rig.speaker.spoken.toList())
        } finally {
            rig.close()
        }
    }
}
