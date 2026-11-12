package app.claudevoice

import android.app.Application
import android.content.SharedPreferences
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
