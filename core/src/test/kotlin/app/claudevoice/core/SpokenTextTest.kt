package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tabellengetriebener Test: links, was das Modell schreiben könnte, rechts, was die Stimme lesen soll.
 * Eine Tabelle je Thema; schlägt eine Zeile fehl, nennt die Meldung alle falschen Zeilen auf einmal.
 */
class SpokenTextTest {

    private fun check(rows: List<Pair<String, String>>) {
        val wrong = rows.mapNotNull { (input, expected) ->
            val actual = SpokenText.clean(input)
            if (actual == expected) null else "\n  Eingabe:  $input\n  erwartet: $expected\n  bekommen: $actual"
        }
        assertTrue("${wrong.size} von ${rows.size} Zeilen falsch:${wrong.joinToString("")}", wrong.isEmpty())
    }

    @Test fun `Grundzahlen bis zu den Milliarden`() = check(CARDINALS)
    @Test fun `Kommazahlen, Vorzeichen und Aufzaehlungen`() = check(DECIMALS)
    @Test fun `Jahreszahlen und Jahrzehnte`() = check(YEARS)
    @Test fun `Uhrzeiten und Datumsangaben`() = check(TIMES_AND_DATES)
    @Test fun `Ordnungszahlen nur wo es eindeutig ist`() = check(ORDINALS)
    @Test fun `Einheiten, Waehrungen und die gebeugte Eins`() = check(UNITS)
    @Test fun `Abkuerzungen`() = check(ABBREVIATIONS)
    @Test fun `Markdown, Klammern und Sonderzeichen`() = check(MARKDOWN)
    @Test fun `heikle Faelle`() = check(NASTY)
    @Test fun `englische Saetze bekommen englische Zahlwoerter`() = check(ENGLISH)
    @Test fun `englische Titel in deutschen Saetzen aendern nichts`() = check(MIXED)

    // Die folgenden Tabellen stammen aus der Gegenprüfung: Fälle, die vorher falsch oder verstümmelt klangen.
    @Test fun `deutsche Saetze mit englisch aussehenden Woertern bleiben deutsch`() = check(LANGUAGE_TRAPS)
    @Test fun `Ordnungszahlen in Aufzaehlungen, nach Begleitern und am Satzende`() = check(ORDINALS_MORE)
    @Test fun `Bereiche rueckwaerts, Rechnungen und Spielstaende mit Strich`() = check(RANGES_AND_MINUS)
    @Test fun `Zeitspannen, Dauern und Spielstaende mit Doppelpunkt`() = check(TIMES_MORE)
    @Test fun `Kommazahlen mit englischem Punkt vor einer Einheit`() = check(DOT_DECIMALS)
    @Test fun `weitere Schreibweisen fuer Einheiten, Preise und Telefonnummern`() = check(NOTATIONS)
    @Test fun `Datumsbereiche und abgekuerzte Monate`() = check(DATES_MORE)
    @Test fun `Brueche vor Einheiten und gemischte Zahlen`() = check(FRACTIONS_MORE)

    @Test fun `die Tabellen haben mindestens achtzig Zeilen`() {
        assertTrue("nur ${ALL.size} Zeilen", ALL.size >= 80)
    }

    @Test fun `zwei Zifferngruppen verschmelzen nie zu einer Tausenderzahl`() {
        // "384-322 v. Chr." wurde einmal zu "384 322" und damit zu dreihundertvierundachtzigtausend...
        // Egal welche zwei Zahlen und welcher Strich: Das Wort "tausend" darf nur entstehen, wenn eine der
        // beiden Zahlen selbst vierstellig ist.
        for (a in listOf(5, 24, 100, 322, 384, 600, 800, 999)) for (b in listOf(1, 7, 99, 100, 322, 600, 999)) {
            for (dash in listOf("-", "–", " – ", " - ")) {
                val out = SpokenText.clean("Das waren $a$dash$b Leute.")
                assertTrue("\"$a$dash$b\" wurde zu \"$out\"", !out.contains("tausend"))
            }
        }
    }

    @Test fun `der Punkt hinter Tag, Buchstabe oder Abkuerzung beendet keinen Satz`() {
        // (Text, Stelle des Punkts) -> endet dort ein Satz?
        fun ends(text: String) = SpokenText.dotEndsSentence(text, text.lastIndexOf('.'))
        for (text in listOf(
            "Heute ist Donnerstag, der 12.", "Das war am 12.11.", "Der 1.", "Zum 100.", "Das gilt z.", "Das gilt z. B.",
            "Er ist krank, d.", "Der Weg ist insgesamt ca.", "Nimm die Nr.", "Es sind 5 Mio.", "Im Jahr 44 v. Chr.",
            "Frag Dr.", "Am 12. Nov.", "J. K.", "Es dauert 20 Min.",
        )) assertTrue("Satzende erkannt, wo keines ist: $text", !ends(text))
        for (text in listOf(
            "Die Mauer fiel 1989.", "Das stimmt.", "Äpfel, Birnen usw.", "Er heißt Max.", "Es sind 2962 Meter.",
            "Am 12.11.2026.", "Und so weiter etc.", "Ja.",
        )) assertTrue("Satzende übersehen: $text", ends(text))
        // Kein Punkt an der Stelle oder Stelle außerhalb: Dann entscheidet der Aufrufer wie bisher.
        assertTrue(SpokenText.dotEndsSentence("Ja!", 2))
        assertTrue(SpokenText.dotEndsSentence("Ja.", 7))
        assertTrue(SpokenText.dotEndsSentence("", 0))
    }

