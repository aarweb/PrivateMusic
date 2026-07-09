package com.aar.privatemusic.desktop.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS

/** Un móvil compartiendo su biblioteca en la red local. */
data class Phone(val name: String, val host: String, val port: Int) {
    val baseUrl: String get() = "http://$host:$port"
}

object PhoneDiscovery {

    private const val SERVICE_TYPE = "_privatemusic._tcp.local."

    /**
     * Busca móviles durante [timeoutMs]. Devuelve lista vacía si mDNS no llega
     * (redes con aislamiento de clientes, VPN, cortafuegos): en ese caso la
     * interfaz deja escribir la dirección a mano, que siempre funciona.
     *
     * Pregunta por **todas** las interfaces a la vez. Elegir una es adivinar:
     * un PC con Docker tiene diez direcciones "site-local" (172.17.0.1,
     * 172.18.0.1…) antes de llegar a la Wi-Fi, y en ésas no hay ningún móvil.
     */
    suspend fun discover(timeoutMs: Long = 4000): List<Phone> = withContext(Dispatchers.IO) {
        val addresses = lanAddresses()
        if (addresses.isEmpty()) return@withContext emptyList()

        addresses
            .map { address -> async { queryOn(address, timeoutMs) } }
            .awaitAll()
            .flatten()
            .distinctBy { it.host to it.port }
    }

    private fun queryOn(address: InetAddress, timeoutMs: Long): List<Phone> {
        val jmdns = runCatching { JmDNS.create(address) }.getOrNull() ?: return emptyList()
        return try {
            jmdns.list(SERVICE_TYPE, timeoutMs).mapNotNull { info ->
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return@mapNotNull null
                Phone(name = info.name, host = host, port = info.port)
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { jmdns.close() }
        }
    }

    private fun lanAddresses(): List<InetAddress> =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .toList()
}
