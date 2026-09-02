package com.payandplan.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---- the comic palette -------------------------------------------------

val Ink = Color(0xFF17161A)
val Cream = Color(0xFFFFF6E5)
val Paper = Color(0xFFFFFDF7)
val Yellow = Color(0xFFFFD93D)
val Coral = Color(0xFFFF6B6B)
val Mint = Color(0xFF6BCB77)
val Sky = Color(0xFF4D96FF)
val Grape = Color(0xFFB983FF)
val Bubblegum = Color(0xFFFF9CEE)
val Tangerine = Color(0xFFFF9F45)
val Aqua = Color(0xFF4ECDC4)
val Shadow = Color(0xFF17161A)

/** Palette used to tag payments. Index is stored in the database. */
val StickerColors = listOf(
    Coral, Yellow, Mint, Sky, Grape, Tangerine, Aqua, Bubblegum
)

fun stickerColor(index: Int): Color = StickerColors[((index % StickerColors.size) + StickerColors.size) % StickerColors.size]

private val ComicScheme = lightColorScheme(
    primary = Coral,
    onPrimary = Ink,
    secondary = Sky,
    onSecondary = Ink,
    tertiary = Mint,
    onTertiary = Ink,
    background = Cream,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFFFEFCB),
    onSurfaceVariant = Ink,
    error = Color(0xFFE23E3E),
    onError = Color.White,
    outline = Ink
)

@Composable
fun PayPlanTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // the comic look is intentionally always bright
    MaterialTheme(
        colorScheme = ComicScheme,
        typography = ComicTypography,
        content = content
    )
}
