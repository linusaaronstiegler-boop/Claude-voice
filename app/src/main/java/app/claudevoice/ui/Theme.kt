package app.claudevoice.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Die Farben der App an einer Stelle. Drei Grundfarben (Navy, Gold, Creme) und wenige Ableitungen.
 *
 * Warum Ableitungen: Gold auf Creme sieht edel aus, ist als Schrift aber zu blass (Kontrast etwa 2:1,
 * gut lesbar ist ab 4,5:1). Deshalb ist Gold hier nur Schmuck (Linien, Flächen mit Navy-Schrift darauf);
 * für goldene Schrift auf hellem Grund gibt es das dunklere [GoldDeep]. Die Kontraste prüft ein Test.
 */
object Palette {
    val Navy = Color(0xFF1F3864)
    val Gold = Color(0xFFC9A84C)
    val Cream = Color(0xFFF5F0E8)

    /** Karten und Eingabefelder: ein Hauch heller als der Hintergrund, wie gutes Briefpapier. */
    val Paper = Color(0xFFFFFDF8)

    /** Gold als Schrift auf hellem Grund (Abschnitts-Etiketten). */
    val GoldDeep = Color(0xFF7A5F17)

    /** Gedämpftes Navy für Nebentext. */
    val InkSoft = Color(0xFF4A5A7A)

    /** Rahmen von Eingabefeldern und Auswahlleisten: muss sich vom Hintergrund abheben (mindestens 3:1). */
    val Outline = Color(0xFF74809A)

    /** Feine Trennlinien ohne Funktion. */
    val Hairline = Color(0xFFD9CFBB)

    /** Fehler und Hinweise: ein ruhiges Weinrot statt Signalrot. */
    val Alert = Color(0xFF8E2A2A)
}

private val Serif = FontFamily.Serif

// Überschriften in Serifenschrift, Fließtext in der Standardschrift des Geräts (gut lesbar in klein).
private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
        titleLarge = titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
        titleMedium = titleMedium.copy(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
        labelLarge = labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        // Etiketten wie "MITSCHRIFT": klein, gesperrt, in Großbuchstaben.
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
    )
}

private val AppColors = lightColorScheme(
    primary = Palette.Navy, onPrimary = Palette.Cream,
    secondary = Palette.Gold, onSecondary = Palette.Navy,
    background = Palette.Cream, onBackground = Palette.Navy,
    surface = Palette.Cream, onSurface = Palette.Navy,
    surfaceVariant = Palette.Paper, onSurfaceVariant = Palette.InkSoft,
    // Dialoge und Menüs nehmen diese "Container"-Töne; ohne Angabe wären sie lila getönt.
    surfaceContainerLowest = Palette.Paper, surfaceContainerLow = Palette.Paper, surfaceContainer = Palette.Paper,
    surfaceContainerHigh = Palette.Paper, surfaceContainerHighest = Palette.Paper,
    outline = Palette.Outline, outlineVariant = Palette.Hairline,
    error = Palette.Alert, onError = Palette.Cream,
)

// Kleine Radien: wirkt wie Papier und Karten, nicht wie Sprechblasen.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

/** Das Erscheinungsbild der ganzen App. Bewusst nur hell: Creme ist Teil des Charakters. */
@Composable
fun ClaudeVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = AppTypography, shapes = AppShapes, content = content)
}
