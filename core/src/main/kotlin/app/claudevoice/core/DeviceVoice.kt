package app.claudevoice.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Sprachausgabe mit der "Stimme des Geräts": Android bringt eine eigene Sprachausgabe mit
// (Text-in-Sprache, englisch "TextToSpeech"). Sie kostet nichts und braucht kein Konto.
//
// Aufteilung, damit sich fast alles ohne Handy testen lässt:
//  - [DeviceVoiceEngine] ist die Naht: "nimm diesen Satz, melde mir, was daraus wird". Auf dem Handy
//    steckt dahinter Androids TextToSpeech (app/…/AndroidDeviceVoice.kt), im Test eine Attrappe.
//  - [DeviceVoiceSynth] ist die Logik: Reihenfolge der Sätze, Abbruch, Fehler, Tonformat. Sie erfüllt
//    dieselbe Schnittstelle wie ElevenLabs ([SpeechSynth]). Alles dahinter (nur in Kopfhörer spielen,
//    Audiofokus, Stopp, Längendeckel) bleibt deshalb genau, wie es ist.
// ---------------------------------------------------------------------------------------------

/** Wie die Engine ihre Abtastwerte ablegt; [bytes] ist die Größe eines Werts. */
enum class PcmEncoding(val bytes: Int) {
    /** Ein Byte ohne Vorzeichen, 128 ist Stille. */
    PCM_8(1),
    /** Zwei Bytes mit Vorzeichen, das niederwertige zuerst. Das ist der Normalfall. */
    PCM_16(2),
    /** Vier Bytes als Kommazahl zwischen -1 und 1. */
    PCM_FLOAT(4),
    /** Etwas, das die App nicht kennt. */
    OTHER(0),
}

/** Was die Engine zu einem Satz meldet. Je Satz in dieser Reihenfolge: [Begin], beliebig oft [Audio], dann ein Ende. */
sealed interface VoiceEvent {
    /** Die Engine beginnt und nennt das Format des Tons. Erst hier erfährt man ihre Abtastrate. */
    data class Begin(val sampleRate: Int, val encoding: PcmEncoding, val channels: Int) : VoiceEvent

    /** Ein Stück Ton im angekündigten Format. */
    class Audio(val pcm: ByteArray) : VoiceEvent

    /** Der Satz ist vollständig erzeugt. */
    data object Done : VoiceEvent

    /** Die Engine meldet einen Fehler; [code] ist ihre Nummer dafür (bei Android z. B. -3 "Synthese", -5 "Ausgabe"). */
    data class Failed(val code: Int) : VoiceEvent

    /** Der Satz wurde angehalten, bevor er fertig war. */
    data object Stopped : VoiceEvent
}

/**
 * Die Naht zur Sprach-Engine des Geräts. Bewusst winzig: einreihen, anhalten, schließen.
 * Die Engine arbeitet die eingereihten Sätze der Reihe nach ab und meldet über [onEvent], was geschieht.
 */
interface DeviceVoiceEngine {
    /** Wohin die Engine meldet. Wird von irgendeinem Thread gerufen; die Ereignisse eines Satzes kommen in Reihenfolge. */
    var onEvent: (id: String, event: VoiceEvent) -> Unit

    /** Reiht [text] hinter allem ein, was schon wartet. [id] steht später an jedem Ereignis. false = abgelehnt. */
    fun enqueue(id: String, text: String): Boolean

    /** Hält den laufenden Satz sofort an und verwirft alle wartenden. */
    fun stop()

    /** Gibt die Engine frei. Danach wird sie nicht mehr benutzt. */
    fun close()
}

/** Die Stimme des Geräts lässt sich nicht benutzen; [message] sagt dem Nutzer auf Deutsch, warum. */
class DeviceVoiceUnavailable(message: String) : Exception(message)

