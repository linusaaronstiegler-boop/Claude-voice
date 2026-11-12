package app.claudevoice.core

/**
 * Sicherheitsnetz vor der Sprachausgabe: macht aus einem Satz etwas, das eine Stimme vorlesen kann.
 *
 * Warum: Das Antwort-Modell soll Zahlen als Wörter schreiben, rutscht aber gelegentlich aus. Das
 * Sprachmodell (eleven_flash_v2_5) normalisiert Ziffern, Symbole und Abkürzungen nicht selbst, also
 * würde "2962 m" oder "3,5 %" falsch oder gar nicht gelesen. Hier wird deshalb alles ausgeschrieben.
 *
 * Aufbau: eine feste Kette von Regeln, die Spezialfälle zuerst (Datum, Uhrzeit, Telefonnummer ...),
 * die allgemeine Zahl zuletzt. Jede Regel ersetzt ihre Ziffern durch Wörter; was eine frühere Regel
 * erledigt hat, sieht eine spätere gar nicht mehr. Die Reihenfolge ist also zugleich die Rangfolge.
 *
 * Drei Zusagen, auf die sich der Aufrufer verlassen kann:
 *  - Idempotent: Im Ergebnis stehen keine Ziffern (0 bis 9) und keines der behandelten Zeichen mehr
 *    (Markdown, Klammern, % € $ ° & + = ...); ein zweiter Durchlauf ändert nichts.
 *  - Text ohne Ziffern, Symbole und Abkürzungen kommt bis auf Leerraum unverändert zurück.
 *  - [clean] wirft nie. Geben die Regeln bei einer absurden Eingabe auf (tausende Zeichen Ziffernkette),
 *    kommt der Text so zurück, wie er kam; nur dann können auch Ziffern stehen bleiben.
 *
 * Grenzen: Das ist ein Netz, kein Sprachverständnis. Im Zweifel fällt die Wahl auf die Lesart, die auch
 * falsch geraten noch verständlich ist ("fünfzehnhundert Euro", "ein Euro" statt "einen Euro").
 *
 * Reine Textfunktion ohne Zustand: nichts wird gespeichert oder protokolliert.
 */
object SpokenText {

    /** Macht [sentence] (einen Satz der Antwort) vorlesbar. Leerer oder reiner Markdown-Text ergibt "". */
    fun clean(sentence: String): String =
        // Ein Sicherheitsnetz darf nie selbst der Grund sein, dass eine Antwort ausfällt. Die Regeln sind
        // reguläre Ausdrücke, und die können bei absurd langen Ziffernketten (tausende Zeichen
        // "1.1.1.1...") den Aufrufstapel sprengen. Dann lieber den Text so sprechen, wie er kam.
        try {
            cleanToFixpoint(sentence)
        } catch (e: RuntimeException) {
            sentence.replace(SPACES, " ").trim()
        } catch (e: StackOverflowError) {
            sentence.replace(SPACES, " ").trim()
        }

    private fun cleanToFixpoint(sentence: String): String {
        // Die Regeln sind so gebaut, dass ein Durchlauf genügt. Bei sehr schrägen Eingaben kann eine
        // Ersetzung aber etwas hinterlassen, das erst beim nächsten Mal greift (aus "–°C" wird
        // "– Grad Celsius", und das sieht dann aus wie ein Listenpunkt). Darum: wiederholen, bis sich
        // nichts mehr ändert. Der Prüfdurchlauf auf fertigem Text kostet weit unter einer Millisekunde.
        var s = cleanOnce(sentence)
        repeat(MAX_PASSES - 1) {
            val again = cleanOnce(s)
            if (again == s) return s
            s = again
        }
        return s
    }

    private const val MAX_PASSES = 4

    /** Ein einzelner Durchlauf aller Regeln; nur für Tests sichtbar. */
    internal fun cleanOnce(sentence: String): String {
        var s = sentence.replace(ODD_SPACES, " ")
        s = spellings(s)
        s = stripMarkdown(s)
        s = brackets(s)
        // Die Sprache wird einmal am bereinigten Text bestimmt und gilt dann für alle Regeln.
        val en = isEnglish(s)
        // "5'000" (Schweizer Schreibweise) ist dieselbe Zahl wie "5.000".
        if (!en) s = s.replace(APOSTROPHE_THOUSANDS, ".")
        s = abbreviations(s, en)
        s = prefixSymbols(s, en)
        s = isoDates(s, en)
        if (!en) s = numericDates(s)
        s = times(s, en)
        s = phoneNumbers(s, en)
        s = dotted(s, en)
        s = if (en) englishOrdinals(s) else ordinals(s)
        s = decades(s, en)
        s = ranges(s, en)
        s = compounds(s, en)
        s = fractions(s, en)
        s = numbers(s, en)
        s = leftoverSymbols(s, en)
        return tidy(s)
    }

    /**
     * Für den Satzzerleger: Beendet der Punkt an der Stelle [dot] in [text] wirklich einen Satz?
     *
     * Warum das hierher gehört: [clean] bekommt die Antwort satzweise. Trennt der Zerleger schon hinter
     * "12." oder "z.", kommen "Heute ist der 12." und "November." als zwei Sätze an, und keine Regel kann
     * das Datum mehr als Ganzes lesen. Nein heißt es deshalb bei
     *  - kurzen Zahlen ("am 12. November", "12.11. um acht", "1. FC Köln"); eine Jahreszahl ("1989.") endet,
     *  - einzelnen Buchstaben ("z. B.", "d. h.", "J. K. Rowling"),
     *  - Abkürzungen, die mitten im Satz stehen ("ca.", "Nr.", "Mio.", "Dr.").
     * Irrt die Antwort in Richtung "kein Satzende", werden nur zwei Sätze zusammen gesprochen; das kostet
     * etwas Wartezeit, klingt aber richtig. Der umgekehrte Irrtum zerreißt ein Datum.
     */
    fun dotEndsSentence(text: CharSequence, dot: Int): Boolean {
        if (dot !in text.indices || text[dot] != '.') return true
        var i = dot
        while (i > 0 && text[i - 1].isLetterOrDigit()) i--
        val token = text.subSequence(i, dot).toString()
        return when {
            token.isEmpty() -> true
            token.all { it.isDigit() } -> token.length > 3
            token.length == 1 -> false
            else -> token !in MID_SENTENCE_ABBR
        }
    }

    private val MID_SENTENCE_ABBR = setOf(
        "ca", "Ca", "bzw", "evtl", "Evtl", "ggf", "Ggf", "inkl", "Inkl", "bzgl", "Bzgl", "Nr", "Mio", "Mrd", "Std",
        "Min", "Sek", "max", "vs", "approx", "Chr", "Dr", "Prof", "Hr", "Fr", "St", "Str", "Tel", "Mr", "Mrs", "Ms",
        "Jan", "Feb", "Mär", "Apr", "Jun", "Jul", "Aug", "Sep", "Sept", "Okt", "Nov", "Dez",
    )

    private val APOSTROPHE_THOUSANDS = Regex("""(?<=\d)['’](?=\d{3}(?!\d))""")

    // ------------------------------------------------------------------------------------------
    // Markdown, Klammern, Leerraum

    /** Geschützte und schmale Leerzeichen (Tausendertrenner, Abstand vor Einheiten) zählen als Leerzeichen. */
    private val ODD_SPACES = Regex("[\\u00A0\\u2007\\u2009\\u202F]")

    private val SUBSCRIPT_DIGIT = Regex("[\\u2080-\\u2089]")
    private val DEGREE_GAP = Regex("""° (?=[CF](?![\p{L}\d]))""")
    private val PRICE_DASH = Regex("""(?<=\d),[-–—]{1,2}(?![\d\p{L}])""")
    private val PHONE_PARENS = Regex("""\((0\d{0,5})\) ?(?=\d)""")
    private val CURRENCY_CODE_FIRST = Regex("""(?<![\p{L}\d])(EUR|USD) ?(?=\d)""")

    /**
     * Schreibweisen, die dasselbe meinen, auf die eine Form bringen, die die Regeln weiter unten kennen:
     * "CO₂" -> "CO2", "30° C" -> "30°C", "5,- €" -> "5 €", "EUR 5" -> "€5". Bei Telefonnummern fällt die
     * Klammer um die Vorwahl weg ("(089) 1234" -> "089 1234"), bevor Klammern allgemein zu Kommas werden;
     * die eingeklammerte Null nach der Ländervorwahl ("+49 (0) 89") wird gar nicht mitgewählt.
     */
    private fun spellings(text: String): String = text
        .replace(SUBSCRIPT_DIGIT) { m -> ('0' + (m.value[0] - '₀')).toString() }
        .replace(DEGREE_GAP, "°")
        .replace(PRICE_DASH, "")
        .replace(PHONE_PARENS) { m -> if (m.groupValues[1] == "0") "" else m.groupValues[1] + " " }
        .replace(CURRENCY_CODE_FIRST) { m -> if (m.groupValues[1] == "EUR") "€" else "$" }

    private val MD_LINK = Regex("""\[([^\]]+)]\([^)\s]+\)""")
    private val LINE_PREFIX = Regex("""(?m)^[ \t]*(?:#{1,6}[ \t]+|[-–—•·▪][ \t]+|>[ \t]+)""")
    private val STAR_BETWEEN_NUMBERS = Regex("""(?<=\d) ?\* ?(?=\d)""")
    private val EMPHASIS_UNDERSCORE = Regex("""(?<![\p{L}\d])_+|_+(?![\p{L}\d])""")
    private val HASH = Regex("""#(?!\d)""")
    private val SHARP = Regex("""(?<![\p{L}\d])([CF])#(?![\p{L}\d])""")

    private fun stripMarkdown(text: String): String = text
        .replace(MD_LINK, "$1")                    // [Text](Adresse) -> Text
        .replace(LINE_PREFIX, "")                  // "# Titel", "- Punkt", "> Zitat" am Zeilenanfang
        .replace(STAR_BETWEEN_NUMBERS, " × ")      // "2 * 3" ist eine Rechnung, keine Hervorhebung
        .replace("*", "").replace("`", "").replace("~~", "")
        .replace(EMPHASIS_UNDERSCORE, "")          // _betont_
        .replace('_', ' ')                         // schlangen_schreibweise
        .replace(SHARP, "$1 Sharp")                // "C#" ist ein Name; ohne die Raute bliebe nur "C" übrig
        .replace(HASH, "")                         // "#1" bleibt stehen und wird später zu "Nummer eins"

    private val DOUBLE_COMMA = Regex(""",(\s*,)+""")
    private val COMMA_BEFORE_MARK = Regex(""",\s*([.!?;:])""")
    private const val OPENERS = "([{"
    private const val CLOSERS = ")]}"

    /**
     * Klammern werden zu Kommas: "Die Zugspitze (2962 m) ist hoch" -> "Die Zugspitze, 2962 m, ist hoch".
     * Der Inhalt bleibt erhalten; eine Stimme kann Klammern nicht sprechen, eine Pause aber schon.
     */
    private fun brackets(text: String): String {
        if (text.none { it in OPENERS || it in CLOSERS }) return text
        val out = StringBuilder()
        for ((i, c) in text.withIndex()) {
            when (c) {
                in OPENERS -> {
                    while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
                    if (out.isNotEmpty() && out.last() !in ",;:") out.append(',')
                    if (out.isNotEmpty()) out.append(' ')
                }
                in CLOSERS -> {
                    val next = text.substring(i + 1).firstOrNull { it != ' ' }
                    if (next != null && next !in ".,;:!?" && next !in CLOSERS) out.append(',')
                }
                else -> out.append(c)
            }
        }
        // Aufräumen nur hier, wo Klammern standen: leere Klammern hinterlassen sonst ", ,".
        return out.toString().replace(DOUBLE_COMMA, ",").replace(COMMA_BEFORE_MARK, "$1")
    }

