package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Die Audio-Weiche des Dienstes: Was bleibt in der Bereitschaft auf dem Gerät, was geht nach dem
 * Weckwort hinaus, und was passiert, wenn die Verbindung auf sich warten lässt?
 *
 * Die Stücke tragen hier einfach ihre laufende Nummer. So lässt sich genau ablesen, welche gesendet wurden.
 */
class AudioGateTest {

    /** Erkenner-Attrappe: löst beim n-ten gefütterten Stück aus. */
    private class FakeWake(private vararg val fireAt: Int) : WakeWordDetector {
        var fed = 0
        var resets = 0
        var closed = false
        override fun feed(pcm: ByteArray, length: Int): Boolean {
            check(!closed) { "Erkenner wurde nach dem Freigeben noch gefüttert" }
            fed++
            return fed in fireAt
        }
        override fun reset() { resets++ }
        override fun close() { closed = true }
    }

    /** Verbindungs-Attrappe: merkt sich die Nummern der gesendeten Stücke. */
    private class FakeStream : Transcriber {
        val sent = ArrayList<Int>()
        override fun connect() {}
        override fun sendAudio(pcm: ByteArray, length: Int) { sent += ByteBuffer.wrap(pcm, 0, length).int }
        override fun close() {}
    }

    private val measured = ArrayList<Int>()
    private fun gate(wake: WakeWordDetector?) =
        AudioGate(wake, measure = { pcm, n -> measured += ByteBuffer.wrap(pcm, 0, n).int })

    private fun chunk(number: Int): ByteArray = ByteBuffer.allocate(8).putInt(number).array()

    /** Füttert die Stücke [from]..[to]; gibt die Nummer des Stücks zurück, bei dem das Weckwort fiel (oder -1). */
    private fun AudioGate.feed(from: Int, to: Int): Int {
        var woke = -1
        for (i in from..to) if (onAudio(chunk(i), 8)) woke = i
        return woke
    }

    @Test fun `in der Bereitschaft verlaesst nichts das Geraet`() {
        val wake = FakeWake()
        val gate = gate(wake)
        assertEquals(-1, gate.feed(1, 200))
        assertEquals("jedes Stück geht an den lokalen Erkenner", 200, wake.fed)
        assertTrue("nichts wurde vermessen, also auch nichts weitergereicht", measured.isEmpty())
    }

    @Test fun `nach dem Weckwort geht als Vorlauf nur das Weckwort selbst hinaus`() {
        val gate = gate(FakeWake(40))
        assertEquals(40, gate.feed(1, 40))
        val stream = FakeStream()
        gate.open(stream)
        assertTrue(gate.connected(stream))
        // 13 Stücke = 1,3 s: das Weckwort (gut eine Sekunde) und kaum etwas von davor.
        assertEquals((28..40).toList(), stream.sent)
        assertEquals("die Pipeline vermisst genau das, was gesendet wird", stream.sent, measured)
    }

    @Test fun `langsamer Verbindungsaufbau verliert den Anfang der Frage nicht`() {
        val gate = gate(FakeWake(40))
        gate.feed(1, 40)
        val stream = FakeStream()
        gate.open(stream)
        gate.feed(41, 80) // vier Sekunden, in denen die Verbindung noch nicht steht
        gate.connected(stream)
        assertEquals((28..80).toList(), stream.sent)
    }

    @Test fun `ein sehr langer Verbindungsaufbau behaelt die letzten acht Sekunden`() {
        val gate = gate(FakeWake(40))
        gate.feed(1, 40)
        val stream = FakeStream()
        gate.open(stream)
        gate.feed(41, 200)
        gate.connected(stream)
        assertEquals((121..200).toList(), stream.sent)
    }

    @Test fun `zwischen Weckwort und Oeffnen der Verbindung wird gesammelt aber nicht mehr gelauscht`() {
        val wake = FakeWake(40, 42) // würde zwei Stücke später gleich noch einmal auslösen
        val gate = gate(wake)
        assertEquals(40, gate.feed(1, 40))
        assertEquals("kein zweites Auslösen, solange die Verbindung geöffnet wird", -1, gate.feed(41, 45))
        assertEquals(40, wake.fed)
        assertEquals("der Erkenner wurde nach dem Auslösen geleert", 1, wake.resets)
        val stream = FakeStream()
        gate.open(stream)
        gate.connected(stream)
        assertEquals((28..45).toList(), stream.sent)
    }