/** Die Meldungen rund um die Stimme des Geräts, an einer Stelle. */
object DeviceVoiceReport {
    private const val WAY_OUT =
        "Bitte in den Android-Einstellungen unter „Text-in-Sprache“ nachsehen, oder in den Einstellungen der App " +
            "die Sprachausgabe „ElevenLabs“ wählen."

    const val NO_ENGINE =
        "Die Stimme des Geräts ist nicht verfügbar: Android stellt keine Sprachausgabe (Text-in-Sprache) bereit. $WAY_OUT"

    const val NO_GERMAN =
        "Die Stimme des Geräts hat keine deutsche Stimme, die ohne Netz spricht. Bitte in den Android-Einstellungen " +
            "unter „Text-in-Sprache“ die Sprachdaten für Deutsch laden, oder in den Einstellungen der App die " +
            "Sprachausgabe „ElevenLabs“ wählen."

    const val STUCK = "Die Stimme des Geräts antwortet nicht. $WAY_OUT"

    const val FORMAT = "Die Stimme des Geräts liefert Ton in einem Format, das die App nicht abspielen kann. $WAY_OUT"

    /** [code] ist die Fehlernummer der Engine, falls sie eine genannt hat. */
    fun noAudio(code: Int?): String =
        "Die Stimme des Geräts liefert keinen Ton" + (if (code == null) "" else " (Fehler $code)") + ". $WAY_OUT"
}

/** Eine Stimme, die die Engine anbietet, auf das reduziert, was für die Auswahl zählt. */
data class VoiceOption(
    val name: String,
    /** Sprache als Kürzel, so wie die Engine sie nennt: "de" oder (bei manchen Engines) "deu". */
    val language: String,
    val country: String,
    /** Güte laut Engine; größer ist besser (Android: 100 bis 500). */
    val quality: Int,
    /** Verzögerung laut Engine; kleiner ist besser. */
    val latency: Int,
    /** true = diese Stimme schickt den Text ins Netz. Solche Stimmen nimmt die App nie. */
    val needsNetwork: Boolean,
    /** false = die Sprachdaten sind noch nicht geladen. */
    val installed: Boolean,
    /** true = die Stimme, die die Engine von sich aus nähme (oft die, die der Nutzer in Android eingestellt hat). */
    val isDefault: Boolean = false,
)

object DeviceVoiceChoice {
    private val GERMAN = setOf("de", "deu", "ger")

    /**
     * Die beste deutsche Stimme aus [options]; null, wenn es keine brauchbare gibt.
     *
     * Brauchbar heißt: deutsch, auf dem Gerät vorhanden und ohne Netz. Der letzte Punkt ist ein Versprechen
     * der Einstellung ("bleibt auf dem Gerät"): Eine Netz-Stimme würde den Text jeder Antwort verschicken.
     * Unter den brauchbaren gewinnt Hochdeutsch aus Deutschland (bzw. [country]) vor anderen Ländern, dann
     * die höhere Güte, dann die Stimme, die Android ohnehin nähme, dann die geringere Verzögerung. Der Name
     * entscheidet zuletzt, damit bei Gleichstand immer dieselbe Stimme herauskommt.
     */
    fun best(options: List<VoiceOption>, country: String = "DE"): VoiceOption? =
        options
            .filter { it.language.lowercase() in GERMAN && !it.needsNetwork && it.installed }
            .sortedWith(
                compareByDescending<VoiceOption> { it.country.equals(country, ignoreCase = true) }
                    .thenByDescending { it.quality }
                    .thenByDescending { it.isDefault }
                    .thenBy { it.latency }
                    .thenBy { it.name }
            )
            .firstOrNull()
}

