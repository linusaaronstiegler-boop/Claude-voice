package app.claudevoice.core

import kotlin.math.ceil

/** Die Entscheidung des Modells, sie steht in der ersten Zeile seiner Antwort. */
sealed interface Decision {
    /** Nichts sagen. [reason] ist ein kurzes Stichwort (z. B. "hintergrund", "sozial") für Anzeige und Auswertung. */
    data class Skip(val reason: String) : Decision
    /**
     * Sprechen. [length] ist die vom Modell gewählte Länge ("kurz", "mittel", "lang") oder leer, wenn
     * keine der drei dastand. [offProtocol] = das Modell hat die Kopfzeile vergessen und direkt losgeredet.
     * [commentary] kann nur dann wahr sein: Der Text ohne Kopfzeile sieht nicht nach einer Antwort aus,
     * sondern nach einem Kommentar zur Entscheidung oder zum Protokoll ([Headerless.isCommentary]).
     */
    data class Say(val length: String, val offProtocol: Boolean = false, val commentary: Boolean = false) : Decision
}

/** Anweisungen an die App, die nicht vorgelesen werden. */
sealed interface Directive {
    /** "Merk dir das": eine Zeile fürs Gedächtnis. */
    data class Memo(val text: String) : Directive
    /** "Sei für 10 Minuten still." */
    data class Mute(val minutes: Int) : Directive
}

/**
 * Liest die Antwort des Modells, während sie Stück für Stück eintrifft.
 *
 * Protokoll: Die erste Zeile ist "SKIP <grund>" oder "SAY <länge>". Danach folgt der Sprechtext.
 * Zeilen, die mit "MEMO:" oder "MUTE:" beginnen, sind Anweisungen an die App.
 *
 * Warum ein Zeilenprotokoll statt JSON: Das erste Wort entscheidet. Bei SKIP wird der Aufruf sofort
 * abgebrochen, bei SAY kann der Text ohne Umweg in die Sprachausgabe fließen. JSON müsste erst
 * vollständig sein, bevor man es lesen kann.
 *
 * Das Modell hält dieses Format fast immer genau ein, aber nur der Prompt verlangt es, nichts erzwingt
 * es. Wer zu wörtlich liest, macht zwei Arten von Fehlern, und sie wiegen nicht gleich:
 *  - Steuertext landet im Ohr ("SKIP medien", "MEMO: ..."), eine zugesagte Notiz wird nicht gespeichert,
 *    eine zugesagte Ruhe nicht eingehalten. Das merkt der Träger sofort.
 *  - Gewöhnlicher Sprechtext wird für Steuertext gehalten und verschluckt. Die Antwort ist dann
 *    verstümmelt, ohne dass es jemand bemerkt.
 * Die Regeln lesen deshalb großzügig, wo ein Irrtum nur Schweigen kostet, und genau, wo er Sprechtext
 * kosten würde. Bei jeder Regel steht, nach welcher Seite sie sich im Zweifel irrt.
 *
 * Kopfzeile:
 *  - Zeichen vor und hinter den Wörtern, die weder Buchstabe noch Ziffer sind, zählen nicht
 *    ("**SAY kurz**", "> SKIP medien", "SAY: kurz", "SAY <kurz>").
 *  - SKIP gilt in der ersten Zeile schon als Wortanfang und in jeder Schreibweise. Irrtum: Schweigen.
 *    Sofort entschieden ist damit aber nur, wenn hinter SKIP höchstens ein Grund steht (bis zu zwei Wörter;
 *    so sahen 711 von 712 gemessenen SKIP-Zeilen aus). Steht dort ein ganzer Satz, kann er ein Kommentar
 *    sein, auf den die eigentliche Kopfzeile erst folgt ("SKIP-Entscheidung entfällt, ich antworte." und
 *    zwei Zeilen später "SAY kurz", so gemessen). Dann wird weitergelesen: Kommt eine Kopfzeile in genauer
 *    Schreibweise, gilt sie. Kommt keine, bleibt es beim Schweigen, mit dem Satz als Grund. Vorgelesen
 *    wird eine Zeile, die mit SKIP beginnt, in keinem Fall.
 *  - SAY braucht ein Wortende dahinter ("SAYONARA" ist keine Kopfzeile) und dann kurz, mittel oder
 *    lang. Fehlt die Länge, gilt nur das großgeschriebene SAY als Kopfzeile, sonst verlöre eine
 *    englische Antwort wie "Say hello to her." ihr erstes Wort.
 *  - Steht in der Kopfzeile noch mehr, kann das die Antwort selbst sein ("SAY kurz Zweitausend Meter.")
 *    oder ein Zusatz zur Kopfzeile ("SAY sehr kurz", "SAY kurz bis mittel"). Als Sprechtext gilt der
 *    Rest, sobald er ein Satzzeichen enthält, länger als [MAX_LABEL] Zeichen ist oder die Antwort mit
 *    ihm endet. Folgt dagegen auf einen kurzen Rest ohne Satzzeichen weiterer Text, war er ein Zusatz
 *    und wird nicht gesprochen. Irrtum: Im Zweifel ein Zusatz, also still; verloren ginge dabei nur ein
 *    Satzanfang ohne Satzzeichen, und so schreibt das Modell nicht. Gewartet wird höchstens bis zum
 *    ersten Satzzeichen, und früher beginnt die Sprachausgabe ohnehin nicht. Ein Rest ganz ohne
 *    Buchstaben und Ziffern ("SAY kurz (!)") ist nie die Antwort.
 *  - Kommt die Kopfzeile erst nach anderem Text (das Modell begründet erst und entscheidet dann), wird
 *    dieser Text nicht gesprochen. Eine verspätete Kopfzeile zählt aber nur in genauer Schreibweise: SKIP
 *    oder SAY in Großbuchstaben als ganzes Wort, hinter SAY eine bekannte Länge oder nichts. Zeilen vor
 *    einer verspäteten SAY-Kopfzeile, die für sich eine Sonderzeile sind ("MEMO: Zahnarzt am Dienstag"),
 *    werden trotzdem ausgeführt: Sonst sagte der Assistent "Notiert", und nichts wäre gespeichert.
 *  - Eine Ausnahme davon: Kommt hinter der verspäteten SAY-Kopfzeile bis zum Ende des Stroms nichts
 *    Sprechbares mehr, dann war der Text davor keine Begründung, sondern die Antwort. Das Modell hat sie
 *    zuerst geschrieben und die Kopfzeile nachgetragen ("Yes. Venus ... than Mercury.\n\nSAY kurz", so
 *    gemessen). Er wird dann gesprochen, mit der Länge aus der Kopfzeile. Irrtum: Bricht eine Antwort genau
 *    hinter ihrer verspäteten Kopfzeile ab, ist die Begründung zu hören. Ohne die Ausnahme ginge dafür jede
 *    Antwort dieser Form verloren, und zu hören wäre gar nichts ("SAY kurz" und dann Stille).
 *  - Solange keine Kopfzeile da war, wird nichts gesprochen. Erst am Ende des Stroms steht fest, dass
 *    keine mehr kommt; dann gilt alles als Antwort ohne Kopfzeile ([Decision.Say.offProtocol]). Die
 *    Pipeline spricht sie, ohne direkte Anrede höchstens so lang wie "kurz". Nur wenn ihr Anfang nach
 *    einem Kommentar zur Entscheidung aussieht statt nach einer Antwort ([Headerless.isCommentary]),
 *    bleibt es dabei, dass sie ohne direkte Anrede nicht zu hören ist. Irrtum: Eine Antwort ohne
 *    Kopfzeile ist später zu hören als eine mit. Das ist der Preis dafür, dass keine Begründung samt
 *    "SKIP medien" vorgelesen wird. Damit das Warten ein Ende hat, gilt nach [MAX_PARKED] Zeichen ohne
 *    Kopfzeile: Es kommt keine mehr, ab hier fließt der Text. Gezählt wird auch mitten in einer Zeile,
 *    denn ein Absatz ohne Zeilenumbruch kann beliebig lang werden. Steht ganz am Ende eines so langen
 *    Textes doch noch eine Kopfzeile, gilt für sie, was für jede Kopfzeile mitten in der Antwort gilt
 *    (nächster Absatz): Sie wird nicht gesprochen, entscheidet aber auch nichts mehr.
 *
 * Kopfzeile mitten in der Antwort, siehe [body]:
 *  - Gemessen ist eine Antwort, die sich selbst verbessert: Kopfzeile, ein Satz, eine Leerzeile, "Korrektur
 *    für die Aussprache:", eine Leerzeile, noch einmal "SAY kurz" und der Satz in besserer Fassung. Früher
 *    war alles davon zu hören, auch "SAY kurz".
 *  - Eine Zeile im Sprechtext, die für sich eine Kopfzeile in genauer Schreibweise ist, wird nicht
 *    gesprochen: SAY in Großbuchstaben, dahinter eine bekannte Länge oder nichts; SKIP in Großbuchstaben
 *    als ganzes Wort, dahinter nichts oder einer der bekannten Gründe. Sie entscheidet nichts mehr, denn
 *    was schon zu hören war, lässt sich nicht zurücknehmen. Irrtum: Eine Antwortzeile, die genau so
 *    aussieht ("SAY", "SKIP medien"), ginge verloren; so schreibt niemand einen Satz.
 *  - Mit ihr fällt die kurze Ankündigung weg, die direkt vor ihr einen neuen Absatz begonnen hat und mit
 *    einem Doppelpunkt endet ("Korrektur für die Aussprache:"). Dafür wartet eine solche Zeile, bis die
 *    nächste Zeile mit Inhalt zeigt, was sie ankündigt. Gewartet wird nur am Anfang eines Absatzes hinter
 *    schon gesprochenem Text und nur bis zum ersten Satzzeichen oder [MAX_HELD_LINE] Zeichen. Die
 *    Sprachausgabe beginnt ohnehin erst am Satzende, zu hören ist davon also keine Verzögerung. Irrtum:
 *    Folgt keine Kopfzeile, wird die Ankündigung gesprochen wie jede andere Zeile ("Die Zutaten:").
 *
 * Sonderzeilen, siehe [probe]:
 *  - Am Zeilenanfang großzügig: Einrückung, Aufzählungs- und Hervorhebungszeichen davor und direkt
 *    dahinter ("**MEMO**: ..."), beliebige Groß- und Kleinschreibung, Leerzeichen vor dem Doppelpunkt.
 *    Ohne Doppelpunkt gilt nur "MUTE 10" in Großbuchstaben; "Mute 10 Kanäle auf einmal" ist ein Satz.
 *  - Mitten in der Zeile nur genau "MEMO:" oder "MUTE:" in Großbuchstaben, und nur hinter einem
 *    Satzende ("Notiert, Sir. MEMO: ..."). So bleiben "ein Memo: ..." und "Die Taste heißt MUTE: damit
 *    schaltest du stumm." Sprechtext. Die Anweisung reicht dann bis zum Zeilenende.
 *  - Irrtum am Zeilenanfang: Im Zweifel ist es eine Anweisung. Ein vorgelesenes "MEMO: Kennzeichen ..."
 *    ohne gespeicherte Notiz bemerkt der Träger sofort. Der Preis: Eine Antwortzeile, die wirklich mit
 *    "Memo:" oder "Mute:" beginnt, wird nicht gesprochen, sondern als Notiz gespeichert oder schaltet
 *    eine halbe Stunde stumm. Solche Zeilen schreibt das Modell für die Sprachausgabe praktisch nie.
 *  - Irrtum mitten in der Zeile: Im Zweifel ist es Sprechtext. Dort stehen die Wörter eher in einem
 *    Satz über die Taste oder das Protokoll, und eine Notiz aus einem halben Satz oder eine Ruhe, um die
 *    niemand gebeten hat, wiegt schwerer als ein vorgelesenes "MEMO:".
 *
 * Nach der Kopfzeile fließt der Sprechtext ohne Aufenthalt weiter: Zurückgehalten werden immer nur die
 * wenigen Zeichen, die noch der Anfang einer Sonderzeile werden können (ein großes M am Anfang einer
 * Zeile oder eines Satzes, Zeichen vor dem ersten Wort einer Zeile). Im gewöhnlichen Text wartet
 * höchstens ein Zeichen, im ungünstigsten Fall sind es sechzehn (gemessen in ReplyProtocolTest).
 *
 * Ob eine Anweisung wirken darf, entscheidet nicht diese Klasse, sondern die Pipeline an der Stimme.
 * Dieselben Regeln stehen noch einmal in `tools/eval_score.py`; `EvalParserCheck` hält beide zusammen.
 */
