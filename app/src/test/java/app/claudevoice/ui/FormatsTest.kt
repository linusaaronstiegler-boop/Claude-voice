package app.claudevoice.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die kleinen Anzeige-Helfer und die Farbkontraste. Reine Rechnerei, läuft ohne Android. */
class FormatsTest {

    @Test
    fun `Messwerte einer Zeile werden kurz und ohne Nachkommastellen gezeigt`() {
        assertEquals("-27 dBFS · 118 Hz", formatMeasurement(-27.4, 118.2))
        assertEquals("-27 dBFS", formatMeasurement(-27.4, null))
        assertEquals("118 Hz", formatMeasurement(null, 118.2))
        assertEquals("", formatMeasurement(null, null))
        // Eine gewöhnliche Zeile aus der Pipeline hat (noch) keine Messwerte.
        assertEquals("", parseTranscriptLine("ICH: Hallo.").measurement)
        assertEquals("-31 dBFS · 205 Hz", TranscriptLine("NAH", null, "Hallo.", -31.0, 205.0).measurement)
    }

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

    @Test
    fun `Felder der Auswahlleiste teilen sich die Breite sinnvoll`() {
        // Alles passt in gleich breite Felder: gleiche Anteile.
        assertEquals(listOf(1f, 1f, 1f), segmentWidths(total = 300, wanted = listOf(80, 100, 90), atLeast = listOf(80, 60, 50)))
        // Ein langes Feld sprengt die Gleichverteilung, in der Summe passt es aber: Anteile nach Textlänge.
        assertEquals(listOf(100f, 180f), segmentWidths(total = 300, wanted = listOf(100, 180), atLeast = listOf(100, 90)))
        // Zu schmal: das Feld aus einem einzigen Wort behält seine Breite, das andere bricht am Leerzeichen um.
        assertEquals(listOf(100f, 160f), segmentWidths(total = 260, wanted = listOf(100, 180), atLeast = listOf(100, 90)))
        // Selbst die längsten Wörter passen nicht: der Platz wird nach Textlänge geteilt (hier 100 zu 180).
        val cramped = segmentWidths(total = 150, wanted = listOf(100, 180), atLeast = listOf(100, 90))
        assertEquals(100f / 180f, cramped[0] / cramped[1], 0.001f)
    }

    @Test
    fun `Anteile der Auswahlleiste ergeben nie mehr als die Breite`() {
        // Die Zeile rechnet vorab mit ganzen Zahlen aus, wie hoch ihre Felder werden. Wären die Anteile
        // zusammen größer als der Platz, nähme sie zu breite Felder an und schnitte umbrochenen Text ab.
        // Gleich breite Felder (alle Anteile 1) sind davon nicht betroffen.
        for (total in listOf(60, 150, 260, 296, 300, 592, 1000)) {
            for ((wanted, atLeast) in listOf(
                listOf(100, 180) to listOf(100, 90),
                listOf(304, 534) to listOf(304, 284),
                listOf(120, 200, 190) to listOf(70, 160, 120),
            )) {
                val shares = segmentWidths(total, wanted, atLeast)
                assertTrue("Anteile muessen positiv sein: $shares", shares.all { it > 0f })
                if (shares.any { it != 1f }) {
                    assertTrue("total=$total wanted=$wanted: Summe ${shares.sum()}", shares.sum() <= total + 0.01f)
                }
            }
        }
    }

    @Test
    fun `Hey Jarvis bleibt beim Umbrechen zusammen`() {
        // Zwischen "Hey" und "Jarvis" steht ein geschütztes Leerzeichen (Zeichen Nummer 160). Man sieht
        // ihm das nicht an; deshalb passt dieser Test auf, dass es beim Bearbeiten nicht verloren geht.
        assertTrue(LABEL_WECKWORT.contains("Hey" + Char(160) + "Jarvis"))
        assertEquals("Dauer-Zuhören · AirPods-Mikro · Mein Gespräch", ListenChoices().summary)
        assertEquals("$LABEL_WECKWORT · Handy-Mikro · Nur ich", ListenChoices(MODE_WECKWORT, bluetoothMic = false, reach = REACH_ICH).summary)
    }

