package com.dualframe.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Pretendard font — uses system SansSerif as base.
// When pretendard_regular.otf, pretendard_semibold.otf, pretendard_bold.otf
// are placed in app/src/main/res/font/, uncomment the Font() lines below
// and add: import androidx.compose.ui.text.font.Font
//          import com.sunnlab.dualframe.R
//
// val PretendardFont = FontFamily(
//     Font(R.font.pretendard_regular, FontWeight.Normal),
//     Font(R.font.pretendard_semibold, FontWeight.SemiBold),
//     Font(R.font.pretendard_bold, FontWeight.Bold),
// )

val PretendardFont = FontFamily.SansSerif

val DualFrameTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = (-0.02).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.02).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        letterSpacing = (-0.01).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = (-0.01).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = (-0.01).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = (-0.02).sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = (-0.01).sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.sp,
    ),
)
