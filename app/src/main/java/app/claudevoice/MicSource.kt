package app.claudevoice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import app.claudevoice.core.OutputRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread

/**
 * Mikrofon-Aufnahme als roher PCM-Strom (16 kHz, mono, 16 Bit), in 100-ms-Stücken.
 *
 * Bluetooth-Besonderheit: Kopfhörer kennen zwei Betriebsarten. Für Musik (A2DP) ist das Mikro aus;
 * erst im "Telefonie-Modus" (SCO/HFP) liefern AirPods ihr Mikro, dafür klingt alles in Telefonqualität.
 * Deshalb schalten wir nur dann in den Kommunikationsmodus, wenn das Kopfhörer-Mikro gewünscht ist.
 *
 * Der Telefonkanal kommt nicht sofort und nicht sicher zustande: Android nimmt den Wunsch nur
 * entgegen, die Funkverbindung steht ein bis zwei Sekunden später oder gar nicht (Headset gerade mit
 * einem anderen Gerät beschäftigt). Bis dahin würde Android stillschweigend das Handy-Mikro liefern.
 * Darum gilt hier: Erst wenn Android meldet, dass der Kanal wirklich steht, wird über ihn aufgenommen.
 * Sonst bleibt es ehrlich beim Handy-Mikro, und [usingBluetooth] sagt das auch.
 *
 * [onLost] wird auf dem Haupt-Thread gerufen, wenn die Aufnahme mitten im Betrieb verloren geht
 * (Telefonkanal weg, Mikrofon liefert Fehler). Der Dienst beendet dann das Zuhören mit dieser Meldung.
 *
 * [start] und [stop] gehören auf den Haupt-Thread.
 */
