package app.claudevoice.core

/** Ergebnis des Ordners: Szenenbeschreibung und die Zeilen, die nicht zum Gespräch des Trägers gehören. */
data class Tidy(val scene: String?, val aside: Set<Long>)

/**
 * Die drei Denk-Aufgaben, jeweils ein API-Aufruf:
 *  - [respond]: entscheiden UND antworten in einem gestreamten Aufruf (starkes Modell). Das erste Wort
 *    der Ausgabe ist die Entscheidung. Ein Aufruf statt zwei spart einen ganzen Netz-Umlauf.
 *  - [pregate]: billige Vorprüfung für Äußerungen, die nicht wie eine Frage aussehen. Sie läuft im selben
 *    Auftrag direkt vor [respond] und hat eine eigene kurze Frist, damit sie nie eine Antwort aufhält.
 *  - [tidy]: sortiert im Hintergrund, was zum Gespräch des Trägers gehört (nicht zeitkritisch).
 */
class Brain(private val claude: ClaudeApi, profile: Profile) {
    val system: String = Persona.systemPrompt(profile)

    /** Die Rufnamen des Assistenten. Der Ordner braucht sie, um "Alfred, ..." als Anrede an ihn zu erkennen. */
    private val names: List<String> = Persona.calledNames(profile)

    /** So heißen seine eigenen Zeilen in der Mitschrift; dasselbe Wort steht in der Legende des System-Prompts. */
    private val selfLabel: String = Persona.selfLabel(profile)

    /** Blockiert, bis die Antwort vollständig ist; [onText] bekommt den Text stückweise. */
    fun respond(turn: Turn, cancel: CancelToken = CancelToken(), onText: (String) -> Unit) =
        claude.respond(system, Persona.userMessage(turn, selfLabel), cancel, onText)

    /** Beim Start aufrufen: lädt Regeln und Gedächtnis vorab in den Zwischenspeicher der API. */
    fun warmUp() = claude.warmUp(system)

    /** Kurze Frist, kein zweiter Versuch (siehe [ClaudeApi.gate]): Scheitert sie, wirft sie eine Ausnahme. */
    fun pregate(turn: Turn): Boolean =
        claude.gate(Persona.PREGATE_SYSTEM, Persona.userMessage(turn, selfLabel), 10).trim().uppercase().startsWith("JA")

    fun tidy(lines: List<Line>, previousScene: String?, mic: MicMode, nowMs: Long): Tidy {
        if (lines.isEmpty()) return Tidy(previousScene, emptySet())
        val reply = claude.quick(Persona.TIDY_SYSTEM, Persona.tidyMessage(lines, previousScene, mic, nowMs, names), 200)
        return parseTidy(reply, lines)
    }

    companion object {
        /**
         * Eine Zeilennummer oder ein Bereich ("3-9", "3 bis 9") in der ABSEITS-Zeile.
         *
         * Die Zahl muss für sich stehen: Direkt davor und dahinter darf weder Buchstabe noch Ziffer sein.
         * Sonst würde die 2 aus dem Sprecher-Kürzel "S2" als Zeile 2 gelesen. Der zweite Teil ist freiwillig
         * und fängt das Ende eines Bereichs ein, mit Bindestrich, Gedankenstrich oder "bis".
         */
        private val ASIDE_NUMBER = Regex("(?<![\\p{L}\\d])(\\d+)(?:\\s*(?:-|–|—|bis)\\s*(\\d+))?(?![\\p{L}\\d])")

        fun parseTidy(reply: String, lines: List<Line>): Tidy {
            var scene: String? = null
            val aside = HashSet<Long>()
            for (raw in reply.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("SZENE:", ignoreCase = true) ->
                        scene = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                    line.startsWith("ABSEITS:", ignoreCase = true) ->
                        for (m in ASIDE_NUMBER.findAll(line.substringAfter(':'))) {
                            // toIntOrNull statt toInt: Eine unsinnig lange Ziffernfolge darf nicht das ganze
                            // Ergebnis des Ordners (samt Szene) mit einer Ausnahme zunichtemachen.
                            val first = m.groupValues[1].toIntOrNull() ?: continue
                            // Ein Bereich meint alle Zeilen dazwischen. Gerade bei einem laufenden Fernseher
                            // schreibt das Modell gern "3-9"; nur die Ränder zu markieren, wäre falsch.
                            val last = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: Int.MAX_VALUE } ?: first
                            // Das Modell nennt Zeilennummern (1-basiert); wir übersetzen sie in Zeilen-IDs und
                            // bleiben dabei innerhalb der Zeilen, die es gibt ("8-100000" endet bei der letzten).
                            for (n in maxOf(first, 1)..minOf(last, lines.size)) aside += lines[n - 1].id
                        }
                }
            }
            return Tidy(scene, aside)
        }
    }
}
