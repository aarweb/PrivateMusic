package com.aar.privatemusic.hilight

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.aar.privatemusic.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

enum class HiLightStatus { UNSUPPORTED, CHECKING, NEEDS_SHIZUKU, NEEDS_PERMISSION, DENIED, READY, ACTIVE, UNAVAILABLE, ERROR }

/** App-scoped owner. All hardware / binder operations are serialized off the playback/UI thread. */
class HiLightController(private val context: Context, private val settings: AppSettings) {
    val visible = HiLightPolicy.isCandidate(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL)
    private val mutableStatus = MutableStateFlow(if (visible) HiLightStatus.CHECKING else HiLightStatus.UNSUPPORTED)
    val status = mutableStatus.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var enabled = settings.hiLightEnabled.value
    private var playing = false
    private val playback = MutableStateFlow(false)
    private var output: HiLightOutput? = null
    private var cleanup: HiLightOutput? = null
    private var remote: IHiLightService? = null
    private var binding = false
    private var bindGeneration = 0
    private val owner = Binder()
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, HiLightUserService::class.java))
            .daemon(false).processNameSuffix("hilight").version(com.aar.privatemusic.BuildConfig.VERSION_CODE)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            commands.trySend(Command.Connected(service))
        }
        override fun onServiceDisconnected(name: ComponentName) { commands.trySend(Command.Disconnected) }
    }
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener { commands.trySend(Command.Disconnected) }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_CODE) {
            commands.trySend(if (result == PackageManager.PERMISSION_GRANTED) Command.Refresh else Command.Denied)
        }
    }

    init {
        if (visible) {
            Shizuku.addBinderReceivedListener(received)
            Shizuku.addBinderDeadListener(dead)
            Shizuku.addRequestPermissionResultListener(permission)
            scope.launch {
                for (command in commands) {
                    try {
                        handle(command)
                    } catch (_: Exception) {
                        cleanup = output ?: cleanup
                        output = null
                        runCatching { cleanup?.stop(); cleanup = null }
                        mutableStatus.value = HiLightStatus.ERROR
                    }
                }
            }
            scope.launch {
                settings.hiLightEnabled.collect { commands.send(Command.Enabled(it)) }
            }
            scope.launch {
                combine(settings.hiLightEnabled, playback) { optedIn, localPlaying -> optedIn && localPlaying }
                    .distinctUntilChanged().collectLatest { active ->
                        if (active) while (true) {
                            delay(HiLightPolicy.HEARTBEAT_MS)
                            commands.send(Command.Tick)
                        }
                    }
            }
        }
    }

    fun refresh() { if (visible) commands.trySend(Command.Refresh) }
    fun setPlaying(value: Boolean) {
        if (visible && playback.value != value) {
            playback.value = value
            commands.trySend(Command.Playing(value))
        }
    }
    fun setEnabled(value: Boolean) {
        if (!value || status.value in setOf(HiLightStatus.READY, HiLightStatus.ACTIVE)) {
            settings.setHiLightEnabled(value)
        }
    }

    /** Called only by an explicit button tap, never by a playback event. */
    fun requestAccess() {
        if (!visible) return
        runCatching {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                commands.trySend(Command.Disconnected)
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refresh()
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                commands.trySend(Command.Denied)
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }.onFailure { commands.trySend(Command.Disconnected) }
    }

    private fun handle(command: Command) {
        cleanup?.let { it.stop(); cleanup = null }
        // Read current intent, not a queued tick's old state, after a slow Binder transaction.
        enabled = settings.hiLightEnabled.value
        playing = playback.value
        when (command) {
            Command.Refresh -> connect()
            is Command.Enabled -> {
                if (enabled && output == null) connect()
            }
            is Command.Playing -> {
                if (playing && enabled && output == null && mutableStatus.value == HiLightStatus.CHECKING) connect()
            }
            is Command.Connected -> {
                // Reject a late callback after timeout/revocation instead of adopting a stale
                // helper. A subsequent explicit retry will create a new binding.
                if (!binding) {
                    runCatching { IHiLightService.Stub.asInterface(command.binder).stop() }
                    return
                }
                binding = false
                bindGeneration++
                val service = IHiLightService.Stub.asInterface(command.binder)
                remote = service
                if (service.isSupported) {
                    output = object : HiLightOutput {
                        override fun pulse() = service.pulse(owner)
                        override fun stop() = service.stop()
                    }
                    mutableStatus.value = HiLightStatus.READY
                } else {
                    mutableStatus.value = HiLightStatus.UNAVAILABLE
                }
            }
            Command.Disconnected -> {
                if (remote == null && output != null && !binding) return // direct system access
                // A dead helper cannot accept stop; its finite effect and lease expire locally.
                output = null
                remote = null
                binding = false
                bindGeneration++
                mutableStatus.value = if (Shizuku.pingBinder()) HiLightStatus.ERROR else HiLightStatus.NEEDS_SHIZUKU
            }
            Command.Denied -> {
                binding = false
                bindGeneration++
                output?.stop()
                output = null
                remote = null
                runCatching { Shizuku.unbindUserService(args, connection, true) }
                mutableStatus.value = HiLightStatus.DENIED
            }
            is Command.Timeout -> if (binding && bindGeneration == command.generation) {
                binding = false
                runCatching { Shizuku.unbindUserService(args, connection, true) }
                mutableStatus.value = HiLightStatus.ERROR
            }
            Command.Tick -> Unit
        }
        val ready = output != null
        if (HiLightPolicy.shouldIlluminate(settings.hiLightEnabled.value, playback.value, ready)) {
            output?.pulse()
            mutableStatus.value = HiLightStatus.ACTIVE
        } else if (ready && mutableStatus.value == HiLightStatus.ACTIVE) {
            output?.stop()
            mutableStatus.value = HiLightStatus.READY
        }
    }

    private fun connect() {
        if (Build.VERSION.SDK_INT < 37) return
        if (output != null || binding) return
        mutableStatus.value = HiLightStatus.CHECKING
        if (context.checkSelfPermission("android.permission.CONTROL_DEVICE_LIGHTS") == PackageManager.PERMISSION_GRANTED) {
            val direct = AndroidHiLightOutput.direct(context)
            if (direct.supported) {
                output = direct
                mutableStatus.value = HiLightStatus.READY
            } else mutableStatus.value = HiLightStatus.UNAVAILABLE
            return
        }
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            mutableStatus.value = HiLightStatus.NEEDS_SHIZUKU
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            mutableStatus.value = HiLightStatus.NEEDS_PERMISSION
            return
        }
        binding = true
        val generation = ++bindGeneration
        try { Shizuku.bindUserService(args, connection) } catch (error: Exception) {
            binding = false
            throw error
        }
        scope.launch { delay(10_000); commands.send(Command.Timeout(generation)) }
    }

    private sealed interface Command {
        data object Refresh : Command
        data object Tick : Command
        data object Disconnected : Command
        data object Denied : Command
        data class Enabled(val value: Boolean) : Command
        data class Playing(val value: Boolean) : Command
        data class Connected(val binder: IBinder) : Command
        data class Timeout(val generation: Int) : Command
    }

    companion object { private const val REQUEST_CODE = 7101 }
}
