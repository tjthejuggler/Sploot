package com.example.sploot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Koala-sleep night theme: near-black violet-cast surfaces for comfortable
 * nighttime reading, soft pastel accents (eucalypt / koala fur / dawn rose),
 * generous rounded shapes. Dark-only by design — the app is used in bed.
 * No dynamic color (dynamic palettes are uncontrolled).
 */
private val KoalaSleepNightScheme = darkColorScheme(
    primary = Eucalypt,
    onPrimary = OnEucalypt,
    primaryContainer = EucalyptContainer,
    onPrimaryContainer = Eucalypt,
    secondary = KoalaFur,
    onSecondary = OnKoalaFur,
    secondaryContainer = KoalaFurContainer,
    onSecondaryContainer = KoalaFur,
    tertiary = DawnRose,
    onTertiary = OnDawnRose,
    tertiaryContainer = DawnRoseContainer,
    onTertiaryContainer = DawnRose,
    background = NightInk,
    onBackground = StarWhite,
    surface = MoonSurface,
    onSurface = StarWhite,
    surfaceVariant = Starlight,
    onSurfaceVariant = TextMuted,
    outline = OutlineDeep,
    outlineVariant = OutlineMuted,
    error = ErrorRose,
    onError = OnErrorRose,
    errorContainer = ErrorRoseContainer,
    onErrorContainer = ErrorRose,
    surfaceContainer = Starlight,
    surfaceContainerHigh = MoonSurfaceHigh,
    surfaceContainerHighest = MoonSurfaceHighest,
    scrim = NightInk,
)

/** Soft, generously rounded shapes for cards / dialogs / chips. */
private val KoalaShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SplootTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KoalaSleepNightScheme,
        typography = Typography,
        shapes = KoalaShapes,
        content = content,
    )
}
