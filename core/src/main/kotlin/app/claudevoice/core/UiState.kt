package app.claudevoice.core

/** Alles, was die Oberfläche anzeigt. Ein unveränderlicher Schnappschuss: die UI zeichnet einfach den jeweils neuesten. */
data class UiState(
    val running: Boolean = false,
    val startedAtMs: Long = 0,
    val status: String = "Bereit",
    val partial: String = "",
    val transcript: List<String> = emptyList(),
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
