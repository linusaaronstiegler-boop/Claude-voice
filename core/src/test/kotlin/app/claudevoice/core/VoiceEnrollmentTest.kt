package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Das Einlernen der Stimme, geprüft mit künstlichen Signalen: Ein "Stimmton" ist ein Grundton mit zwei
 * Obertönen in bekannter Lautstärke und Tonhöhe. Echte Sprache ist unruhiger; was die Schwellen am Handy
 * taugen, zeigt nur der Gerätetest.
 */
class VoiceEnrollmentTest {
    private val rate = 16000

    /** Stille von [ms] Millisekunden. */
    private fun silence(ms: Int) = ShortArray(rate * ms / 1000)

    /** Stimmähnlicher Ton: Grundton [hz] plus zwei leisere Obertöne, auf den Pegel [db] (dBFS, RMS) gebracht. */
    private fun voice(ms: Int, db: Double, hz: Double): ShortArray {
        val raw = DoubleArray(rate * ms / 1000) { i ->
            val t = 2 * PI * hz * i / rate
            sin(t) + 0.5 * sin(2 * t) + 0.25 * sin(3 * t)
        }
        return scaled(raw, db)
    }

    /** Weißes Rauschen auf dem Pegel [db]: laut, aber ohne Tonhöhe. */
    private fun noise(ms: Int, db: Double): ShortArray {
        val random = Random(7)
        return scaled(DoubleArray(rate * ms / 1000) { random.nextGaussian() }, db)
    }

