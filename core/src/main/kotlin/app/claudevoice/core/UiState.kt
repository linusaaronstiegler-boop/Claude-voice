package app.claudevoice.core

/** Alles, was die Oberfläche anzeigt. Ein unveränderlicher Schnappschuss: die UI zeichnet einfach den jeweils neuesten. */
/**
 * Was der Messer zu einer Zeile der Mitschrift gemessen hat. Nur für die Anzeige ("Messwerte"): So lässt sich
 * am Gerät nachsehen, wie laut die eigene Stimme wirklich ankommt und warum eine Zeile als ICH, NAH oder FERN
 * gilt. Es sind Zahlen, kein Audio, und nichts davon wird gespeichert.
 */
data class VoiceMeasure(
    /** Pegel der Sprache in dBFS (0 = Vollaussteuerung); null, wenn sich die Äußerung nicht messen ließ. */
    val levelDb: Double?,
    /** Stimmlage (Grundfrequenz) in Hz; null, wenn nicht bestimmbar. */
    val pitchHz: Double?,
    /** Die Einschätzung, wie sie vor der Zeile steht: "ICH", "NAH", "FERN" oder "?". */
    val voice: String,
    /** true, wenn die Akustik die Stimme dem Träger zuordnet (nur dann sind Merken und Stummschalten erlaubt). */
    val trusted: Boolean,
)

data class UiState(
    val running: Boolean = false,
    val startedAtMs: Long = 0,
    val status: String = "Bereit",
    val partial: String = "",
    val transcript: List<String> = emptyList(),
    /** Die Messwerte zu [transcript]: gleich viele Einträge, an gleicher Stelle. */
    val measures: List<VoiceMeasure> = emptyList(),
    /** Das Stimmprofil, mit dem gerade verglichen wird (Pegel, Stimmlage, Zahl der Proben); null = noch keines. */
    val voiceProfile: VoiceProfile? = null,
    val question: String? = null,
    val answer: String = "",
    val speaking: Boolean = false,
    val error: String? = null,
    /** Einschätzung der Lage durch den "Ordner" (wer spricht mit wem worüber). */
    val scene: String? = null,
    /** Letzte Entscheidung in Klartext, z. B. "übersprungen: hintergrund" oder "antwortet (kurz)". */
    val lastDecision: String? = null,
    /**
     * Zeit vom Ende der Äußerung bis zum ersten hörbaren Ton der letzten Antwort. "Ende" ist das letzte neue
     * Wort in der Mitschrift, nicht das spätere Eintreffen der fertigen Äußerung: So fühlt sich die Wartezeit an.
     */
    val latencyMs: Long? = null,
    /** Bis wann der Assistent auf Wunsch schweigt (0 = nicht stumm). */
    val mutedUntilMs: Long = 0,
)
