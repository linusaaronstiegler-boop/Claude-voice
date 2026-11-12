package app.claudevoice.core

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Sprachausgabe über ElevenLabs. Die Antwort ist ein Strom aus rohem PCM (24 kHz, mono, 16 Bit),
 * den der Aufrufer direkt abspielen kann, noch während er eintrifft.
 */
class ElevenTts(
    private val apiKey: String,
    private val http: OkHttpClient,
    private val voiceId: String,
    private val baseUrl: String = "https://api.elevenlabs.io",
) : SpeechSynth {
    private val json = ObjectMapper()

    override val sampleRate: Int = SAMPLE_RATE

    override suspend fun synthesize(sentences: ReceiveChannel<String>, onPcm: suspend (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(4800)
        for (sentence in sentences) {
            val response = withContext(Dispatchers.IO) { runInterruptible { open(sentence) } }
            response.use {
                val input = it.body!!.byteStream()
                while (true) {
                    val n = withContext(Dispatchers.IO) { runInterruptible { input.read(buffer) } }
                    if (n < 0) break
                    if (n > 0) onPcm(buffer, n)
                }
            }
        }
    }

    private fun open(text: String): Response {
        val body = json.writeValueAsString(
            json.createObjectNode().put("text", text).put("model_id", MODEL)
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/v1/text-to-speech/$voiceId/stream?output_format=pcm_$SAMPLE_RATE")
            .header("xi-api-key", apiKey)
            .post(body)
            .build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException(
                if (code == 401 || code == 403) "ElevenLabs-Key wurde abgelehnt ($code)"
                else "Sprachausgabe antwortet mit $code"
            )
        }
        return response
    }

    companion object {
        const val MODEL = "eleven_flash_v2_5"
        const val SAMPLE_RATE = 24000
    }
}