    private val SPACES = Regex("""\s+""")
    private val SPACE_BEFORE_MARK = Regex(""" +([,.;:!?])""")

    private fun tidy(text: String): String {
        var s = text.replace(SPACES, " ").replace(SPACE_BEFORE_MARK, "$1").trim()
        // Steht ein Strich erst jetzt frei am Anfang ("–°C" -> "– Grad Celsius"), sähe er beim nächsten
        // Durchlauf wie ein Listenpunkt aus. Darum gleich hier entfernen.
        while (true) {
            val stripped = s.replace(LINE_PREFIX, "").trim()
            if (stripped == s) return s
            s = stripped
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sprache

    private val WORD = Regex("""[\p{L}']+""")

    // Bewusst ohne Wörter, die es in beiden Sprachen gibt ("in", "was", "war", "an", "am", "so", "die",
    // "will", "not"): "Tom will 3 Bier" ist Deutsch, und "Not" ist auch ein deutsches Hauptwort.
    private val EN_STOP = setOf(
        "the", "is", "are", "were", "of", "and", "to", "it", "that", "this", "you", "for", "with", "on", "at",
        "be", "have", "has", "but", "from", "they", "would", "can", "there", "what", "which",
        "about", "its", "it's", "we", "he", "she", "do", "does", "or", "by", "as", "if", "your", "my", "than",
        "then", "costs", "takes", "how", "when", "where", "who", "why", "had", "been", "their", "our", "now",
        "please", "here", "yes", "today", "tomorrow", "yesterday", "very", "some", "into", "call",
        "i'm", "i'll", "i've", "i'd", "don't", "doesn't", "isn't", "aren't", "can't", "won't", "that's",
        "there's", "what's", "let's", "you're", "we're", "they're",
    )
    // Ohne Wörter, die englisch genauso aussehen ("Meter", "Euro", "Minute"): Die englischen Regeln
    // schreiben solche Wörter selbst und dürfen das Urteil damit nicht nachträglich kippen.
    private val DE_STOP = setOf(
        // Häufige Verbformen und Füllwörter. Sie fangen Sätze ab, in denen ein englischer Name das einzige
        // Stoppwort wäre ("The Beatles hatten 4 Mitglieder", "Game of Thrones Staffel 8").
        "hatte", "hatten", "habe", "hast", "habt", "bist", "seid", "werde", "werden", "wurden", "worden",
        "kannst", "konnte", "konnten", "muss", "musst", "musste", "soll", "sollst", "sollte", "sollten", "darf",
        "darfst", "wollen", "wollte", "machen", "gemacht", "gehen", "ging", "kommen", "sagen", "gesagt", "sehen",
        "geben", "nehmen", "stehen", "steht", "liegen", "bleiben", "bleibt", "brauchen", "braucht", "brauchst",
        "finden", "findet", "wissen", "spielen", "spielte", "gewann", "gewonnen", "verlor", "verloren", "endete",
        "sogar", "bereits", "insgesamt", "kaum", "wieder", "weiter", "zuerst", "zuletzt", "danach", "davor",
        "dabei", "dazu", "darauf", "deshalb", "deswegen", "trotzdem", "jedoch", "sondern", "sowie", "sowohl",
        "weder", "entweder", "obwohl", "bevor", "nachdem", "sobald", "neben", "wegen", "statt", "trotz", "je",
        "neue", "neuen", "neuer", "neues", "erste", "ersten", "zweite", "zweiten", "letzte", "letzten", "andere",
        "anderen", "beide", "beiden", "jeder", "jede", "jedes", "jeden", "jedem", "ihnen", "euch", "mich", "dich",
        "unser", "unsere", "welche", "welcher", "welches", "wem", "wen", "wieso", "weshalb", "oben", "unten",
        "rechts", "jahren", "tagen", "sekunden", "kilogramm", "zentimeter", "millionen", "milliarden", "tausend",
        "hundert", "sechs", "sieben", "acht", "neun", "zehn", "zwanzig", "einwohner", "menschen", "leute",
        "personen", "zuschauer", "mitglieder", "staffel", "teil", "folge", "seite", "platz", "punkte",
        "spiel", "spiele", "stadt", "zeit", "preis", "zahl", "haus", "frau", "leben", "deutschland", "deutsche",
        "deutschen", "deutsch",
        "der", "das", "ist", "sind", "und", "zu", "es", "dass", "nicht", "ein", "eine", "einen", "einem", "einer",
        "mit", "auf", "für", "von", "den", "dem", "des", "im", "ich", "du", "er", "sie", "wir", "ihr", "auch",
        "aber", "oder", "bei", "nach", "wie", "noch", "nur", "sich", "hat", "haben", "wird", "kann", "um", "aus",
        "als", "wenn", "etwa", "rund", "liegt", "gibt", "sehr", "zum", "zur", "bis", "seit", "uhr", "mal",
        "kostet", "kosten", "dauert", "heute", "morgen", "gestern", "jetzt", "hier", "dort", "ja", "nein", "mehr",
        "weniger", "jahr", "jahre", "grad", "prozent", "vor", "ab", "durch", "unter", "gleich", "nummer",
        "beispiel", "komma", "punkt", "eins", "zwei", "drei", "vier", "wurde", "waren", "gab", "kam", "kommt",
        "geht", "macht", "spielt", "stammt", "erschien", "lebt", "wiegt", "misst", "sagt", "bitte", "danke",
        "genau", "knapp", "ganz", "schon", "immer", "viel", "viele", "alle", "kein", "keine", "dieser", "diese",
        "dieses", "sein", "seine", "ihre", "mein", "dein", "uns", "mir", "dir", "ihm", "ihn", "wo", "wer", "wann",
        "warum", "weil", "doch", "eben", "zwar", "dann", "denn", "damit", "ohne", "gegen", "zwischen", "stunde",
        "stunden", "minuten", "tage", "woche", "wochen", "monat", "monate", "gramm",
    )

    /**
     * Kleine Stoppwort-Zählung statt echter Spracherkennung: Für die Frage "deutsche oder englische
     * Zahlwörter?" reicht das. Im Zweifel gilt Deutsch, denn die App antwortet fast immer deutsch:
     * Schon ein deutsches Wort wiegt drei englische auf, damit ein englischer Titel in einem deutschen
     * Satz ("The Lord of the Rings kostet 10 €") nichts umwirft.
     * Die Regeln fügen nur Wörter der gewählten Sprache hinzu, deshalb kippt das Urteil beim zweiten
     * Durchlauf nicht (wichtig für die Idempotenz).
     */
    private fun isEnglish(text: String): Boolean {
        var en = 0
        var de = 0
        for (m in WORD.findAll(text)) {
            val w = m.value.lowercase()
            if (w in EN_STOP) en++
            else if (w in DE_STOP || w.any { it in "äöüß" }) de++
        }
        return en >= 1 && en > 3 * de
    }

    // ------------------------------------------------------------------------------------------
    // Hilfen für den Blick nach links und rechts

    /** Das Wort direkt vor [index], nur durch Leerzeichen getrennt; [skip] = 1 liefert das Wort davor. */
    private fun wordBefore(s: CharSequence, index: Int, skip: Int = 0): String {
        var i = index
        var word = ""
        repeat(skip + 1) {
            while (i > 0 && s[i - 1] == ' ') i--
            val end = i
            while (i > 0 && s[i - 1].isLetter()) i--
            word = s.substring(i, end)
            if (word.isEmpty()) return ""
        }
        return word
    }

    /** Das Wort direkt nach [index], nur durch Leerzeichen getrennt. */
    private fun wordAfter(s: CharSequence, index: Int): String {
        var i = index
        while (i < s.length && s[i] == ' ') i++
        val start = i
        while (i < s.length && s[i].isLetter()) i++
        return s.substring(start, i)
    }

    /** true, wenn nach [index] nichts Gesprochenes mehr kommt (nur Leerzeichen, Anführungszeichen). */
    private fun atEnd(s: CharSequence, index: Int): Boolean =
        (index until s.length).all { s[it] == ' ' || s[it] in "\"'“”„«»‹›" }

    private fun capitalized(word: String) = word.isNotEmpty() && word[0].isUpperCase()

    /** Großgeschriebene Wörter, die einen neuen Satz beginnen und deshalb kein gezähltes Hauptwort sind. */
    private val SENTENCE_STARTERS = setOf(
        "Dann", "Das", "Die", "Der", "Es", "Und", "Aber", "Danach", "Sie", "Er", "Wir", "Ich", "Du", "Dort",
        "Hier", "Dafür", "Dazu", "Außerdem", "Also", "Wenn", "Weil", "Da", "Dabei", "In", "Im", "Am", "Auf",
        "Mit", "Für", "Von", "Bei", "Nach", "Vor", "Zu", "Ein", "Eine", "Heute", "Morgen", "Man", "Ja", "Nein",
    )

    // ------------------------------------------------------------------------------------------
    // Abkürzungen

    private class Abbr(pattern: String, val spoken: String) {
        // Nur der erste Buchstabe darf groß oder klein sein ("z. B." und "Z. B." am Satzanfang).
        // Ganz ohne Rücksicht auf Großschreibung würde "D. H. Lawrence" zu "das heißt Lawrence".
        val regex = Regex(
            "(?<![\\p{L}.])" +
                (if (pattern[0].isLetter()) "[${pattern[0].lowercaseChar()}${pattern[0].uppercaseChar()}]" + pattern.drop(1) else pattern),
        )
        val lower = pattern[0].isLowerCase()
    }

    private val ABBR_DE = listOf(
        Abbr("""z\. ?B\.""", "zum Beispiel"), Abbr("""d\. ?h\.""", "das heißt"),
        Abbr("""u\. ?a\.""", "unter anderem"), Abbr("""o\. ?[ÄäAa]\.""", "oder Ähnliches"),
        Abbr("""i\. ?d\. ?R\.""", "in der Regel"), Abbr("""z\. ?T\.""", "zum Teil"),
        Abbr("""v\. ?Chr\.""", "vor Christus"), Abbr("""n\. ?Chr\.""", "nach Christus"),
        Abbr("""ca\.""", "circa"), Abbr("""bzw\.""", "beziehungsweise"), Abbr("""usw\.""", "und so weiter"),
        Abbr("""evtl\.""", "eventuell"), Abbr("""ggf\.""", "gegebenenfalls"), Abbr("""inkl\.""", "inklusive"),
        Abbr("""bzgl\.""", "bezüglich"), Abbr("""etc\.""", "et cetera"), Abbr("""Nr\.""", "Nummer"),
        Abbr("""Mio\.""", "Millionen"), Abbr("""Mrd\.""", "Milliarden"), Abbr("""Std\.""", "Stunden"),
    )
    private val ABBR_EN = listOf(
        Abbr("""e\. ?g\.""", "for example"), Abbr("""i\. ?e\.""", "that is"), Abbr("""etc\.""", "et cetera"),
        Abbr("""approx\.""", "approximately"), Abbr("""vs\.""", "versus"),
    )

    /**
     * Nach diesen Ausschreibungen endet oft ein Satz; folgt ein typischer Satzanfang, bleibt ihr Punkt als
     * Satzpunkt stehen ("... 300 v. Chr. Danach ..." -> "... vor Christus. Danach ...").
     */
    private val CLAUSE_ENDERS = setOf(
        "und so weiter", "et cetera", "oder Ähnliches", "vor Christus", "nach Christus", "millionen", "milliarden",
        "stunden", "1 Million", "1 Milliarde", " min", " h", " s",
    )
    private val ONE_BIG = Regex("""(?<![\d.,])1 (Mio|Mrd)\.""")
    private val MAX_BEFORE_NUMBER = Regex("""(?<![\p{L}\d.])([mM])ax\.(?= ?\d)""")
    private val TIME_ABBR = Regex("""(?<=\d) ?(Min|Std|Sek)\.""")
    private val TIME_SYMBOLS = mapOf("Min" to "min", "Std" to "h", "Sek" to "s")
    private val MONTH_BY_ABBR = mapOf(
        "Jan" to "Januar", "Feb" to "Februar", "Mär" to "März", "Apr" to "April", "Jun" to "Juni", "Jul" to "Juli",
        "Aug" to "August", "Sept" to "September", "Sep" to "September", "Okt" to "Oktober", "Nov" to "November",
        "Dez" to "Dezember",
    )
    // Nur direkt hinter einem Tag ("12. Nov."): Für sich allein sind "Jan." und "Aug." auch Vornamen.
    private val MONTH_ABBR = Regex("""(?<=\d\. ?)(""" + MONTH_BY_ABBR.keys.joinToString("|") + """)\.""")

    private fun abbreviations(text: String, en: Boolean): String {
        var s = text
        if (!en) {
            // Einzahl zuerst, sonst entstünde "eine Millionen". "max." nur vor Zahlen, sonst ist "Max." ein Name.
            s = s.replace(ONE_BIG) { m -> expand(s, m, if (m.groupValues[1] == "Mio") "1 Million" else "1 Milliarde") }
            s = s.replace(MAX_BEFORE_NUMBER) { m -> if (m.groupValues[1] == "M") "Maximal" else "maximal" }
            // "20 Min." wird zum Einheitenzeichen; Einzahl und Mehrzahl ("1 Std." -> "eine Stunde") klärt die Zahlregel.
            s = s.replace(TIME_ABBR) { m -> expand(s, m, " " + TIME_SYMBOLS.getValue(m.groupValues[1])) }
            s = s.replace(MONTH_ABBR) { m -> expand(s, m, MONTH_BY_ABBR.getValue(m.groupValues[1])) }
        }
        for (a in if (en) ABBR_EN else ABBR_DE) {
            val before = s
            s = a.regex.replace(before) { m ->
                val spoken = if (a.lower && m.value[0].isUpperCase()) a.spoken.replaceFirstChar { it.uppercaseChar() } else a.spoken
                expand(before, m, spoken)
            }
        }
        return s
    }

    /**
     * Der Punkt der Abkürzung fällt weg, außer er war zugleich der Satzpunkt
     * ("Äpfel, Birnen usw." -> "Äpfel, Birnen und so weiter.").
     */
    private fun expand(s: String, m: MatchResult, spoken: String): String {
        val after = m.range.last + 1
        val newSentence = spoken.replaceFirstChar { it.lowercaseChar() } in CLAUSE_ENDERS && wordAfter(s, after) in SENTENCE_STARTERS
        return if (atEnd(s, after) || newSentence) "$spoken." else spoken
    }

    // ------------------------------------------------------------------------------------------
    // Deutsche Zahlwörter

    private val DE_SMALL = arrayOf(
        "null", "ein", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwölf",
        "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn",
    )
    private val DE_TENS = arrayOf("", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig")
    private val DE_DIGITS = arrayOf("null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun")

    /** Baustein 1..999. Die Eins heißt hier "ein", so wie sie in Zusammensetzungen klingt (einhundert, einundzwanzig). */
    private fun deBelow1000(n: Int): String {
        val sb = StringBuilder()
        val rest = n % 100
        if (n >= 100) sb.append(DE_SMALL[n / 100]).append("hundert")
        when {
            rest == 0 -> {}
            rest < 20 -> sb.append(DE_SMALL[rest])
            else -> {
                // Im Deutschen kommt der Einer vor dem Zehner: ein-und-zwanzig.
                if (rest % 10 > 0) sb.append(DE_SMALL[rest % 10]).append("und")
                sb.append(DE_TENS[rest / 10])
            }
        }
        return sb.toString()
    }

    private val DE_BIG = listOf(
        Triple(1_000_000_000_000L, "Billion", "Billionen"),
        Triple(1_000_000_000L, "Milliarde", "Milliarden"),
        Triple(1_000_000L, "Million", "Millionen"),
    )

    /** Grundzahl bis unter eine Billiarde, z. B. 1200 -> "eintausendzweihundert", 2000000 -> "zwei Millionen". */
    private fun deCardinal(n: Long): String {
        if (n == 0L) return "null"
        val parts = ArrayList<String>()
        var rest = n
        for ((value, one, many) in DE_BIG) {
            val count = (rest / value).toInt()
            rest %= value
            // Million und Milliarde sind eigene Hauptwörter: "eine Million", nicht "einmillion".
            if (count == 1) parts += "eine $one" else if (count > 1) parts += "${deBelow1000(count)} $many"
        }
        val thousands = (rest / 1000).toInt()
        val units = (rest % 1000).toInt()
        val tail = StringBuilder()
        if (thousands > 0) tail.append(deBelow1000(thousands)).append("tausend")
        if (units > 0) tail.append(deBelow1000(units))
        // Steht die Eins ganz am Ende, heißt sie "eins": eins, einhunderteins, eintausendeins.
        if (units % 100 == 1) tail.append('s')
        if (tail.isNotEmpty()) parts += tail.toString()
        return parts.joinToString(" ")
    }

    /** Form für Zusammensetzungen und vor Hauptwörtern: "ein" statt "eins" (einjährig, ein Uhr). */
    private fun deJoinable(n: Long): String = deCardinal(n).let { if (n % 100 == 1L) it.dropLast(1) else it }

    /** Jahreszahl: 1989 -> "neunzehnhundertneunundachtzig"; außerhalb von 1100..1999 wie eine Grundzahl. */
    private fun deYear(n: Int): String =
        if (n in 1100..1999) deBelow1000(n / 100) + "hundert" + (if (n % 100 > 0) deCardinal((n % 100).toLong()) else "")
        else deCardinal(n.toLong())

    private val DE_ORDINAL_IRREGULAR = mapOf(1 to "erst", 3 to "dritt", 7 to "siebt", 8 to "acht")

    /** Ordnungszahl ohne Endung für 1..999: "zwölft", "zwanzigst"; die Endung (-er, -e, -en) hängt vom Satz ab. */
    private fun deOrdinalStem(n: Int): String {
        val rest = n % 100
        val head = if (n >= 100) deBelow1000(n - rest) else ""
        return when {
            rest == 0 -> head + "st"
            rest < 20 -> head + (DE_ORDINAL_IRREGULAR[rest] ?: (DE_SMALL[rest] + "t"))
            else -> head + deBelow1000(rest) + "st"
        }
    }

    private val DE_MONTHS = listOf(
        "Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember",
    )
    private val DE_FRACTIONS = arrayOf("", "", "halb", "Drittel", "Viertel", "Fünftel", "Sechstel", "Siebtel", "Achtel", "Neuntel", "Zehntel")

    // ------------------------------------------------------------------------------------------
    // Englische Zahlwörter (bewusst schlicht gehalten)

    private val EN_SMALL = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val EN_TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun enBelow1000(n: Int): String {
        val parts = ArrayList<String>()
        val rest = n % 100
        if (n >= 100) parts += EN_SMALL[n / 100] + " hundred"
        if (rest in 1..19) parts += EN_SMALL[rest]
        else if (rest >= 20) parts += EN_TENS[rest / 10] + (if (rest % 10 > 0) "-" + EN_SMALL[rest % 10] else "")
        return parts.joinToString(" ")
    }

    private val EN_BIG = listOf(
        1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand",
    )

    private fun enCardinal(n: Long): String {
        if (n == 0L) return "zero"
        val parts = ArrayList<String>()
        var rest = n
        for ((value, name) in EN_BIG) {
            val count = (rest / value).toInt()
            rest %= value
            if (count > 0) parts += enBelow1000(count) + " " + name
        }
        if (rest > 0) parts += enBelow1000(rest.toInt())
        return parts.joinToString(" ")
    }

    private val EN_ORDINAL_IRREGULAR = mapOf(
        "one" to "first", "two" to "second", "three" to "third", "five" to "fifth", "eight" to "eighth",
        "nine" to "ninth", "twelve" to "twelfth",
    )

    /** "twenty-one" -> "twenty-first": Nur das letzte Wort bekommt die Ordnungsform. */
    private fun enOrdinal(n: Long): String {
        val words = enCardinal(n)
        val cut = words.lastIndexOfAny(charArrayOf(' ', '-')) + 1
        val last = words.substring(cut)
        val ordinal = EN_ORDINAL_IRREGULAR[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        return words.substring(0, cut) + ordinal
    }

    /** Jahreszahl in Paaren: 1989 -> "nineteen eighty-nine", 2026 -> "twenty twenty-six", 2005 -> "two thousand five". */
    private fun enYear(n: Int): String {
        if (n !in 1100..2099 || n in 2000..2009) return enCardinal(n.toLong())
        val high = EN_SMALL.getOrElse(n / 100) { enBelow1000(n / 100) }
        val low = n % 100
        return when {
            low == 0 -> "$high hundred"
            low < 10 -> "$high oh ${EN_SMALL[low]}"
            else -> "$high ${enBelow1000(low)}"
        }
    }

    private val EN_DAY_ORDINALS: Set<String> by lazy { (1..31).map { enOrdinal(it.toLong()).substringAfterLast('-') }.toSet() }
    private val EN_MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )

    // ------------------------------------------------------------------------------------------
    // Gemeinsame Bausteine

    private fun cardinal(n: Long, en: Boolean) = if (en) enCardinal(n) else deCardinal(n)

    /** Ziffer für Ziffer: Telefonnummern, Nachkommastellen, Zahlen mit führender Null. */
    private fun digitWords(digits: String, en: Boolean): String =
        // digitToIntOrNull statt "it - '0'": Auf Android zählt "\d" auch Ziffern anderer Schriften mit
        // (arabisch-indische, vollbreite); deren Zeichenwert läge weit außerhalb der Tabelle.
        digits.mapNotNull { it.digitToIntOrNull() }.map { if (en) EN_SMALL[it] else DE_DIGITS[it] }.joinToString(" ")

    /** Grundzahl, oder Ziffer für Ziffer, wenn die Zahl mit Null beginnt oder zu lang für ein Zahlwort ist. */
    private fun plain(digits: String, en: Boolean): String =
        if ((digits.length > 1 && digits[0] == '0') || digits.length > 15) digitWords(digits, en)
        else cardinal(digits.toLong(), en)

    /**
     * Was vor der Zahl gesprochen wird. Hängt die Zahl an einem Buchstaben ("A4", "COVID-19", "v2.0"),
     * ist ein Bindestrich nur Kitt und kein Minus; steht davor Leerraum, ist "-5" wirklich minus fünf.
     */
    private fun lead(s: CharSequence, start: Int, sign: String): String {
        val before = if (start > 0) s[start - 1] else ' '
        return when {
            before.isLetter() -> " "
            sign.isEmpty() -> ""
            sign == "+" -> if (before.isDigit()) " plus " else "plus "
            // Das echte Minuszeichen ist immer eine Rechnung ("7−2"); der Bindestrich zwischen Ziffern nur Kitt.
            before.isDigit() -> if (sign == "−") " minus " else " "
            else -> "minus "
        }
    }

    // ------------------------------------------------------------------------------------------
    // Regeln, Spezialfälle zuerst

    private class Symbol(pattern: String, val de: String, val en: String) { val regex = Regex(pattern) }

    /** Zeichen, deren Bedeutung von der folgenden Zahl abhängt; sie müssen vor den Zahlregeln dran sein. */
    private val PREFIX_SYMBOLS = listOf(
        Symbol("""#(?=\d)""", "Nummer ", "number "),
        Symbol("""[~≈] ?(?=\d)""", "ungefähr ", "about "),
        Symbol(""">= ?""", "mindestens ", "at least "),
        Symbol("""<= ?""", "höchstens ", "at most "),
        Symbol("""(?<=\d) ?\^ ?(?=[-−]?\d)""", " hoch ", " to the power of "),
        Symbol("""< ?(?=\d)""", "weniger als ", "less than "),
        Symbol("""> ?(?=\d)""", "mehr als ", "more than "),
        Symbol("""≤ ?""", "höchstens ", "at most "),
        Symbol("""≥ ?""", "mindestens ", "at least "),
        Symbol("""± ?""", "plus minus ", "plus or minus "),
        Symbol("""(?<=\d) ?[x×] ?(?=\d)""", " mal ", " times "),
        Symbol("""(?<=\d)[x×](?![\p{L}\d])""", " mal", " times"),
    )

    private fun prefixSymbols(text: String, en: Boolean): String =
        PREFIX_SYMBOLS.fold(text) { s, sym -> s.replace(sym.regex, if (en) sym.en else sym.de) }

    private val EN_WORDS = setOf(
        "am", "vom", "zum", "zur", "beim", "im", "dem", "den", "des", "ans", "ins", "einem", "einen", "eines",
        "jeden", "jedem", "diesem", "diesen", "ab", "seit", "bis", "vor", "nach", "von", "mit", "zu",
    )
    private val E_WORDS = setOf("die", "das", "eine", "diese", "dieses", "jede", "jedes")
    private val LINK_WORDS = setOf("bis", "und", "oder")
    private val POSSESSIVE_STEMS = setOf("sein", "ihr", "mein", "dein", "unser", "euer", "kein")

    /** Besitzanzeigende Begleiter in allen Formen ("sein", "seine", "seinem" ...), jeweils mit ihrem Stamm. */
    private val POSSESSIVES: Set<String> = POSSESSIVE_STEMS.flatMap { stem ->
        val base = if (stem == "euer") "eur" else stem
        listOf(stem, base + "e", base + "en", base + "em", base + "er", base + "es")
    }.toSet()
    private val DE_PREPOSITIONS = setOf(
        "in", "an", "auf", "aus", "bei", "mit", "nach", "von", "zu", "seit", "vor", "hinter", "über", "unter",
        "neben", "zwischen", "während", "wegen",
    )

    /**
     * Endung einer Ordnungszahl (der Stamm endet schon auf t: "zwölft" + "en"). Sie richtet sich nach
     * dem Wort davor: "am zwölften", "der zwölfte",
     * ohne Begleiter nach dem Geschlecht des Hauptworts ("zwölfter November", "dritte Etage").
     * [last] ist die Endung der vorigen Ordnungszahl, damit "vom 3. bis 5. Mai" einheitlich klingt.
     */
    private fun deEnding(s: String, start: Int, gender: Char?, last: String? = null): String {
        val prev = wordBefore(s, start).lowercase()
        return when {
            prev in LINK_WORDS && last != null -> last
            prev in EN_WORDS -> "en"
            prev == "der" || prev == "jeder" -> if (wordBefore(s, start, 1).lowercase() in DE_PREPOSITIONS) "en" else "e"
            prev in E_WORDS -> "e"
            // "sein drittes Tor", "sein dritter Sieg": Der endungslose Begleiter zeigt das Geschlecht nicht selbst.
            prev in POSSESSIVE_STEMS -> when (gender) { 'n' -> "es"; 'f' -> "e"; else -> "er" }
            prev in POSSESSIVES -> if (prev.endsWith("e")) "e" else "en"   // "seine dritte", "seinem dritten"
            gender == 'f' -> "e"
            gender == 'n' -> "es"
            else -> "er"
        }
    }

    private val ISO_DATE = Regex("""(?<![\d\p{L}-])(\d{4})-(\d{2})-(\d{2})(?![\d-])""")

    /** "2026-11-12" -> "zwölfter November zweitausendsechsundzwanzig". */
    private fun isoDates(s: String, en: Boolean): String = ISO_DATE.replace(s) { m ->
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        when {
            month !in 1..12 || day !in 1..31 -> m.value
            en -> "${EN_MONTHS[month - 1]} ${enOrdinal(day.toLong())} ${enYear(year)}"
            else -> "${deOrdinalStem(day)}${deEnding(s, m.range.first, 'm')} ${DE_MONTHS[month - 1]} ${deYear(year)}"
        }
    }

    private val NUMERIC_DATE = Regex("""(?<![\d.,\p{L}])(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2})?(?!\d)""")
    private val DATE_CUES = setOf(
        "am", "vom", "zum", "bis", "ab", "seit", "den", "dem", "der", "heute", "morgen", "gestern", "datum",
        "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag",
    )

    // "Sa., 14.11.": Abgekürzte Wochentage zählen nur mit Punkt oder Komma dahinter, sonst wäre "so" ein Hinweis.
    private val WEEKDAY_ABBR = Regex("""(?<!\p{L})(?:Mo|Di|Mi|Do|Fr|Sa|So)(?:\.,?|,)$""")
    private val DAY_SPAN = Regex("""(?<![\d.,\p{L}])(\d{1,2}\.(?:\d{1,2}\.(?:\d{4}|\d{2})?)?) ?[-–—] ?(?=\d{1,2}\.)""")
    private val DAY_PAIR = Regex("""(?<![\d.,\p{L}])(\d{1,2}\.)/(?=\d{1,2}\.)""")
    private val OPEN_DATE = Regex("""(?<![\d.,\p{L}])(\d{1,2})\.(\d{1,2})(?![\d.]|,\d| ?Uhr)""")

    /** "12.11.2026" -> "zwölfter elfter zweitausendsechsundzwanzig", so wie man ein Datum am Telefon sagt. */
    private fun numericDates(text: String): String {
        // "14.-16.11." und "14./15. November": Der Strich heißt hier "bis", der Schrägstrich "und". Erst
        // danach sieht jede Zahl ein Wort vor sich, an dem sie ihre Endung ausrichten kann.
        val s = text.replace(DAY_SPAN, "$1 bis ").replace(DAY_PAIR, "$1 und ")
        val dated = NUMERIC_DATE.replace(s) { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val year = m.groupValues[3]
            val start = m.range.first
            // "Donnerstag, 12.11.": Das Komma nach dem Wochentag soll den Hinweis nicht verdecken.
            val before = s.substring(0, start).trimEnd()
            val head = before.removeSuffix(",")
            val cue = wordBefore(head, head.length).lowercase() in DATE_CUES || WEEKDAY_ABBR.containsMatchIn(before)
            // Ohne vierstellige Jahreszahl könnte "1.5." oder "3.10.12" auch eine Versionsnummer sein:
            // dann gilt es nur als Datum, wenn ein Wort wie "am" davor steht.
            if (day !in 1..31 || month !in 1..12 || (year.length != 4 && !cue)) return@replace m.value
            val ending = deEnding(s, start, 'm')
            val spoken = StringBuilder(deOrdinalStem(day)).append(ending).append(' ').append(deOrdinalStem(month)).append(ending)
            when {
                year.length == 4 -> spoken.append(' ').append(deYear(year.toInt()))
                year.length == 2 -> spoken.append(' ').append(deCardinal(year.toLong()))
                atEnd(s, m.range.last + 1) -> spoken.append('.')   // der Punkt nach dem Monat war zugleich der Satzpunkt
            }
            spoken.toString()
        }
        // "am 12.11 um 18 Uhr": Der zweite Punkt fehlt. Nur nach "am", "vom", "zum" ist das sicher ein Datum.
        return OPEN_DATE.replace(dated) { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val prev = wordBefore(dated, m.range.first).lowercase()
            if (day !in 1..31 || month !in 1..12 || prev !in OPEN_DATE_CUES) return@replace m.value
            deOrdinalStem(day) + "en " + deOrdinalStem(month) + "en"
        }
    }

    private val OPEN_DATE_CUES = setOf("am", "vom", "zum")

    // Hinter der Uhrzeit darf "Uhr" oder das Kürzel "h" stehen ("14:30h"); beides ist mit "Uhr" schon gesagt.
    private val CLOCK = Regex("""(?<![\d:.,\p{L}])([01]?\d|2[0-4]):([0-5]\d)(?::([0-5]\d))?(?![\d:])( ?(?:Uhr|h)(?![\p{L}\d]))?""")
    private val CLOCK_DOTTED = Regex("""(?<![\d:.,\p{L}])([01]?\d|2[0-4])\.([0-5]\d) ?(?:Uhr|h)(?![\p{L}\d])""")
    private val SCORE = Regex("""(?<![\d:.,\p{L}])(\d{1,3}):(\d{1,7})(?![\d:])""")
    private val CLOCK_SPAN = Regex("""(?<![\d:.,\p{L}])(\d{1,2}:\d{2}(?: ?Uhr)?) ?[-–—] ?(?=\d{1,2}:\d{2}(?![\d:])|\d{1,2} ?Uhr)""")
    private val DURATION = Regex("""(?<![\d:.,\p{L}])(\d{1,2}):([0-5]\d) ?(h|Stunden)(?![\p{L}\d])""")

    /** Nach diesen Wörtern ist "14:30 h" eine Uhrzeit und keine Dauer. "nach" und "vor" fehlen: "nach 2:30 h" dauert. */
    private val CLOCK_CUES = setOf("um", "ab", "bis", "gegen", "von", "seit")
    private val DURATION_CUES = setOf(
        "in", "dauert", "dauerte", "dauern", "braucht", "brauchte", "brauchen", "für", "über", "unter", "etwa",
        "rund", "circa", "knapp", "fast", "insgesamt",
    )

    /** Wörter, die einen Spielstand oder ein Verhältnis ankündigen: Dann ist "23:21" keine Uhrzeit. */
    private val SCORE_CUES = setOf(
        "gewann", "gewannen", "gewinnt", "gewonnen", "verlor", "verloren", "verliert", "siegte", "siegten", "siegt",
        "unterlag", "schlug", "schlägt", "besiegte", "unentschieden", "remis", "endstand", "spielstand",
        "halbzeitstand", "zwischenstand", "endergebnis", "maßstab", "verhältnis", "mischungsverhältnis",
        "seitenverhältnis", "quote",
    )
    private val SCORE_WORDS_AFTER = setOf("gewonnen", "verloren", "unentschieden")
    /** Direkt nach diesen Wörtern bleibt es eine Uhrzeit, auch wenn weiter vorn von einem Spiel die Rede ist. */
    private val SCORE_VETO = setOf("um", "ab", "bis", "gegen", "seit")

    /**
     * Kündigt der Zusammenhang einen Spielstand an? Geschaut wird auf die letzten Wörter vor [start] (bis zum
     * nächsten Satzzeichen) und auf das Wort nach [end] ("haben 23:21 gewonnen").
     */
    private fun scoreCue(s: String, start: Int, end: Int): Boolean {
        // "Endstand: 23:21": Der Doppelpunkt direkt davor trennt nur die Ansage vom Stand.
        val head = s.substring(0, start).trimEnd().removeSuffix(":")
        if (wordBefore(head, head.length).lowercase() in SCORE_VETO) return false
        for (skip in 0 until 5) {
            val word = wordBefore(head, head.length, skip).lowercase()
            if (word.isEmpty()) break
            if (word in SCORE_CUES) return true
        }
        return wordAfter(s, end).lowercase() in SCORE_WORDS_AFTER
    }

    /** "21:14" -> "einundzwanzig Uhr vierzehn"; was keine Uhrzeit sein kann ("2:1"), ist ein Spielstand. */
    private fun times(text: String, en: Boolean): String {
        // "8:00-18:00 Uhr": Zwischen zwei Uhrzeiten heißt der Strich "bis".
        var s = text.replace(CLOCK_SPAN, if (en) "$1 to " else "$1 bis ")
        if (!en) {
            // "Der Film dauert 1:30 h", "2:30 Stunden": eine Dauer, keine Uhrzeit. Die Zahlregel weiter unten
            // macht daraus "eine Stunde dreißig Minuten". "um 14:30 h" bleibt dagegen eine Uhrzeit.
            val spans = s
            s = DURATION.replace(spans) { m ->
                val hours = m.groupValues[1].toInt()
                val minutes = m.groupValues[2].toInt()
                val prev = wordBefore(spans, m.range.first).lowercase()
                val isClock = m.groupValues[3] == "h" && (prev in CLOCK_CUES || (hours >= 6 && prev !in DURATION_CUES))
                if (isClock) return@replace m.value
                listOfNotNull(
                    if (hours > 0 || minutes == 0) "$hours h" else null,
                    if (minutes > 0) "$minutes min" else null,
                ).joinToString(" ")
            }
        }
        val prepared = s
        s = CLOCK.replace(prepared) { m ->
            val bare = m.groupValues[3].isEmpty() && m.groupValues[4].isEmpty()
            // "Deutschland gewann 23:21", "Maßstab 1:50": Der Zusammenhang sagt, dass es keine Uhrzeit ist.
            if (!en && bare && scoreCue(prepared, m.range.first, m.range.last + 1)) return@replace m.value
            clock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), en) +
                (if (en && m.groupValues[4].isNotEmpty()) " " + m.groupValues[4].trim() else "")
        }
        if (!en) s = CLOCK_DOTTED.replace(s) { m -> clock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null, false) }
        return SCORE.replace(s) { m ->
            plain(m.groupValues[1], en) + (if (en) " to " else " zu ") + plain(m.groupValues[2], en)
        }
    }

