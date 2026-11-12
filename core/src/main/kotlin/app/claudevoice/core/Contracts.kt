package app.claudevoice.core

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
         */
        fun onPartial(text: String, turnProbability: Double?)

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
}

/** Spricht Sätze hörbar aus (Android: AudioTrack, Audiofokus). */
interface Speaker {
    /**
     * Spricht die eintreffenden Sätze nacheinander und kehrt zurück, wenn der letzte ausgespielt ist.
     * Kommt kein einziger Satz, passiert nichts (insbesondere wird fremde Musik nicht unterbrochen).
     * [onFirstAudio] wird aufgerufen, sobald der erste Ton hörbar wird.
     */
    suspend fun speak(sentences: ReceiveChannel<String>, onFirstAudio: () -> Unit = {})

    /** Bricht die laufende Ausgabe sofort ab. */
    fun stop()
}
