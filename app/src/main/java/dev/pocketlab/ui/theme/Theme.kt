package dev.pocketlab.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.pocketlab.data.Appearance
import dev.pocketlab.data.ThemeMode

@Immutable
data class Tones(
    val bg: Color, val surface: Color, val surfaceHigh: Color, val outline: Color,
    val text: Color, val textDim: Color,
    val ok: Color, val warn: Color, val bad: Color,
    /** Swatch colours for the theme picker preview. */
    val swatch: List<Color>,
)

data class Palette(val name: String, val dark: Tones, val light: Tones)

private fun c(hex: Long) = Color(0xFF000000 or hex)

val Palettes = listOf(
    Palette("Midnight",
        Tones(c(0x000000), c(0x111318), c(0x1B1E25), c(0x2A2E37), c(0xF1F3F7), c(0x9AA1AE), c(0x3DDC97), c(0xF5B748), c(0xFF6B6B), listOf(c(0xFFFFFF), c(0x3DDC97), c(0x5B9DFF))),
        Tones(c(0xF6F7F9), c(0xFFFFFF), c(0xEEF0F4), c(0xD8DCE3), c(0x14171C), c(0x5C6370), c(0x1A9E66), c(0xB7791F), c(0xD64545), listOf(c(0x14171C), c(0x1A9E66), c(0x2F6FDB)))),
    Palette("Catppuccin",
        Tones(c(0x11111B), c(0x1E1E2E), c(0x313244), c(0x45475A), c(0xCDD6F4), c(0xA6ADC8), c(0xA6E3A1), c(0xF9E2AF), c(0xF38BA8), listOf(c(0xCBA6F7), c(0xA6E3A1), c(0x89B4FA))),
        Tones(c(0xEFF1F5), c(0xE6E9EF), c(0xDCE0E8), c(0xBCC0CC), c(0x4C4F69), c(0x6C6F85), c(0x40A02B), c(0xDF8E1D), c(0xD20F39), listOf(c(0x8839EF), c(0x40A02B), c(0x1E66F5)))),
    Palette("Dracula",
        Tones(c(0x191A21), c(0x282A36), c(0x343746), c(0x44475A), c(0xF8F8F2), c(0xB6B9CC), c(0x50FA7B), c(0xF1FA8C), c(0xFF5555), listOf(c(0xBD93F9), c(0x50FA7B), c(0xFF79C6))),
        Tones(c(0xF8F8F2), c(0xFFFFFF), c(0xEDEDF5), c(0xD0D0DC), c(0x282A36), c(0x6272A4), c(0x2E9E4A), c(0xA58B00), c(0xD63A3A), listOf(c(0x7C5CC4), c(0x2E9E4A), c(0xC2409A)))),
    Palette("Gruvbox",
        Tones(c(0x1D2021), c(0x282828), c(0x32302F), c(0x504945), c(0xEBDBB2), c(0xBDAE93), c(0xB8BB26), c(0xFABD2F), c(0xFB4934), listOf(c(0xFABD2F), c(0xB8BB26), c(0x83A598))),
        Tones(c(0xF9F5D7), c(0xFBF1C7), c(0xF2E5BC), c(0xD5C4A1), c(0x3C3836), c(0x665C54), c(0x79740E), c(0xB57614), c(0x9D0006), listOf(c(0xB57614), c(0x79740E), c(0x076678)))),
    Palette("Nord",
        Tones(c(0x242933), c(0x2E3440), c(0x3B4252), c(0x434C5E), c(0xECEFF4), c(0xC0C8D6), c(0xA3BE8C), c(0xEBCB8B), c(0xBF616A), listOf(c(0x88C0D0), c(0xA3BE8C), c(0xEBCB8B))),
        Tones(c(0xECEFF4), c(0xF5F7FA), c(0xE5E9F0), c(0xD8DEE9), c(0x2E3440), c(0x4C566A), c(0x6E8C4F), c(0xB08D3E), c(0xBF616A), listOf(c(0x5E81AC), c(0x6E8C4F), c(0xB08D3E)))),
    Palette("Solarized",
        Tones(c(0x002029), c(0x002B36), c(0x073642), c(0x0E4552), c(0xEEE8D5), c(0x93A1A1), c(0x859900), c(0xB58900), c(0xDC322F), listOf(c(0xEEE8D5), c(0x859900), c(0x268BD2))),
        Tones(c(0xFDF6E3), c(0xFFFBEE), c(0xEEE8D5), c(0xD9D2BC), c(0x073642), c(0x586E75), c(0x859900), c(0xB58900), c(0xDC322F), listOf(c(0x073642), c(0x859900), c(0x268BD2)))),
    Palette("Tokyo Night",
        Tones(c(0x16161E), c(0x1A1B26), c(0x24283B), c(0x2F3549), c(0xC0CAF5), c(0x9AA5CE), c(0x9ECE6A), c(0xE0AF68), c(0xF7768E), listOf(c(0xBB9AF7), c(0x9ECE6A), c(0x7AA2F7))),
        Tones(c(0xE1E2E7), c(0xEDEEF3), c(0xD5D6DB), c(0xC4C8DA), c(0x343B58), c(0x565A6E), c(0x485E30), c(0x8F5E15), c(0x8C4351), listOf(c(0x5A4A78), c(0x485E30), c(0x34548A)))),
    Palette("GitHub",
        Tones(c(0x010409), c(0x0D1117), c(0x161B22), c(0x30363D), c(0xE6EDF3), c(0x8D96A0), c(0x3FB950), c(0xD29922), c(0xF85149), listOf(c(0xE6EDF3), c(0x3FB950), c(0x2F81F7))),
        Tones(c(0xF6F8FA), c(0xFFFFFF), c(0xF0F2F5), c(0xD0D7DE), c(0x1F2328), c(0x656D76), c(0x1A7F37), c(0x9A6700), c(0xCF222E), listOf(c(0x1F2328), c(0x1A7F37), c(0x0969DA)))),
)

