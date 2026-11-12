package app.claudevoice.core

/**
 * Erkennt den bloßen Ruf ohne Anliegen: "Hey Jarvis", "Jarvis?", "hallo Alfred".
 *
 * Wozu: In der Bereitschaft sagt man "Hey Jarvis", wartet auf den Ton und stellt dann die Frage. Die Pause
 * reicht der Transkription, um "Hey Jarvis" als eigene Äußerung abzuschließen. Würde die App darauf mit
 * "Ja, Sir?" antworten, spräche sie genau über die eigentliche Frage, und das Weckwort wäre verbraucht,
 * bevor die Frage kommt. Der Ton war schon die Bestätigung; der Ruf allein löst darum nichts aus.
 *
 * [afterWakeWord] = das lokale Weckwort ist eben gefallen. Dann zählt auch der verhörte Ruf, also ein
 * Grußwort und genau ein unbekanntes Wort ("hey Travis", "hey dschawis"): Gesagt wurde das Weckwort, nur
 * die Transkription hat es anders geschrieben. "Hey Jarvis, Uhrzeit" oder "Wie spät?" bleiben Bitten.
 */
internal fun isBareCall(text: String, names: List<String>, afterWakeWord: Boolean): Boolean {
    val w = Heuristics.words(text)
    if (w.isEmpty() || w.size > 3) return false
    val rest = w.filter { it !in GREETINGS && it !in HESITATIONS }
    // Nur Gruß oder Füllsel ("hey", "äh hey"): der Name wurde verschluckt.
    if (rest.isEmpty()) return afterWakeWord
    if (rest.all { Heuristics.mentionsAny(it, names) }) return true
    // Der Name in zwei Wörter zerlegt ("hey jar vis").
    if (rest.size == 2 && names.any { Heuristics.mentionsName(rest[0] + rest[1], it) }) return true
    return afterWakeWord && w.size == 2 && w[0] in GREETINGS
}

/**
 * Ein Ruf, in dem gar kein Name vorkommt, auch kein verhörter: nur Gruß oder Füllsel ("Hey", "Ja.", "ähm okay").
 *
 * Nach dem Weckwort gilt auch das als bloßer Ruf ([isBareCall]), damit ein Zögern des Trägers das Weckwort
 * nicht verbraucht. Wer gerufen hat, verrät so eine Äußerung aber nicht: "Ja." und "Okay." sagt auch der
 * Fernseher ständig. Die Pipeline fragt deshalb hier nach, bevor sie sich die Stimme als die des Rufers merkt.
 */
internal fun isNamelessCall(text: String): Boolean {
    val w = Heuristics.words(text)
    return w.isNotEmpty() && w.all { it in GREETINGS || it in HESITATIONS }
}

private val GREETINGS = setOf("hey", "hi", "hallo", "hello", "he", "ey", "hej")
private val HESITATIONS = setOf("äh", "ähm", "hm", "hmm", "mhm", "ja", "okay", "ok", "ach", "ah", "oh", "also", "na", "uh", "um")
