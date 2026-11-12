package app.claudevoice.ui

import java.util.Locale

// Kleine Helfer, die Rohwerte in Anzeigetext verwandeln. Bewusst ohne Android- und Compose-Klassen:
// so lassen sie sich als gewöhnliche Funktionen in Millisekunden testen.

/** Eine Mitschrift-Zeile, zerlegt in Sprecher-Kürzel ("ICH", "NAH", ...), Sprecher-Nummer ("S2") und Text. */
data class TranscriptLine(val voice: String?, val speaker: String?, val text: String) {
    /** Das Kürzel, wie es links neben dem Text steht, z. B. "NAH S2". Leer, wenn die Zeile keines hat. */
    val prefix: String get() = listOfNotNull(voice, speaker).joinToString(" ")
}

// Die Pipeline liefert Zeilen der Form "ICH: Text" oder "NAH S2: Text". Das Kürzel besteht aus
// Großbuchstaben (oder "?" für unklar); danach kann eine Sprecher-Nummer folgen.
private val LINE_PATTERN = Regex("""^([A-ZÄÖÜ]{2,12}|\?)(?: (S\d{1,3}))?: (.*)$""", RegexOption.DOT_MATCHES_ALL)

/** Trennt das Sprecher-Kürzel vom Text. Passt die Zeile nicht ins Muster, bleibt sie als reiner Text erhalten. */
fun parseTranscriptLine(raw: String): TranscriptLine {
    val match = LINE_PATTERN.matchEntire(raw) ?: return TranscriptLine(null, null, raw)
    val (voice, speaker, text) = match.destructured
    return TranscriptLine(voice, speaker.ifEmpty { null }, text)
}

/** Laufzeit als "3:05", ab einer Stunde als "1:02:09". */
fun formatElapsed(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Statuszeile; solange zugehört wird, mit Laufzeit (Dauerbetrieb kostet API-Minuten). */
fun statusText(status: String, running: Boolean, startedAtMs: Long, nowMs: Long): String =
    if (running) "$status · ${formatElapsed((nowMs - startedAtMs) / 1000)}" else status

/** "Antwort nach 0,8 s": Zeit vom Ende der Frage bis zum ersten hörbaren Ton. Deutsch, also mit Komma. */
fun formatLatency(latencyMs: Long): String {
    val seconds = latencyMs.coerceAtLeast(0) / 1000.0
    val number = if (seconds < 10) String.format(Locale.GERMAN, "%.1f", seconds) else String.format(Locale.GERMAN, "%.0f", seconds)
    return "Antwort nach $number s"
}

/** Die kleine Zeile unter dem Status: Antwortzeit und letzte Entscheidung, soweit vorhanden. */
fun metaLine(latencyMs: Long?, lastDecision: String?): String? {
    val parts = listOfNotNull(
        latencyMs?.let(::formatLatency),
        lastDecision?.takeIf { it.isNotBlank() }?.let { "Zuletzt: $it" },
    )
    return parts.joinToString(" · ").ifEmpty { null }
}

/** Hinweis, solange der Assistent auf Wunsch schweigt; sonst null. */
fun mutedText(mutedUntilMs: Long, nowMs: Long): String? {
    val leftMs = mutedUntilMs - nowMs
    if (mutedUntilMs == 0L || leftMs <= 0) return null
    val minutes = (leftMs + 59_999) / 60_000 // aufrunden: "noch 1 Min." statt "noch 0 Min."
    return "Schweigt auf Wunsch noch $minutes Min. Beim Namen genannt, antwortet er wieder."
}

/**
 * Ein Satz unter der Mikrofon-Wahl, der sagt, was man mit ihr bekommt und was sie kostet.
 * Beide Mikrofone haben einen Haken, den man vorher kennen sollte, statt ihn später zu erraten.
 */
fun micHint(bluetoothMic: Boolean): String =
    if (bluetoothMic) {
        "Erkennt deine Stimme am besten. Solange zugehört wird, klingt Musik wie am Telefon, andere Apps " +
            "bekommen kein Mikrofon, und die Kopfhörer-Taste trennt die Verbindung."
    } else {
        "Hört den ganzen Raum, Musik bleibt unverändert. Deine Stimme ist nicht von anderen zu " +
            "unterscheiden: Merken und Stummschalten per Sprache gehen so nicht."
    }
