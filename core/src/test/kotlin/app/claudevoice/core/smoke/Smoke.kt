package app.claudevoice.core.smoke

import app.claudevoice.core.AnthropicClaude
import app.claudevoice.core.Brain
import app.claudevoice.core.Decision
import app.claudevoice.core.ElevenTts
import app.claudevoice.core.Line
import app.claudevoice.core.MicMode
import app.claudevoice.core.Profile
import app.claudevoice.core.ReplyParser
import app.claudevoice.core.ScribeClient
import app.claudevoice.core.SentenceChunker
import app.claudevoice.core.Situation
import app.claudevoice.core.Transcriber
import app.claudevoice.core.Turn
import app.claudevoice.core.Voice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Rauchtest der ganzen Kette gegen die echten Dienste, ohne Handy:
 *   Text -> Sprache (ElevenLabs) -> Transkription (ElevenLabs) -> Entscheidung + Antwort (Claude) -> Sprache
 *
 * Aufruf:  ELEVENLABS_API_KEY=... ANTHROPIC_API_KEY=... ./gradlew :core:smoke
 * Optional: ELEVENLABS_VOICE_ID, SMOKE_FRAGE ("Hey Jarvis, wie hoch ist eigentlich die Zugspitze?")
 *
 * Er verbraucht ein paar Cent Guthaben und zeigt für jede Stufe, ob sie funktioniert und wie lange sie braucht.
 */
