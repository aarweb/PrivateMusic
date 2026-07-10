package com.aar.privatemusic.player

/**
 * La aritmética de la cola que es nuestra, no de ExoPlayer.
 *
 * `PlayerController` delega en ExoPlayer mover, quitar e insertar, y ésa es su
 * aritmética, no la nuestra: probarla aquí sería probar una copia del contrato,
 * no el código que corre. Lo que sí decidimos nosotros vive en este fichero, y
 * por eso se puede probar en la JVM, sin `Context` ni `MediaController`.
 */
object QueueLogic {

    /** A partir de aquí una pista es larga y recuerda por dónde iba. */
    const val RESUME_MIN_DURATION_SEC = 15 * 60

    /**
     * Dónde arranca una pista al reproducirla.
     *
     * Sólo las pistas largas (mixes, sets) reanudan. Por debajo de 30 s no vale
     * la pena, y a menos de 10 s del final reanudar es acabarla nada más
     * empezarla.
     */
    fun resumeStartMs(durationSec: Int, savedMs: Long): Long {
        if (durationSec <= RESUME_MIN_DURATION_SEC) return 0L
        val nearTheEnd = (durationSec - 10) * 1000L
        return if (savedMs > 30_000L && savedMs < nearTheEnd) savedMs else 0L
    }

    /**
     * Al arrancar en frío se recupera la cola guardada, pero entre medias el
     * usuario ha podido borrar canciones de la biblioteca. [present] dice, para
     * cada id guardado y en el mismo orden, si esa canción sigue existiendo.
     *
     * Devuelve el índice que le corresponde a [savedIndex] en la lista ya
     * filtrada. No se busca por id: la misma canción puede estar repetida en la
     * cola, y entonces se elegiría la copia equivocada. Se cuenta cuántas
     * sobreviven por delante, que es exacto también con duplicados.
     *
     * Si la canción que sonaba ya no está, el índice cae en la siguiente que
     * quede (o en la última, si no queda ninguna detrás).
     */
    fun restoreIndex(present: List<Boolean>, savedIndex: Int): Int {
        val survivors = present.count { it }
        if (survivors == 0) return 0
        val before = present.take(savedIndex.coerceIn(0, present.size)).count { it }
        return before.coerceAtMost(survivors - 1)
    }

    /**
     * La posición guardada pertenece a una pista concreta. Si esa pista ya no
     * está, heredarla haría que la siguiente empezara por la mitad.
     */
    fun restorePositionMs(present: List<Boolean>, savedIndex: Int, savedPositionMs: Long): Long =
        if (present.getOrNull(savedIndex) == true) savedPositionMs else 0L
}
