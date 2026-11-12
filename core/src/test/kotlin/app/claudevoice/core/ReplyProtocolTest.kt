package app.claudevoice.core

import app.claudevoice.core.eval.EvalParserCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Das Antwort-Protokoll unter realistischen Abweichungen: Was passiert, wenn das Modell die Kopfzeile
 * verziert, eine Sonderzeile nicht exakt in Spalte 0 schreibt oder die Ruhezeit in Worten angibt?
 *
 * Zwei Fehlerrichtungen werden hier gegeneinander abgesichert: Steuertext darf nicht im Ohr landen
 * (und muss wirken), und gewöhnlicher Sprechtext darf nicht als Steuertext verschluckt werden.
 */
class ReplyProtocolTest {

    private data class Read(val decisions: List<Decision>, val speech: String, val directives: List<Directive>)

    private fun read(pieces: List<String>): Read {
        val decisions = mutableListOf<Decision>()
        val speech = StringBuilder()
        val directives = mutableListOf<Directive>()
        val parser = ReplyParser({ decisions += it }, { speech.append(it) }, { directives += it })
        pieces.forEach(parser::feed)
        parser.finish()
        return Read(decisions, speech.toString(), directives)
    }

    private fun read(text: String, size: Int = Int.MAX_VALUE): Read =
        read(if (text.isEmpty()) emptyList() else text.chunked(minOf(size, text.length)))

    private fun show(s: String) = s.replace("\n", "\\n")

    /** Prüft eine Tabelle in mehreren Stückelungen und meldet alle falschen Zeilen auf einmal. */
    private fun check(rows: List<Pair<String, Read>>) {
        val wrong = ArrayList<String>()
        for ((raw, expected) in rows) for (size in listOf(1, 4, 100)) {
            val got = read(raw, size)
            if (got != expected) wrong += "\n  ${show(raw)} [Stück $size]\n    erwartet: $expected\n    bekommen: $got"
        }
        assertTrue("${wrong.size} falsch:${wrong.joinToString("")}", wrong.isEmpty())
    }

    private fun say(length: String, speech: String, vararg directives: Directive) =
        Read(listOf(Decision.Say(length)), speech, directives.toList())

    private fun skip(reason: String) = Read(listOf(Decision.Skip(reason)), "", emptyList())

    private fun offProtocol(speech: String, vararg directives: Directive) =
        Read(listOf(Decision.Say("", offProtocol = true)), speech, directives.toList())

    // ------------------------------------------------------------------------------------------
    // Kopfzeile

    @Test fun `Sprechtext in derselben Zeile wie SAY wird gesprochen und nicht als Laenge gelesen`() = check(
        listOf(
            "SAY kurz Zweitausendneunhundertzweiundsechzig Meter." to say("kurz", "Zweitausendneunhundertzweiundsechzig Meter."),
            "SAY kurz: Zweitausendneunhundertzweiundsechzig Meter." to say("kurz", "Zweitausendneunhundertzweiundsechzig Meter."),
            "SAY mittel - Nein, Sir.\nEin Wal ist ein Säugetier." to say("mittel", "- Nein, Sir.\nEin Wal ist ein Säugetier."),
            // Ohne bekannte Länge ist alles hinter SAY Sprechtext; in der Anzeige steht dann keine erfundene Länge.
            "SAY Zweitausend Meter." to say("", "Zweitausend Meter."),
            "SAY -5 Grad, Sir." to say("", "-5 Grad, Sir."),
            // Auch ohne Satzzeichen, wenn die Antwort mit dieser Zeile endet: Es ist alles, was das Modell gesagt hat.
            "SAY kurz Zweitausend Meter" to say("kurz", "Zweitausend Meter"),
            "SAY kurz Zweitausend Meter\n  \n" to say("kurz", "Zweitausend Meter\n  \n"),
            "SAY Paris\n" to say("", "Paris\n"),
        ),
    )

    @Test fun `ein Zusatz zur Kopfzeile wird nicht vorgelesen`() = check(
        listOf(
            // Kurzer Rest ohne Satzzeichen, und danach kommt die eigentliche Antwort: Der Rest gehörte zur Kopfzeile.
            "SAY sehr kurz\nZweitausend Meter." to say("", "Zweitausend Meter."),
            "SAY short\nTwo thousand meters." to say("", "Two thousand meters."),
            "SAY kurz bis mittel\nZweitausend Meter." to say("kurz", "Zweitausend Meter."),
            "SAY kurz (direkte Frage)\n\nZweitausend Meter." to say("kurz", "\nZweitausend Meter."),
            "SAY kurz - er fragt selbst\nZweitausend Meter.\nMUTE: 5" to say("kurz", "Zweitausend Meter.\n", Directive.Mute(5)),
            // Ein langer Rest ist kein Zusatz mehr, sondern die Antwort.
            "SAY kurz Die Zugspitze ist der höchste Berg Deutschlands\nSie liegt in Bayern." to
                say("kurz", "Die Zugspitze ist der höchste Berg Deutschlands\nSie liegt in Bayern."),
        ),
    )

    @Test fun `ein Rest der Kopfzeile ohne Buchstaben und Ziffern ist nie die Antwort`() = check(
        listOf(
            // Früher ging das "!" als eigener Satz an die Sprachausgabe.
            "SAY kurz!\nZweitausend Meter." to say("kurz", "Zweitausend Meter."),
            "SAY kurz …\nZweitausend Meter." to say("kurz", "Zweitausend Meter."),
            "SAY kurz (!)\nZweitausend Meter." to say("kurz", "Zweitausend Meter."),
            "SAY kurz -\nZweitausend Meter." to say("kurz", "Zweitausend Meter."),
            "SAY kurz! Zweitausend Meter." to say("kurz", "Zweitausend Meter."),
            // Auch dann nicht, wenn die Antwort mit ihm endet.
            "SAY kurz!" to say("kurz", ""),
            "SAY kurz (!)" to say("kurz", ""),
            "SAY kurz (!)\n  \n" to say("kurz", "  \n"),
            // Ein Satzzeichen zählt erst, wenn davor etwas Sprechbares stand.
            "SAY kurz (?) Zusatz\nZweitausend Meter." to say("kurz", "Zweitausend Meter."),
            "SAY kurz (2) Zweitausend Meter." to say("kurz", "(2) Zweitausend Meter."),
        ),
    )

