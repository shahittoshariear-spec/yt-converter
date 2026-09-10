package com.ytconverter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Brand accents, reused for the gradient surfaces the app leans on. */
val Violet = Color(0xFF6C5CE7)
val Mint = Color(0xFF00B894)
val Coral = Color(0xFFFD79A8)

private val LightScheme = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF1E1149),
    secondary = Mint,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF3E6),
    onSecondaryContainer = Color(0xFF00382B),
    tertiary = Coral,
    onTertiary = Color.White,
    background = Color(0xFFF7F6FC),
    onBackground = Color(0xFF1B1A21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1A21),
    surfaceVariant = Color(0xFFE8E5F4),
    onSurfaceVariant = Color(0xFF48464F),
    outline = Color(0xFF797680),
    outlineVariant = Color(0xFFCAC5D6),
    error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB9ACFF),
    onPrimary = Color(0xFF241570),
    primaryContainer = Color(0xFF3B2CA6),
    onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = Color(0xFF6EDCC0),
    onSecondary = Color(0xFF00382B),
    secondaryContainer = Color(0xFF005041),
    onSecondaryContainer = Color(0xFFCCF3E6),
    tertiary = Color(0xFFFFAECB),
    onTertiary = Color(0xFF571539),
    background = Color(0xFF0F0E14),
    onBackground = Color(0xFFE5E1EA),
    surface = Color(0xFF17161F),
    onSurface = Color(0xFFE5E1EA),
    surfaceVariant = Color(0xFF24222F),
    onSurfaceVariant = Color(0xFFC7C3D2),
    outline = Color(0xFF918F9C),
    outlineVariant = Color(0xFF46444F),
    error = Color(0xFFFFB4AB),
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
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(
        colorScheme = colors,
        shapes = AppShapes,
        content = content,
    )
}
