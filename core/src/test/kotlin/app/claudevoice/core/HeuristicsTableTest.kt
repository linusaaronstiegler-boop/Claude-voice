package app.claudevoice.core

import app.claudevoice.core.Heuristics.Heat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bewertete Tabelle für den lokalen Vorfilter ([Heuristics]).
 *
 * Warum eine Tabelle statt einzelner Tests? Der Vorfilter besteht aus vielen kleinen Regeln, und jede
 * neue Regel kann eine alte still kaputt machen. Die Tabelle hält fest, was bei echten Sätzen herauskommen
 * SOLL. Läuft der Test, sieht man sofort, welche Zeile gekippt ist. Unten wird außerdem ausgezählt, wie oft
 * Soll und Ist auseinanderliegen ("Verwechslungstabelle").
 *
 * So ist die Tabelle bewertet (das Soll hängt nur vom Satz ab, nicht davon, was der Code gerade kann):
 *  - HOT: ausdrücklicher Wissenswunsch. Eine Frage oder Bitte, deren Antwort allgemeines Wissen ist
 *    (Fakten, Sprache, Rechnen, Erklärung, Uhrzeit und Datum), in jeder Form: W-Frage, Verb zuerst,
 *    indirekt ("ich frag mich, ..."), Wortsuche ("wie hieß noch ..."), "erklär mal ...". Oder die Anrede
 *    mit Namen.
 *  - MAYBE: ohne Zusammenhang nicht zu entscheiden. Jede inhaltliche Äußerung ab sechs Wörtern ohne
 *    ausdrücklichen Wissenswunsch (so will es die Pipeline: längere Aussage -> billige Vorprüfung), auch
 *    persönliche, organisatorische und situative Fragen dieser Länge. Dazu kürzere Sätze mit möglichem
 *    verstecktem Wunsch: Behauptung mit Rückversicherung ("... oder"), unsichere Angabe ("... glaub ich").
 *  - COLD: erkennbar nichts zu holen. Füllsel, Reaktionen, Grüße und Floskeln in jeder Länge. Unter sechs
 *    Wörtern auch Aussagen, Ausrufe und persönliche, organisatorische oder situative Fragen
 *    ("kommst du mit", "wo sind meine Schuhe").
 *
 * Zwei Fehler sind verschieden teuer:
 *  - Ein Wissenswunsch landet in COLD: Die Frage wird nie einem Modell gezeigt. Das darf nicht passieren
 *    und wird unten hart geprüft ("COLD ist sicher").
 *  - Geplauder landet in HOT: kostet einen Aufruf des starken Modells, das dann schweigt. Ärgerlich,
 *    aber kein Schaden.
 *
 * Bekannte Abweichungen stehen direkt an der Zeile: `ist = ...` nennt, was der Vorfilter tatsächlich
 * liefert, `weil = ...` den Grund. Der Test prüft auch diese Angabe. Wird der Vorfilter besser, schlägt
 * er fehl und man streicht den Vermerk. So bleibt die Liste der Schwächen ehrlich.
 *
 * Die Schreibweise ahmt die Spracherkennung nach: oft ohne Satzzeichen, Satzanfang klein, Füllwörter,
 * Verhörer ("wahr" statt "war"), doppelte Wörter. Es sind nachgebaute Sätze, keine echten Mitschnitte
 * (die App speichert keine).
 *
 * Wie weit man den Zahlen trauen darf: Die Szenen vor der "Zweiten Stichprobe" entstanden zusammen mit
 * den Regeln; dass sie fast alle stimmen, beweist wenig. Die "Zweite" und "Dritte Stichprobe" wurden
 * dagegen erst nach den Regeln geschrieben und einmal ungesehen gemessen. Dort lag der neue Vorfilter beim
 * ersten Versuch bei 148 von 185 und 70 von 80 richtigen Zeilen (der alte bei 110 und 58), jeweils ohne
 * einen Wissenswunsch in COLD. Beim Einführen dieser Tabelle stand es insgesamt so: alter Vorfilter 492
 * von 782 Zeilen richtig und 21 Wissenswünsche in COLD, neuer 721 von 782 und keiner in COLD.
 * Was der Vorfilter grundsätzlich nicht sehen kann, steht unten bei den blinden Flecken.
 *
 * Danach hat ein zweiter Prüfer eigene Sätze geschrieben, ohne die Tabelle zu kennen, und gezielt nach
 * Lücken gesucht. Ergebnis beim ersten, ungesehenen Durchlauf: Von 150 gewöhnlichen Wissensfragen landeten
 * 4 in COLD (alter Vorfilter: 19), von 209 Sätzen ohne Wissenswunsch 12 in HOT (alter Vorfilter: 86).
 * Eine ganze Gruppe fiel aber durch: kurze Fragen mit dem Verb vorn, deren Verb auf keiner Liste stand
 * ("wurde Kennedy erschossen", "enthält Cola Koffein"). Ohne Fragezeichen waren 34 von 35 solchen Sätzen
 * COLD. Diese Gruppe,
 * fehlende Fragewörter ("woraus"), das Fragewort am Satzende ("das bedeutet was") und Rechenaufgaben ohne
 * Verb sind seither geregelt; die Sätze dazu stehen in der Szene "Nachprüfung". Auch sie sind nachgebaut
 * und wurden nach dem Messen für Korrekturen benutzt, beweisen also nur, dass diese Lücken zu sind.
 */
class HeuristicsTableTest {

    private fun heat(text: String) = Heuristics.heat(text, NAMES)

    @Test fun `Tabellen haben den verlangten Umfang und keine doppelten Zeilen`() {
        assertTrue("Hitze-Zeilen: ${HEAT_ROWS.size}", HEAT_ROWS.size >= 250)
        assertTrue("Namens-Zeilen: ${NAME_ROWS.size}", NAME_ROWS.size >= 80)
        val twice = HEAT_ROWS.groupBy { it.text }.filter { it.value.size > 1 }.keys
        assertTrue("doppelt: $twice", twice.isEmpty())
        val twiceNames = NAME_ROWS.groupBy { it.first }.filter { it.value.size > 1 }.keys
        assertTrue("doppelt: $twiceNames", twiceNames.isEmpty())
    }

    @Test fun `Hitze entspricht der Tabelle`() {
        // Zeile = Soll, Spalte = Ist. Auf der Diagonalen steht alles, was stimmt.
        val heats = Heat.values()
        val counts = Array(heats.size) { IntArray(heats.size) }
        val wrong = ArrayList<String>()
        val stale = ArrayList<String>()
        for (row in HEAT_ROWS) {
            val actual = heat(row.text)
            counts[row.soll.ordinal][actual.ordinal]++
            val documented = row.ist ?: row.soll
            if (actual != documented) {
                val line = "[${row.szene}] \"${row.text}\" soll ${row.soll}, ist $actual"
                if (row.ist != null && actual == row.soll) stale += "$line (Vermerk ist=${row.ist} streichen)" else wrong += line
            }
        }
        println("TABELLE Hitze: ${HEAT_ROWS.size} Zeilen (Zeile = Soll, Spalte = Ist)")
        println("TABELLE          " + heats.joinToString("") { it.name.padStart(7) })
        for (s in heats) println("TABELLE Soll " + s.name.padEnd(5) + counts[s.ordinal].joinToString("") { it.toString().padStart(7) })
        val right = heats.sumOf { counts[it.ordinal][it.ordinal] }
        println("TABELLE richtig: $right von ${HEAT_ROWS.size}")
        (wrong + stale).forEach { println("TABELLE abweichend: $it") }
        assertTrue("Abweichungen ohne Vermerk:\n" + wrong.joinToString("\n"), wrong.isEmpty())
        assertTrue("Überholte Vermerke:\n" + stale.joinToString("\n"), stale.isEmpty())
    }

    @Test fun `COLD ist sicher - kein Wissenswunsch wird verschluckt`() {
        val lost = HEAT_ROWS.filter { it.wunsch && heat(it.text) == Heat.COLD }.map { "[${it.szene}] ${it.text}" }
        assertTrue("Wissenswunsch in COLD:\n" + lost.joinToString("\n"), lost.isEmpty())
    }

    @Test fun `blinde Flecken sind benannt`() {
        // Wird einer dieser Sätze eines Tages erkannt, gehört er in die Tabelle und hier gestrichen.
        val seen = BLIND_SPOTS.filter { heat(it.first) != Heat.COLD }.map { it.first }
        println("TABELLE blinde Flecken: ${BLIND_SPOTS.size} (Wissenswunsch, Ist COLD)")
        assertTrue("kein blinder Fleck mehr, bitte in die Tabelle übernehmen: $seen", seen.isEmpty())
    }

    @Test fun `was nichts hergibt, wird nur in vermerkten Faellen HOT`() {
        val wasted = HEAT_ROWS.filter { it.soll == Heat.COLD && it.ist == null && heat(it.text) == Heat.HOT }.map { it.text }
        assertTrue("unnötig HOT:\n" + wasted.joinToString("\n"), wasted.isEmpty())
        // Die vermerkten Ausnahmen sollen eine Randerscheinung bleiben, sonst taugt das Soll nichts mehr.
        // Die Szene "Nachprüfung" zählt dabei nicht mit: Sie sammelt absichtlich Grenzfälle, die erst seit
        // der Nachprüfung überhaupt geprüft werden (MAYBE statt COLD), und ist deshalb voller Vermerke.
        val regular = HEAT_ROWS.filter { !it.szene.startsWith("Nachprüfung") }
        val noted = regular.count { it.ist != null }
        println("TABELLE vermerkte Abweichungen: $noted von ${regular.size} (mit Nachprüfung: ${HEAT_ROWS.count { it.ist != null }} von ${HEAT_ROWS.size})")
        assertTrue("zu viele vermerkte Abweichungen: $noted", noted * 10 <= regular.size)
    }

    @Test fun `Namen werden erkannt und Beinahe-Treffer nicht`() {
        val wrong = NAME_ROWS.filter { (text, expected) -> Heuristics.mentionsAny(text, NAMES) != expected }
        println("TABELLE Namen: ${NAME_ROWS.size} Zeilen, falsch ${wrong.size}")
        wrong.forEach { println("TABELLE Name abweichend: \"${it.first}\" soll ${it.second}") }
        assertTrue("falsch erkannt:\n" + wrong.joinToString("\n") { "\"${it.first}\" soll ${it.second}" }, wrong.isEmpty())
    }

    @Test fun `hingenommene Irrtuemer bei Namen und Stopp sind benannt`() {
        val changedNames = NAME_LIMITS.filter { (text, actual) -> Heuristics.mentionsAny(text, NAMES) != actual }.map { it.first }
        assertTrue("Namensgrenze verschoben, bitte in NAME_ROWS übernehmen: $changedNames", changedNames.isEmpty())
        val changedStops = STOP_LIMITS.filter { !Heuristics.isStopCommand(it, NAMES) }
        assertTrue("kein lokaler Abbruch mehr, bitte in STOP_ROWS übernehmen: $changedStops", changedStops.isEmpty())
    }

