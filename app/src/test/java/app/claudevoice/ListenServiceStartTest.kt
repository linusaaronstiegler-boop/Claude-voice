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
    private var hold: SetupHold? = null

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
        // Nichts zurücklassen: Ein noch angehaltener Hintergrund-Schritt darf jetzt zu Ende laufen, und erst
        // wenn in keinem Dienst mehr etwas läuft, beginnt der nächste Test. Sonst öffnete ein Nachzügler die
        // Einstellungen dieses Tests, während der nächste schon seine eigenen anlegt.
        hold?.release()
        waitFor { services.none { it.get().busy } }
        FakeAndroidKeyStore.install()
    }

    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    /** Legt fest, wohin Android eine Antwort leiten würde. */
    private fun routeTo(vararg devices: AudioDeviceInfo) =
        shadowOf(audio).setAudioDevicesForAttributes(media, ImmutableList.copyOf(devices))

    private fun service(): ServiceController<ListenService> =
        Robolectric.buildService(ListenService::class.java).create().also { services += it }

    /**
     * Lässt Hintergrund-Arbeit und Haupt-Thread laufen, bis [done] gilt.
     * Die Frist (30 Sekunden) ist nur ein Netz gegen einen Test, der sonst ewig hinge. Im guten Fall kostet
     * sie nichts; sie ist darum so großzügig, dass auch ein ausgelasteter Rechner nicht an ihr scheitert.
     */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /**
     * Startet einen Dienst und hält seinen Aufbau im ersten Hintergrund-Schritt fest (siehe [SetupHold]).
     * Wenn diese Funktion zurückkehrt, steckt der Aufbau nachweislich mittendrin, und zwar so lange, bis
     * der Test ihn mit `hold.release()` weiterschickt.
     */
    private fun startHeld(): ServiceController<ListenService> {
        val controller = service()
        val armed = SetupHold.arm().also { hold = it }
        controller.startCommand(0, 1)
        armed.awaitReached()
        return controller
    }

    /** Wartet, bis im Dienst nichts mehr läuft: Der Hintergrund-Schritt ist fertig und sein Ergebnis verarbeitet. */
    private fun awaitQuiet(controller: ServiceController<ListenService>) {
        waitFor { !controller.get().busy }
        assertFalse("im Dienst läuft immer noch etwas", controller.get().busy)
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
        val controller = startHeld()
        // Noch bevor irgendetwas Langsames (Einstellungen, Modelle, Dateien) fertig ist. Dass es wirklich
        // noch nicht fertig ist, stellt die Sperre sicher; früher war das nur meistens so.
        assertTrue(AppState.ui.value.running)
        assertEquals("Startet …", AppState.ui.value.status)
        assertEquals("solange der Aufbau läuft, gibt es nichts zu melden", null, AppState.ui.value.error)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
    }

    @Test fun `ein zweiter Start-Befehl waehrend des Aufbaus baut nicht doppelt auf`() {
        routeTo(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        val controller = startHeld()
        val startedAt = AppState.ui.value.startedAtMs
        // Nur damit ein zweiter Beginn an seiner späteren Uhrzeit zu erkennen wäre (die Uhr zählt in Millisekunden).
        Thread.sleep(5)
        controller.startCommand(0, 2) // z. B. ein doppelter Tipp auf Start, während der erste Aufbau noch läuft
        assertEquals("der zweite Befehl hat neu begonnen", startedAt, AppState.ui.value.startedAtMs)
        assertEquals("Startet …", AppState.ui.value.status)
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
        // Ohne Kopfhörer: Liefe der Aufbau nach dem Ende weiter, würde er genau das melden.
        routeTo(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        val controller = startHeld()
        controller.destroy() // z. B. Stopp direkt nach Start: Der Hintergrund-Teil steckt noch im Öffnen der Einstellungen
        assertEquals("bis zum Ende gab es nichts zu melden", null, AppState.ui.value.error)

        // Jetzt erst wird der Hintergrund-Teil fertig; sein Ergebnis darf nichts mehr öffnen oder melden.
        hold!!.release()
        awaitQuiet(controller)
        assertFalse(AppState.ui.value.running)
        assertEquals("Bereit", AppState.ui.value.status)
        assertEquals("nach dem Ende kommt keine Meldung mehr", null, AppState.ui.value.error)
        assertFalse("ein beendeter Dienst verlangt sein Ende nicht noch einmal", shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun `scheitert der Hintergrund-Teil erst nach dem Ende wird auch das nicht mehr gemeldet`() {
        // Der härtere Fall: Der Hintergrund-Teil kommt nicht mit einem Ergebnis zurück, sondern mit einem
        // Fehler. Einen Fehler reicht die Coroutine auch nach einem Abbruch noch weiter; dass daraus keine
        // Meldung wird, liegt allein an der Schranke in fail() ("stopping"). Vor dem Ende wird derselbe
        // Fehler gemeldet, siehe den Test "ein Fehler im Hintergrund-Teil ..." weiter oben.
        routeTo(device(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        val controller = startHeld()
        controller.destroy()

        hold!!.release(failing = true) // der Tresor verweigert sich, als der Dienst schon beendet ist
        awaitQuiet(controller)
        assertEquals("nach dem Ende kommt keine Meldung mehr", null, AppState.ui.value.error)
        assertFalse(AppState.ui.value.running)
        assertEquals("Bereit", AppState.ui.value.status)
        assertFalse("ein beendeter Dienst verlangt sein Ende nicht noch einmal", shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun `meldet der Aufbau vor dem Ende bleibt die Meldung stehen`() {
        // Das Gegenstück, und genau der Ablauf, an dem der Bau in der CI gescheitert ist: Der Hintergrund-Teil
        // wird fertig, BEVOR der Dienst endet. Dann meldet der Aufbau zu Recht die fehlenden Kopfhörer, und
        // diese Meldung gehört dem Nutzer auch nach dem Ende noch gezeigt.
        routeTo(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        val controller = startHeld()
        hold!!.release()
        waitFor { shadowOf(controller.get()).isStoppedBySelf }
        assertEquals("Bitte zuerst Kopfhörer verbinden: Die Antworten sollen nur Sie hören.", AppState.ui.value.error)

        controller.destroy()
        awaitQuiet(controller)
        assertFalse(AppState.ui.value.running)
        assertEquals("Bitte zuerst Kopfhörer verbinden: Die Antworten sollen nur Sie hören.", AppState.ui.value.error)
    }
}
