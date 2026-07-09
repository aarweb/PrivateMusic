package com.aar.privatemusic.downloader

import java.io.File

/**
 * Lo poco que los descargadores necesitaban de Android: dónde escribir, dónde
 * registrar errores y cómo avisar al usuario. Con esto detrás de una interfaz,
 * el mismo descargador sirve para el móvil y para el escritorio.
 */
interface DownloaderEnv {
    /** Carpeta donde viven las canciones y sus carátulas. Se crea si no existe. */
    val musicDir: File

    /** Equivalente a `Log.e`/`Log.w`: diagnóstico, nunca visible para el usuario. */
    fun log(tag: String, message: String, error: Throwable? = null) {}

    /** Mensaje breve para el usuario (en Android, un Toast). */
    fun notify(message: String) {}
}

/**
 * La cuenta de Deezer vive en las preferencias de cada plataforma. El descargador
 * sólo necesita leer el ARL y devolver el plan cuando lo revalida.
 */
interface DeezerAccount {
    /** Cookie ARL de la sesión del usuario. Vacía = no ha iniciado sesión. */
    fun readArl(): String

    /** Se llama tras revalidar la sesión, por si el plan cambió (p.ej. renovó HiFi). */
    fun saveSession(arl: String, info: DeezerUserInfo) {}
}