    @Test fun `verzierte Kopfzeile bleibt eine Kopfzeile`() = check(
        listOf(
            "SAY: kurz\nZweitausend Meter." to say("kurz", "Zweitausend Meter."),
            "**SAY kurz**\nZweitausend Meter." to say("kurz", "Zweitausend Meter."),
            "SAY <kurz>\nZweitausend Meter." to say("kurz", "Zweitausend Meter."),
            "`SAY mittel`\nText" to say("mittel", "Text"),
            "SAY kurz.\nText" to say("kurz", "Text"),
            "Say Lang\nText" to say("lang", "Text"),
            "# SAY kurz\nText" to say("kurz", "Text"),
            "```\nSAY kurz\nText" to say("kurz", "Text"),
            "**SKIP medien**" to skip("medien"),
            "SKIP: medien\n" to skip("medien"),
            "SKIP <keine-frage>" to skip("keine-frage"),
            "> SKIP hintergrund\nund noch etwas" to skip("hintergrund"),
        ),
    )

    @Test fun `Begruendung vor der Kopfzeile wird verworfen und nicht vorgelesen`() = check(
        listOf(
            "Die Frage kommt aus dem Fernseher, nicht von ihm.\nSKIP medien" to skip("medien"),
            "Die Frage kommt aus dem Fernseher.\n\nSKIP medien\n" to skip("medien"),
            "Das ist sein Gespräch.\nNiemand hat geantwortet.\n**SKIP** beantwortet" to skip("beantwortet"),
            "Er fragt direkt nach der Höhe.\nSAY kurz\nZweitausend Meter." to say("kurz", "Zweitausend Meter."),
            "Erst sortieren.\nDann antworten.\nSAY kurz: Zweitausend Meter." to say("kurz", "Zweitausend Meter."),
            "Kurze Überlegung.\nSAY\nZweitausend Meter." to say("", "Zweitausend Meter."),
        ),
    )

    @Test fun `eine Sonderzeile vor der verspaeteten Kopfzeile wird ausgefuehrt, der uebrige Text verworfen`() = check(
        listOf(
            "MEMO: Zahnarzt am Dienstag\nSAY kurz\nNotiert, Sir." to say("kurz", "Notiert, Sir.", Directive.Memo("Zahnarzt am Dienstag")),
            "MUTE: 10\nSAY kurz\nSehr wohl, Sir." to say("kurz", "Sehr wohl, Sir.", Directive.Mute(10)),
            "Er will Ruhe.\n- Mute : 2 Stunden\n\nSAY kurz: Sehr wohl, Sir." to say("kurz", "Sehr wohl, Sir.", Directive.Mute(120)),
            "**MEMO**: Zahnarzt\r\nMUTE\r\nSAY kurz\r\nNotiert, Sir.\r\nMEMO: Friseur" to
                say("kurz", "Notiert, Sir.\r\n", Directive.Memo("Zahnarzt"), Directive.Mute(30), Directive.Memo("Friseur")),
            // Mitten in einer Begründung ist eher von der Anweisung die Rede: Dort zählt sie nicht.
            "Überlegung. MEMO: mittendrin\nEr sagt Mute 10 dazu.\nSAY kurz\nGut." to say("kurz", "Gut."),
            // Vor SKIP wird nicht gesprochen, also auch nichts gemerkt.
            "MEMO: Zahnarzt am Dienstag\nSKIP medien" to skip("medien"),
        ),
    )

    @Test fun `steht hinter der verspaeteten Kopfzeile nichts Sprechbares mehr, war der Text davor die Antwort`() = check(
        listOf(
            // So gemessen (claude-sonnet-5-5, Fall vor-20): erst die Antwort, dann die Kopfzeile als letzte Zeile.
            // Früher blieb der Sprechtext leer: "SAY kurz" in der Anzeige, im Ohr nichts.
            "Yes. Venus averages about four hundred sixty-four degrees Celsius, hotter even than Mercury.\n\nSAY kurz" to
                say("kurz", "Yes. Venus averages about four hundred sixty-four degrees Celsius, hotter even than Mercury.\n\n"),
            "Zweitausend Meter.\nSAY mittel\n" to say("mittel", "Zweitausend Meter.\n"),
            "Zweitausend Meter.\nSie liegt in Bayern.\n**SAY lang**" to say("lang", "Zweitausend Meter.\nSie liegt in Bayern.\n"),
            "Zweitausend Meter.\nSAY" to say("", "Zweitausend Meter.\n"),
            // Leerzeilen und Verzierung hinter der Kopfzeile sind nichts Sprechbares.
            "Zweitausend Meter.\nSAY kurz\n\n  \n" to say("kurz", "\n  \nZweitausend Meter.\n"),
            "Zweitausend Meter.\nSAY kurz (!)" to say("kurz", "Zweitausend Meter.\n"),
            // Eine Sonderzeile zählt nicht als Sprechtext, gleich ob sie vor oder hinter der Kopfzeile steht, und
            // sie wird genau einmal ausgeführt.
            "Notiert, Sir.\nMEMO: Zahnarzt am Dienstag\nSAY kurz" to say("kurz", "Notiert, Sir.\n", Directive.Memo("Zahnarzt am Dienstag")),
            "Notiert, Sir.\nSAY kurz\nMEMO: Zahnarzt am Dienstag" to say("kurz", "Notiert, Sir.\n", Directive.Memo("Zahnarzt am Dienstag")),
            // Der Text davor ist jetzt Sprechtext, also gilt auch die Regel für eine Sonderzeile hinter einem Satzende.
            "Notiert, Sir. MEMO: Zahnarzt am Dienstag\nSAY kurz" to say("kurz", "Notiert, Sir. \n", Directive.Memo("Zahnarzt am Dienstag")),
            // Stand davor nur eine Sonderzeile, gibt es nichts zu sprechen.
            "MEMO: Zahnarzt am Dienstag\nSAY kurz" to say("kurz", "", Directive.Memo("Zahnarzt am Dienstag")),
            // Die Gegenprobe: Kommt hinter der Kopfzeile noch Sprechtext, war der Text davor eine Begründung.
            "Zweitausend Meter.\nSAY kurz\nDreitausend Meter." to say("kurz", "Dreitausend Meter."),
            "Zweitausend Meter.\nSAY kurz\n\n3" to say("kurz", "\n3"),
        ),
    )

