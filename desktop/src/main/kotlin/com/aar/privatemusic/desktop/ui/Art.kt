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
        // Si ya está cargada, hay que ADOPTARLA, no sólo saltarse la carga.
        // La misma carátula la piden a la vez la fila, la barra y el panel: el
        // que llegaba segundo veía la clave puesta, se iba, y se quedaba sin
        // imagen para siempre — una nota gris junto a una carátula buena.
        if (cache.containsKey(path)) {
            image = cache[path]
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { File(path).inputStream().use(::loadImageBitmap) }.getOrNull()
        }
        cache[path] = loaded
        image = loaded
    }
    return image
}

/**
 * Miniaturas de los resultados de búsqueda: viven en YouTube o Deezer, no en
 * disco. Se guardan en `art/thumbs` para no volver a pedirlas en cada búsqueda,
 * y el nombre es el hash de la URL porque las de YouTube traen parámetros.
 */
@Composable
fun rememberRemoteArt(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    var image by remember(url) { mutableStateOf(cache[url]) }
    LaunchedEffect(url) {
        if (cache.containsKey(url)) {
            image = cache[url]
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val name = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                val file = File(thumbDir, "$name.jpg")
                if (!file.exists() || file.length() == 0L) {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                    conn.disconnect()
                }
                file.inputStream().use(::loadImageBitmap)
            }.getOrNull()
        }
        cache[url] = loaded
        image = loaded
    }
    return image
}

private val thumbDir: File by lazy {
    File(com.aar.privatemusic.desktop.DesktopStorage.artDir, "thumbs").apply { mkdirs() }
}
