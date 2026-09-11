package com.ytconverter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The brand treatment, kept out of [MaterialTheme] because Material has no slot for a
 * gradient. Two pairs, because they have opposite jobs:
 *
 *  * [gradientStart]..[gradientEnd] is a *filled* surface, so [onGradient] sits on top
 *    of it and has to be light enough to read.
 *  * [accentStart]..[accentEnd] is the reverse: it is used *as* text and as a glow, so
 *    it has to be light on a dark background and dark on a light one.
 */
@Immutable
data class BrandPalette(
    val gradientStart: Color,
    val gradientEnd: Color,
    val onGradient: Color,
    val accentStart: Color,
    val accentEnd: Color,
    val signatureFill: Color,
)

private val LightBrand = BrandPalette(
    gradientStart = Color(0xFF5B45D6),
    gradientEnd = Color(0xFF7350E8),
    onGradient = Color(0xFFFFFFFF),
    accentStart = Color(0xFF5B45D6),
    accentEnd = Color(0xFF7A5AF0),
    signatureFill = Color(0xFFFFFFFF),
)

private val DarkBrand = BrandPalette(
    gradientStart = Color(0xFF6644D9),
    gradientEnd = Color(0xFF8058F0),
    onGradient = Color(0xFFFFFFFF),
    accentStart = Color(0xFFA996FF),
    accentEnd = Color(0xFFC9BCFF),
    signatureFill = Color(0xFF1E1E25),
)

val LocalBrandPalette = staticCompositionLocalOf { LightBrand }

/**
 * Deliberately one accent hue.
 *
 * The surfaces are near-neutral charcoal rather than tinted greys: a strong purple
 * cast under every panel is what made the previous dark theme look muddy, especially
 * once two coloured glows were layered on top of it. Teal survives only as the success
 * colour, so nothing else competes with the violet.
 *
 * Separation is measured rather than eyeballed — against `background` in dark mode the
 * cards sit at dL* 6.6, the segmented track at dL* 12.0 and the disabled button at
 * dL* 17.4, and every text pair clears 4.5:1.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B45D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7E1FF),
    onPrimaryContainer = Color(0xFF1E1149),
    secondary = Color(0xFF0F7A63),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC3F0E2),
    onSecondaryContainer = Color(0xFF00382B),
    tertiary = Color(0xFFC2185B),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFEDEDF3),
    onBackground = Color(0xFF1A1A20),
    surface = Color(0xFFEDEDF3),
    onSurface = Color(0xFF1A1A20),
    surfaceVariant = Color(0xFFE6E6ED),
    onSurfaceVariant = Color(0xFF4B4B56),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE6E6ED),
    surfaceContainerHighest = Color(0xFFDCDCE5),
    surfaceDim = Color(0xFFDCDCE5),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceTint = Color(0xFF5B45D6),
    outline = Color(0xFF77777F),
    outlineVariant = Color(0xFFCBCBD4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA996FF),
    onPrimary = Color(0xFF22115E),
    primaryContainer = Color(0xFF33227A),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFF6FD3B8),
    onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF17564A),
    onSecondaryContainer = Color(0xFFB4EEDD),
    tertiary = Color(0xFFFFA9C9),
    onTertiary = Color(0xFF5C1136),
    background = Color(0xFF0F0F12),
    onBackground = Color(0xFFE8E8ED),
    surface = Color(0xFF0F0F12),
    onSurface = Color(0xFFE8E8ED),
    surfaceVariant = Color(0xFF2A2A31),
    onSurfaceVariant = Color(0xFFAAAAB5),
    surfaceContainerLowest = Color(0xFF0A0A0D),
    surfaceContainerLow = Color(0xFF15151A),
    surfaceContainer = Color(0xFF1A1A20),
    surfaceContainerHigh = Color(0xFF232329),
    surfaceContainerHighest = Color(0xFF2D2D35),
    surfaceDim = Color(0xFF0F0F12),
    surfaceBright = Color(0xFF3A3A43),
    surfaceTint = Color(0xFFA996FF),
    outline = Color(0xFF75757F),
    outlineVariant = Color(0xFF34343D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun YTConverterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        // Dynamic colour is deliberately not supported: it would replace the audited
        // palette below with device colours, and every contrast guarantee would go.
        LocalBrandPalette provides if (darkTheme) DarkBrand else LightBrand
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            shapes = AppShapes,
            content = content,
        )
    }
}