    private fun clock(hour: Int, minute: Int, second: Int?, en: Boolean): String {
        val sb = StringBuilder()
        if (en) {
            sb.append(enCardinal(hour.toLong()))
            sb.append(if (minute == 0) " o'clock" else if (minute < 10) " oh " + EN_SMALL[minute] else " " + enBelow1000(minute))
            if (second != null && second > 0) sb.append(" and ").append(enCardinal(second.toLong())).append(if (second == 1) " second" else " seconds")
        } else {
            // "ein Uhr", nicht "eins Uhr"; volle Stunden ohne "null".
            sb.append(deJoinable(hour.toLong())).append(" Uhr")
            if (minute > 0) sb.append(' ').append(deCardinal(minute.toLong()))
            if (second != null && second > 0) sb.append(" und ").append(if (second == 1) "eine Sekunde" else deCardinal(second.toLong()) + " Sekunden")
        }
        return sb.toString()
    }

    private const val NOT_IN_NUMBER = """(?<![\d\p{L}.,:/+-])(?<!\d )"""
    // Das jeweils zweite "+" macht die Wiederholung besitzergreifend: Einmal gelesene Ziffern werden nie neu
    // aufgeteilt. Ohne das probiert der Ausdruck bei "+" und einer langen Ziffernfolge mit unpassendem Ende
    // jede denkbare Aufteilung durch; schon 90 Zeichen kosteten dann viele Sekunden.
    private val PHONE_PLUS = Regex("""$NOT_IN_NUMBER\+\d{1,3}+(?:[ /-]?\d++){1,6}+(?!\d|[.,:]\d)""")
    private val PHONE_ZERO = Regex("""${NOT_IN_NUMBER}0\d+(?:[ /-]\d{2,})*(?!\d|[.,:]\d)""")
    private val PHONE_GROUPS = Regex("""$NOT_IN_NUMBER\d+(?:-\d+){2,}(?![\d-]|[.,:]\d)""")
    private val LONG_RUN = Regex("""$NOT_IN_NUMBER\d{8,}(?!\d|[.,:]\d)""")

