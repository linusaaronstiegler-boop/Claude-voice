package app.claudevoice

import android.app.Application
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import app.claudevoice.core.OutputRule
import app.claudevoice.core.UiState
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.flow.update
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
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
    private var hold: SetupHold? = null
    private lateinit var prefs: Prefs

    private val wired = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADSET).build()
    private val phoneSpeaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
    private val earpiece = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()

    // Ohrhörer des Trägers: Musik- und Telefonkanal tragen dieselbe Adresse. Dazu das Auto, das sich dazwischendrängt.
    private val earbudsMusic = bluetooth("DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES", "Ohrhörer", EARBUDS)
    private val earbudsCall = bluetooth("DEVICE_OUT_BLUETOOTH_SCO_HEADSET", "Ohrhörer", EARBUDS)
    private val carCall = bluetooth("DEVICE_OUT_BLUETOOTH_SCO_CARKIT", "Auto", "AA:BB:CC:DD:EE:22")

    /** Wohin jede Netzverbindung des Dienstes umgeleitet wird. Port 9 ist tot; ein Test kann eine [DeadEnd] einsetzen. */
    @Volatile private var proxyPort = 9
    private var deadEnd: DeadEnd? = null

    /**
     * Läuft einmal auf dem Mikrofon-Thread, und zwar genau dann, wenn der Dienst beim Abbau auf diesen Thread
     * wartet. So kann ein Test etwas mitten in den Abbau hinein geschehen lassen, ohne Zeiten zu schätzen.
     * Nach dem Setzen wartet der Test auf [micParked] und stoppt den Dienst erst dann.
     */
    @Volatile private var whileClosingMic: (() -> Unit)? = null
    private val micParked = CountDownLatch(1)

    /** Steckt der Haupt-Thread gerade in MicSource.stop()? Dort wartet er auf das Ende des Mikrofon-Threads. */
    private fun closingMic(): Boolean =
        Looper.getMainLooper().thread.stackTrace.any { it.className == MicSource::class.java.name && it.methodName == "stop" }

    @Before fun setUp() {
        oldSelector = ProxySelector.getDefault()
        // Nichts verlässt den Rechner: Jede Verbindung läuft auf einen toten lokalen Port.
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
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
                val hook = whileClosingMic
                if (hook != null) {
                    whileClosingMic = null
                    // Der Mikrofon-Thread bleibt mitten im Lesen stehen, bis der Dienst beim Abbau auf ihn wartet.
                    // Der Test wartet seinerseits, bis er hier steht (micParked), und stoppt erst dann. So ist
                    // sicher, dass der Einschub in den Abbau fällt; nur zu hoffen, der Thread lese gerade, genügt nicht.
                    micParked.countDown()
                    val until = System.currentTimeMillis() + 30_000
                    while (!closingMic() && System.currentTimeMillis() < until) Thread.sleep(1)
                    hook()
                }
                return sizeInBytes
            }
        })
        shadowOf(audio).addOutputDevice(wired, false)
        shadowOf(audio).setAudioDevicesForAttributes(media, ImmutableList.of(wired))
    }

    @After fun tearDown() {
        services.forEach { runCatching { it.destroy() } }
        // Nichts zurücklassen: Ein noch angehaltener Hintergrund-Schritt und eine festgehaltene Verbindung
        // dürfen jetzt zu Ende laufen. Der nächste Test beginnt erst, wenn in keinem Dienst mehr etwas läuft.
        hold?.release()
        deadEnd?.close()
        waitFor { services.none { it.get().busy } }
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

    /**
     * Lässt Hintergrund und Haupt-Thread laufen, bis [done] gilt. Gibt zurück, ob es so weit kam.
     * Die Frist ist nur ein Netz gegen einen Test, der sonst ewig hinge: Im guten Fall kostet sie nichts,
     * sie darf also großzügig sein. Ein langsamer Rechner soll daran nicht scheitern.
     */
    private fun waitFor(timeoutMs: Long = 30_000, done: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < deadline) settle(20)
        return done()
    }

    /** Wartet, bis im Dienst nichts mehr läuft, auch kein Hintergrund-Schritt, der nur noch zu Ende rechnet. */
    private fun awaitQuiet(c: ServiceController<ListenService>) {
        assertTrue("im Dienst läuft immer noch etwas", waitFor { !c.get().busy })
    }

    /**
     * Startet den Dienst und wartet, bis der Aufbau durch ist (das Mikrofon läuft, die Transkription wird verbunden).
     *
     * Die Frist ist großzügig: Der erste Aufbau in einem Testlauf lädt den ganzen Client von Anthropic, die
     * Sprachausgabe und das Mikrofon zum ersten Mal. Auf einem Rechner, der nebenbei noch übersetzt, wurden
     * dafür schon sechs Sekunden gemessen (sonst eine halbe). Im guten Fall kostet die längere Frist nichts.
     */
    private fun startAndRun(): ServiceController<ListenService> {
        val c = service()
        c.startCommand(0, 1)
        val deadline = System.currentTimeMillis() + 60_000
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
        // Der Aufbau wird in seinem ersten Hintergrund-Schritt festgehalten (siehe SetupHold). So trifft der
        // Stopp ihn sicher mittendrin, auch auf einem Rechner, auf dem gerade alles langsam ist.
        val armed = SetupHold.arm().also { hold = it }
        c.startCommand(0, 1)
        armed.awaitReached()
        stopCommand(c) // z. B. Stopp direkt nach Start, der Aufbau arbeitet noch im Hintergrund
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertFalse(micAlive())

        // onDestroy ist noch nicht gekommen; der Hintergrund-Teil wird jetzt fertig und kehrt zurück. Gewartet
        // wird, bis im Dienst nichts mehr läuft. Ginge der Aufbau trotz Stopp weiter, liefe am Ende die
        // Pipeline, und das Warten liefe in seine Frist.
        armed.release()
        awaitQuiet(c)
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

    // --- Nach dem Ende -----------------------------------------------------------------------

    @Test fun `was nach dem Stopp noch in die Anzeige geschrieben wird bleibt nicht stehen`() {
        val c = startAndRun()
        // Erst wenn die Transkription ihren ersten Fehlschlag gemeldet hat, steht die Meldungszeile still:
        // Jeder weitere Versuch scheitert am selben toten Port und meldet denselben Wortlaut.
        assertTrue("die Transkription hat nichts gemeldet", waitFor { AppState.ui.value.error != null })
        val shown = AppState.ui.value.error // was dasteht, als der Nutzer stoppt
        stopCommand(c)

        // Ein Nachzügler. Pipeline und Sprachausgabe schreiben ihren Stand von eigenen Threads aus in dieselbe
        // Anzeige; ein Schritt, der beim Stopp gerade lief, kann das noch einen Augenblick danach tun (etwa
        // "Antwort fehlgeschlagen", weil ihm die Verbindung unter den Händen geschlossen wurde). Hier schreibt
        // der Test selbst, damit der Zeitpunkt feststeht: nach dem Stopp, vor onDestroy.
        AppState.ui.update { it.copy(error = "Antwort fehlgeschlagen: Sprachausgabe unterbrochen", speaking = true, partial = "noch ein Wort") }

        c.destroy()
        assertEquals("nach dem Ende steht etwas anderes da als beim Stopp", shown, AppState.ui.value.error)
        assertFalse(AppState.ui.value.speaking)
        assertEquals("", AppState.ui.value.partial)
        assertFalse(AppState.ui.value.running)
    }

    @Test fun `was die Transkription waehrend des Abbaus meldet erreicht die Anzeige nicht mehr`() {
        // Der Abbau dauert einen Moment: Der Dienst wartet, bis der Mikrofon-Thread sein letztes Stück gelesen
        // hat (bis zu einer halben Sekunde), und schließt erst danach die Verbindung zur Transkription. Meldet
        // die sich genau in dieser Zeit, hat der Dienst sein Ende schon beschlossen: Es wird nichts mehr gezeigt.
        //
        // Hier hängt der Verbindungsaufbau zur Transkription in der Sackgasse (noch kein Erfolg, noch kein
        // Fehler) und scheitert in dem Augenblick, in dem der Dienst auf das Mikrofon wartet.
        val net = DeadEnd(holdHost = TRANSCRIPTION).also { deadEnd = it; proxyPort = it.port }
        val c = startAndRun()
        assertTrue("die Transkription wurde nie angerufen", waitFor { net.attempts(TRANSCRIPTION) >= 1 })
        assertNull(AppState.ui.value.error)
        val status = AppState.ui.value.status

        val reported = AtomicReference<String?>()
        val ran = CountDownLatch(1)
        whileClosingMic = {
            net.letGo() // jetzt scheitert der Aufbau, und die Transkription meldet das auf ihrem eigenen Thread
            // Der Meldung Zeit geben anzukommen, solange der Dienst noch wartet. Kommt sie nicht durch (so soll
            // es sein), läuft diese kurze Frist einfach ab. Auf einem langsamen Rechner kann der Test dadurch
            // höchstens weniger bemerken, nie fälschlich scheitern.
            val until = System.currentTimeMillis() + 300
            while (AppState.ui.value.error == null && System.currentTimeMillis() < until) Thread.sleep(5)
            reported.set(AppState.ui.value.error)
            ran.countDown()
        }
        assertTrue("das Mikrofon liest nicht mehr", micParked.await(30, TimeUnit.SECONDS))
        stopCommand(c)
        // Der Dienst wartet höchstens eine halbe Sekunde auf den Mikrofon-Thread. Stockt der Rechner, kann der
        // Abbau schon weiter sein, bevor der Einschub oben fertig ist; darum wird hier auf ihn gewartet.
        assertTrue("der Abbau hat nicht auf das Mikrofon gewartet; der Test hat seinen Moment verfehlt", ran.await(30, TimeUnit.SECONDS))
        assertNull("während des Abbaus wurde noch etwas gemeldet", reported.get())
        assertNull(AppState.ui.value.error)
        assertEquals("auch die Statuszeile fasst niemand mehr an", status, AppState.ui.value.status)
    }

    @Test fun `nach dem Ende geht keine Anfrage an Claude mehr hinaus`() {
        // Statt des toten Ports eine Sackgasse, die mitzählt und den Aufruf an Claude festhält.
        val net = DeadEnd(holdHost = CLAUDE).also { deadEnd = it; proxyPort = it.port }
        val c = startAndRun()
        // Gleich beim Start wärmt der Dienst die Verbindung zu Claude vor. Dieser Aufruf steckt jetzt in der
        // Sackgasse und wartet auf Antwort; mehr als diesen einen gibt es bis hierher nicht.
        assertTrue("der Dienst hat Claude nie angerufen", waitFor { net.attempts(CLAUDE) >= 1 })
        assertEquals(1, net.attempts(CLAUDE))

        c.destroy()
        // Erst jetzt, nach dem Ende, scheitert der Aufruf. Der Client von Anthropic wartet nach einem
        // Fehlschlag eine halbe Sekunde und versucht es dann noch einmal. Ohne Gegenmaßnahme gingen Regeln und
        // Gedächtnis so ein zweites Mal auf die Reise, obwohl der Dienst längst beendet ist.
        net.letGo()
        awaitQuiet(c)
        assertEquals("nach dem Ende wurde Claude noch einmal angerufen", 1, net.attemptsSoFar(CLAUDE))
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

    /**
     * Eine Sackgasse anstelle des toten Ports: Sie nimmt jede Verbindung an, merkt sich, wohin die wollte, und
     * beendet sie gleich wieder. Für den Dienst sieht das aus wie ein Netz, in dem nichts durchkommt; nichts
     * verlässt den Rechner. Der Test kann so zählen, wie oft der Dienst einen Rechner zu erreichen versucht.
     *
     * Verbindungen zu [holdHost] werden nicht gleich beendet, sondern offen gehalten, bis der Test [letGo]
     * ruft. Damit bestimmt der Test (und keine Uhr), wann dieser eine Aufruf scheitert.
     */
    private class DeadEnd(private val holdHost: String) : Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        /** Die erste Zeile jeder Verbindung, so wie ein Proxy sie bekommt: "CONNECT api.anthropic.com:443 HTTP/1.1". */
        private val wanted = CopyOnWriteArrayList<String>()
        private val held = CopyOnWriteArrayList<Socket>()
        @Volatile private var holding = true
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "sackgasse") {
                while (true) {
                    val socket = try { server.accept() } catch (e: IOException) { break }
                    val line = runCatching {
                        socket.soTimeout = 2_000
                        socket.getInputStream().bufferedReader().readLine()
                    }.getOrNull().orEmpty()
                    // Erst festhalten oder beenden, dann zählen: Wer die Zahl liest, findet die Verbindung schon versorgt.
                    if (holding && holdHost in line) held += socket else runCatching { socket.close() }
                    wanted += line
                }
            }
        }

        /** Wie oft bisher eine Verbindung zu [host] verlangt wurde. */
        fun attempts(host: String): Int = wanted.count { host in it }

        /**
         * Dasselbe, aber sicher vollständig: Vorher geht eine Markierung durch dieselbe Tür. Verbindungen
         * werden in der Reihenfolge ihres Eintreffens bedient; ist die Markierung gezählt, ist es auch alles,
         * was vor ihr kam, selbst wenn der Zähler-Thread gerade nicht zum Zug gekommen war.
         */
        fun attemptsSoFar(host: String): Int {
            val mark = "MARKE ${System.nanoTime()}"
            Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write("$mark\r\n".toByteArray()) }
            val deadline = System.currentTimeMillis() + 10_000
            while (mark !in wanted && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertTrue("die Sackgasse zählt nicht mehr mit", mark in wanted)
            return attempts(host)
        }

        /** Beendet die festgehaltenen Verbindungen: Für den Anrufer scheitert sein Versuch in diesem Moment. */
        fun letGo() {
            holding = false
            held.forEach { runCatching { it.close() } }
            held.clear()
        }

        override fun close() {
            letGo()
            runCatching { server.close() }
        }
    }

    private companion object {
        const val EARBUDS = "AA:BB:CC:DD:EE:21"
        const val CLAUDE = "api.anthropic.com"
        const val TRANSCRIPTION = "api.elevenlabs.io" // ohne Soniox-Schlüssel läuft die Transkription über ElevenLabs
    }
}
