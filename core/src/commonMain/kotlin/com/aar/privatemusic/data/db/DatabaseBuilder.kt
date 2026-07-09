package com.aar.privatemusic.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.CoroutineContext

/**
 * El único sitio donde se decide cómo se abre la biblioteca. Cada plataforma
 * construye su `Builder` (Android necesita un Context; el escritorio, sólo una
 * ruta) y termina aquí, para que las migraciones y el driver sean los mismos.
 *
 * `BundledSQLiteDriver` lleva su propio SQLite dentro: el PC no depende del que
 * traiga el sistema, y el móvil no depende de la versión de Android.
 */
fun RoomDatabase.Builder<MusicDatabase>.buildMusicDatabase(
    queryContext: CoroutineContext,
): MusicDatabase =
    addMigrations(*MUSIC_MIGRATIONS)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryContext)
        .build()