    // --- Farben -----------------------------------------------------------------------------
    // Kontrast nach WCAG: (heller + 0,05) / (dunkler + 0,05). Ab 4,5 gilt normale Schrift als gut
    // lesbar, ab 3 genügt es für Rahmen und große Schrift.
    //
    // Beim Glas ist der Grund hinter einer Schrift keine feste Farbe: er entsteht aus dem Hintergrund,
    // den Lichtscheinen und den durchscheinenden Scheiben darüber. Die Tests legen diese Schichten
    // rechnerisch übereinander ("compositeOver") und prüfen jeweils die ungünstigste Stelle.

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val schemes = listOf("hell" to LightGlass, "dunkel" to DarkGlass)

    /** Legt jede Farbe aus [layers] über jeden Grund aus [grounds]. */
    private fun over(layers: List<Color>, grounds: List<Color>): List<Color> =
        grounds.flatMap { ground -> layers.map { it.compositeOver(ground) } }

    /** Der nackte Hintergrund an seinen Extremstellen: oben, unten, mitten in einem oder beiden Lichtscheinen. */
    private fun backdrops(c: GlassColors): List<Color> = listOf(c.backdropTop, c.backdropBottom).flatMap { base ->
        val cool = c.glowCool.compositeOver(base)
        listOf(base, cool, c.glowWarm.compositeOver(base), c.glowWarm.compositeOver(cool))
    }

    /** Eine Scheibe direkt auf dem Hintergrund, und eine schwebende mit der Notlösung "Tönung statt Unschärfe" darunter. */
    private fun panes(c: GlassColors): List<Color> {
        val fill = listOf(c.glassTop, c.glassBottom)
        return over(fill, backdrops(c)) + over(fill, over(listOf(c.scrim), backdrops(c)))
    }

    private fun assertReadable(what: String, ink: Color, grounds: List<Color>, min: Double = 4.5) {
        val worst = grounds.minOf { contrast(ink, it) }
        assertTrue("$what: Kontrast %.2f ist unter %.1f".format(worst, min), worst >= min)
    }

    @Test
    fun `Schrift hebt sich in beiden Erscheinungsbildern genug vom Grund ab`() {
        for ((name, c) in schemes) {
            val bare = backdrops(c)
            val glass = panes(c)
            // Direkt auf dem Hintergrund: Titel, Mitschrift, Etiketten, Erklärtexte, Hinweise.
            assertReadable("$name: Schrift auf dem Hintergrund", c.ink, bare)
            assertReadable("$name: Nebentext auf dem Hintergrund", c.inkSoft, bare)
            assertReadable("$name: Etikett auf dem Hintergrund", c.label, bare)
            assertReadable("$name: Hinweis auf dem Hintergrund", c.alert, bare)
            // Auf einer Glasscheibe.
            assertReadable("$name: Schrift auf Glas", c.ink, glass)
            assertReadable("$name: Nebentext auf Glas", c.inkSoft, glass)
            assertReadable("$name: Etikett auf Glas", c.label, glass)
            assertReadable("$name: Hinweis auf Glas", c.alert, glass)
            // Im Eingabefeld (eine Vertiefung in der Scheibe): Eingabe, Beschriftung, "Zeigen".
            val well = over(listOf(c.well), glass)
            assertReadable("$name: Schrift im Eingabefeld", c.ink, well)
            assertReadable("$name: Beschriftung im Eingabefeld", c.inkSoft, well)
            assertReadable("$name: Text-Schaltflaeche im Eingabefeld", c.label, well)
            // Auswahlleiste: nicht gewählte Felder auf der Schiene, das gewählte auf dem Daumen.
            val track = over(listOf(c.track), glass)
            assertReadable("$name: nicht gewaehlt auf der Schiene", c.inkSoft, track)
            assertReadable("$name: gewaehlt auf dem Daumen", c.ink, over(listOf(c.thumbTop, c.thumbBottom), track))
            // Glas-Kapseln als Schaltflächen: im Seitenkopf direkt auf dem Hintergrund, sonst auf einer Scheibe.
            assertReadable("$name: Schaltflaeche", c.ink, over(listOf(c.thumbTop, c.thumbBottom), bare + glass))
            // Der große Knopf: ruhend goldgetöntes Glas, beim Zuhören volles Gold. Unter dem ruhenden
            // Knopf liegt im Normalfall der Hintergrund selbst (weichgezeichnet ändert er sich kaum),
            // in der Notlösung ohne Unschärfe zusätzlich die abdeckende Tönung.
            val primary = over(listOf(c.primaryTop, c.primaryBottom), bare + over(listOf(c.scrim), bare))
            assertReadable("$name: Zuhoeren starten", c.onPrimary, primary)
            assertReadable("$name: Stopp", c.onActive, listOf(c.activeTop, c.activeBottom))
            // Dialog (deckend).
            assertReadable("$name: Schrift im Dialog", c.ink, listOf(c.dialog))
            assertReadable("$name: Nebentext im Dialog", c.inkSoft, listOf(c.dialog))
            assertReadable("$name: Schaltflaeche im Dialog", c.label, listOf(c.dialog))
        }
    }

