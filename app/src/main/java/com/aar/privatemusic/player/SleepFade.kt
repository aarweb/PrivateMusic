package com.aar.privatemusic.player

/**
 * Nivel de atenuación del temporizador de apagado, 0..1 (1 = sin atenuar).
 *
 * Antes el fade de los últimos segundos se aplicaba escribiendo
 * `controller.volume` desde [PlayerController], pero el bucle de volumen de
 * [PlaybackService] reescribe `player.volume` cada tic cuando hay crossfade o
 * normalización: los dos se peleaban por el mismo ExoPlayer y el fade rebotaba
 * en vez de bajar suave. Ahora el fade es un multiplicador global que SÓLO
 * consume el bucle de volumen (lo aplica a `player` y a `tailPlayer`), de modo
 * que también atenúa el solape del crossfade. Un único escritor del volumen.
 *
 * Vive como singleton porque el servicio corre en el mismo proceso que la app
 * (sin `android:process`), igual que [EqHolder] y CastState.
 */
object SleepFade {
    @Volatile
    var level: Float = 1f
}
