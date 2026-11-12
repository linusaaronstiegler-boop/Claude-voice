package app.claudevoice.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

class FakeClaude(
    var reply: (String) -> List<String> = { listOf("SKIP keine-frage") },
    var quickReply: (system: String, user: String) -> String = { _, _ -> "NEIN" },
) : ClaudeApi {
    val respondCalls = mutableListOf<String>()
    val quickCalls = mutableListOf<Pair<String, String>>()
    var systemSeen: String? = null

    var warmUps = 0
    override fun warmUp(system: String) { warmUps++ }

    override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
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

    var prepares = 0

    override suspend fun speak(sentences: ReceiveChannel<String>, release: Deferred<Boolean>?, onFirstAudio: () -> Unit) {
        // Wie der echte Lautsprecher: Mit Schranke erklingt nichts, bevor sie sich öffnet; bei Nein gar nichts.
        if (release != null && !release.await()) return
        var first = true
        for (s in sentences) {
            if (first) { onFirstAudio(); first = false }
            spoken += s
            gate?.await() // hält die "Wiedergabe" an, bis der Test sie freigibt
        }
    }

    override fun stop() { stops++ }
    override fun prepare() { prepares++ }
}

class FakeEnv(
    var music: Boolean = false,
    /** Mit dieser Ausnahme scheitert jedes Speichern einer Notiz (Speicher voll, Datei gesperrt). */
    private val saveFails: Exception? = null,
) : Pipeline.Env {
    val notes = mutableListOf<String>()
    val profiles = mutableListOf<VoiceProfile>()
    override fun localTime() = "Donnerstag, 12. November 2026, 21:14"
    override fun musicPlaying() = music
    override fun saveNote(text: String) {
        saveFails?.let { throw it }
        notes += text
    }
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

/**
 * Lautsprecher-Attrappe, die wie der echte zwei Dinge trennt: was die Sprachausgabe schon bekommen hat
 * ([received], auch hinter der noch geschlossenen Schranke) und was wirklich zu hören war ([spoken]).
 * Mit [clock] und [playMs] dauert das Abspielen einer Antwort so lange auf der Uhr des Tests.
 */
class RecordingSpeaker(
    private val clock: java.util.concurrent.atomic.AtomicLong? = null,
    private val playMs: Long = 0,
) : Speaker {
    val received = java.util.concurrent.CopyOnWriteArrayList<String>()
    val spoken = java.util.concurrent.CopyOnWriteArrayList<String>()
    val prepares = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun speak(sentences: ReceiveChannel<String>, release: Deferred<Boolean>?, onFirstAudio: () -> Unit) {
        kotlinx.coroutines.coroutineScope {
            val ready = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val intake = launch {
                for (s in sentences) { received += s; ready.send(s) }
                ready.close()
            }
            if (release != null && !release.await()) {
                intake.cancel()
                return@coroutineScope
            }
            var any = false
            for (s in ready) {
                if (!any) onFirstAudio()
                any = true
                spoken += s
            }
            if (any) clock?.addAndGet(playMs)
        }
    }

    override fun stop() {}
    override fun prepare() { prepares.incrementAndGet() }
}

/**
 * Lautsprecher-Attrappe mit echter Dauer: Jeder Satz braucht [perSentenceMs], und nach einem Abbruch räumt
 * sie noch [windDownMs] lang auf. So verhält sich der echte Lautsprecher (Ausgabe anhalten, Audiofokus
 * abgeben), und genau in dieser Spanne liegen die Lücken, die sich mit einer Attrappe ohne Dauer nie zeigen.
 */
class TimedSpeaker(private val perSentenceMs: Long, private val windDownMs: Long = 0) : Speaker {
    val spoken = java.util.concurrent.CopyOnWriteArrayList<String>()

    override suspend fun speak(sentences: ReceiveChannel<String>, release: Deferred<Boolean>?, onFirstAudio: () -> Unit) {
        if (release != null && !release.await()) return
        var first = true
        try {
            for (s in sentences) {
                if (first) { onFirstAudio(); first = false }
                spoken += s
                kotlinx.coroutines.delay(perSentenceMs)
            }
        } finally {
            // NonCancellable: Das Aufräumen läuft auch dann zu Ende, wenn der Auftrag gerade abgebrochen wurde.
            if (!first && windDownMs > 0) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.delay(windDownMs)
            }
        }
    }

    override fun stop() {}
}

/**
 * Verdrahtung wie im Dienst auf dem Handy: die Pipeline auf Dispatchers.Default, Modell-Aufrufe auf
 * Dispatchers.IO, also echte Threads. Nur so lässt sich prüfen, was passiert, während ein Aufruf
 * wirklich noch unterwegs ist. Die Uhr stellt der Test von Hand ([clock]).
 */
class ThreadRig(
    val claude: ClaudeApi,
    config: Pipeline.Config = Pipeline.Config(),
    val speaker: RecordingSpeaker = RecordingSpeaker(),
    val clock: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0),
    /** Ein anderer Lautsprecher als der mitschreibende, etwa einer mit echter Dauer ([TimedSpeaker]). */
    out: Speaker? = null,
    val env: FakeEnv = FakeEnv(),
) {
    val state = kotlinx.coroutines.flow.MutableStateFlow(UiState())
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    val pipeline = Pipeline(
        Brain(claude, Profile()), out ?: speaker, state, scope, kotlinx.coroutines.Dispatchers.IO, config, env,
    ) { clock.get() }.also { it.start() }

    /** Der Träger spricht: laute, tiefe Stimme am Kopfhörer-Mikro, dann die fertige Äußerung. */
    fun wearerSays(text: String) = says(115.0, 8000.0, 600, text)

    /** Irgendeine Stimme: [ms] Ton in Tonhöhe [hz] und Lautstärke [amplitude], dann die fertige Äußerung. */
    fun says(hz: Double, amplitude: Double, ms: Int, text: String) {
        pipeline.onAudio(silence(300), 9600)
        val t = tone(hz, amplitude, ms)
        pipeline.onAudio(t, t.size)
        pipeline.onCommitted(text, null, null)
    }

    fun close() {
        pipeline.stop()
        scope.cancel()
    }
}

/** Wartet höchstens [ms] Millisekunden echte Zeit darauf, dass [condition] wahr wird. */
fun waitFor(ms: Long, condition: () -> Boolean): Boolean {
    val end = System.nanoTime() + ms * 1_000_000
    while (System.nanoTime() < end) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}
