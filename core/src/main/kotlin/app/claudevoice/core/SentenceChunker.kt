package app.claudevoice.core

/**
 * Zerlegt den Token-Strom von Claude in sprechbare Sätze.
 * Warum: Die Sprachausgabe soll mit dem ersten Satz beginnen, während Claude den zweiten noch schreibt.
 *
 * Ein Satz endet an . ! ? … gefolgt von Leerraum. Zwei Feinheiten:
 *
 * 1. Der erste Satz geht sofort hinaus, egal wie kurz. Die Antworten beginnen mit dem Ergebnis
 *    ("Ja.", "Nein, Sir."), und genau dieses Stück soll nicht warten, bis der zweite Satz fertig ist.
 *    Spätere Sätze werden erst ab [minLength] Zeichen abgetrennt: größere Stücke klingen flüssiger,
 *    und sobald schon gesprochen wird, kostet das Warten keine Zeit mehr.
 *
 * 2. Ein Punkt nach einer Abkürzung ("z. B.", "Dr."), einem einzelnen Buchstaben oder einer Zahl
 *    ("am 3. Mai") ist kein Satzende. Lieber einmal zwei Sätze zusammen sprechen als mitten in
 *    "am dritten Mai" eine Pause setzen.
 */
class SentenceChunker(private val minLength: Int = 25) {
    private val pending = StringBuilder()
    private var emitted = 0

    fun feed(delta: String): List<String> {
        pending.append(delta)
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < pending.length - 1) {
            val c = pending[i]
            val endsSentence = (c == '.' || c == '!' || c == '?' || c == '…') &&
                pending[i + 1].isWhitespace() && !(c == '.' && isAbbreviationBefore(i, start))
            val longEnough = emitted == 0 || i + 1 - start >= minLength
            if ((endsSentence && longEnough) || c == '\n') {
                val s = pending.substring(start, i + 1).trim()
                if (s.isNotEmpty()) {
                    out += s
                    emitted++
                }
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

    /** Steht direkt vor dem Punkt an Stelle [dot] ein Wort, nach dem ein Punkt keinen Satz beendet? */
    private fun isAbbreviationBefore(dot: Int, start: Int): Boolean {
        var from = dot
        while (from > start && pending[from - 1].isLetterOrDigit()) from--
        if (from == dot) return false // kein Wort davor, etwa nach einer Klammer
        val token = pending.substring(from, dot)
        if (token.length == 1) return true // "z. B.", "u. a.", Initialen
        if (token.all { it.isDigit() }) return true // "3. Mai", "1. FC"
        return token.lowercase() in ABBREVIATIONS
    }

    private companion object {
        val ABBREVIATIONS = setOf(
            "ca", "bzw", "dr", "prof", "nr", "st", "str", "hr", "fr", "mr", "mrs", "ms", "vs", "ggf", "evtl",
            "inkl", "zzgl", "sog", "bzgl", "abs", "art", "vgl", "tel", "mio", "mrd", "jh", "chr", "approx", "no",
        )
    }
}
