package com.example.sploot.ui.theme

import androidx.compose.ui.graphics.Color

// ---- Koala-sleep light palette ------------------------------------------------
// Soft pastel, dreamy light scheme matching the light-lavender launcher icon.
// Dark ink text (#423B49) on light lavender/green surfaces.

// Primary: eucalypt (soft green — primary actions)
val Eucalypt = Color(0xFFB5CBB3)
val EucalyptContainer = Color(0xFFD9E7D7)
val OnEucalypt = Color(0xFF33422F)

// Secondary: koala fur (muted grey-violet)
val KoalaFur = Color(0xFF928C98)
val KoalaFurContainer = Color(0xFFD6D2DC)
val OnKoalaFur = Color(0xFF2E2A33)

// Tertiary: dawn rose (recovery / autonomic accent)
val DawnRose = Color(0xFFD2BCBF)
val DawnRoseContainer = Color(0xFFEADADD)
val OnDawnRose = Color(0xFF46333A)

// Light surfaces (lavender mists)
val NightMist = Color(0xFFC1B7D8)   // background
val MoonSurface = Color(0xFFB8B2D6) // surface
val Starlight = Color(0xFFC5BAD9)   // light supporting / surfaceVariant base
val MoonSurfaceHigh = Color(0xFFD4CEE6)
val MoonSurfaceHighest = Color(0xFFDCD7EC)

// Content
val TextOnDark = Color(0xFF423B49)   // primary ink on light surfaces
val TextMuted = Color(0xFF5B5464)    // secondary ink
val OutlineMuted = Color(0xFFB7B2C6)
val OutlineDeep = Color(0xFF8F8A9E)

// Error (soft rose-red, kept pastel)
val ErrorRose = Color(0xFFA9564E)
val ErrorRoseContainer = Color(0xFFE7D2CE)
val OnErrorRose = Color(0xFF4A2C28)

// ---- Hypnogram stage bands ----------------------------------------------------
// Harmonized pastels derived from the koala palette; kept visually distinct:
// Wake = deep koala grey-violet, Light = pale starlight lavender,
// Deep = deep eucalypt green, REM = deep dawn rose.
val StageWake = Color(0xFF7A7386)
val StageLight = Color(0xFFC5BAD9)
val StageDeep = Color(0xFF6F8F6E)
val StageRem = Color(0xFFC79A9E)

// Chart extras
val HrCurve = Color(0xFFB98A8F)
val RmssdCurve = Color(0xFF7FA07D)
val RhrMarker = Color(0xFF928C98)
val GridLine = Color(0xFFDCD5E6)
val ScrubLine = TextOnDark

/** Stage color for AASM codes 0..3 (Wake/Light/Deep/REM). */
fun stageColor(stage: Int): Color = when (stage) {
    0 -> StageWake
    1 -> StageLight
    2 -> StageDeep
    else -> StageRem
}
