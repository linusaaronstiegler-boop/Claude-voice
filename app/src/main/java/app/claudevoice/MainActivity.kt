package app.claudevoice

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.claudevoice.ui.ClaudeVoiceApp
import app.claudevoice.ui.backdropArgb

/**
 * Der Einstieg der App. Hier steht absichtlich fast nichts: die Activity richtet nur das Fenster ein
 * und übergibt an die Oberfläche in `ui/` (Aussehen in Theme.kt, Seiten in MainScreen.kt und
 * SettingsScreen.kt, Anbindung an Service und Speicher in ClaudeVoiceApp.kt).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Vorläufig so, wie das Handy eingestellt ist. Sobald die Oberfläche steht, meldet sie über
        // "onDarkChange", ob in den Einstellungen etwas anderes gewählt wurde.
        applySystemBars(dark = resources.configuration.isNightModeActive)
        // Öffnet sich die Tastatur, soll der Inhalt Platz machen statt verdeckt zu werden
        // (die Seiten halten dafür mit "safeDrawing" Abstand zur Tastatur).
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent { ClaudeVoiceApp(onDarkChange = { applySystemBars(it) }) }
    }
}

/**
 * Die App zeichnet bis unter Status- und Navigationsleiste ("edge to edge"); die Leisten selbst
 * sind durchsichtig. Ihre Symbole (Uhr, Akku, Zurück-Geste) müssen zum Grund passen:
 * auf dunklem Grund hell, auf hellem dunkel. Darf mehrfach aufgerufen werden.
 *
 * Steht außerhalb der Klasse (als Erweiterung jeder Activity), damit ein Test es an einer leeren
 * Test-Activity nachprüfen kann: MainActivity selbst lässt sich dort nicht starten, weil sie den
 * Schlüsselspeicher des Handys braucht.
 */
internal fun ComponentActivity.applySystemBars(dark: Boolean) {
    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    // Der Fensterhintergrund ist zu sehen, bis das erste Bild gezeichnet ist. In der Farbe des
    // Erscheinungsbilds blitzt beim Start nichts auf.
    window.setBackgroundDrawable(ColorDrawable(backdropArgb(dark)))
}
