package app.claudevoice.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigBetweenTools
import com.anthropic.models.messages.ThinkingConfigDisabled
import java.time.Duration

/**
 * Schmale Schnittstelle zu Claude. Der Rest der App kennt nur diese zwei Funktionen;
 * in Tests wird sie durch eine Attrappe ersetzt, damit keine echten API-Aufrufe nötig sind.
 */
interface ClaudeApi {
    /**
     * Starkes Modell, gestreamt: [onText] bekommt den Text stückweise. Blockiert bis zum Ende.
     * Wirft [onText] eine Ausnahme, wird der Aufruf sofort abgebrochen (so beendet die Pipeline ein SKIP).
     */
    fun respond(system: String, user: String, onText: (String) -> Unit)

    /** Schnelles Modell für kurze Urteile (Vorprüfung, Ordner). Antwort als Ganzes. */
    fun quick(system: String, user: String, maxTokens: Long): String
}

class AnthropicClaude(apiKey: String, baseUrl: String? = null) : ClaudeApi {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        // Im Ohr zählt Tempo: lieber einmal schnell scheitern als zehn Minuten warten (SDK-Standard).
        .timeout(Duration.ofSeconds(30))
        .maxRetries(1)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    override fun respond(system: String, user: String, onText: (String) -> Unit) {
        val params = MessageCreateParams.builder()
            .model(ANSWER_MODEL)
            .maxTokens(600L)
            // Der System-Prompt ist in einer Sitzung immer gleich: cache_control lässt die API ihn
            // zwischenspeichern, das verkürzt die Zeit bis zum ersten Wort.
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(system)
                        .cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())
                        .build()
                )
            )
            .addUserMessage(user)
            // Sonnet 5.5 denkt standardmäßig vor der Antwort nach. Für einen Zuruf ins Ohr kostet das
            // zu viel Zeit: "between_tools" schaltet das Vorab-Nachdenken ab.
            .thinking(ThinkingConfigBetweenTools.builder().build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .build()
        client.messages().createStreaming(params).use { stream ->
            stream.stream().forEach { event ->
                event.contentBlockDelta().ifPresent { delta ->
                    delta.delta().text().ifPresent { onText(it.text()) }
                }
            }
        }
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
