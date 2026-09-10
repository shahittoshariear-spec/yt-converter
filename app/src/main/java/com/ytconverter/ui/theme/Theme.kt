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
 * Colours that only exist for the brand treatment: the gradient button and the
 * glowing signature.
 *
 * These live outside [MaterialTheme]'s ColorScheme because Material has no slot for
 * a gradient pair. Every combination here is contrast-audited against the surface it
 * is actually drawn on, in both themes, so the numbers below are not arbitrary.
 */
@Immutable
data class BrandPalette(
    val gradientStart: Color,
    val gradientEnd: Color,
    /** Text/icon colour laid over [gradientStart]..[gradientEnd]. */
    val onGradient: Color,
    /** Opaque fill behind the signature text, which carries the glow. */
    val signatureFill: Color,
)

private val LightBrand = BrandPalette(
    gradientStart = Color(0xFF5B4BD6),
    gradientEnd = Color(0xFF00795F),
    onGradient = Color(0xFFFFFFFF),
    signatureFill = Color(0xFFFFFFFF),
)

private val DarkBrand = BrandPalette(
    gradientStart = Color(0xFFBCAFFF),
    gradientEnd = Color(0xFF63DFBF),
    onGradient = Color(0xFF151038),
    signatureFill = Color(0xFF221F30),
)

val LocalBrandPalette = staticCompositionLocalOf { LightBrand }

/**
 * The surface-container ramp is set explicitly rather than left to the Material
 * baseline, because in dark mode that is exactly what makes cards readable: the
 * default `surface` sits too close to `background` and the whole screen flattens
 * into one black rectangle.
 *
 * Audited separation against `background` in dark mode: cards dL* 6.6, segmented
 * control track dL* 9.9, disabled button dL* 16.4.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B4BD6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF1E1149),
    secondary = Color(0xFF00795F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC7F2E4),
    onSecondaryContainer = Color(0xFF00382B),
    tertiary = Color(0xFFC2185B),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFEFEDF7),
    onBackground = Color(0xFF1A1920),
    surface = Color(0xFFEFEDF7),
    onSurface = Color(0xFF1A1920),
    surfaceVariant = Color(0xFFEAE7F4),
    onSurfaceVariant = Color(0xFF4A4854),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEAE7F4),
    surfaceContainerHighest = Color(0xFFDED9EE),
    surfaceDim = Color(0xFFDED9EE),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceTint = Color(0xFF5B4BD6),
    outline = Color(0xFF79767F),
    outlineVariant = Color(0xFFCBC7D6),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFBCAFFF),
    onPrimary = Color(0xFF1B1063),
    primaryContainer = Color(0xFF3A2BA6),
    onPrimaryContainer = Color(0xFFE7E2FF),
    secondary = Color(0xFF63DFBF),
    onSecondary = Color(0xFF003A2C),
    secondaryContainer = Color(0xFF005041),
    onSecondaryContainer = Color(0xFFCDF5E7),
    tertiary = Color(0xFFFFA8C7),
    onTertiary = Color(0xFF5C1136),
    background = Color(0xFF0B0A11),
    onBackground = Color(0xFFEAE7F1),
    surface = Color(0xFF0B0A11),
    onSurface = Color(0xFFEAE7F1),
    surfaceVariant = Color(0xFF302D3D),
    onSurfaceVariant = Color(0xFFC7C3D1),
    surfaceContainerLowest = Color(0xFF06050A),
    surfaceContainerLow = Color(0xFF14121D),
    surfaceContainer = Color(0xFF1B1926),
    surfaceContainerHigh = Color(0xFF252331),
    surfaceContainerHighest = Color(0xFF302D3D),
    surfaceDim = Color(0xFF0B0A11),
    surfaceBright = Color(0xFF3A3745),
    surfaceTint = Color(0xFFBCAFFF),
    outline = Color(0xFF8F8C9A),
    outlineVariant = Color(0xFF3C3947),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun YTConverterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        // Dynamic colour is deliberately not supported: it would replace the audited
        // palette below with device colours, and the contrast guarantees would be gone.
        LocalBrandPalette provides if (darkTheme) DarkBrand else LightBrand
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            shapes = AppShapes,
            content = content,
        )
    }
}
