package com.aar.privatemusic.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Carátulas desde disco. Sin biblioteca de imágenes: son ficheros locales, no
 * hacen falta ni red ni caché en disco. Sólo un mapa en memoria para no leer el
 * mismo JPEG en cada recomposición de la lista.
 */
private val cache = java.util.Collections.synchronizedMap(HashMap<String, ImageBitmap?>())

@Composable
fun rememberArt(path: String?): ImageBitmap? {
    if (path == null) return null
    var image by remember(path) { mutableStateOf(cache[path]) }
    LaunchedEffect(path) {
        if (cache.containsKey(path)) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching { File(path).inputStream().use(::loadImageBitmap) }.getOrNull()
        }
        cache[path] = loaded
        image = loaded
    }
    return image
}
