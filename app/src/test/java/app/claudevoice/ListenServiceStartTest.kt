package app.claudevoice

import android.app.Application
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import app.claudevoice.core.UiState
import com.google.common.collect.ImmutableList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Der Start des Dienstes: Er darf die App nie abstürzen lassen, und er darf nur loslegen, wenn
 * feststeht, dass Antworten in Kopfhörer gehen und (in der Bereitschaft) der Weckwort-Erkenner da ist.
 *
 * Läuft mit Robolectric auf dem Rechner. Jeder Test hier endet VOR dem Öffnen von Mikrofon und
 * Netzverbindungen; geprüft wird also genau der Teil "im Zweifel gar nicht erst anfangen". Dass der
 * Dienst danach wirklich zuhört und spricht, zeigt nur ein Handy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenServiceStartTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val media = AndroidSpeaker.attributes(voiceCallRoute = false)
    private lateinit var prefs: Prefs
    private val services = ArrayList<ServiceController<ListenService>>()

    @Before fun setUp() {
        prefs = FakeAndroidKeyStore.freshPrefs(context)
        // Diese Tests beschreiben, wenn nichts anderes gesagt ist, den Betrieb mit dem Handy-Mikro.
        // Der Standard der App ist das Kopfhörer-Mikro; wer es prüfen will, schaltet es im Test selbst ein.
        prefs.useBluetoothMic = false
        AppState.ui.value = UiState()
        ListenService.confirmation.forget()
    }

    @After fun tearDown() {
        services.forEach { runCatching { it.destroy() } }
        FakeAndroidKeyStore.install()
    }

    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    /** Legt fest, wohin Android eine Antwort leiten würde. */
    private fun routeTo(vararg devices: AudioDeviceInfo) =
        shadowOf(audio).setAudioDevicesForAttributes(media, ImmutableList.copyOf(devices))

    private fun service(): ServiceController<ListenService> =
        Robolectric.buildService(ListenService::class.java).create().also { services += it }

    /** Lässt Hintergrund-Arbeit und Haupt-Thread laufen, bis [done] gilt (höchstens zehn Sekunden). */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /** Startet einen Dienst und wartet, bis er sich selbst beendet hat. Gibt die Meldung zurück. */
    private fun startAndExpectStop(): String {
        val controller = service()
        controller.startCommand(0, 1)
        waitFor { shadowOf(controller.get()).isStoppedBySelf }
        assertTrue("der Dienst hat sich nicht selbst beendet", shadowOf(controller.get()).isStoppedBySelf)
        // Bis hierher darf weder verbunden noch zugehört worden sein.
        assertEquals("Startet …", AppState.ui.value.status)
        val error = AppState.ui.value.error
        assertNotNull("es fehlt die Meldung, warum nicht gestartet wurde", error)
        controller.destroy()
        assertFalse(AppState.ui.value.running)
        assertEquals("die Meldung bleibt nach dem Ende stehen", error, AppState.ui.value.error)
        return error!!
    }

    // --- Kopfhörer ---------------------------------------------------------------------------

    @Test fun `ohne Kopfhoerer startet nichts`() {
        routeTo(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals("Bitte zuerst Kopfhörer verbinden: Die Antworten sollen nur Sie hören.", startAndExpectStop())
    }

    @Test fun `verbundene Kopfhoerer genuegen nicht wenn die Ausgabe auf dem Handy steht`() {
        shadowOf(audio).setOutputDevices(
            listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), device(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        )
        routeTo(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals("Bitte zuerst Kopfhörer verbinden: Die Antworten sollen nur Sie hören.", startAndExpectStop())
    }

    @Test fun `nennt Android keinen Ausgabeweg startet nichts`() {
        assertTrue(startAndExpectStop().startsWith("Der Weg der Tonausgabe ließ sich nicht prüfen."))
    }

    @Test fun `ein Bluetooth-Geraet unklarer Art wird erfragt und erst der zweite Start bestaetigt es`() {
        // Bereitschaft gewählt, damit der Test nach bestandener Kopfhörer-Prüfung sicher endet: Einen
        // Weckwort-Erkenner gibt es auf dem Rechner nicht.
        prefs.listenMode = "weckwort"
        routeTo(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)) // könnte Ohrhörer, Box oder Auto sein

        val question = startAndExpectStop()
        assertTrue(question, "noch einmal auf Start" in question)
        assertTrue("ohne Antwort ist nichts bestätigt", prefs.confirmedOutputs.isEmpty())

        // Sofort noch einmal (Doppeltipper): zählt nicht als Bestätigung, die Frage kommt wieder.
        assertTrue("noch einmal auf Start" in startAndExpectStop())
        assertTrue(prefs.confirmedOutputs.isEmpty())

        // Bewusst nach ein paar Sekunden: Das Gerät ist bestätigt, die Kopfhörer-Prüfung besteht.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        assertTrue(startAndExpectStop().startsWith("Weckwort-Erkennung nicht verfügbar."))
        assertEquals(1, prefs.confirmedOutputs.size)

        // Beim nächsten Mal wird nicht mehr gefragt.
        assertTrue(startAndExpectStop().startsWith("Weckwort-Erkennung nicht verfügbar."))
    }

    @Test fun `mit Kopfhoerer-Mikro wird auch nach einem Headset ohne Musikkanal gefragt`() {
        // Ein Headset, das nur telefonieren kann: Musik liefe über das Handy, die Antwort aber (mit
        // Kopfhörer-Mikro) über den Telefonkanal des Headsets. Dann zählt dieses Gerät, und weil jedes
        // Bluetooth-Gerät einmal bestätigt werden muss, wird auch danach gefragt.
        prefs.listenMode = "weckwort" // damit der Test nach bestandener Kopfhörer-Prüfung sicher endet
        routeTo(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        shadowOf(audio).setAvailableCommunicationDevices(
            listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        )
        // Mit dem Handy-Mikro hilft dieses Headset nicht: Die Antwort käme aus dem Lautsprecher.
        assertEquals("Bitte zuerst Kopfhörer verbinden: Die Antworten sollen nur Sie hören.", startAndExpectStop())

        prefs.useBluetoothMic = true
        assertTrue("noch einmal auf Start" in startAndExpectStop())
        assertTrue(prefs.confirmedOutputs.isEmpty())

        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        assertTrue(startAndExpectStop().startsWith("Weckwort-Erkennung nicht verfügbar."))
        assertEquals(1, prefs.confirmedOutputs.size)
    }

    // --- Bereitschaft ------------------------------------------------------------------------

    @Test fun `Bereitschaft ohne Weckwort-Erkenner faellt nicht auf Dauer-Zuhoeren zurueck`() {
        prefs.listenMode = "weckwort"
        routeTo(device(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(
            "Weckwort-Erkennung nicht verfügbar. Bitte „Dauer-Zuhören“ wählen oder die App neu installieren.",
            startAndExpectStop(),
        )
    }

    // --- Nie abstürzen -----------------------------------------------------------------------

    @Test fun `ein Fehler im Hintergrund-Teil des Aufbaus endet als Meldung und nicht als Absturz`() {
        // Ohne Keystore lassen sich die verschlüsselten Einstellungen nicht öffnen. Das passiert abseits des
        // Haupt-Threads; früher hätte ein Fehler beim Start die ganze App beendet.
        FakeAndroidKeyStore.uninstall()
        FakeAndroidKeyStore.forgetPrefs()
        routeTo(device(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals("Start fehlgeschlagen (KeyStoreException). Bitte noch einmal versuchen.", startAndExpectStop())
    }

    @Test fun `ein Fehler gleich zu Beginn endet als Meldung ohne den Fehlertext`() {
        val controller = service()
        shadowOf(controller.get()).setThrowInStartForeground(SecurityException("geheim: sk_0123456789"))
        controller.startCommand(0, 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals("Start fehlgeschlagen (SecurityException). Bitte noch einmal versuchen.", AppState.ui.value.error)
    }

    @Test fun `der Start kehrt sofort zurueck und zeigt Benachrichtigung und Status`() {
        routeTo(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        val controller = service()
        controller.startCommand(0, 1)
        // Noch bevor irgendetwas Langsames (Einstellungen, Modelle, Dateien) fertig ist:
        assertTrue(AppState.ui.value.running)
        assertEquals("Startet …", AppState.ui.value.status)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
    }

    @Test fun `ein zweiter Start-Befehl waehrend des Aufbaus baut nicht doppelt auf`() {
        routeTo(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        val controller = service()
        controller.startCommand(0, 1)
        val startedAt = AppState.ui.value.startedAtMs
        Thread.sleep(5)
        controller.startCommand(0, 2) // z. B. ein doppelter Tipp auf Start
        assertEquals("der zweite Befehl hat neu begonnen", startedAt, AppState.ui.value.startedAtMs)
    }

    @Test fun `Stopp und Antwort-Stopp vor jedem Start sind harmlos`() {
        val controller = service()
        val service = controller.get()
        service.onStartCommand(Intent(service, ListenService::class.java).setAction(ListenService.ACTION_STOP_SPEAKING), 0, 1)
        assertFalse(AppState.ui.value.running)
        service.onStartCommand(Intent(service, ListenService::class.java).setAction(ListenService.ACTION_STOP), 0, 2)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
        assertFalse(AppState.ui.value.running)
    }

    @Test fun `wird der Dienst mitten im Aufbau beendet bleibt nichts offen und nichts wird mehr gemeldet`() {
        routeTo(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        val controller = service()
        controller.startCommand(0, 1)
        controller.destroy() // z. B. Stopp direkt nach Start
        // Der Hintergrund-Teil läuft noch zu Ende; sein Ergebnis darf nichts mehr öffnen oder melden.
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AppState.ui.value.running)
        assertEquals("Bereit", AppState.ui.value.status)
        assertEquals("nach dem Ende kommt keine Meldung mehr", null, AppState.ui.value.error)
    }
}
