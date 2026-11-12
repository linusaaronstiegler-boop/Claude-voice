package app.claudevoice.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigDisabled

/**
 * Schmale Schnittstelle zu Claude. Der Rest der App kennt nur diese zwei Funktionen;
 * in Tests wird sie durch eine Attrappe ersetzt, damit keine echten API-Aufrufe nötig sind.
 */
interface ClaudeApi {
    /** Kurze Ja/Nein-artige Entscheidung mit dem schnellen Modell, Antwort als Ganzes. */
    fun classify(system: String, user: String): String

    /** Antwort mit dem starken Modell, Text kommt stückweise über [onText]. Blockiert bis zum Ende. */
    fun answer(system: String, user: String, onText: (String) -> Unit)
}

class AnthropicClaude(apiKey: String, baseUrl: String? = null) : ClaudeApi {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    override fun classify(system: String, user: String): String {
        val params = MessageCreateParams.builder()
            .model(GATE_MODEL)
            .maxTokens(300L)
            .system(system)
            .addUserMessage(user)
            // Der Filter soll in Millisekunden entscheiden: kein Nachdenken, niedrigster Aufwand.
            .thinking(ThinkingConfigDisabled.builder().build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .build()
        return client.messages().create(params).content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
    }

    override fun answer(system: String, user: String, onText: (String) -> Unit) {
        val params = MessageCreateParams.builder()
            .model(ANSWER_MODEL)
            .maxTokens(2000L)
            .system(system)
            .addUserMessage(user)
            // Sonnet 5.5 denkt adaptiv; "low" hält die Zeit bis zum ersten Wort kurz.
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

    companion object {
        const val GATE_MODEL = "claude-haiku-5-5"
        const val ANSWER_MODEL = "claude-sonnet-5-5"
    }
}
