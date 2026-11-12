package app.claudevoice.core

/** Ergebnis des Ordners: Szenenbeschreibung und die Zeilen, die nicht zum Gespräch des Trägers gehören. */
data class Tidy(val scene: String?, val aside: Set<Long>)

/**
 * Die drei Denk-Aufgaben, jeweils ein API-Aufruf:
 *  - [respond]: entscheiden UND antworten in einem gestreamten Aufruf (starkes Modell). Das erste Wort
 *    der Ausgabe ist die Entscheidung. Ein Aufruf statt zwei spart einen ganzen Netz-Umlauf.
 *  - [pregate]: billige Vorprüfung für Äußerungen, die nicht wie eine Frage aussehen.
 *  - [tidy]: sortiert im Hintergrund, was zum Gespräch des Trägers gehört (nicht zeitkritisch).
 */
class Brain(private val claude: ClaudeApi, profile: Profile) {
    val system: String = Persona.systemPrompt(profile)

    /** Blockiert, bis die Antwort vollständig ist; [onText] bekommt den Text stückweise. */
    fun respond(turn: Turn, cancel: CancelToken = CancelToken(), onText: (String) -> Unit) =
        claude.respond(system, Persona.userMessage(turn), cancel, onText)

    /** Beim Start aufrufen: lädt Regeln und Gedächtnis vorab in den Zwischenspeicher der API. */
    fun warmUp() = claude.warmUp(system)

    fun pregate(turn: Turn): Boolean =
        claude.quick(Persona.PREGATE_SYSTEM, Persona.userMessage(turn), 10).trim().uppercase().startsWith("JA")

    fun tidy(lines: List<Line>, previousScene: String?, mic: MicMode, nowMs: Long): Tidy {
        if (lines.isEmpty()) return Tidy(previousScene, emptySet())
        val reply = claude.quick(Persona.TIDY_SYSTEM, Persona.tidyMessage(lines, previousScene, mic, nowMs), 200)
        return parseTidy(reply, lines)
    }

    companion object {
        fun parseTidy(reply: String, lines: List<Line>): Tidy {
            var scene: String? = null
            val aside = HashSet<Long>()
            for (raw in reply.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("SZENE:", ignoreCase = true) ->
                        scene = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                    line.startsWith("ABSEITS:", ignoreCase = true) ->
                        Regex("\\d+").findAll(line.substringAfter(':')).forEach { m ->
                            // Das Modell nennt Zeilennummern (1-basiert); wir übersetzen sie in Zeilen-IDs.
                            lines.getOrNull(m.value.toInt() - 1)?.let { aside += it.id }
                        }
                }
            }
            return Tidy(scene, aside)
        }
    }
}