    /**
     * Telefonnummern und andere Ziffernfolgen werden Ziffer für Ziffer gelesen: Was mit Null oder "+"
     * beginnt, aus mehreren Bindestrich-Gruppen besteht oder sehr lang ist, ist keine Menge.
     * Die Gruppen bleiben als kurze Pausen (Kommas) hörbar.
     */
    private fun phoneNumbers(text: String, en: Boolean): String {
        fun spell(match: String) = match.split(' ', '/', '-').filter { it.isNotEmpty() }
            .joinToString(", ") { (if (it.startsWith("+")) "plus " else "") + digitWords(it, en) }
        var s = PHONE_PLUS.replace(text) { m -> if (m.value.count { it.isDigit() } >= 7) spell(m.value) else m.value }
        s = PHONE_ZERO.replace(s) { m -> spell(m.value) }
        // Drei und mehr Gruppen mit Bindestrich: lang = Nummer (Ziffern einzeln), kurz = Aufzählung ("4-4-2").
        s = PHONE_GROUPS.replace(s) { m ->
            if (m.value.count { it.isDigit() } >= 7) spell(m.value) else m.value.split('-').joinToString(", ") { plain(it, en) }
        }
        // Eine lange Ziffernfolge mit Nullen am Ende ist eher eine große Menge ("8000000000 Menschen").
        return LONG_RUN.replace(s) { m -> if (m.value.endsWith("000") && m.value.length <= 15) m.value else spell(m.value) }
    }