    @Test fun `Wissenswunsch haengt nicht an Grossschreibung und Satzzeichen`() {
        // Niemand weiß sicher, wie die Spracherkennung schreibt: mit Fragezeichen und großen Hauptwörtern
        // oder alles klein und ohne Satzzeichen. Jede Zeile mit Wissenswunsch läuft deshalb in drei
        // Schreibweisen durch. Verloren gehen darf nur, was sich ohne Großschreibung wirklich nicht
        // erkennen lässt, und davon nur kurze Sätze, die nicht mit einem Fragewort beginnen.
        val punctuation = Regex("[?!.,;:…]")
        val wishes = HEAT_ROWS.filter { it.wunsch }.map { punctuation.replace(it.text, "").trim() }.filter { it.isNotEmpty() }

        // 1. Wie ein sauber geschriebener Satz: großer Anfang, Fragezeichen am Ende.
        val lostAsQuestion = wishes.map { it.replaceFirstChar(Char::uppercase) + "?" }.filter { heat(it) == Heat.COLD }
        assertTrue("mit Fragezeichen COLD: $lostAsQuestion", lostAsQuestion.isEmpty())

        // 2. Alles klein, keine Satzzeichen.
        val lostLower = wishes.map { it.lowercase() }.filter { heat(it) == Heat.COLD }
        println("TABELLE klein geschrieben verloren: ${lostLower.size} von ${wishes.size}")
        lostLower.forEach { println("TABELLE klein verloren: $it") }
        val questionWords = setOf("wer", "wen", "wem", "was", "wann", "wo", "wie", "warum", "wieso", "weshalb", "welche", "welcher", "welches")
        val unexpected = lostLower.filter { Heuristics.words(it).size >= 6 || Heuristics.words(it).first() in questionWords }
        assertTrue("klein geschrieben COLD, obwohl lang oder mit Fragewort vorn: $unexpected", unexpected.isEmpty())
        // Die Grenze steht hier als Zahl, damit ein Rückschritt auffällt. Wird es besser, die Zahl senken.
        assertTrue("klein geschrieben gehen ${lostLower.size} Wissenswünsche verloren", lostLower.size <= LOWERCASE_LOST_MAX)

        // 3. Mit Punkt statt Fragezeichen (flach gesprochene Frage).
        val lostWithPeriod = wishes.map { it.replaceFirstChar(Char::uppercase) + "." }.filter { heat(it) == Heat.COLD }
        println("TABELLE mit Punkt verloren: ${lostWithPeriod.size} von ${wishes.size} $lostWithPeriod")
        assertTrue("mit Punkt COLD: $lostWithPeriod", lostWithPeriod.size <= PERIOD_LOST_MAX)
    }

    @Test fun `kurze Frage mit Verb vorn - das Hauptwort dahinter entscheidet`() {
        // Die Regel in einem Blick: gleiches Verb, einmal mit Subjekt (Frage), einmal ohne (Erzähltes).
        assertEquals(Heat.HOT, heat("wurde Rom geplündert"))
        assertEquals(Heat.COLD, heat("wurde dann spät"))
        assertEquals(Heat.HOT, heat("kam Cäsar aus Rom"))
        assertEquals(Heat.COLD, heat("kam mir komisch vor"))
        assertEquals(Heat.MAYBE, heat("leuchtet Phosphor im Dunkeln"))
        assertEquals(Heat.COLD, heat("leuchtet mir nicht ein"))
        assertEquals(Heat.MAYBE, heat("spiegelt sich Licht im Wasser"))
        // Ein Fragezeichen rettet auch das, was die Form nicht hergibt.
        assertEquals(Heat.MAYBE, heat("Quaken alle Frösche?"))
        assertEquals(Heat.HOT, heat("Überwintern Störche in Afrika?"))
    }

    @Test fun `andere Namen - kurze nur woertlich, zweiteilige zusammengesetzt`() {
        // Bei kurzen Namen wäre jede Nachsicht ein Risiko: "Marx" klingt fast wie "Max".
        assertTrue(Heuristics.mentionsName("Max, wie spät ist es", "Max"))
        assertEquals(false, Heuristics.mentionsName("Marx hat das geschrieben", "Max"))
        assertEquals(false, Heuristics.mentionsName("maximal zwei Stück", "Max"))
        assertTrue(Heuristics.mentionsName("mister stark, hilf mir mal", "Mister Stark"))
        assertEquals(false, Heuristics.mentionsName("Alfred, wie spät ist es", ""))
        assertEquals(false, Heuristics.mentionsAny("Alfred, wie spät ist es", emptyList()))
    }

    @Test fun `Stoppbefehl mit Namen`() {
        val wrong = STOP_ROWS.filter { (text, expected) -> Heuristics.isStopCommand(text, NAMES) != expected }
        println("TABELLE Stoppbefehl: ${STOP_ROWS.size} Zeilen, falsch ${wrong.size}")
        wrong.forEach { println("TABELLE Stopp abweichend: \"${it.first}\" soll ${it.second}") }
        assertTrue("falsch:\n" + wrong.joinToString("\n") { "\"${it.first}\" soll ${it.second}" }, wrong.isEmpty())
        // Die Fassung mit einem einzelnen Namen muss dasselbe liefern.
        assertTrue(Heuristics.isStopCommand("Jarvis, stopp", "Jarvis"))
        assertEquals(false, Heuristics.isStopCommand("Jarvis, stopp", "Alfred"))
    }

    @Test fun `blosses Stopp ohne Namen`() {
        val wrong = BARE_STOP_ROWS.filter { (text, expected) -> Heuristics.isBareStop(text) != expected }
        println("TABELLE blosses Stopp: ${BARE_STOP_ROWS.size} Zeilen, falsch ${wrong.size}")
        wrong.forEach { println("TABELLE blosses Stopp abweichend: \"${it.first}\" soll ${it.second}") }
        assertTrue("falsch:\n" + wrong.joinToString("\n") { "\"${it.first}\" soll ${it.second}" }, wrong.isEmpty())
    }

    @Test fun `echter Inhalt gegen Fuellsel und Ratlosigkeit`() {
        val wrong = CONTENT_ROWS.filter { (text, expected) -> Heuristics.isRealContent(text) != expected }
        println("TABELLE Inhalt: ${CONTENT_ROWS.size} Zeilen, falsch ${wrong.size}")
        wrong.forEach { println("TABELLE Inhalt abweichend: \"${it.first}\" soll ${it.second}") }
        assertTrue("falsch:\n" + wrong.joinToString("\n") { "\"${it.first}\" soll ${it.second}" }, wrong.isEmpty())
    }

    @Test fun `eigenes Anliegen gegen blossen Einwurf`() {
        // Die Pipeline fragt hiernach, ob eine wartende Äußerung für sich ein Anliegen trägt (Pipeline.isAside).
        // Anders als bei isRealContent zählt Ratlosigkeit vor einer Frage mit: Der Satz ist ein Wissenswunsch.
        val rows = listOf(
            "keine Ahnung wie das heißt" to true,
            "weiß nicht wie der heißt" to true,
            "keine Ahnung wer das war" to true,
            "nicht sicher ob das stimmt" to true,
            "Stimmt das?" to true,
            "Wann fängt der Film an?" to true,

            "Warum?" to false,
            "Ja wieso?" to false,
            "Wann?" to false,
            "keine Ahnung" to false,
            "ähm gute Frage" to false,
            "" to false,
        )
        val wrong = rows.filter { (text, expected) -> Heuristics.saysSomething(text) != expected }
        assertTrue("falsch:\n" + wrong.joinToString("\n") { "\"${it.first}\" soll ${it.second}" }, wrong.isEmpty())
        // Die ersten vier sind genau die Fälle, in denen sich die beiden Maßstäbe unterscheiden.
        for ((text, _) in rows.take(4)) assertEquals(text, false, Heuristics.isRealContent(text))
        // Was als Antwort auf eine Frage zählt, sagt erst recht für sich etwas.
        val odd = CONTENT_ROWS.filter { (text, content) -> content && !Heuristics.saysSomething(text) }.map { it.first }
        assertTrue("Inhalt, aber kein Anliegen: $odd", odd.isEmpty())
    }

    @Test fun `Zwischenstaende und krumme Eingaben bringen nichts zum Absturz`() {
        // Der Vorfilter sieht jeden Zwischenstand der Transkription, also auch halbe Sätze.
        // Hier läuft jede Zeile Wort für Wort durch alle Funktionen; es darf nur nichts schiefgehen.
        val odd = listOf("?", "...", "'", "''s", "???", "a", " , ", "ß", "ok?!", "was,", "ist ?", "wie 's", "\uD83D\uDE42", "A. B. C.", "ob", "tell", "wie wie", "der der der")
        val texts = HEAT_ROWS.map { it.text } + NAME_ROWS.map { it.first } + STOP_ROWS.map { it.first } + odd
        var calls = 0
        for (text in texts) {
            val parts = text.split(' ')
            for (n in 1..parts.size) {
                val partial = parts.take(n).joinToString(" ")
                Heuristics.heat(partial, NAMES)
                Heuristics.mentionsAny(partial, NAMES)
                Heuristics.isStopCommand(partial, NAMES)
                Heuristics.isBareStop(partial)
                Heuristics.isRealContent(partial)
                calls++
            }
        }
        assertTrue(calls > 3000)
    }

    @Test fun `ab sechs Woertern mit Inhalt ist nichts mehr COLD`() {
        // Das Sicherheitsnetz hinter allen Regeln: Wer länger redet, kommt mindestens in die Vorprüfung.
        val cold = HEAT_ROWS.filter { row ->
            val w = Heuristics.words(row.text)
            w.size >= 6 && row.soll != Heat.COLD && heat(row.text) == Heat.COLD
        }.map { it.text }
        assertTrue("lang und trotzdem COLD: $cold", cold.isEmpty())
    }

    @Test fun `Hilfsfunktionen behalten ihr Verhalten`() {
        assertEquals(listOf("wie", "geht's", "dir", "2", "mal"), Heuristics.words("Wie geht's dir? 2 mal!"))
        assertEquals(0, Heuristics.editDistance("alfred", "alfred"))
        assertEquals(1, Heuristics.editDistance("alfred", "alfret"))
        assertEquals(3, Heuristics.editDistance("jarvis", "travis"))
        assertEquals(6, Heuristics.editDistance("", "alfred"))
        // Beide Fassungen von heat liefern dasselbe.
        assertEquals(Heuristics.heat("Wie hoch ist die Zugspitze?", listOf("Alfred")), Heuristics.heat("Wie hoch ist die Zugspitze?", "Alfred"))
        assertEquals(Heat.HOT, Heuristics.heat("Jarvis", listOf("Alfred", "Jarvis")))
        assertEquals(Heat.COLD, Heuristics.heat("   ", "Alfred"))
    }
}

private val NAMES = listOf("Alfred", "Jarvis")

/**
 * So viele Zeilen mit Wissenswunsch werden COLD, wenn alles klein geschrieben ist. Es sind kurze Fragen
 * mit dem Verb vorn ("wurde kennedy erschossen"): Ohne Großschreibung ist das Hauptwort hinter dem Verb
 * nicht von "wurde dann spät" zu unterscheiden.
 */
private const val LOWERCASE_LOST_MAX = 23

/** ... und so viele, wenn hinter der Frage ein Punkt statt eines Fragezeichens steht. */
private const val PERIOD_LOST_MAX = 2

// Gründe für bekannte Abweichungen. Jede steht für eine Grenze, die ein Filter ohne Zusammenhang hat.
private const val SITUATIV =
    "situative Frage: sieht aus wie eine Wissensfrage; dass es ums Hier und Jetzt geht, zeigt nur der Zusammenhang"
private const val ERLAUBNIS =
    "\"kann/darf/muss ich ...\" fragt mal um Erlaubnis, mal nach Wissen (\"darf ich hier parken\"): billige Vorprüfung"
private const val SELBST =
    "Frage an sich selbst; \"wie koche ich Reis\" hat dieselbe Form und wäre ein Wissenswunsch"
private const val RUNDE =
    "Frage an die Runde: \"hat jemand die Lösung\" sähe genauso aus"
private const val ER_SIE =
    "\"er/sie/Sie\" als Subjekt: Gegenüber, Bekannte oder berühmte Person? Das zeigt nur der Zusammenhang"
private const val OFFEN =
    "unbekanntes Wort hinter dem Verb: Frage (\"ist heute Vollmond\") oder Aussage ohne Subjekt? Lieber die Vorprüfung"
private const val NEBENSATZ =
    "Nebensatz mit Fragewort: Ausruf oder abgebrochene Frage (\"wie man das schreibt\")? Lieber die Vorprüfung"
private const val KLEIN =
    "ohne Großschreibung ist das Hauptwort hinter dem Verb nicht zu erkennen"
private const val KENNST_DU =
    "\"kennst du ...\" fragt meist nach Bekannten (\"kennst du den Marco\"), selten nach Wissen"
private const val DREI_WORTE =
    "drei Wörter mit Fragezeichen: Angebot oder Stichwort-Frage (\"Hauptstadt von Australien?\")?"
private const val ZUSTIMMUNG =
    "\"das stimmt\" zählt als Inhalt, damit \"ich frag mich, ob das stimmt\" nicht untergeht"
private const val STICHWORT =
    "Stichwort ohne Verb: als Frage nicht sicher zu erkennen, deshalb nur die Vorprüfung"
private const val OB_ES =
    "\"ob es/das ...\" ist mal Sachfrage, mal Bangen (\"ob das wohl klappt\"): billige Vorprüfung"
