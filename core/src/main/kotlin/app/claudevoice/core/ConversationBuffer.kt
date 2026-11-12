package app.claudevoice.core

/**
 * Eine Zeile der Mitschrift.
 * [voice] ist die akustische Schätzung (Lautstärke, Stimmlage), [speaker] ein Sprecher-Kürzel aus der
 * Diarisierung ("S1", "S2", ...), [aside] die Einschätzung des Ordners: gehört nicht zum Gespräch des Trägers.
 */
data class Line(
    val id: Long,
    val atMs: Long,
    val voice: Voice,
    val text: String,
    val speaker: String? = null,
    val aside: Boolean = false,
)

/**
 * Rollendes Fenster über das Gespräch: nur die letzten Minuten, nur im Arbeitsspeicher.
 * Claude braucht den Verlauf, um Bezüge aufzulösen ("und wie hoch ist der?") und um zu erkennen,
 * wer mit wem worüber spricht. Das ganze Gespräch würde nur Tokens und Latenz kosten.
 */
class ConversationBuffer(
    private val windowMs: Long = 180_000,
    private val maxChars: Int = 5_000,
) {
    private val lines = ArrayList<Line>()
    private var nextId = 1L

    @Synchronized
    fun add(text: String, voice: Voice, nowMs: Long, speaker: String? = null): Line? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val line = Line(nextId++, nowMs, voice, t, speaker)
        lines += line
        prune(nowMs)
        return line
    }

    @Synchronized
    fun snapshot(nowMs: Long): List<Line> {
        prune(nowMs)
        return lines.toList()
    }

    /** Nachträgliche Korrektur einer Zeile, z. B. wenn die Diarisierung den Sprecher nachliefert. */
    @Synchronized
    fun relabel(id: Long, voice: Voice? = null, speaker: String? = null, text: String? = null) {
        val i = lines.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = lines[i]
        lines[i] = old.copy(voice = voice ?: old.voice, speaker = speaker ?: old.speaker, text = text ?: old.text)
    }

    @Synchronized
    fun renameSpeaker(from: String, to: String) {
        for (i in lines.indices) if (lines[i].speaker == from) lines[i] = lines[i].copy(speaker = to)
    }

    /**
     * Ergebnis des Ordners eintragen: von den betrachteten Zeilen [considered] gelten genau [aside] als abseits.
     * Zeilen, die er nicht gesehen hat, bleiben unverändert.
     */
    @Synchronized
    fun markAside(aside: Set<Long>, considered: Set<Long>) {
        for (i in lines.indices) {
            val l = lines[i]
            if (l.id in considered) lines[i] = l.copy(aside = l.id in aside)
        }
    }

    private fun prune(nowMs: Long) {
        lines.removeAll { nowMs - it.atMs > windowMs }
        // Wird es zu lang, fliegt zuerst, was ohnehin als abseits gilt; danach das Älteste.
        // Die neueste Zeile bleibt immer erhalten, auch wenn sie allein das Limit sprengt.
        while (lines.size > 1 && lines.sumOf { it.text.length } > maxChars) {
            val i = lines.dropLast(1).indexOfFirst { it.aside }
            lines.removeAt(if (i >= 0) i else 0)
        }
    }

    companion object {
        /**
         * Schreibt die Mitschrift so auf, wie das Modell sie liest: pro Zeile Alter, Stimmen-Schätzung und
         * Sprecher-Kürzel; die Äußerung, um die es geht, zuletzt und mit ">>" markiert.
         */
        fun render(history: List<Line>, trigger: Line): String = buildString {
            for (line in history) {
                val ago = ((trigger.atMs - line.atMs) / 1000).coerceAtLeast(0)
                append("[vor ").append(ago).append(" s] ")
                if (line.aside) append("(abseits) ")
                append(who(line)).append(": ").append(line.text).append('\n')
            }
            append(">> [jetzt] ").append(who(trigger)).append(": ").append(trigger.text)
        }

        /** Nummerierte Fassung für den Ordner. */
        fun renderNumbered(lines: List<Line>, nowMs: Long): String = buildString {
            lines.forEachIndexed { i, line ->
                val ago = ((nowMs - line.atMs) / 1000).coerceAtLeast(0)
                append(i + 1).append(". [vor ").append(ago).append(" s] ").append(who(line)).append(": ").append(line.text).append('\n')
            }
        }.trimEnd()

        fun who(line: Line): String = if (line.speaker != null) "${line.voice.label} ${line.speaker}" else line.voice.label
    }
}
