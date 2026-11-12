package app.claudevoice.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die kleinen Anzeige-Helfer und die Farbkontraste. Reine Rechnerei, läuft ohne Android. */
class FormatsTest {

    @Test
    fun `Kuerzel und Text einer Mitschrift-Zeile werden getrennt`() {
        assertEquals(TranscriptLine("ICH", null, "Wie hoch ist die Zugspitze?"), parseTranscriptLine("ICH: Wie hoch ist die Zugspitze?"))
        assertEquals(TranscriptLine("NAH", "S2", "Keine Ahnung."), parseTranscriptLine("NAH S2: Keine Ahnung."))
        assertEquals(TranscriptLine("FERN", "S12", "Zwei Kaffee, bitte."), parseTranscriptLine("FERN S12: Zwei Kaffee, bitte."))
        assertEquals(TranscriptLine("?", null, "Mhm."), parseTranscriptLine("?: Mhm."))
        assertEquals(TranscriptLine("ALFRED", null, "2962 Meter, Sir."), parseTranscriptLine("ALFRED: 2962 Meter, Sir."))
        assertEquals("NAH S2", parseTranscriptLine("NAH S2: Keine Ahnung.").prefix)
    }

    @Test
    fun `Doppelpunkte im Text gehoeren zum Text`() {
        val line = parseTranscriptLine("ICH S1: Er sagte: um 7:30 Uhr geht es los.")
        assertEquals("ICH", line.voice)
        assertEquals("S1", line.speaker)
        assertEquals("Er sagte: um 7:30 Uhr geht es los.", line.text)
    }

    @Test
    fun `Zeile ohne Kuerzel bleibt ganz erhalten`() {
        val raw = "Er sagte: bis morgen"
        assertEquals(TranscriptLine(null, null, raw), parseTranscriptLine(raw))
        assertEquals("", parseTranscriptLine(raw).prefix)
        assertEquals(TranscriptLine(null, null, ""), parseTranscriptLine(""))
    }

    @Test
    fun `Laufzeit in Minuten und ab einer Stunde mit Stunden`() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:07", formatElapsed(7))
        assertEquals("3:05", formatElapsed(185))
        assertEquals("59:59", formatElapsed(3599))
        assertEquals("1:02:09", formatElapsed(3729))
        assertEquals("0:00", formatElapsed(-5)) // Uhr verstellt: nie negativ anzeigen
    }

    @Test
    fun `Status zeigt die Laufzeit nur waehrend zugehoert wird`() {
        assertEquals("Bereit", statusText("Bereit", running = false, startedAtMs = 0, nowMs = 99_000))
        assertEquals("Hört zu (Handy-Mikro) · 3:05", statusText("Hört zu (Handy-Mikro)", running = true, startedAtMs = 1_000, nowMs = 186_000))
    }

    @Test
    fun `Antwortzeit mit deutschem Komma`() {
        assertEquals("Antwort nach 0,8 s", formatLatency(800))
        assertEquals("Antwort nach 0,8 s", formatLatency(849))
        assertEquals("Antwort nach 1,3 s", formatLatency(1_300))
        assertEquals("Antwort nach 0,0 s", formatLatency(0))
        assertEquals("Antwort nach 0,0 s", formatLatency(-40)) // Messfehler: nie negativ anzeigen
        assertEquals("Antwort nach 12 s", formatLatency(12_300))
    }

    @Test
    fun `Zeile unter dem Status nennt was vorhanden ist`() {
        assertNull(metaLine(null, null))
        assertNull(metaLine(null, "  "))
        assertEquals("Antwort nach 0,8 s", metaLine(800, null))
        assertEquals("Zuletzt: übersprungen: hintergrund", metaLine(null, "übersprungen: hintergrund"))
        assertEquals("Antwort nach 0,8 s · Zuletzt: antwortet (kurz)", metaLine(800, "antwortet (kurz)"))
    }

    @Test
    fun `Schweige-Hinweis nur solange die Frist laeuft`() {
        assertNull(mutedText(mutedUntilMs = 0, nowMs = 1_000))
        assertNull(mutedText(mutedUntilMs = 5_000, nowMs = 5_000))
        assertNull(mutedText(mutedUntilMs = 5_000, nowMs = 9_000))
        assertTrue(mutedText(mutedUntilMs = 61_000, nowMs = 1_000)!!.contains("noch 1 Min."))
        assertTrue(mutedText(mutedUntilMs = 601_000, nowMs = 1_000)!!.contains("noch 10 Min."))
        assertTrue(mutedText(mutedUntilMs = 31_000, nowMs = 1_000)!!.contains("noch 1 Min.")) // aufgerundet, nie "0 Min."
    }

    // --- Farben -----------------------------------------------------------------------------
    // Kontrast nach WCAG: (heller + 0,05) / (dunkler + 0,05). Ab 4,5 gilt normale Schrift als gut
    // lesbar, ab 3 genügt es für Rahmen und große Schrift.

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `Schrift hebt sich genug vom Grund ab`() {
        val pairs = listOf(
            "Navy auf Creme" to (Palette.Navy to Palette.Cream),
            "Navy auf Papier" to (Palette.Navy to Palette.Paper),
            "Creme auf Navy (Startknopf, gewaehlte Auswahl)" to (Palette.Cream to Palette.Navy),
            "Navy auf Gold (Stoppknopf)" to (Palette.Navy to Palette.Gold),
            "Nebentext auf Creme" to (Palette.InkSoft to Palette.Cream),
            "Nebentext auf Papier" to (Palette.InkSoft to Palette.Paper),
            "Etikett auf Creme" to (Palette.GoldDeep to Palette.Cream),
            "Etikett auf Papier" to (Palette.GoldDeep to Palette.Paper),
            "Hinweis auf Creme" to (Palette.Alert to Palette.Cream),
        )
        for ((name, colors) in pairs) {
            val ratio = contrast(colors.first, colors.second)
            assertTrue("$name: Kontrast %.2f ist unter 4,5".format(ratio), ratio >= 4.5)
        }
    }

    @Test
    fun `Rahmen sind sichtbar und Gold bleibt Schmuck`() {
        assertTrue(contrast(Palette.Outline, Palette.Cream) >= 3.0)
        assertTrue(contrast(Palette.Outline, Palette.Paper) >= 3.0)
        // Der Grund, warum es GoldDeep gibt: reines Gold wäre als Schrift auf Creme zu blass.
        assertTrue(contrast(Palette.Gold, Palette.Cream) < 3.0)
    }
}
