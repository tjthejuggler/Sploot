package com.example.sploot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Koala-sleep light theme: calm lavender surfaces, eucalypt-green primary,
 * dark plum ink text. Light-only by design — it matches the pastel launcher
 * icon; no dynamic color (dynamic palettes are uncontrolled).
 */
private val KoalaSleepLightScheme = lightColorScheme(
    primary = Eucalypt,
    onPrimary = OnEucalypt,
    primaryContainer = EucalyptContainer,
    onPrimaryContainer = OnEucalypt,
    secondary = KoalaFur,
    onSecondary = OnKoalaFur,
    secondaryContainer = KoalaFurContainer,
    onSecondaryContainer = OnKoalaFur,
    tertiary = DawnRose,
    onTertiary = OnDawnRose,
    tertiaryContainer = DawnRoseContainer,
    onTertiaryContainer = OnDawnRose,
    background = NightMist,
    onBackground = TextOnDark,
    surface = MoonSurface,
    onSurface = TextOnDark,
    surfaceVariant = Starlight,
    onSurfaceVariant = TextMuted,
    outline = OutlineDeep,
    outlineVariant = OutlineMuted,
    error = ErrorRose,
    onError = OnErrorRose,
    errorContainer = ErrorRoseContainer,
    onErrorContainer = OnErrorRose,
    surfaceContainer = Starlight,
    surfaceContainerHigh = MoonSurfaceHigh,
    surfaceContainerHighest = MoonSurfaceHighest,
    scrim = TextOnDark,
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
        colorScheme = KoalaSleepLightScheme,
        typography = Typography,
        shapes = KoalaShapes,
        content = content,
    )
}
