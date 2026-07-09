package com.aar.privatemusic.desktop.ui

import androidx.compose.ui.input.key.KeyEvent

/**
 * Los atajos se registran en la ventana, no en un `Modifier`.
 *
 * Un `Modifier.onKeyEvent` sólo recibe teclas si su nodo está en la cadena del
 * foco, y en cuanto el usuario pulsa un botón el foco se va a ese botón: los
 * atajos dejaban de responder. `Window(onKeyEvent = …)` los recibe siempre, y
 * al no ser `onPreviewKeyEvent` llega *después* del componente enfocado, así
 * que escribir un espacio en el buscador no pausa la música.
 *
 * Este puente existe porque la ventana se crea en `main()` y el reproductor
 * vive dentro de `App()`.
 */
class KeyShortcuts {
    var handler: ((KeyEvent) -> Boolean)? = null

    fun handle(event: KeyEvent): Boolean = handler?.invoke(event) ?: false
}