private const val REDENSART =
    "rhetorisch oder Redensart; der Form nach eine Frage"
private const val SELTEN =
    "Verb steht auf keiner Liste und ist nur an der Endung erkannt (\"Robert Koch war Arzt\" fängt genauso an): Vorprüfung"
private const val ENDE =
    "Fragewort am Satzende: \"da ist was\" und \"ich weiß wie\" enden genauso, deshalb nur die Vorprüfung"
private const val RECHNEN =
    "Rechenaufgabe ohne Verb: \"zwei mal drei Meter\" sieht genauso aus, deshalb nur die Vorprüfung"
private const val VORNAME =
    "Wort auf \"t\" und dahinter ein Hauptwort: sieht aus wie Verb und Subjekt einer Frage"

/** Eine Zeile der Hitze-Tabelle. [wunsch] = enthält einen Wissenswunsch und darf deshalb nie COLD sein. */
private class HeatRow(val text: String, val soll: Heat, val szene: String, val wunsch: Boolean, val ist: Heat?, val weil: String?)

private class HeatTable {
    val rows = ArrayList<HeatRow>()
    private var szene = ""

    fun szene(name: String, block: HeatTable.() -> Unit) { szene = name; block() }

    /** Ausdrücklicher Wissenswunsch oder Anrede. */
    fun hot(text: String, ist: Heat? = null, weil: String? = null) = add(text, Heat.HOT, true, ist, weil)

    /** Ohne Zusammenhang nicht zu entscheiden; darf auch COLD werden, ohne dass etwas verloren geht. */
    fun maybe(text: String, ist: Heat? = null, weil: String? = null) = add(text, Heat.MAYBE, false, ist, weil)

    /** Wie [maybe], aber mit verstecktem Wissenswunsch: darf nie COLD werden. */
    fun wunsch(text: String, ist: Heat? = null, weil: String? = null) = add(text, Heat.MAYBE, true, ist, weil)

    /** Erkennbar nichts zu holen. */
    fun cold(text: String, ist: Heat? = null, weil: String? = null) = add(text, Heat.COLD, false, ist, weil)

    private fun add(text: String, soll: Heat, wunsch: Boolean, ist: Heat?, weil: String?) {
        require((ist == null) == (weil == null)) { "ist und weil gehören zusammen: $text" }
        require(ist != soll) { "Vermerk ohne Abweichung: $text" }
        rows += HeatRow(text, soll, szene, wunsch, ist, weil)
    }
}

