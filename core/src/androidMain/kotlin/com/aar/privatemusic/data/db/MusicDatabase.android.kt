package com.aar.privatemusic.data.db

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers

@Volatile private var instance: MusicDatabase? = null

/**
 * Una sola base de datos por proceso. Abrirla dos veces sobre el mismo fichero
 * con WAL activo es pedir corrupción, y aquí la llaman siete sitios (la app, el
 * servicio de reproducción, el worker, las copias de seguridad…).
 *
 * Misma ruta que usaba `MusicDatabase.get(context)`: la biblioteca que ya está
 * en el móvil se abre sin migrar de sitio.
 */
fun openMusicDatabase(context: Context): MusicDatabase =
    instance ?: synchronized(MusicDatabase::class) {
        instance ?: Room.databaseBuilder<MusicDatabase>(
            context = context.applicationContext,
            name = context.getDatabasePath("music.db").absolutePath,
        ).buildMusicDatabase(Dispatchers.IO).also { instance = it }
    }
