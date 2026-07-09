package com.aar.privatemusic.util

import java.io.File

/**
 * Elige la portada de un álbum descargado.
 *
 * Un torrent de música rara vez trae una sola imagen: suele venir Front, Back,
 * Disc y varias páginas del libreto, muchas veces dentro de una subcarpeta
 * ("Artwork", "Scans"). Y a veces la imagen que anuncia el .torrent nunca llega
 * a descargarse, así que fiarse del nombre sin comprobar el disco deja la
 * carátula apuntando a un fichero que no existe.
 */
object CoverFinder {
    private val imageExts = setOf("jpg", "jpeg", "png", "webp")
    private val preferred = setOf("cover", "folder", "front", "albumart", "album", "artwork")
    private val booklet = setOf("inside", "booklet", "insert", "scan", "scans")
    private val physical = setOf("disc", "disk", "cd", "tray", "matrix", "obi", "label")

    /** Cuanto más bajo, mejor portada. La contraportada es el último recurso. */
    private fun rank(file: File): Int {
        // "Booklet-1" y "Booklet 2" son la misma clase de imagen que "Booklet".
        val base = file.nameWithoutExtension.lowercase()
            .substringBefore('-').substringBefore('_')
            .trim().trimEnd { it.isDigit() || it == ' ' }
        return when (base) {
            in preferred -> 0
            in booklet -> 2
            in physical -> 3
            "back" -> 4
            else -> 1
        }
    }

    private fun isImage(f: File) = f.isFile && f.extension.lowercase() in imageExts

    private fun depth(file: File, root: File) =
        file.toRelativeString(root).count { it == File.separatorChar }

    /**
     * Busca la mejor imagen dentro de [root], bajando hasta [maxDepth] niveles.
     * A igual categoría gana la menos anidada y, entre esas, la primera por
     * nombre: "Booklet-1" es la portada del libreto, "Booklet-7" una página suelta.
     */
    fun findIn(root: File, maxDepth: Int = 3): File? {
        if (!root.isDirectory) return null
        val order = compareBy<File>({ rank(it) }, { depth(it, root) }, { it.name.lowercase() })
        return root.walkTopDown().maxDepth(maxDepth).filter { isImage(it) }.minWithOrNull(order)
    }

    /**
     * [candidates] son los ficheros que anuncia el torrent. Puede que alguno no
     * exista en disco, de ahí el `exists()`: el nombre no basta.
     */
    fun pick(candidates: List<File>, root: File): File? {
        val onDisk = candidates.filter { isImage(it) && it.exists() }
        onDisk.filter { rank(it) == 0 }.minByOrNull { it.name.lowercase() }?.let { return it }
        return findIn(root) ?: onDisk.minWithOrNull(compareBy({ rank(it) }, { it.name.lowercase() }))
    }
}
