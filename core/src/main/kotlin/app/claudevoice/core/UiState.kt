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
)
