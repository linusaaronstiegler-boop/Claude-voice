package app.claudevoice.core

import app.claudevoice.core.eval.EvalParserCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Antwort ohne Kopfzeile: echte Antwort oder Kommentar zur Entscheidung? ([Headerless.isCommentary])
 *
 * Die Prüfung ist aus Messdaten abgeleitet, und die stehen deshalb hier im Test: alle 31 Antworten, bei
 * denen das echte Modell die Kopfzeile vergessen hat ([MeasuredHeaderless]), und alles, was es in denselben
 * 1480 Antworten über seine Entscheidung geschrieben hat. Wer eine Regel ändert, sieht hier sofort, ob
 * dabei eine echte Antwort verloren geht.
 */
class HeaderlessTest {

    /** Lässt den echten ReplyParser lesen, in einem Stück. */
    private fun read(raw: String) = EvalParserCheck.read(raw)

    @Test fun `alle gemessenen Antworten ohne Kopfzeile gelten als Antwort`() {
        assertEquals("so viele Antworten ohne Kopfzeile hat die Messung ergeben", 31, MeasuredHeaderless.REPLIES.size)
        val blocked = MeasuredHeaderless.REPLIES.filter { Headerless.isCommentary(it.raw) }
        assertTrue("als Kommentar gewertet: ${blocked.map { "${it.run}/${it.mode}/${it.id}" }}", blocked.isEmpty())
        // Und durch den Parser: Jede wird als "sprechen" gelesen, mit ihrem ganzen Text und ohne den Vermerk.
        for (reply in MeasuredHeaderless.REPLIES) {
            val v = read(reply.raw)
            val where = "${reply.run}/${reply.mode}/${reply.id}"
            assertTrue(where, v.spoke)
            assertFalse(where, v.commentary)
            assertEquals(where, reply.raw.removeSuffix("SAY kurz").trim(), v.speech.trim())
        }
        // 30 kamen ganz ohne Kopfzeile, eine mit der Kopfzeile als letzter Zeile.
        assertEquals(30, MeasuredHeaderless.REPLIES.count { read(it.raw).offProtocol })
        assertEquals(listOf("kurz"), MeasuredHeaderless.REPLIES.map { read(it.raw) }.filterNot { it.offProtocol }.map { it.length })
    }

    @Test fun `die zwei gemessenen Kommentarzeilen vor einer Kopfzeile gelten als Kommentar`() {
        // So stand es da (A5/korrekt/bef-01): erst der Kommentar, dann die Kopfzeile, dann die Antwort.
        assertTrue(Headerless.isCommentary("SKIP-Entscheidung entfällt, ich antworte."))
        // Und so (A5/korrekt/mus-03), vor einer zweiten Kopfzeile mitten in der Antwort.
        assertTrue(Headerless.isCommentary("Korrektur für die Aussprache:"))
        // Samt dem, was jeweils dahinter stand: Es zählt die erste Zeile.
        assertTrue(Headerless.isCommentary("SKIP-Entscheidung entfällt, ich antworte.\n\nEtwa fünfhunderttausend in der Stadt selbst."))
        assertTrue(Headerless.isCommentary("Korrektur für die Aussprache:\n\nEinundzwanzig Komma neun sieben Kilometer."))
    }

    @Test fun `ein SKIP-Grund ohne das SKIP gilt als Kommentar, in jeder Schreibweise`() {
        // Alle elf Stichwörter, die das Modell in 711 von 712 SKIP-Zeilen geschrieben hat.
        val measured = listOf(
            "sozial", "medien", "unterricht", "hintergrund", "beantwortet", "unklar", "keine-frage", "wiederholung",
            "bruchstück", "organisatorisch", "rhetorisch",
        )
        for (reason in measured) {
            assertTrue(reason, Headerless.isCommentary(reason))
            assertTrue(reason, Headerless.isCommentary("**${reason.uppercase()}**\n"))
            assertTrue(reason, Headerless.isCommentary("\n  ($reason)\nund eine Zeile dahinter"))
        }
        assertTrue(Headerless.isCommentary("Keine Frage."))
        assertTrue(Headerless.isCommentary("keine frage"))
        // Mehr als das Stichwort ist ein Satz, und der kann eine Antwort sein.
        for (answer in listOf("Sozial ist das nicht.", "Unklar, die Quellen widersprechen sich.", "Medien sind Zeitung, Funk und Netz.")) {
            assertFalse(answer, Headerless.isCommentary(answer))
        }
    }