    private fun scaled(raw: DoubleArray, db: Double): ShortArray {
        val rms = sqrt(raw.sumOf { it * it } / raw.size)
        val factor = 32768.0 * 10.0.pow(db / 20) / rms
        return ShortArray(raw.size) { (raw[it] * factor).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    private fun bytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[2 * i] = (samples[i].toInt() and 0xFF).toByte()
            out[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
        return out
    }

    /** Speist die Stücke nacheinander ein, zerlegt in Häppchen von [chunk] Bytes (100 ms wie das echte Mikro). */
    private fun enroll(vararg parts: ShortArray, chunk: Int = 3200): VoiceEnrollment {
        val e = VoiceEnrollment()
        val all = bytes(parts.fold(ShortArray(0)) { a, b -> a + b })
        var i = 0
        while (i < all.size) {
            val n = minOf(chunk, all.size - i)
            e.feed(all.copyOfRange(i, i + n), n)
            i += n
        }
        return e
    }

    /** Ein Satz: 900 ms Stimme, dann eine kurze Atempause. */
    private fun sentences(count: Int, db: Double, hz: Double): Array<ShortArray> =
        Array(count * 2) { if (it % 2 == 0) voice(900, db, hz) else silence(300) }

    @Test fun `eine gleichmaessige Stimme ergibt ein Profil mit ihrem Pegel und ihrer Tonhoehe`() {
        val e = enroll(silence(300), *sentences(8, -27.0, 120.0))
        assertTrue(e.progress.done)
        val result = e.finish() as EnrollResult.Success
        assertEquals(-27.0, result.profile.levelDb, 1.0)
        assertEquals(120.0, result.profile.pitchHz!!, 3.0)
        assertEquals(VoiceEnrollment.ENROLLED_SAMPLES, result.profile.samples)
        assertNull(result.warning)
    }

    @Test fun `das Ergebnis haengt nicht davon ab wie der Ton gestueckelt ankommt`() {
        val parts = arrayOf(silence(300), *sentences(8, -24.0, 180.0))
        val even = enroll(*parts).finish() as EnrollResult.Success
        val odd = enroll(*parts, chunk = 1111).finish() as EnrollResult.Success
        assertEquals(even.profile, odd.profile)
    }

    @Test fun `der Fortschritt zaehlt nur Sprache, keine Pausen`() {
        val e = enroll(silence(3000))
        assertEquals(0, e.progress.voicedMs)
        assertEquals(3000, e.progress.heardMs)
        assertNull(e.progress.levelDb)
        assertFalse(e.progress.done)
        val half = enroll(silence(300), *sentences(3, -25.0, 120.0))
        assertTrue(half.progress.voicedMs in 2000..3000)
        assertTrue(half.progress.fraction in 0.4f..0.6f)
        assertFalse(half.progress.done)
    }

    @Test fun `waehrend gesprochen wird zeigt der Zwischenstand den Pegel`() {
        val e = enroll(silence(300), voice(900, -25.0, 120.0))
        assertEquals(-25.0, e.progress.levelDb!!, 1.5)
    }

    @Test fun `Stille wird abgelehnt`() {
        val result = enroll(silence(10_000)).finish() as EnrollResult.Failure
        assertTrue(result.reason, "keine Stimme" in result.reason)
    }

    @Test fun `ohne Ton heisst es dass das Mikrofon nichts geliefert hat`() {
        val result = VoiceEnrollment().finish() as EnrollResult.Failure
        assertTrue(result.reason, "keinen Ton" in result.reason)
    }

    @Test fun `Rauschen ohne Stimme wird abgelehnt`() {
        val result = enroll(silence(300), noise(10_000, -25.0)).finish() as EnrollResult.Failure
        assertTrue(result.reason, "Geräusch" in result.reason)
    }

    @Test fun `zu wenig Sprache wird abgelehnt und nennt die Dauer`() {
        val result = enroll(silence(300), *sentences(3, -25.0, 120.0)).finish() as EnrollResult.Failure
        assertTrue(result.reason, "Zu wenig Sprache" in result.reason)
        assertTrue(result.reason, "5,0 Sekunden" in result.reason)
    }

    @Test fun `zwei abwechselnde Stimmlagen werden abgelehnt`() {
        val parts = Array(16) { if (it % 2 == 0) voice(900, -25.0, if (it % 4 == 0) 115.0 else 215.0) else silence(300) }
        val result = enroll(silence(300), *parts).finish() as EnrollResult.Failure
        assertTrue(result.reason, "Stimmlage" in result.reason)
    }

    @Test fun `zwei abwechselnd laute und leise Stimmen werden abgelehnt`() {
        val parts = Array(16) { if (it % 2 == 0) voice(900, if (it % 4 == 0) -20.0 else -38.0, 120.0) else silence(300) }
        val result = enroll(silence(300), *parts).finish() as EnrollResult.Failure
        assertTrue(result.reason, "Lautstärke" in result.reason)
    }

    @Test fun `ein kurzer Zwischenruf verschiebt das Profil nicht`() {
        // Sieben Sätze der eigenen Stimme, dazwischen einer von jemand anderem: Der Median bleibt beim Träger.
        val parts = arrayOf(
            silence(300), *sentences(4, -26.0, 120.0), voice(900, -40.0, 220.0), silence(300), *sentences(3, -26.0, 120.0),
        )
        val result = enroll(*parts).finish() as EnrollResult.Success
        assertEquals(-26.0, result.profile.levelDb, 1.0)
        assertEquals(120.0, result.profile.pitchHz!!, 3.0)
    }

    @Test fun `eine sehr leise Stimme ergibt ein Profil mit Warnung`() {
        val result = enroll(silence(300), *sentences(8, -44.0, 120.0)).finish() as EnrollResult.Success
        assertEquals(-44.0, result.profile.levelDb, 1.0)
        assertNotNull(result.warning)
        assertTrue(result.warning!!, "sehr leise" in result.warning!!)
    }

    @Test fun `nach der Hoechstdauer ist Schluss auch ohne genug Sprache`() {
        val e = enroll(silence(VoiceEnrollment.MAX_HEARD_MS))
        assertTrue(e.progress.done)
        assertTrue(e.finish() is EnrollResult.Failure)
    }

    @Test fun `das eingelernte Profil macht eine leise eigene Stimme am Kopfhoerer zu ICH`() {
        // Der Anlass: Ohne Profil gilt -36 dBFS nicht als eigene Stimme (Bezug -22). Mit dem eingelernten
        // Profil stuft der Klassifikator dieselbe Äußerung als ICH ein, eine fremde Stimmlage weiterhin nicht.
        val heard = VoiceFeatures(-36.0, 121.0, 900)
        assertEquals(Voice.NAH, VoiceClassifier(MicMode.AIRPODS).classify(heard, record = false))
        val profile = (enroll(silence(300), *sentences(8, -35.0, 120.0)).finish() as EnrollResult.Success).profile
        val known = VoiceClassifier(MicMode.AIRPODS, profile)
        assertEquals(Voice.ICH, known.classify(heard))
        assertEquals(Voice.NAH, known.classify(VoiceFeatures(-36.0, 215.0, 900)))
        assertEquals(Voice.FERN, known.classify(VoiceFeatures(-62.0, 121.0, 900)))
    }
}
