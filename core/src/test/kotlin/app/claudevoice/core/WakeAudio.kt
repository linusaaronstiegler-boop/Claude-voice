package app.claudevoice.core

import org.junit.Assume
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * Hilfen für die Weckwort-Tests: Modelle finden, Testaufnahmen laden, Audio zusammensetzen.
 * Alles hier arbeitet mit rohem PCM (16 kHz, mono, 16 Bit little endian), so wie es das Mikrofon liefert.
 */
object WakeAudio {
    const val RATE = OpenWakeWord.SAMPLE_RATE

    /**
     * Die drei Modelle aus build/wakeword (dorthin lädt sie Gradle vor den Tests).
     * Fehlen sie (kein Netz beim ersten Lauf), wird der aufrufende Test übersprungen statt rot:
     * ein fehlender Download ist kein Fehler im Code.
     */
    fun detector(threshold: Float = 0.5f): OpenWakeWord {
        val dir = listOf(File("build/wakeword"), File("core/build/wakeword")).firstOrNull { it.isDirectory }
        val files = listOf(OpenWakeWord.MEL_FILE, OpenWakeWord.EMBEDDING_FILE, OpenWakeWord.WAKE_FILE)
            .map { File(dir ?: File("build/wakeword"), it) }
        Assume.assumeTrue(
            "Weckwort-Tests übersprungen: Die Modelle fehlen in ${files.first().parentFile.absolutePath}. " +
                "Sie werden beim Testlauf mit Netz automatisch geladen (./gradlew :core:downloadWakeWordModels).",
            files.all { it.isFile },
        )
        return OpenWakeWord(files[0].readBytes(), files[1].readBytes(), files[2].readBytes(), threshold)
    }

    /** Testaufnahme aus src/test/resources/wakeword als rohes PCM. */
    fun clip(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/wakeword/$name") ?: error("Testaufnahme fehlt: $name")
        return wavToPcm(stream.use { it.readBytes() })
    }

    /** Erwartete Scores der Python-Vorlage, ein Wert je 80-ms-Schritt. */
    fun referenceScores(name: String): FloatArray {
        val stream = javaClass.getResourceAsStream("/wakeword/$name") ?: error("Referenz fehlt: $name")
        return stream.use { it.readBytes() }.decodeToString().lines().filter { it.isNotBlank() }.map { it.trim().toFloat() }.toFloatArray()
    }

    /**
     * Holt die Abtastwerte aus einer WAV-Datei. Eine WAV-Datei besteht aus benannten Abschnitten
     * ("fmt " beschreibt das Format, "data" enthält den Ton); andere Abschnitte werden übersprungen.
     */
    fun wavToPcm(wav: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE") { "keine WAV-Datei" }
        var pos = 12
        var formatOk = false
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4)
            val size = buffer.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                val pcm = buffer.getShort(body).toInt() == 1
                val channels = buffer.getShort(body + 2).toInt()
                val rate = buffer.getInt(body + 4)
                val bits = buffer.getShort(body + 14).toInt()
                require(pcm && channels == 1 && rate == RATE && bits == 16) { "erwartet: 16 kHz, mono, 16 Bit PCM" }
                formatOk = true
            } else if (id == "data") {
                require(formatOk) { "WAV ohne Formatangabe" }
                return wav.copyOfRange(body, minOf(body + size, wav.size))
            }
            pos = body + size + (size and 1) // Abschnitte beginnen immer an einer geraden Stelle
        }
        error("WAV ohne Tondaten")
    }

    fun silence(seconds: Double): ByteArray = ByteArray((seconds * RATE).toInt() * 2)

    /** Weißes Rauschen mit der angegebenen Spitzen-Amplitude (32767 = Vollaussteuerung). */
    fun noise(seconds: Double, amplitude: Int, seed: Long = 1): ByteArray {
        val random = Random(seed)
        return samples((seconds * RATE).toInt()) { (random.nextInt(2 * amplitude + 1) - amplitude).toShort() }
    }

    /** Reiner Sinuston, z. B. ein Pfeifen oder Brummen. */
    fun tone(seconds: Double, hz: Double, amplitude: Int): ByteArray =
        samples((seconds * RATE).toInt()) { i -> (amplitude * Math.sin(2 * Math.PI * hz * i / RATE)).toInt().toShort() }

    /** Lautstärke ändern (1.0 = unverändert), mit Begrenzung auf den erlaubten Wertebereich. */
    fun gain(pcm: ByteArray, factor: Double): ByteArray {
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return samples(pcm.size / 2) { i -> (input.get(i) * factor).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** Zwei Signale übereinanderlegen (das kürzere wird mit Stille aufgefüllt). */
    fun mix(a: ByteArray, b: ByteArray): ByteArray {
        val x = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val y = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return samples(maxOf(a.size, b.size) / 2) { i ->
            val sum = (if (i < x.limit()) x.get(i).toInt() else 0) + (if (i < y.limit()) y.get(i).toInt() else 0)
            sum.coerceIn(-32768, 32767).toShort()
        }
    }

    private fun samples(count: Int, value: (Int) -> Short): ByteArray {
        val out = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) out.putShort(value(i))
        return out.array()
    }

    /** Ergebnis eines Durchlaufs: wie oft ausgelöst wurde und der Score jedes 80-ms-Schritts. */
    class Run(val detections: Int, val scores: FloatArray) {
        val maxScore: Float get() = scores.maxOrNull() ?: 0f
    }

    /**
     * Spielt [pcm] in Stücken von [chunkBytes] ein, so wie das Mikrofon es tut
     * (Standard: 100 ms = 3200 Bytes, wie im ListenService).
     */
    fun play(detector: OpenWakeWord, pcm: ByteArray, chunkBytes: Int = 3200): Run {
        var detections = 0
        var offset = 0
        while (offset < pcm.size) {
            val n = minOf(chunkBytes, pcm.size - offset)
            if (detector.feed(pcm.copyOfRange(offset, offset + n), n)) detections++
            offset += n
        }
        return Run(detections, FloatArray(0))
    }

    /** Wie [play], aber genau ein 80-ms-Schritt je Aufruf: so lässt sich der Score jedes Schritts ablesen. */
    fun playFrames(detector: OpenWakeWord, pcm: ByteArray): Run {
        val frameBytes = OpenWakeWord.FRAME * 2
        val scores = ArrayList<Float>()
        var detections = 0
        var offset = 0
        while (offset + frameBytes <= pcm.size) {
            if (detector.feed(pcm.copyOfRange(offset, offset + frameBytes), frameBytes)) detections++
            scores += detector.lastScore
            offset += frameBytes
        }
        return Run(detections, scores.toFloatArray())
    }
}
