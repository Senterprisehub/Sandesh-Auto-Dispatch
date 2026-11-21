package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Curated Emerald & Cool Slate fallback palette for Android 7-11 (Android 12+ uses Material You Dynamic Color)
val EmeraldPrimaryLight = Color(0xFF006C4C)
val OnEmeraldPrimaryLight = Color(0xFFFFFFFF)
val EmeraldContainerLight = Color(0xFF89F8C7)
val OnEmeraldContainerLight = Color(0xFF002114)

val SlateSecondaryLight = Color(0xFF4D6357)
val OnSlateSecondaryLight = Color(0xFFFFFFFF)
val SlateContainerLight = Color(0xFFCFE9D9)
val OnSlateContainerLight = Color(0xFF0A1F16)

val AmberTertiaryLight = Color(0xFF3D6373)
val OnAmberTertiaryLight = Color(0xFFFFFFFF)
val AmberContainerLight = Color(0xFFC1E8FB)
val OnAmberContainerLight = Color(0xFF001F29)

val BackgroundLight = Color(0xFFF6FBF6)
val SurfaceLight = Color(0xFFF6FBF6)
val SurfaceVariantLight = Color(0xFFDBE5DD)
val OnSurfaceLight = Color(0xFF171D1A)
val OnSurfaceVariantLight = Color(0xFF404943)

val EmeraldPrimaryDark = Color(0xFF6CDBAC)
val OnEmeraldPrimaryDark = Color(0xFF003826)
val EmeraldContainerDark = Color(0xFF005138)
val OnEmeraldContainerDark = Color(0xFF89F8C7)

val SlateSecondaryDark = Color(0xFFB3CCBE)
val OnSlateSecondaryDark = Color(0xFF1F352B)
val SlateContainerDark = Color(0xFF354B40)
val OnSlateContainerDark = Color(0xFFCFE9D9)

val AmberTertiaryDark = Color(0xFFA5CCDF)
val OnAmberTertiaryDark = Color(0xFF073544)
val AmberContainerDark = Color(0xFF244C5B)
val OnAmberContainerDark = Color(0xFFC1E8FB)

val BackgroundDark = Color(0xFF0F1512)
val SurfaceDark = Color(0xFF0F1512)
val SurfaceVariantDark = Color(0xFF404943)
val OnSurfaceDark = Color(0xFFDFE4DF)
val OnSurfaceVariantDark = Color(0xFFC0C9C1)

@Immutable
data class StatusColors(
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val infoContainer: Color,
    val onInfoContainer: Color
)

val LightStatusColors = StatusColors(
    successContainer = Color(0xFFD2F7E2),
    onSuccessContainer = Color(0xFF003822),
    warningContainer = Color(0xFFFFEBC2),
    onWarningContainer = Color(0xFF4F3400),
    infoContainer = Color(0xFFD6EEFC),
    onInfoContainer = Color(0xFF003549)
)

val DarkStatusColors = StatusColors(
    successContainer = Color(0xFF0A4D33),
    onSuccessContainer = Color(0xFFB3F8D6),
    warningContainer = Color(0xFF5C4000),
    onWarningContainer = Color(0xFFFFDF9E),
    infoContainer = Color(0xFF13485E),
    onInfoContainer = Color(0xFFBFE7FB)
)
