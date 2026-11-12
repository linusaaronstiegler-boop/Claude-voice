package app.claudevoice.core

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AnthropicClaudeTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    @Test fun `Claude-Client streamt Text und nennt das Sonnet-Modell`() {
        val sse = listOf(
            """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Zwei "}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Sätze."}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":4}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { data ->
            val type = ObjectMapper().readTree(data)["type"].asText()
            "event: $type\ndata: $data\n\n"
        }
        server.enqueue(MockResponse().setHeader("content-type", "text/event-stream").setBody(sse))
        val claude = AnthropicClaude("sk-test", server.url("/").toString())
        val out = StringBuilder()
        claude.respond("System", "Frage") { out.append(it) }
        assertEquals("Zwei Sätze.", out.toString())

        val request = server.takeRequest()
        val body = ObjectMapper().readTree(request.body.readUtf8())
        assertEquals("claude-sonnet-5-5", body["model"].asText())
        assertEquals(true, body["stream"].asBoolean())
        assertEquals("low", body["output_config"]["effort"].asText())
        // Vorab-Nachdenken aus und System-Prompt zum Zwischenspeichern markiert: beides für Tempo.
        assertEquals("between_tools", body["thinking"]["type"].asText())
        assertEquals("System", body["system"][0]["text"].asText())
        assertEquals("ephemeral", body["system"][0]["cache_control"]["type"].asText())
        // Das SDK schickt den Key je nach API-Version als x-api-key oder als Authorization-Header.
        val auth = listOfNotNull(request.getHeader("x-api-key"), request.getHeader("Authorization")).joinToString(" ")
        assertTrue(auth.contains("sk-test"))
    }

    @Test fun `Claude-Client liest die Filter-Antwort und schaltet Nachdenken ab`() {
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(
            """{"id":"msg_2","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"SKIP"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1}}"""
        ))
        val claude = AnthropicClaude("sk-test", server.url("/").toString())
        assertEquals("SKIP", claude.quick("System", "Text", 10))

        val body = ObjectMapper().readTree(server.takeRequest().body.readUtf8())
        assertEquals("claude-haiku-5-5", body["model"].asText())
        assertEquals("disabled", body["thinking"]["type"].asText())
    }
}
