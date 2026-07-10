package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap

/**
 * El fondo que cambia con cada canción: dos o tres colores sacados de la
 * carátula, muy oscurecidos y fundidos. Es la seña de Plexamp y lo que hace que
 * una ventana negra deje de parecer un listado.
 *
 * En la JVM no existe `androidx.palette`, así que se muestrea a mano: se recorre
 * una rejilla de la imagen, se agrupan los píxeles en cubos gruesos de color y
 * gana el cubo más poblado. Se descartan los píxeles casi negros y casi blancos
 * (una portada con fondo blanco daría un gris sucio) y los muy poco saturados.
 */
@Composable
fun UltraBlurBackground(artPath: String?, modifier: Modifier = Modifier) {
    val image = rememberArt(artPath)
    val colors = remember(artPath, image) { image?.let(::dominantColors) ?: emptyList() }
    val surface = androidx.compose.material3.MaterialTheme.colorScheme.surface

    if (colors.isEmpty()) {
        Box(modifier.fillMaxSize().background(surface))
        return
    }

    val top = colors[0].darken()
    val bottom = colors.getOrElse(1) { colors[0] }.darken()
    Box(
        modifier.fillMaxSize().background(
            // Un degradado lineal hace de base; encima, un halo radial del color
            // principal. Nada de `blur()`: desenfocar un degradado es gastar GPU
            // en suavizar algo que ya es suave.
            Brush.linearGradient(listOf(top, bottom)),
        ),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(colors[0].copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(0.5f, 0.25f),
                    radius = 900f,
                ),
            ),
        )
        // El velo final garantiza que el texto blanco encima siempre se lea, sea
        // cual sea la portada. Sin él, una carátula clara arruina el panel.
        Box(Modifier.fillMaxSize().background(surface.copy(alpha = 0.55f)))
    }
}

/** Hacia el negro, que es donde vive nuestro tema. */
private fun Color.darken(factor: Float = 0.42f): Color =
    Color(red * factor, green * factor, blue * factor, 1f)

/** Los colores más presentes de la imagen, del más frecuente al menos. */
internal fun dominantColors(image: ImageBitmap, count: Int = 3): List<Color> {
    val pixels = runCatching { image.toPixelMap() }.getOrNull() ?: return emptyList()
    val buckets = HashMap<Int, Int>()
    // Una rejilla de ~64x64 muestras basta y cuesta nada: la portada es 500x500
    // y sólo queremos su paleta, no su detalle.
    val stepX = (pixels.width / 64).coerceAtLeast(1)
    val stepY = (pixels.height / 64).coerceAtLeast(1)
    var x = 0
    while (x < pixels.width) {
        var y = 0
        while (y < pixels.height) {
            val color = pixels[x, y]
            val max = maxOf(color.red, color.green, color.blue)
            val min = minOf(color.red, color.green, color.blue)
            val saturation = if (max == 0f) 0f else (max - min) / max
            if (max in 0.12f..0.95f && saturation > 0.15f) {
                // Ocho niveles por canal: agrupa tonos vecinos en el mismo cubo.
                val key = (color.red * 7).toInt() * 64 +
                    (color.green * 7).toInt() * 8 +
                    (color.blue * 7).toInt()
                buckets[key] = (buckets[key] ?: 0) + 1
            }
            y += stepY
        }
        x += stepX
    }
    if (buckets.isEmpty()) return emptyList()
    return buckets.entries.sortedByDescending { it.value }.take(count).map { (key, _) ->
        val r = key / 64
        val g = (key / 8) % 8
        val b = key % 8
        Color(r / 7f, g / 7f, b / 7f)
    }
}
