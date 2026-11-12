package app.claudevoice

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.claudevoice.ui.ClaudeVoiceApp
import app.claudevoice.ui.ClaudeVoiceTheme

/**
 * Der Einstieg der App. Hier steht absichtlich fast nichts: die Activity richtet nur das Fenster ein
 * und übergibt an die Oberfläche in `ui/` (Aussehen in Theme.kt, Seiten in MainScreen.kt und
 * SettingsScreen.kt, Anbindung an Service und Speicher in ClaudeVoiceApp.kt).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Die App zeichnet bis unter Status- und Navigationsleiste. "light" heißt: dunkle Symbole,
        // passend zum cremefarbenen Hintergrund, auch wenn das Handy im Dunkelmodus läuft.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Öffnet sich die Tastatur, soll der Inhalt Platz machen statt verdeckt zu werden
        // (die Seiten halten dafür mit "safeDrawing" Abstand zur Tastatur).
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent { ClaudeVoiceTheme { ClaudeVoiceApp() } }
    }
}
