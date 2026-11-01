package com.example.sploot.ui.theme

import androidx.compose.ui.graphics.Color

// ---- Koala-sleep night palette -------------------------------------------------
// Deep-night theme: near-black surfaces that are easy on the eyes in the dark,
// with the soft koala pastels kept as accents. Stage colors stay harmonized with
// the brand palette but are tuned for contrast on dark surfaces.

// Primary: eucalypt (soft green — primary actions)
val Eucalypt = Color(0xFFB5CBB3)
val EucalyptContainer = Color(0xFF2C3A2A)
val OnEucalypt = Color(0xFF22301F)

// Secondary: koala fur (muted grey-violet)
val KoalaFur = Color(0xFFB4AEC0)
val KoalaFurContainer = Color(0xFF35303D)
val OnKoalaFur = Color(0xFF211E28)

// Tertiary: dawn rose (recovery / autonomic accent)
val DawnRose = Color(0xFFE4C4C9)
val DawnRoseContainer = Color(0xFF46333A)
val OnDawnRose = Color(0xFF3A2B31)

// Night surfaces — layered blacks with a violet cast (darkest -> highest)
val NightInk = Color(0xFF0B0910)        // background: almost black
val MoonSurface = Color(0xFF14111C)     // base surface
val Starlight = Color(0xFF1D1827)       // surfaceVariant
val MoonSurfaceHigh = Color(0xFF262032) // raised container
val MoonSurfaceHighest = Color(0xFF302940)

// Content
val StarWhite = Color(0xFFEDE9F4)       // primary text
val TextMuted = Color(0xFFA79FB8)       // secondary text
val OutlineMuted = Color(0xFF3E3750)
val OutlineDeep = Color(0xFF574E6E)

// Compatibility alias — screens used TextOnDark as the "ink" color; on the night
// theme the ink is starlight white. Kept so older call sites render correctly.
val TextOnDark = StarWhite

// Error (soft rose-red)
val ErrorRose = Color(0xFFE2A9A3)
val ErrorRoseContainer = Color(0xFF4A2C28)
val OnErrorRose = Color(0xFF3A2420)

// ---- Hypnogram stage bands ----------------------------------------------------
// Distinct hues, consistent everywhere stages appear (diary bars, hypnogram,
// legends, proportion bars, A/B table):
//   Awake = soft coral red, REM = pale lavender, Light = mist blue, Deep = royal indigo.
val StageWake = Color(0xFFEF8A80)
val StageRem = Color(0xFFC9C4F0)
val StageLight = Color(0xFF8FB4E3)
val StageDeep = Color(0xFF5A6FD6)

// Chart extras
val HrCurve = Color(0xFF8FB4E3)
val RmssdCurve = Color(0xFF9CC79A)
val RhrMarker = Color(0xFFB4AEC0)
val GridLine = Color(0xFF2E2840)
val ScrubLine = StarWhite

/** Stage color for AASM codes 0..3 (Wake/Light/Deep/REM). */
fun stageColor(stage: Int): Color = when (stage) {
    0 -> StageWake
    1 -> StageLight
    2 -> StageDeep
    else -> StageRem
}