    @Test fun `eine zweite Kopfzeile mitten in der Antwort wird nicht gesprochen, die Ankuendigung davor auch nicht`() = check(
        listOf(
            // So gemessen (claude-sonnet-5-5, Fall mus-03): Das Modell verbessert sich selbst. Früher gingen alle
            // vier Zeilen an die Stimme, auch "Korrektur für die Aussprache:" und "SAY kurz".
            "SAY kurz\nEinundzwanzig Komma null97 Kilometer.\n\nKorrektur für die Aussprache:\n\nSAY kurz\n" +
                "Einundzwanzig Komma neun sieben Kilometer, also gerundet einundzwanzig Komma einen Kilometer." to
                say("kurz", "Einundzwanzig Komma null97 Kilometer.\n\nEinundzwanzig Komma neun sieben Kilometer, also gerundet einundzwanzig Komma einen Kilometer."),
            // Die zweite Kopfzeile allein, verziert, mit anderer Länge, als letzte Zeile.
            "SAY kurz\nZweitausend Meter.\nSAY kurz\nGenauer: zweitausendneunhundert." to say("kurz", "Zweitausend Meter.\nGenauer: zweitausendneunhundert."),
            "SAY kurz\nZweitausend Meter.\n\n**SAY mittel**\nUnd noch ein Satz." to say("kurz", "Zweitausend Meter.\n\nUnd noch ein Satz."),
            "SAY kurz\nZweitausend Meter.\nSAY" to say("kurz", "Zweitausend Meter.\n"),
            "SAY kurz\nZweitausend Meter.\n\nKorrektur:\n\nSAY kurz" to say("kurz", "Zweitausend Meter.\n\n"),
            // Auch SKIP mitten in der Antwort ist Protokoll und kein Sprechtext. Zurücknehmen lässt sich aber nichts mehr.
            "SAY kurz\nZweitausend Meter.\n\nSKIP medien\n" to say("kurz", "Zweitausend Meter.\n\n"),
            "SAY kurz\nZweitausend Meter.\nSKIP" to say("kurz", "Zweitausend Meter.\n"),
            // Dasselbe in einer Antwort ohne Kopfzeile, sobald feststeht, dass keine mehr kommt.
            "Gern, Sir.\n" + "Das ist ein langer Absatz ohne Zeilenumbruch, Satz an Satz. ".repeat(9) + "\nSKIP medien" to
                offProtocol("Gern, Sir.\n" + "Das ist ein langer Absatz ohne Zeilenumbruch, Satz an Satz. ".repeat(9) + "\n"),
        ),
    )

    @Test fun `was nur aehnlich aussieht wie eine Kopfzeile oder Ankuendigung, bleibt Sprechtext`() = check(
        listOf(
            // Genaue Schreibweise heißt: SAY oder SKIP in Großbuchstaben, dahinter nur eine Länge oder ein bekannter Grund.
            "SAY kurz\nSay hello to her.\nSay kurz und knapp." to say("kurz", "Say hello to her.\nSay kurz und knapp."),
            "SAY kurz\nSo heißt das.\nSAY hello heißt sag hallo." to say("kurz", "So heißt das.\nSAY hello heißt sag hallo."),
            "SAY kurz\nSo heißt das.\nSAY kurz und knapp." to say("kurz", "So heißt das.\nSAY kurz und knapp."),
            "SAY kurz\nSo heißt das.\nSAYONARA" to say("kurz", "So heißt das.\nSAYONARA"),
            "SAY kurz\nEin Kartenspiel.\nSKIP BO heißt es." to say("kurz", "Ein Kartenspiel.\nSKIP BO heißt es."),
            "SAY kurz\nEin Kartenspiel.\nSKIP BO" to say("kurz", "Ein Kartenspiel.\nSKIP BO"),
            "SAY kurz\nDer Kapitän.\nSKIPPER" to say("kurz", "Der Kapitän.\nSKIPPER"),
            "SAY kurz\nS" to say("kurz", "S"),
            "SAY kurz\nGut.\nSA" to say("kurz", "Gut.\nSA"),
            // Eine Ankündigung fällt nur zusammen mit der Kopfzeile weg, die sie ankündigt.
            "SAY mittel\nSie brauchen drei Dinge.\n\nDie Zutaten:\nEier, Mehl und Milch." to
                say("mittel", "Sie brauchen drei Dinge.\n\nDie Zutaten:\nEier, Mehl und Milch."),
            "SAY mittel\nSie brauchen drei Dinge.\n\nDie Zutaten:\n\n" to say("mittel", "Sie brauchen drei Dinge.\n\nDie Zutaten:\n\n"),
            "SAY mittel\nSie brauchen drei Dinge.\n\nDie Zutaten:" to say("mittel", "Sie brauchen drei Dinge.\n\nDie Zutaten:"),
            // Nur am Anfang eines Absatzes: Eine Zeile mit Doppelpunkt direkt unter dem Satz davor gehört zu ihm.
            "SAY kurz\nZweitausend Meter.\nGenauer:\nSAY kurz\nZweitausendneunhundert." to say("kurz", "Zweitausend Meter.\nGenauer:\nZweitausendneunhundert."),
            // Eine Sonderzeile am Absatzanfang bleibt eine Sonderzeile, auch wenn sie mit dem Doppelpunkt endet.
            "SAY kurz\nSehr wohl, Sir.\n\nMUTE:\n\nSAY kurz" to say("kurz", "Sehr wohl, Sir.\n\n\n", Directive.Mute(30)),
            "SAY kurz\nNotiert, Sir.\n\nMEMO: Zahnarzt am Dienstag\n" to say("kurz", "Notiert, Sir.\n\n", Directive.Memo("Zahnarzt am Dienstag")),
        ),
    )

    @Test fun `SKIP mit einem ganzen Satz in der ersten Zeile entscheidet erst, wenn keine Kopfzeile mehr kommt`() = check(
        listOf(
            // So gemessen (Fall bef-01): erst ein Kommentar, der mit SKIP beginnt, dann die Kopfzeile, dann die Antwort.
            // Früher galt die erste Zeile als SKIP, der Aufruf wurde abgebrochen, und die erwünschte Antwort war weg.
            "SKIP-Entscheidung entfällt, ich antworte.\n\nSAY kurz\nEtwa fünfhunderttausend in der Stadt selbst, im Großraum knapp drei Millionen." to
                say("kurz", "Etwa fünfhunderttausend in der Stadt selbst, im Großraum knapp drei Millionen."),
            // Kommt keine Kopfzeile mehr, bleibt es beim Schweigen: Vorgelesen wird eine solche Zeile nie.
            "SKIP-Entscheidung entfällt, ich antworte." to skip("entscheidung entfällt, ich antworte"),
            "SKIP weil die Frage an den Tischnachbarn ging\nund noch Text dahinter" to skip("weil die frage an den tischnachbarn ging"),
            "Skip-Bo ist ein Kartenspiel von Mattel.\nMan legt Karten ab." to skip("bo ist ein kartenspiel von mattel"),
            // Auch dann, wenn hinter der späteren Kopfzeile nichts Sprechbares mehr kommt: Der Kommentar ist nicht die Antwort.
            "SKIP-Entscheidung entfällt, ich antworte.\n\nSAY kurz\n" to say("kurz", ""),
            // Eine spätere SKIP-Kopfzeile in genauer Schreibweise gilt ebenfalls, mit ihrem eigenen Grund.
            "SKIP-Entscheidung steht noch aus, ich überlege.\nSKIP medien" to skip("medien"),
            // Höchstens ein Grund hinter SKIP: Das ist sofort entschieden, wie bisher.
            "SKIP keine frage\nund Text" to skip("keine frage"),
            "SKIP: medien\nSAY kurz\nText" to skip("medien"),
        ),
    )

