package app.claudevoice

import android.app.Application
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import app.claudevoice.core.OutputRule
import app.claudevoice.core.UiState
import com.google.common.collect.ImmutableList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowAudioRecord
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.time.Duration

/**
 * Das Ende des Dienstes und alles, was im laufenden Betrieb dazu führt.
 *
 * Anders als [ListenServiceStartTest] läuft der Dienst hier wirklich an: Mikrofon (nachgestellt), Pipeline
 * und die Verbindung zur Transkription. Jede Netzverbindung endet an einem toten lokalen Port; nichts
 * verlässt den Rechner, und die Transkription meldet fortlaufend "keine Verbindung". Genau diese Meldungen
 * braucht ein Teil der Tests als Störfeuer.
 *
 * Wichtig für das Verständnis: Unter Robolectric kommt onDestroy erst, wenn der Test es verlangt. So lässt
 * sich der Augenblick zwischen "der Dienst hat sein Ende verlangt" und "Android beendet ihn" beliebig dehnen.
 * Auf dem Handy dauert er nur Millisekunden; was in ihm schiefgehen kann, zeigt sich hier zuverlässig.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenServiceStopTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val media = AndroidSpeaker.attributes(voiceCallRoute = false)
    private val call = AndroidSpeaker.attributes(voiceCallRoute = true)
    private val looper get() = shadowOf(Looper.getMainLooper())
    private val services = ArrayList<ServiceController<ListenService>>()
    private var oldSelector: ProxySelector? = null
    private lateinit var prefs: Prefs

    private val wired = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADSET).build()
    private val phoneSpeaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
    private val earpiece = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()

    // Ohrhörer des Trägers: Musik- und Telefonkanal tragen dieselbe Adresse. Dazu das Auto, das sich dazwischendrängt.
    private val earbudsMusic = bluetooth("DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES", "Ohrhörer", EARBUDS)
    private val earbudsCall = bluetooth("DEVICE_OUT_BLUETOOTH_SCO_HEADSET", "Ohrhörer", EARBUDS)
    private val carCall = bluetooth("DEVICE_OUT_BLUETOOTH_SCO_CARKIT", "Auto", "AA:BB:CC:DD:EE:22")

    @Before fun setUp() {
        oldSelector = ProxySelector.getDefault()
        // Nichts verlässt den Rechner: Jede Verbindung läuft auf einen toten lokalen Port.
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        })
        prefs = FakeAndroidKeyStore.freshPrefs(context)
        prefs.anthropicKey = "sk-ant-test-0000"
        prefs.elevenKey = "el-test-0000"
        prefs.useBluetoothMic = false // Handy-Mikro, solange ein Test nicht ausdrücklich das Kopfhörer-Mikro wählt
        AppState.ui.value = UiState()
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                Thread.sleep(5) // ein echtes Mikrofon liefert auch nicht beliebig schnell
                return sizeInBytes
            }
        })
        shadowOf(audio).addOutputDevice(wired, false)
        shadowOf(audio).setAudioDevicesForAttributes(media, ImmutableList.of(wired))
    }

    @After fun tearDown() {
        services.forEach { runCatching { it.destroy() } }
        ShadowAudioRecord.clearSource()
        ProxySelector.setDefault(oldSelector)
        FakeAndroidKeyStore.install()
    }

    /** Ein Gerät, wie das echte Framework es baut: interner Typ (AudioSystem.DEVICE_OUT_*), Name und Adresse. */
    private fun bluetooth(internalType: String, name: String, address: String): AudioDeviceInfo {
        val internal = Class.forName("android.media.AudioSystem").getField(internalType).getInt(null)
        val portClass = Class.forName("android.media.AudioDevicePort")
        val port = portClass
            .getMethod("createForTesting", Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .invoke(null, internal, name, address)
        return AudioDeviceInfo::class.java.getConstructor(portClass).newInstance(port)
    }

    private fun service(): ServiceController<ListenService> =
        Robolectric.buildService(ListenService::class.java).create().also { services += it }

    private fun stopCommand(c: ServiceController<ListenService>) {
        c.get().onStartCommand(Intent(c.get(), ListenService::class.java).setAction(ListenService.ACTION_STOP), 0, 2)
    }

    /** Läuft gerade ein Mikrofon-Thread? Er ist das sichtbare Zeichen dafür, dass aufgenommen wird. */
    private fun micAlive(): Boolean = Thread.getAllStackTraces().keys.any { it.name == "mic" && it.isAlive }

    /** Gibt Hintergrund-Threads echte Zeit; die Uhr des Haupt-Threads bleibt dabei stehen. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            looper.idle()
            Thread.sleep(20)
        }
    }

    /** Startet den Dienst und wartet, bis der Aufbau durch ist (das Mikrofon läuft, die Transkription wird verbunden). */
    private fun startAndRun(): ServiceController<ListenService> {
        val c = service()
        c.startCommand(0, 1)
        val deadline = System.currentTimeMillis() + 10_000
        while (AppState.ui.value.status == "Startet …" && !shadowOf(c.get()).isStoppedBySelf &&
            System.currentTimeMillis() < deadline
        ) settle(50)
        assertFalse("der Start ist gescheitert: ${AppState.ui.value.error}", shadowOf(c.get()).isStoppedBySelf)
        assertTrue("der Aufbau wurde nicht fertig (Status: ${AppState.ui.value.status})", micAlive())
        return c
    }

    /** Die Kopfhörer werden herausgezogen: Android leitet auf den Lautsprecher um und meldet das Gerät ab. */
    private fun unplugHeadphones() {
        shadowOf(audio).setAudioDevicesForAttributes(media, ImmutableList.of(phoneSpeaker))
        shadowOf(audio).removeOutputDevice(wired, true)
        looper.idle()
    }

    // --- Zwischen der Entscheidung und onDestroy ---------------------------------------------

    @Test fun `der Grund fuer das Ende bleibt stehen bis der Dienst wirklich endet`() {
        val c = startAndRun()
        unplugHeadphones()
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertEquals("Kopfhörer getrennt. Das Zuhören wurde beendet.", AppState.ui.value.error)
        val status = AppState.ui.value.status

        // onDestroy ist noch nicht gekommen. Früher versuchte es die Transkription weiter und überschrieb
        // den Grund mit "Keine Verbindung zur Transkription …".
        settle(3_000)
        assertEquals(
            "der Grund wurde überschrieben, obwohl der Dienst schon sein Ende verlangt hat",
            "Kopfhörer getrennt. Das Zuhören wurde beendet.", AppState.ui.value.error,
        )
        assertEquals("auch die Statuszeile fasst niemand mehr an", status, AppState.ui.value.status)

        c.destroy()
        assertFalse(AppState.ui.value.running)
        assertEquals("Kopfhörer getrennt. Das Zuhören wurde beendet.", AppState.ui.value.error)
    }

    @Test fun `mit der Entscheidung zu enden ist das Mikrofon sofort zu, nicht erst mit onDestroy`() {
        val c = startAndRun()
        unplugHeadphones()
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertFalse("das Mikrofon läuft weiter, obwohl der Dienst sein Ende verlangt hat", micAlive())
    }

    @Test fun `nach dem Stopp-Befehl oeffnet der Aufbau kein Mikrofon mehr`() {
        val c = service()
        c.startCommand(0, 1)
        stopCommand(c) // z. B. Stopp direkt nach Start, der Aufbau arbeitet noch im Hintergrund
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertFalse(micAlive())

        // onDestroy ist noch nicht gekommen; der Hintergrund-Teil wird fertig und kehrt zurück.
        settle(3_000)
        assertFalse("nach dem Stopp wurde das Mikrofon geöffnet (Status: ${AppState.ui.value.status})", micAlive())
        assertEquals("und verbunden wurde auch nichts", "Startet …", AppState.ui.value.status)
        assertNull("ein gewöhnlicher Stopp ist kein Fehler", AppState.ui.value.error)
    }

    @Test fun `der Stopp-Befehl schliesst im laufenden Betrieb sofort alles`() {
        val c = startAndRun()
        stopCommand(c)
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertFalse("das Mikrofon läuft nach dem Stopp weiter", micAlive())
        val status = AppState.ui.value.status
        val error = AppState.ui.value.error

        // Was jetzt noch von der Transkription kommt, erreicht die Oberfläche nicht mehr.
        settle(2_000)
        assertEquals(status, AppState.ui.value.status)
        assertEquals(error, AppState.ui.value.error)

        c.destroy()
        assertFalse(AppState.ui.value.running)
        assertEquals("Bereit", AppState.ui.value.status)
    }

    // --- Kopfhörer-Mikro: Der Telefonkanal reißt ab ------------------------------------------

    /** Startet den Dienst mit Kopfhörer-Mikro; die Ohrhörer hat der Träger schon bestätigt. */
    private fun startWithHeadsetMic(): ServiceController<ListenService> {
        prefs.useBluetoothMic = true
        prefs.confirmedOutputs = setOf(EARBUDS)
        shadowOf(audio).setAvailableCommunicationDevices(listOf(earpiece, earbudsCall))
        shadowOf(audio).setAudioDevicesForAttributes(media, ImmutableList.of(earbudsMusic))
        shadowOf(audio).setAudioDevicesForAttributes(call, ImmutableList.of(earbudsCall))
        val c = startAndRun()
        assertEquals("es wird über das Headset aufgenommen", AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertEquals(earbudsCall, audio.communicationDevice)
        return c
    }

    /** Der Telefonkanal zum Headset ist weg (etwa nach einem Druck auf die Kopfhörer-Taste). */
    private fun dropCallLink(micNotices: Boolean = true) {
        shadowOf(audio).setAudioDevicesForAttributes(call, ImmutableList.of(earpiece))
        if (micNotices) shadowOf(audio).callOnCommunicationDeviceChangedListeners(earpiece)
        // Der Dienst erfährt von Änderungen über die Geräteliste; hier meldet sich irgendein Gerät neu an.
        shadowOf(audio).addOutputDevice(phoneSpeaker, true)
        looper.idle()
    }

    @Test fun `kommt der Telefonkanal gleich zurueck geht das Zuhoeren weiter`() {
        val c = startWithHeadsetMic()
        dropCallLink()
        assertFalse("der Dienst endet sofort, ohne dem Kanal Zeit zu geben", shadowOf(c.get()).isStoppedBySelf)

        looper.idleFor(Duration.ofSeconds(1))
        shadowOf(audio).setAudioDevicesForAttributes(call, ImmutableList.of(earbudsCall))
        shadowOf(audio).callOnCommunicationDeviceChangedListeners(earbudsCall)
        looper.idleFor(Duration.ofSeconds(10))
        assertFalse("der Kanal kam zurück: ${AppState.ui.value.error}", shadowOf(c.get()).isStoppedBySelf)
        assertTrue("es wird weiter aufgenommen", micAlive())
    }

    @Test fun `bleibt der Telefonkanal weg endet das Zuhoeren nach der Schonfrist mit dem wahren Grund`() {
        val c = startWithHeadsetMic()
        dropCallLink()
        looper.idleFor(Duration.ofSeconds(2))
        assertFalse(shadowOf(c.get()).isStoppedBySelf)

        looper.idleFor(Duration.ofSeconds(2))
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        // Nicht "Kopfhörer getrennt": Die Kopfhörer sind noch verbunden, nur ihr Telefonkanal fehlt.
        assertEquals(OutputRule.CALL_LINK_LOST, AppState.ui.value.error)
        assertFalse(micAlive())
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }

    @Test fun `bemerkt nur die Sprachausgabe den fehlenden Kanal endet das Zuhoeren ebenfalls`() {
        // Hier meldet Android dem Mikrofon keinen Wechsel. Dann entscheidet die Schonfrist der Sprachausgabe.
        val c = startWithHeadsetMic()
        dropCallLink(micNotices = false)
        looper.idleFor(Duration.ofSeconds(3))
        assertFalse(shadowOf(c.get()).isStoppedBySelf)

        looper.idleFor(Duration.ofSeconds(1))
        looper.idle()
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertEquals(OutputRule.CALL_LINK_LOST, AppState.ui.value.error)
    }

    @Test fun `uebernimmt das Auto den Telefonkanal endet das Zuhoeren mit dessen Namen`() {
        val c = startWithHeadsetMic()
        // Für das Mikrofon sieht das Auto wie ein Headset aus (derselbe Gerätetyp). Die Sprachausgabe sieht,
        // dass Antworten nun an ein Gerät gingen, das der Träger nie bestätigt hat.
        shadowOf(audio).setAudioDevicesForAttributes(call, ImmutableList.of(carCall))
        shadowOf(audio).callOnCommunicationDeviceChangedListeners(carCall)
        shadowOf(audio).addOutputDevice(carCall, true)
        looper.idle()
        assertFalse(shadowOf(c.get()).isStoppedBySelf)
        looper.idleFor(Duration.ofSeconds(4))
        looper.idle()
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertEquals(
            "Die Tonausgabe ist auf „Auto“ gewechselt, das nicht als Kopfhörer bestätigt ist. Das Zuhören wurde beendet.",
            AppState.ui.value.error,
        )
    }

    private companion object {
        const val EARBUDS = "AA:BB:CC:DD:EE:21"
    }
}
