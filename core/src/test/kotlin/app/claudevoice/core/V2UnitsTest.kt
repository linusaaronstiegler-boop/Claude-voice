package app.claudevoice.core

import app.claudevoice.core.Heuristics.Heat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoiceMeterTest {
    @Test fun `tiefe und hohe Stimme werden an der Tonhoehe unterschieden`() {
        val meter = VoiceMeter()
        meter.feed(silence(300), 9600)
        val low = tone(110.0, 8000.0, 600)
        meter.feed(low, low.size)
        val a = meter.takeSummary()!!
        assertTrue("war ${a.pitchHz}", abs(a.pitchHz!! - 110.0) < 4.0)

        val high = tone(220.0, 8000.0, 600)
        meter.feed(high, high.size)
        val b = meter.takeSummary()!!
        assertTrue("war ${b.pitchHz}", abs(b.pitchHz!! - 220.0) < 6.0)
    }

    @Test fun `naehere Stimme ist messbar lauter`() {
        val meter = VoiceMeter()
        meter.feed(silence(300), 9600)
        val near = tone(120.0, 12000.0, 500)
        meter.feed(near, near.size)
        val a = meter.takeSummary()!!
        meter.feed(silence(300), 9600)
        val far = tone(120.0, 1200.0, 500)
        meter.feed(far, far.size)
        val b = meter.takeSummary()!!
        // Zehnfache Amplitude entspricht 20 dB.
        assertTrue("Abstand war ${a.levelDb - b.levelDb}", abs((a.levelDb - b.levelDb) - 20.0) < 1.5)
    }

    @Test fun `Stille ergibt keine Merkmale`() {
        val meter = VoiceMeter()
        meter.feed(silence(1000), 32000)
        assertNull(meter.takeSummary())
    }

    @Test fun `ungerade Stueckgroessen verschieben die Samples nicht`() {
        val meter = VoiceMeter()
        meter.feed(silence(300), 9600)
        val t = tone(150.0, 8000.0, 600)
        var i = 0
        while (i < t.size) { // absichtlich krumme Häppchen
            val n = minOf(333, t.size - i)
            meter.feed(t.copyOfRange(i, i + n), n)
            i += n
        }
        val f = meter.takeSummary()
        assertNotNull(f)
        assertTrue("war ${f!!.pitchHz}", abs(f.pitchHz!! - 150.0) < 5.0)
    }
}

class HeuristicsTest {
    @Test fun `Name wird auch verhoert erkannt`() {
        for (s in listOf("Alfred, wie spät ist es", "alfret wie hoch ist das", "all fred sag mal", "Hey Alfreds Meinung dazu")) {
            assertTrue(s, Heuristics.mentionsName(s, "Alfred"))
        }
        for (s in listOf("das ist alles frei", "Albert hat angerufen heute", "alle freuen sich")) {
            assertFalse(s, Heuristics.mentionsName(s, "Alfred"))
        }
    }

    @Test fun `Fragen und Wissenswuensche sind HOT`() {
        val hot = listOf(
            "Wie hoch ist die Zugspitze?", "wer hat eigentlich das Telefon erfunden", "ist ein Wal ein Fisch",
            "ich frag mich wie lange das Licht zur Erde braucht", "weiß jemand wann die Mauer gefallen ist",
            "keine Ahnung wie das Ding heißt", "what's the capital of Australia", "und wer hat das dann gebaut",
            "Alfred merk dir das Kennzeichen", "das war doch 1989 oder nicht?",
        )
        for (s in hot) assertEquals(s, Heat.HOT, Heuristics.heat(s, "Alfred"))
    }

    @Test fun `Fuellsel ist COLD und laengere Aussagen sind MAYBE`() {
        for (s in listOf("ja genau", "okay", "na dann", "haha stimmt", "")) assertEquals(s, Heat.COLD, Heuristics.heat(s, "Alfred"))
        assertEquals(Heat.MAYBE, Heuristics.heat("ich hab gestern den ganzen Abend an dem Aufsatz gesessen", "Alfred"))
    }

    @Test fun `Stoppbefehl braucht Namen und Stoppwort`() {
        assertTrue(Heuristics.isStopCommand("Alfred, stopp", "Alfred"))
        assertTrue(Heuristics.isStopCommand("alfret sei bitte still", "Alfred"))
        assertFalse(Heuristics.isStopCommand("stopp mal kurz", "Alfred"))
        assertFalse(Heuristics.isStopCommand("Alfred, wie stoppt man einen Zug am schnellsten ohne Bremse", "Alfred"))
    }
}

class ReplyParserTest {
    private class Sink {
        val decisions = mutableListOf<Decision>()
        val speech = StringBuilder()
        val directives = mutableListOf<Directive>()
        val parser = ReplyParser({ decisions += it }, { speech.append(it) }, { directives += it })
        fun feedInPieces(text: String, size: Int) {
            text.chunked(size).forEach(parser::feed)
            parser.finish()
        }
    }

    @Test fun `SKIP beendet sofort und spricht nichts`() {
        val s = Sink()
        s.parser.feed("SKIP hinter")
        s.parser.feed("grund\nDas darf nie gesprochen werden.")
        s.parser.finish()
        assertEquals(listOf<Decision>(Decision.Skip("hintergrund")), s.decisions)
        assertEquals("", s.speech.toString())
    }

    @Test fun `SKIP ohne Zeilenende am Stromende`() {
        val s = Sink()
        s.feedInPieces("SKIP", 2)
        assertEquals(listOf<Decision>(Decision.Skip("")), s.decisions)
    }

    @Test fun `SAY liefert Sprechtext in jeder Stueckelung gleich`() {
        val text = "SAY kurz\n2962 Meter. Sie ist der höchste Berg Deutschlands.\n"
        for (size in listOf(1, 2, 3, 7, 100)) {
            val s = Sink()
            s.feedInPieces(text, size)
            assertEquals("Stückgröße $size", listOf<Decision>(Decision.Say("kurz")), s.decisions)
            assertEquals("Stückgröße $size", "2962 Meter. Sie ist der höchste Berg Deutschlands.\n", s.speech.toString())
        }
    }

    @Test fun `MEMO und MUTE werden ausgefuehrt und nicht vorgelesen`() {
        val text = "SAY kurz\nNotiert, Sir.\nMEMO: Kennzeichen M-AB 1234\nMUTE: 10"
        for (size in listOf(1, 4, 100)) {
            val s = Sink()
            s.feedInPieces(text, size)
            assertEquals("Notiert, Sir.\n", s.speech.toString())
            assertEquals(listOf(Directive.Memo("Kennzeichen M-AB 1234"), Directive.Mute(10)), s.directives)
        }
    }

    @Test fun `Wort das wie eine Anweisung beginnt wird normal gesprochen`() {
        val s = Sink()
        s.feedInPieces("SAY mittel\nMEMORY ist das englische Wort für Gedächtnis.\nMUT braucht man dafür nicht.", 3)
        assertEquals("MEMORY ist das englische Wort für Gedächtnis.\nMUT braucht man dafür nicht.", s.speech.toString())
        assertTrue(s.directives.isEmpty())
    }

    @Test fun `Antwort ohne Kopfzeile wird trotzdem gesprochen`() {
        val s = Sink()
        s.feedInPieces("Die Zugspitze ist 2962 Meter hoch.", 5)
        assertEquals(listOf<Decision>(Decision.Say("", offProtocol = true)), s.decisions)
        assertEquals("Die Zugspitze ist 2962 Meter hoch.", s.speech.toString())
    }
}
