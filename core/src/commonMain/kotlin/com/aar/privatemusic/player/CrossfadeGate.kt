package com.aar.privatemusic.player

/**
 * Las guardas que deciden si una transición concreta puede llevar fundido.
 *
 * Viven aquí, fuera del motor, porque son la parte que más ha roto: cada vez
 * que se ha tocado, algún caso ("esta canción nunca funde", "con el fundido
 * corto no funde") se ha escapado hasta el móvil. Como funciones puras se
 * pueden fijar con pruebas; el motor de `PlaybackService` sólo las llama.
 *
 * Ninguna decide *cuándo* abrir la ventana ni cómo mezclar: sólo si esta pareja
 * de canciones es candidata.
 */
object CrossfadeGate {

    /**
     * Duración utilizable de la pista que suena.
     *
     * Algunos opus/webm bajados de YouTube no traen duración en el contenedor
     * ([playerDurationMs] llega como [timeUnset]) y sin ella el fundido no se
     * armaba nunca para esas canciones. La duración real de la biblioteca viaja
     * en el `MediaItem`, así que sirve de respaldo.
     *
     * @return la duración en ms, o [timeUnset] si no hay ninguna fiable.
     */
    fun effectiveDurationMs(
        playerDurationMs: Long,
        metadataDurationMs: Long?,
        timeUnset: Long,
    ): Long = when {
        playerDurationMs != timeUnset -> playerDurationMs
        metadataDurationMs != null && metadataDurationMs > 0L -> metadataDurationMs
        else -> timeUnset
    }

    /**
     * Si el estado del reproductor permite fundir esta transición.
     *
     * Pide que la pista dure más del doble del fundido: por debajo de eso el
     * solape se comería la canción entera. Y descarta el modo "repetir una",
     * donde la siguiente pista es la misma.
     */
    fun windowOpen(
        crossfadeMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        hasNext: Boolean,
        repeatOne: Boolean,
        timeUnset: Long,
    ): Boolean =
        crossfadeMs > 0 &&
            isPlaying &&
            durationMs != timeUnset &&
            durationMs > crossfadeMs * 2 &&
            hasNext &&
            !repeatOne

    /**
     * Si la canción que suena admite fundido.
     *
     * Las preescuchas (`preview:`) se reproducen sueltas en streaming y no
     * deben arrastrar a la siguiente. [skipXfForId] marca la que ya falló el
     * relevo en esta pasada: se salta su fundido y se va a corte limpio, que es
     * mejor que un salto de audio.
     */
    fun trackEligible(
        hasUri: Boolean,
        mediaId: String?,
        skipXfForId: String?,
    ): Boolean =
        hasUri &&
            mediaId != null &&
            !mediaId.startsWith(PREVIEW_PREFIX) &&
            mediaId != skipXfForId

    /**
     * La marca de "esta ya falló" sólo vale para la canción que la provocó: al
     * cambiar de pista se olvida, para que la siguiente vuelva a intentarlo.
     */
    fun keepSkipMark(skipXfForId: String?, currentId: String?): String? =
        if (skipXfForId != null && skipXfForId != currentId) null else skipXfForId

    const val PREVIEW_PREFIX = "preview:"
}
