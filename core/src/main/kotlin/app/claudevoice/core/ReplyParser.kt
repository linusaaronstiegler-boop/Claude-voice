package app.claudevoice.core

/** Die Entscheidung des Modells, sie steht in der ersten Zeile seiner Antwort. */
sealed interface Decision {
    /** Nichts sagen. [reason] ist ein kurzes Stichwort (z. B. "hintergrund", "sozial") für Anzeige und Auswertung. */
    data class Skip(val reason: String) : Decision
    /** Sprechen. [length] ist die vom Modell gewählte Länge ("kurz", "mittel", "lang"). */
    data class Say(val length: String) : Decision
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
 */
class ReplyParser(
    private val onDecision: (Decision) -> Unit,
    private val onSpeech: (String) -> Unit,
    private val onDirective: (Directive) -> Unit,
) {
    private enum class State { HEADER, LINE_START, SPEECH, DIRECTIVE, DONE }

    private var state = State.HEADER
    private val held = StringBuilder()

    var decision: Decision? = null
        private set

    fun feed(delta: String) {
        for (c in delta) step(c)
    }

    /** Am Ende des Stroms aufrufen: verarbeitet, was noch zurückgehalten wurde. */
    fun finish() {
        when (state) {
            State.HEADER -> header(held.toString(), endOfStream = true)
            State.LINE_START -> if (held.isNotEmpty()) onSpeech(held.toString())
            State.DIRECTIVE -> directive(held.toString())
            else -> {}
        }
        held.setLength(0)
        state = State.DONE
    }

    private fun step(c: Char) {
        when (state) {
            State.HEADER -> if (c == '\n') header(held.toString(), endOfStream = false) else held.append(c)
            State.LINE_START -> {
                held.append(c)
                val soFar = held.toString()
                when {
                    c == '\n' -> { onSpeech(soFar); held.setLength(0) }
                    KEYWORDS.any { it == soFar } -> state = State.DIRECTIVE
                    // Noch unklar, ob das eine Anweisung wird: zurückhalten statt vorlesen.
                    KEYWORDS.any { it.startsWith(soFar) } -> {}
                    else -> { onSpeech(soFar); held.setLength(0); state = State.SPEECH }
                }
            }
            State.SPEECH -> {
                onSpeech(c.toString())
                if (c == '\n') state = State.LINE_START
            }
            State.DIRECTIVE -> if (c == '\n') {
                directive(held.toString()); held.setLength(0); state = State.LINE_START
            } else held.append(c)
            State.DONE -> {}
        }
    }

    private fun header(line: String, endOfStream: Boolean) {
        held.setLength(0)
        val t = line.trim()
        val upper = t.uppercase()
        when {
            upper.startsWith("SKIP") || t.isEmpty() && endOfStream -> {
                decide(Decision.Skip(t.drop(4).trim().lowercase()))
                state = State.DONE
            }
            upper.startsWith("SAY") -> {
                decide(Decision.Say(t.drop(3).trim().lowercase()))
                state = State.LINE_START
            }
            t.isEmpty() -> {} // führende Leerzeile: weiter auf die Kopfzeile warten
            else -> {
                // Das Modell hat das Protokoll verfehlt und direkt geantwortet. Lieber sprechen als verschlucken.
                decide(Decision.Say(""))
                state = State.LINE_START
                feed(line + if (endOfStream) "" else "\n")
            }
        }
    }

    private fun directive(line: String) {
        val t = line.trim()
        when {
            t.startsWith("MEMO:") -> t.removePrefix("MEMO:").trim().takeIf { it.isNotEmpty() }?.let { onDirective(Directive.Memo(it)) }
            t.startsWith("MUTE:") -> Regex("\\d+").find(t)?.value?.toIntOrNull()?.let { onDirective(Directive.Mute(it.coerceIn(1, 600))) }
        }
    }

    private fun decide(d: Decision) {
        decision = d
        onDecision(d)
    }

    private companion object {
        val KEYWORDS = listOf("MEMO:", "MUTE:")
    }
}