    @Test fun `mit der Rueckfrage beim Zerlegen bleiben Datum und Abkuerzung in einem Satz`() {
        // So wie SentenceChunker.feed trennt (Punkt + Leerraum, mindestens 25 Zeichen), nur mit der
        // zusätzlichen Frage an SpokenText.dotEndsSentence. Ohne sie käme "… der 12." und "November …" an.
        fun split(text: String): List<String> {
            val out = ArrayList<String>()
            var start = 0
            for (i in 0 until text.length - 1) {
                val c = text[i]
                val ends = (c == '.' || c == '!' || c == '?') && text[i + 1].isWhitespace() &&
                    (c != '.' || SpokenText.dotEndsSentence(text, i))
                if (ends && i + 1 - start >= 25) {
                    out += text.substring(start, i + 1).trim()
                    start = i + 1
                }
            }
            return out + text.substring(start).trim()
        }
        assertEquals(
            listOf("Heute ist Donnerstag, der zwölfte November zweitausendsechsundzwanzig.", "Morgen ist dann schon Freitag."),
            split("Heute ist Donnerstag, der 12. November 2026. Morgen ist dann schon Freitag.").map(SpokenText::clean),
        )
        assertEquals(
            listOf("Das gilt für sehr viele Stoffe, zum Beispiel für Wasser.", "Bei Öl ist es anders."),
            split("Das gilt für sehr viele Stoffe, z. B. für Wasser. Bei Öl ist es anders.").map(SpokenText::clean),
        )
        assertEquals(
            listOf("Der Weg dorthin ist insgesamt circa drei Kilometer lang.", "Das schaffst du."),
            split("Der Weg dorthin ist insgesamt ca. 3 km lang. Das schaffst du.").map(SpokenText::clean),
        )
    }

    @Test fun `absurd lange Ziffernketten werfen nichts und kommen unveraendert zurueck`() {
        // Reguläre Ausdrücke arbeiten sich rekursiv durch "1.1.1.1…" und sprengen bei tausenden Gruppen
        // den Aufrufstapel. Ein kleiner Stapel macht das hier sicher reproduzierbar. Ohne das Auffangen
        // in clean() flöge ein StackOverflowError bis in die Pipeline.
        val inputs = listOf("1.".repeat(30_000), "12-".repeat(20_000), "0" + "12 ".repeat(20_000), "(" + ", ".repeat(20_000) + ")")
        val results = arrayOfNulls<Any>(inputs.size)
        val worker = Thread(null, {
            inputs.forEachIndexed { i, input ->
                results[i] = try { SpokenText.clean(input) } catch (t: Throwable) { t }
            }
        }, "kleiner-stapel", 256 * 1024)
        worker.start()
        worker.join()
        inputs.forEachIndexed { i, input -> assertEquals(input.trim(), results[i]) }
    }

    @Test(timeout = 10_000) fun `lange Ziffernfolgen hinter einem Plus kosten keine Rechenzeit`() {
        // Der Ausdruck für Telefonnummern probierte früher jede Aufteilung der Ziffern durch, wenn das Ende
        // nicht passte: 90 Zeichen dauerten 15 Sekunden, 130 Zeichen Minuten. In der Sprachkette hieße das Stille.
        for (digits in listOf(40, 80, 400)) {
            val out = SpokenText.clean("Ruf +" + "12".repeat(digits) + ":3 an")
            assertTrue(out, out.none { it.isDigit() })
            SpokenText.clean("+" + "1".repeat(digits) + ",5")
        }
    }

    @Test fun `Ziffern anderer Schriften bringen nichts zum Absturz`() {
        // Auf Android zählt "\d" solche Zeichen als Ziffern, auf dem Rechner nicht. Hier lässt sich deshalb
        // nur prüfen, dass nichts geworfen wird und der Rest des Satzes trotzdem bereinigt ist.
        val arabic = SpokenText.clean("Das sind ٣,٥ % und 5 €.")
        assertTrue(arabic, arabic.endsWith("Prozent und fünf Euro."))
        val wide = SpokenText.clean("Ruf ０１７１ an, es sind 12 Leute.")
        assertTrue(wide, wide.endsWith("es sind zwölf Leute."))
    }

    @Test fun `Text ohne Ziffern und Symbole bleibt gleich`() {
        for (text in PLAIN) assertEquals(text, SpokenText.clean(text))
    }

    @Test fun `nur der Leerraum wird zusammengezogen`() {
        assertEquals("Das ist viel Platz.", SpokenText.clean("  Das   ist\n  viel\tPlatz.  "))
        assertEquals("", SpokenText.clean("   "))
        assertEquals("", SpokenText.clean(""))
    }

    @Test fun `ein zweiter Durchlauf aendert nichts`() {
        for ((input, _) in ALL) {
            val once = SpokenText.clean(input)
            assertEquals("nicht idempotent bei: $input", once, SpokenText.clean(once))
        }
    }

    @Test fun `fuer die Tabellen genuegt schon ein einziger Regeldurchlauf`() {
        // clean() wiederholt zur Sicherheit bis zum Fixpunkt. Für normale Sätze darf das nie nötig sein:
        // Sonst wäre eine Regel so gebaut, dass sie ihrer eigenen Ausgabe widerspricht.
        for ((input, expected) in ALL) {
            val once = SpokenText.cleanOnce(input)
            assertEquals("erster Durchlauf unfertig bei: $input", expected, once)
            assertEquals("zweiter Durchlauf ändert: $input", once, SpokenText.cleanOnce(once))
        }
    }

    @Test fun `im Ergebnis stehen keine Ziffern und keine Markdown-Zeichen mehr`() {
        for ((input, _) in ALL) {
            val out = SpokenText.clean(input)
            assertTrue("Rest in \"$out\" (aus \"$input\")", out.none { it.isDigit() || it in "*#`_%€$°()[]{}" })
        }
    }

    @Test fun `alle Zahlen von null bis zwanzigtausend werden zu stabilen Woertern`() {
        val seen = HashSet<String>()
        for (n in 0..20_000) {
            val words = SpokenText.clean("Genau $n Stück.")
            assertTrue("Ziffer übrig bei $n: $words", words.none { it.isDigit() })
            assertEquals("nicht idempotent bei $n", words, SpokenText.clean(words))
            // Zwei verschiedene Zahlen dürfen nie gleich klingen.
            assertTrue("doppelt: $words", seen.add(words))
        }
    }

