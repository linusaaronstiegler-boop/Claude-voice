package app.claudevoice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * Mikrofon-Aufnahme als roher PCM-Strom (16 kHz, mono, 16 Bit), in 100-ms-Stücken.
 *
 * Bluetooth-Besonderheit: Kopfhörer kennen zwei Betriebsarten. Für Musik (A2DP) ist das Mikro aus;
 * erst im "Telefonie-Modus" (SCO/HFP) liefern AirPods ihr Mikro, dafür klingt alles in Telefonqualität.
 * Deshalb schalten wir nur dann in den Kommunikationsmodus, wenn das AirPods-Mikro gewünscht ist.
 */
class MicSource(context: Context, private val wantBluetooth: Boolean) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    /** true, wenn tatsächlich über das Kopfhörer-Mikro aufgenommen wird. */
    var usingBluetooth = false
        private set

    @SuppressLint("MissingPermission") // RECORD_AUDIO wird in der Activity vor dem Start abgefragt
    fun start(onChunk: (ByteArray, Int) -> Unit) {
        if (wantBluetooth) {
            val headset = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (headset != null) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                usingBluetooth = audio.setCommunicationDevice(headset)
                if (!usingBluetooth) audio.mode = AudioManager.MODE_NORMAL
            }
        }

        val source = if (usingBluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minSize = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minSize, CHUNK * 2)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            releaseRouting()
            throw IllegalStateException("Mikrofon konnte nicht geöffnet werden")
        }
        if (!usingBluetooth) {
            // Ohne diese Zeile würde Android bei verbundenem Headset teils trotzdem dessen Mikro wählen.
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { rec.setPreferredDevice(it) }
        }
        record = rec
        rec.startRecording()
        running = true
        worker = thread(name = "mic") {
            val buffer = ByteArray(CHUNK)
            while (running) {
                val n = rec.read(buffer, 0, buffer.size)
                if (n > 0) onChunk(buffer, n) else if (n < 0) break
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        record?.run { runCatching { stop() }; release() }
        record = null
        releaseRouting()
    }

    private fun releaseRouting() {
        if (usingBluetooth) {
            audio.clearCommunicationDevice()
            audio.mode = AudioManager.MODE_NORMAL
            usingBluetooth = false
        }
    }

    private companion object {
        const val RATE = 16000
        const val CHUNK = 3200 // 100 ms bei 16 kHz * 2 Byte
    }
}
