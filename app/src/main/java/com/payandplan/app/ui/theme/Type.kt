package com.payandplan.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.payandplan.app.R

/** Big chunky poster lettering. */
val PosterFont = FontFamily(Font(R.font.luckiestguy_regular, FontWeight.Normal))

/** Friendly hand-drawn body copy. */
val ComicFont = FontFamily(
    Font(R.font.comicneue_regular, FontWeight.Normal),
    Font(R.font.comicneue_bold, FontWeight.Bold)
)

val ComicTypography = Typography(
    displayLarge = TextStyle(fontFamily = PosterFont, fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = 1.sp),
    displayMedium = TextStyle(fontFamily = PosterFont, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = 1.sp),
    displaySmall = TextStyle(fontFamily = PosterFont, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = 0.5.sp),
    headlineMedium = TextStyle(fontFamily = PosterFont, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.5.sp),
    headlineSmall = TextStyle(fontFamily = PosterFont, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontFamily = ComicFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = ComicFont, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = ComicFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = ComicFont, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = ComicFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = ComicFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = ComicFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.5.sp),
    labelMedium = TextStyle(fontFamily = ComicFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = ComicFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)
)