    private val DOTTED_DE = Regex("""(?<![\d.,])([-−+]?)(\d+(?:\.\d+)+)(?!\d|\.\d)""")
    private val DOTTED_EN = Regex("""(?<![\d.,])([-−+]?)(\d+(?:\.\d+){2,})(?!\d|\.\d)""")
    private val THOUSANDS_DE = Regex("""\d{1,3}(?:\.\d{3})+""")

    /** Versionsnummern und Ähnliches: "1.2.3" -> "eins Punkt zwei Punkt drei". "12.500" bleibt eine Zahl. */
    private fun dotted(s: String, en: Boolean): String = (if (en) DOTTED_EN else DOTTED_DE).replace(s) { m ->
        val number = m.groupValues[2]
        if (!en && THOUSANDS_DE.matches(number)) return@replace m.value
        val start = m.range.first
        val end = m.range.last + 1
        val parts = number.split('.')
        if (!en && parts.size == 2) {
            // "2.5 kg", "5.50 €", "3.5 Grad": Wird etwas gemessen oder bezahlt, ist der Punkt ein englisch
            // geschriebenes Komma. Als Kommazahl weitergereicht, bekommt sie unten auch ihre Einheit.
            val next = wordAfter(s, end)
            val paidBefore = start > 0 && (s[start - 1] in "€$" || (s[start - 1] == ' ' && start > 1 && s[start - 2] in "€$"))
            val measured = paidBefore || UNIT.matchAt(s, end) != null || SCALE_DE.matchAt(s, end) != null ||
                next in MONEY_WORDS || next in COUNT_WORDS
            if (measured) return@replace m.groupValues[1] + parts[0] + "," + parts[1]
        }
        lead(s, start, m.groupValues[1]) +
            parts.mapIndexed { i, part ->
                // "3.14159": Lange Nachkommastellen einzeln, sonst würde daraus "vierzehntausend...".
                if (i == 1 && parts.size == 2 && part.length > 3) digitWords(part, en) else plain(part, en)
            }.joinToString(if (en) " point " else " Punkt ") +
            (if (end < s.length && s[end].isLetter()) " " else "")
    }

    /** Hauptwörter mit Geschlecht (m, f, n): für "1. Platz" -> "erster Platz" und "1 Stunde" -> "eine Stunde". */
    private val GENDER: Map<String, Char> = HashMap<String, Char>().apply {
        fun add(gender: Char, words: String) = words.split(' ').forEach { put(it, gender) }
        add(
            'm',
            "FC Platz Rang Stock Weltkrieg Advent Teil Spieltag Geburtstag Tag Monat Versuch Schritt Satz Meter " +
                "Kilometer Zentimeter Millimeter Quadratmeter Kubikmeter Euro Dollar Cent Liter Milliliter Punkt " +
                "Mann Mensch Löffel Esslöffel Teelöffel Fuß Franken",
        )
        add(
            'f',
            "Etage Klasse Liga Bundesliga Runde Halbzeit Auflage Staffel Folge Woche Minute Stunde Sekunde Stelle " +
                "Reihe Generation Million Milliarde Billion Frau Person Tasse Flasche Prise Seite Tonne Meile " +
                "Kilowattstunde Sache Frage",
        )
        add(
            'n',
            "Mal mal Jahrhundert Jahrtausend Kapitel Quartal Semester Jahr Obergeschoss Stockwerk Geschoss " +
                "Lebensjahr Prozent Kilogramm Gramm Kilo Pfund Tor Kind Stück Glas Zimmer Haus Auto Wort Gigabyte " +
                "Megabyte Kilowatt Drittel Viertel Grad",
        )
        // Häufige Hauptwörter, deren Geschlecht die Endung nicht verrät ("sein drittes Buch", "einen Hund").
        add(
            'm',
            "Film Sieg Titel Roman Sohn Hund Song Treffer Preis Präsident Kanzler Akt Abschnitt Absatz Artikel Lauf " +
                "Durchgang Zug Bus Flug Termin Besuch Apfel Becher Schluck",
        )
        add(
            'f',
            "Tochter Mutter Schwester Stadt Nacht Wahl Hand Zahl Nummer Oper Antwort Zeit Jahreszeit Amtszeit Dosis " +
                "Handvoll Messerspitze",
        )
        add(
            'n',
            "Buch Album Spiel Lied Bild Land Tier Ziel Mitglied Team Rennen Turnier Finale Konzert Werk Gesetz Ei Bier " +
                "Paket Blatt Brot Gericht Level Bundesland Schiff Flugzeug Fahrrad Handy",
        )
    }

    /**
     * Hauptwörter, die üblicherweise durchgezählt werden. Nur vor ihnen gilt "N." auch ohne Begleiter als
     * Ordnungszahl. Maße wie "Meter" oder "Euro" fehlen absichtlich: "Es sind 5. Euro..." ist ein Satzende.
     */
    private val ORDINAL_NOUNS = setOf(
        "FC", "Platz", "Rang", "Stock", "Weltkrieg", "Advent", "Teil", "Spieltag", "Geburtstag", "Versuch", "Schritt",
        "Etage", "Klasse", "Liga", "Bundesliga", "Runde", "Halbzeit", "Auflage", "Staffel", "Folge", "Stelle",
        "Reihe", "Generation", "Mal", "Jahrhundert", "Jahrtausend", "Kapitel", "Quartal", "Semester",
        "Obergeschoss", "Stockwerk", "Geschoss", "Lebensjahr",
    )