    @Test fun `zufaellige Zeichenfolgen bringen nichts zum Absturz und bleiben stabil`() {
        // Fester Startwert: Der Test ist jedes Mal derselbe und trotzdem breit gestreut.
        val random = Random(20261112)
        val pieces = listOf(
            "1", "2", "5", "0", "07", "12", "100", "1989", "2024", "12.500", "3,5", "0,99", ".", ",", ":", "-", "–", "/",
            "+", " ", " ", " ", "%", "€", "$", "°C", "km", "m", "h", "min", "Uhr", "am", "der", "im", "Mai", "November",
            "Euro", "Stunden", "ca.", "z. B.", "usw.", "Nr.", "(", ")", "*", "_", "#", "x", "A", "G", "the", "is", "und",
            "ist", "Platz", "er", "st", "th", "\n", "~", ">", "<", "`", "[", "]", "&", "=", "1.", "5.", "0171", "+49",
            // Bausteine der später ergänzten Regeln: Brüche, Bereiche, Spielstände, "pro", Hochzahlen, Klammern.
            "1/2", "3/4", "1/100", "²", "‰", "^", ">=", "(089)", "(0)", "EUR", "/min", "/kg", "8:00-18:00", "14.-16.",
            "14./15.", "Nov.", "sein", "jeder", "gewann", "Maßstab", "₂", "'", ",-", "° C", "m2", "5er", "2025/26",
            "23:21", "1:30", "384-322", "1914–18", " - ", " – ", "−", "v. Chr.", "will", "hatten", "Liter", "Stunde",
            "nimm", "eine", "von", "Dann", "2.5", "5.50", "12.11", "Mio.", "Std.", "Min.", "{", "}", "§", "×", "C#",
        )
        repeat(6000) {
            val text = (1..random.nextInt(1, 9)).joinToString("") { pieces[random.nextInt(pieces.size)] }
            val once = SpokenText.clean(text)
            assertTrue("Ziffer übrig in \"$once\" (aus \"$text\")", once.none { it.isDigit() })
            assertEquals("nicht idempotent bei \"$text\"", once, SpokenText.clean(once))
        }
    }

