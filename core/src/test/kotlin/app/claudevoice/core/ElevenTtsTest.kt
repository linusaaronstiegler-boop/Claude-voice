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

class ElevenTtsTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    @Test fun `Sprachausgabe fragt PCM-Strom mit Stimme und Modell an`() {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(10, 20, 30, 40))))
        val tts = ElevenTts("key-123", http, "voice42", server.url("/").toString().removeSuffix("/"))
        val out = java.io.ByteArrayOutputStream()
        kotlinx.coroutines.runBlocking {
            val sentences = kotlinx.coroutines.channels.Channel<String>(1)
            sentences.send("Hallo Welt.")
            sentences.close()
            tts.synthesize(sentences) { pcm, n -> out.write(pcm, 0, n) }
        }
        assertTrue(out.toByteArray().contentEquals(byteArrayOf(10, 20, 30, 40)))

        val request = server.takeRequest()
        assertEquals("/v1/text-to-speech/voice42/stream?output_format=pcm_24000", request.path)
        assertEquals("key-123", request.getHeader("xi-api-key"))
        val body = ObjectMapper().readTree(request.body.readUtf8())
        assertEquals("Hallo Welt.", body["text"].asText())
        assertEquals("eleven_flash_v2_5", body["model_id"].asText())
    }
}
