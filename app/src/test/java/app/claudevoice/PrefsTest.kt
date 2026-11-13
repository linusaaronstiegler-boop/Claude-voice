package app.claudevoice

import android.app.Application
import android.content.SharedPreferences
import app.claudevoice.core.MicMode
import app.claudevoice.core.SpeechOutput
import app.claudevoice.core.VoiceEnrollment
import app.claudevoice.core.VoiceProfile
import app.claudevoice.ui.forgetVoiceProfileIfStopped
import app.claudevoice.ui.voiceProfileRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Die Einstellungen: Standardwerte, Reinigung der Schlüssel und die eine geteilte Instanz.
 * Läuft mit echter Verschlüsselung; nur der Android Keystore ist nachgestellt ([FakeAndroidKeyStore]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrefsTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private lateinit var prefs: Prefs

    @Before fun setUp() {
        prefs = FakeAndroidKeyStore.freshPrefs(context)
    }

    /** Die Datei hinter den Einstellungen, um einen Wert so abzulegen, wie eine ältere App-Version es tat. */
    private fun backing(prefs: Prefs): SharedPreferences =
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.get(prefs) as SharedPreferences

    @Test fun `ohne eigene Wahl gilt das Kopfhoerer-Mikrofon`() {
        // Nur am Kopfhörer-Mikro lässt sich die eigene Stimme erkennen; daran hängen Merken und Stummschalten.
        assertTrue(prefs.useBluetoothMic)
    }

    @Test fun `die Wahl des Mikrofons bleibt erhalten`() {
        prefs.useBluetoothMic = false
        assertFalse(Prefs(context).useBluetoothMic)
        prefs.useBluetoothMic = true
        assertTrue(Prefs(context).useBluetoothMic)
    }

    @Test fun `Schluessel werden beim Speichern von unsichtbaren Zeichen befreit`() {
        prefs.anthropicKey = " sk-ant-0123\n"
        prefs.elevenKey = "sk_0123456789abcdef​"
        prefs.sonioxKey = "„abc123“"
        prefs.voiceId = " JBFq nCBs\t"
        assertEquals("sk-ant-0123", prefs.anthropicKey)
        assertEquals("sk_0123456789abcdef", prefs.elevenKey)
        assertEquals("abc123", prefs.sonioxKey)
        assertEquals("JBFqnCBs", prefs.voiceId)
        // Auch in der Datei selbst steht der saubere Wert.
        assertEquals("sk_0123456789abcdef", backing(prefs).getString("eleven", null))
    }

    @Test fun `ein schon unsauber gespeicherter Schluessel wird beim Lesen geheilt`() {
        backing(prefs).edit()
            .putString("eleven", "sk_0123456789abcdef​")
            .putString("anthropic", "sk-ant-0123\r\n")
            .putString("soniox", "abc 123")
            .putString("voice", "​")
            .commit()
        assertEquals("sk_0123456789abcdef", prefs.elevenKey)
        assertEquals("sk-ant-0123", prefs.anthropicKey)
        assertEquals("abc123", prefs.sonioxKey)
        assertEquals("eine leere Voice-ID fällt auf die Standardstimme zurück", Prefs.DEFAULT_VOICE, prefs.voiceId)
        assertTrue(prefs.hasKeys)
    }

    @Test fun `ein Schluessel nur aus unsichtbaren Zeichen zaehlt als fehlend`() {
        prefs.anthropicKey = "​\n"
        prefs.elevenKey = "sk_1"
        assertFalse(prefs.hasKeys)
    }

    // --- Sprachausgabe und nötige Schlüssel ---------------------------------------------------

    @Test fun `ohne Wahl richtet sich die Sprachausgabe nach dem ElevenLabs-Schluessel`() {
        assertEquals("ohne Schlüssel: die Stimme des Geräts", SpeechOutput.DEVICE, prefs.speechOutput)
        prefs.elevenKey = "sk_1"
        assertEquals(SpeechOutput.ELEVEN, prefs.speechOutput)
        assertEquals("gewählt wurde dabei nichts", null, prefs.speechChoice)
        prefs.elevenKey = ""
        assertEquals(SpeechOutput.DEVICE, prefs.speechOutput)
    }

    @Test fun `eine ausdrueckliche Wahl der Sprachausgabe bleibt erhalten und gewinnt`() {
        prefs.elevenKey = "sk_1"
        prefs.speechChoice = SpeechOutput.DEVICE
        assertEquals(SpeechOutput.DEVICE, Prefs(context).speechOutput)
        prefs.elevenKey = ""
        prefs.speechChoice = SpeechOutput.ELEVEN
        assertEquals("auch ohne Schlüssel gilt die Wahl; der Start meldet dann, was fehlt", SpeechOutput.ELEVEN, prefs.speechOutput)
        prefs.speechChoice = null
        assertEquals(SpeechOutput.DEVICE, prefs.speechOutput)
    }

    @Test fun `mit der Stimme des Geraets genuegen Anthropic und Soniox`() {
        prefs.anthropicKey = "sk-ant-1"
        assertFalse("es fehlt noch ein Schlüssel für die Transkription", prefs.hasKeys)
        assertEquals(
            "Für die Transkription fehlt ein Schlüssel: entweder der ElevenLabs-Key oder der Soniox-Key. " +
                "Bitte in den Einstellungen eintragen.",
            prefs.keyProblem,
        )
        prefs.sonioxKey = "sx_1"
        assertTrue(prefs.hasKeys)
        // Wer ausdrücklich ElevenLabs sprechen lassen will, braucht dessen Schlüssel.
        prefs.speechChoice = SpeechOutput.ELEVEN
        assertFalse(prefs.hasKeys)
        prefs.elevenKey = "sk_1"
        assertTrue(prefs.hasKeys)
    }

    // --- Stimmprofil --------------------------------------------------------------------------

    @Test fun `ein gespeichertes Stimmprofil wird gelesen und laesst sich je Mikrofon vergessen`() {
        assertEquals(null, prefs.storedVoiceProfile(MicMode.AIRPODS))
        prefs.saveVoiceProfile(MicMode.AIRPODS.name, "-18.5;118.0;12")
        prefs.saveVoiceProfile(MicMode.HANDY.name, "-30.0;;3")
        assertEquals(VoiceProfile(-18.5, 118.0, 12), Prefs(context).storedVoiceProfile(MicMode.AIRPODS))
        assertEquals("ohne gemessene Tonhöhe", VoiceProfile(-30.0, null, 3), prefs.storedVoiceProfile(MicMode.HANDY))

        prefs.forgetVoiceProfile(MicMode.AIRPODS.name)
        assertEquals(null, Prefs(context).storedVoiceProfile(MicMode.AIRPODS))
        assertEquals("das andere Mikrofon behält seines", VoiceProfile(-30.0, null, 3), prefs.storedVoiceProfile(MicMode.HANDY))
        // Vergessen nimmt nur weg: In der Datei steht danach nichts mehr zu diesem Mikrofon.
        assertFalse(backing(prefs).contains("voice_AIRPODS"))
    }

    @Test fun `solange zugehoert wird bleibt das Stimmprofil liegen`() {
        prefs.saveVoiceProfile(MicMode.AIRPODS.name, "-18.5;118.0;12")
        // Die laufende Sitzung schriebe ihr Profil gleich wieder zurück; deshalb wird gar nicht erst gelöscht.
        assertFalse(prefs.forgetVoiceProfileIfStopped(MicMode.AIRPODS.name, running = true))
        assertEquals(VoiceProfile(-18.5, 118.0, 12), prefs.storedVoiceProfile(MicMode.AIRPODS))
        assertTrue(prefs.forgetVoiceProfileIfStopped(MicMode.AIRPODS.name, running = false))
        assertEquals(null, prefs.storedVoiceProfile(MicMode.AIRPODS))
    }

    @Test fun `eine beendete Sitzung bringt ein vergessenes Stimmprofil nicht zurueck`() {
        // Die laufende Sitzung speichert, was sie gelernt hat, und es laesst sich genauso wieder lesen.
        assertTrue(prefs.storeVoiceProfile(MicMode.AIRPODS, VoiceProfile(-18.5, 118.0, 12), ended = false))
        assertEquals(VoiceProfile(-18.5, 118.0, 12), prefs.storedVoiceProfile(MicMode.AIRPODS))
        assertTrue(prefs.storeVoiceProfile(MicMode.HANDY, VoiceProfile(-30.0, null, 3), ended = false))
        assertEquals("ohne gemessene Tonhöhe", VoiceProfile(-30.0, null, 3), prefs.storedVoiceProfile(MicMode.HANDY))

        // Die Sitzung ist zu Ende, der Nutzer laesst das Profil vergessen. Meldet sich danach noch ein
        // Nachzuegler der alten Sitzung, darf das Profil nicht wieder auftauchen.
        assertTrue(prefs.forgetVoiceProfileIfStopped(MicMode.AIRPODS.name, running = false))
        assertFalse(prefs.storeVoiceProfile(MicMode.AIRPODS, VoiceProfile(-18.0, 120.0, 13), ended = true))
        assertEquals(null, Prefs(context).storedVoiceProfile(MicMode.AIRPODS))
    }

    @Test fun `die Einstellungen zeigen beide Mikrofon-Arten auch ohne Profil`() {
        prefs.saveVoiceProfile(MicMode.AIRPODS.name, "-18.5;118.0;12")
        val rows = prefs.voiceProfileRows()
        assertEquals(listOf("AIRPODS" to 12, "HANDY" to null), rows.map { it.mic to it.samples })
        assertEquals(listOf("AirPods-Mikro", "Handy-Mikro"), rows.map { it.label })
    }

    @Test fun `ein eingelerntes Profil wird je Mikrofon gespeichert und in den Einstellungen mit Zahlen gezeigt`() {
        prefs.saveEnrolledVoiceProfile(MicMode.AIRPODS, VoiceProfile(-33.2, 118.6, VoiceEnrollment.ENROLLED_SAMPLES))
        assertEquals(VoiceProfile(-33.2, 118.6, 50), Prefs(context).storedVoiceProfile(MicMode.AIRPODS))
        assertEquals("das andere Mikrofon bleibt ohne Profil", null, prefs.storedVoiceProfile(MicMode.HANDY))
        val rows = prefs.voiceProfileRows()
        assertEquals(listOf("-33 dBFS · 119 Hz", null), rows.map { it.numbers })
        // Einlernen ersetzt ein altes, vielleicht falsch gelerntes Profil.
        prefs.saveVoiceProfile(MicMode.HANDY.name, "-20.0;210.0;4")
        prefs.saveEnrolledVoiceProfile(MicMode.HANDY, VoiceProfile(-29.0, 121.0, 50))
        assertEquals(VoiceProfile(-29.0, 121.0, 50), prefs.storedVoiceProfile(MicMode.HANDY))
    }

    @Test fun `Messwerte in der Mitschrift sind aus bis man sie einschaltet`() {
        assertFalse(prefs.showMeasurements)
        prefs.showMeasurements = true
        assertTrue(Prefs(context).showMeasurements)
    }

    @Test fun `ein unlesbares Stimmprofil gilt als keines`() {
        assertEquals(null, parseVoiceProfile(null))
        assertEquals(null, parseVoiceProfile(""))
        assertEquals(null, parseVoiceProfile("laut;hoch;oft"))
        assertEquals(VoiceProfile(-20.0, null, 1), parseVoiceProfile("-20.0"))
    }

    @Test fun `bestaetigte Ausgabegeraete werden gemerkt und lassen sich wieder vergessen`() {
        assertTrue(prefs.confirmedOutputs.isEmpty())
        prefs.confirmedOutputs = prefs.confirmedOutputs + "AA:BB:CC:DD:EE:FF"
        prefs.confirmedOutputs = prefs.confirmedOutputs + "Sport-Ohrhörer"
        assertEquals(setOf("AA:BB:CC:DD:EE:FF", "Sport-Ohrhörer"), Prefs(context).confirmedOutputs)
        prefs.confirmedOutputs = emptySet()
        assertTrue(Prefs(context).confirmedOutputs.isEmpty())
    }

    @Test fun `die verschluesselte Datei wird je Prozess nur einmal geoeffnet`() {
        val other = Prefs(context)
        assertSame(backing(prefs), backing(other))
        // Das Gedächtnis nutzt dieselben Einstellungen, statt die Datei ein zweites Mal zu öffnen.
        val memory = MemoryStore(context, prefs)
        prefs.linkedUris = listOf("content://beispiel/datei")
        assertEquals(1, memory.linked().size)
    }
}
