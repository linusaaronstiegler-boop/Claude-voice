package app.claudevoice.core

import kotlin.math.log10
import kotlin.math.sqrt

/** Was wir akustisch über eine Äußerung wissen: wie laut sie am Mikro ankam und wie hoch die Stimme ist. */
data class VoiceFeatures(
    /** Pegel der aktiven Sprache in dBFS (0 = Vollaussteuerung, -60 = sehr leise). */
    val levelDb: Double,
    /** Grundfrequenz der Stimme in Hz (Median), null wenn nicht bestimmbar. */
    val pitchHz: Double?,
    val speechMs: Int,
)

/**
 * Misst das Mikrofonsignal nebenbei mit, während es zur Transkription geht.
 *
 * Warum: Die Transkription liefert nur Text. Ob die eigene Stimme, das Gegenüber oder jemand
 * am Nebentisch gesprochen hat, steht nicht drin. Zwei billige Merkmale helfen:
 *  - Lautstärke: Wer näher am Mikro ist, kommt lauter an.
 *  - Tonhöhe: Jede Stimme hat eine typische Grundfrequenz (tief ~100 Hz, hoch ~220 Hz).
 *
 * Das Signal wird in 30-ms-Stücke (Frames) geteilt. Pro Frame: Pegel (RMS) und, falls es ein
 * stimmhafter Laut ist, die Tonhöhe per Autokorrelation: man verschiebt das Signal gegen sich
 * selbst und sucht die Verschiebung, bei der es sich am besten wiederholt. Das ist die Periode.
 */
class VoiceMeter(private val sampleRate: Int = 16000) {
    private val frameSize = sampleRate * 30 / 1000
    private val frame = ShortArray(frameSize)
    private var filled = 0
    private var carry = -1 // einzelnes Byte, dessen Partner erst im nächsten Stück kommt

    private var noiseDb = -50.0
    private val levels = ArrayList<Double>()
    private val pitches = ArrayList<Double>()

    private val minLag = sampleRate / 350 // höchste erwartete Stimme
    private val maxLag = sampleRate / 70  // tiefste erwartete Stimme

    /** Rohes PCM (16 Bit, little endian, mono) einspeisen. */
    @Synchronized
    fun feed(pcm: ByteArray, length: Int) {
        var i = 0
        if (carry >= 0 && length > 0) {
            push(((pcm[0].toInt() shl 8) or carry).toShort())
            carry = -1
            i = 1
        }
        while (i + 1 < length) {
            push(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort())
            i += 2
        }
        if (i < length) carry = pcm[i].toInt() and 0xFF
    }

    /** Merkmale der Sprache seit dem letzten Aufruf; null, wenn kaum Sprache dabei war. */
    @Synchronized
    fun takeSummary(): VoiceFeatures? {
        val speechMs = levels.size * 30
        if (speechMs < MIN_SPEECH_MS) {
            levels.clear(); pitches.clear()
            return null
        }
        // "Aktiver Sprachpegel": der Wert, den ein Viertel der Frames übertrifft (75. Perzentil).
        // Leise Silbenränder ziehen ihn nicht herunter, und ein einzelner lauter Ausreißer
        // (etwa der Rest der vorigen Äußerung) zieht ihn nicht hoch, wie es ein Mittelwert täte.
        val sorted = levels.sortedDescending()
        val level = sorted[sorted.size / 4]
        val pitch = if (pitches.size >= 5) pitches.sorted()[pitches.size / 2] else null
        levels.clear(); pitches.clear()
        return VoiceFeatures(level, pitch, speechMs)
    }

    private fun push(sample: Short) {
        frame[filled++] = sample
        if (filled == frameSize) {
            analyse()
            filled = 0
        }
    }

    private fun analyse() {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / frameSize)
        val db = if (rms < 1.0) -96.0 else 20 * log10(rms / 32768.0)

        // Grundrauschen: folgt sofort nach unten, kriecht langsam nach oben (ca. 0,7 dB pro Sekunde).
        noiseDb = if (db < noiseDb) db else minOf(db, noiseDb + 0.02)
        val isSpeech = db > noiseDb + SPEECH_ABOVE_NOISE_DB && db > -55.0
        if (!isSpeech) return

        if (levels.size < MAX_FRAMES) levels += db
        pitchOf(sum)?.let { if (pitches.size < MAX_FRAMES) pitches += it }
    }

    /** Autokorrelation; liefert nur für klar stimmhafte Frames einen Wert. */
    private fun pitchOf(energy: Double): Double? {
        if (energy <= 0.0) return null
        val corr = DoubleArray(maxLag + 1)
        var best = 0.0
        for (lag in minLag..maxLag) {
            var c = 0.0
            for (i in 0 until frameSize - lag) c += frame[i].toDouble() * frame[i + lag]
            // Normieren, damit laute und leise Frames vergleichbar sind.
            corr[lag] = c / energy * frameSize / (frameSize - lag)
            if (corr[lag] > best) best = corr[lag]
        }
        if (best < VOICED_THRESHOLD) return null
        // Eine Periode wiederholt sich auch bei 2x, 3x ... Verschiebung. Wir wollen die kürzeste,
        // also das erste lokale Maximum, das fast so gut ist wie das beste.
        for (lag in minLag + 1 until maxLag) {
            if (corr[lag] >= 0.85 * best && corr[lag] >= corr[lag - 1] && corr[lag] >= corr[lag + 1]) {
                return sampleRate.toDouble() / lag
            }
        }
        return null
    }

    private companion object {
        const val MIN_SPEECH_MS = 150
        const val SPEECH_ABOVE_NOISE_DB = 12.0
        const val VOICED_THRESHOLD = 0.5
        const val MAX_FRAMES = 2000 // eine Minute Sprache reicht als Obergrenze
    }
}
