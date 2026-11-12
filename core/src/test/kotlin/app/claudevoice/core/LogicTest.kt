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

    @Test fun `ein kurzer erster Satz geht sofort hinaus`() {
        val c = SentenceChunker()
        assertEquals(listOf("Nein, Sir."), c.feed("Nein, Sir. Der Mount"))
        assertEquals(emptyList<String>(), c.feed(" Everest ist deutlich höher."))
        assertEquals("Der Mount Everest ist deutlich höher.", c.flush())
    }

    @Test fun `spaetere kurze Saetze werden zusammen gesprochen`() {
        val c = SentenceChunker()
        assertEquals(listOf("Ja."), c.feed("Ja. Genau. Das stimmt so. "))
        assertEquals("Genau. Das stimmt so.", c.flush())
    }

    @Test fun `Abkuerzung mitten in einem langen Satz trennt nicht`() {
        val c = SentenceChunker()
        val text = "Das gilt für sehr viele verschiedene Stoffe, z. B. für Wasser, ca. zwei Liter. "
        assertEquals(listOf(text.trim()), c.feed(text))
    }

    @Test fun `Ordnungszahl und Titel trennen nicht, auch nicht am Anfang`() {
        val c = SentenceChunker()
        assertEquals(listOf("Am 3. Mai, Sir."), c.feed("Am 3. Mai, Sir. "))
        assertEquals(listOf("Dr. Brandt kommt um vier!"), c.feed("Dr. Brandt kommt um vier! "))
    }
}
