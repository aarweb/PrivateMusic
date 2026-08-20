package com.aar.privatemusic.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import com.aar.privatemusic.BuildConfig
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Modo correr: la cola se elige por BPM para que cada canción cuadre con la
 * cadencia (pasos por minuto), y el tempo se ajusta hasta ±6 % para clavarla
 * sin cambiar el tono. La cadencia sale del podómetro del sistema
 * (`TYPE_STEP_DETECTOR`, ventana de 30 s) o de un valor fijado a mano.
 *
 * Las canciones a medio tiempo o doble tiempo también valen: 85 BPM sirven
 * para 170 pasos (un paso por corchea) igual que 170.
 */
class RunningMode(
    private val context: Context,
    private val dao: MusicDao,
    private val player: PlayerController,
    private val scope: CoroutineScope,
) {
    data class State(
        /** Cadencia objetivo, pasos por minuto. */
        val targetSpm: Int,
        /** Cadencia medida por el podómetro (null = sin datos aún o modo manual). */
        val measuredSpm: Int?,
        val useSensor: Boolean,
        /** BPM de la canción actual y factor de tempo aplicado. */
        val songBpm: Float?,
        val speed: Float,
        val queueSize: Int,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private val sensorManager by lazy { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val stepTimes = ArrayDeque<Long>()
    private var followJob: Job? = null
    private var debugReceiver: BroadcastReceiver? = null

    val hasStepSensor: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return
            onStep(System.currentTimeMillis())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Arranca con una cadencia manual; si [useSensor], el podómetro la irá corrigiendo. */
    fun start(manualSpm: Int, useSensor: Boolean) {
        stop(restoreTempo = false)
        _state.value = State(manualSpm, null, useSensor, null, 1f, 0)
        if (useSensor) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        if (BuildConfig.DEBUG) registerDebugReceiver()
        scope.launch {
            val queue = buildQueue(manualSpm)
            _state.value = _state.value?.copy(queueSize = queue.size)
            if (queue.isEmpty()) {
                Log.w(TAG, "no hay canciones con BPM cerca de $manualSpm")
                return@launch
            }
            // MediaController sólo admite llamadas desde el hilo principal.
            withContext(Dispatchers.Main) { player.playQueueInOrder(queue) }
            follow()
        }
    }

    fun setTarget(spm: Int) {
        val s = _state.value ?: return
        _state.value = s.copy(targetSpm = spm)
        scope.launch { applyTempo(player.nowPlaying.value?.songId) }
    }

    fun stop(restoreTempo: Boolean = true) {
        if (_state.value == null && followJob == null) return
        runCatching { sensorManager.unregisterListener(listener) }
        debugReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        debugReceiver = null
        followJob?.cancel()
        followJob = null
        stepTimes.clear()
        _state.value = null
        if (restoreTempo) scope.launch(Dispatchers.Main) { player.setPlaybackSpeed(1f) }
    }

    // ------------------------------------------------------------------ cola

    /** Canciones cuyo BPM (o su mitad/doble) está a ±5 % de la cadencia, las más cercanas primero. */
    internal suspend fun buildQueue(spm: Int): List<Song> {
        val now = System.currentTimeMillis()
        return dao.songsOnce()
            .filter { it.snoozedUntil < now && it.bpm != null }
            .mapNotNull { s -> effectiveBpm(s.bpm!!, spm)?.let { s to abs(it - spm) / spm } }
            .filter { it.second <= 0.05f }
            .sortedBy { it.second }
            .map { it.first }
            .shuffled()
            .sortedBy { s -> (abs(effectiveBpm(s.bpm!!, spm)!! - spm) / spm * 4).toInt() } // por franjas de ~1 %, barajado dentro
            .take(60)
    }

    /** BPM, ×2 o ÷2: el que más cerca quede de la cadencia. null si ninguno está a menos del 12 %. */
    private fun effectiveBpm(bpm: Float, spm: Int): Float? =
        listOf(bpm, bpm * 2, bpm / 2).minByOrNull { abs(it - spm) }?.takeIf { abs(it - spm) / spm <= 0.12f }

    // ----------------------------------------------------------------- tempo

    private fun follow() {
        followJob?.cancel()
        followJob = scope.launch {
            player.nowPlaying.collectLatest { np -> applyTempo(np?.songId) }
        }
    }

    private suspend fun applyTempo(songId: String?) {
        val s = _state.value ?: return
        val bpm = songId?.let { dao.getBpm(it) }
        val eff = bpm?.let { effectiveBpm(it, s.targetSpm) }
        // Hasta ±6 %: más se nota, y la cola ya se eligió para no necesitarlo.
        val speed = if (eff != null) (s.targetSpm / eff).coerceIn(0.94f, 1.06f) else 1f
        withContext(Dispatchers.Main) { player.setPlaybackSpeed(speed) }
        _state.value = _state.value?.copy(songBpm = bpm, speed = speed)
    }

    // -------------------------------------------------------------- cadencia

    private fun onStep(at: Long) {
        stepTimes.addLast(at)
        while (stepTimes.isNotEmpty() && at - stepTimes.first() > WINDOW_MS) stepTimes.removeFirst()
        if (stepTimes.size < MIN_STEPS) return
        val span = at - stepTimes.first()
        if (span <= 0) return
        val spm = ((stepTimes.size - 1) * 60_000f / span).toInt()
        onCadence(spm)
    }

    private fun onCadence(spm: Int, force: Boolean = false) {
        val s = _state.value ?: return
        if (!s.useSensor && !force) return
        val changed = abs(spm - s.targetSpm) / s.targetSpm.toFloat() > 0.04f
        _state.value = s.copy(measuredSpm = spm, targetSpm = if (changed) spm else s.targetSpm)
        if (changed) scope.launch { applyTempo(player.nowPlaying.value?.songId) }
    }

    /**
     * Sólo en builds de depuración: el emulador no tiene podómetro, así que la
     * cadencia se inyecta con
     * `adb shell am broadcast -a com.aar.privatemusic.DEBUG_CADENCE --ei spm 170 -p com.aar.privatemusic`.
     */
    private fun registerDebugReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val spm = intent?.getIntExtra("spm", 0) ?: 0
                if (spm in 60..240) {
                    Log.i(TAG, "cadencia simulada: $spm spm")
                    onCadence(spm, force = true)
                }
            }
        }
        val filter = IntentFilter(DEBUG_ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(r, filter)
        debugReceiver = r
    }

    companion object {
        private const val TAG = "RunningMode"
        private const val WINDOW_MS = 30_000L
        private const val MIN_STEPS = 20
        const val DEBUG_ACTION = "com.aar.privatemusic.DEBUG_CADENCE"
    }
}