    /** Wörter, vor denen "N." sicher eine Ordnungszahl ist, auch ohne Hauptwort dahinter ("am 5. kommt er"). */
    private val DATE_PREPOSITIONS = setOf("am", "vom", "zum", "beim")
    private val DETERMINERS = setOf(
        "der", "die", "das", "den", "dem", "des", "im", "zur", "ins", "ans", "jeder", "jede", "jeden", "jedem", "jedes",
    )
    /** Nach diesen Begleitern ist "N." auch am Satzende eine Ordnungszahl ("Heute ist der 12."). */
    private val DATE_ARTICLES = setOf("der", "den", "dem", "des")
    private val ORDINAL = Regex("""(?<![\d.,\p{L}-])(\d{1,3})\.(?![\d\p{L}])""")
    // "1., 2. und 3. Platz", "der 1. und der 2. Platz": Das Hauptwort steht erst hinter der letzten Zahl.
    private val ORDINAL_LIST_AHEAD = Regex("""(?:, ?\d{1,3}\.)*,? (?:bis|und|oder)(?: zum| de[rnm]| die| das)? \d{1,3}\. (\p{L}+)""")

    /**
     * Ordnungszahlen nur dort, wo es eindeutig ist: vor einem Monat, vor einem bekannten Hauptwort
     * ("1. FC Köln", "3. Etage") oder nach einem Begleiter ("am 5.", "im 2. Stock", "sein 3. Tor").
     * Geht es nach einem Begleiter klein weiter ("der 3. von links"), kann der Punkt kein Satzende sein.
     * Überall sonst kann "5." auch eine Zahl am Satzende sein und bleibt eine Grundzahl mit Punkt.
     */
    private fun ordinals(s: String): String {
        var last: String? = null
        var lastEnd = -1
        return ORDINAL.replace(s) { m ->
            val n = m.groupValues[1].toInt()
            val start = m.range.first
            val after = m.range.last + 1
            val next = wordAfter(s, after)
            val prev = wordBefore(s, start).lowercase()
            val sentenceEnd = atEnd(s, after)
            val listNoun = ORDINAL_LIST_AHEAD.matchAt(s, after)?.groupValues?.get(1)
            val known = next in DE_MONTHS || next in ORDINAL_NOUNS
            val listed = listNoun != null && (listNoun in DE_MONTHS || listNoun in ORDINAL_NOUNS)
            val companion = prev in DETERMINERS || prev in POSSESSIVES
            val isOrdinal = n > 0 && (
                known || listed || (prev in DATE_PREPOSITIONS && n <= 31) ||
                    (companion && capitalized(next) && next !in SENTENCE_STARTERS) ||
                    (prev in DETERMINERS && next.isNotEmpty() && next[0].isLowerCase()) ||
                    (prev in DATE_ARTICLES && sentenceEnd)
                )
            if (!isOrdinal) return@replace m.value
            val noun = if (known || !listed) next else listNoun!!
            val gender = if (noun in DE_MONTHS) 'm' else GENDER[noun]
            // "am 1., 2. und 3. Mai": In einer Aufzählung übernimmt jede Zahl die Endung der vorigen.
            val inList = last != null && lastEnd >= 0 && s.substring(lastEnd, start).trim() == ","
            val ending = if (inList) last!! else deEnding(s, start, gender, last)
            last = ending
            lastEnd = after
            // War der Punkt zugleich das Satzende ("Er kommt am 5."), bleibt er stehen.
            deOrdinalStem(n) + ending + (if (sentenceEnd || next in SENTENCE_STARTERS) "." else "")
        }
    }

    private val EN_ORDINAL = Regex("""(?<![\d.,\p{L}])(\d{1,4})(?:st|nd|rd|th)(?![\p{L}\d])""")

    private fun englishOrdinals(s: String): String = EN_ORDINAL.replace(s) { m -> enOrdinal(m.groupValues[1].toLong()) }

    // Auch einstellig: "ein 5er BMW" -> "fünfer", "im 3er-Pack" -> "dreier-Pack".
    private val DECADE_DE = Regex("""(?<![\d.,\p{L}])(\d{4}|\d{1,2})-?er(n?)(?![\p{L}\d])""")
    private val DECADE_EN = Regex("""(?<![\d.,\p{L}])(\d{4}|\d{2})'?s(?![\p{L}\d])""")

    /** "die 90er" -> "die neunziger", "1990er" -> "neunzehnhundertneunziger", "the 1990s" -> "nineteen nineties". */
    private fun decades(s: String, en: Boolean): String =
        if (en) DECADE_EN.replace(s) { m ->
            val digits = m.groupValues[1]
            val n = digits.toInt()
            if (n % 10 != 0 || n == 0) return@replace m.value
            val words = if (digits.length == 4) enYear(n) else enCardinal(n.toLong())
            if (words.endsWith("y")) words.dropLast(1) + "ies" else words + "s"
        } else DECADE_DE.replace(s) { m ->
            val digits = m.groupValues[1]
            // "ein 1er BMW", "ein 1er-Schüler": Das heißt "Einser", nicht "einer".
            val stem = if (digits == "1") "eins" else if (digits.length == 4) deYear(digits.toInt()) else deJoinable(digits.toLong())
            stem + "er" + m.groupValues[2]
        }

    private const val TOKEN_DE = """\d{1,3}(?:\.\d{3})+|\d+(?:,\d+)?"""
    private const val TOKEN_EN = """\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?"""
    private val RANGE_DE = Regex("""(?<![\d.,\p{L}-])($TOKEN_DE)(-| - | ?[–—] ?)($TOKEN_DE)(?![\d-]|[.,]\d)""")
    private val RANGE_EN = Regex("""(?<![\d.,\p{L}-])($TOKEN_EN)(-| - | ?[–—] ?)($TOKEN_EN)(?![\d-]|[.,]\d)""")

    /**
     * "3-4 Stunden" -> "3 bis 4 Stunden". Hier wird nur der Strich ersetzt; die Zahlen selbst liest die
     * allgemeine Regel weiter unten. Der Reihe nach:
     *  - "7 - 2 = 5": Mit einem Gleichheitszeichen daneben ist es eine Rechnung.
     *  - "3-4": Die zweite Zahl ist größer, also ein Bereich.
     *  - "1914-18": ein Zeitraum mit abgekürztem Endjahr.
     *  - "384-322 v. Chr.": zwei große Zahlen, also ein rückwärts laufender Bereich.
     *  - "10 – 5" mit Leerzeichen bleibt, wie es ist; der Strich kann auch ein Gedankenstrich sein.
     *  - "24-7" ist ein Name aus zwei Zahlen.
     * Wichtig: Nie zwei Zifferngruppen nur durch ein Leerzeichen getrennt hinterlassen, wenn die zweite
     * dreistellig ist. Die Zahlregel hielte das Leerzeichen für einen Tausendertrenner ("384 322").
     */
    private fun ranges(s: String, en: Boolean): String = (if (en) RANGE_EN else RANGE_DE).replace(s) { m ->
        fun value(token: String) =
            (if (en) token.replace(",", "") else token.replace(".", "").replace(',', '.')).toDoubleOrNull() ?: 0.0
        val (a, dash, b) = m.destructured
        val start = m.range.first
        val end = m.range.last + 1
        val to = if (en) " to " else " bis "
        val plainA = a.all { it in '0'..'9' }
        val plainB = b.all { it in '0'..'9' }
        val equation = s.startsWith("=", end) || s.startsWith(" =", end) || s.substring(0, start).trimEnd().endsWith("=")
        // "verlor 1-3", "gewann 3-1": mit einem Wort wie "gewann" davor ein Spielstand, kein Bereich.
        val score = !en && plainA && plainB && a.length <= 3 && b.length <= 3 && scoreCue(s, start, end)
        when {
            equation -> "$a minus $b"
            score -> "$a zu $b"
            value(a) < value(b) -> a + to + b
            plainA && plainB && a.length == 4 && b.length == 2 && b.toInt() > a.toInt() % 100 -> a + to + a.take(2) + b
            value(a) >= 100 && value(b) >= 100 -> a + to + b
            dash.length > 1 -> m.value
            plainB && b.length == 3 -> "$a, $b"
            else -> "$a $b"
        }
    }

    // Nicht hinter "/": In "3/4-Takt" gehört die Vier zum Bruch, den die Bruchregel als Ganzes liest.
    private val COMPOUND = Regex("""(?<![\d.,/\p{L}])(\d+)-(?=\p{L})""")
    private val PERCENT_IG = Regex("""(?<![\d.,\p{L}])(\d{1,6}) ?%(?=ig)""")

    /** "10-jährig" -> "zehnjährig", "2-Zimmer-Wohnung" -> "zwei-Zimmer-Wohnung", "100%ig" -> "einhundertprozentig". */
    private fun compounds(text: String, en: Boolean): String {
        val s = if (en) text else PERCENT_IG.replace(text) { m -> deJoinable(m.groupValues[1].toLong()) + "prozent" }
        return compoundWords(s, en)
    }

    private fun compoundWords(s: String, en: Boolean): String = COMPOUND.replace(s) { m ->
        val digits = m.groupValues[1]
        val spellOut = (digits.length > 1 && digits[0] == '0') || digits.length > 15
        val lowerNext = s[m.range.last + 1].isLowerCase()
        when {
            spellOut -> digitWords(digits, en) + "-"
            en -> enCardinal(digits.toLong()) + "-"
            lowerNext -> deJoinable(digits.toLong())          // klein weiter: ein Wort ("einjährig", "dreimal")
            else -> deJoinable(digits.toLong()) + "-"
        }
    }

    private val FRACTION = Regex("""(?<![\d.,/\p{L}])(\d{1,3})/(\d{1,4})(?![\d/]|[.,]\d)""")

    private val MIXED_NUMBER = Regex("""(?<![\d.,/\p{L}])(\d{1,3}) (1/2|1/4|3/4)(?![\d/]|[.,]\d)""")
    private val MIXED_DECIMALS = mapOf("1/2" to "5", "1/4" to "25", "3/4" to "75")
    private val YEAR_PAIR = Regex("""(?<![\d.,/\p{L}])(1[1-9]\d\d|20\d\d)/(\d{4}|\d{2})(?![\d/]|[.,]\d)""")
    private val DE_BIG_FRACTIONS = mapOf(100 to "Hundertstel", 1000 to "Tausendstel")
    private val HALF_OF = setOf("der", "des", "aller", "von", "vom", "davon")

    /** "3/4" -> "drei Viertel". Kein echter Bruch ("24/7", "50/50"): beide Zahlen nacheinander. */
    private fun fractions(text: String, en: Boolean): String {
        val comma = if (en) "." else ","
        // "2 1/2 kg" -> "2,5 kg": Als Kommazahl ist die gemischte Zahl eindeutig und bekommt ihre Einheit.
        var s = MIXED_NUMBER.replace(text) { m -> m.groupValues[1] + comma + MIXED_DECIMALS.getValue(m.groupValues[2]) }
        // "Saison 2025/26": zwei Jahre, kein Bruch.
        if (!en) s = YEAR_PAIR.replace(s) { m ->
            val second = m.groupValues[2]
            deYear(m.groupValues[1].toInt()) + " " + (if (second.length == 4) deYear(second.toInt()) else plain(second, false))
        }
        val prepared = s
        val fractioned = FRACTION.replace(prepared) { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val start = m.range.first
            val end = m.range.last + 1
            // Ein echter Bruch: Zähler kleiner als Nenner, und davor kein Wort wie "Gleis" oder "Seite".
            val proper = a in 1 until b && wordBefore(prepared, start) !in LABEL_NOUNS
            val unit = if (proper && b in 2..10) UNIT.matchAt(prepared, end) else null
            when {
                // "3/4 l": Vor einem Einheitenzeichen als Kommazahl weiterreichen; die Zahlregel hängt die Einheit an.
                unit != null && 100 % b == 0 -> "0" + comma + (a * 100 / b).toString().trimEnd('0')
                !en && proper && b in DE_BIG_FRACTIONS -> (if (a == 1) "ein" else deCardinal(a.toLong())) + " " + DE_BIG_FRACTIONS.getValue(b)
                !proper || b !in 2..10 -> plain(m.groupValues[1], en) + " " + plain(m.groupValues[2], en)
                en -> {
                    val part = when (b) { 2 -> "half"; 4 -> "quarter"; else -> enOrdinal(b.toLong()) }
                    enCardinal(a.toLong()) + " " + if (a == 1) part else if (b == 2) "halves" else part + "s"
                }
                a == 1 && b == 2 -> deHalf(prepared, start, end)
                else -> (if (a == 1) "ein" else deCardinal(a.toLong())) + " " + DE_FRACTIONS[b]
            }
        }
        // "1/3 l" lässt sich nicht als Kommazahl sagen. Dann steht das Einheitenzeichen jetzt hinter einem
        // Bruchwort und wird hier ausgeschrieben ("ein Drittel Liter"); die Zahlregel sieht nur hinter Ziffern nach.
        return if (en) fractioned else UNIT_AFTER_FRACTION.replace(fractioned) { m -> " " + UNITS.getValue(m.groupValues[1]).one }
    }

