package com.aar.privatemusic.lyrics

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Caché local de traducciones de letra.
 *
 * El nombre no contiene el id original de la canción: sólo su SHA-256. Así un
 * id importado con `../` nunca puede escapar de [musicDir]. El hash del texto
 * fuente evita enseñar una traducción vieja si cambia la letra o se sincroniza.
 */
class LyricsTranslationCache(private val musicDir: File) {

    /**
     * Huella opaca y de sólo lectura de la caché. No expone una ruta derivada
     * del id y permite detectar sustituciones sin abrir el contenido.
     */
    fun cacheStamp(songId: String, targetLanguage: String): Long {
        val file = cacheFileFor(songId, targetLanguage)
        if (!file.isFile) return 0L
        var stamp = 17L
        stamp = stamp * 31 + file.lastModified()
        stamp = stamp * 31 + file.length()
        return if (stamp == 0L) 1L else stamp
    }

    fun read(songId: String, targetLanguage: String, source: Lyrics): Lyrics? {
        val file = cacheFileFor(songId, targetLanguage)
        if (!file.isFile) return null
        return runCatching {
            val rows = file.readLines(StandardCharsets.UTF_8)
            if (rows.size < HEADER_LINES) return null
            if (rows[0] != "$MAGIC\t$VERSION") return null
            if (decodeField(rows[1], "target") != targetLanguage) return null
            if (field(rows[2], "hash") != sourceHash(source)) return null
            val expectedCount = field(rows[3], "count").toIntOrNull() ?: return null
            if (expectedCount != source.lines.size || rows.size != HEADER_LINES + expectedCount) {
                return null
            }
            val translations = rows.drop(HEADER_LINES).map { decodeField(it, "line") }
            source.copy(
                lines = source.lines.zip(translations).map { (original, translated) ->
                    // Traducir rompe cualquier correspondencia palabra/tiempo.
                    original.copy(text = translated, words = emptyList())
                },
            )
        }.getOrNull()
    }

    /** Escribe sólo una traducción completa; una operación fallida conserva la anterior. */
    fun write(songId: String, targetLanguage: String, source: Lyrics, translated: Lyrics) {
        require(source.lines.size == translated.lines.size) {
            "La traducción debe conservar el número de líneas"
        }
        musicDir.mkdirs()
        val destination = cacheFileFor(songId, targetLanguage)
        val temp = File(musicDir, "${destination.name}.tmp-${UUID.randomUUID()}")
        try {
            temp.bufferedWriter(StandardCharsets.UTF_8).use { out ->
                out.append(MAGIC).append('\t').append(VERSION.toString()).append('\n')
                out.append("target\t").append(encode(targetLanguage)).append('\n')
                out.append("hash\t").append(sourceHash(source)).append('\n')
                out.append("count\t").append(source.lines.size.toString()).append('\n')
                translated.lines.forEach { line ->
                    out.append("line\t").append(encode(line.text)).append('\n')
                }
            }
            try {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Android/Linux usa rename(2); este fallback sigue sustituyendo
                // el fichero de una vez en sistemas que no anuncian ATOMIC_MOVE.
                if (!temp.renameTo(destination)) {
                    Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally {
            temp.delete()
        }
    }

    /** Borra todos los destinos y temporales asociados a una canción. */
    fun invalidate(songId: String) {
        val prefix = "$FILE_PREFIX${sha256(songId)}_"
        musicDir.listFiles()
            ?.filter { it.name.startsWith(prefix) }
            ?.forEach { runCatching { it.delete() } }
    }

    internal fun cacheFileFor(songId: String, targetLanguage: String): File {
        val safeTarget = targetLanguage.lowercase().filter { it in 'a'..'z' || it == '-' }
            .ifEmpty { "unknown" }
        return File(musicDir, "$FILE_PREFIX${sha256(songId)}_$safeTarget.$EXTENSION")
    }

    private fun sourceHash(source: Lyrics): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.lines.forEach { line ->
            val bytes = line.text.toByteArray(StandardCharsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().toHex()
    }

    private fun field(row: String, name: String): String {
        val prefix = "$name\t"
        require(row.startsWith(prefix))
        return row.substring(prefix.length)
    }

    private fun decodeField(row: String, name: String): String =
        String(Base64.getDecoder().decode(field(row, name)), StandardCharsets.UTF_8)

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val EXTENSION = "pmlt"
        private const val MAGIC = "PRIVATE_MUSIC_LYRICS_TRANSLATION"
        private const val VERSION = 1
        private const val HEADER_LINES = 4
        private const val FILE_PREFIX = ".lyrics_translation_"
    }
}
