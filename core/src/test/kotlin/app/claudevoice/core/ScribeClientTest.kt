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

class ScribeClientTest {
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
        val client = ScribeClient("key-123", http, object : Transcriber.Listener {
            override fun onPartial(text: String, turnProbability: Double?) { events += "partial:$text" }
            override fun onCommitted(text: String, speaker: String?, segment: Int?) { events += "committed:$text" }
            override fun onConnection(connected: Boolean, error: String?, fresh: Boolean) { events += "conn:$connected:$error" }
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
        val client = ScribeClient("falsch", http, object : Transcriber.Listener {
            override fun onPartial(text: String, turnProbability: Double?) {}
            override fun onCommitted(text: String, speaker: String?, segment: Int?) {}
            override fun onConnection(connected: Boolean, error: String?, fresh: Boolean) { events += "$connected:$error" }
        }, baseUrl = server.url("/").toString().removeSuffix("/"))
        client.connect()
        assertEquals("false:ElevenLabs-Key wurde abgelehnt (401)", events.poll(5, TimeUnit.SECONDS))
        client.close()
    }
}