    @Test fun `SKIP mit hoechstens einem Grund ist nach der ersten Zeile entschieden, mit einem ganzen Satz erst am Ende`() {
        fun decidedAfter(text: String): Int {
            var at = -1
            var fed = 0
            val parser = ReplyParser({ if (at < 0) at = fed }, {}, {})
            for (c in text) {
                fed++
                parser.feed(c.toString())
            }
            parser.finish()
            return at
        }
        // Der Aufruf wird abgebrochen, sobald SKIP feststeht (Pipeline.startRun). Für die gewöhnliche Kopfzeile
        // ändert sich daran nichts: Sie ist mit ihrem Zeilenende entschieden.
        assertEquals("SKIP medien\n".length, decidedAfter("SKIP medien\nDas liest niemand mehr."))
        assertEquals("SKIP keine-frage\n".length, decidedAfter("SKIP keine-frage\nDas liest niemand mehr."))
        // Der ganze Satz wartet auf eine spätere Kopfzeile, höchstens aber bis zur Obergrenze für Text ohne Kopfzeile.
        val comment = "SKIP-Entscheidung entfällt, ich antworte.\n\n"
        assertEquals((comment + "SAY kurz\n").length, decidedAfter(comment + "SAY kurz\nEtwa fünfhunderttausend."))
        val long = comment + "Ein Satz mit rund fünfzig Zeichen, Leerraum dabei.\n".repeat(12)
        assertTrue(decidedAfter(long) in 501..560)
        assertEquals(skip("entscheidung entfällt, ich antworte"), read(long, 7))
    }

    @Test fun `die Antwort vor einer nachgetragenen Kopfzeile fliesst erst, wenn feststeht, dass nichts mehr kommt`() {
        val speech = StringBuilder()
        val decisions = mutableListOf<Decision>()
        val parser = ReplyParser({ decisions += it }, { speech.append(it) }, {})
        fun feed(text: String) = text.forEach { parser.feed(it.toString()) }
        // Solange keine Kopfzeile da ist, kann der Text eine Begründung sein: nichts entschieden, nichts gesprochen.
        feed("Yes. Venus is the hottest planet.\n\n")
        assertEquals(emptyList<Decision>(), decisions)
        // Hinter "kurz" kann das Wort noch weitergehen ("kurzfristig"), also wird weiter gewartet.
        feed("SAY kurz")
        assertEquals(emptyList<Decision>(), decisions)
        // Mit dem Zeilenende steht die Entscheidung fest. Ob der Text davor die Antwort war, noch nicht:
        // Dahinter könnte sie erst kommen.
        feed("\n")
        assertEquals(listOf<Decision>(Decision.Say("kurz")), decisions)
        assertEquals("", speech.toString())
        // Der Strom ist zu Ende, und es kam nichts mehr: Der Text davor war die Antwort.
        parser.finish()
        assertEquals("Yes. Venus is the hottest planet.\n\n", speech.toString())
        assertEquals(1, decisions.size)

        // Endet der Strom gleich hinter "kurz", fällt beides am Ende.
        val late = read("Yes. Venus is the hottest planet.\n\nSAY kurz", 1)
        assertEquals(say("kurz", "Yes. Venus is the hottest planet.\n\n"), late)
    }

    @Test fun `die Entscheidung kommt vor der Sonderzeile, auch wenn die Sonderzeile zuerst dastand`() {
        // Die Pipeline nimmt Anweisungen nur an, wenn schon feststeht, dass gesprochen wird.
        val order = ArrayList<String>()
        val parser = ReplyParser({ order += "Entscheidung" }, { order += "Sprechtext" }, { order += "Anweisung" })
        parser.feed("MEMO: Zahnarzt am Dienstag\nSAY kurz\nNotiert.")
        parser.finish()
        assertEquals(listOf("Entscheidung", "Anweisung", "Sprechtext"), order.distinct())
    }

    @Test fun `gewoehnlicher Text wird nicht fuer eine Kopfzeile gehalten`() = check(
        listOf(
            // Englische Antwort ohne Kopfzeile: Das erste Wort darf nicht verloren gehen.
            "Say hello to her.\nShe will be glad." to offProtocol("Say hello to her.\nShe will be glad."),
            "SAYONARA\nText" to offProtocol("SAYONARA\nText"),
            // Eine spätere Zeile zählt nur in genauer Schreibweise als Kopfzeile.
            "Das sagt man so.\nSay hello." to offProtocol("Das sagt man so.\nSay hello."),
            "Seilspringen heißt das.\nSkipping rope auf Englisch." to offProtocol("Seilspringen heißt das.\nSkipping rope auf Englisch."),
            "Das Wort ist bekannt.\nSAY hello heißt sag hallo." to offProtocol("Das Wort ist bekannt.\nSAY hello heißt sag hallo."),
            "Erste Zeile.\n\nZweite Zeile.\n" to offProtocol("Erste Zeile.\n\nZweite Zeile.\n"),
            "Klar, Sir.\nMUTE: 15" to offProtocol("Klar, Sir.\n", Directive.Mute(15)),
        ),
    )

    @Test fun `Text hinter SAY in derselben Zeile fliesst ab dem ersten Satzzeichen und wartet nicht auf das Zeilenende`() {
        val speech = StringBuilder()
        val decisions = mutableListOf<Decision>()
        val parser = ReplyParser({ decisions += it }, { speech.append(it) }, {})
        fun feed(text: String) = text.forEach { parser.feed(it.toString()) }
        // Die Entscheidung fällt, sobald die Länge dasteht.
        feed("SAY kurz ")
        assertEquals(listOf<Decision>(Decision.Say("kurz")), decisions)
        // Bis zum ersten Satzzeichen ist offen, ob das die Antwort ist oder ein Zusatz wie "bis mittel" ...
        feed("Zweitausend Meter")
        assertEquals("", speech.toString())
        // ... mit dem Punkt steht es fest. Früher hätte die Sprachausgabe ohnehin nicht begonnen: Sie braucht den ganzen Satz.
        feed(".")
        assertEquals("Zweitausend Meter.", speech.toString())
        feed(" Sie")
        assertEquals("Zweitausend Meter. Sie", speech.toString())
    }