    @Test fun `steht die Verbindung geht jedes Stueck sofort hinaus`() {
        val wake = FakeWake(5)
        val gate = gate(wake)
        gate.feed(1, 5)
        val stream = FakeStream()
        gate.open(stream)
        gate.connected(stream)
        stream.sent.clear()
        gate.feed(6, 9)
        assertEquals(listOf(6, 7, 8, 9), stream.sent)
        assertEquals("während des Zuhörens läuft der Erkenner nicht mit", 5, wake.fed)
    }

    @Test fun `bei einer Unterbrechung wird gesammelt und nach dem Neuverbinden nachgereicht`() {
        val gate = gate(null)
        val stream = FakeStream()
        gate.open(stream)
        gate.connected(stream)
        gate.feed(1, 3)
        assertTrue(gate.disconnected(stream))
        gate.feed(4, 6)
        assertEquals(listOf(1, 2, 3), stream.sent)
        gate.connected(stream)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), stream.sent)
    }

    @Test fun `Dauer-Zuhoeren ohne Erkenner sammelt bis die Verbindung steht`() {
        val gate = gate(null)
        val stream = FakeStream()
        gate.open(stream)
        assertEquals(-1, gate.feed(1, 10))
        gate.connected(stream)
        gate.feed(11, 12)
        assertEquals((1..12).toList(), stream.sent)
    }

    @Test fun `Rueckrufe einer abgeloesten Verbindung aendern nichts`() {
        val gate = gate(FakeWake(3, 6))
        gate.feed(1, 3)
        val old = FakeStream()
        gate.open(old)
        assertTrue(gate.isCurrent(old))
        assertEquals("die Bereitschaft gibt die alte Verbindung zum Schließen heraus", old, gate.rest())

        // Ein verspätetes "verbunden" der alten Verbindung: kein Audio, kein Zustandswechsel.
        assertFalse(gate.isCurrent(old))
        assertFalse(gate.connected(old))
        assertFalse(gate.disconnected(old))
        gate.feed(4, 5)
        assertTrue(old.sent.isEmpty())

        // Auch wenn inzwischen eine neue Verbindung im Aufbau ist, bleibt die alte außen vor.
        assertEquals(6, gate.feed(6, 6))
        val fresh = FakeStream()
        gate.open(fresh)
        assertFalse(gate.connected(old))
        assertTrue(old.sent.isEmpty())
        assertTrue(gate.connected(fresh))
    }

    @Test fun `zurueck in der Bereitschaft ist das Gesammelte weg und der Erkenner frisch`() {
        val wake = FakeWake(20, 50)
        val gate = gate(wake)
        gate.feed(1, 20)
        val first = FakeStream()
        gate.open(first)
        gate.feed(21, 30) // Verbindung kam nie zustande
        gate.rest()
        assertEquals("einmal nach dem Auslösen, einmal bei der Rückkehr", 2, wake.resets)

        assertEquals(60, gate.feed(31, 60)) // der Erkenner zählt weiter: sein 50. Stück ist Nummer 60
        val second = FakeStream()
        gate.open(second)
        gate.connected(second)
        assertEquals("nichts von vor der Bereitschaft", (48..60).toList(), second.sent)
        assertTrue(first.sent.isEmpty())
    }

    @Test fun `nach dem Schliessen wird nichts mehr angenommen und der Erkenner ist freigegeben`() {
        val wake = FakeWake(2)
        val gate = gate(wake)
        gate.feed(1, 2)
        val stream = FakeStream()
        gate.open(stream)
        assertEquals(stream, gate.shutdown())
        assertTrue(wake.closed)
        // Das Mikrofon kann noch ein letztes Stück liefern: Es darf den freigegebenen Erkenner nicht erreichen.
        assertEquals(-1, gate.feed(3, 10))
        assertFalse(gate.connected(stream))
        assertFalse("eine neue Verbindung wird abgewiesen", gate.open(FakeStream()))
        assertTrue(stream.sent.isEmpty())
        gate.shutdown() // zweimal schließen ist harmlos
    }

    // ------------------------------------------------------------------------------------------
    // Mit dem echten Weckwort-Erkenner und echten Aufnahmen (ohne die Modelle überspringen sich diese Tests)

    private class Result(val firstUploadedMs: Int, val callStartMs: Int, val wokeMs: Int)

    /** Raumgespräch, dann "Hey Jarvis", dann die Frage; die Verbindung steht [connectMs] nach dem Weckwort. */
    private fun play(connectMs: Int): Result {
        val bytesPerChunk = 3200 // 100 ms, wie das Mikrofon liefert
        val speech = WakeAudio.clip("neg_de_thorsten1.wav")
        val room = speech.copyOfRange(speech.size - 4 * 32000, speech.size) // 4 s Sprache von irgendwem im Raum
        val call = WakeAudio.clip("pos_thorsten_de.wav")                    // "Hey Jarvis", gut eine Sekunde
        val question = speech.copyOfRange(0, 6 * 32000)
        val pcm = room + call + question

        var sent = 0
        val stream = object : Transcriber {
            override fun connect() {}
            override fun sendAudio(pcm: ByteArray, length: Int) { sent++ }
            override fun close() {}
        }
        val gate = AudioGate(WakeAudio.detector(), measure = { _, _ -> })
        var wokeAt = -1
        var firstUploaded = -1
        var index = 0
        while ((index + 1) * bytesPerChunk <= pcm.size) {
            if (gate.onAudio(pcm.copyOfRange(index * bytesPerChunk, (index + 1) * bytesPerChunk), bytesPerChunk)) {
                wokeAt = index
                gate.open(stream)
            }
            if (wokeAt >= 0 && index == wokeAt + connectMs / 100) {
                gate.connected(stream)
                // Gesendet wurde lückenlos das Ende dessen, was bis hierher vom Mikrofon kam.
                firstUploaded = index + 1 - sent
            }
            index++
        }
        gate.shutdown()
        assertTrue("Weckwort nicht erkannt", wokeAt >= 0)
        return Result(firstUploaded * 100, room.size / 32, (wokeAt + 1) * 100)
    }

    @Test fun `echte Aufnahme - von vor dem Weckwort geht hoechstens ein Wimpernschlag hinaus`() {
        val r = play(connectMs = 300)
        val before = r.callStartMs - r.firstUploadedMs
        assertTrue("Hochgeladen wurden $before ms Sprache von VOR dem Weckwort", before <= 300)
        assertTrue("Das Weckwort selbst fehlt in der Übertragung (${-before} ms abgeschnitten)", before >= 0)
    }

    @Test fun `echte Aufnahme - bei langsamer Verbindung geht der Anfang der Frage nicht verloren`() {
        val r = play(connectMs = 4000)
        val lost = r.firstUploadedMs - r.wokeMs
        assertTrue("Die ersten $lost ms nach dem Weckwort wurden nie übertragen", lost <= 0)
    }

    // ------------------------------------------------------------------------------------------

    @Test fun `Rueckrufe erreichen den Listener nur solange die Verbindung die aktuelle ist`() {
        val seen = ArrayList<String>()
        val inner = object : Transcriber.Listener {
            override fun onPartial(text: String, turnProbability: Double?, speaker: String?) { seen += "partial" }
            override fun onCommitted(text: String, speaker: String?, segment: Int?) { seen += "committed" }
            override fun onDiarized(segment: Int, parts: List<SpokenPart>) { seen += "diarized" }
            override fun onSpeakersMerged(from: String, to: String) { seen += "merged" }
            override fun onConnection(connected: Boolean, error: String?, fresh: Boolean) { seen += "connection:$connected:$fresh" }
        }
        var current = true
        val guarded = CurrentOnlyListener(inner) { current }
        fun all() {
            guarded.onPartial("wie hoch", 0.4)
            guarded.onCommitted("wie hoch ist die Zugspitze", null, 1)
            guarded.onDiarized(1, emptyList())
            guarded.onSpeakersMerged("a", "b")
            guarded.onConnection(true, null, fresh = true)
        }
        all()
        assertEquals(listOf("partial", "committed", "diarized", "merged", "connection:true:true"), seen)

        seen.clear()
        current = false // der Dienst ist zurück in der Bereitschaft
        all()
        assertTrue("verspätete Rückrufe kamen durch: $seen", seen.isEmpty())
    }
}