/**
 * Sprachausgabe mit der Stimme des Geräts, als [SpeechSynth] wie ElevenLabs.
 *
 * Abtastrate: Die Schnittstelle verlangt eine feste [sampleRate], weil der Abspieler einmal dafür gebaut
 * wird. Die Engine nennt ihre Rate aber erst, wenn sie einen Satz beginnt. Deshalb fragt [start] sie beim
 * Start des Zuhörens einmal mit einem Probesatz ab (der nirgends zu hören ist). Das hat zwei Vorteile: Der
 * Ton wird in seiner eigenen Rate abgespielt, ohne Umrechnen und ohne Qualitätsverlust, und es zeigt sich
 * gleich beim Start, ob die Engine überhaupt Ton liefert. Wechselt die Rate später doch einmal (andere
 * Stimme, Engine aktualisiert), rechnet [PcmShaper] diesen Satz auf die feste Rate um.
 */
class DeviceVoiceSynth internal constructor(
    private val engine: DeviceVoiceEngine,
    override val sampleRate: Int,
) : SpeechSynth {

    /** Nur zur Diagnose: Etwas lief nicht sauber, die Antwort war aber (wenigstens teilweise) zu hören. */
    @Volatile var onTrouble: (String) -> Unit = {}

    // Geduld in Millisekunden. "internal", damit die Tests sie verkürzen können.
    /** So lange darf die Engine zu einem Satz schweigen, bevor die Antwort als gescheitert gilt. */
    internal var firstEventTimeoutMs = FIRST_EVENT_MS
    /** So lange wird nach dem letzten Stück Ton noch auf das Ende des Satzes gewartet. */
    internal var idleTimeoutMs = IDLE_MS

    /** Immer nur eine Antwort zur Zeit: Die Engine hat eine einzige Warteschlange, und [stop] leert sie ganz. */
    private val turn = Mutex()
    private val answers = AtomicInteger()

    /** Wer gerade die Ereignisse der Engine bekommt; null zwischen zwei Antworten (Nachzügler verfallen dann). */
    @Volatile private var inbox: Channel<Pair<String, VoiceEvent>>? = null

    init {
        engine.onEvent = { id, event -> inbox?.trySend(id to event) }
    }

    override suspend fun synthesize(sentences: ReceiveChannel<String>, onPcm: suspend (ByteArray, Int) -> Unit) {
        turn.withLock {
            // Jede Antwort bekommt eigene Satz-Kennungen. Meldet die Engine noch etwas zu einer früheren
            // (angehaltenen) Antwort, ist das an der Kennung zu sehen und wird übergangen.
            val prefix = "cv${answers.incrementAndGet()}-"
            val events = Channel<Pair<String, VoiceEvent>>(Channel.UNLIMITED)
            inbox = events
            var finished = false
            try {
                coroutineScope {
                    // Die Nummern der eingereihten Sätze, in ihrer Reihenfolge.
                    val queued = Channel<Int>(Channel.UNLIMITED)
                    // Sätze werden eingereiht, sobald sie eintreffen, auch während der vorige noch spielt:
                    // Die Engine arbeitet vor, und zwischen den Sätzen entsteht keine Lücke.
                    launch {
                        var count = 0
                        try {
                            for (sentence in sentences) {
                                val text = sentence.trim()
                                if (text.isEmpty()) continue
                                val number = count++
                                // Erst anmelden, dann einreihen: Die ersten Ereignisse können sofort kommen.
                                queued.send(number)
                                if (!engine.enqueue(prefix + number, text)) {
                                    events.send(prefix + number to VoiceEvent.Failed(REFUSED))
                                }
                            }
                        } finally {
                            queued.close()
                        }
                    }
                    play(prefix, queued, events, onPcm)
                }
                finished = true
            } finally {
                inbox = null
                // Abgebrochen oder gescheitert: Die Engine soll sofort aufhören und nichts Wartendes mehr
                // erzeugen. Nach einem sauberen Ende wartet nichts mehr, dann ist das überflüssig.
                if (!finished) engine.stop()
            }
        }
    }

    /** Nimmt die Ereignisse der Engine Satz für Satz entgegen und reicht den Ton in Abspielreihenfolge weiter. */
    private suspend fun play(
        prefix: String,
        queued: ReceiveChannel<Int>,
        events: ReceiveChannel<Pair<String, VoiceEvent>>,
        onPcm: suspend (ByteArray, Int) -> Unit,
    ) {
        var sentences = 0
        var silent = 0
        var lastFailure: Int? = null
        // Ein Ereignis, das schon zum nächsten Satz gehört (siehe unten).
        var early: Pair<String, VoiceEvent>? = null
        for (number in queued) {
            val id = prefix + number
            val shaper = PcmShaper(sampleRate)
            var heard = false
            var ended = false
            while (!ended) {
                val next = early ?: nextEvent(events, if (heard) idleTimeoutMs else firstEventTimeoutMs)
                early = null
                if (next == null) {
                    // Die Engine schweigt. Ganz ohne Ton ist die Antwort gescheitert, und das soll man lesen
                    // können, statt auf Stille zu warten.
                    if (!heard) throw IOException(DeviceVoiceReport.STUCK)
                    // Der Ton kam, nur die Schlussmeldung blieb aus: Der Satz gilt als gesprochen.
                    onTrouble("Die Stimme des Geräts hat das Ende eines Satzes nicht gemeldet")
                    break
                }
                val (from, event) = next
                if (from != id) {
                    // Meldet sich schon der NÄCHSTE Satz, ist dieser offenbar fertig, auch ohne Schlussmeldung.
                    // Das Ereignis wird aufgehoben, damit vom nächsten Satz nichts verloren geht.
                    if (isLater(prefix, from, number)) {
                        early = next
                        break
                    }
                    continue // Nachzügler eines früheren Satzes oder einer früheren Antwort
                }
                when (event) {
                    is VoiceEvent.Begin -> shaper.begin(event)
                    is VoiceEvent.Audio -> {
                        val pcm = shaper.feed(event.pcm)
                        if (pcm.isNotEmpty()) {
                            heard = true
                            onPcm(pcm, pcm.size)
                        }
                    }
                    VoiceEvent.Done, VoiceEvent.Stopped -> ended = true
                    is VoiceEvent.Failed -> {
                        // Ein Fehler NACH dem Ton nimmt den Satz nicht zurück: Er ist schon unterwegs zum Ohr.
                        // So ein später Fehler kommt vor, wenn die Engine am Schluss an ihrem Ausgabeziel
                        // scheitert (siehe AndroidDeviceVoice), obwohl der Ton vollständig geliefert wurde.
                        if (heard) onTrouble("Die Stimme des Geräts meldete nach dem Ton einen Fehler (${event.code})")
                        else lastFailure = event.code
                        ended = true
                    }
                }
            }
            sentences++
            if (!heard) silent++
        }
        // Kein einziger Ton für die ganze Antwort: Das ist ein Fehler mit klarem Wortlaut, keine stille Antwort.
        if (sentences > 0 && silent == sentences) throw IOException(DeviceVoiceReport.noAudio(lastFailure))
        if (silent > 0) onTrouble("Die Stimme des Geräts blieb bei $silent von $sentences Sätzen stumm")
    }

    companion object {
        internal const val FIRST_EVENT_MS = 8_000L
        internal const val IDLE_MS = 3_000L

        /** Fehlernummer der App selbst: Die Engine hat den Satz gar nicht erst angenommen. */
        const val REFUSED = -100

        private const val PROBE_ID = "cv-probe"
        /** Der Probesatz beim Start. Er wird erzeugt, aber nirgends abgespielt. */
        private const val PROBE_TEXT = "Ja."

        /**
         * Fragt die Engine mit einem Probesatz nach ihrem Tonformat und baut daraus die Sprachausgabe.
         * Gehört an den Start des Zuhörens: Liefert die Engine keinen Ton, steht das dann sofort da und nicht
         * erst bei der ersten Antwort.
         * @throws DeviceVoiceUnavailable mit einer Meldung für den Nutzer, wenn die Engine nicht taugt.
         */
        suspend fun start(
            engine: DeviceVoiceEngine,
            firstEventTimeoutMs: Long = FIRST_EVENT_MS,
            idleTimeoutMs: Long = 1_500L,
        ): DeviceVoiceSynth {
            val events = Channel<Pair<String, VoiceEvent>>(Channel.UNLIMITED)
            engine.onEvent = { id, event -> events.trySend(id to event) }
            var format: VoiceEvent.Begin? = null
            var bytes = 0
            var failure: Int? = null
            var ended = false
            try {
                if (!engine.enqueue(PROBE_ID, PROBE_TEXT)) throw DeviceVoiceUnavailable(DeviceVoiceReport.noAudio(REFUSED))
                while (!ended) {
                    // Kam schon Ton, wird nur noch kurz auf die Schlussmeldung gewartet; sie ist dann Nebensache.
                    val (from, event) = nextEvent(events, if (bytes > 0) idleTimeoutMs else firstEventTimeoutMs) ?: break
                    if (from != PROBE_ID) continue
                    when (event) {
                        is VoiceEvent.Begin -> format = event
                        is VoiceEvent.Audio -> bytes += event.pcm.size
                        VoiceEvent.Done, VoiceEvent.Stopped -> ended = true
                        is VoiceEvent.Failed -> {
                            failure = event.code
                            ended = true
                        }
                    }
                }
            } finally {
                engine.onEvent = { _, _ -> }
                if (!ended) engine.stop() // auch bei einem Abbruch: Die Engine soll nicht weiterrechnen
            }
            val known = format
            if (bytes == 0 || known == null) {
                throw DeviceVoiceUnavailable(
                    if (!ended && known == null) DeviceVoiceReport.STUCK else DeviceVoiceReport.noAudio(failure)
                )
            }
            if (!PcmShaper(known.sampleRate).begin(known)) throw DeviceVoiceUnavailable(DeviceVoiceReport.FORMAT)
            // Ein Fehler erst NACH dem Ton (failure != null, bytes > 0) hindert nicht: Der Ton ist brauchbar.
            return DeviceVoiceSynth(engine, known.sampleRate)
        }

        /**
         * Das nächste Ereignis, oder null, wenn [timeoutMs] lang keines kommt. `select` statt eines Zeitlimits
         * um `receive()`: So kann ein Ereignis nicht genau im Moment des Ablaufs verloren gehen.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun nextEvent(
            events: ReceiveChannel<Pair<String, VoiceEvent>>,
            timeoutMs: Long,
        ): Pair<String, VoiceEvent>? = select {
            events.onReceive { it }
            onTimeout(timeoutMs) { null }
        }

        /** Gehört [id] zu einem Satz derselben Antwort, der NACH Satz [number] eingereiht wurde? */
        private fun isLater(prefix: String, id: String, number: Int): Boolean =
            id.startsWith(prefix) && (id.substring(prefix.length).toIntOrNull() ?: -1) > number
    }
}