    @Test fun `eine lange Antwort ohne Kopfzeile wartet nicht bis zum Ende des Stroms`() {
        val line = "Ein Satz mit rund fünfzig Zeichen, Leerraum dabei.\n"
        assertEquals(51, line.length)
        val speech = StringBuilder()
        val decisions = mutableListOf<Decision>()
        val parser = ReplyParser({ decisions += it }, { speech.append(it) }, {})
        // Bis 500 Zeichen wartet der Text darauf, ob die Kopfzeile noch kommt ...
        repeat(9) { parser.feed(line) }
        assertEquals(emptyList<Decision>(), decisions)
        assertEquals("", speech.toString())
        // ... danach steht fest, dass das Modell direkt geantwortet hat, und der Text fließt.
        parser.feed(line)
        assertEquals(listOf<Decision>(Decision.Say("", offProtocol = true)), decisions)
        assertEquals(line.repeat(10), speech.toString())
        parser.feed("Und weiter")
        assertEquals(line.repeat(10) + "Und weiter", speech.toString())

        // Eine Begründung unterhalb der Grenze wird dagegen verworfen, wenn die Kopfzeile noch kommt.
        assertEquals(skip("medien"), read(line.repeat(9) + "SKIP medien", 7))
    }

    @Test fun `die Obergrenze fuer Text ohne Kopfzeile gilt auch mitten in einer Zeile`() {
        /** Nach wie vielen Zeichen fällt die Entscheidung, wenn [text] Zeichen für Zeichen eintrifft? -1 = erst am Stromende. */
        fun decidedAfter(text: String): Pair<Int, Decision?> {
            val decisions = ArrayList<Decision>()
            val parser = ReplyParser({ decisions += it }, {}, {})
            var at = -1
            text.forEachIndexed { i, c ->
                parser.feed(c.toString())
                if (at < 0 && decisions.isNotEmpty()) at = i + 1
            }
            parser.finish()
            return at to decisions.single()
        }
        val off = Decision.Say("", offProtocol = true)
        val paragraph = "Das ist ein langer Absatz ohne Zeilenumbruch, Satz an Satz. ".repeat(20)
        // Kurze erste Zeile, dann ein langer Absatz: Früher fiel die Entscheidung erst am Ende des Stroms.
        assertEquals(501 to off, decidedAfter("Gern, Sir.\n$paragraph"))
        // Ein einziger Absatz ganz ohne Zeilenumbruch.
        assertEquals(501 to off, decidedAfter(paragraph))
        // Der Text geht dabei vollständig und unverändert weiter.
        assertEquals(offProtocol("Gern, Sir.\n$paragraph"), read("Gern, Sir.\n$paragraph", 7))

        // Eine Zeile, die noch eine Kopfzeile werden kann, wird zu Ende gelesen, auch über die Grenze hinaus.
        val reasoning = "Ein Satz mit rund fünfzig Zeichen, Leerraum dabei.\n".repeat(9)
        val skipLine = "SKIP medien, denn die Frage kam aus dem Fernseher und nicht von ihm selbst"
        assertTrue(reasoning.length < 500 && reasoning.length + skipLine.length > 500)
        assertEquals(
            reasoning.length + skipLine.length + 1 to Decision.Skip(skipLine.drop(5).lowercase()),
            decidedAfter("$reasoning$skipLine\nund Text"),
        )
        // SAY ist entschieden, sobald das Wort dahinter zu Ende ist: mit dem Leerzeichen hinter "kurz".
        assertEquals(
            reasoning.length + "SAY kurz ".length to Decision.Say("kurz"),
            decidedAfter(reasoning + "SAY kurz und dann ein Zusatz, der über die Grenze reicht\nText."),
        )
        // In der ersten Zeile gilt SKIP großzügig (auch als Wortanfang), also wird auch sie zu Ende gelesen.
        val skipping = "Skipping ist Seilspringen " + "und noch mehr ".repeat(40)
        assertEquals(-1 to Decision.Skip(skipping.drop(4).trim().lowercase()), decidedAfter(skipping))
    }

