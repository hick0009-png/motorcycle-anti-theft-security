package com.example.motorcycleantitheftsensor.theme

import androidx.compose.ui.graphics.Color

/**
 * Every colour the app is allowed to draw with.
 *
 * Three palettes used to exist at once: a monochrome scheme in the theme, a navy one the
 * home screen declared for itself and applied over the theme, and a blue/green/amber set
 * private to the settings screen. They disagreed about the two colours that carry the most
 * meaning here — a phone was "healthy" in one green on one screen and another green on the
 * next, and "alerting" in two different reds — which is exactly the kind of drift a person
 * reads as two different apps.
 *
 * Navy won because it is what the owner already sees on the home screen, and because an
 * instrumented test pins the selected navigation destination to it.
 *
 * Every value below is measured, not chosen by eye. The ratios in the comments are computed
 * against the surface each colour is actually drawn on, and `ProtectionPaletteContrastTest`
 * recomputes them on every build — a colour that stops meeting AA fails the suite rather
 * than shipping quietly.
 */

// ---------------------------------------------------------------- structure

/** The brand. Protection, not alarm. 13.1:1 on white. */
val Navy = Color(0xFF16324F)

/** Lighter navy for headers and decorative accents. 7.2:1 on white. */
val NavyHeader = Color(0xFF245B85)

/** Page behind the cards. */
val Canvas = Color(0xFFF8FAFC)

/** Tinted navy surface for grouped rows and chips. */
val SurfaceNavySoft = Color(0xFFE6EFF8)

/** Neutral tinted surface for inner rows that must not read as navy. */
val SurfaceMuted = Color(0xFFF2F4F7)

/** Body text. 15.5:1 on white. */
val Ink = Color(0xFF1C2530)

/**
 * Secondary text. Darkened from the old `#667085`, which met AA on white but only reached
 * 4.28:1 on the tinted surface it was most often drawn on — the failure was invisible
 * precisely where the text was smallest.
 */
val InkMuted = Color(0xFF5B6879)

/**
 * Borders that mean something: input outlines, inactive tracks, card edges that separate.
 * The old `#C8D3DF` measured 1.5:1 and could not be seen against white at all; non-text
 * contrast asks for 3:1 and this gives 3.3:1.
 */
val OutlineStrong = Color(0xFF7D8FA3)

/** Hairlines between rows, which carry no meaning and stay quiet. */
val OutlineHairline = Color(0xFFC8D3DF)

// ---------------------------------------------------------------- status

/**
 * Armed and healthy. 5.5:1 on white.
 *
 * The home screen used `#168A63` for this, which measures 4.33:1 — under AA, on the single
 * word an owner most needs to read at a glance in daylight.
 */
val StatusHealthy = Color(0xFF047857)
val StatusHealthyContainer = Color(0xFFE6F5EF)

/** Degraded: protecting, but not with everything. 5.9:1 on white. */
val StatusWarning = Color(0xFF96530A)
val StatusWarningContainer = Color(0xFFFDF1E1)

/** An alert, and the destructive actions that share its weight. 7.5:1 on white. */
val StatusCritical = Color(0xFFA81818)
val StatusCriticalContainer = Color(0xFFFCE8E6)

/** Offline, idle, not yet measured: absence of a claim, not a fault. 7.6:1 on white. */
val StatusIdle = Color(0xFF4B5563)
val StatusIdleContainer = Color(0xFFEEF2F6)
