package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Los mismos colores, radios y pesos que la app de Android. El escritorio no
 * tiene color dinámico del sistema, así que usa la paleta de reserva.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFF80CBC4),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Los titulares pesan y los metadatos no. */
private val AppTypography = Typography().let { d ->
    d.copy(
        headlineLarge = d.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = d.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = d.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = d.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = d.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun PrivateMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