class MicSource(context: Context, private val onLost: (String) -> Unit) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var lostReported = false

    /** true, wenn tatsächlich über das Kopfhörer-Mikro aufgenommen wird. */
    var usingBluetooth = false
        private set

    // --- Telefonkanal zum Headset ------------------------------------------------------------
    private var watchingLink = false
    private var linkUp: CompletableDeferred<Unit>? = null

    /**
     * true, solange der Telefonkanal gerade fehlt. In dieser Zeit käme der Ton womöglich vom Handy-Mikro,
     * während die Pipeline noch mit den Regeln für das Kopfhörer-Mikro rechnet ("die lauteste Stimme ist
     * der Träger"). Deshalb wird dann nichts weitergereicht.
     */
    @Volatile private var linkDown = false

    private val linkLost = Runnable {
        // Derselbe Wortlaut, mit dem auch die Sprachausgabe einen abgerissenen Telefonkanal meldet.
        if (running && linkDown) lost(OutputRule.CALL_LINK_LOST)
    }

    private val linkWatch = AudioManager.OnCommunicationDeviceChangedListener { device ->
        if (isHeadsetLink(device)) {
            linkDown = false
            main.removeCallbacks(linkLost)
            linkUp?.complete(Unit)
        } else if (usingBluetooth && running && !linkDown) {
            // Android baut den Kanal nach einer kurzen Unterbrechung oft von selbst wieder auf (etwa nach
            // einem Druck auf die Kopfhörer-Taste). Erst wenn er nach der Schonfrist noch fehlt, ist Schluss.
            linkDown = true
            main.postDelayed(linkLost, LINK_GRACE_MS)
        }
    }

    /**
     * Öffnet das Mikrofon. Ist [headset] angegeben, wird zuerst dessen Telefonkanal aufgebaut und bis
     * zu [LINK_WAIT_MS] darauf gewartet, ohne den Haupt-Thread zu blockieren.
     * @throws Unavailable wenn sich das Mikrofon nicht öffnen lässt.
     */
    suspend fun start(headset: AudioDeviceInfo?, onChunk: (ByteArray, Int) -> Unit) {
        if (headset != null) usingBluetooth = connect(headset)
        try {
            record(onChunk)
        } catch (e: Exception) {
            releaseRouting()
            throw e
        }
    }

    /** Kurze Auskunft zum letzten Aufbau des Telefonkanals, für die Meldung auf dem Bildschirm. */
    @Volatile var linkNote: String = ""
        private set

    private suspend fun connect(headset: AudioDeviceInfo): Boolean {
        val up = CompletableDeferred<Unit>()
        linkUp = up
        // Erst zuhören, dann bitten: Neuere Android-Versionen reichen die Bitte nur weiter und antworten später.
        audio.addOnCommunicationDeviceChangedListener(appContext.mainExecutor, linkWatch)
        watchingLink = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        var connected = false
        try {
            val accepted = audio.setCommunicationDevice(headset)
            // "true" heißt nur "Bitte angenommen". Ob der Kanal steht, zeigt allein das wirklich gewählte Gerät.
            // Nicht jedes Gerät meldet den Wechsel über den Beobachter; deshalb wird zusätzlich alle 200 ms
            // nachgesehen, welches Gerät Android wirklich gewählt hat.
            connected = accepted && (
                isHeadsetLink(audio.communicationDevice) ||
                    withTimeoutOrNull(LINK_WAIT_MS) {
                        coroutineScope {
                            // Der Nachseher meldet über dasselbe Signal wie der Beobachter; wer zuerst kommt, gewinnt.
                            val poll = launch {
                                while (!isHeadsetLink(audio.communicationDevice)) delay(200)
                                up.complete(Unit)
                            }
                            up.await()
                            poll.cancel()
                        }
                    } != null
                )
            // Für die Fehlersuche auf dem Gerät: Was hat Android zur Bitte gesagt, und welches Gerät ist es
            // am Ende geworden? Steht nur in der Meldung auf dem Bildschirm, nie in einer Datei.
            linkNote = "Bitte ${if (accepted) "angenommen" else "abgelehnt"}, gewählt: Typ ${audio.communicationDevice?.type ?: "keins"}"
        } finally {
            linkUp = null
            // Auch wenn der Start währenddessen abgebrochen wird: nie im Telefonie-Modus hängen bleiben.
            if (!connected) releaseRouting()
        }
        return connected
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO wird in der Activity vor dem Start abgefragt
    private fun record(onChunk: (ByteArray, Int) -> Unit) {
        val source = if (usingBluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minSize = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minSize, CHUNK * 2)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw Unavailable(CANNOT_OPEN)
        }
        if (!usingBluetooth) {
            // Ohne diese Zeile würde Android bei verbundenem Headset teils trotzdem dessen Mikro wählen.
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { rec.setPreferredDevice(it) }
        }
        // startRecording() meldet einen Fehlschlag nicht immer als Ausnahme (etwa wenn Android der App das
        // Mikrofon gerade verweigert). Verlässlich ist erst der Zustand danach.
        runCatching { rec.startRecording() }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw Unavailable(CANNOT_OPEN)
        }
        record = rec
        running = true
        worker = thread(name = "mic") {
            val buffer = ByteArray(CHUNK)
            try {
                while (running) {
                    val n = rec.read(buffer, 0, buffer.size)
                    when {
                        n > 0 -> if (!linkDown) onChunk(buffer, n)
                        // Nichts gelesen: kurz warten, damit die Schleife den Prozessor nicht voll auslastet.
                        n == 0 -> Thread.sleep(10)
                        // Negative Werte sind Fehlercodes (z. B. nach einem Neustart des Audio-Dienstes).
                        // Früher endete die Schleife dann lautlos, und die App "hörte zu", ohne etwas zu hören.
                        else -> {
                            lost("Das Mikrofon liefert keinen Ton mehr (Fehler $n). Das Zuhören wurde beendet.")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // Auch ein Fehler beim Verarbeiten (Weckwort-Erkenner, Weiche) darf nicht still enden.
                lost("Die Aufnahme ist abgebrochen (${e.javaClass.simpleName}). Das Zuhören wurde beendet.")
            }
        }
    }

    /** Meldet den Verlust genau einmal und nur, solange nicht ohnehin gerade gestoppt wird. */
    private fun lost(message: String) {
        main.post {
            if (running && !lostReported) {
                lostReported = true
                onLost(message)
            }
        }
    }

    fun stop() {
        running = false
        main.removeCallbacks(linkLost)
        worker?.join(500)
        worker = null
        record?.run { runCatching { stop() }; release() }
        record = null
        releaseRouting()
    }

    private fun releaseRouting() {
        if (watchingLink) {
            watchingLink = false
            runCatching { audio.removeOnCommunicationDeviceChangedListener(linkWatch) }
            // Wichtig auch nach einem gescheiterten Aufbau: Sonst behandelte Android die App weiter wie ein
            // Telefonat (andere Apps bekämen kein Mikrofon, Musik liefe über den Telefonkanal).
            runCatching { audio.clearCommunicationDevice() }
            audio.mode = AudioManager.MODE_NORMAL
        }
        usingBluetooth = false
        linkDown = false
    }

    private fun isHeadsetLink(device: AudioDeviceInfo?): Boolean =
        device != null && (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    /** Das Mikrofon lässt sich nicht öffnen. Die Meldung ist für den Nutzer gedacht. */
    class Unavailable(message: String) : Exception(message)

    private companion object {
        const val RATE = 16000
        const val CHUNK = 3200 // 100 ms bei 16 kHz * 2 Byte
        const val CANNOT_OPEN = "Mikrofon konnte nicht geöffnet werden"

        /** So lange darf der Aufbau des Telefonkanals dauern, bevor das Handy-Mikro einspringt. */
        const val LINK_WAIT_MS = 4_000L

        /** So lange darf der Telefonkanal im Betrieb fehlen, bevor das Zuhören endet. */
        const val LINK_GRACE_MS = 3_000L
    }
}