    // "by lazy", weil die Einheitentabelle erst weiter unten in der Datei angelegt wird.
    private val UNIT_AFTER_FRACTION: Regex by lazy {
        Regex("(?<=Drittel|Viertel|Fünftel|Sechstel|Siebtel|Achtel|Neuntel|Zehntel) (" + UNITS.keys.filter { key -> key.all { it.isLetter() } }
            .sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\d])")
    }

    /** "1/2 Liter" -> "ein halber Liter", "in 1/2 Stunde" -> "in einer halben Stunde"; ohne bekanntes Hauptwort "ein halb". */
    private fun deHalf(s: String, start: Int, end: Int): String {
        val next = wordAfter(s, end)
        if (next in HALF_OF) return "die Hälfte"          // "1/2 der Leute"
        val gender = GENDER[next] ?: return "ein halb"
        val prev = wordBefore(s, start).lowercase()
        return when {
            // Steht "eine" oder "einem" schon da, fehlt nur noch das Eigenschaftswort.
            prev == "eine" -> "halbe"
            prev == "einen" || prev == "einem" || prev == "einer" -> "halben"
            prev == "ein" -> if (gender == 'n') "halbes" else "halber"
            prev in DATIVE -> if (gender == 'f') "einer halben" else "einem halben"
            gender == 'f' -> "eine halbe"
            gender == 'n' -> "ein halbes"
            prev in ACCUSATIVE -> "einen halben"
            else -> "ein halber"
        }
    }

    // ------------------------------------------------------------------------------------------
    // Die allgemeine Zahl mit Vorzeichen, Nachkommastellen, Einheit

    private class UnitName(val one: String, val many: String, val gender: Char, val enOne: String, val enMany: String) {
        constructor(name: String, gender: Char, enOne: String, enMany: String = enOne + "s") : this(name, name, gender, enOne, enMany)
    }

    private val EURO = UnitName("Euro", 'm', "euro")
    private val DOLLAR = UnitName("Dollar", 'm', "dollar")
    private val LITER = UnitName("Liter", 'm', "liter")
    private val MINUTE = UnitName("Minute", "Minuten", 'f', "minute", "minutes")
    private val CENT = UnitName("Cent", 'm', "cent")
    private val SQUARE_METER = UnitName("Quadratmeter", 'm', "square meter")
    private val CUBIC_METER = UnitName("Kubikmeter", 'm', "cubic meter")
    private val SQUARE_KILOMETER = UnitName("Quadratkilometer", 'm', "square kilometer")

    // Groß- und Kleinschreibung zählt: "5 g" sind Gramm, "5G" ist ein Mobilfunknetz.
    private val UNITS: Map<String, UnitName> = mapOf(
        "km/h" to UnitName("Kilometer pro Stunde", 'm', "kilometer per hour", "kilometers per hour"),
        "°C" to UnitName("Grad Celsius", 'n', "degree Celsius", "degrees Celsius"),
        "°F" to UnitName("Grad Fahrenheit", 'n', "degree Fahrenheit", "degrees Fahrenheit"),
        "°" to UnitName("Grad", 'n', "degree"),
        "%" to UnitName("Prozent", 'n', "percent", "percent"),
        "€" to EURO, "EUR" to EURO, "$" to DOLLAR, "USD" to DOLLAR,
        "km²" to SQUARE_KILOMETER, "m²" to SQUARE_METER, "m³" to CUBIC_METER,
        "km" to UnitName("Kilometer", 'm', "kilometer"),
        "cm" to UnitName("Zentimeter", 'm', "centimeter"),
        "mm" to UnitName("Millimeter", 'm', "millimeter"),
        "m" to UnitName("Meter", 'm', "meter"),
        "kg" to UnitName("Kilogramm", 'n', "kilogram"),
        "mg" to UnitName("Milligramm", 'n', "milligram"),
        "g" to UnitName("Gramm", 'n', "gram"),
        "t" to UnitName("Tonne", "Tonnen", 'f', "ton", "tons"),
        "ml" to UnitName("Milliliter", 'm', "milliliter"),
        "l" to LITER, "L" to LITER,
        "h" to UnitName("Stunde", "Stunden", 'f', "hour", "hours"),
        "min" to MINUTE, "Min" to MINUTE,
        "s" to UnitName("Sekunde", "Sekunden", 'f', "second", "seconds"),
        "ms" to UnitName("Millisekunde", "Millisekunden", 'f', "millisecond", "milliseconds"),
        "kWh" to UnitName("Kilowattstunde", "Kilowattstunden", 'f', "kilowatt hour", "kilowatt hours"),
        "kW" to UnitName("Kilowatt", 'n', "kilowatt"),
        "GB" to UnitName("Gigabyte", 'n', "gigabyte"),
        "MB" to UnitName("Megabyte", 'n', "megabyte"),
        "TB" to UnitName("Terabyte", 'n', "terabyte"),
        "‰" to UnitName("Promille", 'n', "per mille", "per mille"),
        // Flächen und Räume in den Schreibweisen ohne Hochzahl, wie sie auf einer Tastatur entstehen.
        "m2" to SQUARE_METER, "qm" to SQUARE_METER, "m3" to CUBIC_METER, "km2" to SQUARE_KILOMETER,
        "cm²" to UnitName("Quadratzentimeter", 'm', "square centimeter"),
        "cm³" to UnitName("Kubikzentimeter", 'm', "cubic centimeter"),
    )

    // Längere Kürzel zuerst, sonst gewänne "m" gegen "min" oder "°" gegen "°C".
    private val UNIT = Regex(
        " ?(" + UNITS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\d])",
    )

    /** Wonach in Preisen und Raten geteilt wird ("50 €/Monat"); dazu kommen alle Einheitenkürzel aus Buchstaben. */
    private val PER_WORDS = listOf(
        "Tag", "Woche", "Monat", "Jahr", "Stunde", "Minute", "Sekunde", "Person", "Stück", "Kopf", "Nacht", "Liter",
        "Kilo", "Kilogramm", "Meter", "Kilometer",
    )
    private val PER = Regex(
        "/ ?(" + (UNITS.keys.filter { key -> key.all { it.isLetter() } } + PER_WORDS)
            .sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\d])",
    )
    private val MONEY_WORDS = mapOf("Euro" to EURO, "Euros" to EURO, "euros" to EURO, "Dollar" to DOLLAR, "Dollars" to DOLLAR, "dollars" to DOLLAR)
    private val SCALE_DE = Regex(""" ?(Millionen|Milliarden|Million|Milliarde|Tausend)(?!\p{L})""")
    private val SCALE_EN = Regex(""" ?(million|billion|trillion|thousand)(?!\p{L})""")

    private val NUMBER_DE = Regex("""([-−+])?([€$] ?)?(\d{1,3}(?:\.\d{3})+(?!\d)|\d{1,3}(?: \d{3})+(?!\d)|\d+)(?:,(\d+))?""")
    private val NUMBER_EN = Regex("""([-−+])?([€$] ?)?(\d{1,3}(?:,\d{3})+(?!\d)|\d+)(?:\.(\d+))?""")

    private fun numbers(s: String, en: Boolean): String {
        val rule = if (en) NUMBER_EN else NUMBER_DE
        val separator = if (en) '.' else ','
        val out = StringBuilder()
        var pos = 0
        while (true) {
            val m = rule.find(s, pos) ?: break
            val start = m.range.first
            var end = m.range.last + 1
            val sign = m.groupValues[1]
            var decimals: String? = m.groupValues[4].ifEmpty { null }
            // Hängt die Zahl an einem Buchstaben ("A4", "COVID-19"), ist sie ein Name: keine Einheit, kein Jahr.
            val label = start > 0 && s[start - 1].isLetter()
            var number = m.groupValues[3]
            // "Gleis 3 120 Minuten später": Hinter einem Namen ist das Leerzeichen kein Tausendertrenner.
            if (' ' in number && (label || wordBefore(s, start) in LABEL_NOUNS)) {
                number = number.substringBefore(' ')
                end = m.groups[3]!!.range.first + number.length
                decimals = null
            }
            val digits = number.filter { it.isDigit() }
            // "1,2,3" ist eine Aufzählung, keine Dezimalzahl.
            val inList = (sign.isEmpty() && start >= 2 && s[start - 1] == separator && s[start - 2].isDigit()) ||
                (end + 1 < s.length && s[end] == separator && s[end + 1].isDigit())
            if (decimals != null && inList) {
                end -= decimals.length + 1
                decimals = null
            }
            var unit: UnitName? = when (m.groupValues[2].trim()) { "€" -> EURO; "$" -> DOLLAR; else -> null }
            var scale = ""
            if (unit != null) {
                // "$5 million": erst die Zahl, dann die Größenordnung, dann die Währung.
                (if (en) SCALE_EN else SCALE_DE).matchAt(s, end)?.let { scale = " " + it.groupValues[1]; end = it.range.last + 1 }
            } else if (!label) {
                val unitMatch = UNIT.matchAt(s, end)
                if (unitMatch != null) {
                    unit = UNITS.getValue(unitMatch.groupValues[1])
                    end = unitMatch.range.last + 1
                    // "5 min. später": Der Punkt gehört zur Abkürzung, nicht zum Satz.
                    if (s.startsWith(". ", end) && end + 2 < s.length && s[end + 2].isLowerCase()) end++
                } else if (decimals?.length == 2) {
                    // "3,50 Euro" -> "drei Euro fünfzig": das Währungswort wandert zwischen Euro und Cent.
                    val next = wordAfter(s, end)
                    MONEY_WORDS[next]?.let { unit = it; end = s.indexOf(next, end) + next.length }
                }
            }
            out.append(s, pos, start)
            // "1,2,3": nach dem Komma fehlt das Leerzeichen, die Wörter würden sonst aneinanderkleben.
            if (start > 0 && s[start - 1] == ',') out.append(' ')
            out.append(lead(s, start, sign))
            val one = decimals == null && digits == "1"
            val money = unit === EURO || unit === DOLLAR
            val counted = if (scale.isEmpty()) unit else null
            out.append(
                when {
                    label -> plain(digits, en)
                    decimals == null && unit == null && digits.length >= 4 && sign.isEmpty() && digitCue(s, start) -> digitWords(digits, en)
                    decimals != null && money && decimals.length == 2 && digits.length <= 15 && scale.isEmpty() ->
                        money(s, start, digits.toLong(), decimals.toInt(), unit!!, en)
                    decimals != null -> plain(digits, en) + (if (en) " point " else " Komma ") + digitWords(decimals, en) + scale + unitWords(unit, false, en)
                    unit == null && digits == number && sign.isEmpty() && isYear(s, start, end, digits, en) ->
                        if (en) enYear(digits.toInt()) else deYear(digits.toInt())
                    one && !en -> deOne(s, start, end, if (scale.isEmpty()) wordAfter(s, end) else scale.trim(), counted) + scale + unitWords(unit, scale.isEmpty(), false)
                    else -> plain(digits, en) + scale + unitWords(unit, one && scale.isEmpty(), en)
                },
            )
            // "5 €/kg", "3 m/s", "60/min", "100 €/Nacht/Person": Der Schrägstrich vor einer Einheit heißt "pro".
            while (!label) {
                val per = PER.matchAt(s, end) ?: break
                val name = per.groupValues[1]
                out.append(if (en) " per " else " pro ").append(UNITS[name]?.let { if (en) it.enOne else it.one } ?: name)
                end = per.range.last + 1
            }
            // "5G", "H2O": Zahl und folgenden Buchstaben getrennt sprechen.
            if (end < s.length && s[end].isLetter()) out.append(' ')
            pos = end
        }
        return out.append(s, pos, s.length).toString()
    }

    /** Nach diesen Wörtern ist eine Zahl eine Kennung und wird Ziffer für Ziffer gelesen ("PLZ 80331"). */
    private val DIGIT_CUES = setOf(
        "plz", "postleitzahl", "pin", "code", "vorwahl", "durchwahl", "telefon", "telefonnummer", "handynummer",
        "kontonummer", "kundennummer", "bestellnummer", "iban", "isbn",
    )
    private val LINKING_VERBS = setOf("ist", "lautet", "war", "is")

    private fun digitCue(s: String, start: Int): Boolean {
        val prev = wordBefore(s, start).lowercase()
        return prev in DIGIT_CUES || (prev in LINKING_VERBS && wordBefore(s, start, 1).lowercase() in DIGIT_CUES)
    }

    private fun unitWords(unit: UnitName?, single: Boolean, en: Boolean): String = when {
        unit == null -> ""
        en -> " " + if (single) unit.enOne else unit.enMany
        else -> " " + if (single) unit.one else unit.many
    }

    /** "3,50 €" -> "drei Euro fünfzig", "0,99 €" -> "neunundneunzig Cent", "$3.50" -> "three dollars and fifty cents". */
    private fun money(s: String, start: Int, whole: Long, cents: Int, unit: UnitName, en: Boolean): String {
        if (en) {
            val main = enCardinal(whole) + " " + if (whole == 1L) unit.enOne else unit.enMany
            val small = enCardinal(cents.toLong()) + if (cents == 1) " cent" else " cents"
            return if (cents == 0) main else if (whole == 0L) small else "$main and $small"
        }
        val main = if (whole == 1L) deOne(s, start, start, "", unit) + " " + unit.one else deCardinal(whole) + " " + unit.many
        return when {
            cents == 0 -> main
            whole == 0L -> if (cents == 1) deOne(s, start, start, "", CENT) + " Cent" else deCardinal(cents.toLong()) + " Cent"
            else -> main + " " + deCardinal(cents.toLong())
        }
    }

    /** Nach diesen Wörtern ist eine vierstellige Zahl fast immer ein Jahr ("seit 1990", "im Jahr 1989"). */
    private val YEAR_CUES = setOf(
        "jahr", "jahre", "jahres", "jahren", "anno", "seit", "ab", "bis", "vor", "nach", "anfang", "ende", "mitte",
        "sommer", "winter", "frühjahr", "frühling", "herbst", "jahrgang", "in", "um", "zwischen",
    )
    private val EN_YEAR_CUES = setOf(
        "in", "since", "until", "by", "from", "of", "year", "before", "after", "during", "early", "late", "mid",
        "to", "around", "circa",
    )

    /** Was gezählt oder gemessen wird: Davor steht eine Menge, kein Jahr ("bis 1500 Meter"). */
    private val COUNT_WORDS = setOf(
        "Meter", "Metern", "Kilometer", "Kilometern", "Zentimeter", "Millimeter", "Höhenmeter", "Quadratmeter",
        "Kubikmeter", "Hektar", "Euro", "Dollar", "Cent", "Franken", "Pfund", "Prozent", "Grad", "Kilo", "Kilogramm",
        "Gramm", "Liter", "Milliliter", "Tonnen", "Menschen", "Personen", "Leute", "Einwohner", "Einwohnern",
        "Besucher", "Zuschauer", "Teilnehmer", "Teilnehmern", "Mitglieder", "Mitarbeiter", "Jahre", "Jahren",
        "Tage", "Tagen", "Stunden", "Minuten", "Sekunden", "Stück", "Mal", "mal", "Kalorien", "Kilokalorien",
        "Watt", "Kilowatt", "Kilowattstunden", "Volt", "Seiten", "Wörter", "Zeichen", "Schritte", "Punkte", "Fuß",
        "Meilen", "Pixel",
    )

    /**
     * Steht die vierstellige Zahl wie ein Jahr da? Deutsch betrifft das nur 1100 bis 1999
     * ("neunzehnhundert..."); ab 2000 klingt das Jahr wie die Grundzahl. Eine Verwechslung ist harmlos:
     * "fünfzehnhundert Euro" versteht jeder.
     */
    private fun isYear(s: String, start: Int, end: Int, digits: String, en: Boolean): Boolean {
        if (digits.length != 4) return false
        val n = digits.toInt()
        val prev = wordBefore(s, start)
        if (en) {
            val alone = s.trim().trimEnd('.', '!', '?') == digits
            // "November 12th, 1990": Das Komma trennt Tag und Jahr.
            val head = s.substring(0, start).trimEnd()
            val afterDay = head.endsWith(",") && wordBefore(head, head.length - 1).let { it in EN_MONTHS || it in EN_DAY_ORDINALS }
            return n in 1100..2099 && (alone || afterDay || prev.lowercase() in EN_YEAR_CUES || prev in EN_MONTHS)
        }
        if (n !in 1100..1999) return false
        val next = wordAfter(s, end)
        if (next in COUNT_WORDS) return false
        val cue = prev.lowercase() in YEAR_CUES || prev in DE_MONTHS || prev.endsWith("jahr", ignoreCase = true)
        // Ohne Hinweis davor entscheidet das Wort danach: ein Hauptwort wird gezählt ("1800 Zuschauer").
        return cue || !capitalized(next)
    }

    /** Hinter diesen Wörtern ist die Eins ein Name und heißt "eins" ("Gleis 1 Richtung Köln"). */
    private val LABEL_NOUNS = setOf(
        "Seite", "Gleis", "Platz", "Nummer", "Kapitel", "Linie", "Formel", "Teil", "Gruppe", "Stufe", "Klasse",
        "Phase", "Typ", "Punkt", "Band", "Staffel", "Folge", "Version", "Zimmer", "Raum", "Terminal", "Halle",
        "Block", "Abschnitt", "Absatz", "Artikel", "Paragraph", "Level", "Tor", "Bahnsteig", "Note", "Rang",
        "Kanal", "Programm", "Variante", "Option", "Schritt", "Runde", "Liga", "Position", "Ziffer", "Zahl",
        "Faktor", "Größe",
    )
    private val DATIVE = setOf("in", "vor", "seit", "mit", "nach", "von", "bei", "aus", "zu", "ab", "binnen", "unter", "innerhalb")
    // Verhältniswörter und ein paar häufige Verben, nach denen der Akkusativ steht ("kostet einen Euro").
    private val ACCUSATIVE = setOf(
        "für", "ohne", "um", "gegen", "durch", "pro", "je", "über", "auf", "kostet", "kosten", "kostete", "hat",
        "habe", "hast", "haben", "hatte", "braucht", "brauche", "brauchst", "brauchen", "gibt", "nimm", "nehme",
        "nimmt", "dauert", "dauerte", "bekommt", "bekommst", "zahlt", "zahle",
    )
    private val ONE_IN_RANGE = Regex(""" (?:bis|oder) \d+(?:,\d+)? ?(\p{L}+|[%€°])""")
    private val FEMININE_ENDINGS = listOf("e", "ung", "heit", "keit", "ion", "schaft", "tät", "ei")

    /**
     * Die Eins vor einem Hauptwort wird gebeugt: "1 Meter" -> "ein Meter", "1 Stunde" -> "eine Stunde",
     * "in 1 Stunde" -> "in einer Stunde". Allein stehend bleibt sie "eins".
     * [end] ist die Stelle direkt nach der Ziffer, [next] das Wort danach, [unit] eine schon erkannte Einheit.
     */
    private fun deOne(s: String, start: Int, end: Int, next: String, unit: UnitName?): String {
        if (unit == null && next == "Uhr") return "ein"
        val before = wordBefore(s, start)
        if (unit == null && (next == "bis" || next == "oder") && before !in LABEL_NOUNS) {
            // "1 bis 2 Stunden" -> "ein bis zwei Stunden": Das Hauptwort steht erst hinter der zweiten Zahl.
            val noun = ONE_IN_RANGE.matchAt(s, end)?.groupValues?.get(1) ?: ""
            if (noun in GENDER || noun in UNITS || (capitalized(noun) && noun !in SENTENCE_STARTERS)) return "ein"
        }
        val gender = when {
            unit != null -> unit.gender
            before in LABEL_NOUNS -> return "eins"
            next in GENDER -> GENDER.getValue(next)
            // Unbekanntes Hauptwort: Die Endung verrät meist, ob es weiblich ist; sonst bleibt es offen.
            capitalized(next) && next.length > 2 && next !in SENTENCE_STARTERS -> if (FEMININE_ENDINGS.any(next::endsWith)) 'f' else '?'
            else -> return "eins"
        }
        val prev = before.lowercase()
        return when {
            prev in DATIVE -> if (gender == 'f') "einer" else "einem"
            prev in ACCUSATIVE -> when (gender) { 'f' -> "eine"; 'm' -> "einen"; else -> "ein" }
            else -> if (gender == 'f') "eine" else "ein"
        }
    }

    // ------------------------------------------------------------------------------------------
    // Übrig gebliebene Zeichen

    private val LEFTOVER_SYMBOLS = listOf(
        Symbol("""(?<![\p{L}\d])km/h(?![\p{L}\d])""", "Kilometer pro Stunde", "kilometers per hour"),
        Symbol("°C", " Grad Celsius ", " degrees Celsius "),
        Symbol("°F", " Grad Fahrenheit ", " degrees Fahrenheit "),
        Symbol("°", " Grad ", " degrees "),
        Symbol("%", " Prozent ", " percent "),
        Symbol("‰", " Promille ", " per mille "),
        Symbol("²", " hoch zwei ", " squared "),
        Symbol("³", " hoch drei ", " cubed "),
        Symbol("€", " Euro ", " euros "),
        Symbol("""\$""", " Dollar ", " dollars "),
        Symbol("&", " und ", " and "),
        Symbol("""\+""", " plus ", " plus "),
        Symbol("=", " gleich ", " equals "),
        Symbol("×", " mal ", " times "),
        Symbol("≈", " ungefähr ", " about "),
        Symbol("""(?:Paragraph )?§+ ?""", "Paragraph ", "section "),
    )

    /** Zeichen, die nicht an einer Zahl hingen ("Tom & Anna", "100 % sicher" ist oben schon erledigt). */
    private fun leftoverSymbols(text: String, en: Boolean): String =
        LEFTOVER_SYMBOLS.fold(text) { s, sym -> s.replace(sym.regex, if (en) sym.en else sym.de) }
}
