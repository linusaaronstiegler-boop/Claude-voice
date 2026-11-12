package app.claudevoice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.claudevoice.core.ElevenTts
import app.claudevoice.core.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spielt die Sprachausgabe ab, während sie noch vom Server eintrifft.
 * Ein AudioTrack im Streaming-Modus ist wie ein Wasserhahn: wir schütten PCM-Bytes hinein,
 * Android spielt sie in der Reihenfolge ab.
 */
class AndroidSpeaker(
    private val tts: ElevenTts,
    private val voiceId: String,
    voiceCallRoute: Boolean,
) : Speaker {

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                // Läuft das AirPods-Mikro (Telefonie-Modus), muss auch die Ausgabe über diesen Kanal,
                // sonst landet sie auf dem Handy-Lautsprecher.
                .setUsage(if (voiceCallRoute) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(ElevenTts.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(
                    ElevenTts.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                ),
                ElevenTts.SAMPLE_RATE, // ca. eine halbe Sekunde Puffer
            )
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    @Volatile private var framesWritten = 0L

    override suspend fun speak(text: String) {
        withContext(Dispatchers.IO) {
            runInterruptible {
                tts.open(text, voiceId).use { response ->
                    val input = response.body!!.byteStream()
                    val buffer = ByteArray(4800)
                    var filled = 0
                    track.play()
                    while (true) {
                        val n = input.read(buffer, filled, buffer.size - filled)
                        if (n < 0) break
                        filled += n
                        // Ein Sample besteht aus 2 Bytes; ein einzelnes übriges Byte wartet auf seinen Partner.
                        val even = filled and 1.inv()
                        if (even > 0) {
                            val written = track.write(buffer, 0, even)
                            if (written < 0) break
                            framesWritten += written / 2
                            if (filled > even) buffer[0] = buffer[even]
                            filled -= even
                        }
                    }
                }
            }
        }
        // Erst zurückkehren, wenn der Satz auch wirklich zu Ende gespielt ist.
        withTimeoutOrNull(15_000) {
            while ((track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < framesWritten) delay(20)
        }
    }

    override fun stop() {
        runCatching {
            track.pause()
            track.flush() // verwirft, was noch im Puffer liegt
        }
        framesWritten = 0
    }

    fun release() {
        stop()
        track.release()
    }
}
