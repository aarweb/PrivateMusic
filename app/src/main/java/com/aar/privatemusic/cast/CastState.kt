package com.aar.privatemusic.cast

import kotlinx.coroutines.flow.MutableStateFlow

/** Where the session audio is going: the cast device name, or null = local. */
object CastState {
    val castDeviceName = MutableStateFlow<String?>(null)

    /**
     * `http://ip:puerto` del servidor que la tele está leyendo, o null si suena
     * en el móvil. Lo consulta el servicio cada vez que la app pone algo a
     * sonar: mientras esto no sea null, las canciones viajan como URL y no como
     * fichero local, que es lo único que la tele sabe abrir.
     */
    @Volatile
    var baseUrl: String? = null

    /**
     * La carátula que se le manda a la tele es una URL, pero la app la quiere
     * pintar desde el disco. La ruta local viaja aquí, en los extras, para no
     * volver a la base de datos sólo para dibujarla.
     */
    const val EXTRA_LOCAL_ART = "localArtPath"
}
