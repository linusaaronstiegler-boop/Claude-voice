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

class NetworkTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    @Test fun `Scribe-Client sendet Audio und liefert Zwischenstand und fertige Aeusserung`() {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"message_type":"session_started","session_id":"s1"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                webSocket.send("""{"message_type":"partial_transcript","text":"Wie hoch"}""")
                webSocket.send("""{"message_type":"committed_transcript","text":"Wie hoch ist die Zugspitze?"}""")
            }
        }))
        val events = LinkedBlockingQueue<String>()
        val client = ScribeClient("key-123", http, object : ScribeClient.Listener {
            override fun onPartial(text: String) { events += "partial:$text" }
            override fun onCommitted(text: String) { events += "committed:$text" }
            override fun onConnection(connected: Boolean, error: String?) { events += "conn:$connected:$error" }
        }, baseUrl = server.url("/").toString().removeSuffix("/"))

        client.connect()
        assertEquals("conn:true:null", events.poll(5, TimeUnit.SECONDS))
        client.sendAudio(byteArrayOf(1, 2, 3, 4, 99), 4)

        val sent = ObjectMapper().readTree(received.poll(5, TimeUnit.SECONDS))
        assertEquals("input_audio_chunk", sent["message_type"].asText())
        assertEquals(16000, sent["sample_rate"].asInt())
        assertTrue(Base64.getDecoder().decode(sent["audio_base_64"].asText()).contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals("partial:Wie hoch", events.poll(5, TimeUnit.SECONDS))
        assertEquals("committed:Wie hoch ist die Zugspitze?", events.poll(5, TimeUnit.SECONDS))

        val request = server.takeRequest()
        assertEquals("key-123", request.getHeader("xi-api-key"))
        assertTrue(request.path!!.contains("model_id=scribe_v2_realtime"))
        assertTrue(request.path!!.contains("commit_strategy=vad"))
        client.close()
    }

    @Test fun `Scribe-Client meldet abgelehnten Key`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val events = LinkedBlockingQueue<String>()
        val client = ScribeClient("falsch", http, object : ScribeClient.Listener {
            override fun onPartial(text: String) {}
            override fun onCommitted(text: String) {}
            override fun onConnection(connected: Boolean, error: String?) { events += "$connected:$error" }
        }, baseUrl = server.url("/").toString().removeSuffix("/"))
        client.connect()
        assertEquals("false:ElevenLabs-Key wurde abgelehnt (401)", events.poll(5, TimeUnit.SECONDS))
        client.close()
    }

    @Test fun `Sprachausgabe fragt PCM-Strom mit Stimme und Modell an`() {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(10, 20, 30, 40))))
        val tts = ElevenTts("key-123", http, server.url("/").toString().removeSuffix("/"))
        val bytes = tts.open("Hallo Welt.", "voice42").use { it.body!!.bytes() }
        assertTrue(bytes.contentEquals(byteArrayOf(10, 20, 30, 40)))

        val request = server.takeRequest()
        assertEquals("/v1/text-to-speech/voice42/stream?output_format=pcm_24000", request.path)
        assertEquals("key-123", request.getHeader("xi-api-key"))
        val body = ObjectMapper().readTree(request.body.readUtf8())
        assertEquals("Hallo Welt.", body["text"].asText())
        assertEquals("eleven_flash_v2_5", body["model_id"].asText())
    }

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
        claude.answer("System", "Frage") { out.append(it) }
        assertEquals("Zwei Sätze.", out.toString())

        val request = server.takeRequest()
        val body = ObjectMapper().readTree(request.body.readUtf8())
        assertEquals("claude-sonnet-5-5", body["model"].asText())
        assertEquals(true, body["stream"].asBoolean())
        assertEquals("low", body["output_config"]["effort"].asText())
        // Das SDK schickt den Key je nach API-Version als x-api-key oder als Authorization-Header.
        val auth = listOfNotNull(request.getHeader("x-api-key"), request.getHeader("Authorization")).joinToString(" ")
        assertTrue(auth.contains("sk-test"))
    }

    @Test fun `Claude-Client liest die Filter-Antwort und schaltet Nachdenken ab`() {
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(
            """{"id":"msg_2","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"SKIP"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1}}"""
        ))
        val claude = AnthropicClaude("sk-test", server.url("/").toString())
        assertEquals("SKIP", claude.classify("System", "Text"))

        val body = ObjectMapper().readTree(server.takeRequest().body.readUtf8())
        assertEquals("claude-haiku-5-5", body["model"].asText())
        assertEquals("disabled", body["thinking"]["type"].asText())
    }
}
