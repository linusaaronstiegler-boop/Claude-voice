package app.claudevoice.core

/**
 * Zerlegt den Token-Strom von Claude in sprechbare Sätze.
 * Warum: Die Sprachausgabe soll mit dem ersten Satz beginnen, während Claude den zweiten noch schreibt.
 * Ein Satz endet an . ! ? … gefolgt von Leerraum. Sehr kurze Stücke ("z. B.") werden nicht abgetrennt.
 */
class SentenceChunker(private val minLength: Int = 25) {
    private val pending = StringBuilder()

    fun feed(delta: String): List<String> {
        pending.append(delta)
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < pending.length - 1) {
            val c = pending[i]
            val endsSentence = (c == '.' || c == '!' || c == '?' || c == '…') && pending[i + 1].isWhitespace()
            if ((endsSentence && i + 1 - start >= minLength) || c == '\n') {
                val s = pending.substring(start, i + 1).trim()
                if (s.isNotEmpty()) out += s
                start = i + 1
            }
            i++
        }
        pending.delete(0, start)
        return out
    }

    /** Rest am Ende des Stroms. */
    fun flush(): String? {
        val s = pending.toString().trim()
        pending.setLength(0)
        return s.ifEmpty { null }
    }
}
