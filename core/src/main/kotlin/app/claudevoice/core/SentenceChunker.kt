package app.claudevoice.core

/**
 * Zerlegt den Token-Strom von Claude in sprechbare Sätze.
 * Warum: Die Sprachausgabe soll mit dem ersten Satz beginnen, während Claude den zweiten noch schreibt.
 *
 * Ein Satz endet an . ! ? … gefolgt von Leerraum. Vier Feinheiten:
 *
 * 1. Der erste Satz geht sofort hinaus, egal wie kurz. Die Antworten beginnen mit dem Ergebnis
 *    ("Ja.", "Nein, Sir."), und genau dieses Stück soll nicht warten, bis der zweite Satz fertig ist.
 *    Spätere Sätze werden erst ab [minLength] Zeichen abgetrennt: größere Stücke klingen flüssiger,
 *    und sobald schon gesprochen wird, kostet das Warten keine Zeit mehr.
 *
 * 2. Ein Punkt nach einer Abkürzung ("z. B.", "Dr."), einem einzelnen Buchstaben oder einer kurzen Zahl
 *    ("am 3. Mai") ist kein Satzende. Lieber einmal zwei Sätze zusammen sprechen als mitten in
 *    "am dritten Mai" eine Pause setzen. Die Regel dafür steht in [SpokenText.dotEndsSentence], weil
 *    dieselbe Stelle später auch die Zahlen ausschreibt und beide dasselbe unter einem Satz verstehen müssen.
 *    Manchmal entscheidet dort erst das nächste Wort ("Plan B. Der ..." gegen "D. B. Cooper"); solange es
 *    noch nicht da ist, heißt die Antwort nein, und beim nächsten Stück wird neu gefragt.
 *
 * 3. Schließende Anführungszeichen und Klammern hinter dem Satzzeichen gehören noch zum Satz
 *    (... ein Berliner." Das war ...). Getrennt wird dort aber nur, wenn es danach groß weitergeht;
 *    in 'Er rief "Halt!" und rannte los.' läuft der Satz weiter, und eine Pause wäre falsch.
 *
 * 4. Ein Stück ohne Buchstaben und ohne Ziffer ist kein Satz: das "```" am Ende einer Antwort, die das
 *    Modell in einen Code-Block gesetzt hat, oder ein einzelnes "!". Zu sprechen gibt es daran nichts,
 *    also geht es nicht hinaus. Es zählt auch nicht als erster Satz, sonst nähme es dem echten ersten
 *    Satz den Vortritt aus Feinheit 1.
 *
 * Bewusst gibt es keine Obergrenze für einen Satz ohne Satzende: Ein sehr langer erster Satz wartet, bis
 * er fertig ist. Ihn vorher an einem Komma zu teilen, brächte den ersten Ton etwas früher, kostet aber
 * mehr, als es bringt. Die Pipeline zählt jedes Stück als einen Satz, und ihr Längendeckel lässt nur den
 * ersten Satz ungeprüft durch; die zweite Hälfte eines geteilten Satzes fiele ihm zum Opfer, die Antwort
 * bräche am Komma ab. Außerdem ist jede Naht mitten im Satz zu hören. Wer die Obergrenze einführen will,
 * muss zuerst dem Deckel beibringen, dass ein angefangener Satz zu Ende gesprochen wird.
 */
class SentenceChunker(private val minLength: Int = 25) {
    private val pending = StringBuilder()
    private var emitted = 0

    fun feed(delta: String): List<String> {
        pending.append(delta)
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < pending.length) {
            val c = pending[i]
            var end = -1 // Stelle hinter dem Satz, wenn hier einer endet
            if (c == '\n') {
                end = i + 1
            } else if (c in SENTENCE_END) {
                var after = i + 1
                while (after < pending.length && pending[after] in CLOSERS) after++
                // Was hinter dem Satzzeichen kommt, ist noch nicht da: beim nächsten Stück weitersehen.
                if (after == pending.length) break
                val longEnough = emitted == 0 || after - start >= minLength
                if (pending[after].isWhitespace() && longEnough && (c != '.' || SpokenText.dotEndsSentence(pending, i))) {
                    if (after == i + 1) {
                        end = after
                    } else {
                        // Hinter Anführungszeichen oder Klammer entscheidet das nächste Wort (Feinheit 3).
                        var next = after
                        while (next < pending.length && pending[next].isWhitespace() && pending[next] != '\n') next++
                        if (next == pending.length) break
                        if (!pending[next].isLowerCase()) end = after
                    }
                }
            }
            if (end >= 0) {
                speakable(pending.substring(start, end))?.let {
                    out += it
                    emitted++
                }
                start = end
                i = end
            } else {
                i++
            }
        }
        pending.delete(0, start)
        return out
    }

    /** Rest am Ende des Stroms. */
    fun flush(): String? {
        val s = speakable(pending.toString())
        pending.setLength(0)
        return s
    }

    /** Das Stück ohne Leerraum am Rand, oder null, wenn es nichts zu sprechen enthält (Feinheit 4). */
    private fun speakable(piece: String): String? = piece.trim().takeIf { s -> s.any { it.isLetterOrDigit() } }

    private companion object {
        const val SENTENCE_END = ".!?…"
        const val CLOSERS = "\"“”«»‘’')]"
    }
}
