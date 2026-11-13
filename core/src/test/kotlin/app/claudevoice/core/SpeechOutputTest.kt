package app.claudevoice.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Welche Sprachausgabe gilt, welche Schlüssel dafür nötig sind, und was die Meldung sagt, wenn einer fehlt. */
class SpeechOutputTest {
    private val a = "sk-ant-test"
    private val e = "el-test"
    private val s = "sx-test"

    @Test fun `ohne Wahl folgt die Sprachausgabe dem ElevenLabs-Schluessel`() {
        assertEquals(SpeechOutput.ELEVEN, SpeechOutput.resolve(null, hasElevenKey = true))
        assertEquals(SpeechOutput.DEVICE, SpeechOutput.resolve(null, hasElevenKey = false))
    }

    @Test fun `eine ausdrueckliche Wahl gewinnt immer`() {
        assertEquals(SpeechOutput.DEVICE, SpeechOutput.resolve(SpeechOutput.DEVICE, hasElevenKey = true))
        assertEquals(SpeechOutput.ELEVEN, SpeechOutput.resolve(SpeechOutput.ELEVEN, hasElevenKey = false))
    }

    @Test fun `der gespeicherte Wert laesst sich zuruecklesen`() {
        for (output in SpeechOutput.entries) assertEquals(output, SpeechOutput.fromKey(output.key))
        assertNull(SpeechOutput.fromKey(null))
        assertNull("ein unbekannter Wert gilt als nicht gewaehlt", SpeechOutput.fromKey("lautsprecher"))
    }

    @Test fun `mit ElevenLabs als Sprachausgabe ist der ElevenLabs-Schluessel immer noetig`() {
        assertNull(StartKeys.problem(a, e, s, SpeechOutput.ELEVEN))
        assertNull(StartKeys.problem(a, e, "", SpeechOutput.ELEVEN))
        assertEquals(
            "Der ElevenLabs-Key fehlt. Er wird für die Sprachausgabe „ElevenLabs“ gebraucht. Ohne ElevenLabs-Konto " +
                "geht es mit der Sprachausgabe „Stimme des Geräts“. Bitte in den Einstellungen eintragen.",
            StartKeys.problem(a, "", s, SpeechOutput.ELEVEN),
        )
        assertEquals(
            "Der ElevenLabs-Key fehlt. Er wird für die Transkription und für die Sprachausgabe „ElevenLabs“ " +
                "gebraucht. Bitte in den Einstellungen eintragen.",
            StartKeys.problem(a, "", "", SpeechOutput.ELEVEN),
        )
    }

    @Test fun `mit der Stimme des Geraets genuegt ein Schluessel fuer die Transkription`() {
        assertNull(StartKeys.problem(a, e, s, SpeechOutput.DEVICE))
        assertNull(StartKeys.problem(a, e, "", SpeechOutput.DEVICE))
        assertNull("ganz ohne ElevenLabs-Konto", StartKeys.problem(a, "", s, SpeechOutput.DEVICE))
        assertEquals(
            "Für die Transkription fehlt ein Schlüssel: entweder der ElevenLabs-Key oder der Soniox-Key. " +
                "Bitte in den Einstellungen eintragen.",
            StartKeys.problem(a, "", "", SpeechOutput.DEVICE),
        )
    }

    @Test fun `der Anthropic-Schluessel ist immer noetig und fehlt er wird alles Fehlende genannt`() {
        for (speech in SpeechOutput.entries) {
            assertEquals(
                "Der Anthropic-Key fehlt. Ohne ihn gibt es keine Antworten. Bitte in den Einstellungen eintragen.",
                StartKeys.problem("", e, s, speech),
            )
        }
        val both = StartKeys.problem("", "", "", SpeechOutput.DEVICE)!!
        assertTrue(both, both.startsWith(StartKeys.NO_ANTHROPIC) && StartKeys.NO_TRANSCRIPTION in both)
    }

    // --- Hinweis auf die Stimme des Geräts, wenn ElevenLabs am Konto scheitert -------------------

    private class Failing(private val error: Exception) : SpeechSynth {
        override val sampleRate = 24000
        var prepared = 0
        override suspend fun synthesize(sentences: ReceiveChannel<String>, onPcm: suspend (ByteArray, Int) -> Unit) = throw error
        override fun prepare() { prepared++ }
    }

    private suspend fun errorOf(error: Exception): Throwable? =
        runCatching { AccountHintSynth(Failing(error)).synthesize(Channel()) { _, _ -> } }.exceptionOrNull()

    @Test fun `scheitert ElevenLabs am Konto nennt die Meldung den Ausweg`() = runTest {
        // Die Wortlaute sind die, die ElevenTts wirklich erzeugt (siehe ElevenTts.describeFailure).
        assertEquals(
            "ElevenLabs-Guthaben ist aufgebraucht (401). ${AccountHintSynth.HINT}",
            errorOf(IOException("ElevenLabs-Guthaben ist aufgebraucht (401)"))?.message,
        )
        assertEquals(
            "ElevenLabs-Key wurde abgelehnt (401). ${AccountHintSynth.HINT}",
            errorOf(IOException("ElevenLabs-Key wurde abgelehnt (401)"))?.message,
        )
    }

    @Test fun `andere Fehler der Sprachausgabe bleiben wie sie sind`() = runTest {
        val network = IOException("Sprachausgabe unterbrochen: Verbindung getrennt")
        assertSame(network, errorOf(network))
        val odd = IllegalStateException("etwas anderes")
        assertSame(odd, errorOf(odd))
    }

    @Test fun `Abtastrate und Vorwaermen kommen von der eigentlichen Sprachausgabe`() {
        val inner = Failing(IOException("x"))
        val synth = AccountHintSynth(inner)
        assertEquals(24000, synth.sampleRate)
        synth.prepare()
        assertEquals(1, inner.prepared)
    }
}
