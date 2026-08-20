package com.aar.privatemusic.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.aar.privatemusic.BuildConfig
import com.aar.privatemusic.data.db.countLibrarySongs
import com.aar.privatemusic.data.db.openMusicDatabase
import com.aar.privatemusic.data.db.relocateLibraryRoots
import com.aar.privatemusic.data.db.snapshotTo
import com.aar.privatemusic.data.db.walCheckpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copia completa de la app para cambiar de móvil: un ZIP con la base de datos,
 * las preferencias (incluidas las sesiones de Deezer y ListenBrainz), y todos
 * los ficheros de `files/music` (audio, carátulas, letras, karaoke) y de
 * `files/torrents`. Lo que se queda fuera se puede volver a bajar (modelos de
 * IA) o no es de este móvil (música escaneada del dispositivo, que sigue en
 * `/sdcard/Music`).
 *
 * La restauración no toca la base de datos en caliente: Room la tiene abierta
 * como singleton sin `close()` y la usan el servicio de reproducción y los
 * workers. Los ficheros de audio se colocan en su sitio directamente (nadie
 * los busca hasta que la BD los nombra), pero la BD y las prefs se dejan en
 * `files/restore/` con una marca `READY`, y [applyPendingRestore] las pone en
 * su sitio en el siguiente arranque, antes de que nadie las abra. Por eso el
 * último paso es reiniciar el proceso.
 *
 * Las rutas de la BD son absolutas. Entre dos móviles con el mismo paquete y
 * el usuario principal son idénticas; si no (perfil de trabajo, segundo
 * usuario, otro applicationId), se reescriben los prefijos al restaurar.
 */
object FullBackup {
    private const val TAG = "FullBackup"
    private const val FORMAT = 1
    private const val MANIFEST = "privatemusic-backup.json"
    private const val DB_ENTRY = "db/music.db"
    private const val PREFS_PREFIX = "prefs/"
    private const val MUSIC_PREFIX = "music/"
    private const val TORRENTS_PREFIX = "torrents/"
    private const val RESTORE_DIR = "restore"
    private const val READY = "READY"

    /** Ficheros de SharedPreferences que merece la pena llevarse. */
    private val PREFS = listOf("settings", "spotify_sync", "resume_positions", "queue_state")

    /** Restos de descargas a medias: no son canciones. */
    private val SKIP_SUFFIXES = listOf(".part", ".ytdl", ".tmp", ".restoring")

