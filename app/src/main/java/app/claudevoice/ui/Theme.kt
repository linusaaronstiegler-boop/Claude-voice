package app.claudevoice.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Hell oder dunkel? [System] folgt der Einstellung des Handys, die beiden anderen legen es fest.
 * [key] ist der Wert, der in den Prefs steht.
 */
enum class Appearance(val key: String) {
    System("system"), Light("hell"), Dark("dunkel");

    /** Ob dunkel gezeichnet wird; [systemDark] sagt, was das Handy gerade eingestellt hat. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        /** Unbekannte oder fehlende Werte fallen auf [System] zurück. */
        fun fromKey(key: String?): Appearance = entries.firstOrNull { it.key == key } ?: System
    }
}

/** Die drei Grundfarben der App. Alles andere ist daraus abgeleitet und steht in [GlassColors]. */
object Palette {
    val Navy = Color(0xFF1F3864)
    val Gold = Color(0xFFC9A84C)
    val Cream = Color(0xFFF5F0E8)
}

/**
 * Alle Farben eines Erscheinungsbilds an einer Stelle. Es gibt zwei Sätze davon: [LightGlass] und [DarkGlass].
 *
 * Die Oberfläche ist wie Glas gedacht: hinten ein ruhiger Hintergrund mit zwei weichen Lichtscheinen
 * (einer kühl in Navy, einer warm in Gold), davor Scheiben aus Milchglas. Eine Scheibe ist nur eine
 * durchscheinende weiße Fläche mit einer hauchdünnen Kante, die oben heller ist als unten, so als fiele
 * Licht von oben darauf. Deshalb sind viele Farben hier halb durchsichtig (der Alpha-Wert in den ersten
 * beiden Stellen, z. B. 0x1A = 10 %).
 *
 * Gold ist Schmuck und Akzent, aber als kleine Schrift zu blass (auf Creme etwa 2:1, gut lesbar ist ab
 * 4,5:1). Für goldene Schrift gibt es deshalb [label]: im Hellen ein dunkles Goldbraun, im Dunklen ein
 * helles Gold. Dass alle Schriften auf jedem Grund lesbar bleiben, rechnet ein Test nach (FormatsTest).
 */
@Immutable
data class GlassColors(
    val isDark: Boolean,

    // --- Hintergrund ---
    /** Der Grund läuft von oben nach unten ganz leicht von [backdropTop] nach [backdropBottom]. */
    val backdropTop: Color,
    val backdropBottom: Color,
    /** Die Lichtscheine, schon mit ihrer (geringen) Deckkraft. */
    val glowCool: Color,
    val glowWarm: Color,

    // --- Schrift und Schmuck ---
    val ink: Color,
    /** Nebentext: Erklärungen, Zeiten, fremde Stimmen. */
    val inkSoft: Color,
    /** Goldene Schrift: Etiketten und Text-Schaltflächen. */
    val label: Color,
    /** Fehler und Hinweise: ein ruhiges Weinrot statt Signalrot. */
    val alert: Color,
    /** Gold als Schmuck (Punkt, Linien). Nicht für kleine Schrift. */
    val accent: Color,

    // --- Glas ---
    /** Füllung einer Scheibe, oben einen Hauch heller als unten. */
    val glassTop: Color,
    val glassBottom: Color,
    /** Die Kante: oben ein Lichtreflex, unten fast nichts. */
    val rimTop: Color,
    val rimBottom: Color,
    /** Goldene Kante für die Antwortkarte. */
    val accentRimTop: Color,
    val accentRimBottom: Color,
    /** Feine Trennlinie innerhalb einer Scheibe. */
    val hairline: Color,
    /** Weicher Schatten unter schwebenden Scheiben. */
    val shadow: Color,
    /** Notlösung, falls ein Gerät nicht weichzeichnen kann: deckt ab, was hinter schwebendem Glas liegt. */
    val scrim: Color,

    // --- Eingabefelder und Auswahlleisten ---
    /** Vertiefung, in die man schreibt. */
    val well: Color,
    /** Rahmen eines Eingabefelds: muss sich vom Glas abheben (mindestens 3:1). */
    val wellBorder: Color,
    /** Die Schiene der Auswahlleiste und der hellere "Daumen" auf dem gewählten Feld. */
    val track: Color,
    val thumbTop: Color,
    val thumbBottom: Color,
    val thumbRimTop: Color,
    val thumbRimBottom: Color,

    // --- Der große Knopf ---
    /** Ruhend: goldgetöntes Glas. */
    val primaryTop: Color,
    val primaryBottom: Color,
    val primaryRimTop: Color,
    val primaryRimBottom: Color,
    val onPrimary: Color,
    /** Während zugehört wird: volles Gold, damit man es ohne Lesen sieht. */
    val activeTop: Color,
    val activeBottom: Color,
    val activeRimTop: Color,
    val activeRimBottom: Color,
    val onActive: Color,

    /** Deckende Fläche für Dialoge (die liegen in einem eigenen Fenster, Glas ginge dort nicht). */
    val dialog: Color,
)

