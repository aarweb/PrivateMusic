package com.aar.privatemusic.data.db

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers

/**
 * Misma ruta que usaba `MusicDatabase.get(context)` en `:app` ("music.db"),
 * para que al migrar el módulo la biblioteca que ya está en el móvil se abra
 * sin tocar nada.
 */
fun openMusicDatabase(context: Context): MusicDatabase =
    Room.databaseBuilder<MusicDatabase>(
        context = context.applicationContext,
        name = context.getDatabasePath("music.db").absolutePath,
    ).buildMusicDatabase(Dispatchers.IO)