    @Test fun `ein Protokollwort zaehlt nur in Grossbuchstaben und als ganzes Wort`() {
        for (commentary in listOf("Die SKIP-Entscheidung entfällt.", "Entscheidung: SAY kurz", "Kein SKIP nötig.", "(SAY)")) {
            assertTrue(commentary, Headerless.isCommentary(commentary))
        }
        val answers = listOf(
            "Say hello to her.", "SAYONARA heißt auf Wiedersehen.", "Ein ESSAY ist ein Aufsatz.", "Der SKIPPER ist der Kapitän.",
            "Das Kartenspiel heißt Skip-Bo.", "Skipping rope heißt Seilspringen.",
            // So gemessen, mit Kopfzeile (en-04): das englische Wort mitten in einer Antwort.
            "For three years. Say \"I have been working there for three years.\"",
        )
        for (answer in answers) assertFalse(answer, Headerless.isCommentary(answer))
    }

    @Test fun `eine Ankuendigung ist kurz und endet mit dem Doppelpunkt`() {
        for (commentary in listOf("Antwort:", "**Hinweis:**", "Korrektur für die Aussprache :  ", "x".repeat(39) + ":")) {
            assertTrue(commentary, Headerless.isCommentary(commentary))
        }
        val answers = listOf(
            // Ein Doppelpunkt mitten im Satz ist gewöhnlicher Sprechtext (so gemessen: lern-16, mix-13).
            "Idempotent heißt: Dieselbe Anfrage mehrfach geschickt, bewirkt dasselbe wie einmal geschickt.",
            "Weder noch: Der Regionalexpress nach Bamberg fährt heute von Gleis drei.",
            // Über vierzig Zeichen ist es ein Satz, auch wenn er mit einem Doppelpunkt endet.
            "x".repeat(40) + ":",
            ":", "",
        )
        for (answer in answers) assertFalse(answer, Headerless.isCommentary(answer))
    }

    @Test fun `nur die erste Zeile mit Inhalt zaehlt`() {
        // Eine Antwort beginnt mit der Antwort. Was weiter hinten steht, macht sie nicht zum Kommentar.
        assertFalse(Headerless.isCommentary("Zweitausend Meter.\nKorrektur für die Aussprache:\nZweitausend."))
        assertFalse(Headerless.isCommentary("Das Wort ist bekannt.\nSAY hello heißt sag hallo."))
        assertFalse(Headerless.isCommentary("Zweitausend Meter.\nhintergrund"))
        // Leerzeilen und bloße Verzierung davor zählen nicht als erste Zeile.
        assertTrue(Headerless.isCommentary("\n```\nhintergrund\n```"))
        assertFalse(Headerless.isCommentary("\n \n***\n"))
    }

    @Test fun `bekannte Grenze - ein frei formulierter Satz ueber die Entscheidung wird nicht erkannt`() {
        // Davor hatte die alte Regel Angst. In 1480 gemessenen Antworten kam so ein Satz nie vor; es gibt also
        // nichts, woraus sich eine Regel ableiten ließe. Schreibt das Modell doch einmal so, ist der Satz zu
        // hören (ohne Anrede höchstens so lang wie "kurz"). Dieser Test hält das als bewusste Lücke fest.
        assertFalse(Headerless.isCommentary("Das ist nicht an mich gerichtet."))
        assertFalse(Headerless.isCommentary("Entscheidung entfällt, ich antworte."))
    }

