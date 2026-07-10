package com.aar.privatemusic.desktop.player

import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.data.db.openMusicDatabase
import com.aar.privatemusic.desktop.DesktopSettings
import com.aar.privatemusic.desktop.audio.AudioEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El fallo: en mitad de un cruce el índice ya apunta a la canción que entra
 * (el motor la adelantó él solo). Pulsar "siguiente" ahí sumaba otra vez, así
 * que un solo toque avanzaba dos pistas y se comía la de en medio. Este test lo
 * reproduce con un motor falso —no hace falta VLC ni ficheros de audio de
 * verdad— y comprueba que ahora se queda en la entrante y corta el fundido.
 */
class DesktopPlayerNextTest {

    /** Motor de audio de mentira: no decodifica nada, sólo expone flujos que el
     *  test mueve a mano y cuenta las llamadas que importan. */
    private class FakeEngine : AudioEngine {
        val playing = MutableStateFlow(false)
        override val isPlaying: StateFlow<Boolean> = playing.asStateFlow()
        val position = MutableStateFlow(0L)
        override val positionMs: StateFlow<Long> = position.asStateFlow()
        val duration = MutableStateFlow(0L)
        override val durationMs: StateFlow<Long> = duration.asStateFlow()
        val fading = MutableStateFlow(false)
        override val crossfading: StateFlow<Boolean> = fading.asStateFlow()
        override var onFinished: (() -> Unit)? = null

        var settleCount = 0

        override fun play(file: File) { playing.value = true }
        override fun playUrl(url: String) {}
        override fun crossfadeTo(file: File, gain: Float, durationMs: Long, rateOut: Float) {
            fading.value = true
        }
        override fun settleCrossfade() { settleCount++; fading.value = false }
        override fun pause() {}
        override fun resume() {}
        override fun seekTo(ms: Long) {}
        override fun setRate(rate: Float) {}
        override fun setVolume(percent: Int) {}
        override fun setGain(gain: Float) {}
        override val equalizerBands: List<Float> = emptyList()
        override val equalizerPresets: List<String> = emptyList()
        override fun presetAmps(preset: String): FloatArray? = null
        override fun setEqualizer(enabled: Boolean, preamp: Float, amps: FloatArray) {}
        override fun release() {}
    }

    private fun song(id: String, path: String) = Song(
        id = id, title = id, artist = "x", durationSec = 10,
        filePath = path, artPath = null, thumbnailUrl = null, addedAt = 0,
    )

    @Test
    fun `siguiente en mitad de un cruce no se salta una cancion`() = runBlocking {
        val dir = Files.createTempDirectory("pmtest").toFile()
        // Ficheros legibles: crossfadeTo() y playAt() comprueban canRead() antes
        // de avanzar; sin ellos el índice no se movería y el test no probaría nada.
        val f0 = File(dir, "a.mp3").apply { writeText("x") }
        val f1 = File(dir, "b.mp3").apply { writeText("x") }
        val f2 = File(dir, "c.mp3").apply { writeText("x") }
        val dao = openMusicDatabase(File(dir, "db")).musicDao()
        val settings = DesktopSettings(dir).apply { setCrossfadeSec(3); setAutoMix(false) }
        val engine = FakeEngine()
        val player = DesktopPlayer(engine, dao, settings)

        val songs = listOf(song("s0", f0.path), song("s1", f1.path), song("s2", f2.path))
        player.playQueue(songs, 0)
        engine.playing.value = true
        engine.duration.value = 10_000
        assertEquals(0, player.index.value)

        // Posición dentro de la ventana de cruce (>= duración - crossfade).
        engine.position.value = 8_000
        // El crossfadeWatcher tica cada 200 ms; le damos hasta 3 s para disparar.
        var waited = 0
        while (!engine.crossfading.value && waited < 3_000) { delay(100); waited += 100 }
        assertTrue(engine.crossfading.value, "el crossfade debería haber disparado")
        assertEquals(1, player.index.value) // el motor adelantó el índice a s1

        // Aquí estaba el fallo: "siguiente" sumaba otra vez y saltaba a s2.
        player.next()
        assertEquals(1, player.index.value) // se queda en s1, no se salta nada
        assertTrue(engine.settleCount >= 1, "next() debe cortar el cruce")
    }
}