fun main() {
    val eleven = System.getenv("ELEVENLABS_API_KEY").orEmpty()
    val anthropic = System.getenv("ANTHROPIC_API_KEY").orEmpty()
    if (eleven.isBlank() || anthropic.isBlank()) {
        println("Rauchtest übersprungen: ELEVENLABS_API_KEY und ANTHROPIC_API_KEY müssen gesetzt sein.")
        println("Aufruf: ELEVENLABS_API_KEY=... ANTHROPIC_API_KEY=... ./gradlew :core:smoke")
        return
    }
    val voice = System.getenv("ELEVENLABS_VOICE_ID")?.takeIf { it.isNotBlank() } ?: "JBFqnCBsd6RMkjVDRZzb"
    val question = System.getenv("SMOKE_FRAGE")?.takeIf { it.isNotBlank() }
        ?: "Hey Jarvis, wie hoch ist eigentlich die Zugspitze?"
    val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    val rows = ArrayList<Pair<String, String>>()
    var failed = false
    fun step(name: String, block: () -> String) {
        try {
            rows += name to block()
        } catch (e: Exception) {
            failed = true
            rows += name to "FEHLER: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // 1. Sprache erzeugen: liefert zugleich das Testaudio für die Transkription.
    val tts = ElevenTts(eleven, http, voice)
    // Fällt der schnelle WebSocket-Weg aus, springt still der langsamere HTTP-Weg ein. Für den Rauchtest
    // wäre das ein falscher Erfolg, deshalb werden solche Meldungen gesammelt und als Fehler gewertet.
    val ttsTrouble = java.util.concurrent.CopyOnWriteArrayList<String>()
    tts.onTrouble = { ttsTrouble += it }
    fun ttsClean() = check(ttsTrouble.isEmpty()) { "schneller Weg nicht sauber: ${ttsTrouble.joinToString("; ")}" }
    var speech16 = ByteArray(0)
    step("1 Sprache erzeugen") {
        val (pcm, firstMs, totalMs) = speak(tts, listOf(question))
        speech16 = resample24to16(pcm)
        ttsClean()
        "erster Ton nach $firstMs ms, fertig nach $totalMs ms, ${pcm.size / 48} ms Audio"
    }

    // 2. Transkription: Audio in Echtzeit einspeisen und messen, wie lange nach dem Sprechende der Text feststeht.
    var heard = ""
    step("2 Transkription") {
        check(speech16.isNotEmpty()) { "kein Testaudio aus Schritt 1" }
        val connected = CountDownLatch(1)
        val done = CountDownLatch(1)
        val text = AtomicReference("")
        val committedAt = AtomicLong(0)
        val error = AtomicReference<String?>(null)
        val client = ScribeClient(eleven, http, object : Transcriber.Listener {
            override fun onPartial(text: String, turnProbability: Double?, speaker: String?) {}
            override fun onCommitted(t: String, speaker: String?, segment: Int?) {
                if (t.isNotBlank() && committedAt.compareAndSet(0, System.currentTimeMillis())) { text.set(t); done.countDown() }
            }
            override fun onConnection(c: Boolean, e: String?, fresh: Boolean) {
                if (e != null) error.set(e)
                if (c) connected.countDown()
            }
        })
        client.connect()
        check(connected.await(10, TimeUnit.SECONDS)) { "keine Verbindung: ${error.get() ?: "Zeitüberschreitung"}" }
        var speechEnd = 0L
        var i = 0
        while (i < speech16.size) {
            val n = minOf(3200, speech16.size - i)
            client.sendAudio(speech16.copyOfRange(i, i + n), n)
            i += n
            Thread.sleep(100)
        }
        speechEnd = System.currentTimeMillis()
        val quiet = ByteArray(3200)
        var waited = 0
        while (done.count > 0 && waited < 80) { client.sendAudio(quiet, quiet.size); Thread.sleep(100); waited++ }
        client.close()
        check(done.count == 0L) { "kein fertiger Text innerhalb von 8 s (${error.get() ?: "ohne Fehlermeldung"})" }
        heard = text.get()
        "\"$heard\" stand ${committedAt.get() - speechEnd} ms nach dem Sprechende fest"
    }

    // 3. Entscheidung und Antwort in einem Aufruf.
    val sentences = ArrayList<String>()
    step("3 Entscheidung + Antwort") {
        check(heard.isNotBlank()) { "kein Text aus Schritt 2" }
        val brain = Brain(AnthropicClaude(anthropic), Profile(aliases = listOf("Jarvis")))
        val warm = System.currentTimeMillis()
        brain.warmUp()
        val warmMs = System.currentTimeMillis() - warm
        val turn = Turn(
            emptyList(), Line(1, 0, Voice.ICH, heard),
            Situation("Donnerstag, 12. November 2026, 21:14", MicMode.AIRPODS, false, null, null),
        )
        val started = System.currentTimeMillis()
        var firstMs = -1L
        var decision: Decision? = null
        val chunker = SentenceChunker()
        val parser = ReplyParser({ decision = it }, { d -> chunker.feed(d).forEach { sentences += it } }, {})
        brain.respond(turn) { delta ->
            if (firstMs < 0) firstMs = System.currentTimeMillis() - started
            parser.feed(delta)
        }
        parser.finish()
        chunker.flush()?.let { sentences += it }
        val totalMs = System.currentTimeMillis() - started
        check(decision is Decision.Say) { "Modell hat nicht geantwortet: $decision" }
        "Vorwärmen $warmMs ms; erstes Wort nach $firstMs ms, fertig nach $totalMs ms: ${sentences.joinToString(" ")}"
    }

    // 4. Antwort sprechen, wie es das Handy tun würde.
    step("4 Antwort sprechen") {
        check(sentences.isNotEmpty()) { "keine Antwort aus Schritt 3" }
        val (pcm, firstMs, totalMs) = speak(tts, sentences)
        ttsClean()
        "erster Ton nach $firstMs ms, fertig nach $totalMs ms, ${pcm.size / 48} ms Audio"
    }

    println()
    println("Rauchtest Claude Voice")
    rows.forEach { (name, result) -> println("  %-26s %s".format(name, result)) }
    println(if (failed) "ERGEBNIS: mindestens eine Stufe ist fehlgeschlagen." else "ERGEBNIS: alle Stufen funktionieren.")
    http.dispatcher.executorService.shutdown()
    http.connectionPool.evictAll()
    exitProcess(if (failed) 1 else 0)
}

/** Spricht die Sätze und misst die Zeit bis zum ersten Ton und bis zum Ende. */
private fun speak(tts: ElevenTts, sentences: List<String>): Triple<ByteArray, Long, Long> = runBlocking {
    val channel = Channel<String>(Channel.UNLIMITED)
    sentences.forEach { channel.send(it) }
    channel.close()
    val out = ByteArrayOutputStream()
    val started = System.currentTimeMillis()
    var first = -1L
    tts.synthesize(channel) { pcm, n ->
        if (first < 0) first = System.currentTimeMillis() - started
        out.write(pcm, 0, n)
    }
    check(out.size() > 0) { "kein Audio erhalten" }
    Triple(out.toByteArray(), first, System.currentTimeMillis() - started)
}

/** 24 kHz -> 16 kHz (Verhältnis 3:2) per linearer Interpolation; für Sprache als Testsignal genau genug. */
fun resample24to16(pcm: ByteArray): ByteArray {
    val inSamples = pcm.size / 2
    fun sample(i: Int): Int {
        val k = i.coerceIn(0, inSamples - 1)
        return (pcm[2 * k + 1].toInt() shl 8) or (pcm[2 * k].toInt() and 0xFF)
    }
    val outSamples = inSamples * 2 / 3
    val out = ByteArray(outSamples * 2)
    for (j in 0 until outSamples) {
        val pos = j * 1.5
        val i = pos.toInt()
        val frac = pos - i
        val v = (sample(i) * (1 - frac) + sample(i + 1) * frac).toInt()
        out[2 * j] = (v and 0xFF).toByte()
        out[2 * j + 1] = (v shr 8).toByte()
    }
    return out
}