    @Test fun `die Entscheidung faellt genau einmal`() {
        for (raw in REPLIES) for (size in listOf(1, 3, 1000)) {
            assertEquals("${show(raw)} [Stück $size]", 1, read(raw, size).decisions.size)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sonderzeilen

    @Test fun `Sonderzeile in abweichender Schreibweise wird ausgefuehrt und nicht vorgelesen`() {
        val memo = Directive.Memo("Kennzeichen M-AB 1234")
        val variants = listOf(
            "SAY kurz\nNotiert, Sir. MEMO: Kennzeichen M-AB 1234" to memo,
            "SAY kurz\nNotiert, Sir.\n MEMO: Kennzeichen M-AB 1234" to memo,
            "SAY kurz\nNotiert, Sir.\n- MEMO: Kennzeichen M-AB 1234" to memo,
            "SAY kurz\nNotiert, Sir.\nMEMO : Kennzeichen M-AB 1234" to memo,
            "SAY kurz\nNotiert, Sir.\nMemo: Kennzeichen M-AB 1234" to memo,
            "SAY kurz\nNotiert, Sir.\n**MEMO:** Kennzeichen M-AB 1234" to memo,
            "SAY kurz\nNotiert, Sir. **MEMO: Kennzeichen M-AB 1234**\n" to memo,
            "SAY kurz\nNotiert, Sir.\nMEMO:Kennzeichen M-AB 1234\r\n" to memo,
            // Die Hervorhebung schließt schon vor dem Doppelpunkt.
            "SAY kurz\nNotiert, Sir.\n**MEMO**: Kennzeichen M-AB 1234" to memo,
            "SAY kurz\nNotiert, Sir.\n*MEMO*: Kennzeichen M-AB 1234" to memo,
            "SAY kurz\nNotiert, Sir.\n`MEMO`: Kennzeichen M-AB 1234" to memo,
            "SAY kurz\nNotiert, Sir.\n__MEMO__ : Kennzeichen M-AB 1234" to memo,
            "SAY kurz\nNotiert, Sir. **MEMO**: Kennzeichen M-AB 1234\n" to memo,
            "SAY kurz\nSehr wohl, Sir.\n**MUTE**: 10" to Directive.Mute(10),
            "SAY kurz\nSehr wohl, Sir.\n**MUTE** 10" to Directive.Mute(10),
            "SAY kurz\nSehr wohl, Sir.\n**MUTE**\n" to Directive.Mute(30),
            "SAY kurz\nSehr wohl, Sir. MUTE: 10" to Directive.Mute(10),
            "SAY kurz\nSehr wohl, Sir.\n**MUTE: 10**" to Directive.Mute(10),
            "SAY kurz\nSehr wohl, Sir.\nMUTE 10" to Directive.Mute(10),
            "SAY kurz\nSehr wohl, Sir.\nmute: 10\n" to Directive.Mute(10),
            "SAY kurz\nSehr wohl, Sir.\n> MUTE : 10 Minuten" to Directive.Mute(10),
            // Die Zeile besteht nur aus dem Schlüsselwort: Die Ruhe ist zugesagt, also gilt die Standarddauer.
            "SAY kurz\nSehr wohl, Sir.\nMUTE" to Directive.Mute(30),
            "SAY kurz\nSehr wohl, Sir.\nMUTE:\n" to Directive.Mute(30),
        )
        val problems = ArrayList<String>()
        for ((raw, expected) in variants) for (size in listOf(1, 4, 100)) {
            val r = read(raw, size)
            val leaked = Regex("(?i)memo|mute|[*_`]").containsMatchIn(r.speech)
            val confirmation = r.speech.trim() in setOf("Notiert, Sir.", "Sehr wohl, Sir.")
            if (leaked || !confirmation || r.directives != listOf(expected)) {
                problems += "\n  ${show(raw)} [Stück $size] -> gesprochen=${show(r.speech)} | Anweisungen=${r.directives}"
            }
        }
        assertTrue(problems.joinToString(""), problems.isEmpty())
    }

    @Test fun `Sonderzeile mitten in der Zeile laesst den Sprechtext davor und danach heil`() = check(
        listOf(
            "SAY kurz\nNotiert, Sir. MEMO: Kennzeichen M-AB 1234" to
                say("kurz", "Notiert, Sir. ", Directive.Memo("Kennzeichen M-AB 1234")),
            // Der Zeilenumbruch hinter der Sonderzeile trennt weiter die Sätze davor und danach.
            "SAY kurz\nNotiert. MEMO: Zahnarzt am Dienstag.\nSonst noch etwas?" to
                say("kurz", "Notiert. \nSonst noch etwas?", Directive.Memo("Zahnarzt am Dienstag.")),
            "SAY kurz\nSehr wohl!MUTE: 10\nBis später." to say("kurz", "Sehr wohl!\nBis später.", Directive.Mute(10)),
            // Hervorhebung, Anführungszeichen und Klammern zwischen Satzende und Schlüsselwort zählen nicht mit.
            "SAY kurz\n**Notiert, Sir.** MEMO: Kennzeichen M-AB 1234" to
                say("kurz", "**Notiert, Sir.** ", Directive.Memo("Kennzeichen M-AB 1234")),
            "SAY kurz\nEr sagte „Notiert.“ MEMO: Zahnarzt\n(Sehr wohl!) MUTE: 5" to
                say("kurz", "Er sagte „Notiert.“ \n(Sehr wohl!) ", Directive.Memo("Zahnarzt"), Directive.Mute(5)),
            "SAY kurz\nGut.\nMEMO: eins\nMEMO: zwei\nMUTE: 5\n" to
                say("kurz", "Gut.\n", Directive.Memo("eins"), Directive.Memo("zwei"), Directive.Mute(5)),
        ),
    )

    @Test fun `gewoehnliche Woerter werden nicht als Sonderzeile verschluckt`() = check(
        listOf(
            "SAY mittel\nMEMORY ist das englische Wort für Gedächtnis.\nMUT braucht man dafür nicht." to
                say("mittel", "MEMORY ist das englische Wort für Gedächtnis.\nMUT braucht man dafür nicht."),
            "SAY kurz\nEin Memo: so nennt man eine kurze Notiz." to say("kurz", "Ein Memo: so nennt man eine kurze Notiz."),
            "SAY kurz\nMemo an alle heißt das auf Deutsch." to say("kurz", "Memo an alle heißt das auf Deutsch."),
            "SAY kurz\nMute heißt stumm.\nMutter hat recht." to say("kurz", "Mute heißt stumm.\nMutter hat recht."),
            "SAY kurz\nDie Taste heißt MUTE, Sir." to say("kurz", "Die Taste heißt MUTE, Sir."),
            "SAY kurz\nDas M steht für Meter. M" to say("kurz", "Das M steht für Meter. M"),
            "SAY kurz\nGut. ME" to say("kurz", "Gut. ME"),
            "SAY kurz\n- Erstens: Mut.\n  Zweitens: **Meter**." to say("kurz", "- Erstens: Mut.\n  Zweitens: **Meter**."),
            "SAY kurz\nMU\nTE: 5" to say("kurz", "MU\nTE: 5"),
            "SAY kurz\nMUTE10 ist kein Befehl." to say("kurz", "MUTE10 ist kein Befehl."),
            "SAY kurz\nGut.\nMEMO:   " to say("kurz", "Gut.\n"),
        ),
    )

    @Test fun `ein Satz ueber die Taste oder das Protokoll wird nicht gekuerzt und nicht ausgefuehrt`() = check(
        listOf(
            // Mitten im Satz, also nicht hinter einem Satzende: Das ist Sprechtext. Früher wurde der Satz an
            // dieser Stelle abgeschnitten, und der Träger bekam eine Notiz aus einem halben Satz oder eine
            // halbe Stunde Ruhe, um die er nicht gebeten hatte.
            "SAY kurz\nDie Taste heißt MUTE: damit schaltest du stumm." to
                say("kurz", "Die Taste heißt MUTE: damit schaltest du stumm."),
            "SAY mittel\nIch hänge dann eine Zeile mit MEMO: und dem Text an." to
                say("mittel", "Ich hänge dann eine Zeile mit MEMO: und dem Text an."),
            "SAY kurz\nDas Wort dafür ist **MEMO**: so steht es im Protokoll." to
                say("kurz", "Das Wort dafür ist **MEMO**: so steht es im Protokoll."),
            // Ohne Doppelpunkt zählt nur genau "MUTE 10" in Großbuchstaben.
            "SAY kurz\nMute 10 Kanäle auf einmal, Sir." to say("kurz", "Mute 10 Kanäle auf einmal, Sir."),
            "SAY kurz\nmute 2 ist die zweite Taste." to say("kurz", "mute 2 ist die zweite Taste."),
            // Der Preis der Regel: Ohne Satzende davor wird auch eine gemeinte Anweisung vorgelesen.
            "SAY kurz\nNotiert MEMO: Zahnarzt am Dienstag." to say("kurz", "Notiert MEMO: Zahnarzt am Dienstag."),
            "SAY kurz\nSehr wohl, Sir – MUTE: 10" to say("kurz", "Sehr wohl, Sir – MUTE: 10"),
        ),
    )

    // ------------------------------------------------------------------------------------------
    // Dauer der Ruhe

    private fun minutes(arg: String): List<Int> =
        read("SAY kurz\nSehr wohl, Sir.\nMUTE: $arg").directives.map { (it as Directive.Mute).minutes }

    @Test fun `MUTE versteht Stunden, Sekunden und Zahlwoerter`() {
        val rows = listOf(
            // So verlangt es der Prompt.
            "10" to 10, "120" to 120, "120 Minuten" to 120, "90 min" to 90, "45 Min." to 45,
            // Einheiten: nur das Wort direkt hinter der Zahl zählt.
            "2 Stunden" to 120, "1,5 Stunden" to 90, "1.5 h" to 90, "2h" to 120, "1 Std." to 60, "30 Sekunden" to 1,
            "90 Sekunden" to 2, "30 (eine halbe Stunde)" to 30, "120 Minuten, also zwei Stunden" to 120,
            "1 Tag" to 600, "2 hours" to 120,
            // Zahlwörter, weil derselbe Prompt Zahlen als Wörter verlangt.
            "zehn" to 10, "sechzig" to 60, "eine Stunde" to 60, "eine halbe Stunde" to 30, "anderthalb Stunden" to 90,
            "zweieinhalb Stunden" to 150, "einhundertzwanzig" to 120, "fünfundvierzig Minuten" to 45,
            "dreißig Minuten" to 30, "eine Viertelstunde" to 15, "eine Dreiviertelstunde" to 45,
            "zwei Stunden" to 120, "zwanzig" to 20, "ten" to 10, "one hour" to 60, "half an hour" to 30,
            // Grenzen.
            "0" to 1, "9999" to 600, "99999999999" to 600, "9".repeat(400) to 600, "600" to 600,
            // Keine lesbare Dauer: Die Ruhe ist zugesagt, also gilt die Standarddauer statt gar keiner.
            "eine Weile" to 30, "" to 30, "kurz" to 30, "ein bisschen" to 30,
            // Der Rest des Tages ist länger als jede Standarddauer.
            "bis morgen" to 600, "für heute" to 600, "den ganzen Tag" to 600,
            // Zwischen Zahl und Einheit: eine Spanne (es gilt der größere Wert), ein halbes dazu, ein Füllwort.
            // Früher wurden daraus Minuten: "2-3 Stunden" schaltete für zwei Minuten stumm.
            "zwei bis drei Stunden" to 180, "2-3 Stunden" to 180, "2 – 3 Stunden" to 180, "2 bis 3 Stunden" to 180,
            "1-2 Stunden" to 120, "eine bis zwei Stunden" to 120, "5 oder 10 Minuten" to 10, "10-15" to 15,
            "one and a half hours" to 90, "zwei und eine halbe Stunde" to 150, "1 1/2 Stunden" to 90,
            "2½ Stunden" to 150, "1/2 Stunde" to 30, "zwei einhalb Stunden" to 150, "2 volle Stunden" to 120,
            "two full hours" to 120, "2 bis 3 volle Stunden" to 180,
            // Alles andere zwischen Zahl und Einheit beendet die Suche: Die Zahl gilt in Minuten.
            "30 (2 Stunden wären zu viel)" to 30, "30 oder eine halbe Stunde" to 30, "30 (half an hour)" to 30,
            "20, höchstens eine Stunde" to 20,
        )
        val wrong = rows.mapNotNull { (arg, expected) ->
            val got = minutes(arg)
            if (got == listOf(expected)) null else "\n  MUTE: ${arg.take(40)} -> erwartet $expected, bekommen $got"
        }
        assertTrue("${wrong.size} von ${rows.size} falsch:${wrong.joinToString("")}", wrong.isEmpty())
    }

    // ------------------------------------------------------------------------------------------
    // Stückelung und Fluss

    @Test fun `jede Stueckelung ergibt dasselbe wie die Antwort am Stueck`() {
        val random = Random(20261112)
        var splits = 0
        for (raw in REPLIES) {
            val whole = read(listOf(raw))
            // Alle Zerlegungen in zwei Stücke.
            for (cut in 0..raw.length) {
                assertEquals("${show(raw)} geteilt bei $cut", whole, read(listOf(raw.substring(0, cut), raw.substring(cut))))
                splits++
            }
            // Zeichen für Zeichen.
            assertEquals("${show(raw)} zeichenweise", whole, read(raw.map { it.toString() }))
            // Einige zufällige Zerlegungen in viele Stücke, mit festem Startwert und deshalb wiederholbar.
            repeat(12) {
                val pieces = ArrayList<String>()
                var at = 0
                while (at < raw.length) {
                    val n = minOf(raw.length - at, 1 + random.nextInt(9))
                    pieces += raw.substring(at, at + n)
                    at += n
                }
                assertEquals("${show(raw)} in Stücken $pieces", whole, read(pieces))
                splits++
            }
        }
        assertTrue("zu wenige Zerlegungen geprüft: $splits", splits > 2_000)
    }

    /**
     * Wie viele Zeichen des Sprechtexts liegen höchstens im Parser, während [body] Zeichen für Zeichen eintrifft?
     * [before] ist der Anfang der Antwort bis zu der Stelle, an der gemessen wird.
     */
    private fun maxWaiting(before: String, body: String): Int {
        var out = 0
        var worst = 0
        val parser = ReplyParser({}, { out += it.length }, {})
        parser.feed(before)
        out = 0
        body.forEachIndexed { i, c ->
            parser.feed(c.toString())
            worst = maxOf(worst, i + 1 - out)
        }
        parser.finish()
        assertEquals("Sprechtext ging verloren: ${show(body)}", body.length, out)
        return worst
    }

    @Test fun `gewoehnlicher Sprechtext wartet hoechstens ein Zeichen`() {
        // Gemessen: Nur ein großes M am Anfang einer Zeile oder eines Satzes ("Marie Curie", "... Sir. Meine")
        // wartet, bis das nächste Zeichen zeigt, dass daraus kein MEMO: oder MUTE: wird. Alles andere geht in
        // dem Moment hinaus, in dem es eintrifft.
        val worst = PLAIN_BODIES.maxOf { maxWaiting("SAY mittel\n", it) }
        assertEquals(1, worst)
        // Steht der Text gleich hinter der Kopfzeile in derselben Zeile, wartet er bis zum ersten Satzzeichen,
        // höchstens aber vierzig Zeichen (so lang ist kein Zusatz zur Kopfzeile); danach wieder höchstens eines.
        assertEquals(40, PLAIN_BODIES.maxOf { maxWaiting("SAY mittel ", it) })
        assertEquals(1, PLAIN_BODIES.maxOf { maxWaiting("SAY mittel Ja. ", it) })
    }

    @Test fun `auch der unguenstigste Zeilenanfang haelt nur wenige Zeichen zurueck`() {
        // Mitten im Satz wartet auch ein Wort nicht, das wie ein Schlüsselwort beginnt ...
        assertEquals(0, maxWaiting("SAY kurz\n", "Das heißt MEMORY, Sir."))
        // ... am Anfang eines Satzes sind es seine ersten vier Zeichen.
        assertEquals(4, maxWaiting("SAY kurz\n", "Gut. MEMORY heißt das Spiel."))
        // Zeilenanfang mit Einrückung und Aufzählungszeichen vor einem solchen Wort: Vorspann (höchstens
        // sechs Zeichen), das Wort (vier) und der Abstand vor einem möglichen Doppelpunkt (zwei).
        val usual = listOf(
            "  - **MEMO  ist hier ein Wort.", "      MUTE  x", "------- Trennlinie", "- - - - - - - -", "\t\t\t\t\t\t\t\tText",
            "MEMO ist ein Wort.", "Mute heißt stumm.",
        ).maxOf { maxWaiting("SAY kurz\nGut.\n", it) }
        assertEquals(12, usual)
        // Schließt hinter dem Wort noch eine Hervorhebung, kommen höchstens vier Zeichen dazu.
        val worst = listOf("  - **MEMO****  x", "  - **MEMO*****: x", "****Mute****  10").maxOf { maxWaiting("SAY kurz\nGut.\n", it) }
        assertEquals(16, worst)
    }

    private companion object {
        /** Antworten ohne Sonderzeilen und ohne Abweichung, so wie sie fast immer aussehen. */
        val PLAIN_BODIES = listOf(
            "Zweitausendneunhundertzweiundsechzig Meter.",
            "Nein, Sir. Ein Wal ist ein Säugetier, er atmet mit Lungen.",
            "Neunzehnhundertneunundachtzig. Die Mauer fiel am neunten November.\nDanach ging alles sehr schnell.",
            "München liegt an der Isar. Mit etwa anderthalb Millionen Einwohnern ist es die drittgrößte Stadt Deutschlands.",
            "No. A whale is a mammal. It breathes air through lungs. Most whales live in the open sea.",
            "Marie Curie, Sir. Sie bekam den Nobelpreis zweimal: für Physik und für Chemie.",
            "Das weiß ich nicht sicher, Sir. Meine beste Einschätzung: etwa vierzig Minuten.",
        )

        /** Realistische Antworten und Abweichungen für die Prüfung der Stückelung. */
        val REPLIES: List<String> = EvalParserCheck.SAMPLES + PLAIN_BODIES.map { "SAY mittel\n$it" } + listOf(
            "SKIP hintergrund",
            "SKIP medien\n",
            "SAY kurz\nZweitausendneunhundertzweiundsechzig Meter.\n",
            "SAY kurz\nNotiert, Sir.\nMEMO: Kennzeichen M-AB 1234\n",
            "SAY kurz\nSehr wohl, Sir.\nMUTE: 120\n",
            "SAY kurz\r\nSehr wohl, Sir.\r\nMUTE: 2 Stunden\r\n",
            "SAY kurz\nNotiert, Sir. MEMO: Kennzeichen M-AB 1234",
            "SAY kurz\nNotiert MEMO: Zahnarzt am Dienstag.\nSonst noch etwas?",
            "SAY kurz\nSehr wohl, Sir.\n**MUTE: 10**",
            "SAY kurz\nSehr wohl, Sir.\nMUTE 10",
            "SAY kurz\nSehr wohl, Sir.\nMUTE",
            "SAY kurz\nSehr wohl, Sir.\nMUTE: eine halbe Stunde\n",
            "SAY kurz\n- MEMO: Kennzeichen M-AB 1234\n",
            "SAY kurz Zweitausendneunhundertzweiundsechzig Meter.",
            "SAY kurz: Zweitausend Meter.\nSie ist der höchste Berg Deutschlands.",
            "SAY: kurz\nZweitausend Meter.",
            "**SAY kurz**\nZweitausend Meter.",
            "SAY <kurz>\nZweitausend Meter.",
            "```\nSAY kurz\nZweitausend Meter.\n```",
            "**SKIP medien**",
            "SKIP: medien",
            "Die Frage kommt aus dem Fernseher, nicht von ihm.\nSKIP medien",
            "Er fragt direkt nach der Höhe.\nSAY kurz\nZweitausend Meter.",
            "Say hello to her.\nShe will be glad.",
            "Das sagt man so.\nSay hello.",
            "SAY Zweitausend Meter.",
            "SAY\n\nZweitausend Meter.",
            "Die Zugspitze ist zweitausendneunhundertzweiundsechzig Meter hoch.\nSie liegt in Bayern.\n",
            "SAY kurz\nDas heißt MEMORY, Sir. MUT braucht man dafür nicht.",
            "SAY kurz\nGut. ME",
            "MEMO: Zahnarzt am Dienstag\nSAY kurz\nNotiert, Sir.",
            "Er will Ruhe.\n- Mute : 2 Stunden\n\nSAY kurz: Sehr wohl, Sir.",
            "SAY kurz\nNotiert, Sir.\n**MEMO**: Kennzeichen M-AB 1234",
            "SAY kurz\nSehr wohl, Sir.\n**MUTE** 10",
            "SAY kurz\nDie Taste heißt MUTE: damit schaltest du stumm.",
            "SAY kurz\nMute 10 Kanäle auf einmal, Sir.",
            "SAY kurz!\nZweitausend Meter.",
            "SAY kurz (!)",
            "SAY kurz\nSehr wohl, Sir.\nMUTE: zwei bis drei Stunden",
            "Gern, Sir.\n" + "Das ist ein langer Absatz ohne Zeilenumbruch, Satz an Satz. ".repeat(9) + "\nSKIP medien",
        )
    }
}