    @Test fun `der Parser meldet den Kommentar mit der Entscheidung, in jeder Stueckelung`() {
        val rows = listOf(
            "hintergrund" to true,
            "Korrektur für die Aussprache:\n\nEinundzwanzig Komma neun sieben Kilometer." to true,
            "Entscheidung: SAY kurz\nZweitausend Meter." to true,
            "Wellington. Auckland ist nur die größte Stadt." to false,
            "Zweitausend Meter.\nhintergrund" to false,
        )
        for ((raw, commentary) in rows) for (size in listOf(1, 3, 1000)) {
            val decisions = ArrayList<Decision>()
            val speech = StringBuilder()
            val parser = ReplyParser({ decisions += it }, { speech.append(it) }, {})
            raw.chunked(size).forEach(parser::feed)
            parser.finish()
            assertEquals("$raw [Stück $size]", listOf<Decision>(Decision.Say("", offProtocol = true, commentary = commentary)), decisions)
            // Der Vermerk ändert nichts am Text: Ob er gesprochen wird, entscheidet die Pipeline.
            assertEquals("$raw [Stück $size]", raw, speech.toString())
        }
        // Mit Kopfzeile gibt es den Vermerk nie, auch wenn die Antwort wie ein Kommentar beginnt.
        assertEquals(Decision.Say("kurz"), read("SAY kurz\nhintergrund").let { Decision.Say(it.length, it.offProtocol, it.commentary) })
    }

    @Test fun `mitten in einer langen ersten Zeile entschieden ergibt dasselbe wie am Stueck`() {
        // Nach 500 Zeichen ohne Kopfzeile entscheidet der Parser, auch mitten in der Zeile; er kennt dann nur
        // ihren Anfang. Das Auswertungs-Skript sieht die ganze Zeile. Beide müssen gleich urteilen.
        val long = "Das ist ein langer Absatz ohne Zeilenumbruch, Satz an Satz. ".repeat(12)
        val rows = listOf(
            "Kein SKIP nötig. $long" to true,
            long + "Und ganz am Ende ein SKIP." to false,
            "medien" + " ".repeat(600) + "x" to false,
            " ".repeat(495) + "medien und noch ein ganzer Satz dahinter" to false,
        )
        for ((raw, commentary) in rows) {
            assertEquals(raw.take(40), commentary, Headerless.isCommentary(raw))
            for (size in listOf(1, 7, 10_000)) {
                var decision: Decision? = null
                var decidedAfter = -1
                var fed = 0
                val parser = ReplyParser({ decision = it; decidedAfter = fed }, {}, {})
                for (piece in raw.chunked(size)) {
                    fed += piece.length
                    parser.feed(piece)
                }
                parser.finish()
                assertEquals("${raw.take(40)} [Stück $size]", Decision.Say("", offProtocol = true, commentary = commentary), decision)
                if (size == 1) assertEquals("entschieden wird nach dem 501. Zeichen, nicht erst am Ende", 501, decidedAfter)
            }
        }
    }
}

/**
 * Alle Antworten, bei denen das echte Modell die Kopfzeile vergessen hat, Wort für Wort.
 *
 * Messung: claude-sonnet-5-5 ohne Nachdenken, 185 Fälle, je vier Fassungen der Mitschrift (korrekt,
 * verrauscht, ohne Sprecher-Angaben, Handy-Mikrofon), zwei Fassungen der Anweisungen (Läufe A4 und A5), also
 * 1480 Antworten. 31-mal fehlte die Kopfzeile: 30-mal ganz, einmal stand sie als letzte Zeile hinter der
 * Antwort. Jede der 31 ist eine echte Antwort, 29 davon waren laut Etikett erwünscht.
 */
internal object MeasuredHeaderless {
    class Reply(val run: String, val mode: String, val id: String, val raw: String)

