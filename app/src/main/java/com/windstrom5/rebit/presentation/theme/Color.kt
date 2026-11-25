package com.windstrom5.rebit.presentation.theme

import androidx.compose.ui.graphics.Color

// ── Audiophile hardware palette ─────────────────────────────────────────────
// Direction: a premium rack-unit console, not a hacker dashboard — restrained
// ink-black surfaces, one cool signal-blue accent doing most of the work, and
// a warm brass accent held in reserve for things that genuinely deserve
// emphasis (an active/armed state, a highlighted number) rather than spread
// across every label. Previous palette's neon 0xFF00FFD1 teal is kept only as
// a tertiary accent — it reads more "CRT terminal" than "hi-fi hardware" as a
// dominant color, but is still useful for a rare success/live indicator.

// Backgrounds — three flat layers, no gradients needed for depth
val BackgroundInk       = Color(0xFF090B10) // App background
val SurfaceInk          = Color(0xFF0F1218) // Screen-level surface
val SurfacePanel        = Color(0xFF171B24) // Raised panel / card
val SurfacePanelHigh    = Color(0xFF212736) // Nested/hovered panel, dialogs

// Text
val TextPrimary         = Color(0xFFF2F4F8) // Primary reading text
val TextSecondary       = Color(0xFFA4ACBC) // Secondary/caption text
val TextTertiary        = Color(0xFF636B7A) // Disabled / least-important
val TextOnAccent        = Color(0xFF06090D) // Text drawn on top of SignalBlue/HifiBrass fills

// Primary accent — a cool signal-blue, used for the one thing per screen that
// most deserves attention (primary action, active tab, the number that matters)
val SignalBlue          = Color(0xFF7BA7FF)
val SignalBlueDim       = Color(0xFF263E6B) // Muted variant for borders/inactive fills
val SignalBlueGlow      = Color(0x267BA7FF) // ~15% wash for subtle fills/highlights

// Secondary accent — warm brass, reserved for "armed/active" states and
// standout numbers (matches classic hi-fi VU-meter warmth)
val HifiBrass           = Color(0xFFF2B866)
val HifiBrassDim        = Color(0xFF4A3A22)

// Tertiary accent — the old neon teal, demoted to occasional live/success use
val SignalTeal          = Color(0xFF45E0C0)
val SignalTealDim       = Color(0xFF1D4A42)

// Status
val StatusGood          = Color(0xFF4CC38A)
val StatusWarn          = Color(0xFFE0A73E)
val StatusError         = Color(0xFFE8654F)

// Level-meter gradient stops (quiet → loud → clipping)
val MeterGreen          = Color(0xFF4CC38A)
val MeterAmber          = Color(0xFFE0A73E)
val MeterRed            = Color(0xFFE8654F)

// Structure
val OutlineSubtle       = Color(0xFF1E2430)
val OutlineStrong       = Color(0xFF2D3446)
val GridLine            = Color(0xFF1B2029)

// Deprecated names kept as aliases so any not-yet-migrated screen still
// compiles and reads sanely — remove once every screen is off the old palette.
@Deprecated("Use BackgroundInk", ReplaceWith("BackgroundInk"))
val BackgroundCharcoal = BackgroundInk
@Deprecated("Use SurfaceInk", ReplaceWith("SurfaceInk"))
val SurfaceCharcoal = SurfaceInk
@Deprecated("Use SurfacePanel", ReplaceWith("SurfacePanel"))
val SurfaceRaised = SurfacePanel
@Deprecated("Use StatusError", ReplaceWith("StatusError"))
val ErrorCoral = StatusError
@Deprecated("Use HifiBrass", ReplaceWith("HifiBrass"))
val HifiGold = HifiBrass
@Deprecated("Use SignalBlue", ReplaceWith("SignalBlue"))
val McIntoshBlue = SignalBlue
@Deprecated("Use BackgroundInk", ReplaceWith("BackgroundInk"))
val CarbonBlack = BackgroundInk
@Deprecated("Use StatusWarn", ReplaceWith("StatusWarn"))
val WarnAmber = StatusWarn
@Deprecated("Use SignalBlueGlow", ReplaceWith("SignalBlueGlow"))
val SignalGlow = SignalBlueGlow
