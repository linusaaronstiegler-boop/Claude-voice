package app.claudevoice.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.math.PI
import kotlin.math.sin

class FakeClaude(
    var reply: (String) -> List<String> = { listOf("SKIP keine-frage") },
    var quickReply: (system: String, user: String) -> String = { _, _ -> "NEIN" },
) : ClaudeApi {
    val respondCalls = mutableListOf<String>()
    val quickCalls = mutableListOf<Pair<String, String>>()
    var systemSeen: String? = null

    override fun respond(system: String, user: String, onText: (String) -> Unit) {
        systemSeen = system
        respondCalls += user
        reply(user).forEach(onText)
    }

    override fun quick(system: String, user: String, maxTokens: Long): String {
        quickCalls += system to user
        return quickReply(system, user)
    }
}

class FakeSpeaker(private val gate: CompletableDeferred<Unit>? = null) : Speaker {
    val spoken = mutableListOf<String>()
    var stops = 0

    override suspend fun speak(sentences: ReceiveChannel<String>, onFirstAudio: () -> Unit) {
        var first = true
        for (s in sentences) {
            if (first) { onFirstAudio(); first = false }
            spoken += s
            gate?.await() // hält die "Wiedergabe" an, bis der Test sie freigibt
        }
    }

    override fun stop() { stops++ }
}

class FakeEnv(var music: Boolean = false) : Pipeline.Env {
    val notes = mutableListOf<String>()
    val profiles = mutableListOf<VoiceProfile>()
    override fun localTime() = "Donnerstag, 12. November 2026, 21:14"
    override fun musicPlaying() = music
    override fun saveNote(text: String) { notes += text }
    override fun saveVoiceProfile(profile: VoiceProfile) { profiles += profile }
}

/** Ton mit Obertönen (ähnlich einer Stimme) als 16-Bit-PCM. */
fun tone(hz: Double, amplitude: Double, ms: Int, rate: Int = 16000): ByteArray {
    val n = rate * ms / 1000
    val out = ByteArray(n * 2)
    for (i in 0 until n) {
        val t = i.toDouble() / rate
        val v = amplitude * (0.6 * sin(2 * PI * hz * t) + 0.3 * sin(2 * PI * 2 * hz * t) + 0.1 * sin(2 * PI * 3 * hz * t))
        val s = v.toInt()
        out[2 * i] = (s and 0xFF).toByte()
        out[2 * i + 1] = (s shr 8).toByte()
    }
    return out
}

fun silence(ms: Int) = ByteArray(16000 * ms / 1000 * 2)