private val HEAT_ROWS: List<HeatRow> = HeatTable().apply {

    szene("Party, WG, Kneipe") {
        hot("wer hat eigentlich das Telefon erfunden")
        hot("ähm wie viele Einwohner hat Island eigentlich")
        hot("sag mal wie alt ist der Kölner Dom")
        hot("und warum ist der Himmel jetzt genau blau")
        hot("ist ein Wal eigentlich ein Fisch oder ein Säugetier")
        hot("weiß jemand wie der Typ aus Breaking Bad heißt")
        hot("wie hieß nochmal der Sänger von Queen")
        hot("ey ist Tequila aus Kakteen gemacht")
        hot("in welchem Jahr war nochmal die WM in Deutschland")
        hot("stimmt es dass Goldfische nur drei Sekunden Gedächtnis haben")
        hot("seit wann gibt es eigentlich den Euro")
        hot("gibt es in Australien wirklich keine aktiven Vulkane")
        hot("kann man von zu viel Wasser sterben")
        hot("ich frag mich gerade wie viel so ein Fass Bier wiegt")
        hot("keine Ahnung wie die Hauptstadt von Kanada heißt")
        hot("wie viel Prozent hat eigentlich Absinth")
        hot("Leute kurze Frage was ist der höchste Berg in Afrika")
        hot("hat Finnland eigentlich mehr Seen als Schweden")
        hot("wer hat nochmal letztes Jahr die Champions League gewonnen")
        hot("wie nennt man das wenn man Angst vor Spinnen hat")
        hot("mir fällt der Name von dem Film nicht ein")
        hot("wie lange braucht das Licht von der Sonne bis zur Erde")
        hot("was war nochmal der Unterschied zwischen Ale und Lager")
        hot("ich komm grad nicht drauf wie die Band heißt")
        hot("stimmt das dass Bananen radioaktiv sind")
        hot("wie heißt das Ding nochmal mit dem man Flaschen aufmacht")
        hot("war das nicht der gleiche Schauspieler wie in Inception")

        cold("ja genau")
        cold("haha stimmt")
        cold("echt jetzt")
        cold("Echt?")
        cold("Wirklich?")
        cold("nee oder")
        cold("ach so okay")
        cold("krass")
        cold("prost")
        cold("na dann prost")
        cold("auf jeden Fall")
        cold("kein Ding")
        cold("was für ein Abend")
        cold("wie geil ist das denn")
        cold("na alles klar bei dir")
        cold("wie geht's dir so")
        cold("was geht ab")
        cold("kommst du noch mit raus")
        cold("willst du noch ein Bier")
        cold("hast du Feuer")
        cold("wer will noch was trinken")
        cold("mach mal lauter")
        cold("boah ist das laut hier")
        cold("das ist ja der Hammer")
        cold("ich hol mir noch was")
        cold("bin gleich wieder da")
        cold("alter was für eine Aktion")
        cold("wo bist du gerade")
        cold("was machst du so beruflich")
        cold("wie war dein Urlaub")
        cold("ja ja ich weiß")
        cold("war echt gut gestern")
        cold("ist halt so")
        cold("kann man machen")
        cold("hat Spaß gemacht")
        cold("weißt du was")
        cold("war ein schöner Abend")
        cold("stimmt schon")

        maybe("wir waren letztes Jahr drei Wochen in Portugal unterwegs")
        maybe("ich glaub der kommt ursprünglich aus Leipzig oder so")
        maybe("der Laden hat übrigens neulich erst aufgemacht")
        maybe("also ich fand den zweiten Teil deutlich besser als den ersten")
        maybe("kommst du am Samstag auch zu der Party von Lena")
        maybe("ich weiß nicht ob ich morgen kommen kann")
        maybe("was ich sagen wollte ist dass wir noch Eis brauchen")
        maybe("weißt du was ich meine das ist halt immer so")
        maybe("wie schön dass ihr alle da seid")
        maybe("die spielen glaub ich seit den Achtzigern zusammen")

        wunsch("das war doch 2014 in Brasilien oder")
        wunsch("der ist doch auch schon über sechzig ne")
        wunsch("das war 1989 glaub ich")
        wunsch("Berlin hat dreieinhalb Millionen Einwohner oder")
    }

    szene("Unterricht, Vorlesung, Lernen") {
        hot("was bedeutet eigentlich Ambivalenz")
        hot("was heißt Photosynthese")
        hot("was heisst das auf Deutsch")
        hot("wie sagt man Schmetterling auf Englisch")
        hot("wie schreibt man Rhythmus")
        hot("kannst du mir erklären was ein Integral ist")
        hot("erklär mir mal bitte den Dreisatz")
        hot("kann mir jemand den Unterschied zwischen Mitose und Meiose erklären")
        hot("was ist der Unterschied zwischen Wetter und Klima")
        hot("wie berechnet man nochmal die Fläche von einem Kreis")
        hot("ist die Wurzel aus zwei eine rationale Zahl")
        hot("wofür steht die Abkürzung DNA")
        hot("wie viel ist siebzehn mal dreiundzwanzig")
        hot("ich versteh nicht warum man hier ableiten muss")
        hot("was genau ist ein Oxymoron")
        hot("wann war nochmal der Dreißigjährige Krieg")
        hot("definiere mal bitte den Begriff Inflation")
        hot("wer kann mir sagen wie viele Protonen Kohlenstoff hat")
        hot("welche Zeitform ist das")
        hot("heißt es der oder das Nutella")
        hot("schreibt man das groß oder klein")
        hot("sind Viren eigentlich Lebewesen")
        hot("wie funktioniert noch mal die Bruchrechnung")
        hot("ich weiß nicht mehr wie die Formel für die Geschwindigkeit geht")
        hot("übersetz mal bitte den Satz ins Englische")
        hot("was ist nochmal ein Substantiv")
        hot("was kommt raus wenn man 144 durch 12 teilt")
        hot("wie viele Bundesländer hat Österreich")
        hot("aus welchem Jahr stammt das Grundgesetz")
        hot("nenn mir mal drei Edelgase")
        hot("buchstabier mal Chrysantheme")
        hot("wie spricht man das aus")
        hot("ich hab vergessen wie man den Flächeninhalt berechnet")

        maybe("wir schlagen jetzt bitte alle das Buch auf Seite vierzig auf")
        maybe("die Photosynthese findet in den Chloroplasten statt")
        maybe("das haben wir letzte Woche schon besprochen")
        maybe("wie gesagt das kommt in der Klausur dran")
        maybe("was man dabei beachten muss ist die Reihenfolge")
        maybe("also die Ableitung von x Quadrat ist zwei x")
        maybe("wer das nicht versteht meldet sich bitte")
        maybe("kannst du mir mal deinen Stift leihen")
        maybe("ob das wohl in der Klausur drankommt")

        cold("schreibt euch das bitte auf")
        cold("Ruhe bitte")
        cold("das ist eine gute Frage")
        cold("gut aufgepasst")
        cold("genau so ist es")
        cold("ich verstehe das einfach nicht")
        cold("darf ich kurz aufs Klo", ist = Heat.MAYBE, weil = ERLAUBNIS)
        cold("müssen wir das auswendig können", ist = Heat.MAYBE, weil = ERLAUBNIS)
        cold("Entschuldigung was war die Hausaufgabe", ist = Heat.HOT, weil = SITUATIV)
    }

    szene("Telefonat") {
        hot("weißt du zufällig wann der letzte Zug nach München fährt")
        hot("kannst du mir sagen wie spät es in New York gerade ist")
        hot("wie war noch mal die Vorwahl von Hamburg")
        hot("was kostet eigentlich ein Brief nach Frankreich")
        hot("ist morgen eigentlich ein Feiertag")
        hot("sag mal weißt du noch wie der Arzt hieß bei dem wir letztes Jahr waren")
        hot("wo genau liegt nochmal Bielefeld")
        hot("wie lange fliegt man eigentlich nach Tokio")

        cold("hallo hörst du mich")
        cold("bist du noch dran")
        cold("ich ruf dich gleich zurück")
        cold("ja mhm okay ja")
        cold("warte mal kurz")
        cold("alles klar dann bis morgen tschüss")
        cold("ja genau das stimmt ja ja okay gut", ist = Heat.MAYBE, weil = ZUSTIMMUNG)
        cold("was hast du gesagt")
        cold("wie bitte")
        cold("kannst du das nochmal wiederholen")
        cold("okay mach's gut")
        cold("ja hallo wer ist da")

        maybe("können wir das auf Donnerstag verschieben")
        maybe("wann passt es dir denn am besten")
        maybe("ich wollte eigentlich nur kurz fragen ob das Paket angekommen ist")
        maybe("die Nummer ist null eins sieben sechs und dann")
        maybe("ich bin gerade noch im Zug und hab schlechten Empfang")
        maybe("um wie viel Uhr geht dein Flug")
        maybe("wie lange hat das Bürgeramt heute auf", ist = Heat.HOT, weil = SITUATIV)
        maybe("wie ist denn die Adresse von dem Restaurant", ist = Heat.HOT, weil = SITUATIV)
    }

    szene("Selbstgespräch") {
        hot("hm wie viel Gramm sind nochmal ein Pfund")
        hot("wie lange müssen Eier kochen bis sie hart sind")
        hot("wie war das noch mit dem Satz des Pythagoras")
        hot("ich frag mich ob Pinguine eigentlich Knie haben")
        hot("wie viele Kalorien hat eigentlich eine Avocado")
        hot("na wie heißt das Wort dafür")
        hot("liegt mir auf der Zunge")
        hot("wie rechnet man Fahrenheit in Celsius um")
        hot("hmm welcher Wochentag war eigentlich der erste Januar 2000")
        hot("würde mich mal interessieren wie tief der Bodensee ist")
        hot("ich wüsste gern wie viel so ein Tesla wiegt")
        hot("wie viel Uhr ist es eigentlich")
        hot("wie spät ist es")
        hot("muss man Reis eigentlich vorher waschen")
        hot("ob Hefe wohl im Kühlschrank länger hält")
        hot("wann ist nochmal Muttertag")
        hot("was reimt sich auf Orange")
        hot("wie hieß der Typ nochmal der die Glühbirne erfunden hat")
        hot("kein Plan wie man das ausrechnet")
        hot("ich überleg gerade wie viele Tage der Februar in einem Schaltjahr hat")
        hot("bin mir nicht sicher ob man Chrom mit K oder mit C schreibt")

        cold("so wo war ich")
        cold("wo war ich stehen geblieben")
        cold("okay weiter gehts")
        cold("ach Mist")
        cold("na toll")
        cold("wie die Zeit vergeht")
        cold("hm mal überlegen")
        cold("passt")
        cold("so fertig")

        maybe("so jetzt noch schnell die Wäsche aufhängen und dann einkaufen")
        maybe("ich muss unbedingt noch die Steuererklärung machen")
        maybe("das kann doch nicht so schwer sein")
        maybe("noch zwei Minuten dann ist die Pizza fertig")
        maybe("erst das Mehl dann die Eier und dann die Milch")
        maybe("was wollte ich jetzt eigentlich nachschauen")
        maybe("wo ist denn jetzt schon wieder mein Handy", ist = Heat.HOT, weil = SITUATIV)
        maybe("wo hab ich nur meinen Schlüssel hingelegt")
        maybe("war das jetzt links oder rechts rum", ist = Heat.HOT, weil = SITUATIV)
    }

    szene("Familie, Alltag") {
        hot("Papa warum ist der Mond manchmal tagsüber zu sehen")
        hot("Mama was ist ein Vulkan")
        hot("wie viele Beine hat eine Spinne")
        hot("können Fische eigentlich ertrinken")
        hot("warum haben Zebras Streifen")
        hot("wie weit ist es bis zum Mond")
        hot("sind Erdbeeren eigentlich Nüsse")
        hot("darf man Avocadokerne essen")
        hot("wie lange ist Hackfleisch im Kühlschrank haltbar")
        hot("bei wie viel Grad wäscht man Handtücher")
        hot("weißt du wie man Rotweinflecken rausbekommt")
        hot("was ist schwerer ein Kilo Federn oder ein Kilo Blei")
        hot("wie alt werden Schildkröten")
        hot("woher kommt eigentlich der Name Dienstag")
        hot("hat ein Regenwurm Augen")
        hot("kennst du die Hauptstadt von Australien", ist = Heat.MAYBE, weil = KENNST_DU)

        cold("was gibt's heute zum Abendessen")
        cold("räum bitte dein Zimmer auf")
        cold("kommst du bitte zum Essen")
        cold("hat Mama schon angerufen")
        cold("ist Oma noch im Krankenhaus")
        cold("wann kommt Papa nach Hause")
        cold("vergiss nicht den Müll rauszubringen")
        cold("das Essen ist fertig")
        cold("gute Nacht schlaf schön")
        cold("wie war's in der Schule")
        cold("na wie war dein Tag")
        cold("magst du noch Nudeln")
        cold("wann musst du morgen aufstehen")
        cold("hat jemand meine Brille gesehen", ist = Heat.MAYBE, weil = RUNDE)
        cold("wo sind meine Schuhe", ist = Heat.HOT, weil = SITUATIV)
        cold("ab ins Bett jetzt")

        maybe("hast du die Hausaufgaben schon gemacht")
        maybe("ich geh mal kurz einkaufen brauchst du was")
        maybe("wir müssen morgen um sieben los")
        maybe("der Arzttermin ist am Dienstag um halb neun")
        maybe("sollen wir heute Abend Pizza bestellen")
        maybe("wer hat den letzten Joghurt gegessen", ist = Heat.HOT, weil = SITUATIV)
        maybe("wer bringt die Kinder morgen zur Schule", ist = Heat.HOT, weil = SITUATIV)
    }

    szene("Unterwegs: Restaurant, Bahn, Laden") {
        hot("wie viel Trinkgeld gibt man eigentlich in den USA")
        hot("ist Leitungswasser in Spanien trinkbar")
        hot("was heißt danke auf Portugiesisch")
        hot("wie sagt man die Rechnung bitte auf Italienisch")
        hot("wie viel sind fünfzig Dollar in Euro")
        hot("welche Sprache spricht man eigentlich in Brasilien")
        hot("wie lange braucht man mit dem Zug von Berlin nach München")

        cold("die Rechnung bitte")
        cold("einmal Pommes mit Mayo bitte")
        cold("Mit Sahne?")
        cold("Noch ein Bier?", ist = Heat.MAYBE, weil = DREI_WORTE)
        cold("Entschuldigung ist hier noch frei", ist = Heat.MAYBE, weil = OFFEN)
        cold("ich hätte gern einen Cappuccino")
        cold("nächster Halt Hannover Hauptbahnhof")
        cold("was können Sie denn empfehlen", ist = Heat.MAYBE, weil = ER_SIE)
        cold("stimmt so danke")
        cold("zum Mitnehmen bitte")

        maybe("zahlen Sie bar oder mit Karte")
        maybe("das macht dann zwölf Euro achtzig")
        maybe("bitte beachten Sie die Lücke zwischen Zug und Bahnsteigkante")
        maybe("wann fährt der nächste Zug nach Köln", ist = Heat.HOT, weil = SITUATIV)
        maybe("auf welchem Gleis fährt der ICE nach Berlin", ist = Heat.HOT, weil = SITUATIV)
        maybe("gibt es hier in der Nähe eine Apotheke", ist = Heat.HOT, weil = SITUATIV)
    }

    szene("Englisch und gemischt") {
        hot("what's the capital of Australia")
        hot("who invented the telephone")
        hot("how do you say butterfly in German")
        hot("do you know when the Berlin Wall fell")
        hot("is a tomato a fruit or a vegetable")
        hot("why is the sky blue")
        hot("I wonder how far away the moon is")
        hot("does anyone know what time it is in Tokyo")
        hot("what does serendipity mean")
        hot("can you explain how a transformer works")
        hot("how many people live in Canada")
        hot("how do you spell necessary")
        hot("are whales mammals")
        hot("wait what year did the Titanic sink")
        hot("tell me the boiling point of water in Fahrenheit")
        hot("any idea who wrote Faust")
        hot("was ist eigentlich der Unterschied zwischen affect und effect")
        hot("wie spricht man eigentlich Worcestershire aus")
        hot("was heißt nochmal deadline auf Deutsch")

        cold("how are you")
        cold("what's up")
        cold("oh my god")
        cold("what a day")
        cold("how nice")
        cold("how cool is that")
        cold("see you later")
        cold("yeah I know right")
        cold("that's so funny")
        cold("are you coming tonight")
        cold("nice to meet you")
        cold("let's go")
        cold("no way")
        cold("what the hell")
        cold("do you want another drink")
        cold("sorry what did you say")
        cold("you know what I mean")

        maybe("I was thinking we could go to the movies tomorrow")
        maybe("das Meeting war echt nice aber viel zu lang")
        maybe("we should probably leave in about ten minutes")
    }

    szene("Ausrufe und feste Wendungen mit Fragewort") {
        cold("was für ein Tag")
        cold("wie schön")
        cold("was für eine Überraschung")
        cold("wie süß ist der denn")
        cold("was ein Glück")
        cold("wie krass")
        cold("wie cool ist das bitte")
        cold("was du nicht sagst")
        cold("wie auch immer")
        cold("wer weiß")
        cold("was soll's")
        cold("wie gesagt")
        cold("wie du meinst")
        cold("warum auch nicht")
        cold("wie lieb von dir")
        cold("was für ein Quatsch")
        cold("na und wie")
        cold("was weiß ich")
        cold("wie peinlich")
        cold("was zum Teufel")
        cold("wo wir gerade dabei sind")
        cold("wie immer halt")
        cold("was für ein Wetter heute")

        maybe("wie schade dass du nicht kommen kannst")
        maybe("was für ein schönes Wetter heute")
        maybe("was mich echt nervt ist dieser ständige Lärm")

        // Gegenprobe: dieselben Anfänge, aber echte Fragen.
        hot("was für ein Tier ist das eigentlich")
        hot("wie schwer ist ein Elefant")
        hot("was für eine Sprache spricht man in Belgien")
        hot("wie groß ist eigentlich der Bodensee")
    }

    szene("Verb zuerst: Frage oder Aussage ohne Subjekt") {
        hot("hat der Mond eine eigene Atmosphäre")
        hot("kann Glas eigentlich schmelzen")
        hot("ist das Tote Meer wirklich ein Meer")
        hot("leben eigentlich Eisbären in der Antarktis")
        hot("leben eisbären eigentlich in der antarktis", ist = Heat.MAYBE, weil = KLEIN)
        hot("ist Wasser bei vier Grad am dichtesten")
        hot("stimmt es eigentlich dass man im Weltall nichts hört")
        hot("war Mozart eigentlich Österreicher oder Deutscher")
        hot("gehört Grönland zu Dänemark")
        hot("braucht man für Kanada ein Visum")
        hot("dürfen Hunde Schokolade essen")
        hot("kommt der Begriff Roboter wirklich aus dem Tschechischen")
        hot("haben Haie Knochen")
        hot("ist eine Tomate jetzt Obst oder Gemüse")
        hot("macht Kaffee wirklich wach")
        hot("hast du eine Ahnung wie viel so ein Haus kostet")
        hot("weißt du noch wie das Restaurant in Rom hieß")
        hot("kannst du mir sagen was Empathie bedeutet")
        hot("ist der Mond rund")
        hot("war nicht Edison der Erfinder der Glühbirne")
        hot("ist heute nicht der längste Tag des Jahres")

        cold("sind wir schon da")
        cold("weiß ich doch nicht")
        cold("hab ich mir auch gedacht")
        cold("kann schon sein")
        cold("muss ich mal schauen")
        cold("ist mir eigentlich egal")
        cold("geht klar")
        cold("wird schon schiefgehen")
        cold("ist ja krass")
        cold("war klar")
        cold("ist eine lange Geschichte")
        cold("ist der Hammer")
        cold("ist ein guter Punkt")
        cold("gibt's doch nicht")
        cold("kommt drauf an")
        cold("hat er gestern noch gesagt", ist = Heat.MAYBE, weil = ER_SIE)
        cold("ist das dein Ernst")
        cold("ist das schön hier")
        cold("war das lecker")
        cold("hast du das gesehen")
        cold("seid ihr schon fertig")
        cold("ist nicht schlimm")
        cold("kann ich verstehen")
    }

    szene("Indirekte Fragen und lautes Nachdenken") {
        hot("mich würde interessieren warum Katzen schnurren")
        hot("ich hab mich schon immer gefragt warum das Meer salzig ist")
        hot("weiß einer von euch wie viele Saiten eine Harfe hat")
        hot("ich bin mir nicht sicher ob Sydney oder Canberra die Hauptstadt ist")
        hot("keine Ahnung warum das so ist")
        hot("ich würde gern wissen wie alt das Universum ist")
        hot("kann mir mal jemand sagen wer eigentlich Bundespräsident ist")
        hot("ich überleg schon die ganze Zeit wie der Schauspieler heißt")
        hot("wie hieß der gleich")
        hot("na der Dings wie heißt er denn")
        hot("ich komm einfach nicht auf den Namen")
        hot("ich muss mal googeln wie viele Menschen in Tokio leben")

        cold("keine Ahnung was du meinst")
        cold("ich weiß was du meinst")
        cold("ich weiß wie das geht")
        cold("sag Bescheid wann du kommst")
        cold("frag mich nicht")
        cold("frag mich was Leichteres")
        cold("weiß nicht genau")
        cold("keine Ahnung")

        maybe("ich weiß nicht was ich anziehen soll")
        maybe("mal sehen wie das Wetter wird")
        maybe("egal wer gewinnt Hauptsache es wird spannend")
        maybe("ich hab gesehen wie er hingefallen ist")
        maybe("ich frag mich echt was das soll")

        wunsch("der Film mit dem Typen der immer so grimmig guckt na der")
        wunsch("die Hauptstadt von Australien ist doch Sydney")
        wunsch("ich glaub die Zugspitze ist knapp 3000 Meter hoch")
        wunsch("ich glaub das war Edison")
    }

    szene("Rückversicherung am Satzende") {
        hot("das war doch 1989 oder nicht?")
        hot("Das stimmt doch so, oder?")
        hot("Spinnen sind doch keine Insekten, oder?")
        hot("it's in Bavaria, right?")

        wunsch("die Mauer ist doch 1989 gefallen oder")
        wunsch("ein Marathon hat zweiundvierzig Kilometer ne")
        wunsch("das ist doch giftig oder")
        wunsch("Canberra ist die Hauptstadt von Australien richtig")
        wunsch("der Eiffelturm ist doch über 300 Meter hoch gell")

        cold("schön hier ne")
        cold("ganz schön kalt heute oder")
        cold("war lecker oder")
        cold("Schönes Wetter heute, oder?")
        cold("ist doch so oder")

        maybe("du kommst doch auch mit oder")
        maybe("das kann man so machen oder")
    }

    szene("Erklären, definieren, übersetzen, rechnen") {
        hot("erklär mal kurz was ein Algorithmus ist")
        hot("kannst du das nochmal langsam erklären")
        hot("was versteht man unter Opportunitätskosten")
        hot("was meint man eigentlich mit Nachhaltigkeit")
        hot("definier mir mal Demokratie in einem Satz")
        hot("kann jemand erklären wie eine Wärmepumpe funktioniert")
        hot("wofür braucht man eigentlich Logarithmen")
        hot("explain photosynthesis in one sentence")
        hot("was ist ein Derivat")
        hot("was sind Primzahlen")
        hot("rechne mal fünfzehn Prozent von achtzig aus")
        hot("was heißt eigentlich ambivalent")

        cold("das erklärt einiges")
        cold("ich erklär dir das später")

        maybe("er hat mir das gestern schon erklärt")
    }

    szene("Verhörer und Rauschen der Spracherkennung") {
        hot("wie hoch ist die zug spitze")
        hot("wer hatte Relativität Theorie erfunden")
        hot("wie fiel Einwohner hat Berlin")
        hot("was is die Hauptstadt von Peru")
        hot("wann wahr der erste Weltkrieg")
        hot("isst ein Wal ein Fisch")
        hot("ist ein Wahl ein Fisch")
        hot("weist du wie hoch der Eiffelturm ist")
        hot("seid wann gibt es eigentlich das Internet")
        hot("wie wie alt ist eigentlich der der Papst")
        hot("wieviel Uhr haben wir")
        hot("wann ist die Mauer gefallen ähm also die Berliner")
        hot("wer wer hat das noch mal gesagt mit dem ich weiß dass ich nichts weiß")
        hot("die die Dings äh die Hauptstadt von Kanada wie heißt die")
        hot("Wie hoch ist die Zugspitze")
        hot("WIE VIELE EINWOHNER HAT BERLIN")
        hot("wie hoch ist die zugspitze")

        cold("äh was was wollte ich")
        cold("ja genau und ähm ja")
        cold("äh ähm also ja")
        cold("Musik")
        cold("untertitel der amara org community")
        cold("vielen dank fürs zuschauen")
        cold("mhm")
        cold("…")

        maybe("und dann hat er hat er gesagt dass er nicht kommt")
    }

    // Diese Stichprobe entstand, NACHDEM die Regeln für die Szenen oben standen, und wurde einmal
    // ungesehen gemessen. Sie zeigt ehrlicher als der Rest, wie der Vorfilter mit neuen Sätzen umgeht.
    // Was dabei auffiel, ist seither nachgebessert; die Zeilen bleiben als Sicherung stehen.
    szene("Zweite Stichprobe") {
        hot("wie viel Uhr haben wir eigentlich")
        cold("hat jemand ein Ladekabel dabei", ist = Heat.MAYBE, weil = RUNDE)
        hot("wie heißt nochmal dieser Cocktail mit Minze und Limette")
        hot("ich hab gehört dass Glas eigentlich eine Flüssigkeit ist stimmt das")
        maybe("wer von euch war schon mal in Japan")
        maybe("das glaubst du doch selber nicht")
        cold("ach komm hör auf")
        maybe("wie lange seid ihr schon zusammen")
        hot("sag mal wie viele Bundesländer hat Deutschland eigentlich")
        maybe("ich wette das waren die Römer")
        wunsch("der hat doch auch bei Tatort mitgespielt oder")
        maybe("Wahnsinn wie voll das hier ist")
        hot("wie viel Alkohol hat so ein Bier ungefähr")
        maybe("nee lass mal ich muss morgen früh raus")
        cold("was trinkst du da")
        cold("ist das Bier alkoholfrei", ist = Heat.HOT, weil = SITUATIV)
        hot("wer ist eigentlich gerade Bundeskanzler")
        wunsch("ich glaub das Lied ist von Queen")
        cold("woher kennt ihr euch")
        hot("kennt jemand ein gutes Trinkspiel")
        hot("was war nochmal der Unterschied zwischen Bakterien und Viren")
        hot("wie leitet man den Sinus ab")
        hot("ich check nicht wie man auf das Ergebnis kommt")
        hot("können Sie das bitte nochmal erklären")
        maybe("kommt das in der Prüfung dran", ist = Heat.HOT, weil = SITUATIV)
        maybe("wann müssen wir die Hausarbeit abgeben")
        maybe("das Ergebnis ist also minus drei")
        hot("in welchem Jahr ist Goethe gestorben")
        cold("ich versteh die Aufgabe nicht")
        hot("wie viele Elektronen passen in die zweite Schale")
        hot("was ist die Wurzel aus 169")
        hot("wer war der erste Bundeskanzler")
        maybe("die Formel lautet E gleich m c Quadrat")
        maybe("hast du die Lösung von Aufgabe drei")
        hot("wie nennt man ein Vieleck mit zwölf Ecken")
        hot("schreibt man Fahrrad mit einem oder zwei r")
        hot("was heißt because auf Deutsch")
        hot("wie konjugiert man être im Präsens")
        cold("ruhe dahinten")
        hot("wofür ist die Leber eigentlich zuständig")
        hot("bedeutet das dass die Funktion stetig ist")
        hot("gilt das auch für negative Zahlen")
        cold("hallo ja ich bins")
        cold("kannst du mich hören")
        maybe("ich wollte nur kurz Bescheid sagen dass ich später komme")
        maybe("wie spät ist es bei euch gerade")
        cold("wann genau landet ihr denn")
        maybe("wie ist das Wetter bei dir")
        maybe("hast du die Mail schon gelesen")
        hot("weißt du ob die Apotheke am Sonntag aufhat")
        cold("ja ja mach ich")
        cold("dann bis später ciao")
        maybe("wie war nochmal deine neue Adresse")
        hot("sag mal wie heißt nochmal die Hauptstadt von Neuseeland")
        maybe("wo ist jetzt schon wieder der Schlüssel", ist = Heat.HOT, weil = SITUATIV)
        hot("wie ging das noch mal mit der Prozentrechnung")
        cold("was koche ich denn heute", ist = Heat.HOT, weil = SELBST)
        hot("hm was reimt sich auf Haus")
        hot("wie viele Tage sind es noch bis Weihnachten")
        cold("ach du meine Güte")
        cold("wie war nochmal das Passwort", ist = Heat.HOT, weil = SITUATIV)
        hot("muss ich die Nudeln eigentlich abschrecken", ist = Heat.MAYBE, weil = ERLAUBNIS)
        maybe("na super jetzt regnet es auch noch")
        cold("ich glaub ich spinne")
        hot("welcher Tag ist heute eigentlich")
        hot("was ist zwölf mal dreizehn")
        cold("komm schon denk nach")
        hot("wie lange dauert es bis ein Ei hart ist")
        maybe("ich sollte echt mal wieder zum Sport gehen")
        wunsch("ob das wohl giftig ist")
        hot("Mama wie viele Sterne gibt es")
        hot("Papa was macht ein Bürgermeister")
        hot("warum ist das Meer salzig")
        cold("darf ich noch Fernsehen gucken", ist = Heat.MAYBE, weil = ERLAUBNIS)
        cold("wer räumt die Spülmaschine aus", ist = Heat.HOT, weil = SITUATIV)
        maybe("ich hab keine Lust auf Hausaufgaben")
        hot("können Pferde eigentlich im Stehen schlafen")
        hot("wie heißt das Baby von einem Pferd")
        cold("haben wir noch Milch")
        maybe("essen ist fertig kommt ihr bitte")
        hot("wann ist endlich Weihnachten")
        maybe("warum muss ich immer als Erster ins Bett", ist = Heat.HOT, weil = SELBST)
        hot("wo wohnt eigentlich der Weihnachtsmann")
        hot("stimmt es dass Karotten gut für die Augen sind")
        cold("was machen wir am Wochenende")
        cold("ist noch Kuchen da", ist = Heat.MAYBE, weil = OFFEN)
        maybe("Entschuldigung wo ist hier die Toilette", ist = Heat.HOT, weil = SITUATIV)
        cold("wie komme ich zum Hauptbahnhof", ist = Heat.HOT, weil = SITUATIV)
        cold("einmal zahlen bitte")
        cold("haben Sie auch vegetarische Gerichte", ist = Heat.MAYBE, weil = ER_SIE)
        hot("was ist eigentlich der Unterschied zwischen Latte Macchiato und Cappuccino")
        cold("wie viel kostet eine Tageskarte", ist = Heat.HOT, weil = SITUATIV)
        maybe("der Zug hat mal wieder zwanzig Minuten Verspätung")
        maybe("ist das hier der Zug nach Hamburg", ist = Heat.HOT, weil = SITUATIV)
        cold("wie heißt dieser Platz hier", ist = Heat.HOT, weil = SITUATIV)
        hot("wie hoch ist eigentlich der Fernsehturm")
        hot("aus welchem Jahrhundert stammt der Dom")
        maybe("kann man hier mit Karte zahlen", ist = Heat.HOT, weil = SITUATIV)
        hot("what time is it")
        hot("who's the president of France right now")
        hot("how far is the moon from earth")
        hot("what's the difference between a crocodile and an alligator")
        hot("I have no idea how this works")
        cold("can I get the check please", ist = Heat.MAYBE, weil = ERLAUBNIS)
        cold("where are you from")
        cold("what do you mean")
        cold("could you repeat that")
        hot("does anybody know the wifi password")
        cold("that was amazing")
        hot("how much is a pound in kilograms")
        hot("is it true that bats are blind")
        cold("see you tomorrow then")
        cold("why not")
        hot("what's the plural of octopus")
        cold("der Call war mega awkward")
        cold("was meinst du mit fancy")
        hot("was heißt eigentlich awkward auf Deutsch")
        cold("wie bescheuert ist das denn")
        cold("was für eine Frechheit")
        cold("wie ärgerlich")
        cold("was soll der Mist")
        cold("wer hätte das gedacht")
        cold("wie man sich täuschen kann", ist = Heat.MAYBE, weil = NEBENSATZ)
        cold("was es nicht alles gibt", ist = Heat.MAYBE, weil = NEBENSATZ)
        cold("wozu das Ganze", ist = Heat.MAYBE, weil = NEBENSATZ)
        cold("wie dem auch sei")
        cold("wo denkst du hin")
        cold("wann wenn nicht jetzt")
        cold("was heißt hier wir")
        maybe("wie schön ruhig es hier ist")
        hot("ist Gold schwerer als Blei")
        hot("hat Australien eine Hauptstadt die nicht Sydney ist")
        hot("kann ein Flugzeug rückwärts fliegen")
        hot("wird Glas aus Sand gemacht")
        hot("sterben Bienen wirklich nach dem Stechen")
        hot("geht die Sonne im Osten auf")
        maybe("ist halt auch nicht so einfach")
        cold("hat doch super funktioniert")
        cold("wäre schön gewesen")
        cold("kann ich gut verstehen")
        cold("muss jeder selber wissen")
        cold("war ja nur ein Vorschlag")
        cold("ist bei uns genauso", ist = Heat.MAYBE, weil = OFFEN)
        hot("gibt es eigentlich auch weiße Tiger")
        hot("funktioniert ein Induktionsherd mit jedem Topf")
        cold("geht mir auch so")
        cold("hab ich doch gesagt")
        maybe("hast du gewusst dass Oktopusse drei Herzen haben")
        maybe("ist ja wohl nicht dein Ernst")
        cold("könnte man so sagen")
        hot("ich frage mich wie viele Sprachen es auf der Welt gibt")
        hot("weiß das zufällig jemand")
        hot("keine Ahnung ob das stimmt")
        hot("ich bin mir unsicher wie man das ausspricht")
        hot("hab total vergessen wie der Film ausgeht")
        maybe("ich weiß auch nicht was ich davon halten soll")
        maybe("mir ist egal wo wir essen")
        cold("keine Ahnung wo er ist", ist = Heat.MAYBE, weil = ER_SIE)
        maybe("ich wüsste zu gern was da drin ist")
        hot("wie hieß nochmal der Typ der Amerika entdeckt hat")
        wunsch("na dieser eine Schauspieler der bei Matrix mitgespielt hat")
        hot("ich komm nicht auf das Wort")
        hot("wie sagt man noch gleich wenn jemand alles besser weiß")
        maybe("du bist doch auch Vegetarier oder")
        wunsch("der Mount Everest ist doch der höchste Berg oder")
        cold("war gut ne")
        wunsch("das stimmt so nicht oder")
        hot("Wale sind Säugetiere, richtig?")
        cold("geil oder")
        hot("erklär mir bitte nochmal den Unterschied zwischen Strom und Spannung")
        hot("kann mir das mal jemand erklären")
        hot("was genau macht eigentlich ein Notar")
        hot("was bedeutet das Wort Empathie")
        hot("definier mal Glück")
        hot("übersetz das mal ins Spanische")
        cold("ich erklärs dir gleich")
        hot("wie fiele Planeten hat unser Sonnensystem")
        hot("wer hat die mona Lisa gemahlt")
        hot("wahr Einstein eigentlich gut in Mathe")
        hot("wie lang ist die die chinesische Mauer")
        maybe("was kostet ein ein Liter Benzin gerade", ist = Heat.HOT, weil = SITUATIV)
        cold("hm hm ja")
        cold("danke fürs zuhören")
        cold("ja ne is klar")
        cold("okay also ähm wie soll ich sagen")
    }

    // Noch eine frische Stichprobe, nach den Nachbesserungen aus der zweiten geschrieben und ebenfalls
    // einmal ungesehen gemessen.
    szene("Dritte Stichprobe") {
        hot("wie viele Knochen hat ein Mensch")
        hot("wo entspringt eigentlich die Donau")
        hot("ich hab keinen blassen Schimmer was das bedeuten soll")
        hot("gehört Pluto noch zu den Planeten")
        hot("wie viel wiegt ein Liter Milch")
        maybe("meine Güte ist das teuer geworden")
        hot("wann wurde eigentlich das Rad erfunden")
        maybe("sag mal hast du morgen Zeit")
        hot("welcher Fluss fließt durch Paris")
        hot("wie buchstabiert man Portemonnaie")
        maybe("ich hätte da mal eine Frage zum Thema Steuern")
        hot("wachsen Haare nach dem Tod weiter")
        maybe("das hab ich dir doch schon dreimal gesagt")
        hot("was genau versteht man unter einem schwarzen Loch")
        maybe("na komm jetzt stell dich nicht so an")
        hot("können Kühe Treppen runtergehen")
        maybe("wer von euch hat mein Ladegerät")
        hot("wie heißt der höchste Wasserfall der Welt")
        maybe("ich bin mir ziemlich sicher dass das Canberra ist")
        cold("boah keine Ahnung echt")
        hot("wozu braucht man eigentlich einen Blinddarm")
        maybe("lass uns morgen nochmal darüber reden")
        hot("ist der Eiffelturm höher als der Kölner Dom")
        hot("wie war das noch gleich mit den Primzahlen")
        cold("hast recht")
        hot("wer hat eigentlich Amerika entdeckt")
        cold("ich glaub es hackt")
        hot("wie viele Nullen hat eine Milliarde")
        hot("sind Pilze Pflanzen oder Tiere")
        maybe("der Akku ist schon wieder leer")
        hot("wieso ist Bananensaft eigentlich so selten")
        maybe("kannst du mal eben das Fenster zumachen")
        hot("was bedeutet YOLO")
        hot("wie heißt die Mehrzahl von Kaktus")
        cold("wann fängt der Film an", ist = Heat.HOT, weil = SITUATIV)
        maybe("wie lange dauert die Fahrt nach Hamburg ungefähr", ist = Heat.HOT, weil = SITUATIV)
        cold("tja so ist das halt")
        hot("woher weiß man eigentlich wie alt das Universum ist")
        hot("gibt es eigentlich einen Unterschied zwischen Mehl und Stärke")
        cold("ich muss dir was erzählen")
        hot("bei wie viel Grad kocht Wasser auf dem Mount Everest")
        cold("hat dir das Essen geschmeckt")
        maybe("was ist denn das für ein Vogel da draußen", ist = Heat.HOT, weil = SITUATIV)
        hot("wie viele Zeitzonen hat Russland")
        cold("stimmt gar nicht")
        hot("ich wollte schon immer mal wissen wie ein Kühlschrank funktioniert")
        maybe("was würdest du an meiner Stelle machen")
        maybe("weißt du noch wie wir damals in Rom waren")
        hot("ist Schwarz eine Farbe")
        hot("was kommt nach Trillion")
        maybe("könnt ihr mal bitte leiser sein")
        hot("wie tief ist der Marianengraben")
        hot("aus was besteht eigentlich Glas")
        cold("ich find das irgendwie komisch")
        hot("who was the first man on the moon")
        cold("it is what it is")
        cold("what are you talking about")
        hot("how long does it take to boil an egg")
        hot("wait is Sydney not the capital")
        cold("let me think about it")
        hot("what's the square root of 144")
        hot("wie nennt man eigentlich junge Schwäne")
        hot("warum ist die Banane krumm")
        cold("na was hab ich gesagt")
        cold("wer das liest ist doof", ist = Heat.MAYBE, weil = NEBENSATZ)
        hot("woran erkennt man einen Fliegenpilz")
        hot("wie viel Prozent der Erde sind mit Wasser bedeckt")
        cold("was mach ich nur falsch", ist = Heat.HOT, weil = SELBST)
        cold("mir ist langweilig")
        hot("wie lang ist ein Marathon nochmal genau")
        hot("ob es auf dem Mars wohl Leben gibt", ist = Heat.MAYBE, weil = OB_ES)
        maybe("ich hab mal gelesen dass Honig nie schlecht wird")
        hot("wie rum dreht sich eigentlich die Erde")
        hot("hat der Bodensee eigentlich Gezeiten")
        cold("wer zuerst kommt mahlt zuerst", ist = Heat.HOT, weil = REDENSART)
        maybe("kennst du das wenn man ein Wort vergisst")
        hot("wie viel Uhr ist es in Tokio")
        hot("sagt man eigentlich der oder die Butter")
        maybe("also ich würde sagen das ist Quatsch")
        hot("wo liegt nochmal Timbuktu")
    }

    szene("Nachträge: Lücken, die beim Gegenprüfen auffielen") {
        // Ich-Fragen mit einem Verb, das auf keiner Liste steht, waren zuerst COLD.
        hot("wie rechne ich das aus")
        hot("wie berechne ich den Flächeninhalt")
        hot("wo finde ich die Formel")
        hot("wie schlimm ist Zucker")
        hot("schmilzt ein Diamant")
        hot("kann man das sehen")
        hot("stimmt die Rechnung")
        hot("hab ich recht")
        hot("Stimmt das?")
        hot("Lisa, ist das giftig")
        hot("Warum?")
        hot("was für Tiere bellen")
        hot("ist schon Mittag")
        hot("was stimmt denn jetzt")
        hot("lohnt sich das")
        hot("ist ist das giftig")
        hot("ist Schwimmen gesund")
        hot("Hauptstadt von Peru", ist = Heat.MAYBE, weil = STICHWORT)
        hot("Wurzel aus 144", ist = Heat.MAYBE, weil = STICHWORT)
        hot("Definition von Osmose", ist = Heat.MAYBE, weil = STICHWORT)
        hot("Rostet Edelstahl?", ist = Heat.MAYBE, weil = "Zwei-Wort-Frage: zu kurz, um sicher zu sein")
        hot("ist pluto ein planet", ist = Heat.MAYBE, weil = KLEIN)

        wunsch("sind die giftig")
        wunsch("ist mein Puls zu hoch")
        maybe("warum")
        maybe("seit wann")
        maybe("Stimmt, das ist ein guter Punkt")
        maybe("dann ist ein Wal also ein Säugetier")
        maybe("kann sein dass er später kommt")
        // Wortsuche ohne erkennbare Wendung: bleibt Sache der Vorprüfung.
        wunsch("der höchste Berg bei Garmisch na der Dings ich komm gleich drauf")

        cold("weiß man nie")
        cold("kennt man ja")
        cold("merkt man gar nicht")
        cold("hört sich gut an")
        cold("ist was dran")
        cold("Essen ist fertig")
        cold("jetzt ist aber gut")
        cold("so ist das Leben")
        cold("ja stimmt hast recht")
        cold("wer denn sonst")
        cold("wie auch sonst")
        cold("wer hat Hunger")
        cold("wann müssen wir los")
        cold("was machen wir heute Abend")
        cold("hat ein bisschen gedauert")
        cold("kostet ein Vermögen")
        cold("geht nicht anders")
        cold("tut mir leid")
        cold("wo bleibt ihr")
        cold("was ist denn hier los")
        cold("sagt man so", ist = Heat.HOT, weil = REDENSART)
        cold("wer sagt das", ist = Heat.HOT, weil = REDENSART)
        cold("wie lange noch", ist = Heat.HOT, weil = SITUATIV)
    }

    szene("Nachprüfung: Sätze eines zweiten Prüfers") {
        // Verb vorn, Verb aus der Erzählform ("wurde", "kam", "starb"): war vorher COLD.
        hot("wurde Kennedy erschossen")
        hot("starb Mozart jung")
        hot("kam Kolumbus aus Italien")
        hot("schrieb Goethe den Faust")
        hot("lag Troja in der Türkei")
        hot("lebte Kleopatra vor Cäsar")
        hot("erfand Edison die Glühbirne")
        hot("gewann Deutschland 2014 die WM")
        hot("hieß Istanbul früher Konstantinopel")
        hot("konnte Beethoven hören")
        hot("konnten die Wikinger schreiben")
        hot("musste Sokrates sterben")
        hot("hätten die Römer gewonnen")
        hot("wurden die Pyramiden von Sklaven gebaut")
        hot("stammt Sushi aus Japan")
        hot("entsteht Regen aus Wolken")
        hot("wie konnte Hannibal die Alpen überqueren")
        // Verben, die in der Liste fehlten.
        hot("enthält Cola Koffein")
        hot("wiegt Luft etwas")
        hot("atmen Fische Sauerstoff")
        hot("legen Schlangen Eier")
        // Verb auf keiner Liste: nur an der Endung "t" und am Hauptwort dahinter erkannt. War vorher COLD.
        hot("verdunstet Alkohol beim Kochen", ist = Heat.MAYBE, weil = SELTEN)
        hot("rostet Edelstahl im Wasser", ist = Heat.MAYBE, weil = SELTEN)
        hot("grenzt Polen an Russland", ist = Heat.MAYBE, weil = SELTEN)
        hot("fließt der Rhein durch Köln", ist = Heat.MAYBE, weil = SELTEN)
        hot("schadet Kaffee dem Herzen", ist = Heat.MAYBE, weil = SELTEN)
        hot("senkt Knoblauch den Blutdruck", ist = Heat.MAYBE, weil = SELTEN)
        hot("verursacht Zucker Karies", ist = Heat.MAYBE, weil = SELTEN)
        hot("dreht sich die Erde schneller", ist = Heat.MAYBE, weil = SELTEN)
        hot("beginnt der Frühling im März", ist = Heat.MAYBE, weil = SELTEN)
        hot("endet die Sommerzeit im Oktober", ist = Heat.MAYBE, weil = SELTEN)
        hot("reimt sich Haus auf Maus", ist = Heat.MAYBE, weil = SELTEN)
        // Fragewörter, die in der Liste fehlten.
        hot("woraus besteht Messing")
        hot("worum geht es in Faust")
        hot("wogegen hilft Ibuprofen")
        hot("wovor haben Elefanten Angst")
        hot("worunter leidet ein Hypochonder")
        // Fragewort am Satzende, wie man im Gespräch nachhakt. War vorher COLD.
        hot("das bedeutet was", ist = Heat.MAYBE, weil = ENDE)
        hot("lol heißt was", ist = Heat.MAYBE, weil = ENDE)
        hot("Serendipity heißt was", ist = Heat.MAYBE, weil = ENDE)
        hot("Messing besteht aus was", ist = Heat.MAYBE, weil = ENDE)
        hot("lol steht für was", ist = Heat.MAYBE, weil = ENDE)
        hot("und das heißt was genau", ist = Heat.MAYBE, weil = ENDE)
        hot("der hieß wie nochmal", ist = Heat.MAYBE, weil = ENDE)
        hot("das schreibt man wie", ist = Heat.MAYBE, weil = ENDE)
        hot("Mauerfall wann", ist = Heat.MAYBE, weil = ENDE)
        hot("Bundesländer wie viele", ist = Heat.MAYBE, weil = ENDE)
        hot("Einwohner Berlin wie viele", ist = Heat.MAYBE, weil = ENDE)
        hot("Milliarde wie viele Nullen", ist = Heat.MAYBE, weil = ENDE)
        // Rechnen und Übersetzen ohne Verb. War vorher COLD.
        hot("zwölf mal zwölf", ist = Heat.MAYBE, weil = RECHNEN)
        hot("144 durch zwölf", ist = Heat.MAYBE, weil = RECHNEN)
        hot("drei hoch vier", ist = Heat.MAYBE, weil = RECHNEN)
        hot("zwei plus zwei ist", ist = Heat.MAYBE, weil = RECHNEN)
        hot("15 Prozent von 80", ist = Heat.MAYBE, weil = RECHNEN)
        hot("hundert geteilt durch acht", ist = Heat.MAYBE, weil = RECHNEN)
        hot("Schmetterling auf Englisch", ist = Heat.MAYBE, weil = STICHWORT)
        hot("danke auf Japanisch", ist = Heat.MAYBE, weil = STICHWORT)
        hot("und auf Englisch", ist = Heat.MAYBE, weil = STICHWORT)
        hot("anderes Wort für schnell", ist = Heat.MAYBE, weil = STICHWORT)
        hot("ist schwimmen gesund", ist = Heat.MAYBE, weil = KLEIN)

        // Persönliches hinter "keine Ahnung" und "weißt du" war HOT.
        maybe("ich hab vergessen wo mein Schlüssel ist")
        maybe("keine Ahnung wo meine Brille ist")
        maybe("weißt du was mir gestern passiert ist")
        maybe("du glaubst nicht was mir passiert ist")

        // Befehlsform, die keine Bitte um Wissen ist: war HOT.
        cold("rechne nicht damit")
        cold("rechne lieber mit zwei Stunden")
        cold("nenn mich nicht so")
        cold("erklär ich gleich")
        cold("wie lange nicht gesehen")
        // Gegenproben zu den neuen Regeln: Das alles muss COLD bleiben.
        cold("wurde auch Zeit")
        cold("kam dann anders")
        cold("ging ganz gut")
        cold("lag am Wetter")
        cold("stand im Stau")
        cold("Statt Fleisch lieber Fisch")
        cold("Acht Leute kommen")
        cold("Hundert Euro Strafe")
        cold("Hast Glück gehabt")
        cold("Willst Kaffee oder Tee")
        cold("Vorsicht Stufe")
        cold("Zeit der Entscheidung")
        cold("Gut gemacht Lisa")
        cold("Laut Wetterbericht regnet es")
        cold("Guten Morgen Lisa")
        cold("Schönen Abend noch")
        cold("Vielen Dank Herr Müller")
        cold("Macht Spaß")
        cold("Kostet Zeit und Geld")
        cold("Gott sei Dank")
        cold("da ist was")
        cold("ich hab was")
        cold("ich weiß wie")
        cold("ich weiß wo")
        cold("sonst wer")
        cold("egal wann")
        cold("ich war zwei mal da")
        cold("zwei mal Pommes bitte")
        cold("eins zu null")
        cold("drei zu zwei")
        // Der Preis der neuen Regeln: Das wird jetzt geprüft, obwohl nichts zu holen ist.
        cold("Robert Koch war Arzt", ist = Heat.MAYBE, weil = VORNAME)
        cold("Schalt die Musik aus", ist = Heat.MAYBE, weil = VORNAME)
        cold("holt die Kinder ab", ist = Heat.MAYBE, weil = VORNAME)
        cold("zwei mal drei Meter", ist = Heat.MAYBE, weil = RECHNEN)
        cold("der Film ist auf Englisch", ist = Heat.MAYBE, weil = STICHWORT)
        cold("mal sehen wie", ist = Heat.MAYBE, weil = ENDE)
    }

    szene("Anrede mit Namen") {
        hot("Alfred merk dir das Kennzeichen")
        hot("Jarvis")
        hot("hey Jarvis wie wird das Wetter")
        hot("danke Alfred")
        hot("alfret stell mal einen Timer")
        hot("ja genau Jarvis")
    }
}.rows