private val White = Color.White
private val Black = Color.Black

/** Helles Gold: goldene Schrift auf dunklem Grund. */
private val GoldLight = Color(0xFFD9BC6A)

/** Tiefes Navy: Schrift auf dem goldenen Knopf. */
private val NavyDeep = Color(0xFF14213D)

/** Hell: warmes Creme, Schrift in Navy. */
val LightGlass = GlassColors(
    isDark = false,
    backdropTop = Color(0xFFF7F3EC), backdropBottom = Color(0xFFEEE7DA),
    glowCool = Palette.Navy.copy(alpha = 0.045f), glowWarm = Palette.Gold.copy(alpha = 0.18f),
    ink = Palette.Navy, inkSoft = Color(0xFF4A5A7A), label = Color(0xFF6B5312), alert = Color(0xFF8E2A2A),
    accent = Palette.Gold,
    glassTop = White.copy(alpha = 0.70f), glassBottom = White.copy(alpha = 0.56f),
    rimTop = White, rimBottom = Palette.Navy.copy(alpha = 0.12f),
    accentRimTop = Palette.Gold, accentRimBottom = Palette.Gold.copy(alpha = 0.45f),
    hairline = Palette.Navy.copy(alpha = 0.12f),
    shadow = Palette.Navy.copy(alpha = 0.13f),
    scrim = Color(0xFFF7F3EC).copy(alpha = 0.80f),
    well = White.copy(alpha = 0.55f), wellBorder = Color(0xFF74809A),
    track = Palette.Navy.copy(alpha = 0.08f),
    thumbTop = White, thumbBottom = White.copy(alpha = 0.90f),
    thumbRimTop = White, thumbRimBottom = Palette.Navy.copy(alpha = 0.16f),
    primaryTop = Palette.Gold.copy(alpha = 0.58f), primaryBottom = Palette.Gold.copy(alpha = 0.44f),
    primaryRimTop = White.copy(alpha = 0.90f), primaryRimBottom = Palette.Gold.copy(alpha = 0.70f),
    onPrimary = Palette.Navy,
    activeTop = Color(0xFFD4B65C), activeBottom = Palette.Gold,
    activeRimTop = White.copy(alpha = 0.85f), activeRimBottom = Color(0xFFA88A36), onActive = NavyDeep,
    dialog = Color(0xFFFFFDF8),
)

/** Dunkel: tiefes Blauschwarz (kein reines Schwarz), Schrift in Creme. Hier ist die App zu Hause. */
val DarkGlass = GlassColors(
    isDark = true,
    backdropTop = Color(0xFF0D1525), backdropBottom = Color(0xFF070B14),
    glowCool = Color(0xFF2F4F8F).copy(alpha = 0.26f), glowWarm = Palette.Gold.copy(alpha = 0.10f),
    ink = Palette.Cream, inkSoft = Color(0xFFB8BCC8), label = GoldLight, alert = Color(0xFFF0B0A8),
    accent = Palette.Gold,
    glassTop = White.copy(alpha = 0.10f), glassBottom = White.copy(alpha = 0.06f),
    rimTop = White.copy(alpha = 0.30f), rimBottom = White.copy(alpha = 0.07f),
    accentRimTop = GoldLight.copy(alpha = 0.80f), accentRimBottom = Palette.Gold.copy(alpha = 0.25f),
    hairline = White.copy(alpha = 0.10f),
    shadow = Black.copy(alpha = 0.36f),
    scrim = Color(0xFF0A101C).copy(alpha = 0.80f),
    well = Black.copy(alpha = 0.20f), wellBorder = Color(0xFF9499A5),
    track = Black.copy(alpha = 0.26f),
    thumbTop = White.copy(alpha = 0.20f), thumbBottom = White.copy(alpha = 0.13f),
    thumbRimTop = White.copy(alpha = 0.45f), thumbRimBottom = White.copy(alpha = 0.10f),
    primaryTop = Palette.Gold.copy(alpha = 0.40f), primaryBottom = Palette.Gold.copy(alpha = 0.24f),
    primaryRimTop = GoldLight.copy(alpha = 0.85f), primaryRimBottom = Palette.Gold.copy(alpha = 0.30f),
    onPrimary = Palette.Cream,
    activeTop = GoldLight, activeBottom = Color(0xFFBF9D42),
    activeRimTop = White.copy(alpha = 0.60f), activeRimBottom = Color(0xFF8F742C), onActive = NavyDeep,
    dialog = Color(0xFF182235),
)