class ReplyParser(
    private val onDecision: (Decision) -> Unit,
    private val onSpeech: (String) -> Unit,
    private val onDirective: (Directive) -> Unit,
) {
    private enum class State { HEADER, HEADER_REST, BODY, DIRECTIVE, DONE }
    private enum class Probe { NO, MAYBE, YES }

    private var state = State.HEADER

    /**
     * Zeichen, über die noch nicht entschieden ist: die laufende Zeile, solange die Kopfzeile aussteht,
     * danach der mögliche Anfang einer Sonderzeile oder die Sonderzeile selbst.
     */
    private val held = StringBuilder()

    /** Zeilen vor der Kopfzeile, die selbst keine waren. Sie warten, ob die Kopfzeile noch kommt. */
    private val parked = StringBuilder()
    /** In [parked] steht schon Text mit Inhalt: Ab jetzt zählt eine Kopfzeile nur in genauer Schreibweise. */
    private var late = false
    /** Die laufende Zeile ist bereits als "keine SAY-Kopfzeile" erkannt; spart die Prüfung bei jedem weiteren Zeichen. */
    private var notSay = false
    /** Wo in [held] die Kopfzeile zu Ende war, solange noch offen ist, was ihr Rest bedeutet; -1 = die Zeile läuft noch. */
    private var restEnd = -1
    /**
     * Der Text, der vor einer verspäteten SAY-Kopfzeile stand; null, wenn es keine verspätete Kopfzeile gab.
     * Er wird aufgehoben, bis am Ende des Stroms feststeht, ob hinter der Kopfzeile noch die Antwort kam
     * (dann war er eine Begründung) oder nichts mehr (dann war er selbst die Antwort, siehe [finish]).
     */
    private var before: String? = null
    /** Seit der Kopfzeile ist schon etwas Sprechbares hinausgegangen: mindestens ein Buchstabe oder eine Ziffer. */
    private var voiced = false
    /**
     * Der Grund aus einer ersten Zeile, die mit SKIP begann, aber ein ganzer Satz war; null, wenn es keine gab.
     * Kommt danach keine Kopfzeile in genauer Schreibweise mehr, wird mit diesem Grund geschwiegen.
     */
    private var skipFallback: String? = null

    // Der Sprechtext läuft zeilenweise durch eine Vorstufe ([body]), bevor er Zeichen für Zeichen gelesen wird:
    /** Die Zeichen der laufenden Zeile, solange offen ist, ob sie eine Kopfzeile oder eine Ankündigung wird. */
    private val line = StringBuilder()
    /** Eine fertige Ankündigung samt den Leerzeilen dahinter; sie wartet auf die nächste Zeile mit Inhalt. */
    private val announcement = StringBuilder()
    /** Über die laufende Zeile ist entschieden: gewöhnlicher Sprechtext, der ohne Aufenthalt weiterfließt. */
    private var flowing = false
    /** Die Zeile davor war leer: Mit der laufenden beginnt ein neuer Absatz. */
    private var afterBlank = false
    /** Die laufende Zeile besteht bisher nur aus Leerraum. */
    private var blankLine = true

    /** Von der laufenden Sprechtext-Zeile ist noch kein Zeichen verarbeitet. */
    private var lineStart = true
    /** [held] begann am Zeilenanfang (dort gelten die großzügigen Regeln). */
    private var heldFromLineStart = false
    /** Das letzte Zeichen, das als Sprechtext hinausging: Eine Sonderzeile beginnt nur an einem Wortanfang. */
    private var last = '\n'
    /**
     * Das letzte Zeichen der laufenden Zeile, das als Sprechtext hinausging und Buchstabe, Ziffer oder
     * Satzzeichen (. ! ? …) war; '\n' = in dieser Zeile noch keines. Daran ist abzulesen, ob gerade ein Satz
     * zu Ende ist: Mitten in der Zeile beginnt eine Sonderzeile nur dort.
     */
    private var lastSolid = '\n'
    /** Die laufende Sonderzeile begann mitten in einer Zeile, vor ihr stand also Sprechtext. */
    private var directiveMidLine = false

    var decision: Decision? = null
        private set

    fun feed(delta: String) {
        for (c in delta) step(c)
    }

    /** Am Ende des Stroms aufrufen: verarbeitet, was noch zurückgehalten wurde. */
    fun finish() {
        // Zuerst die Kopfzeile abschließen. Dabei kann noch Sprechtext nachkommen (Text hinter SAY in
        // derselben Zeile, eine Antwort ganz ohne Kopfzeile), der danach wie üblich zu Ende gelesen wird.
        if (state == State.HEADER) headerLineEnd(endOfStream = true)
        // Die Antwort endet mit dem Rest der Kopfzeile: Dann war er der Sprechtext, sofern er etwas
        // Sprechbares enthält. Sonst bleibt nur, was nach der Kopfzeile noch an Leerzeilen kam.
        if (state == State.HEADER_REST && held.isNotEmpty()) {
            speakHeaderRest(
                when {
                    held.any { it.isLetterOrDigit() } -> held.toString()
                    restEnd >= 0 -> held.substring(restEnd + 1)
                    else -> ""
                },
            )
        }
        endOfBody()
        // Verspätete Kopfzeile, und hinter ihr kam nichts Sprechbares mehr: Dann war der Text davor die
        // Antwort (siehe Klassenkommentar). Er geht jetzt denselben Weg wie jeder Sprechtext, also auch mit
        // den Regeln für eine Sonderzeile mitten in der Zeile ("Notiert, Sir. MEMO: ..."). Zeilen, die als
        // Ganzes eine Sonderzeile waren, sind schon ausgeführt ([directivesBeforeHeader]) und bleiben weg:
        // Sonst stünde dieselbe Notiz zweimal im Gedächtnis.
        val answer = before
        if (answer != null && !voiced) {
            startBody()
            feed(answer.split('\n').filterNot(::isDirectiveLine).joinToString("\n"))
            endOfBody()
        }
        state = State.DONE
    }

    /** Am Ende des Stroms: Was vom Sprechtext oder von einer Sonderzeile noch zurückgehalten ist, wird entschieden. */
    private fun endOfBody() {
        // Zuerst die Vorstufe: Eine Kopfzeile ganz am Schluss fällt weg, samt ihrer Ankündigung. Alles andere,
        // was dort noch wartet, ist Sprechtext. (Eine Schleife, weil hinter einer freigegebenen Ankündigung die
        // letzte Zeile neu beurteilt wird und dabei noch einmal warten kann.)
        while (state == State.BODY && !flowing && (line.isNotEmpty() || announcement.isNotEmpty())) {
            if (isHeaderLine(line)) {
                line.setLength(0)
                announcement.setLength(0)
            } else flow()
        }
        when (state) {
            State.BODY -> endOfSpeechLine()
            State.DIRECTIVE -> directive(held.toString())
            else -> {}
        }
        held.setLength(0)
    }

    private fun step(c: Char) {
        when (state) {
            State.HEADER -> if (c == '\n') headerLineEnd(endOfStream = false) else {
                held.append(c)
                if (!notSay && sayHeader(lineComplete = false)) return
                // Die Obergrenze gilt auch mitten in der Zeile: Ein langer Absatz ohne Zeilenumbruch soll
                // nicht bis zum Ende des Stroms warten. Eine Zeile, die noch eine Kopfzeile werden kann,
                // wird aber zu Ende gelesen.
                if (notSay && parked.length + held.length > MAX_PARKED && !mayBeSkip()) noHeader(parked.toString() + held)
            }
            State.HEADER_REST -> headerRest(c)
            State.BODY -> body(c)
            State.DIRECTIVE -> if (c == '\n') {
                directive(held.toString())
                held.setLength(0)
                // Stand vor der Anweisung Sprechtext in der Zeile, endet mit ihr auch dessen Zeile.
                if (directiveMidLine) speak("\n")
                lineStart = true
                state = State.BODY
                nextLine(blank = false)
            } else held.append(c)
            State.DONE -> {}
        }
    }

    // ------------------------------------------------------------------------------------------
    // Kopfzeile

    /**
     * Ist die laufende Zeile eine SAY-Kopfzeile? Gefragt wird nach jedem Zeichen: Sobald das Wort hinter
     * SAY vollständig ist, steht die Entscheidung fest und wird gemeldet, ohne auf das Zeilenende zu
     * warten. Was die Zeile danach noch enthält, klärt [headerRest].
     *
     * @return true, wenn die Zeile als Kopfzeile erkannt und die Entscheidung gemeldet wurde.
     */
    private fun sayHeader(lineComplete: Boolean): Boolean {
        val s = held
        var i = 0
        while (i < s.length && !s[i].isLetterOrDigit()) i++
        val have = minOf(s.length - i, 3)
        if (!same(s, i, "SAY", have, foldCase = !late)) return no()
        if (have < 3) return if (lineComplete) no() else false
        var j = i + 3
        if (j < s.length && s[j].isLetterOrDigit()) return no()            // "SAYONARA"
        while (j < s.length && !s[j].isLetterOrDigit()) j++                // Leerzeichen, Doppelpunkt, Sternchen ...
        var k = j
        while (k < s.length && s[k].isLetterOrDigit()) k++
        if (k == s.length && !lineComplete) return false                   // das Wort hinter SAY kann noch weitergehen
        val length = LENGTHS.firstOrNull { it.length == k - j && same(s, j, it, it.length, foldCase = true) }
        val upper = same(s, i, "SAY", 3, foldCase = false)
        // Was nach der Entscheidung von der Zeile übrig ist: bei bekannter Länge alles hinter ihr, sonst
        // alles hinter SAY. Als Länge wird dann nichts gemeldet, damit in der Anzeige kein halber Satz
        // steht und der Längendeckel seinen Standardwert nimmt.
        val rest = when {
            length != null -> s.substring(k)
            j == k -> ""                          // nur "SAY" in der Zeile
            upper && !late -> s.substring(i + 3)
            else -> return no()
        }
        decide(Decision.Say(length ?: ""))
        // Erst die Entscheidung, dann die Sonderzeilen: Die Pipeline nimmt Anweisungen nur an, wenn gesprochen wird.
        if (late) {
            directivesBeforeHeader()
            // Ob der Text davor Begründung war oder die Antwort selbst, zeigt erst das Ende des Stroms. Begann
            // er mit SKIP, war er ein Kommentar zur Entscheidung und ist auf keinen Fall die Antwort.
            before = if (skipFallback == null) parked.toString() else null
        }
        held.setLength(0)
        restEnd = -1
        state = State.HEADER_REST
        feed(rest)
        return true
    }

    /**
     * Die Entscheidung SAY ist gefallen, die Kopfzeile aber noch nicht zu Ende. [held] sammelt ihren Rest,
     * bis feststeht, ob er Sprechtext ist oder ein Zusatz zur Kopfzeile (siehe Klassenkommentar).
     */
    private fun headerRest(c: Char) {
        when {
            // Die Kopfzeile ist zu Ende, ihr Rest war kurz und ohne Satzzeichen. Kommt jetzt noch Text,
            // war der Rest ein Zusatz: Gesprochen wird erst, was nach der Kopfzeile steht.
            restEnd >= 0 -> {
                held.append(c)
                if (c != '\n' && !isBlank(c)) {
                    val text = held.substring(restEnd + 1)
                    startBody()
                    feed(text)
                }
            }
            c == '\n' -> if (held.isEmpty()) startBody() else { restEnd = held.length; held.append(c) }
            // Zwischen Länge und Text: Doppelpunkt, Sternchen und Ähnliches überlesen.
            held.isEmpty() && c in AFTER_LENGTH_GAP -> {}
            else -> {
                held.append(c)
                // Erst etwas Sprechbares macht den Rest zur Antwort: "SAY kurz (!)" bleibt ein Zusatz.
                if ((c in SENTENCE_MARKS || held.length > MAX_LABEL) && held.any { it.isLetterOrDigit() }) {
                    speakHeaderRest(held.toString())
                }
            }
        }
    }

    private fun speakHeaderRest(text: String) {
        startBody()
        feed(text)
    }

    private fun no(): Boolean {
        notSay = true
        return false
    }

    private fun headerLineEnd(endOfStream: Boolean) {
        if (!notSay && sayHeader(lineComplete = true)) {
            if (!endOfStream) step('\n')
            return
        }
        notSay = false
        val line = held.toString()
        held.setLength(0)
        val core = line.trim { !it.isLetterOrDigit() }
        val skip = if (late) same(core, 0, "SKIP", 4, foldCase = false) && !(core.length > 4 && core[4].isLetterOrDigit())
        else same(core, 0, "SKIP", 4, foldCase = true)
        val reason = if (skip) core.drop(4).trim { !it.isLetterOrDigit() }.lowercase() else ""
        when {
            // Verspätet zählt SKIP nur in genauer Schreibweise und gilt dann immer. In der ersten Zeile gilt es
            // sofort, wenn dahinter höchstens ein Grund steht.
            skip && (late || words(reason) <= MAX_REASON_WORDS) -> {
                decide(Decision.Skip(reason))
                state = State.DONE
                return
            }
            // Leerzeile oder bloße Verzierung ("```") vor dem ersten Inhalt: weiter auf die Kopfzeile warten.
            core.isEmpty() && !late -> {}
            else -> {
                // Erste Zeile mit SKIP und einem ganzen Satz dahinter: vorgemerkt, falls keine Kopfzeile mehr kommt.
                if (skip) skipFallback = reason
                parked.append(line)
                if (!endOfStream) parked.append('\n')
                if (core.isNotEmpty()) late = true
            }
        }
        if (late && (endOfStream || parked.length > MAX_PARKED)) {
            noHeader(parked.toString())
        } else if (endOfStream) {
            decide(Decision.Skip(""))
            state = State.DONE
        }
    }

    /**
     * Bis zum Schluss kam keine Kopfzeile, oder es steht schon mehr Text da, als je eine Begründung lang
     * wäre: Das Modell hat direkt geantwortet, [text] ist der Anfang seiner Antwort. Die Pipeline spricht
     * sie. Sieht ihr Anfang aber nach einem Kommentar zur Entscheidung aus, meldet das die Entscheidung
     * mit, und die Pipeline lässt sie dann nur nach einer direkten Anrede durch.
     */
    /**
     * Es kommt keine Kopfzeile mehr (Ende des Stroms oder Obergrenze). Begann der Text mit SKIP und einem
     * ganzen Satz, bleibt es beim Schweigen; sonst ist er eine Antwort ohne Kopfzeile.
     */
    private fun noHeader(text: String) {
        val reason = skipFallback
        if (reason == null) return offProtocol(text)
        decide(Decision.Skip(reason))
        held.setLength(0)
        state = State.DONE
    }

    private fun offProtocol(text: String) {
        decide(Decision.Say("", offProtocol = true, commentary = Headerless.isCommentary(text)))
        startBody()
        feed(text)
    }

    /**
     * Kann die laufende Zeile noch eine SKIP-Kopfzeile werden? Dieselbe Regel wie am Zeilenende in
     * [headerLineEnd], nur für eine Zeile, die noch nicht zu Ende ist.
     */
    private fun mayBeSkip(): Boolean {
        var i = 0
        while (i < held.length && !held[i].isLetterOrDigit()) i++
        if (!same(held, i, "SKIP", minOf(held.length - i, 4), foldCase = !late)) return false
        return !(late && held.length > i + 4 && held[i + 4].isLetterOrDigit())
    }

    /**
     * Vor einer verspäteten SAY-Kopfzeile stand anderer Text. Gesprochen wird er nicht. Zeilen darin, die
     * als Ganzes eine Sonderzeile sind, werden aber ausgeführt (siehe Klassenkommentar). Mitten in einer
     * Begründung zählt ein "MEMO:" dagegen nicht: Dort ist eher von der Anweisung die Rede.
     */
    private fun directivesBeforeHeader() {
        for (line in parked.split('\n')) if (isDirectiveLine(line)) directive(line)
    }

    /** Ist [line] von ihrem ersten Zeichen an eine Sonderzeile? Fragt [probe] so, wie es der Sprechtext Zeichen für Zeichen tut. */
    private fun isDirectiveLine(line: String): Boolean {
        for (n in 1..line.length) when (probe(line.subSequence(0, n), atLineStart = true)) {
            Probe.YES -> return true
            Probe.NO -> return false
            Probe.MAYBE -> {}
        }
        return isBareKeyword(line)
    }

    private fun startBody() {
        parked.setLength(0)
        late = false
        notSay = false
        held.setLength(0)
        restEnd = -1
        lineStart = true
        last = '\n'
        lastSolid = '\n'
        line.setLength(0)
        announcement.setLength(0)
        flowing = false
        afterBlank = false
        blankLine = true
        state = State.BODY
    }

    // ------------------------------------------------------------------------------------------
    // Kopfzeile mitten in der Antwort

    /**
     * Die Vorstufe des Sprechtexts (siehe Klassenkommentar, "Kopfzeile mitten in der Antwort").
     *
     * Fast jede Zeile ist nach ihrem ersten Zeichen entschieden und fließt dann Zeichen für Zeichen weiter
     * nach [speech]. Zurückgehalten wird eine Zeile nur, solange sie noch eine Kopfzeile in genauer
     * Schreibweise werden kann ("S", "SA", "SAY", "SAY k" ...) oder, am Anfang eines Absatzes, eine kurze
     * Ankündigung. Am Zeilenende steht fest, was sie war.
     */
    private fun body(c: Char) {
        if (flowing) {
            val blank = blankLine
            if (c != '\n' && !isBlank(c)) blankLine = false
            speech(c)
            if (c == '\n') nextLine(blank)
            return
        }
        if (c == '\n') return endOfHeldLine()
        line.append(c)
        if (!isBlank(c)) blankLine = false
        if (!mayBeHeaderLine(line) && !mayBeAnnouncement(line)) flow()
    }

    /** Das Zeilenende ist da, und über die Zeile war noch nicht entschieden. */
    private fun endOfHeldLine() {
        when {
            // Leerzeilen hinter einer Ankündigung warten mit ihr; sonst sind sie gewöhnlicher Sprechtext.
            blankLine && announcement.isNotEmpty() -> {
                announcement.append(line).append('\n')
                line.setLength(0)
            }
            blankLine -> {
                flow()
                step('\n')
            }
            // Eine Kopfzeile mitten in der Antwort: Sie fällt weg, und mit ihr die Ankündigung direkt davor.
            isHeaderLine(line) -> {
                line.setLength(0)
                announcement.setLength(0)
                blankLine = true
            }
            isAnnouncement(line) -> {
                announcement.append(line).append('\n')
                line.setLength(0)
                blankLine = true
            }
            else -> {
                flow()
                step('\n')
            }
        }
    }

    /**
     * Was bisher zurückgehalten wurde, ist gewöhnlicher Sprechtext: Es geht jetzt denselben Weg wie jedes
     * andere Zeichen. Eine wartende Ankündigung geht voran; die Zeilen dahinter werden dabei neu beurteilt.
     */
    private fun flow() {
        val text = announcement.toString() + line
        announcement.setLength(0)
        line.setLength(0)
        flowing = true
        for (ch in text) step(ch)
    }

    /** Eine Zeile des Sprechtexts ist zu Ende; [blank] = sie enthielt nur Leerraum. */
    private fun nextLine(blank: Boolean) {
        flowing = false
        afterBlank = blank
        blankLine = true
    }

    private fun mayBeHeaderLine(s: CharSequence) = headerShape(s, complete = false)

    /** Ist die ganze Zeile [s] eine Kopfzeile in genauer Schreibweise? */
    private fun isHeaderLine(s: CharSequence) = headerShape(s, complete = true)

    /**
     * Die genaue Schreibweise einer Kopfzeile: bis zu [MAX_LEAD] Zeichen Verzierung, dann SAY in
     * Großbuchstaben und nichts oder eine bekannte Länge, oder SKIP in Großbuchstaben als ganzes Wort und
     * nichts oder ein bekannter Grund. Dahinter darf nur noch Verzierung stehen.
     *
     * @param complete true = [s] ist die ganze Zeile. false = sie läuft noch; gefragt ist dann, ob aus dem
     *                 Anfang noch eine Kopfzeile werden kann.
     */
    private fun headerShape(s: CharSequence, complete: Boolean): Boolean {
        var i = 0
        while (i < s.length && !s[i].isLetterOrDigit()) i++
        if (i > MAX_LEAD) return false
        val n = s.length - i
        if (n == 0) return !complete
        if (same(s, i, "SAY", minOf(n, 3), foldCase = false)) {
            if (n < 3) return !complete
            var j = i + 3
            if (j < s.length && s[j].isLetterOrDigit()) return false           // "SAYONARA"
            while (j < s.length && !s[j].isLetterOrDigit()) j++
            if (j == s.length) return true                                     // nur "SAY"
            var k = j
            while (k < s.length && s[k].isLetterOrDigit()) k++
            val wordDone = complete || k < s.length
            val fits = LENGTHS.any {
                if (wordDone) it.length == k - j && same(s, j, it, it.length, foldCase = true)
                else k - j <= it.length && same(s, j, it, k - j, foldCase = true)
            }
            return fits && (k until s.length).none { s[it].isLetterOrDigit() }
        }
        if (same(s, i, "SKIP", minOf(n, 4), foldCase = false)) {
            if (n < 4) return !complete
            if (n > 4 && s[i + 4].isLetterOrDigit()) return false              // "SKIPPER"
            val rest = s.subSequence(i + 4, s.length)
            // Solange die Zeile läuft, genügt, dass der Grund noch kurz ist; am Ende muss er bekannt sein.
            return if (complete) rest.none { it.isLetterOrDigit() } || Headerless.isReason(rest.trim { !it.isLetterOrDigit() }.toString())
            else s.length <= MAX_HELD_LINE && words(rest) <= MAX_REASON_WORDS
        }
        return false
    }

    /** Hier könnte eine Ankündigung stehen: am Anfang eines Absatzes, hinter schon gesprochenem Text, und es wartet noch keine. */
    private fun announcementPossible() = afterBlank && voiced && announcement.isEmpty()

    private fun mayBeAnnouncement(s: CharSequence) =
        announcementPossible() && s.length <= MAX_HELD_LINE && s.none { it in SENTENCE_MARKS }

    /**
     * Ist die ganze Zeile [s] eine kurze Ankündigung, die mit einem Doppelpunkt endet? Dieselbe Regel wie
     * für die erste Zeile einer Antwort ohne Kopfzeile ([Headerless.isCommentary], Regel 3). Eine Sonderzeile
     * ("MUTE:") ist keine Ankündigung.
     */
    private fun isAnnouncement(s: CharSequence): Boolean {
        if (!mayBeAnnouncement(s)) return false
        val label = s.trim { !it.isLetterOrDigit() && it != ':' }
        return label.length in 2..Headerless.MAX_ANNOUNCEMENT && label.endsWith(':') && !isDirectiveLine(s.toString())
    }

    // ------------------------------------------------------------------------------------------
    // Sprechtext und Sonderzeilen

    private fun speech(c: Char) {
        when {
            c == '\n' -> {
                // Eine Zeile, die sich am Ende als Anweisung herausstellt, hinterlässt auch keinen Zeilenumbruch.
                if (endOfSpeechLine()) speak("\n")
                lineStart = true
                return
            }
            held.isNotEmpty() -> {
                held.append(c)
                when (probe(held, heldFromLineStart)) {
                    Probe.YES -> { directiveMidLine = !heldFromLineStart; state = State.DIRECTIVE }
                    // Noch unklar, ob das eine Anweisung wird: zurückhalten statt vorlesen.
                    Probe.MAYBE -> {}
                    Probe.NO -> release()
                }
            }
            (lineStart || afterSentenceEnd()) && probe(c.toString(), lineStart) != Probe.NO -> {
                held.append(c)
                heldFromLineStart = lineStart
            }
            else -> speak(c.toString())
        }
        lineStart = false
    }

    /**
     * Am Ende einer Sprechtext-Zeile (Zeilenumbruch oder Stromende): Was noch zurückgehalten ist, wird entschieden.
     * @return false, wenn die Zeile eine Anweisung war und nichts von ihr gesprochen wird.
     */
    private fun endOfSpeechLine(): Boolean {
        if (held.isEmpty()) return true
        // Besteht die Zeile nur aus dem Schlüsselwort in Großbuchstaben, war eine Anweisung gemeint.
        // Vorgelesen wird sie nicht, und "MUTE" allein schaltet für die Standarddauer stumm.
        val bare = heldFromLineStart && isBareKeyword(held)
        if (bare) directive(held.toString()) else speak(held.toString())
        held.setLength(0)
        return !bare
    }

    private fun release() {
        speak(held.toString())
        held.setLength(0)
    }

    /**
     * Mitten in der Zeile: Steht die Stelle an einem Wortanfang hinter einem Satzende? Nur dort kann eine
     * Sonderzeile beginnen. Als Satzende zählen . ! ? … und der Fall, dass in der Zeile bisher kein Wort stand.
     * Was sonst dazwischen steht (Leerzeichen, Hervorhebung, Anführungszeichen, Klammern), zählt nicht mit:
     * In "**Notiert.** MEMO: ..." steht MEMO hinter einem Satzende, in "Das Wort heißt **MEMO**: ..." nicht.
     */
    private fun afterSentenceEnd(): Boolean = !last.isLetterOrDigit() && (lastSolid == '\n' || lastSolid in SENTENCE_MARKS)

    private fun speak(text: String) {
        for (c in text) {
            if (c.isLetterOrDigit()) voiced = true
            if (c == '\n') lastSolid = '\n' else if (c.isLetterOrDigit() || c in SENTENCE_MARKS) lastSolid = c
        }
        last = text[text.length - 1]
        onSpeech(text)
    }

    /**
     * Kann aus [s] noch eine Sonderzeile werden (MAYBE), ist es schon eine (YES) oder sicher keine (NO)?
     * [s] ist der Anfang einer Zeile ([atLineStart]) oder eines Wortes mitten in der Zeile.
     *
     * Aufbau einer Sonderzeile: Vorspann, Schlüsselwort, Doppelpunkt.
     *  - Am Zeilenanfang darf der Vorspann aus bis zu [MAX_LEAD] beliebigen Zeichen bestehen, die weder
     *    Buchstabe noch Ziffer sind ("  - **"), das Wort darf beliebig groß oder klein geschrieben sein,
     *    und vor dem Doppelpunkt dürfen bis zu [MAX_GAP] Leerzeichen stehen. Genau "MUTE" in
     *    Großbuchstaben gilt auch ohne Doppelpunkt, wenn nach Leerzeichen eine Ziffer folgt.
     *  - Mitten in der Zeile sind als Vorspann nur Hervorhebungszeichen erlaubt, und es muss genau
     *    "MEMO:" oder "MUTE:" dastehen.
     *  - Hervorhebung schließt oft schon vor dem Doppelpunkt ("**MEMO**: ..."): Direkt hinter dem Wort
     *    dürfen deshalb bis zu [MAX_EMPHASIS] Hervorhebungszeichen stehen.
     * Die Obergrenzen sorgen dafür, dass gewöhnlicher Text nach wenigen Zeichen freigegeben wird.
     */
    private fun probe(s: CharSequence, atLineStart: Boolean): Probe {
        val maxLead = if (atLineStart) MAX_LEAD else MAX_EMPHASIS
        var i = 0
        while (i < s.length && i < maxLead && (if (atLineStart) !s[i].isLetterOrDigit() else s[i] in EMPHASIS)) i++
        if (i == s.length) return Probe.MAYBE
        val word = s.length - i
        for (keyword in KEYWORDS) {
            if (!same(s, i, keyword, minOf(word, keyword.length), foldCase = atLineStart)) continue
            if (word <= keyword.length) return Probe.MAYBE
            var j = i + keyword.length
            var closing = 0
            while (j < s.length && closing < MAX_EMPHASIS && s[j] in EMPHASIS) { j++; closing++ }
            var gap = 0
            if (atLineStart) while (j < s.length && gap < MAX_GAP && isBlank(s[j])) { j++; gap++ }
            return when {
                j == s.length -> Probe.MAYBE
                s[j] == ':' -> Probe.YES
                // Ohne Doppelpunkt nur in genauer Schreibweise: "Mute 10 Kanäle auf einmal" ist ein Satz.
                gap > 0 && s[j] in '0'..'9' && same(s, i, "MUTE", 4, foldCase = false) -> Probe.YES
                else -> Probe.NO
            }
        }
        return Probe.NO
    }

    /** Die ganze Zeile ist nur das Schlüsselwort in Großbuchstaben, höchstens mit Zeichen drumherum ("MUTE", "- MEMO "). */
    private fun isBareKeyword(s: CharSequence): Boolean {
        var i = 0
        while (i < s.length && !s[i].isLetterOrDigit()) i++
        if (KEYWORDS.none { same(s, i, it, it.length, foldCase = false) }) return false
        return (i + 4 until s.length).none { s[it].isLetterOrDigit() }
    }

    /** Führt eine vollständige Sonderzeile aus. [line] beginnt mit dem Vorspann, so wie [probe] ihn zugelassen hat. */
    private fun directive(line: String) {
        var i = 0
        while (i < line.length && !line[i].isLetterOrDigit()) i++
        val keyword = KEYWORDS.firstOrNull { same(line, i, it, it.length, foldCase = true) } ?: return
        // Hinter dem Wort: Hervorhebung und Leerzeichen, der Doppelpunkt, dann der Inhalt. Hervorhebungszeichen
        // am Rand ("**MUTE: 10**", "**MEMO**: ...") gehören nicht zum Inhalt; ein Punkt am Ende einer Notiz schon.
        val payload = line.substring(i + keyword.length).trimStart { isBlank(it) || it in EMPHASIS }.removePrefix(":")
            .trim { isBlank(it) || it in EMPHASIS }
        if (keyword == "MEMO") {
            if (payload.isNotEmpty()) onDirective(Directive.Memo(payload))
        } else {
            onDirective(Directive.Mute(MuteDuration.minutes(payload)))
        }
    }

    private fun decide(d: Decision) {
        decision = d
        onDecision(d)
    }

    private companion object {
        val KEYWORDS = listOf("MEMO", "MUTE")
        val LENGTHS = listOf("kurz", "mittel", "lang")

        /** Hervorhebung aus Markdown; in Sprechtext kommen diese Zeichen sonst nicht vor. */
        const val EMPHASIS = "*_`"

        /**
         * Was zwischen der Länge und dem Rest der Kopfzeile überlesen wird ("SAY kurz: ...", "SAY kurz!").
         * Bewusst ohne Minuszeichen, Gedankenstrich und öffnende Klammer: Damit kann eine Antwort beginnen.
         */
        const val AFTER_LENGTH_GAP = " \t\r\u00A0:*_`#>)]}.,;!?…\"'“”„«»"

        /** Ein Satzzeichen im Rest der Kopfzeile zeigt: Das ist schon die Antwort, kein Zusatz zur Kopfzeile. */
        const val SENTENCE_MARKS = ".!?…"

        /** Länger ist kein Zusatz zur Kopfzeile ("sehr kurz", "bis mittel", "(direkte Frage)"). */
        const val MAX_LABEL = 40

        /**
         * So viel Text darf vor einer verspäteten Kopfzeile stehen (etwa achtzig Wörter). Eine Begründung
         * ist kürzer; was länger ist, ist die Antwort selbst, und auf deren Ende zu warten, hielte die
         * ganze Pipeline auf.
         */
        const val MAX_PARKED = 500

        /** Hinter SKIP steht höchstens ein Grund: ein Wort, oder zwei wie in "keine frage". Mehr ist ein Satz. */
        const val MAX_REASON_WORDS = 2

        /** So lang darf eine Zeile im Sprechtext sein, die noch als Ankündigung oder Kopfzeile zurückgehalten wird. */
        const val MAX_HELD_LINE = Headerless.MAX_ANNOUNCEMENT + 6

        const val MAX_LEAD = 6
        /** So viele Hervorhebungszeichen dürfen mitten in der Zeile vor dem Schlüsselwort und überall direkt hinter ihm stehen. */
        const val MAX_EMPHASIS = 4
        const val MAX_GAP = 2

        fun isBlank(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\u00A0'

        /**
         * Stimmen die ersten [n] Zeichen von [word] mit [s] ab der Stelle [from] überein?
         * [foldCase] macht nur die Buchstaben a bis z groß. Das genügt für die Protokollwörter und
         * verhält sich auf jedem Gerät und in `tools/eval_score.py` gleich.
         */
        fun same(s: CharSequence, from: Int, word: String, n: Int, foldCase: Boolean): Boolean {
            if (from + n > s.length) return false
            for (k in 0 until n) {
                val a = s[from + k]
                val b = word[k]
                if (a != b && !(foldCase && upper(a) == upper(b))) return false
            }
            return true
        }

        fun upper(c: Char) = if (c in 'a'..'z') c - 32 else c

        /** Wie viele Wörter (Folgen aus Buchstaben und Ziffern) stehen in [s]? */
        fun words(s: CharSequence): Int {
            var n = 0
            var inWord = false
            for (c in s) {
                val alnum = c.isLetterOrDigit()
                if (alnum && !inWord) n++
                inWord = alnum
            }
            return n
        }
    }
}

/**
 * Eine Antwort ohne Kopfzeile: Ist das wirklich eine Antwort, oder redet das Modell ÜBER seine Entscheidung?
 *
 * Früher wurde jede Antwort ohne Kopfzeile verworfen, wenn der Assistent nicht direkt angesprochen war. Die
 * Sorge dahinter: Das Modell könnte statt einer Antwort seine Entscheidung kommentieren, und der Kommentar
 * landete im Ohr. Gemessen hat sich die Sorge nicht bestätigt, die Regel aber als teuer erwiesen. In 1480
 * Antworten des echten Modells (185 Fälle, vier Fassungen der Mitschrift, zwei Fassungen der Anweisungen)
 * fehlte 31-mal die Kopfzeile, und alle 31 waren echte Antworten; 29 davon waren erwünscht.
 *
 * Diese Prüfung ist deshalb aus denselben 1480 Antworten abgeleitet und nicht aus ausgedachten Sätzen. Sie
 * fragt, wie das Modell schreibt, wenn es über Entscheidung oder Protokoll spricht. Dafür gibt es dort
 * genau zwei Quellen:
 *  - Die Gründe hinter SKIP. In 711 von 712 Fällen ist das ein einzelnes Stichwort: die neun aus dem
 *    Prompt, dazu "organisatorisch" und "rhetorisch", die das Modell selbst erfunden hat. Einen
 *    ausformulierten Satz schreibt es dort nie.
 *  - Zeilen, die es vor eine Kopfzeile setzt. Davon gibt es drei. Eine ist eine Antwort (siehe die
 *    verspätete Kopfzeile im [ReplyParser]). Die anderen beiden sind Kommentare:
 *    "SKIP-Entscheidung entfällt, ich antworte." und "Korrektur für die Aussprache:".
 *
 * Daraus die drei Regeln. Jede schaut nur auf die erste Zeile mit Inhalt, denn dort stünde die Kopfzeile,
 * und eine Antwort beginnt laut Prompt mit der Antwort selbst:
 *  1. Die Zeile nennt ein Protokollwort: SKIP oder SAY in Großbuchstaben als ganzes Wort.
 *  2. Die Zeile besteht nur aus einem SKIP-Grund ("hintergrund", "Keine Frage."): die Kopfzeile ohne SKIP.
 *  3. Die Zeile ist eine kurze Ankündigung, die mit einem Doppelpunkt endet: Sie sagt nichts, sie kündigt an.
 *
 * Gegenprobe an denselben Daten: Keine der 31 echten Antworten ohne Kopfzeile wird getroffen, und auch
 * keiner der übrigen 737 Sprechtexte, die mit Kopfzeile kamen (hätte das Modell sie dort vergessen).
 *
 * Wichtig für das Verständnis: Die Regeln 1 und 3 sind aus den beiden Kommentarzeilen abgeleitet, greifen
 * aber nicht in den zwei Antworten, in denen diese Zeilen gemessen wurden. Dort stand eine Kopfzeile, und
 * hierher kommt nur, was ganz ohne Kopfzeile blieb. Die eine Antwort begann mit SKIP, in der anderen war die
 * Kopfzeile längst gelesen, als die Ankündigung kam. Für diese beiden Fälle sorgt der [ReplyParser] selbst
 * (SKIP mit einem ganzen Satz in der ersten Zeile; Kopfzeile mitten in der Antwort). Diese Prüfung fängt
 * dieselben Zeilen für den Fall ab, dass das Modell die Kopfzeile dahinter einmal ganz vergisst.
 *
 * Was die Prüfung NICHT kann: einen frei formulierten Satz über die Entscheidung erkennen ("Das ist nicht
 * an mich gerichtet."). So etwas kam in keiner gemessenen Antwort vor, es gibt also nichts, woraus sich
 * eine Regel ableiten ließe, und geratene Wortlisten träfen echte Antworten. Schreibt das Modell doch
 * einmal so, ist der Satz zu hören, gekürzt auf die Länge von "kurz".
 *
 * Irrtum nach der anderen Seite: Eine echte Antwort, deren erste Zeile eine der drei Regeln erfüllt
 * ("SAY heißt sagen."), gilt als Kommentar. Sie wird dann behandelt wie früher jede Antwort ohne Kopfzeile:
 * ohne direkte Anrede nicht gesprochen. Dieselben Regeln stehen in `tools/eval_score.py`.
 */
internal object Headerless {
    private val PROTOCOL_WORDS = listOf("SKIP", "SAY")

    /** So schreibt das Modell seine Gründe hinter SKIP; "keine-frage" steht hier für jede Trennung der zwei Wörter. */
    private val REASONS = setOf(
        "hintergrund", "medien", "sozial", "beantwortet", "unterricht", "bruchstück", "keine-frage", "unklar",
        "wiederholung", "organisatorisch", "rhetorisch",
    )

    /** Länger ist keine Ankündigung mehr, sondern ein Satz ("Korrektur für die Aussprache:" hat 29 Zeichen). */
    const val MAX_ANNOUNCEMENT = 40

    /**
     * So weit wird in der ersten Zeile nach einem Protokollwort gesucht. Die Grenze hat einen technischen
     * Grund: Muss der [ReplyParser] mitten in einer sehr langen ersten Zeile entscheiden, kennt er nur ihren
     * Anfang. Mit der Grenze kommt er dann zum selben Ergebnis wie das Auswertungs-Skript, das die ganze
     * Antwort vor sich hat.
     */
    private const val LOOK = 200

    /** Sieht [text], eine Antwort ohne Kopfzeile, nach einem Kommentar zur Entscheidung oder zum Protokoll aus? */
    fun isCommentary(text: String): Boolean {
        val line = text.split('\n').firstOrNull { l -> l.any { it.isLetterOrDigit() } } ?: return false
        // Eine so lange Zeile ist weder ein bloßes Stichwort noch eine kurze Ankündigung.
        if (line.length > LOOK) return namesProtocolWord(line.substring(0, LOOK))
        if (namesProtocolWord(line)) return true
        // Verzierung am Rand zählt nicht: "**hintergrund**", "(sozial)".
        if (isReason(line.trim { !it.isLetterOrDigit() })) return true
        val label = line.trim { !it.isLetterOrDigit() && it != ':' }
        return label.length in 2..MAX_ANNOUNCEMENT && label.endsWith(':')
    }

    /** Steht in [s] SKIP oder SAY in Großbuchstaben als ganzes Wort? "SAYONARA" und "Skip-Bo" zählen nicht. */
    private fun namesProtocolWord(s: String): Boolean {
        for (word in PROTOCOL_WORDS) {
            var at = s.indexOf(word)
            while (at >= 0) {
                val end = at + word.length
                val startsWord = at == 0 || !s[at - 1].isLetterOrDigit()
                val endsWord = end == s.length || !s[end].isLetterOrDigit()
                if (startsWord && endsWord) return true
                at = s.indexOf(word, at + 1)
            }
        }
        return false
    }

    /** Ist [core] genau einer der Gründe? Groß- und Kleinschreibung und die Zeichen zwischen zwei Wörtern sind egal. */
    fun isReason(core: String): Boolean {
        if (core.length > MAX_ANNOUNCEMENT) return false
        val key = StringBuilder()
        var gap = false
        for (c in core) {
            if (c.isLetterOrDigit()) {
                if (gap) key.append('-')
                gap = false
                key.append(lower(c))
            } else gap = true
        }
        return key.toString() in REASONS
    }

    /** Nur A bis Z und Ü: Mehr kommt in den Gründen nicht vor, und so rechnen jedes Gerät und das Skript gleich. */
    private fun lower(c: Char): Char = if (c in 'A'..'Z') c + 32 else if (c == 'Ü') 'ü' else c
}

/**
 * Liest die Dauer hinter "MUTE:" und liefert Minuten.
 *
 * Verlangt sind Minuten als Zahl ("MUTE: 90"). Derselbe Prompt verlangt aber auch, Zahlen als Wörter zu
 * schreiben, und wer "zwei Stunden Ruhe" hört, schreibt leicht "MUTE: 2 Stunden". Früher zählte die
 * erste Ziffernfolge als Minuten: aus zwei Stunden wurden zwei Minuten, und "eine Stunde" schaltete gar
 * nicht stumm, obwohl der Assistent die Ruhe eben zugesagt hatte.
 *
 * Gelesen wird die erste Zahl in der Zeile (Ziffern, auch mit Komma, oder ein Zahlwort) und als Einheit
 * nur das Wort direkt dahinter. So bleibt "30 (eine halbe Stunde)" bei dreißig Minuten. Zwischen Zahl
 * und Einheit darf nur stehen, was in einer kleinen, festen Liste steht: eine Spanne ("2-3 Stunden",
 * "zwei bis drei Stunden"; es gilt der größere Wert), ein halbes dazu ("one and a half hours",
 * "2 1/2 Stunden") oder ein Füllwort ("2 volle Stunden"). Ohne diese Liste würden aus zwei bis drei
 * Stunden zwei Minuten.
 *
 * Im Zweifel dauert die Ruhe eher zu lang als zu kurz: Ohne lesbare Dauer gilt [DEFAULT_MINUTES] statt
 * gar keiner Stummschaltung. Zu lange Ruhe hebt der Träger jederzeit auf, indem er den Assistenten
 * anspricht; zu kurze stört ihn mitten in der Besprechung.
 */
internal object MuteDuration {
    const val DEFAULT_MINUTES = 30
    const val MAX_MINUTES = 600

    // Ziffern ausdrücklich als 0 bis 9: "\d" zählt auf Android auch Ziffern anderer Schriften mit.
    private val TOKEN = Regex("[0-9]+(?:[.,][0-9]+)?|[a-zäöüß]+")

    private val SMALL = mapOf(
        "ein" to 1, "eins" to 1, "zwei" to 2, "zwo" to 2, "drei" to 3, "vier" to 4, "fünf" to 5, "sechs" to 6,
        "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12, "dreizehn" to 13,
        "vierzehn" to 14, "fünfzehn" to 15, "sechzehn" to 16, "siebzehn" to 17, "achtzehn" to 18, "neunzehn" to 19,
    )
    private val TENS = mapOf(
        "zwanzig" to 20, "dreißig" to 30, "dreissig" to 30, "vierzig" to 40, "fünfzig" to 50, "sechzig" to 60,
        "siebzig" to 70, "achtzig" to 80, "neunzig" to 90,
    )
    private val ENGLISH = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90,
    )
    /** "eine Stunde", "an hour": zählt als eins, aber nur direkt vor einer Einheit ("eine Weile" ist keine Zahl). */
    private val ARTICLES = setOf("ein", "eine", "einer", "einen", "a", "an")
    /** Bruchteile, ebenfalls nur vor einer Einheit: "eine halbe Stunde", "anderthalb Stunden". */
    private val FRACTIONS = mapOf(
        "halbe" to 0.5, "halben" to 0.5, "half" to 0.5, "einhalb" to 0.5, "viertel" to 0.25, "dreiviertel" to 0.75,
        "anderthalb" to 1.5,
    )
    private val FIXED = mapOf("viertelstunde" to 15, "dreiviertelstunde" to 45)
    /** "bis morgen", "für heute", "den ganzen Tag": gemeint ist der Rest des Tages, also die längste Ruhe. */
    private val REST_OF_DAY = setOf("heute", "morgen", "tag", "abend", "nacht", "today", "tonight", "tomorrow")

    // Was zwischen der ersten Zahl und der Einheit stehen darf (siehe amount).
    /** "1/2" und "½" werden vor dem Lesen zu diesem Wort: "1 1/2 Stunden" sind anderthalb Stunden. */
    private val HALF_SIGN = Regex("(?<![0-9/])1/2(?![0-9/])|½")
    private val RANGE_WORDS = setOf("bis", "to", "oder", "or")
    private val RANGE_DASHES = setOf("-", "–", "—")
    private val AND = setOf("und", "and")
    private val HALF = setOf("halbe", "halben", "half")
    private val FILLERS = setOf("volle", "vollen", "ganze", "ganzen", "full", "whole")

    fun minutes(arg: String): Int {
        val text = arg.lowercase().replace(HALF_SIGN, " einhalb ")
        val found = TOKEN.findAll(text).toList()
        for ((i, match) in found.withIndex()) {
            val tok = match.value
            FIXED[tok]?.let { return it }
            val next = found.getOrNull(i + 1)?.value
            val value: Double = when {
                tok[0] in '0'..'9' -> tok.replace(',', '.').toDouble()
                tok in ARTICLES -> if (unit(next) != null) 1.0 else continue
                tok in FRACTIONS || tok.endsWith("einhalb") -> {
                    // "half an hour": Zwischen Bruch und Einheit darf im Englischen der Artikel stehen.
                    val after = if (tok == "half" && (next == "a" || next == "an")) found.getOrNull(i + 2)?.value else next
                    val factor = unit(after) ?: continue
                    val amount = FRACTIONS[tok] ?: SMALL[tok.removeSuffix("einhalb")]?.plus(0.5) ?: continue
                    return clamp(amount * factor)
                }
                tok in REST_OF_DAY -> return MAX_MINUTES
                else -> (number(tok) ?: continue).toDouble()
            }
            return clamp(amount(text, found, i, value))
        }
        return DEFAULT_MINUTES
    }

    /**
     * Die erste Zahl ist gelesen ([first], an der Stelle [at]). Meist folgt die Einheit direkt ("2 Stunden")
     * oder gar nichts ("90"). Dazwischen darf nur stehen, was hier aufgezählt ist; von einer Spanne zählt der
     * größere Wert, denn im Zweifel dauert die Ruhe eher zu lang. Jedes andere Wort beendet die Suche, und
     * die Zahl gilt in Minuten: "30 (eine halbe Stunde)" bleibt bei dreißig.
     *
     * @return die Dauer in Minuten, noch ungerundet.
     */
    private fun amount(text: String, found: List<MatchResult>, at: Int, first: Double): Double {
        var most = first
        var half = 0.0
        var p = at + 1
        while (p < found.size) {
            val tok = found[p].value
            unit(tok)?.let { return (most + half) * it }
            val next = found.getOrNull(p + 1)?.value
            when {
                // "2 1/2 Stunden", "zwei einhalb Stunden"
                tok == "einhalb" -> { half = 0.5; p++ }
                // "one and a half hours", "zwei und eine halbe Stunde"
                tok in AND && next in ARTICLES && found.getOrNull(p + 2)?.value in HALF -> { half = 0.5; p += 3 }
                // "zwei bis drei Stunden", "5 oder 10 Minuten"
                tok in RANGE_WORDS && value(next) != null -> { most = maxOf(most, value(next)!!); p += 2 }
                // "2-3 Stunden": Nur ein Strich macht aus zwei Zahlen eine Spanne; "30 (2 Stunden wären zu viel)" ist keine.
                dashBetween(text, found[p - 1], found[p]) && value(tok) != null -> { most = maxOf(most, value(tok)!!); p++ }
                tok in FILLERS -> p++
                else -> break
            }
        }
        return most + half
    }

    /** Steht zwischen den beiden Fundstellen nichts als ein Strich ("2-3", "2 – 3")? */
    private fun dashBetween(text: String, a: MatchResult, b: MatchResult): Boolean =
        text.substring(a.range.last + 1, b.range.first).trim { it == ' ' || it == '\t' || it == '\u00A0' } in RANGE_DASHES

    /** Der Wert einer Zahl in Ziffern oder Worten; null, wenn [tok] keine Zahl ist. */
    private fun value(tok: String?): Double? = when {
        tok == null -> null
        tok[0] in '0'..'9' -> tok.replace(',', '.').toDouble()
        else -> number(tok)?.toDouble()
    }

    /**
     * Aufrunden (dreißig Sekunden sind eine Minute, nicht null) und auf 1 bis [MAX_MINUTES] begrenzen.
     * Der winzige Abzug fängt Rundungsreste der Kommarechnung ab: 1,1 mal 60 ergibt 66,00000000000001,
     * und daraus sollen keine 67 Minuten werden.
     */
    private fun clamp(minutes: Double): Int = ceil(minutes - 1e-6).coerceIn(1.0, MAX_MINUTES.toDouble()).toInt()

    /** Wie viele Minuten eine Einheit hat, oder null, wenn [word] keine Zeiteinheit ist. */
    private fun unit(word: String?): Double? = when {
        word == null -> null
        word.startsWith("stunde") || word.startsWith("hour") || word == "std" || word == "h" || word == "hr" || word == "hrs" -> 60.0
        word.startsWith("minute") || word == "min" || word == "mins" || word == "m" -> 1.0
        word.startsWith("sekunde") || word.startsWith("second") || word == "sek" || word == "sec" || word == "secs" || word == "s" -> 1.0 / 60.0
        word.startsWith("tag") || word.startsWith("day") -> 1440.0
        else -> null
    }

    /** Zahlwort von eins bis neunhundertneunundneunzig ("einhundertzwanzig", "fünfundvierzig"), dazu einfache englische. */
    private fun number(word: String): Int? {
        ENGLISH[word]?.let { return it }
        var rest = word
        var total = 0
        val h = rest.indexOf("hundert")
        if (h >= 0) {
            val head = rest.substring(0, h)
            total = (if (head.isEmpty()) 1 else SMALL[head] ?: return null) * 100
            rest = rest.substring(h + "hundert".length).removePrefix("und")
            if (rest.isEmpty()) return total
        }
        SMALL[rest]?.let { return total + it }
        TENS[rest]?.let { return total + it }
        // Im Deutschen steht der Einer vor dem Zehner: fünf-und-vierzig.
        val u = rest.indexOf("und")
        if (u > 0) {
            val ones = SMALL[rest.substring(0, u)]?.takeIf { it <= 9 }
            val tens = TENS[rest.substring(u + 3)]
            if (ones != null && tens != null) return total + ones + tens
        }
        return null
    }
}