/**
 * Blinde Flecken: Äußerungen mit Wissenswunsch, die der Vorfilter NICHT sieht und als COLD einstuft.
 * Sie stehen absichtlich nicht in der Tabelle oben, denn dort gilt "COLD ist sicher" ohne Ausnahme.
 * Hier steht, wo diese Zusage endet. Keine dieser Formen lässt sich ohne Zusammenhang von einem
 * gewöhnlichen Bruchstück unterscheiden ("zwei mal die Woche", "Urlaub in Italien").
 * Der Ausweg im Alltag: den Assistenten mit Namen ansprechen; dann ist jede Äußerung HOT.
 * Ein Fragezeichen der Spracherkennung hilft ebenfalls: "Schmilzt Gold?" und "Quaken alle Frösche?"
 * kommen damit mindestens in die Vorprüfung (siehe den Test zu den Schreibweisen).
 */
private val BLIND_SPOTS: List<Pair<String, String>> = listOf(
    "schmilzt Gold" to "Zwei-Wort-Frage ohne Fragezeichen",
    "Photosynthese" to "einzelnes Stichwort",
    "Höhe Zugspitze" to "Stichworte ohne Verb und ohne bekannte Wendung",
    "Wurzel 81" to "Rechenaufgabe ohne Rechenzeichen zwischen zwei Zahlen",
    "zum Beispiel was" to "\"was\" am Satzende zählt nur hinter \"heißt\", \"bedeutet\" oder einer Präposition",
    "quaken alle Frösche" to "Verb der Mehrzahl, das auf keiner Liste steht: Die Endung \"en\" haben zu viele andere Wörter",
    "überwintern Störche in Afrika" to "Verb der Mehrzahl, das auf keiner Liste steht",
    "rostet edelstahl im wasser" to "seltenes Verb und keine Großschreibung: Das Hauptwort dahinter ist nicht zu erkennen",
    "wurde kennedy erschossen" to "Erzählform und keine Großschreibung: Das Hauptwort dahinter ist nicht zu erkennen",
    "ist schon mittag" to "ohne Großschreibung sieht es aus wie \"ist schon okay\"",
    "also ist das jetzt giftig" to "\"also/dann/jetzt\" vor dem Verb ist fast immer eine Aussage (\"dann ist das so\")",
    "Lisa ist das giftig" to "Anrede ohne Komma; mit Komma oder Fragezeichen wird die Frage erkannt",
    "und in Fahrenheit" to "Nachfrage ohne Frageform; nach einer Antwort des Assistenten fängt die Pipeline sie eigens ab",
)

