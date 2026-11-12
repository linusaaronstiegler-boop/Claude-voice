package app.claudevoice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.claudevoice.core.Speaker
import app.claudevoice.core.SpeechSynth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spielt die Sprachausgabe ab, während sie noch vom Server eintrifft.
 * Ein AudioTrack im Streaming-Modus ist wie ein Wasserhahn: wir schütten PCM-Bytes hinein,
 * Android spielt sie in der Reihenfolge ab.
 */
class AndroidSpeaker(
    private val synth: SpeechSynth,
    voiceCallRoute: Boolean,
) : Speaker {

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                // Läuft das Kopfhörer-Mikro (Telefonie-Modus), muss auch die Ausgabe über diesen Kanal,
                // sonst landet sie auf dem Handy-Lautsprecher.
                .setUsage(if (voiceCallRoute) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(synth.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(synth.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                synth.sampleRate, // ca. eine halbe Sekunde Puffer
            )
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    @Volatile private var framesWritten = 0L

    override suspend fun speak(sentences: ReceiveChannel<String>, onFirstAudio: () -> Unit) {
        var first = true
        var odd = -1 // einzelnes Byte, dessen Partner erst im nächsten Stück kommt (ein Sample = 2 Bytes)
        val pair = ByteArray(2)
        synth.synthesize(sentences) { pcm, length ->
            if (first) {
                first = false
                track.play()
                onFirstAudio()
            }
            withContext(Dispatchers.IO) {
                var from = 0
                if (odd >= 0 && length > 0) {
                    pair[0] = odd.toByte(); pair[1] = pcm[0]
                    if (track.write(pair, 0, 2) > 0) framesWritten += 1
                    odd = -1
                    from = 1
                }
                val even = (length - from) and 1.inv()
                if (even > 0) {
                    val written = track.write(pcm, from, even)
                    if (written > 0) framesWritten += written / 2
                }
                if (from + even < length) odd = pcm[length - 1].toInt() and 0xFF
            }
        }
        // Erst zurückkehren, wenn der letzte Satz auch wirklich zu Ende gespielt ist.
        withTimeoutOrNull(20_000) {
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
