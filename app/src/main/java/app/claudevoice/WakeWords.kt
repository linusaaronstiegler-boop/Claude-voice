package app.claudevoice

import android.content.Context
import android.util.Log
import app.claudevoice.core.OpenWakeWord
import app.claudevoice.core.WakeWordDetector

/**
 * Lädt den lokalen Weckwort-Erkenner ("Hey Jarvis").
 * Die drei Modelldateien stecken als Assets in der APK (der Build lädt sie, siehe app/build.gradle.kts).
 * Fehlen sie oder scheitert das Laden, gibt es null zurück: die App hört dann durchgehend zu.
 */
object WakeWords {
    fun create(context: Context): WakeWordDetector? = try {
        fun asset(name: String) = context.assets.open("wakeword/$name").use { it.readBytes() }
        OpenWakeWord(
            asset(OpenWakeWord.MEL_FILE),
            asset(OpenWakeWord.EMBEDDING_FILE),
            asset(OpenWakeWord.WAKE_FILE),
        )
    } catch (e: Throwable) {
        // Throwable statt Exception: fehlt die Maschinenbibliothek, kommt ein UnsatisfiedLinkError (ein Error).
        Log.w("ClaudeVoice", "Weckwort-Erkenner nicht verfügbar: ${e.javaClass.simpleName}")
        null
    }
}