/**
 * Bringt den Ton eines Satzes in die Form, die der Abspieler erwartet: 16 Bit, mono, feste Abtastrate.
 *
 * Im Normalfall (die Engine liefert schon genau das) reicht [feed] die Bytes unverändert durch. Sonst wird
 * umgerechnet: mehrere Kanäle werden gemittelt, 8-Bit- und Kommazahl-Ton auf 16 Bit gebracht, und eine
 * abweichende Abtastrate wird geradlinig zwischen den Nachbarwerten umgerechnet (für Sprache genügt das).
 * Stücke dürfen an beliebiger Stelle enden, auch mitten in einem Wert; der Rest wartet auf das nächste Stück.
 */
internal class PcmShaper(private val targetRate: Int) {
    private var rate = targetRate
    private var encoding = PcmEncoding.PCM_16
    private var channels = 1
    private var usable = true

    /** Bytes vom Ende des letzten Stücks, die noch keinen ganzen Wert ergeben. */
    private var rest = EMPTY
    /** Letzter Wert des vorigen Stücks: der linke Nachbar für Werte, die zwischen zwei Stücke fallen. */
    private var last = 0
    /** Wo der nächste Ausgabewert liegt, gezählt in Eingabewerten ab dem Anfang des nächsten Stücks (-1 = [last]). */
    private var position = 0.0

