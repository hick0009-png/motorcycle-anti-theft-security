package com.example.motorcycleantitheftsensor.theme

import androidx.compose.ui.graphics.Color

// DEPRECATED legacy "OLED High-Contrast Dark Slate" palette. The app design system is
// paper-light (see Theme.kt); no screen should import these for new work. Kept only as
// a reference for a possible future opt-in dark theme (slice 4 candidate).
// SettingsScreen now carries its own light-safe file-private palette instead.
val DarkBackground = Color(0xFF020617)
val DarkSurface = Color(0xFF0E1726)
val DarkSurfaceVariant = Color(0xFF1E293B)
val DarkBorder = Color(0xFF334155)

// Accent & Security Status Tokens
// NOTE: the base hues below are tuned for dark backgrounds. On the paper-light theme,
// use the *Dark variants (TrustBlueDark / ArmedGreenDark / AlertRedDark) or amber-700
// (#B45309) for text/icons so WCAG AA 4.5:1 holds on white.
val TrustBlue = Color(0xFF3B82F6)
val TrustBlueDark = Color(0xFF1D4ED8)
val ArmedGreen = Color(0xFF10B981)
val ArmedGreenDark = Color(0xFF047857)
val WarningAmber = Color(0xFFF59E0B)
val AlertRed = Color(0xFFEF4444)
val AlertRedDark = Color(0xFFB91C1C)
val CyanAccent = Color(0xFF06B6D4)

// DEPRECATED text tokens from the dark palette (near-white on dark). On paper-light
// use colorScheme.onSurface / onSurfaceVariant / outline instead.
val TextHighEmphasis = Color(0xFFF8FAFC)
val TextMediumEmphasis = Color(0xFFCBD5E1)
val TextMuted = Color(0xFF94A3B8)

// Legacy compatibility tokens
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
