package com.aar.privatemusic.data.db

import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Abre (o crea) `music.db` dentro del directorio de datos del escritorio. */
fun openMusicDatabase(dataDir: File): MusicDatabase {
    dataDir.mkdirs()
    return Room.databaseBuilder<MusicDatabase>(
        name = File(dataDir, "music.db").absolutePath,
    ).buildMusicDatabase(Dispatchers.IO)
}