    val REPLIES: List<Reply> = listOf(
        Reply("A4", "handy", "bef-01", "Etwa fünfhundertfünfzigtausend in der Stadt selbst, im Großraum knapp drei Millionen."),
        Reply("A4", "handy", "bef-03", "Zweiunddreißig, mit den vier Weisheitszähnen. Ohne sie sind es achtundzwanzig."), // laut Etikett unerwünscht
        Reply("A4", "handy", "bef-18", "Weil die Sonne nicht die Luft direkt wärmt, sondern den Boden. Oben ist die Luft dünner und speichert kaum Wärme, pro tausend Meter wird es etwa sechs Grad kälter."),
        Reply("A4", "handy", "call-08", "Ein Basispunkt ist ein Hundertstel Prozent. Fünfundzwanzig Basispunkte sind also ein Viertel Prozentpunkt."),
        Reply("A4", "handy", "jar-03", "Zweiundvierzig Kilometer und hundertfünfundneunzig Meter. Beide haben recht: Es ist nicht glatt zweiundvierzig, und zweiundvierzig Komma zwei ist gerundet."),
        Reply("A4", "handy", "lern-14", "Minus zweihundertdreiundsiebzig Komma eins fünf Grad Celsius."),
        Reply("A4", "handy", "solo-05", "Eine Stunde. Lissabon liegt zurzeit eine Stunde hinter Deutschland."), // laut Etikett unerwünscht, mehrdeutig
        Reply("A4", "korrekt", "bef-21", "Neunzehnhundertdrei, den Physik-Nobelpreis, gemeinsam mit Pierre Curie und Henri Becquerel."),
        Reply("A4", "korrekt", "lern-16", "Idempotent heißt: Dieselbe Anfrage mehrfach geschickt, bewirkt dasselbe wie einmal geschickt. Eine wiederholte Bestellung würde also keine Dublette erzeugen."),
        Reply("A4", "korrekt", "mix-08", "Bei normalen Klavieren haben Sie recht: achtundachtzig Tasten ist der Standard. Aber „alle gleich viel“ stimmt nicht, denn ältere Instrumente haben oft nur fünfundachtzig."),
        Reply("A4", "korrekt", "mix-10", "Wellington. Auckland ist nur die größte Stadt."),
        Reply("A4", "korrekt", "mus-09", "Etwa achtundsechzig Komma sechs Zentimeter."),
        Reply("A4", "ohne", "call-03", "403 heißt „Forbidden“: Der Server hat die Seite gefunden, verweigert aber den Zugriff, meist wegen fehlender Berechtigung."),
        Reply("A4", "ohne", "call-14", "Etwa achthundert Lumen, also die mit achthundertsechs."),
        Reply("A4", "ohne", "edge-13", "Drei Teelöffel sind ein Esslöffel."),
        Reply("A4", "ohne", "mix-08", "Beim Klavier hat der mit den achtundachtzig Tasten recht, das ist der Standard, auch bei Wohnzimmerklavieren. Ältere Modelle mit fünfundachtzig gibt es aber tatsächlich. Die Ladenzeit kenne ich nicht."),
        Reply("A4", "ohne", "party-01", "Nein, Agave ist kein Kaktus. Sie gehört zu den Spargelgewächsen, Tequila wird aus der Blauen Agave gemacht."),
        Reply("A4", "verrauscht", "bef-19", "László Krasznahorkai, der Ungar, bekam ihn zweitausendfünfundzwanzig. Den diesjährigen Preis vom Oktober kenne ich allerdings nicht, den müssten Sie nachschlagen."),
        Reply("A4", "verrauscht", "mix-13", "Weder noch: Der Regionalexpress nach Bamberg fährt heute von Gleis drei. Gleis sechs ist der ICE nach München."),
        Reply("A4", "verrauscht", "party-01", "Nein, Tequila wird aus der Blauen Agave gemacht. Die ist kein Kaktus, sondern ein Spargelgewächs."),
        Reply("A4", "verrauscht", "party-07", "Das nehme ich nur von Ihnen selbst an, Sir. Sagen Sie es mir kurz, dann merke ich es mir."),
        Reply("A4", "verrauscht", "party-09", "Nein, das ist das Chinin im Tonic Water. Das leuchtet unter Schwarzlicht blau, Wacholder tut das nicht."),
        Reply("A4", "verrauscht", "party-11", "Skål heißt wörtlich Schale, gemeint ist die Trinkschale, die früher reihum ging."),
        Reply("A4", "verrauscht", "solo-03", "Eine Wärmepumpe holt Wärme aus der Außenluft und pumpt sie ins Heizungswasser, der Strom treibt nur den Transport an.\n\nZuerst saugt ein Ventilator Außenluft über einen Wärmetauscher, in dem ein Kältemittel strömt. Das Mittel verdampft schon bei sehr niedrigen Temperaturen und nimmt so die Wärme der Luft auf, selbst bei null Grad. Dann verdichtet ein Kompressor den Dampf, und dabei steigt die Temperatur deutlich, ähnlich wie bei einer Fahrradpumpe. Im zweiten Wärmetauscher gibt das heiße Gas seine Wärme ans Heizungswasser ab und wird wieder flüssig. Ein Expansionsventil entspannt es, es kühlt stark ab, und der Kreislauf beginnt von vorn. Die Jahresarbeitszahl von dreikommaacht heißt: Aus einer Kilowattstunde Strom werden im Schnitt dreikommaacht Kilowattstunden Wärme."),
        Reply("A4", "verrauscht", "vor-03", "Achtundsiebzig Grad Celsius, genauer achtundsiebzig Komma vier."),
        Reply("A5", "ohne", "call-06", "Kilowatt ist die Leistung, also wie stark ein Gerät gerade zieht. Kilowattstunde ist die verbrauchte Energie: ein Kilowatt, eine Stunde lang. Auf dem Zähler steht die Kilowattstunde."),
        Reply("A5", "ohne", "edge-01", "Ein halb mal Masse mal Geschwindigkeit zum Quadrat. Doppelte Geschwindigkeit heißt also vierfache Energie."),
        Reply("A5", "ohne", "jar-03", "Zweiundvierzig Kilometer und einhundertfünfundneunzig Meter. Der mit den zweiundvierzig Komma zwei liegt gerundet richtig, die glatten zweiundvierzig sind zu kurz."),
        Reply("A5", "ohne", "mus-05", "The Clash. Das Lied ist von neunzehnhundertzweiundachtzig, vom Album \"Combat Rock\"."),
        // Die Kopfzeile als letzte Zeile: Der Text davor ist die Antwort.
        Reply("A5", "ohne", "vor-20", "Yes. Venus averages about four hundred sixty-four degrees Celsius, hotter even than Mercury.\n\nSAY kurz"),
        Reply("A5", "verrauscht", "call-06", "Kilowatt ist die Leistung, also wie stark ein Gerät gerade zieht. Kilowattstunde ist die Energie: ein Kilowatt, eine Stunde lang. Ihr Zähler zählt Kilowattstunden."),
    )

