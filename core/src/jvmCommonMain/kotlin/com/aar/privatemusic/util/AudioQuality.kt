package com.aar.privatemusic.util

data class AudioQuality(val codec: String, val bitrateKbps: Int?, val sampleRateHz: Int?)

/**
 * Propiedades reales del fichero de audio, para la etiqueta de calidad.
 *
 * Cada plataforma lo lee con lo que tiene: Android con `MediaExtractor`, el
 * escritorio con lo que dice el contenedor. No es lo mismo — el escritorio no
 * conoce la frecuencia de muestreo sin abrir el códec — y por eso la firma
 * devuelve nulos en vez de mentir.
 */
expect fun readAudioQuality(path: String, durationSec: Int): AudioQuality?