val Accents = linkedMapOf(
    "Blue" to c(0x5B9DFF), "Teal" to c(0x26A69A), "Indigo" to c(0x6C7CFF),
    "Purple" to c(0xA066F0), "Pink" to c(0xE8457F), "Slate" to c(0x8A97AD),
)

val LocalTones = staticCompositionLocalOf { Palettes.first().dark }

@Composable
fun isDark(a: Appearance) = when (a.mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun PocketlabTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = isDark(appearance)
    val palette = Palettes.firstOrNull { it.name == appearance.theme } ?: Palettes.first()
    val t = if (dark) palette.dark else palette.light
    val accent = Accents[appearance.accent] ?: Accents.values.first()
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = accent.copy(alpha = 0.22f), onPrimaryContainer = t.text,
        secondary = accent, onSecondary = onAccent, secondaryContainer = t.surfaceHigh, onSecondaryContainer = t.text,
        background = t.bg, onBackground = t.text, surface = t.bg, onSurface = t.text,
        surfaceVariant = t.surfaceHigh, onSurfaceVariant = t.textDim, outline = t.outline, outlineVariant = t.outline,
        surfaceContainerLowest = t.bg, surfaceContainerLow = t.surface, surfaceContainer = t.surface,
        surfaceContainerHigh = t.surfaceHigh, surfaceContainerHighest = t.surfaceHigh,
        error = t.bad, errorContainer = t.bad.copy(alpha = 0.85f), onErrorContainer = Color.Black,
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = accent.copy(alpha = 0.18f), onPrimaryContainer = t.text,
        secondary = accent, onSecondary = onAccent, secondaryContainer = t.surfaceHigh, onSecondaryContainer = t.text,
        background = t.bg, onBackground = t.text, surface = t.bg, onSurface = t.text,
        surfaceVariant = t.surfaceHigh, onSurfaceVariant = t.textDim, outline = t.outline, outlineVariant = t.outline,
        surfaceContainerLowest = t.bg, surfaceContainerLow = t.surface, surfaceContainer = t.surface,
        surfaceContainerHigh = t.surfaceHigh, surfaceContainerHighest = t.surfaceHigh,
        error = t.bad, errorContainer = t.bad, onErrorContainer = Color.White,
    )
    CompositionLocalProvider(LocalTones provides t) {
        MaterialTheme(colorScheme = scheme) {
            // Text outside a Surface would otherwise fall back to black, invisible on dark themes.
            CompositionLocalProvider(LocalContentColor provides t.text, content = content)
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
