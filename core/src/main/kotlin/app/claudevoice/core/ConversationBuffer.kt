package app.claudevoice.core

/**
 * Rollendes Fenster über das Gespräch: nur die letzten Minuten, nur im Arbeitsspeicher.
 * Claude braucht den Verlauf, um Bezüge aufzulösen ("und wie hoch ist der?"),
 * aber nicht das ganze Gespräch, das würde nur Tokens und Latenz kosten.
 */
class ConversationBuffer(
    private val windowMs: Long = 120_000,
    private val maxChars: Int = 4_000,
) {
    private data class Line(val atMs: Long, val text: String)
    private val lines = ArrayDeque<Line>()

    @Synchronized
    fun add(text: String, nowMs: Long) {
        val t = text.trim()
        if (t.isEmpty()) return
        lines.addLast(Line(nowMs, t))
        prune(nowMs)
    }

    @Synchronized
    fun render(nowMs: Long): String {
        prune(nowMs)
        return lines.joinToString("\n") { it.text }
    }

    private fun prune(nowMs: Long) {
        while (lines.isNotEmpty() && nowMs - lines.first().atMs > windowMs) lines.removeFirst()
        // Die neueste Zeile bleibt immer erhalten, auch wenn sie allein das Limit sprengt.
        while (lines.size > 1 && lines.sumOf { it.text.length } > maxChars) lines.removeFirst()
    }
}
