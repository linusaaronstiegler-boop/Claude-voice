package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ------------------------------------------------------------------------------------------
    // Wann verlässt das erste Stück den Zerleger? Davon hängt ab, wann der erste Ton zu hören ist.

    /** (Stück, Anzahl der bis dahin eingetroffenen Zeichen); der Rest am Stromende zählt als Länge + 1. */
    private fun stream(text: String): List<Pair<String, Int>> {
        val c = SentenceChunker()
        val out = ArrayList<Pair<String, Int>>()
        text.forEachIndexed { i, ch -> c.feed(ch.toString()).forEach { out += it to i + 1 } }
        c.flush()?.let { out += it to text.length + 1 }
        return out
    }

    /** Derselbe Weg wie in der Pipeline: ReplyParser -> SentenceChunker. */
    private fun viaParser(reply: String): List<Pair<String, Int>> {
        val c = SentenceChunker()
        val out = ArrayList<Pair<String, Int>>()
        var fed = 0
        val parser = ReplyParser({}, { d -> c.feed(d).forEach { out += it to fed } }, {})
        reply.forEach { ch -> fed++; parser.feed(ch.toString()) }
        parser.finish()
        c.flush()?.let { out += it to reply.length + 1 }
        return out
    }

    @Test fun `das Ergebniswort am Anfang geht hinaus, sobald der naechste Satz beginnt`() {
        assertEquals(listOf("Nein."), SentenceChunker().feed("Nein. Ein Wal"))
        assertEquals(listOf("No."), SentenceChunker().feed("No. A whale"))
        // "SAY mittel\nNo. " sind fünfzehn Zeichen: Mit dem Leerzeichen hinter dem Punkt ist "No." unterwegs.
        assertEquals("No." to 15, viaParser("SAY mittel\nNo. A whale is a mammal. It breathes air through lungs.").first())
        assertEquals("Das war 1989.", stream("Das war 1989. Danach fiel die Mauer.").first().first)
        assertEquals("Das ist eine Art.", stream("Das ist eine Art. Sie kommt nur in Europa vor.").first().first)
    }

    @Test fun `ein Buchstabe als Name beendet den Satz, ein Initial nicht`() {
        assertEquals("Das ist Vitamin C.", stream("Das ist Vitamin C. Es steckt vor allem in Zitrusfrüchten.").first().first)
        assertEquals("Dann bleibt nur Plan B.", stream("Dann bleibt nur Plan B. Der ist aber teurer.").first().first)
        assertEquals("It's hepatitis B.", stream("It's hepatitis B. There is a vaccine.").first().first)
        // Initialen und Abkürzungen bleiben im Satz, sonst entstünde mitten im Namen eine Pause.
        for (text in listOf(
            "Das war John F. Kennedy, Sir.", "Geschrieben hat es J. K. Rowling.", "Das gilt z. B. für Wasser.",
            "Das Buch ist von C. S. Lewis.", "Er heißt Hans B. Müller mit Künstlernamen.",
        )) assertEquals(text, listOf(text to text.length + 1), stream(text))

        fun ends(text: String) = SpokenText.dotEndsSentence(text, text.lastIndexOf('.'))
        for (text in listOf("Das ist Vitamin C.", "Nimm Plan B.", "Er hat Blutgruppe A.", "That is vitamin D."))
            assertEquals(text, true, ends(text))
        for (text in listOf("John F.", "von J.", "J. K.", "Das gilt z.", "Das gilt z. B.", "Vitamin c.", "Der Plan, B."))
            assertEquals(text, false, ends(text))
    }

    @Test fun `ein Name mit Initial wird auch hinter einem Wort wie Fall oder Team nicht zerrissen`() {
        // Das Wort davor allein entscheidet nicht: Erst wenn danach erkennbar ein neuer Satz beginnt, endet der alte.
        for ((text, first) in listOf(
            "Der Fall D. B. Cooper ist bis heute ungelöst. Niemand weiß, wer er war." to "Der Fall D. B. Cooper ist bis heute ungelöst.",
            "Der Fall O. J. Simpson war ein Mordprozess. Er endete mit einem Freispruch." to "Der Fall O. J. Simpson war ein Mordprozess.",
            "At that point J. R. R. Tolkien was a professor at Oxford. He wrote in his spare time." to
                "At that point J. R. R. Tolkien was a professor at Oxford.",
            "For his part J. Edgar Hoover denied everything. Nobody believed him." to "For his part J. Edgar Hoover denied everything.",
            "The letter J. K. Rowling wrote went viral. It was short." to "The letter J. K. Rowling wrote went viral.",
            "Im Team A. Müller und B. Schmidt spielen zwei Brüder. Beide sind Stürmer." to
                "Im Team A. Müller und B. Schmidt spielen zwei Brüder.",
            // Auch hinter einem Wort aus der Liste: Auf den Buchstaben folgt ein Name, kein Satzanfang.
            "In der Klasse A. Müllers sind dreißig Kinder. Das ist viel." to "In der Klasse A. Müllers sind dreißig Kinder.",
            "Die Gruppe B. B. King and Friends spielt heute. Karten gibt es noch." to "Die Gruppe B. B. King and Friends spielt heute.",
            "That is the model J. Crew uses for its catalog. It sells well." to "That is the model J. Crew uses for its catalog.",
        )) assertEquals(text, first, stream(text).first().first)

        // Die Entscheidung fällt erst, wenn das Wort nach dem Buchstaben vollständig da ist.
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.feed("Dann bleibt nur Plan B. "))
        assertEquals(emptyList<String>(), c.feed("Der"))
        assertEquals(listOf("Dann bleibt nur Plan B."), c.feed(" ist aber teurer."))
        assertEquals("Der ist aber teurer.", c.flush())
        // Ein Zeilenumbruch nach dem Buchstaben beendet den Satz in jedem Fall.
        assertEquals(listOf("Das ist Vitamin C."), SentenceChunker().feed("Das ist Vitamin C.\nZitronen"))

        fun ends(text: String, dot: Int) = SpokenText.dotEndsSentence(text, dot)
        val planB = "Nimm Plan B. Der ist besser."
        assertEquals(true, ends(planB, planB.indexOf('.')))
        assertEquals(true, ends("Nimm „Plan B.“ Das geht.", 12))
        assertEquals(true, ends("Take plan B. I think it works.", 11))
        // Noch offen (das nächste Wort fehlt oder ist nicht zu Ende) oder kein Satzanfang: nein.
        for (text in listOf("Nimm Plan B. ", "Nimm Plan B. Der", "Nimm Plan B. Müller sagt das.", "Nimm Plan B. I. Müller", "Nimm Plan B. 5 Leute"))
            assertEquals(text, false, ends(text, text.indexOf('.')))
    }

    @Test fun `Satzende vor einem schliessenden Anfuehrungszeichen wird erkannt`() {
        val quoted = stream("Er sagte: \"Ich bin ein Berliner.\" Das war in Berlin. Danach jubelten alle.")
        assertEquals("Er sagte: \"Ich bin ein Berliner.\"", quoted.first().first)
        assertEquals(
            listOf("Sie rief: „Halt!“", "Dann blieb er stehen."),
            stream("Sie rief: „Halt!“ Dann blieb er stehen.").map { it.first },
        )
        assertEquals("Das stimmt (wirklich.)", stream("Das stimmt (wirklich.) Danach kam nichts mehr.").first().first)
        // Geht es klein weiter, läuft der Satz noch: keine Pause mitten im Satz.
        for (text in listOf("Er rief \"Halt!\" und rannte los.", "Sie fragte „Warum?“ und ging dann weiter."))
            assertEquals(text, listOf(text), stream(text).map { it.first })
        // Kein Text geht verloren, auch wenn das Zeichen dahinter erst später eintrifft.
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.feed("Er sagte: \"Ja.\""))
        assertEquals(listOf("Er sagte: \"Ja.\""), c.feed(" Dann"))
        assertEquals("Dann", c.flush())
    }

    @Test fun `ein langer erster Satz bleibt ein Stueck`() {
        // Ein Satz wird nicht an einem Komma geteilt, auch wenn er lang ist: Die Pipeline zählt jedes Stück
        // als Satz, ihr Längendeckel würde die zweite Hälfte verwerfen, und die Naht wäre zu hören.
        for (text in listOf(
            "Idempotent heißt: Dieselbe Anfrage mehrfach geschickt ergibt dasselbe Ergebnis wie einmal, also keine doppelten Bestellungen.",
            "Fünfundfünfzig Zoll sind rund einhundertvierzig Zentimeter Diagonale, das Bild ist etwa " +
                "einhundertzweiundzwanzig Zentimeter breit.",
            "Die Zugspitze ist mit zweitausendneunhundertzweiundsechzig Metern der höchste Berg Deutschlands " +
                "und liegt an der Grenze zu Österreich, Sir.",
            "Hoch dreiundzwanzig: Die Konstante ist sechs Komma null zwei mal zehn hoch dreiundzwanzig, " +
                "mal null Komma fünf also etwa drei mal zehn hoch dreiundzwanzig.",
            "Die Photosynthese ist der Vorgang, bei dem Pflanzen mit Hilfe von Licht aus Wasser und " +
                "Kohlendioxid Zucker und Sauerstoff herstellen, wobei das Licht vom Blattgrün eingefangen wird, " +
                "das in den Chloroplasten sitzt, und die Energie dann in mehreren Schritten gespeichert wird.",
            // Über 200 Zeichen ohne Komma, und das Datum liegt genau dort, wo früher hart getrennt wurde.
            "Er wurde in einem kleinen Dorf in der Nähe von München geboren und zog dann mit seinen Eltern nach Hamburg " +
                "wo er zur Schule ging und danach nach Berlin wo er dann nach langem Suchen endlich am 12. November 1970 " +
                "seine erste Stelle antrat und blieb.",
        )) {
            assertTrue("zu kurz für diese Prüfung: $text", text.length > 120)
            assertEquals(listOf(text), stream(text).map { it.first })
            assertEquals(listOf(text), viaParser("SAY kurz\n$text").map { it.first })
            // Folgt ein zweiter Satz, geht der erste hinaus, sobald das Leerzeichen hinter seinem Punkt da ist.
            assertEquals(text to text.length + 1, stream("$text Danach kommt mehr.").first())
        }
    }

    @Test fun `ein Stueck ohne Buchstaben und Ziffern wird nicht gesprochen`() {
        // Code-Block um die Antwort: Der Zaun am Ende ist kein Satz.
        assertEquals(listOf("Zweitausend Meter."), stream("Zweitausend Meter.\n```").map { it.first })
        assertEquals(listOf("Zweitausend Meter."), viaParser("```\nSAY kurz\nZweitausend Meter.\n```").map { it.first })
        assertEquals(listOf("Zweitausend Meter."), viaParser("SAY kurz!\nZweitausend Meter.").map { it.first })
        assertEquals(listOf("Ja.", "Gut."), stream("Ja.\n...\nGut.").map { it.first })
        assertEquals(emptyList<String>(), stream("!\n?!\n — \n***").map { it.first })
        assertNull(SentenceChunker().apply { feed("```") }.flush())
        // Ein solches Stück zählt auch nicht als erster Satz: Der echte erste Satz geht trotzdem sofort hinaus.
        val c = SentenceChunker()
        assertEquals(listOf("Ja."), c.feed("... \nJa. Und"))
        // Eine einzelne Ziffer oder ein einzelner Buchstabe ist dagegen sprechbar.
        assertEquals(listOf("5", "A"), stream("5\nA").map { it.first })
    }

    @Test fun `in keiner Stueckelung geht Text verloren oder kommt doppelt`() {
        val texts = listOf(
            "Nein, Sir. Ein Wal ist ein Säugetier, er atmet mit Lungen. Fische atmen mit Kiemen.",
            "Das war am 3. Mai 1989, z. B. in Berlin. Danach fiel die Mauer. Ja! Wirklich? Ja.",
            "Er sagte: \"Ich bin ein Berliner.\" Das war in Berlin. Sie rief „Halt!“ und rannte los.",
            "Das ist Vitamin C. Es steckt in Zitronen.\nZweite Zeile ohne Punkt\n\nDritte Zeile.",
            "Die Photosynthese ist der Vorgang, bei dem Pflanzen mit Hilfe von Licht aus Wasser und Kohlendioxid Zucker " +
                "und Sauerstoff herstellen, wobei das Licht vom Blattgrün eingefangen wird. Das war es.",
            "Ein sehr langer Satz ohne jedes Satzzeichen der einfach immer weiter und weiter läuft ".repeat(4),
            "... und dann? Nichts... wirklich nichts. ",
            "```\nZweitausend Meter.\n```\n",
            "",
        )
        val random = kotlin.random.Random(7)
        // Verglichen werden die Wörter: Was weder Buchstaben noch Ziffern enthält ("...", "```"), wird nicht gesprochen.
        fun normal(s: String) = s.trim().split(Regex("\\s+")).filter { w -> w.any { it.isLetterOrDigit() } }.joinToString(" ")
        for (text in texts) repeat(40) { round ->
            val c = SentenceChunker()
            val out = ArrayList<String>()
            var at = 0
            while (at < text.length) {
                // Runde 0: Zeichen für Zeichen, wie der ReplyParser liefert; danach zufällige Stücke.
                val n = if (round == 0) 1 else minOf(text.length - at, 1 + random.nextInt(40))
                out += c.feed(text.substring(at, at + n))
                at += n
            }
            c.flush()?.let { out += it }
            assertEquals("Runde $round: $text", normal(text), out.joinToString(" ") { normal(it) })
            assertEquals("Stück ohne Sprechbares in Runde $round: $text", 0, out.count { piece -> piece.none { it.isLetterOrDigit() } })
        }
    }
}