    private companion object {
        val CARDINALS = listOf(
            "Es sind 0 Fehler." to "Es sind null Fehler.",
            "Ich nehme 7." to "Ich nehme sieben.",
            "Er ist 21." to "Er ist einundzwanzig.",
            "Das sind 16 und 17." to "Das sind sechzehn und siebzehn.",
            "Sie zählt bis 30." to "Sie zählt bis dreißig.",
            "Sie hat 71 Punkte." to "Sie hat einundsiebzig Punkte.",
            "Wir sind 100." to "Wir sind einhundert.",
            "Es waren 101." to "Es waren einhunderteins.",
            "Genau 317." to "Genau dreihundertsiebzehn.",
            "Das Buch hat 1200 Seiten." to "Das Buch hat eintausendzweihundert Seiten.",
            "Die Zugspitze ist 2962 Meter hoch." to "Die Zugspitze ist zweitausendneunhundertzweiundsechzig Meter hoch.",
            "Das Stadion fasst 75000 Zuschauer." to "Das Stadion fasst fünfundsiebzigtausend Zuschauer.",
            "Es sind 999999." to "Es sind neunhundertneunundneunzigtausendneunhundertneunundneunzig.",
            "Es sind 1000000." to "Es sind eine Million.",
            "Es sind 1000001." to "Es sind eine Million eins.",
            "Dort leben 2000000 Menschen." to "Dort leben zwei Millionen Menschen.",
            "Es sind 3500000." to "Es sind drei Millionen fünfhunderttausend.",
            "Die Erde hat 8000000000 Menschen." to "Die Erde hat acht Milliarden Menschen.",
            "Das kostet 12.500 Euro." to "Das kostet zwölftausendfünfhundert Euro.",
            "Es gibt 1.234.567 Einträge." to
                "Es gibt eine Million zweihundertvierunddreißigtausendfünfhundertsiebenundsechzig Einträge.",
            "2.000.000.000 Jahre" to "zwei Milliarden Jahre",
            "Rund 10 000 Leute kamen." to "Rund zehntausend Leute kamen.",
            "Rund 10 000 Euro." to "Rund zehntausend Euro.",
            "Das sind 1001 Nacht." to "Das sind eintausendeins Nacht.",
        )

        val DECIMALS = listOf(
            "Das sind 3,5 Prozent." to "Das sind drei Komma fünf Prozent.",
            "Pi ist 3,14." to "Pi ist drei Komma eins vier.",
            "Es sind 0,5 Liter." to "Es sind null Komma fünf Liter.",
            "1,5 Millionen Menschen" to "eins Komma fünf Millionen Menschen",
            "Das macht 1.234,56." to "Das macht eintausendzweihundertvierunddreißig Komma fünf sechs.",
            "Draußen sind -5 Grad." to "Draußen sind minus fünf Grad.",
            "Es wird −3 °C kalt." to "Es wird minus drei Grad Celsius kalt.",
            "Der Wert ist -0,25." to "Der Wert ist minus null Komma zwei fünf.",
            "Temperaturen von -5 bis -10 Grad" to "Temperaturen von minus fünf bis minus zehn Grad",
            "Ein Plus von +3 Punkten." to "Ein Plus von plus drei Punkten.",
            "Wähle 1, 2 oder 3." to "Wähle eins, zwei oder drei.",
            "Die Werte 1,2,3 passen." to "Die Werte eins, zwei, drei passen.",
            "Es sind 11, 12 und 13." to "Es sind elf, zwölf und dreizehn.",
        )

        val YEARS = listOf(
            "1989" to "neunzehnhundertneunundachtzig",
            "Die Mauer fiel 1989." to "Die Mauer fiel neunzehnhundertneunundachtzig.",
            "im Jahr 2024" to "im Jahr zweitausendvierundzwanzig",
            "Wir schreiben das Jahr 2026." to "Wir schreiben das Jahr zweitausendsechsundzwanzig.",
            "Im Jahr 2000 begann es." to "Im Jahr zweitausend begann es.",
            "Seit 1990 ist Deutschland wieder vereint." to "Seit neunzehnhundertneunzig ist Deutschland wieder vereint.",
            "Von 1939 bis 1945 war Krieg." to
                "Von neunzehnhundertneununddreißig bis neunzehnhundertfünfundvierzig war Krieg.",
            "Der Krieg dauerte 1939-1945." to
                "Der Krieg dauerte neunzehnhundertneununddreißig bis neunzehnhundertfünfundvierzig.",
            "Das war 1900." to "Das war neunzehnhundert.",
            "Goethe starb 1832 in Weimar." to "Goethe starb achtzehnhundertzweiunddreißig in Weimar.",
            "Im Sommer 1969 landeten Menschen auf dem Mond." to
                "Im Sommer neunzehnhundertneunundsechzig landeten Menschen auf dem Mond.",
            "Das geschah 1701 v. Chr." to "Das geschah siebzehnhunderteins vor Christus.",
            "Das war 1066." to "Das war eintausendsechsundsechzig.",
            "Karl der Große wurde 800 gekrönt." to "Karl der Große wurde achthundert gekrönt.",
            "Das kostet 1500 Euro." to "Das kostet eintausendfünfhundert Euro.",
            "Es kamen 1800 Zuschauer." to "Es kamen eintausendachthundert Zuschauer.",
            "Der Lauf geht über 1500 m." to "Der Lauf geht über eintausendfünfhundert Meter.",
            "Die 90er waren bunt." to "Die neunziger waren bunt.",
            "In den 1980ern war das anders." to "In den neunzehnhundertachtzigern war das anders.",
        )

        val TIMES_AND_DATES = listOf(
            "Es ist 21:14." to "Es ist einundzwanzig Uhr vierzehn.",
            "um 7:05 Uhr" to "um sieben Uhr fünf",
            "Der Zug fährt um 12:00 Uhr." to "Der Zug fährt um zwölf Uhr.",
            "Wir treffen uns um 8 Uhr." to "Wir treffen uns um acht Uhr.",
            "Um 1 Uhr ist Schluss." to "Um ein Uhr ist Schluss.",
            "Abfahrt 14.30 Uhr." to "Abfahrt vierzehn Uhr dreißig.",
            "Es ist 0:30." to "Es ist null Uhr dreißig.",
            "Es ist 01:15." to "Es ist ein Uhr fünfzehn.",
            "Bayern gewann 2:1." to "Bayern gewann zwei zu eins.",
            "12. November" to "zwölfter November",
            "Heute ist der 12. November." to "Heute ist der zwölfte November.",
            "Am 3. Oktober ist Feiertag." to "Am dritten Oktober ist Feiertag.",
            "Das war am 9. November 1989." to "Das war am neunten November neunzehnhundertneunundachtzig.",
            "Vom 3. bis 5. Mai bin ich weg." to "Vom dritten bis fünften Mai bin ich weg.",
            "Seit dem 1. Januar gilt das." to "Seit dem ersten Januar gilt das.",
            "Der 1. Mai ist frei." to "Der erste Mai ist frei.",
            "Er kommt am 5." to "Er kommt am fünften.",
            "Heute ist der 12.11.2026." to "Heute ist der zwölfte elfte zweitausendsechsundzwanzig.",
            "Termin am 01.02." to "Termin am ersten zweiten.",
            "Donnerstag, 12.11., um 18 Uhr." to "Donnerstag, zwölfter elfter, um achtzehn Uhr.",
            "Das war am 9.11.1989." to "Das war am neunten elften neunzehnhundertneunundachtzig.",
            "Es ist 12:30:45 Uhr." to "Es ist zwölf Uhr dreißig und fünfundvierzig Sekunden.",
            "Stand: 2026-11-12" to "Stand: zwölfter November zweitausendsechsundzwanzig",
        )

        val ORDINALS = listOf(
            "1. FC Köln" to "erster FC Köln",
            "Sie wohnt im 2. Stock." to "Sie wohnt im zweiten Stock.",
            "Er wurde im 19. Jahrhundert geboren." to "Er wurde im neunzehnten Jahrhundert geboren.",
            "Das ist die 3. Etage." to "Das ist die dritte Etage.",
            "In der 3. Etage" to "In der dritten Etage",
            "Zum 1. Mal dabei." to "Zum ersten Mal dabei.",
            "Sie belegte den 2. Platz." to "Sie belegte den zweiten Platz.",
            "Der 2. Weltkrieg endete 1945." to "Der zweite Weltkrieg endete neunzehnhundertfünfundvierzig.",
            "Der 100. Geburtstag" to "Der einhundertste Geburtstag",
            "Das 21. Jahrhundert" to "Das einundzwanzigste Jahrhundert",
            // Kein Begleiter, kein bekanntes Hauptwort: Der Punkt ist ein Satzende.
            "Es sind 5." to "Es sind fünf.",
            "Ich zähle 3. Dann geht es los." to "Ich zähle drei. Dann geht es los.",
            "1. Mehl kaufen" to "eins. Mehl kaufen",
            "Es sind 5. Euro haben wir keine." to "Es sind fünf. Euro haben wir keine.",
        )

        val UNITS = listOf(
            "Der Berg ist 2962 m hoch." to "Der Berg ist zweitausendneunhundertzweiundsechzig Meter hoch.",
            "Das sind 3,5 %." to "Das sind drei Komma fünf Prozent.",
            "100 % sicher" to "einhundert Prozent sicher",
            "Das kostet 5 €." to "Das kostet fünf Euro.",
            "Das kostet 3,50 €." to "Das kostet drei Euro fünfzig.",
            "Das kostet 0,99 €." to "Das kostet neunundneunzig Cent.",
            "Ein Kaffee kostet 2,80 Euro." to "Ein Kaffee kostet zwei Euro achtzig.",
            "Das kostet $5." to "Das kostet fünf Dollar.",
            "Das iPhone 15 kostet 999 €." to "Das iPhone fünfzehn kostet neunhundertneunundneunzig Euro.",
            "Es sind 20 °C." to "Es sind zwanzig Grad Celsius.",
            "Ein Winkel von 90°." to "Ein Winkel von neunzig Grad.",
            "Er fährt 130 km/h." to "Er fährt einhundertdreißig Kilometer pro Stunde.",
            "Noch 5 km." to "Noch fünf Kilometer.",
            "5km sind es noch." to "fünf Kilometer sind es noch.",
            "Noch 5 km." to "Noch fünf Kilometer.",
            "Das Brett ist 80 cm lang." to "Das Brett ist achtzig Zentimeter lang.",
            "Nur 3 mm dick." to "Nur drei Millimeter dick.",
            "Sie wiegt 60 kg." to "Sie wiegt sechzig Kilogramm.",
            "Nimm 250 g Mehl." to "Nimm zweihundertfünfzig Gramm Mehl.",
            "Dazu 2 l Wasser." to "Dazu zwei Liter Wasser.",
            "Und 200 ml Milch." to "Und zweihundert Milliliter Milch.",
            "Die Fahrt dauert 3 h." to "Die Fahrt dauert drei Stunden.",
            "Noch 10 min." to "Noch zehn Minuten.",
            "Er lief 100 m in 9,58 s." to "Er lief einhundert Meter in neun Komma fünf acht Sekunden.",
            "Die Wohnung hat 80 m²." to "Die Wohnung hat achtzig Quadratmeter.",
            // Die Eins richtet sich nach dem Hauptwort und dem Wort davor.
            "Das dauert 1 h." to "Das dauert eine Stunde.",
            "In 1 h bin ich da." to "In einer Stunde bin ich da.",
            "Es fehlt 1 %." to "Es fehlt ein Prozent.",
            "Für 1 € bekommst du das." to "Für einen Euro bekommst du das.",
            "Das dauert 1 Stunde." to "Das dauert eine Stunde.",
            "Nur 1 Meter fehlt." to "Nur ein Meter fehlt.",
            "Die Stadt hat 1 Million Einwohner." to "Die Stadt hat eine Million Einwohner.",
            "Das kostet 1 Mio. €." to "Das kostet eine Million Euro.",
            "Das sind 1,5 Mio. Euro." to "Das sind eins Komma fünf Millionen Euro.",
            "Gleis 1 Richtung Köln" to "Gleis eins Richtung Köln",
            "Er ist die Nummer 1." to "Er ist die Nummer eins.",
            "Es steht 1 zu 0." to "Es steht eins zu null.",
            "Das kostet 1 Euro." to "Das kostet einen Euro.",
            "E10 kostet 1,79 € pro Liter." to "E zehn kostet einen Euro neunundsiebzig pro Liter.",
            "Mit 1 Tor Vorsprung." to "Mit einem Tor Vorsprung.",
            "Vor 1 Woche." to "Vor einer Woche.",
            "Bei -1 °C friert es." to "Bei minus einem Grad Celsius friert es.",
            "Das dauert 1-2 Stunden." to "Das dauert ein bis zwei Stunden.",
            "Platz 1 bis 3." to "Platz eins bis drei.",
            "Ca. 20 Min. Fußweg." to "Circa zwanzig Minuten Fußweg.",
            "Es dauert ca. 1 Std." to "Es dauert circa eine Stunde.",
            "Max. 30 km/h." to "Maximal dreißig Kilometer pro Stunde.",
            "Wirklich 100%ig." to "Wirklich einhundertprozentig.",
            "Das Auto kostet 25.990,00 €." to "Das Auto kostet fünfundzwanzigtausendneunhundertneunzig Euro.",
        )

        val ABBREVIATIONS = listOf(
            "Das gilt z. B. für Wasser." to "Das gilt zum Beispiel für Wasser.",
            "Das gilt z.B. für Wasser." to "Das gilt zum Beispiel für Wasser.",
            "Z. B. heute." to "Zum Beispiel heute.",
            "Das sind ca. 3 km." to "Das sind circa drei Kilometer.",
            "Montag bzw. Dienstag" to "Montag beziehungsweise Dienstag",
            "Äpfel, Birnen usw." to "Äpfel, Birnen und so weiter.",
            "Er ist krank, d. h. er kommt nicht." to "Er ist krank, das heißt er kommt nicht.",
            "Dazu gehören u. a. Katzen." to "Dazu gehören unter anderem Katzen.",
            "Das klappt evtl. morgen." to "Das klappt eventuell morgen.",
            "Nimm die Nr. 5." to "Nimm die Nummer fünf.",
            "ca. 1,5 Mio. Menschen" to "circa eins Komma fünf Millionen Menschen",
            // Sieht nur aus wie eine Abkürzung.
            "Das ist Max." to "Das ist Max.",
            "D. H. Lawrence schrieb Romane." to "D. H. Lawrence schrieb Romane.",
        )

        val MARKDOWN = listOf(
            "**Wichtig:** Das stimmt." to "Wichtig: Das stimmt.",
            "Das ist *sehr* gut." to "Das ist sehr gut.",
            "# Überschrift" to "Überschrift",
            "Nutze `adb devices` dafür." to "Nutze adb devices dafür.",
            "Das ist _wirklich_ so." to "Das ist wirklich so.",
            "__fett__ und ~~weg~~" to "fett und weg",
            "- erster Punkt" to "erster Punkt",
            "* zweiter Punkt" to "zweiter Punkt",
            "Siehe [Wikipedia](https://de.wikipedia.org) dazu." to "Siehe Wikipedia dazu.",
            "Die Zugspitze (2962 m) ist hoch." to "Die Zugspitze, zweitausendneunhundertzweiundsechzig Meter, ist hoch.",
            "Er kommt später (vielleicht)." to "Er kommt später, vielleicht.",
            "Der Wert [siehe oben] stimmt." to "Der Wert, siehe oben, stimmt.",
            "Tom & Anna" to "Tom und Anna",
            "2 + 2 = 4" to "zwei plus zwei gleich vier",
            "5+3 ist 8." to "fünf plus drei ist acht.",
            "Paragraph § 5 Absatz 2." to "Paragraph fünf Absatz zwei.",
            "#1 der Charts" to "Nummer eins der Charts",
            "~20 Leute" to "ungefähr zwanzig Leute",
            "Das sind >100 Stück." to "Das sind mehr als einhundert Stück.",
        )

        val NASTY = listOf(
            "3-4 Stunden" to "drei bis vier Stunden",
            "10-15 % Rabatt" to "zehn bis fünfzehn Prozent Rabatt",
            "A4" to "A vier",
            "H2O" to "H zwei O",
            "COVID-19" to "COVID neunzehn",
            "5G" to "fünf G",
            "24/7" to "vierundzwanzig sieben",
            "Die B12 ist gesperrt." to "Die B zwölf ist gesperrt.",
            "Das Format MP3 ist alt." to "Das Format MP drei ist alt.",
            "Das ist 4K." to "Das ist vier K.",
            // Telefonnummern und Kennziffern: Ziffer für Ziffer, Gruppen als Pausen.
            "Ruf 0171 1234567 an." to "Ruf null eins sieben eins, eins zwei drei vier fünf sechs sieben an.",
            "Die Nummer ist +49 30 123456." to "Die Nummer ist plus vier neun, drei null, eins zwei drei vier fünf sechs.",
            "Wähle 030-1234567." to "Wähle null drei null, eins zwei drei vier fünf sechs sieben.",
            "Agent 007 ermittelt." to "Agent null null sieben ermittelt.",
            "Die Kundennummer lautet 12345678." to "Die Kundennummer lautet eins zwei drei vier fünf sechs sieben acht.",
            "Die PLZ ist 80331." to "Die PLZ ist acht null drei drei eins.",
            "ISBN 978-3-16-148410-0" to "ISBN neun sieben acht, drei, eins sechs, eins vier acht vier eins null, null",
            "Sie spielen im 4-4-2." to "Sie spielen im vier, vier, zwei.",
            // Versionsnummern
            "Version 1.2.3 ist da." to "Version eins Punkt zwei Punkt drei ist da.",
            "Nimm v2.0." to "Nimm v zwei Punkt null.",
            "Python 3.12 ist schnell." to "Python drei Punkt zwölf ist schnell.",
            "Windows 11 und iOS 17.4" to "Windows elf und iOS siebzehn Punkt vier",
            "Android 14 läuft." to "Android vierzehn läuft.",
            "Die IP ist 192.168.0.1." to
                "Die IP ist einhundertzweiundneunzig Punkt einhundertachtundsechzig Punkt null Punkt eins.",
            "Ein 10-jähriges Kind" to "Ein zehnjähriges Kind",
            "Eine 2-Zimmer-Wohnung" to "Eine zwei-Zimmer-Wohnung",
            "3/4 der Leute" to "drei Viertel der Leute",
            "1/2 Liter Milch" to "ein halber Liter Milch",
            "Das Verhältnis ist 50/50." to "Das Verhältnis ist fünfzig fünfzig.",
            "Der Bildschirm hat 1920x1080 Pixel." to
                "Der Bildschirm hat eintausendneunhundertzwanzig mal eintausendachtzig Pixel.",
            "Er hat 3x gewonnen." to "Er hat drei mal gewonnen.",
            // Schon ausgeschrieben: nichts anfassen.
            "Die Zugspitze ist zweitausendneunhundertzweiundsechzig Meter hoch." to
                "Die Zugspitze ist zweitausendneunhundertzweiundsechzig Meter hoch.",
            "Es ist einundzwanzig Uhr vierzehn." to "Es ist einundzwanzig Uhr vierzehn.",
            "Drei Komma fünf Prozent, also etwa ein Dreißigstel." to "Drei Komma fünf Prozent, also etwa ein Dreißigstel.",
        )

        val ENGLISH = listOf(
            "The answer is 42." to "The answer is forty-two.",
            "The Zugspitze is 2962 m high." to "The Zugspitze is two thousand nine hundred sixty-two meters high.",
            "There are 1,234,567 of them." to
                "There are one million two hundred thirty-four thousand five hundred sixty-seven of them.",
            "We have 2 million users." to "We have two million users.",
            "There are 0 errors and 1 warning." to "There are zero errors and one warning.",
            "It is 3.5 percent." to "It is three point five percent.",
            "This is 100% correct." to "This is one hundred percent correct.",
            "It is -5 degrees." to "It is minus five degrees.",
            "It costs $3.50." to "It costs three dollars and fifty cents.",
            "It is 21 degrees and the wind is 15 km/h." to
                "It is twenty-one degrees and the wind is fifteen kilometers per hour.",
            "The wall fell in 1989." to "The wall fell in nineteen eighty-nine.",
            "It was in the 1990s." to "It was in the nineteen nineties.",
            "That was the 3rd time." to "That was the third time.",
            "It takes 3-4 hours." to "It takes three to four hours.",
            "The meeting is at 7:05." to "The meeting is at seven oh five.",
            "That is 1/2 of it." to "That is one half of it.",
            "This is version 1.2.3 of the app." to "This is version one point two point three of the app.",
            "Call 555-123-4567 now." to "Call five five five, one two three, four five six seven now.",
            "I was born on November 12th, 1990." to "I was born on November twelfth, nineteen ninety.",
            "By 2030 we need 50% less CO2." to "By twenty thirty we need fifty percent less CO two.",
            "$5 million is a lot of money for the team." to "five million dollars is a lot of money for the team.",
            "It's 12:00 now." to "It's twelve o'clock now.",
            "About 5 km." to "About five kilometers.",
            "It weighs 1 kg." to "It weighs one kilogram.",
        )

        // Englische Wörter in einem deutschen Satz ändern die Sprache der Zahlwörter nicht.
        val MIXED = listOf(
            "The Lord of the Rings kostet 10 €." to "The Lord of the Rings kostet zehn Euro.",
            "Der Film The Wall ist von 1982." to "Der Film The Wall ist von neunzehnhundertzweiundachtzig.",
            "Call of Duty kostet 60 €." to "Call of Duty kostet sechzig Euro.",
            "Das Lied Let It Be ist 4 min lang." to "Das Lied Let It Be ist vier Minuten lang.",
        )

        // "will", "Not" und englische Namen dürfen einen deutschen Satz nicht zu englischen Zahlwörtern verleiten.
        val LANGUAGE_TRAPS = listOf(
            "Tom will 3 Bier." to "Tom will drei Bier.",
            "Anna will 10 Euro." to "Anna will zehn Euro.",
            "The Beatles hatten 4 Mitglieder." to "The Beatles hatten vier Mitglieder.",
            "Game of Thrones Staffel 8" to "Game of Thrones Staffel acht",
            "Not: 112." to "Not: einhundertzwölf.",
            // Die Gegenprobe: Echtes Englisch bleibt englisch.
            "I'm 30 years old." to "I'm thirty years old.",
            "Paris has 2 million inhabitants." to "Paris has two million inhabitants.",
        )

        val ORDINALS_MORE = listOf(
            "Am 1., 2. und 3. Mai." to "Am ersten, zweiten und dritten Mai.",
            "1., 2. und 3. Platz" to "erster, zweiter und dritter Platz",
            "Der 1. und der 2. Platz." to "Der erste und der zweite Platz.",
            "Im 3. und 4. Stock." to "Im dritten und vierten Stock.",
            "Sein 3. Tor war schön." to "Sein drittes Tor war schön.",
            "Sein 3. Buch und sein 2. Film." to "Sein drittes Buch und sein zweiter Film.",
            "Ihr 2. Kind heißt Tom." to "Ihr zweites Kind heißt Tom.",
            "Seine 3. Frau." to "Seine dritte Frau.",
            "Mit seinem 2. Buch." to "Mit seinem zweiten Buch.",
            "Jeder 3. Deutsche hat eins." to "Jeder dritte Deutsche hat eins.",
            "In jeder 3. Stunde." to "In jeder dritten Stunde.",
            // Klein weiter nach dem Punkt: Dann war es kein Satzende.
            "Der 3. von links." to "Der dritte von links.",
            "Heinrich der 8. war König." to "Heinrich der achte war König.",
            // So kommt ein Datum an, wenn der Satzzerleger hinter "12." getrennt hat.
            "Heute ist der 12." to "Heute ist der zwölfte.",
            "Heute ist Donnerstag, der 12." to "Heute ist Donnerstag, der zwölfte.",
            "Wir haben den 12." to "Wir haben den zwölften.",
            "Seit dem 3." to "Seit dem dritten.",
            // "die 3." am Satzende bleibt offen (die Linie drei, die Note drei) und damit eine Grundzahl.
            "Sie nimmt die 3." to "Sie nimmt die drei.",
        )

        val RANGES_AND_MINUS = listOf(
            "Aristoteles lebte 384-322 v. Chr." to
                "Aristoteles lebte dreihundertvierundachtzig bis dreihundertzweiundzwanzig vor Christus.",
            "Aristoteles (384–322 v. Chr.) war Philosoph." to
                "Aristoteles, dreihundertvierundachtzig bis dreihundertzweiundzwanzig vor Christus, war Philosoph.",
            "Der Preis fiel von 800-600 Euro." to "Der Preis fiel von achthundert bis sechshundert Euro.",
            "Die Jahre 1914–18." to "Die Jahre neunzehnhundertvierzehn bis neunzehnhundertachtzehn.",
            "Mozart (1756–1791) war Komponist." to
                "Mozart, siebzehnhundertsechsundfünfzig bis siebzehnhunderteinundneunzig, war Komponist.",
            "Etwa 5.000-6.000 Euro." to "Etwa fünftausend bis sechstausend Euro.",
            "Zinsen von 3,5-4,5 %." to "Zinsen von drei Komma fünf bis vier Komma fünf Prozent.",
            "Rabatt 20 - 30 %." to "Rabatt zwanzig bis dreißig Prozent.",
            "Geöffnet 10 - 12 Uhr." to "Geöffnet zehn bis zwölf Uhr.",
            "7 – 2 = 5" to "sieben minus zwei gleich fünf",
            "7 - 2 = 5" to "sieben minus zwei gleich fünf",
            "7−2=5" to "sieben minus zwei gleich fünf",
            "10-5=5" to "zehn minus fünf gleich fünf",
            "2 - 5 = -3" to "zwei minus fünf gleich minus drei",
            "Er lebte 384 – 322 v. Chr." to "Er lebte dreihundertvierundachtzig bis dreihundertzweiundzwanzig vor Christus.",
            // Ohne Gleichheitszeichen kann der Strich auch ein Gedankenstrich sein: nichts hineindeuten.
            "Wir sind 10 – 5 fehlen noch." to "Wir sind zehn – fünf fehlen noch.",
            "24-7" to "vierundzwanzig sieben",
            "Bayern gewann 3-1." to "Bayern gewann drei zu eins.",
            "Bayern verlor 1-3." to "Bayern verlor eins zu drei.",
            // Hinter einem Namen ist das Leerzeichen kein Tausendertrenner.
            "Gleis 3 120 Minuten später." to "Gleis drei einhundertzwanzig Minuten später.",
        )

        val TIMES_MORE = listOf(
            "8:00-18:00 Uhr" to "acht Uhr bis achtzehn Uhr",
            "8:00 – 18:00 Uhr" to "acht Uhr bis achtzehn Uhr",
            "8:00-18 Uhr" to "acht Uhr bis achtzehn Uhr",
            "Geöffnet von 8:00-12:00 und 14:00-18:00 Uhr." to
                "Geöffnet von acht Uhr bis zwölf Uhr und vierzehn Uhr bis achtzehn Uhr.",
            "14:30h" to "vierzehn Uhr dreißig",
            "Um 14:30 h." to "Um vierzehn Uhr dreißig.",
            "Abfahrt 8:30 h." to "Abfahrt acht Uhr dreißig.",
            "Der Film dauert 1:30 h." to "Der Film dauert eine Stunde dreißig Minuten.",
            "Er braucht 2:30 Stunden." to "Er braucht zwei Stunden dreißig Minuten.",
            "Nach 2:30 h war Schluss." to "Nach zwei Stunden dreißig Minuten war Schluss.",
            "Um 3:45 h morgens." to "Um drei Uhr fünfundvierzig morgens.",
            "Deutschland gewann 23:21." to "Deutschland gewann dreiundzwanzig zu einundzwanzig.",
            "Die Handballer haben 23:21 gewonnen." to "Die Handballer haben dreiundzwanzig zu einundzwanzig gewonnen.",
            "Endstand: 23:21." to "Endstand: dreiundzwanzig zu einundzwanzig.",
            "Im Maßstab von 1:50." to "Im Maßstab von eins zu fünfzig.",
            "Der Maßstab ist 1:100000." to "Der Maßstab ist eins zu einhunderttausend.",
            // Ein Spiel weiter vorn im Satz macht aus "um 20:15" keinen Spielstand.
            "Bayern gewann und um 20:15 läuft der Film." to "Bayern gewann und um zwanzig Uhr fünfzehn läuft der Film.",
            "Es dauert 20 Min. Dann gehen wir." to "Es dauert zwanzig Minuten. Dann gehen wir.",
        )

        val DOT_DECIMALS = listOf(
            "Es wiegt 2.5 kg." to "Es wiegt zwei Komma fünf Kilogramm.",
            "Das sind 1.5 km." to "Das sind eins Komma fünf Kilometer.",
            "Es sind 3.5 Grad." to "Es sind drei Komma fünf Grad.",
            "Das kostet 5.50 €." to "Das kostet fünf Euro fünfzig.",
            "Das kostet €5.50." to "Das kostet fünf Euro fünfzig.",
            "Es sind 83.2 Millionen." to "Es sind dreiundachtzig Komma zwei Millionen.",
            "Es sind 3.14159 Radiant." to "Es sind drei Punkt eins vier eins fünf neun Radiant.",
        )

        val NOTATIONS = listOf(
            "Telefon: (089) 123456." to "Telefon: null acht neun, eins zwei drei vier fünf sechs.",
            "Telefon: +49 (0) 89 123456." to "Telefon: plus vier neun, acht neun, eins zwei drei vier fünf sechs.",
            // Einstellige Vorwahl (Wien): auch das ist eine Nummer und keine Menge.
            "Die Nummer ist +43 1 234567." to "Die Nummer ist plus vier drei, eins, zwei drei vier fünf sechs sieben.",
            "Die Nummer ist +491711234567." to "Die Nummer ist plus vier neun eins sieben eins eins zwei drei vier fünf sechs sieben.",
            "Es sind +49 Grad." to "Es sind plus neunundvierzig Grad.",
            "10 m2" to "zehn Quadratmeter",
            "10 qm" to "zehn Quadratmeter",
            "5 cm³" to "fünf Kubikzentimeter",
            "CO₂" to "CO zwei",
            "x² + y³" to "x hoch zwei plus y hoch drei",
            "Das sind 5‰." to "Das sind fünf Promille.",
            "Das sind 10^6." to "Das sind zehn hoch sechs.",
            "Es hat 30° C." to "Es hat dreißig Grad Celsius.",
            "Das kostet 5,- €." to "Das kostet fünf Euro.",
            "Das kostet 5,– Euro." to "Das kostet fünf Euro.",
            "Das kostet EUR 3,50." to "Das kostet drei Euro fünfzig.",
            "Das kostet 0,01 €." to "Das kostet einen Cent.",
            "Er hat 3 Kinder (2 Söhne, 1 Tochter)." to "Er hat drei Kinder, zwei Söhne, eine Tochter.",
            "Er hat 1 Hund." to "Er hat einen Hund.",
            "Abfahrt 14.30h." to "Abfahrt vierzehn Uhr dreißig.",
            "Das kostet 1'000 Franken." to "Das kostet eintausend Franken.",
            "50 €/Monat" to "fünfzig Euro pro Monat",
            "5 €/kg" to "fünf Euro pro Kilogramm",
            "3 m/s" to "drei Meter pro Sekunde",
            "Puls 60/min." to "Puls sechzig pro Minute.",
            "100 €/Nacht/Person" to "einhundert Euro pro Nacht pro Person",
            "Ein 5er BMW." to "Ein fünfer BMW.",
            "Ein 1er Schüler." to "Ein einser Schüler.",
            "Ein 3er-Pack." to "Ein dreier-Pack.",
            "C# ist eine Sprache." to "C Sharp ist eine Sprache.",
            "5 >= 3" to "fünf mindestens drei",
        )

        val DATES_MORE = listOf(
            "Sa., 14.11." to "Sa., vierzehnter elfter.",
            "Vom 14.11.-16.11." to "Vom vierzehnten elften bis sechzehnten elften.",
            "Vom 14.-16.11." to "Vom vierzehnten bis sechzehnten elften.",
            "Vom 14.–16. November." to "Vom vierzehnten bis sechzehnten November.",
            "Am 14./15. November." to "Am vierzehnten und fünfzehnten November.",
            "Am 12. Nov." to "Am zwölften November.",
            "Am 12. Nov. 2026." to "Am zwölften November zweitausendsechsundzwanzig.",
            "Am 12.11 um 18 Uhr." to "Am zwölften elften um achtzehn Uhr.",
            "Die Saison 2025/26." to "Die Saison zweitausendfünfundzwanzig sechsundzwanzig.",
            "Das war 300 v. Chr. Danach kam Rom." to "Das war dreihundert vor Christus. Danach kam Rom.",
            "Es sind 5 Mio. Das ist viel." to "Es sind fünf Millionen. Das ist viel.",
        )

        val FRACTIONS_MORE = listOf(
            "1 1/2 Stunden" to "eins Komma fünf Stunden",
            "Es wiegt 2 1/2 kg." to "Es wiegt zwei Komma fünf Kilogramm.",
            "1/2 l Milch" to "null Komma fünf Liter Milch",
            "3/4 l" to "null Komma sieben fünf Liter",
            "Nimm 1/2 Liter Milch." to "Nimm einen halben Liter Milch.",
            "In 1/2 Stunde." to "In einer halben Stunde.",
            "Eine 1/2 Stunde." to "Eine halbe Stunde.",
            "1/2 der Leute" to "die Hälfte der Leute",
            "Etwa 1/100." to "Etwa ein Hundertstel.",
            // Ein Drittel lässt sich nicht als Kommazahl sagen: Bruchwort plus ausgeschriebene Einheit.
            "1/3 l" to "ein Drittel Liter",
            "2/3 l Wasser" to "zwei Drittel Liter Wasser",
            "Im 3/4-Takt." to "Im drei Viertel-Takt.",
            "Eine 2 1/2-Zimmer-Wohnung" to "Eine zwei Komma fünf-Zimmer-Wohnung",
            // Hinter einem Wort wie "Gleis" sind es zwei Nummern, kein Bruch.
            "Gleis 1/2" to "Gleis eins zwei",
            "Seite 3/4" to "Seite drei vier",
        )

        val ALL = CARDINALS + DECIMALS + YEARS + TIMES_AND_DATES + ORDINALS + UNITS + ABBREVIATIONS + MARKDOWN + NASTY + ENGLISH + MIXED +
            LANGUAGE_TRAPS + ORDINALS_MORE + RANGES_AND_MINUS + TIMES_MORE + DOT_DECIMALS + NOTATIONS + DATES_MORE + FRACTIONS_MORE

        val PLAIN = listOf(
            "Die Zugspitze ist der höchste Berg Deutschlands.",
            "Ja, das stimmt – zumindest meistens!",
            "Er sagte: „Komm doch morgen vorbei.“",
            "Was meinst du, Alfred?",
            "Das dauert etwa eine Stunde, vielleicht auch zwei.",
            "It is about two hours from here.",
            "Ganz ohne Zahlen, ohne Kürzel; nur Wörter.",
            // Sätze aus PipelineTest: Was schon sprechbar ist, darf sich durch das Einhängen nicht ändern.
            "Zweitausendneunhundertzweiundsechzig Meter.",
            "Sie ist der höchste Berg Deutschlands.",
            "Die Zugspitze ist knapp dreitausend Meter hoch.",
            "Notiert.",
        )
    }
}
