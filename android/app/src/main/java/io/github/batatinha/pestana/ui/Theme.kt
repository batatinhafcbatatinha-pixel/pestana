package io.github.batatinha.pestana.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Reading themes. Each one is tuned for long reading at arm's length:
 * no pure white on pure black except the high-contrast theme, chords in a
 * warm hue that separates from lyrics without shouting, tabs in a calm tone.
 */
@Immutable
data class ReadingTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val muted: Color,
    val chord: Color,
    val header: Color,
    val tab: Color,
    val line: Color,
    val good: Color,
)

object Themes {
    val Dark = ReadingTheme(
        "dark", "Escuro", true,
        bg = Color(0xFF121316), surface = Color(0xFF1B1D21), surface2 = Color(0xFF25282D),
        text = Color(0xFFE6E3DC), muted = Color(0xFF8D9098), chord = Color(0xFFF5B556),
        header = Color(0xFF9DB8E8), tab = Color(0xFFA9C9A4), line = Color(0xFF2E3137), good = Color(0xFF6FD08C),
    )
    val Light = ReadingTheme(
        "light", "Claro", false,
        bg = Color(0xFFFBFAF6), surface = Color(0xFFF1EFE9), surface2 = Color(0xFFE6E3DB),
        text = Color(0xFF1E1F22), muted = Color(0xFF6B6E75), chord = Color(0xFFB0501A),
        header = Color(0xFF2F5DA8), tab = Color(0xFF3B6B47), line = Color(0xFFDDD9CF), good = Color(0xFF2E8B57),
    )
    val Sepia = ReadingTheme(
        "sepia", "Sépia (Kindle)", false,
        bg = Color(0xFFF4ECD8), surface = Color(0xFFEADFC6), surface2 = Color(0xFFDDD0B3),
        text = Color(0xFF3B2F24), muted = Color(0xFF7D6B57), chord = Color(0xFF9A3F12),
        header = Color(0xFF6B4E2E), tab = Color(0xFF55603A), line = Color(0xFFD6C8A8), good = Color(0xFF4E7D3A),
    )
    val Paper = ReadingTheme(
        "paper", "Papel (E-ink)", false,
        bg = Color(0xFFEDEDEA), surface = Color(0xFFE2E2DE), surface2 = Color(0xFFD5D5D0),
        text = Color(0xFF111111), muted = Color(0xFF555555), chord = Color(0xFF000000),
        header = Color(0xFF222222), tab = Color(0xFF333333), line = Color(0xFFC8C8C2), good = Color(0xFF1F6F3F),
    )
    val Night = ReadingTheme(
        "night", "Noturno (luz quente)", true,
        bg = Color(0xFF17130F), surface = Color(0xFF211B15), surface2 = Color(0xFF2C241C),
        text = Color(0xFFD9C9AE), muted = Color(0xFF8E7F69), chord = Color(0xFFE8964A),
        header = Color(0xFFC9A36B), tab = Color(0xFFB0A57E), line = Color(0xFF352C22), good = Color(0xFFA7C46A),
    )
    val Amoled = ReadingTheme(
        "amoled", "Preto (AMOLED)", true,
        bg = Color(0xFF000000), surface = Color(0xFF0E0E0F), surface2 = Color(0xFF1A1A1C),
        text = Color(0xFFDCDCDC), muted = Color(0xFF85878C), chord = Color(0xFFFFB547),
        header = Color(0xFF8FB3F0), tab = Color(0xFF9CCB95), line = Color(0xFF222225), good = Color(0xFF63D383),
    )
    val Contrast = ReadingTheme(
        "contrast", "Alto contraste", true,
        bg = Color(0xFF000000), surface = Color(0xFF111111), surface2 = Color(0xFF222222),
        text = Color(0xFFFFFFFF), muted = Color(0xFFBDBDBD), chord = Color(0xFFFFE600),
        header = Color(0xFF4DE8FF), tab = Color(0xFF9CFF9C), line = Color(0xFF444444), good = Color(0xFF00FF88),
    )

    val all = listOf(Dark, Light, Sepia, Paper, Night, Amoled, Contrast)

    @Composable
    fun resolve(id: String): ReadingTheme = when (id) {
        "auto" -> if (isSystemInDarkTheme()) Dark else Light
        else -> all.firstOrNull { it.id == id } ?: Dark
    }
}

val LocalReading = staticCompositionLocalOf { Themes.Dark }

@Composable
fun PestanaTheme(t: ReadingTheme, content: @Composable () -> Unit) {
    val scheme = if (t.dark) {
        darkColorScheme(
            primary = t.chord, onPrimary = t.bg, secondary = t.header, onSecondary = t.bg,
            background = t.bg, onBackground = t.text, surface = t.bg, onSurface = t.text,
            surfaceVariant = t.surface, onSurfaceVariant = t.muted, surfaceContainer = t.surface,
            surfaceContainerHigh = t.surface2, surfaceContainerHighest = t.surface2, surfaceContainerLow = t.surface,
            outline = t.line, outlineVariant = t.line, secondaryContainer = t.surface2, onSecondaryContainer = t.text,
            primaryContainer = t.surface2, onPrimaryContainer = t.chord,
        )
    } else {
        lightColorScheme(
            primary = t.chord, onPrimary = t.bg, secondary = t.header, onSecondary = t.bg,
            background = t.bg, onBackground = t.text, surface = t.bg, onSurface = t.text,
            surfaceVariant = t.surface, onSurfaceVariant = t.muted, surfaceContainer = t.surface,
            surfaceContainerHigh = t.surface2, surfaceContainerHighest = t.surface2, surfaceContainerLow = t.surface,
            outline = t.line, outlineVariant = t.line, secondaryContainer = t.surface2, onSecondaryContainer = t.text,
            primaryContainer = t.surface2, onPrimaryContainer = t.chord,
        )
    }
    val base = Typography()
    val type = base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalReading provides t) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

val SectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
