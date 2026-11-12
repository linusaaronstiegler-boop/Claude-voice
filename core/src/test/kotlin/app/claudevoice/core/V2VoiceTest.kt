package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceClassifierTest {
    private fun f(level: Double, pitch: Double? = 115.0) = VoiceFeatures(level, pitch, 800)

    @Test fun `AirPods mit Profil trennen eigene Stimme, Gegenueber und Nachbartisch`() {
        val c = VoiceClassifier(MicMode.AIRPODS, VoiceProfile(-18.0, 115.0, 5))
        assertEquals(Voice.ICH, c.classify(f(-20.0)))
        assertEquals(Voice.NAH, c.classify(f(-34.0, 210.0)))
        assertEquals(Voice.FERN, c.classify(f(-47.0, 150.0)))
        assertEquals(Voice.UNKLAR, c.classify(null))
    }

    @Test fun `laute fremde Stimmlage ist nah, aber nicht ich`() {
        val c = VoiceClassifier(MicMode.AIRPODS, VoiceProfile(-18.0, 115.0, 5))
        assertEquals(Voice.NAH, c.classify(f(-19.0, 230.0)))
    }

    @Test fun `ohne Profil dient die lauteste Stimme der Sitzung als Bezug`() {
        val c = VoiceClassifier(MicMode.AIRPODS)
        assertEquals(Voice.ICH, c.classify(f(-20.0)))
        assertEquals(Voice.NAH, c.classify(f(-35.0)))
        assertEquals(Voice.FERN, c.classify(f(-50.0)))
        assertEquals(Voice.ICH, c.classify(f(-21.0)))
    }

    @Test fun `Handy braucht die Tonhoehe fuer ICH`() {
        val known = VoiceClassifier(MicMode.HANDY, VoiceProfile(-25.0, 115.0, 5))
        assertEquals(Voice.ICH, known.classify(f(-28.0, 118.0)))
        assertEquals(Voice.NAH, known.classify(f(-28.0, 200.0)))
        assertEquals(Voice.FERN, known.classify(f(-45.0, 118.0)))
        val unknown = VoiceClassifier(MicMode.HANDY)
        assertEquals(Voice.NAH, unknown.classify(f(-28.0, 118.0)))
    }

    @Test fun `Profil wird aus Anreden gelernt und gemittelt`() {
        val c = VoiceClassifier(MicMode.AIRPODS)
        c.learnOwn(f(-20.0, 110.0))
        c.learnOwn(f(-16.0, 120.0))
        val p = c.profile!!
        assertEquals(-18.0, p.levelDb, 0.01)
        assertEquals(115.0, p.pitchHz!!, 0.01)
        assertEquals(2, p.samples)
    }
}

class ConversationBufferV2Test {
    @Test fun `Mitschrift nennt Alter und Sprecher und markiert die aktuelle Zeile`() {
        val b = ConversationBuffer()
        b.add("wir waren letztes Jahr auf der Zugspitze", Voice.NAH, 1_000)
        b.add("krass", Voice.ICH, 9_000)
        val trigger = Line(21_000, Voice.ICH, "wie hoch ist die eigentlich")
        val text = ConversationBuffer.render(b.snapshot(21_000), trigger)
        assertEquals(
            "[vor 20 s] NAH: wir waren letztes Jahr auf der Zugspitze\n" +
                "[vor 12 s] ICH: krass\n" +
                ">> [jetzt] ICH: wie hoch ist die eigentlich",
            text,
        )
    }

    @Test fun `alte Zeilen fallen aus dem Fenster, Zeichenlimit behaelt die neuesten`() {
        val b = ConversationBuffer(windowMs = 1000, maxChars = 10)
        b.add("alt", Voice.NAH, 0)
        b.add("aaaaaa", Voice.NAH, 900)
        b.add("bbbbbb", Voice.ICH, 950)
        assertEquals(listOf("bbbbbb"), b.snapshot(1500).map { it.text })
    }
}
