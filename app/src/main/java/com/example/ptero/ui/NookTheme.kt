package com.example.ptero.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── NookTheme Color Tokens ───────────────────────────────────────────────────

object NookColors {
    // Backgrounds
    val AppBackground   = Color(0xFF0B0E14)   // Deep Void
    val CardSurface     = Color(0xFF131822)   // Nook Dark Slate
    val CardBorder      = Color(0xFF1E2638)   // Subtle Slate Outline
    val ConsoleBg       = Color(0xFF090D14)   // Terminal black

    // Text
    val TextPrimary     = Color(0xFFFFFFFF)
    val TextSecondary   = Color(0xFF8C9BAE)
    val TextMuted       = Color(0xFF4A5568)

    // Accent
    val AccentBlue      = Color(0xFF3B82F6)   // Electric Blue
    val AccentBlueDim   = Color(0xFF1E3A5F)   // Button ghost bg

    // Status
    val StatusOnline    = Color(0xFF10B981)   // Emerald Green
    val StatusOffline   = Color(0xFFEF4444)   // Red
    val StatusStarting  = Color(0xFFF59E0B)   // Amber
    val StatusStopping  = Color(0xFFFF6B35)   // Orange

    // Progress bar tracks
    val BarTrack        = Color(0xFF1E2638)
    val BarCpu          = Color(0xFF3B82F6)   // blue
    val BarMemory       = Color(0xFF8B5CF6)   // violet

    // Pill badge backgrounds
    val PillBlue        = Color(0xFF1D3461)
    val PillGreen       = Color(0xFF064E3B)
    val PillRed         = Color(0xFF7F1D1D)
    val PillAmber       = Color(0xFF78350F)
    val PillGray        = Color(0xFF1E2638)

    // Surface overlays / dividers
    val Divider         = Color(0xFF1E2638)
    val InputBackground = Color(0xFF0F1520)
    val ScrimDark       = Color(0xCC0B0E14)
}

// ─── Material3 Color Scheme mapping ──────────────────────────────────────────

val NookColorScheme = darkColorScheme(
    primary          = NookColors.AccentBlue,
    onPrimary        = Color.White,
    primaryContainer = NookColors.AccentBlueDim,
    secondary        = NookColors.StatusOnline,
    onSecondary      = Color.White,
    background       = NookColors.AppBackground,
    onBackground     = NookColors.TextPrimary,
    surface          = NookColors.CardSurface,
    onSurface        = NookColors.TextPrimary,
    surfaceVariant   = NookColors.CardBorder,
    onSurfaceVariant = NookColors.TextSecondary,
    error            = NookColors.StatusOffline,
    onError          = Color.White,
    outline          = NookColors.CardBorder
)

// ─── Typography ───────────────────────────────────────────────────────────────

// JetBrains Mono is loaded from the system in this project.
// To bundle it: add the .ttf files under res/font/ and reference via Font(R.font.jetbrains_mono_*)
val JetBrainsMonoFamily = FontFamily.Monospace

val NookTypography = Typography(
    // Large display — header greeting
    displayLarge = TextStyle(
        fontFamily  = FontFamily.Default,
        fontWeight  = FontWeight.Bold,
        fontSize    = 26.sp,
        lineHeight  = 32.sp,
        letterSpacing = (-0.5).sp,
        color       = NookColors.TextPrimary
    ),
    // Section titles
    headlineMedium = TextStyle(
        fontFamily  = FontFamily.Default,
        fontWeight  = FontWeight.SemiBold,
        fontSize    = 18.sp,
        lineHeight  = 24.sp,
        color       = NookColors.TextPrimary
    ),
    // Card server name
    titleMedium = TextStyle(
        fontFamily  = FontFamily.Default,
        fontWeight  = FontWeight.SemiBold,
        fontSize    = 15.sp,
        lineHeight  = 20.sp,
        letterSpacing = 0.sp,
        color       = NookColors.TextPrimary
    ),
    // Body / description text
    bodyMedium = TextStyle(
        fontFamily  = FontFamily.Default,
        fontWeight  = FontWeight.Normal,
        fontSize    = 13.sp,
        lineHeight  = 18.sp,
        color       = NookColors.TextSecondary
    ),
    bodySmall = TextStyle(
        fontFamily  = FontFamily.Default,
        fontWeight  = FontWeight.Normal,
        fontSize    = 11.sp,
        lineHeight  = 16.sp,
        color       = NookColors.TextMuted
    ),
    // Pill badges, stat labels
    labelSmall = TextStyle(
        fontFamily  = FontFamily.Default,
        fontWeight  = FontWeight.Medium,
        fontSize    = 10.sp,
        lineHeight  = 14.sp,
        letterSpacing = 0.2.sp,
        color       = NookColors.TextSecondary
    ),
    // Terminal / console font
    labelMedium = TextStyle(
        fontFamily  = JetBrainsMonoFamily,
        fontWeight  = FontWeight.Normal,
        fontSize    = 12.sp,
        lineHeight  = 18.sp,
        color       = NookColors.TextPrimary
    )
)

// ─── Shapes ───────────────────────────────────────────────────────────────────

object NookShapes {
    val Card     = RoundedCornerShape(16.dp)
    val Pill     = RoundedCornerShape(50)
    val Button   = RoundedCornerShape(12.dp)
    val Input    = RoundedCornerShape(10.dp)
    val Small    = RoundedCornerShape(8.dp)
    val Dialog   = RoundedCornerShape(20.dp)
}

// ─── Spacing ─────────────────────────────────────────────────────────────────

object NookSpacing {
    val xs: Dp  = 4.dp
    val sm: Dp  = 8.dp
    val md: Dp  = 12.dp
    val lg: Dp  = 16.dp
    val xl: Dp  = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
}

// ─── Theme entry point ────────────────────────────────────────────────────────

@Composable
fun NookTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NookColorScheme,
        typography  = NookTypography,
        content     = content
    )
}

// ─── Status helpers ───────────────────────────────────────────────────────────

fun serverStatusColor(status: String): Color = when (status.lowercase()) {
    "running"  -> NookColors.StatusOnline
    "starting" -> NookColors.StatusStarting
    "stopping" -> NookColors.StatusStopping
    else       -> NookColors.StatusOffline
}

fun serverStatusLabel(status: String): String = when (status.lowercase()) {
    "running"  -> "Online"
    "starting" -> "Starting"
    "stopping" -> "Stopping"
    "offline"  -> "Offline"
    else       -> status.replaceFirstChar { it.uppercaseChar() }
}

fun serverStatusPillBg(status: String): Color = when (status.lowercase()) {
    "running"  -> NookColors.PillGreen
    "starting" -> NookColors.PillAmber
    "stopping" -> Color(0xFF7C2D12)
    else       -> NookColors.PillRed
}

/** Format bytes to a human-readable string, e.g. 1.8 GB or 512 MB */
fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.0f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }
}

/** MB limit (from panel limits) to human readable */
fun formatMbLimit(mb: Long): String = when {
    mb >= 1_024L -> "%.1f GB".format(mb / 1_024.0)
    else         -> "${mb} MB"
}
