package com.aar.privatemusic.cast

import kotlinx.coroutines.flow.MutableStateFlow

/** Where the session audio is going: the cast device name, or null = local. */
object CastState {
    val castDeviceName = MutableStateFlow<String?>(null)
}