// Die folgenden "CompositionLocal"-Werte reicht Compose unsichtbar an alle Bausteine darunter weiter.
// So muss nicht jede Funktion die Farben als Parameter durchreichen.
private val LocalGlassColors = staticCompositionLocalOf { LightGlass }

/**
 * Ob schwebendes Glas den Inhalt dahinter wirklich weichzeichnen soll. Das braucht eine Fläche, die
 * über die Grafikkarte gezeichnet wird. Die App schaltet es ein (ClaudeVoiceApp). In den Tests wird
 * meist per Software gezeichnet; dort bleibt es aus, und das Glas wirkt allein durch Durchscheinen
 * und Kante.
 */
internal val LocalBackdropBlur = staticCompositionLocalOf { false }

/** Kurzer Zugriff auf die Glas-Werte des aktuellen Erscheinungsbilds, z. B. `Glass.colors.ink`. */
object Glass {
    val colors: GlassColors
        @Composable @ReadOnlyComposable get() = LocalGlassColors.current

    // Großzügige Radien: Scheiben mit weichen Ecken, Knöpfe und Auswahlleisten als Kapseln.
    // Eingabefelder liegen in einer Scheibe und sind deshalb etwas weniger rund: innen ein kleinerer
    // Bogen als außen wirkt wie aus einem Guss.
    val CardShape = RoundedCornerShape(24.dp)
    val FieldShape = RoundedCornerShape(16.dp)
    val Capsule = RoundedCornerShape(percent = 50)
}

/** Farbe für den Fensterhintergrund, bevor die Oberfläche gezeichnet ist (sonst blitzt es beim Start weiß). */
fun backdropArgb(dark: Boolean): Int = (if (dark) DarkGlass else LightGlass).backdropTop.toArgb()

private val Serif = FontFamily.Serif

// Überschriften in Serifenschrift, Fließtext in der Standardschrift des Geräts (gut lesbar in klein).
private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.sp),
        titleLarge = titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.2.sp),
        titleMedium = titleMedium.copy(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
        labelLarge = labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        // Etiketten wie "MITSCHRIFT": klein, gesperrt, in Großbuchstaben.
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
    )
}

// Material-Bausteine, die wir fertig übernehmen (Dialog, Text-Schaltflächen, Schreibmarke), nehmen ihre
// Farben aus diesem Schema. Ohne die Angaben wären sie lila getönt.
private fun GlassColors.toMaterial() = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = label, onPrimary = backdropTop,
    secondary = accent, onSecondary = Palette.Navy,
    background = backdropTop, onBackground = ink,
    surface = backdropTop, onSurface = ink,
    surfaceVariant = dialog, onSurfaceVariant = inkSoft,
    surfaceContainerLowest = dialog, surfaceContainerLow = dialog, surfaceContainer = dialog,
    surfaceContainerHigh = dialog, surfaceContainerHighest = dialog,
    outline = wellBorder, outlineVariant = hairline,
    error = alert, onError = backdropTop,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Das Erscheinungsbild der ganzen App.
 *
 * @param appearance hell, dunkel oder wie das Handy (Standard)
 * @param backdropBlur echte Unschärfe hinter schwebendem Glas, siehe [LocalBackdropBlur]
 */
@Composable
fun ClaudeVoiceTheme(
    appearance: Appearance = Appearance.System,
    backdropBlur: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (appearance.isDark(isSystemInDarkTheme())) DarkGlass else LightGlass
    CompositionLocalProvider(LocalGlassColors provides colors, LocalBackdropBlur provides backdropBlur) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = AppTypography, shapes = AppShapes, content = content)
    }
}
