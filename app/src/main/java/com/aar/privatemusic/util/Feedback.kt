package com.aar.privatemusic.util

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * App-wide one-shot feedback messages, rendered as snackbars by MainActivity.
 * Fire-and-forget from any screen: Feedback.show("Añadida a la cola").
 */
object Feedback {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    fun show(message: String) {
        messages.tryEmit(message)
    }
}
