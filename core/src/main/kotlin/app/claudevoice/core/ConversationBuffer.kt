package app.claudevoice.core

/** Eine Zeile der Mitschrift mit der Schätzung, wer gesprochen hat. */
data class Line(val atMs: Long, val voice: Voice, val text: String)

/**
 * Rollendes Fenster über das Gespräch: nur die letzten Minuten, nur im Arbeitsspeicher.
 * Claude braucht den Verlauf, um Bezüge aufzulösen ("und wie hoch ist der?") und um zu erkennen,
 * wer mit wem worüber spricht. Das ganze Gespräch würde nur Tokens und Latenz kosten.
 */
class ConversationBuffer(
    private val windowMs: Long = 120_000,
    private val maxChars: Int = 4_000,
) {
    private val lines = ArrayDeque<Line>()

    @Synchronized
    fun add(text: String, voice: Voice, nowMs: Long) {
        val t = text.trim()
        if (t.isEmpty()) return
        lines.addLast(Line(nowMs, voice, t))
        prune(nowMs)
    }

    @Synchronized
    fun snapshot(nowMs: Long): List<Line> {
        prune(nowMs)
        return lines.toList()
    }

    private fun prune(nowMs: Long) {
        while (lines.isNotEmpty() && nowMs - lines.first().atMs > windowMs) lines.removeFirst()
        // Die neueste Zeile bleibt immer erhalten, auch wenn sie allein das Limit sprengt.
        while (lines.size > 1 && lines.sumOf { it.text.length } > maxChars) lines.removeFirst()
    }

    companion object {
        /**
         * Schreibt die Mitschrift so auf, wie das Modell sie liest: pro Zeile das Alter und der Sprecher,
         * die Äußerung, um die es geht, zuletzt und mit ">>" markiert.
         */
        fun render(history: List<Line>, trigger: Line): String = buildString {
            for (line in history) {
                val ago = ((trigger.atMs - line.atMs) / 1000).coerceAtLeast(0)
                append("[vor ").append(ago).append(" s] ").append(line.voice.label).append(": ").append(line.text).append('\n')
            }
            append(">> [jetzt] ").append(trigger.voice.label).append(": ").append(trigger.text)
        }
    }
}
