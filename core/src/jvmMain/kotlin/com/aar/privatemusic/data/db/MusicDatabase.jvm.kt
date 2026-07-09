package com.aar.privatemusic.data.db

import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import java.io.File

@Volatile private var instance: MusicDatabase? = null

/** Abre (o crea) `music.db` dentro del directorio de datos del escritorio. */
fun openMusicDatabase(dataDir: File): MusicDatabase =
    instance ?: synchronized(MusicDatabase::class) {
        instance ?: run {
            dataDir.mkdirs()
            Room.databaseBuilder<MusicDatabase>(
                name = File(dataDir, "music.db").absolutePath,
            ).buildMusicDatabase(Dispatchers.IO).also { instance = it }
        }
    }