    /** Merkt sich das Format, das die Engine ankündigt. false = unbekanntes Format, [feed] liefert dann nichts. */
    fun begin(format: VoiceEvent.Begin): Boolean {
        usable = format.encoding != PcmEncoding.OTHER && format.channels in 1..8 && format.sampleRate in 4_000..192_000
        if (usable) {
            rate = format.sampleRate
            encoding = format.encoding
            channels = format.channels
        }
        return usable
    }

    /** Ein Stück Ton hinein, 16 Bit mono in der festen Rate heraus (kann leer sein). */
    fun feed(bytes: ByteArray): ByteArray {
        if (!usable || bytes.isEmpty()) return EMPTY
        // Normalfall: nichts umzurechnen, die Bytes gehen durch, wie sie sind.
        if (rate == targetRate && encoding == PcmEncoding.PCM_16 && channels == 1) return bytes

        val data = if (rest.isEmpty()) bytes else rest + bytes
        val frameBytes = encoding.bytes * channels // ein "Rahmen" = ein Wert je Kanal
        val frames = data.size / frameBytes
        rest = if (frames * frameBytes == data.size) EMPTY else data.copyOfRange(frames * frameBytes, data.size)
        if (frames == 0) return EMPTY

        val mono = IntArray(frames) { frame ->
            var sum = 0
            for (channel in 0 until channels) sum += valueAt(data, (frame * channels + channel) * encoding.bytes)
            sum / channels
        }
        val out = if (rate == targetRate) mono else resample(mono)
        val result = ByteArray(out.size * 2)
        for (i in out.indices) {
            val value = out[i].coerceIn(-32768, 32767)
            result[2 * i] = value.toByte()
            result[2 * i + 1] = (value shr 8).toByte()
        }
        return result
    }

