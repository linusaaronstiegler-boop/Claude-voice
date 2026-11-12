package app.claudevoice.core

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

/** Ein Stück einer Äußerung mit Sprecher-ID des Transkriptions-Anbieters (Diarisierung). */
data class SpokenPart(val speaker: String?, val text: String, val startSec: Double, val endSec: Double)

/**
 * Live-Transkription. Die App kennt nur diese Schnittstelle; welcher Anbieter dahinter steckt,
 * ist austauschbar (und in Tests eine Attrappe).
 */
interface Transcriber {
    fun connect()
    /** Rohes PCM, 16 kHz, mono, 16 Bit little endian. */
    fun sendAudio(pcm: ByteArray, length: Int)
    fun close()

    interface Listener {
        /**
         * Zwischenstand der laufenden Äußerung; spätere Zwischenstände ersetzen frühere.
         * [turnProbability] 0..1: wie sicher der Anbieter ist, dass der Sprecher fertig ist (null = unbekannt).
         * [speaker] ist die vorläufige Sprecher-ID des Anbieters, dieselbe, die später [onCommitted] nennt
         * (null = unbekannt). Mit ihr kann die App schon vor dem Satzende einschätzen, wer spricht.
         */
        fun onPartial(text: String, turnProbability: Double?, speaker: String? = null)

        /**
         * Fertige Äußerung. [speaker] ist eine vorläufige Sprecher-ID des Anbieters oder null,
         * [segment] eine laufende Nummer, über die später [onDiarized] zugeordnet wird.
         */
        fun onCommitted(text: String, speaker: String?, segment: Int?)

        /** Nachgereichte, genauere Sprecher-Zuordnung zu einer früheren Äußerung. */
        fun onDiarized(segment: Int, parts: List<SpokenPart>) {}

        /** Der Anbieter hat erkannt, dass zwei Sprecher-IDs dieselbe Person sind. */
        fun onSpeakersMerged(from: String, to: String) {}

        /** [fresh] = neue Sitzung: Sprecher-IDs und Segmentnummern beginnen von vorn. */
        fun onConnection(connected: Boolean, error: String?, fresh: Boolean = false)
    }
}

/**
 * Wandelt Sätze in Sprache um (reines Netz-/Datenformat, ohne Android).
 * Liefert rohes PCM (16 Bit mono, [sampleRate]) in Abspielreihenfolge an [onPcm].
 */
interface SpeechSynth {
    val sampleRate: Int

    /**
     * Liest Sätze aus [sentences], bis der Kanal geschlossen ist, und liefert deren Audio lückenlos.
     * Kehrt zurück, wenn alles geliefert wurde. Wird die Coroutine abgebrochen, endet die Übertragung sofort.
     */
    suspend fun synthesize(sentences: ReceiveChannel<String>, onPcm: suspend (ByteArray, Int) -> Unit)

    /**
     * Verbindung schon einmal aufbauen, damit der nächste Satz schneller klingt.
     * Kehrt sofort zurück, darf beliebig oft gerufen werden und darf nichts kosten, wenn schon alles bereit ist.
     */
    fun prepare() {}
}

/**
 * Erkennt ein Weckwort ("Hey Jarvis") lokal im Audiostrom, ohne Netz.
 * Damit kann die App in Bereitschaft lauschen, ohne etwas in die Cloud zu schicken.
 */
interface WakeWordDetector : AutoCloseable {
    /** 16 kHz mono PCM (16 Bit little endian) einspeisen. Liefert true genau dann, wenn das Weckwort soeben erkannt wurde. */
    fun feed(pcm: ByteArray, length: Int): Boolean

    /** Internen Zustand leeren, z. B. nach einer Erkennung oder einer Pause. */
    fun reset()
}

/** Spricht Sätze hörbar aus (Android: AudioTrack, Audiofokus). */
interface Speaker {
    /**
     * Spricht die eintreffenden Sätze nacheinander und kehrt zurück, wenn der letzte ausgespielt ist.
     * Kommt kein einziger Satz, passiert nichts (insbesondere wird fremde Musik nicht unterbrochen).
     * [onFirstAudio] wird aufgerufen, sobald der erste Ton hörbar wird.
     *
     * [release] ist eine Schranke vor dem Lautsprecher: Ist sie übergeben, wird sofort Sprache erzeugt,
     * aber erst abgespielt, wenn sie `true` liefert. Bei `false` wird alles verworfen und nichts erklingt.
     * So kann die Sprachausgabe schon arbeiten, während noch offen ist, ob ein Mensch selbst antwortet.
     */
    suspend fun speak(
        sentences: ReceiveChannel<String>,
        release: Deferred<Boolean>? = null,
        onFirstAudio: () -> Unit = {},
    )

    /** Bricht die laufende Ausgabe sofort ab. */
    fun stop()

    /** Sprachausgabe vorwärmen, weil gleich eine Antwort kommen könnte. Kehrt sofort zurück. */
    fun prepare() {}
}
