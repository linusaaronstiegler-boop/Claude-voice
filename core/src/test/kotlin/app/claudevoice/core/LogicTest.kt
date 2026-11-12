package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationBufferTest {
    @Test fun `alte Zeilen fallen aus dem Zeitfenster`() {
        val b = ConversationBuffer(windowMs = 1000)
        b.add("alt", 0)
        b.add("neu", 900)
        assertEquals("alt\nneu", b.render(950))
        assertEquals("neu", b.render(1500))
    }

    @Test fun `Zeichenlimit behaelt die neuesten Zeilen`() {
        val b = ConversationBuffer(maxChars = 10)
        b.add("aaaaaa", 0); b.add("bbbbbb", 1); b.add("cccc", 2)
        assertEquals("bbbbbb\ncccc", b.render(3))
    }

    @Test fun `leere Aeusserungen werden ignoriert`() {
        val b = ConversationBuffer()
        b.add("   ", 0)
        assertEquals("", b.render(0))
    }
}

class SentenceChunkerTest {
    @Test fun `Saetze kommen einzeln, sobald sie fertig sind`() {
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.feed("Die Zugspitze ist 2962 Meter"))
        assertEquals(listOf("Die Zugspitze ist 2962 Meter hoch."), c.feed(" hoch. Sie liegt"))
        assertEquals(emptyList<String>(), c.feed(" in Bayern."))
        assertEquals("Sie liegt in Bayern.", c.flush())
        assertNull(c.flush())
    }

    @Test fun `kurze Abkuerzungen trennen keinen Satz ab`() {
        val c = SentenceChunker()
        val out = c.feed("Das gilt z. B. für Wasser und für viele andere Stoffe. ")
        assertEquals(listOf("Das gilt z. B. für Wasser und für viele andere Stoffe."), out)
    }
}

class QuestionGateTest {
    @Test fun `kurze Fetzen kosten keinen API-Aufruf`() {
        val claude = FakeClaude()
        assertNull(QuestionGate(claude).decide("", "Ja genau", 0))
        assertEquals(0, claude.classifyCalls.size)
    }

    @Test fun `SKIP heisst keine Antwort`() {
        val claude = FakeClaude(gateReply = { "SKIP" })
        assertNull(QuestionGate(claude).decide("", "Wie geht es dir heute so", 0))
    }

    @Test fun `echte Frage wird ausformuliert zurueckgegeben`() {
        val claude = FakeClaude(gateReply = { "Wie hoch ist die Zugspitze?" })
        val q = QuestionGate(claude).decide("Wir reden über die Zugspitze.", "Wie hoch ist die eigentlich", 0)
        assertEquals("Wie hoch ist die Zugspitze?", q)
        assert(claude.classifyCalls.single().contains("Wir reden über die Zugspitze."))
    }

    @Test fun `dieselbe Frage wird nicht doppelt beantwortet, nach dem Fenster wieder`() {
        val claude = FakeClaude(gateReply = { "Wie hoch ist die Zugspitze?" })
        val gate = QuestionGate(claude, repeatWindowMs = 1000)
        assertEquals("Wie hoch ist die Zugspitze?", gate.decide("", "Wie hoch ist die Zugspitze", 0))
        claude.gateReply = { "Wie hoch ist die Zugspitze genau?" }
        assertNull(gate.decide("", "Wie hoch ist die Zugspitze genau", 500))
        assertEquals("Wie hoch ist die Zugspitze genau?", gate.decide("", "Wie hoch ist die Zugspitze genau", 5000))
    }
}
