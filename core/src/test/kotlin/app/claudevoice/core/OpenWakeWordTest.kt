package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Prüft den Weckwort-Erkenner mit echten Sprachaufnahmen (lokal synthetisiert, Stimme "Thorsten", CC0).
 * Die Modelle lädt Gradle vor dem Testlauf; ohne Netz überspringen sich diese Tests.
 */
class OpenWakeWordTest {
    private val pause = WakeAudio.silence(1.0)
    private fun heyJarvis() = WakeAudio.clip("pos_thorsten_de.wav")

    @Test fun `Hey Jarvis loest genau einmal aus, in Mikrofon-Stuecken von 100 ms`() {
        WakeAudio.detector().use { d ->
            assertEquals(1, WakeAudio.play(d, pause + heyJarvis() + pause).detections)
        }
    }

    @Test fun `Portierung rechnet wie das Original`() {
        // Die Vergleichswerte stammen aus der Python-Vorlage (openwakeword), gleicher Ton, gleiche Stille davor und danach.
        val expected = WakeAudio.referenceScores("pos_thorsten_de.scores.txt")
        val run = WakeAudio.detector().use { WakeAudio.playFrames(it, pause + heyJarvis() + pause) }
        assertEquals(expected.size, run.scores.size)
        val worst = expected.indices.maxOf { abs(expected[it] - run.scores[it]) }
        assertTrue("größte Abweichung $worst", worst < 0.01f)
        assertTrue("höchster Score ${run.maxScore}", run.maxScore > 0.9f)
    }

    @Test fun `gewoehnliche Saetze, Stille, Rauschen und Toene loesen nicht aus`() {
        WakeAudio.detector().use { d ->
            assertEquals(0, WakeAudio.play(d, pause + WakeAudio.clip("neg_de_thorsten1.wav") + pause).detections)
            assertEquals(0, WakeAudio.play(d, WakeAudio.silence(5.0)).detections)
            assertEquals(0, WakeAudio.play(d, WakeAudio.noise(5.0, 3000)).detections)
            assertEquals(0, WakeAudio.play(d, WakeAudio.tone(3.0, 440.0, 8000)).detections)
        }
    }

    @Test fun `zwei Rufe mit Abstand zaehlen zweimal, nach reset beginnt alles neu`() {
        WakeAudio.detector().use { d ->
            val twice = pause + heyJarvis() + WakeAudio.silence(4.0) + heyJarvis() + pause
            assertEquals(2, WakeAudio.play(d, twice).detections)
            d.reset()
            assertEquals(1, WakeAudio.play(d, pause + heyJarvis() + pause).detections)
        }
    }

    @Test fun `leiser Ruf und Ruf ueber Rauschen werden noch erkannt`() {
        WakeAudio.detector().use { d ->
            assertEquals(1, WakeAudio.play(d, pause + WakeAudio.gain(heyJarvis(), 0.3) + pause).detections)
            d.reset()
            val noisy = WakeAudio.mix(pause + heyJarvis() + pause, WakeAudio.noise(3.5, 600))
            assertEquals(1, WakeAudio.play(d, noisy).detections)
        }
    }
}
