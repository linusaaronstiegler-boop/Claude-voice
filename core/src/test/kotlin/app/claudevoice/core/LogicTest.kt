package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