    /**
     * Die zwei gemessenen Antworten, in denen Text UM eine Kopfzeile herum steht, ebenfalls Wort für Wort. Aus
     * ihren Kommentarzeilen sind die Regeln 1 und 3 von [Headerless] abgeleitet. In diesen beiden Antworten
     * selbst kommen die Zeilen aber gar nicht bis dorthin: Die eine beginnt mit SKIP, in der anderen war die
     * Kopfzeile längst gelesen. Darum sorgt der [ReplyParser] selbst dafür, dass von ihnen nur die Antwort zu
     * hören ist (HeaderlessPipelineTest).
     */
    val AROUND_HEADER: List<Reply> = listOf(
        // Ein Kommentar, der mit SKIP beginnt, dann die Kopfzeile, dann die Antwort.
        Reply("A5", "korrekt", "bef-01", "SKIP-Entscheidung entfällt, ich antworte.\n\nSAY kurz\nEtwa fünfhunderttausend in der Stadt selbst, im Großraum knapp drei Millionen."),
        // Das Modell verbessert sich selbst: Ankündigung und eine zweite Kopfzeile mitten in der Antwort.
        Reply(
            "A5", "korrekt", "mus-03",
            "SAY kurz\nEinundzwanzig Komma null97 Kilometer.\n\nKorrektur für die Aussprache:\n\nSAY kurz\n" +
                "Einundzwanzig Komma neun sieben Kilometer, also gerundet einundzwanzig Komma einen Kilometer.",
        ),
    )
}