/** Text und ob einer der Namen "Alfred" oder "Jarvis" darin erkannt werden soll. */
private val NAME_ROWS: List<Pair<String, Boolean>> = listOf(
    // Alfred: richtig geschrieben, an jeder Stelle im Satz
    "Alfred, wie spät ist es" to true,
    "alfred wie hoch ist die Zugspitze" to true,
    "ALFRED STOPP" to true,
    "Hey Alfred" to true,
    "Alfred?" to true,
    "Alfred!" to true,
    "sag mal Alfred, wer hat das erfunden" to true,
    "danke Alfred" to true,
    "ok Alfred das reicht" to true,
    "nein Alfred nicht jetzt" to true,
    "wie siehst du das Alfred" to true,
    "mein Alfred hat neulich gesagt dass es regnet" to true,
    // Alfred: so schreibt es die Spracherkennung, wenn sie sich verhört
    "Alfret, was ist ein Derivat" to true,
    "alfret wie hoch ist das" to true,
    "all fred sag mal" to true,
    "Alfried merk dir mal bitte den Termin" to true,
    "Al Fred wie wird das Wetter" to true,
    "Allfred bist du da" to true,
    "Hey Alfreds Meinung dazu" to true,
    "Alfred's Antwort war gut" to true,
    "Alfrett hör mal" to true,
    "Älfred what time is it" to true,
    "Elfred, wie viel Uhr ist es" to true,
    "Alfrid sag mal" to true,
    "Ahlfred hörst du mich" to true,
    // Jarvis: richtig geschrieben
    "Jarvis" to true,
    "JARVIS" to true,
    "jarvis wie spät ist es" to true,
    "Hey Jarvis" to true,
    "hey jarvis mach das Licht an" to true,
    "okay Jarvis danke" to true,
    "Jarvis, stopp!" to true,
    "also Jarvis meinte vorhin" to true,
    "frag doch mal Jarvis" to true,
    "Hey, Jarvis. Wie spät ist es?" to true,
    // Jarvis: Verhörer. Der Name ist englisch, die Erkennung schreibt ihn oft nach deutschem Gehör.
    "Jarwis wie alt ist der Dom" to true,
    "Javis was ist die Hauptstadt von Peru" to true,
    "Dschawis sag mal" to true,
    "hey Tschawis" to true,
    "Tschawis wie wird das Wetter" to true,
    "Dscharwis bist du da" to true,
    "Jarviss stopp" to true,
    "Jarvis's Antwort" to true,
    "Jarvys what's up" to true,
    "Jar vis wie hoch ist der Eiffelturm" to true,
    "hey Dschar wis" to true,
    "Jahvis hörst du mich" to true,
    "Tscharvis" to true,
    "Djarvis stop" to true,
    "Yarvis wie viel ist zwölf mal zwölf" to true,
    "Jervis sag mal" to true,
    "Jarves bist du noch da" to true,

    // Beinahe-Treffer: andere Namen und gewöhnliche Wörter, die ähnlich aussehen oder klingen
    "Albert hat angerufen heute" to false,
    "das ist alles frei" to false,
    "alle freuen sich" to false,
    "alle Fred Feuerstein Folgen" to false,
    "all Freddy Mercury Songs" to false,
    "Travis kommt später" to false,
    "der Service war echt schlecht" to false,
    "das Jarvik Herz ist eine künstliche Pumpe" to false,
    "einmal Pasta Alfredo bitte" to false,
    "Manfred kommt auch" to false,
    "Wilfried hat Geburtstag" to false,
    "Fred kommt später" to false,
    "Alf ist eine alte Serie" to false,
    "Elfriede und Alfons" to false,
    "der Davis Cup" to false,
    "Mavis Beacon" to false,
    "Elvis lebt" to false,
    "Java ist eine Programmiersprache" to false,
    "Javascript nervt" to false,
    "Javier kommt aus Spanien" to false,
    "ein Toyota Yaris" to false,
    "im Januar fahren wir weg" to false,
    "ja wie soll das gehen" to false,
    "ja was soll ich sagen" to false,
    "ja wieso denn nicht" to false,
    "ja wir sind gleich da" to false,
    "ja wisst ihr das nicht" to false,
    "ja viel Spaß euch" to false,
    "jawohl mein Herr" to false,
    "Marvin ist krank" to false,
    "Jasmin hat das gesagt" to false,
    "Tschüss bis morgen" to false,
    "auf Wiedersehen" to false,
    "ein Alfa Romeo" to false,
    "halb vier" to false,
    "alles fertig" to false,
    "elf rote Autos" to false,
    "a friend of mine" to false,
    "al fresco essen" to false,
    "das geht alles viel zu schnell" to false,
    "wir treffen uns am Freitag" to false,
    "" to false,

    // Nachprüfung: Proben eines zweiten Prüfers
    "Dschavis" to true,
    "Tschavis sag mal" to true,
    "Dschaawis" to true,
    "Jaavis" to true,
    "Jarvas wie spät" to true,
    "Jarvis' Antwort" to true,
    "Jarvis-Modus" to true,
    "Alfred-Modus an" to true,
    "Al-Fred" to true,
    "All Fredd" to true,
    "Alfrät" to true,
    "alfred, bist du da?" to true,
    "Paris" to false,
    "Harris" to false,
    "Jonas" to false,
    "Jannis kommt" to false,
    "Darwins Theorie" to false,
    "Jarmusch" to false,
    "Chavez" to false,
    "Jahwe" to false,
    "Jar Jar Binks" to false,
    "charmant" to false,
    "Dschungel" to false,
    "Tschechien" to false,
    "Jeans" to false,
    "Carl fragt" to false,
    "das war fies" to false,
    "ja wies aussieht" to false,
    "ja, Visum brauchst du" to false,
    "jagt wie" to false,
    "Winfried" to false,
    "Gottfried" to false,
    "Siegfried und Roy" to false,
    "Alfredsson spielt Eishockey" to false,
    "Al Freeman" to false,
    "als Fred kam" to false,
    "alles Fred" to false,
    "Alf redet viel" to false,
    "half red" to false,
    "elf Rehe" to false,
    "Altpapier" to false,
)