    /** Ein Abtastwert ab Byte [at], umgerechnet auf den Bereich von 16 Bit. */
    private fun valueAt(data: ByteArray, at: Int): Int = when (encoding) {
        PcmEncoding.PCM_8 -> ((data[at].toInt() and 0xFF) - 128) shl 8
        PcmEncoding.PCM_FLOAT -> {
            val bits = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or
                ((data[at + 2].toInt() and 0xFF) shl 16) or (data[at + 3].toInt() shl 24)
            val value = Float.fromBits(bits)
            if (value.isNaN()) 0 else (value.coerceIn(-1f, 1f) * 32767f).roundToInt()
        }
        else -> (data[at].toInt() and 0xFF) or (data[at + 1].toInt() shl 8)
    }

    /**
     * Rechnet [mono] von der Rate der Engine auf die feste Rate um. Jeder Ausgabewert liegt irgendwo
     * zwischen zwei Eingabewerten und wird aus beiden anteilig gemischt.
     */
    private fun resample(mono: IntArray): IntArray {
        val step = rate.toDouble() / targetRate // so viele Eingabewerte liegen zwischen zwei Ausgabewerten
        val n = mono.size
        val out = IntArray(((n + 1) / step).toInt() + 2)
        var count = 0
        var at = position
        while (at <= n - 1) {
            val left = floor(at).toInt()
            val share = at - left
            val a = if (left < 0) last else mono[left]
            val b = if (left + 1 < n) mono[left + 1] else a
            out[count++] = (a + (b - a) * share).roundToInt()
            at += step
        }
        last = mono[n - 1]
        position = at - n
        return out.copyOf(count)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
