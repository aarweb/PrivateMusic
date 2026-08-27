package com.aar.privatemusic.player

/** Política pura para mantener simétricas las salidas de los reproductores locales. */
object AudioRoutePlan {

    data class Input(
        val localActive: Boolean,
        val hasMain: Boolean,
        val hasTail: Boolean,
        val mainAlreadyOnTarget: Boolean,
        val tailAlreadyOnTarget: Boolean,
        val routeChanged: Boolean,
        val forceApply: Boolean = false,
    )

    data class Output(
        val applyMain: Boolean,
        val applyTail: Boolean,
        val invalidateCrossfade: Boolean,
    )

    /**
     * También alinea los reproductores cuando Cast está activo. [Input.forceApply] obliga a
     * reescribir físicamente una ruta cacheada (por ejemplo, tras suspender Cast/A2DP), pero no
     * constituye por sí solo un cambio de ruta. [Input.localActive] es deliberadamente
     * informativo: enrutar nunca implica iniciar reproducción.
     */
    fun evaluate(input: Input): Output = Output(
        applyMain = input.hasMain && (input.forceApply || !input.mainAlreadyOnTarget),
        applyTail = input.hasTail && (input.forceApply || !input.tailAlreadyOnTarget),
        invalidateCrossfade = input.routeChanged && (input.hasMain || input.hasTail),
    )
}
