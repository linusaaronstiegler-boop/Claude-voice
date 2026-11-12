package app.claudevoice.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.Timeout
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingConfigBetweenTools
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigDisabled
import java.time.Duration
import com.anthropic.models.beta.messages.MessageCreateParams as BetaMessageCreateParams

/**
 * Abbruch-Signal für einen laufenden Aufruf.
 *
 * Warum nicht einfach den Thread unterbrechen? Ein Thread, der gerade auf Netzdaten wartet, reagiert
 * darauf nicht zuverlässig. Sicher ist nur, die Verbindung selbst zu schließen. Der Aufruf hinterlegt
 * dafür hier seine "Schließen"-Aktion; wer abbrechen will, ruft [cancel].
 */
class CancelToken {
    @Volatile var cancelled = false
        private set
    private var action: (() -> Unit)? = null

    fun onCancel(a: () -> Unit) {
        val runNow = synchronized(this) {
            if (cancelled) true else { action = a; false }
        }
        if (runNow) a()
    }

    fun cancel() {
        val a = synchronized(this) {
            cancelled = true
            action.also { action = null }
        }
        runCatching { a?.invoke() }
    }
}

/**
 * Schmale Schnittstelle zu Claude. Der Rest der App kennt nur diese Funktionen;
 * in Tests wird sie durch eine Attrappe ersetzt, damit keine echten API-Aufrufe nötig sind.
 */
interface ClaudeApi {
    /**
     * Starkes Modell, gestreamt: [onText] bekommt den Text stückweise. Blockiert bis zum Ende.
     * Wirft [onText] eine Ausnahme oder wird [cancel] ausgelöst, endet der Aufruf sofort.
     */
    fun respond(system: String, user: String, cancel: CancelToken = CancelToken(), onText: (String) -> Unit)

    /** Schnelles Modell für kurze Urteile (Vorprüfung, Ordner). Antwort als Ganzes. */
    fun quick(system: String, user: String, maxTokens: Long): String

    /** Lädt den System-Prompt vorab in den Zwischenspeicher der API, damit schon die erste echte Antwort schnell ist. */
    fun warmUp(system: String) {}
}

class AnthropicClaude(apiKey: String, baseUrl: String? = null) : ClaudeApi {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        // Im Ohr zählt Tempo: lieber schnell scheitern als zehn Minuten warten (SDK-Standard).
        .timeout(
            Timeout.builder()
                .connect(Duration.ofSeconds(4))
                .read(Duration.ofSeconds(12))
                .request(Duration.ofSeconds(30))
                .build()
        )
        .maxRetries(1)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    private fun answerParams(system: String, user: String, maxTokens: Long): BetaMessageCreateParams =
        BetaMessageCreateParams.builder()
            .model(ANSWER_MODEL)
            .maxTokens(maxTokens)
            // Der System-Prompt ist in einer Sitzung immer gleich: cache_control lässt die API ihn
            // zwischenspeichern (eine Stunde lang), das verkürzt die Zeit bis zum ersten Wort.
            .systemOfBetaTextBlockParams(
                listOf(
                    BetaTextBlockParam.builder()
                        .text(system)
                        .cacheControl(
                            BetaCacheControlEphemeral.builder().ttl(BetaCacheControlEphemeral.Ttl.TTL_1H).build()
                        )
                        .build()
                )
            )
            .addUserMessage(user)
            // Sonnet 5.5 denkt standardmäßig vor der Antwort nach. Für einen Zuruf ins Ohr kostet das
            // zu viel Zeit: "between_tools" schaltet das Vorab-Nachdenken ab.
            .thinking(BetaThinkingConfigBetweenTools.builder().build())
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            // Lehnt ein Sicherheitsfilter die Anfrage ab, übernimmt serverseitig ein Ersatzmodell,
            // statt dass die Antwort ausfällt.
            .fallbacksDefault()
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
            .build()

    override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
        if (cancel.cancelled) return
        val stream = client.beta().messages().createStreaming(answerParams(system, user, 600L))
        cancel.onCancel { stream.close() }
        try {
            stream.use { s ->
                s.stream().forEach { event ->
                    event.contentBlockDelta().ifPresent { delta ->
                        delta.delta().text().ifPresent { onText(it.text()) }
                    }
                }
            }
        } catch (e: Exception) {
            // Nach einem gewollten Abbruch ist der Lesefehler erwartet und kein Problem.
            if (!cancel.cancelled) throw e
        }
    }

    override fun warmUp(system: String) {
        // max_tokens 0: Die API liest und speichert den Prompt, erzeugt aber keine Antwort.
        client.beta().messages().create(answerParams(system, "(Vorwärmen)", 0L))
    }

    override fun quick(system: String, user: String, maxTokens: Long): String {
        val params = MessageCreateParams.builder()
            .model(QUICK_MODEL)
            .maxTokens(maxTokens)
            .system(system)
            .addUserMessage(user)
            .thinking(ThinkingConfigDisabled.builder().build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .build()
        return client.messages().create(params).content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
    }

    companion object {
        const val ANSWER_MODEL = "claude-sonnet-5-5"
        const val QUICK_MODEL = "claude-haiku-5-5"
    }
}