/**
 * Wo die Namenserkennung irrt, und zwar mit Absicht hingenommen. Der Test prüft, dass es noch so ist;
 * wer die Regeln schärft, streicht die Zeile hier und trägt sie oben ein.
 *  - true: wird als Anrede erkannt, ist aber keine (ein anderer Selbstlaut ist erlaubt, damit "Jervis" passt).
 *  - false: wird NICHT erkannt, obwohl es ein Verhörer sein könnte (ein anderer Mitlaut ist nie erlaubt).
 */
private val NAME_LIMITS: List<Pair<String, Boolean>> = listOf(
    "Javas Speicherverwaltung ist kompliziert" to true,
    "Jawas sind kleine Wesen aus Star Wars" to true,
    "Jovis" to true,
    "Alf rät davon ab" to true,
    // Preis dafür, dass der Verhörer "Alfried, merk dir ..." als Anrede zählt. Das Modell sieht den Satz und schweigt.
    "Alfried Krupp war ein Industrieller" to true,
    "Alfed was meinst du" to false,
    "Charvis sag mal" to false,
    "Schawis hallo" to false,
    "Jarfis hallo" to false,
)

/** Stoppbefehl MIT Namen: wird sofort lokal ausgeführt und geht nicht ans Modell. */
private val STOP_ROWS: List<Pair<String, Boolean>> = listOf(
    "Alfred, stopp" to true,
    "Alfred stop" to true,
    "stopp Alfred" to true,
    "Jarvis stopp" to true,
    "Dschawis stopp" to true,
    "alfret sei bitte still" to true,
    "Jarvis sei still" to true,
    "Alfred Ruhe" to true,
    "Alfred, Ruhe bitte" to true,
    "hey Jarvis hör auf" to true,
    "Alfred hör bitte auf" to true,
    "Alfred hör endlich auf" to true,
    "Jarvis halt" to true,
    "Alfred halt stopp" to true,
    "Alfred halt die Klappe" to true,
    "Alfred das reicht" to true,
    "danke Alfred reicht" to true,
    "Alfred es reicht jetzt" to true,
    "Jarvis leise" to true,
    "Alfred sei leise" to true,
    "Alfred sei ruhig" to true,
    "Alfred schweig" to true,
    "Jarvis aufhören" to true,
    "Jarvis genug" to true,
    "Jarvis Schluss jetzt" to true,
    "Jarvis shut up" to true,
    "Jarvis be quiet" to true,

    // kein Name: Das regelt isBareStop, und nur während der Assistent spricht.
    "stopp mal kurz" to false,
    "Ruhe bitte" to false,
    "Albert stopp" to false,
    "Travis halt mal" to false,
    // Name, aber kein Stoppbefehl
    "Jarvis" to false,
    "Alfred danke" to false,
    "Alfred wie spät ist es" to false,
    "Alfred, wie stoppt man einen Zug am schnellsten ohne Bremse" to false,
    "Alfred, wer hat Stille Nacht komponiert" to false,
    "Jarvis stell die Musik leiser" to false,
    "Alfred ich meine das Stopp Schild da vorne an der Ecke" to false,
    // Stoppwort kommt vor, ist aber nicht als Befehl gemeint
    "Jarvis was heißt quiet auf Deutsch" to false,
    "Jarvis wie leise ist ein Flüstern" to false,
    "Jarvis wann ist Ruhe im Haus" to false,
    "Alfred das ist halt so" to false,
    "Alfred geht halt nicht" to false,
    "Alfred halt mal kurz die Tür auf" to false,
    "Alfred reicht das Geld bis Freitag" to false,
    // Schweigen auf Zeit: Das soll das Modell regeln (MUTE), nicht der lokale Abbruch.
    "Alfred, sei bitte zehn Minuten ruhig, ich muss lernen" to false,
    "Alfred sei mal fünf Minuten still" to false,
    "Jarvis Ruhe für eine Stunde" to false,
    "Jarvis sei bis morgen still" to false,
    "Alfred Ruhe für heute" to false,

    // Nachprüfung: Frage mit dem Verb vorn. Früher als Stoppbefehl verschluckt, obwohl mit Namen gefragt wurde.
    "Alfred, reicht das Geld" to false,
    "Alfred, reicht das" to false,
    "Jarvis, ist das genug" to false,
    "Jarvis, ist das leise genug" to false,
    "Jarvis, war das genug" to false,
    "Alfred, sind zwei genug" to false,
    "Jarvis, ist es im Weltall still" to false,
    // ... während diese Zurufe Befehle bleiben.
    "Alfred, reicht schon" to true,
    "Alfred, reicht jetzt" to true,
    "Alfred, reicht danke" to true,
    "Alfred, ist gut, reicht" to true,
    "Jarvis, es ist genug" to true,
    "hey Jarvis, sei mal ruhig" to true,
    "Alfred sei bitte ruhig" to true,
    "Alfred, Stopp Stopp Stopp" to true,
    "Jarvis, schon gut, stopp" to true,
    "Alfred, nein, stopp" to true,
    "Alfred, nicht aufhören" to false,
    "Jarvis, don't stop" to false,
    "Alfred, mach weiter" to false,
    "Jarvis, wie schreibt man Stopp" to false,
    "Alfred, wann hört der Regen auf" to false,
)