    data class Progress(val phase: String, val done: Long, val total: Long, val files: Int) {
        /** 0..1, o null si no se sabe el total. */
        val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    sealed class Outcome {
        data class Exported(val files: Int, val bytes: Long) : Outcome()
        data class Restored(val files: Int, val bytes: Long, val songs: Int) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    private val _progress = MutableStateFlow<Progress?>(null)
    /** Operación en curso; null si no hay ninguna. */
    val progress: StateFlow<Progress?> = _progress

    private val _outcome = MutableStateFlow<Outcome?>(null)
    /** Resultado de la última operación, hasta que la UI lo consuma con [clearOutcome]. */
    val outcome: StateFlow<Outcome?> = _outcome

    private val running = AtomicBoolean(false)

    fun clearOutcome() { _outcome.value = null }

    private fun musicDir(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "music")
    private fun torrentsDir(context: Context) = File(context.filesDir, "torrents")
    private fun externalRoot(context: Context) = (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath
    private fun internalRoot(context: Context) = context.filesDir.absolutePath
    private fun restoreDir(context: Context) = File(context.filesDir, RESTORE_DIR)
    private fun prefsFile(context: Context, name: String) = File(context.dataDir, "shared_prefs/$name.xml")

    // ------------------------------------------------------------------ export

    /** Escribe la copia en [uri] (SAF). Va en [scope] (el de la app): salir de Ajustes no la cancela. */
    fun export(context: Context, uri: Uri, scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val result = awake(app) {
                runCatching { doExport(app, uri) }
                    .onFailure { Log.e(TAG, "export failed", it) }
                    .getOrElse { Outcome.Failed("Error al exportar: ${it.message ?: it.javaClass.simpleName}") }
            }
            _progress.value = null
            _outcome.value = result
            running.set(false)
        }
    }

    private suspend fun doExport(context: Context, uri: Uri): Outcome {
        _progress.value = Progress("Preparando…", 0, 0, 0)
        val music = musicDir(context)
        val torrents = torrentsDir(context)
        fun files(dir: File) = if (dir.isDirectory) {
            dir.walkTopDown().filter { f -> f.isFile && SKIP_SUFFIXES.none { f.name.endsWith(it) } }.toList()
        } else emptyList()
        val musicFiles = files(music)
        val torrentFiles = files(torrents)

        // Instantánea de la BD en un temporal; si VACUUM INTO no está disponible, checkpoint + copia.
        val dbSnapshot = File(context.cacheDir, "backup-music.db").also { it.delete() }
        val db = openMusicDatabase(context)
        runCatching { db.snapshotTo(dbSnapshot.absolutePath) }.onFailure {
            Log.w(TAG, "VACUUM INTO failed, falling back to copy", it)
            db.walCheckpoint()
            context.getDatabasePath("music.db").copyTo(dbSnapshot, overwrite = true)
        }
        val songCount = countLibrarySongs(dbSnapshot.absolutePath)

        val total = dbSnapshot.length() + musicFiles.sumOf { it.length() } + torrentFiles.sumOf { it.length() }
        val fileCount = musicFiles.size + torrentFiles.size
        var done = 0L
        var written = 0

        // "wt" trunca si el usuario eligió sobrescribir; no todos los proveedores lo admiten.
        val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri)
            ?: return Outcome.Failed("No se pudo abrir el destino")
        try {
            ZipOutputStream(out.buffered(1 shl 18)).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(
                    JSONObject()
                        .put("format", FORMAT)
                        .put("app", BuildConfig.VERSION_NAME)
                        .put("createdAt", System.currentTimeMillis())
                        .put("externalRoot", externalRoot(context))
                        .put("internalRoot", internalRoot(context))
                        .put("songs", songCount)
                        .put("files", fileCount)
                        .put("bytes", total)
                        .toString(2).toByteArray()
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(DB_ENTRY))
                dbSnapshot.inputStream().use { input ->
                    done += copy(input, zip) { _progress.value = Progress("Base de datos", done + it, total, written) }
                }
                zip.closeEntry()
                dbSnapshot.delete()

                PREFS.forEach { name ->
                    val f = prefsFile(context, name)
                    if (f.isFile) {
                        zip.putNextEntry(ZipEntry("$PREFS_PREFIX$name.xml"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }

                // El audio ya viene comprimido: guardarlo tal cual ahorra CPU y batería.
                zip.setLevel(Deflater.NO_COMPRESSION)
                fun addAll(list: List<File>, base: File, prefix: String) {
                    list.forEach { f ->
                        val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
                        zip.putNextEntry(ZipEntry(prefix + rel).apply { time = f.lastModified() })
                        f.inputStream().use { input ->
                            val before = done
                            done += copy(input, zip) {
                                _progress.value = Progress("Copiando $rel", before + it, total, written)
                            }
                        }
                        zip.closeEntry()
                        written++
                    }
                }
                addAll(musicFiles, music, MUSIC_PREFIX)
                addAll(torrentFiles, torrents, TORRENTS_PREFIX)
            }
        } finally {
            dbSnapshot.delete()
        }
        return Outcome.Exported(written, done)
    }

    // ------------------------------------------------------------------ import

    /**
     * Restaura desde [uri]: un ZIP de [export], o directamente un `music.db` de
     * "Copia de seguridad ahora" (sólo la biblioteca, sin ficheros ni ajustes).
     * Al terminar hace falta reiniciar la app ([restartApp]).
     */
    fun import(context: Context, uri: Uri, scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val result = awake(app) {
                runCatching { doImport(app, uri) }
                    .onFailure {
                        Log.e(TAG, "import failed", it)
                        restoreDir(app).deleteRecursively()
                    }
                    .getOrElse { Outcome.Failed("Error al restaurar: ${it.message ?: it.javaClass.simpleName}") }
            }
            _progress.value = null
            _outcome.value = result
            running.set(false)
        }
    }

    private fun doImport(context: Context, uri: Uri): Outcome {
        _progress.value = Progress("Abriendo la copia…", 0, 0, 0)
        val total = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L).coerceAtLeast(0L)
        val raw = context.contentResolver.openInputStream(uri)
            ?: return Outcome.Failed("No se pudo abrir el archivo")
        val counting = CountingInputStream(raw.buffered(1 shl 18))

        val staging = restoreDir(context).also { it.deleteRecursively(); it.mkdirs() }
        val stagedDb = File(staging, "music.db")
        val stagedPrefs = File(staging, "prefs").also { it.mkdirs() }

        // Un .db suelto (de "Copia de seguridad ahora") también vale.
        val head = BufferedInputStream(counting).also { it.mark(64) }
        val magic = ByteArray(16).also { head.read(it) }
        head.reset()
        if (String(magic, Charsets.ISO_8859_1).startsWith("SQLite format 3")) {
            head.use { input -> stagedDb.outputStream().use { input.copyTo(it) } }
            val songs = countLibrarySongs(stagedDb.absolutePath)
            File(staging, READY).writeText(JSONObject().put("format", FORMAT).put("dbOnly", true).toString())
            return Outcome.Restored(0, stagedDb.length(), songs)
        }

        var manifest: JSONObject? = null
        var files = 0
        var bytes = 0L
        val music = musicDir(context).also { it.mkdirs() }
        val torrents = torrentsDir(context).also { it.mkdirs() }

        ZipInputStream(head).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) continue
                // Una entrada con ".." escribiría fuera de la app: se ignora sin más.
                if (name.startsWith("/") || name.split('/').any { it == ".." || it.isEmpty() }) {
                    Log.w(TAG, "entrada sospechosa ignorada: $name"); continue
                }
                val label = name.substringAfterLast('/')
                fun report() = run { _progress.value = Progress("Restaurando $label", counting.count, total, files) }
                when {
                    name == MANIFEST -> {
                        val m = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        if (m.optInt("format", 0) > FORMAT) {
                            return Outcome.Failed("Esta copia es de una versión más nueva de la app; actualízala primero")
                        }
                        manifest = m
                    }
                    name == DB_ENTRY -> stagedDb.outputStream().use { bytes += copy(zip, it) { report() } }
                    name.startsWith(PREFS_PREFIX) -> {
                        val pref = name.removePrefix(PREFS_PREFIX).removeSuffix(".xml")
                        if (pref in PREFS) File(stagedPrefs, "$pref.xml").outputStream().use { zip.copyTo(it) }
                    }
                    name.startsWith(MUSIC_PREFIX) || name.startsWith(TORRENTS_PREFIX) -> {
                        val (base, rel) = if (name.startsWith(MUSIC_PREFIX)) {
                            music to name.removePrefix(MUSIC_PREFIX)
                        } else {
                            torrents to name.removePrefix(TORRENTS_PREFIX)
                        }
                        val target = File(base, rel)
                        target.parentFile?.mkdirs()
                        // Primero a un temporal: un corte a medias no deja una canción truncada con nombre bueno.
                        val tmp = File(target.path + ".restoring")
                        tmp.outputStream().use { bytes += copy(zip, it) { report() } }
                        if (entry.time > 0) tmp.setLastModified(entry.time)
                        target.delete()
                        if (!tmp.renameTo(target)) throw IllegalStateException("No se pudo colocar $rel")
                        files++
                    }
                    else -> Log.d(TAG, "entrada desconocida ignorada: $name")
                }
                zip.closeEntry()
            }
        }

        val m = manifest ?: return Outcome.Failed("El ZIP no es una copia completa de PrivateMusic")
        if (!stagedDb.isFile) return Outcome.Failed("La copia no contiene la base de datos")

        _progress.value = Progress("Ajustando rutas…", total, total, files)
        relocateLibraryRoots(
            stagedDb.absolutePath,
            listOf(
                m.optString("externalRoot") to externalRoot(context),
                m.optString("internalRoot") to internalRoot(context),
            ),
        )
        val songs = countLibrarySongs(stagedDb.absolutePath)
        File(staging, READY).writeText(m.toString())
        Log.i(TAG, "restauración preparada: $files ficheros, $songs canciones; se aplica al reiniciar")
        return Outcome.Restored(files, bytes, songs)
    }

    // ----------------------------------------------------------------- startup

    /**
     * Llamar lo primero en `Application.onCreate`, antes de abrir la BD o
     * cualquier SharedPreferences: si hay una restauración preparada, la coloca.
     * Devuelve true si se aplicó algo.
     */
    fun applyPendingRestore(context: Context): Boolean {
        val dir = restoreDir(context)
        if (!dir.isDirectory) return false
        val ready = File(dir, READY).exists()
        try {
            if (!ready) {
                Log.w(TAG, "restauración a medias descartada")
                return false
            }
            val db = File(dir, "music.db")
            if (db.isFile) {
                val target = context.getDatabasePath("music.db")
                target.parentFile?.mkdirs()
                listOf("", "-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
                if (!db.renameTo(target)) db.copyTo(target, overwrite = true)
            }
            val prefsDir = File(context.dataDir, "shared_prefs").also { it.mkdirs() }
            File(dir, "prefs").listFiles()?.forEach { f ->
                val t = File(prefsDir, f.name)
                t.delete()
                if (!f.renameTo(t)) f.copyTo(t, overwrite = true)
            }
            Log.i(TAG, "restauración aplicada")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo aplicar la restauración", e)
            return false
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Relanza la app desde cero para que [applyPendingRestore] haga su trabajo. */
    fun restartApp(context: Context) {
        val app = context.applicationContext
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) app.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    // ----------------------------------------------------------------- helpers

    /** Copia con aviso de progreso cada ~4 MB; devuelve los bytes copiados. */
    private inline fun copy(input: InputStream, out: OutputStream, onProgress: (Long) -> Unit): Long {
        val buf = ByteArray(1 shl 16)
        var copied = 0L
        var sinceReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            copied += n
            sinceReport += n
            if (sinceReport >= (4L shl 20)) {
                sinceReport = 0
                onProgress(copied)
            }
        }
        onProgress(copied)
        return copied
    }

    /** Un par de gigas por Wi-Fi o a un USB tardan: que la CPU no se duerma a mitad. */
    private inline fun <T> awake(context: Context, block: () -> T): T {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "privatemusic:backup")
        lock.acquire(2 * 60 * 60 * 1000L)
        try {
            return block()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
