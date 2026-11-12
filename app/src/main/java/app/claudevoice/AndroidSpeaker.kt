package app.claudevoice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import app.claudevoice.core.Speaker
import app.claudevoice.core.SpeechSynth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spielt die Sprachausgabe ab, während sie noch vom Server eintrifft.
 * Ein AudioTrack im Streaming-Modus ist wie ein Wasserhahn: wir schütten PCM-Bytes hinein,
 * Android spielt sie in der Reihenfolge ab.
 *
 * Rücksicht auf Musik: Android regelt mit dem "Audiofokus", wer gerade zu hören sein soll.
 * Sobald der erste Ton einer Antwort da ist, bitten wir kurz um den Fokus; die Musik-App pausiert
 * dann (oder wird leiser) und macht nach der Antwort von selbst weiter. Kommt gar keine Antwort,
 * wird die Musik nie angefasst.
 *
 * Kopfhörer-Taste: Nur während eine Antwort läuft, meldet sich die App als "Abspieler" an. Ein Druck
 * auf die Taste stoppt dann die Antwort. Davor und danach gehört die Taste wieder der Musik-App.
 */
class AndroidSpeaker(
    context: Context,
    private val synth: SpeechSynth,
    voiceCallRoute: Boolean,
    pauseMusic: Boolean,
    private val onStopRequested: () -> Unit,
) : Speaker {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        // Läuft das Kopfhörer-Mikro (Telefonie-Modus), muss auch die Ausgabe über diesen Kanal,
        // sonst landet sie auf dem Handy-Lautsprecher.
        .setUsage(if (voiceCallRoute) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(
        // TRANSIENT = "kurz, gleich wieder weg": die Musik pausiert. MAY_DUCK = sie wird nur leiser.
        if (pauseMusic) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    )
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            // Jemand anderes braucht den Ton dringender (z. B. ein Anruf): die Antwort bricht ab.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onStopRequested()
        }, main)
        .build()

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes)
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
    private var session: MediaSession? = null

    override suspend fun speak(sentences: ReceiveChannel<String>, onFirstAudio: () -> Unit) {
        var started = false
        var odd = -1 // einzelnes Byte, dessen Partner erst im nächsten Stück kommt (ein Sample = 2 Bytes)
        val pair = ByteArray(2)
        try {
            synth.synthesize(sentences) { pcm, length ->
                if (!started) {
                    started = true
                    begin()
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
            if (started) {
                // Erst zurückkehren, wenn der letzte Satz auch wirklich zu Ende gespielt ist.
                withTimeoutOrNull(20_000) {
                    while ((track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < framesWritten) delay(20)
                }
            }
        } catch (e: CancellationException) {
            stop() // abgebrochen: sofort still sein
            throw e
        } finally {
            if (started) end()
        }
    }

    /** Erster Ton einer Antwort: Fokus holen, Kopfhörer-Taste übernehmen, Wiedergabe starten. */
    private fun begin() {
        audio.requestAudioFocus(focusRequest)
        session = runCatching {
            MediaSession(appContext, "ClaudeVoice").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(intent: Intent): Boolean {
                        // Die typsichere Variante gibt es erst ab Android 13; darunter bleibt nur die alte.
                        val event = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        } ?: return false
                        if (event.action == KeyEvent.ACTION_DOWN) onStopRequested()
                        return true
                    }
                }, main)
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_STOP)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .build()
                )
                isActive = true
            }
        }.getOrNull()
        track.play()
    }

    /** Antwort vorbei: Taste und Fokus zurückgeben, die Musik läuft von selbst weiter. */
    private fun end() {
        runCatching { session?.release() }
        session = null
        audio.abandonAudioFocusRequest(focusRequest)
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
        end()
        track.release()
    }
}
