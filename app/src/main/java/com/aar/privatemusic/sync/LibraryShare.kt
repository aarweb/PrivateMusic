package com.aar.privatemusic.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.aar.privatemusic.cast.MediaHttpServer
import com.aar.privatemusic.data.db.MusicDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Comparte la biblioteca con el PC por la red local: levanta el servidor HTTP
 * en [MediaHttpServer.SHARE_PORT] y se anuncia por mDNS para que el escritorio
 * lo encuentre solo, sin escribir direcciones IP.
 *
 * Sólo sirve; no recibe. El móvil sigue siendo el dueño de la biblioteca.
 */
class LibraryShare(private val context: Context, private val dao: MusicDao) {

    private var server: MediaHttpServer? = null
    private var registration: NsdManager.RegistrationListener? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** "192.168.1.158:8966" mientras comparte, para enseñarlo en Ajustes. */
    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address.asStateFlow()

    @Synchronized
    fun start() {
        if (_running.value) return
        val started = runCatching {
            MediaHttpServer(dao, MediaHttpServer.SHARE_PORT).also { it.start() }
        }.getOrNull() ?: return
        server = started
        _running.value = true
        _address.value = MediaHttpServer.localIp()?.let { "$it:${MediaHttpServer.SHARE_PORT}" }
        registerService()
    }

    @Synchronized
    fun stop() {
        registration?.let { runCatching { nsd().unregisterService(it) } }
        registration = null
        server?.stop()
        server = null
        _address.value = null
        _running.value = false
    }

    private fun nsd() = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = MediaHttpServer.SHARE_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        // Si mDNS falla, el servidor sigue en pie: el PC puede escribir la IP.
        runCatching { nsd().registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { registration = listener }
    }

    companion object {
        const val SERVICE_NAME = "PrivateMusic"
        const val SERVICE_TYPE = "_privatemusic._tcp."
    }
}