    @Test
    fun `Rahmen der Eingabefelder sind sichtbar und Gold bleibt Schmuck`() {
        for ((name, c) in schemes) {
            // Der Rahmen muss sich vom Feld innen und von der Scheibe außen abheben.
            val glass = panes(c)
            assertReadable("$name: Feldrahmen gegen die Scheibe", c.wellBorder, glass, min = 3.0)
            assertReadable("$name: Feldrahmen gegen das Feld", c.wellBorder, over(listOf(c.well), glass), min = 3.0)
            // Ist das Feld im Fokus, trägt der Rahmen die Farbe der Etiketten.
            assertReadable("$name: Fokusrahmen", c.label, glass, min = 3.0)
        }
        // Der Grund, warum es eine eigene Etikettenfarbe gibt: reines Gold wäre als Schrift auf hellem Grund zu blass.
        assertTrue(contrast(Palette.Gold, LightGlass.backdropTop) < 3.0)
        assertTrue(contrast(LightGlass.label, LightGlass.backdropTop) >= 4.5)
    }

    @Test
    fun `Glas ist durchscheinend wie im Entwurf festgelegt`() {
        // Dunkel: Weiß mit etwa 6 bis 10 Prozent Deckkraft. Hell: Weiß mit etwa 55 bis 70 Prozent.
        // (Ein halbes Prozent Spielraum, weil Farben die Deckkraft nur in 256 Stufen speichern.)
        for (fill in listOf(DarkGlass.glassTop, DarkGlass.glassBottom)) assertTrue(fill.alpha in 0.055f..0.105f)
        for (fill in listOf(LightGlass.glassTop, LightGlass.glassBottom)) assertTrue(fill.alpha in 0.545f..0.705f)
        // Die Kante ist oben heller als unten (Lichtreflex).
        for ((_, c) in schemes) {
            val ground = c.glassTop.compositeOver(c.backdropTop)
            assertTrue(c.rimTop.compositeOver(ground).luminance() > c.rimBottom.compositeOver(ground).luminance())
        }
        // Der dunkle Grund ist ein tiefes Blauschwarz, kein reines Schwarz.
        assertTrue(DarkGlass.backdropBottom.blue > DarkGlass.backdropBottom.red && DarkGlass.backdropBottom.luminance() > 0f)
    }

    @Test fun `der Hinweis zum Mikrofon nennt bei beiden den Haken`() {
        assertTrue(micHint(bluetoothMic = true).contains("wie am Telefon"))
        assertTrue(micHint(bluetoothMic = false).contains("Merken und Stummschalten"))
        assertNotEquals(micHint(true), micHint(false))
    }
}