/**
 * Wo der Stoppbefehl irrt und es hingenommen wird: Der lokale Abbruch greift, obwohl etwas anderes gemeint
 * war. Der Schaden ist klein (die laufende Antwort bricht ab), aber die Bitte erreicht das Modell nicht.
 */
private val STOP_LIMITS: List<String> = listOf(
    "Alfred, sei kurz still",        // eigentlich eine Bitte um Stummschaltung; "kurz" steht auch in "stopp mal kurz"
    "Jarvis, stopp die Musik",       // meint nicht den Assistenten
    "Alfred, sprich leise",          // Bitte um Lautstärke, kein Abbruch
)

/** Bloßes Stopp OHNE Namen (bis drei Wörter). Zählt nur, während der Assistent spricht. */
private val BARE_STOP_ROWS: List<Pair<String, Boolean>> = listOf(
    "stopp" to true,
    "Stopp!" to true,
    "stop" to true,
    "stopp bitte" to true,
    "halt" to true,
    "halt stopp" to true,
    "Ruhe" to true,
    "danke" to true,
    "danke reicht" to true,
    "okay danke" to true,
    "reicht" to true,
    "das reicht" to true,
    "sei still" to true,
    "sei ruhig" to true,
    "leise" to true,
    "quiet" to true,
    "hör auf" to true,
    "aufhören" to true,
    "genug" to true,
    "Schluss jetzt" to true,
    "pst" to true,
    "shut up" to true,

    "" to false,
    "ja genau" to false,
    "okay" to false,
    "mhm" to false,
    "weiter" to false,
    "ach so" to false,
    "na gut" to false,
    "wie bitte" to false,
    "ist halt so" to false,
    "geht halt nicht" to false,
    "Stille Nacht" to false,
    "ruhig Blut" to false,
    "noch nicht genug" to false,
    "nicht aufhören" to false,
    "stoppt der Bus hier" to false,
    "das ist halt nicht so einfach" to false,
    "stopp ich bin noch nicht fertig" to false,

    // Nachprüfung
    "reicht schon" to true,
    "reicht danke" to true,
    "ist gut danke" to true,
    "okay reicht" to true,
    "genug jetzt" to true,
    "es reicht" to true,
    "halt mal" to true,
    "reicht das" to false,
    "ist das genug" to false,
    "das reicht nicht" to false,
    "stopp nicht" to false,
    "mach weiter" to false,
    "vielen Dank" to false,
)

/**
 * Hat ein Mensch inhaltlich etwas gesagt? Wenn ja, schweigt der Assistent (jemand antwortet selbst).
 * Füllsel und Ratlosigkeit zählen nicht: Wer "keine Ahnung" sagt, freut sich über Hilfe.
 */
private val CONTENT_ROWS: List<Pair<String, Boolean>> = listOf(
    "das sind ungefähr zweitausendsiebenhundert Meter glaube ich" to true,
    "ich glaube das war 1989" to true,
    "die Zugspitze" to true,
    "knapp dreitausend Meter" to true,
    "Canberra glaub ich" to true,
    "nee das war Edison" to true,
    "wie hoch ist die Zugspitze" to true,
    "das weiß ich das war Edison" to true,
    "keine Ahnung ich glaub das waren so ungefähr dreitausend Meter" to true,
    "ich weiß es nicht aber Lisa hat gesagt es wären zweitausendneunhundert Meter" to true,
    "ich weiß dass das nicht stimmt" to true,
    "stimmt nicht das war Tesla" to true,

    "" to false,
    "ja" to false,
    "okay" to false,
    "äh" to false,
    "ja genau" to false,
    "mhm okay" to false,
    "Moment warte" to false,
    "ähm keine Ahnung" to false,
    "hm gute Frage" to false,
    "weiß nicht" to false,
    "weiß ich nicht" to false,
    "ich weiß es nicht" to false,
    "das weiß ich auch nicht" to false,
    "keine Ahnung ehrlich gesagt" to false,
    "da bin ich überfragt" to false,
    "puh gute Frage da muss ich überlegen" to false,
    "hm weiß ich jetzt auch nicht so genau" to false,
    "kein Plan" to false,
    "no idea" to false,
    "I don't know" to false,

    // Nachprüfung: Ratlosigkeit, die vorher als Antwort zählte (der Assistent schwieg dann).
    "ich weiß es wirklich nicht" to false,
    "das weiß ich leider auch nicht" to false,
    "kann ich nicht sagen" to false,
    "kann ich dir nicht sagen" to false,
    "muss ich googeln" to false,
    "müsste ich nachschauen" to false,
    "keinen blassen Schimmer" to false,
    "da fragst du mich was" to false,
    "hmm let me think" to false,
    // Kurze, unsichere Antwort hinter "keine Ahnung": gewollt kein Inhalt. Der Assistent hilft lieber
    // einmal zu viel, als zu schweigen, wenn jemand ratlos ist.
    "keine Ahnung, vielleicht 1989" to false,
    "weiß nicht, vielleicht Edison" to false,
    // Ein einzelnes Wort zählt nicht als Inhalt (das war schon immer so): Der Assistent wiederholt dann
    // womöglich, was eben jemand gesagt hat.
    "Canberra" to false,
    "1989" to false,
    "das war Edison" to true,
    "nee, Sydney" to true,
    "doch, das stimmt" to true,
    "so um die dreitausend" to true,
    "ich bin mir nicht sicher aber ich glaube Canberra" to true,
    "gute Frage, das waren die alten Römer glaube ich" to true,
)
