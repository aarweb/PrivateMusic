package com.aar.privatemusic.desktop.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** El puerto en el que el móvil publica su biblioteca (`MediaHttpServer.SHARE_PORT`). */
const val SHARE_PORT = 8966

/** Un móvil compartiendo su biblioteca en la red local. */
data class Phone(val name: String, val host: String, val port: Int) {
    val baseUrl: String get() = "http://$host:$port"
}

object PhoneDiscovery {

    private const val SERVICE_TYPE = "_privatemusic._tcp.local."

    /**
     * Busca móviles durante [timeoutMs]. Devuelve lista vacía si mDNS no llega
     * (redes con aislamiento de clientes, VPN, cortafuegos): para eso Ajustes
     * deja escribir la dirección a mano, que siempre funciona.
     *
     * Pregunta desde **todas** las direcciones de todas las interfaces, IPv4 e
     * IPv6. Las dos cosas importan, y por motivos distintos:
     *
     *  - Elegir una interfaz es adivinar: un PC con Docker tiene varias
     *    direcciones "site-local" (172.17.0.1, 172.18.0.1…) antes de la Wi-Fi,
     *    y en ésas no hay ningún móvil.
     *  - Un Pixel anuncia el servicio por multicast **IPv6**. Atado sólo a la
     *    IPv4 de la Wi-Fi, jmdns no oye nada: el móvil está publicando y el PC
     *    jura que no hay nadie. Escuchando también en la dirección IPv6 de
     *    enlace local aparece a la primera, y el registro trae dentro su IPv4,
     *    que es por donde luego habla el HTTP.
     */
    suspend fun discover(timeoutMs: Long = 4000): List<Phone> = withContext(Dispatchers.IO) {
        val addresses = localAddresses()
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
            jmdns.list(SERVICE_TYPE, timeoutMs).mapNotNull(::toPhone)
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { jmdns.close() }
        }
    }

    /**
     * Un registro sin puerto es un anuncio a medio resolver: no sirve. El
     * servidor del móvil escucha en IPv4, así que ésa es la buena; si faltara,
     * la IPv6 vale igual entre corchetes (sin el sufijo de ámbito, que sólo
     * entiende la máquina que lo escribió).
     */
    private fun toPhone(info: ServiceInfo): Phone? {
        if (info.port == 0) return null
        val host = info.inet4Addresses.firstOrNull()?.hostAddress
            ?: info.inet6Addresses.firstOrNull()?.hostAddress?.substringBefore('%')?.let { "[$it]" }
            ?: return null
        return Phone(name = info.name, host = host, port = info.port)
    }

    private fun localAddresses(): List<InetAddress> =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .flatMap { it.inetAddresses.asSequence() }
            .toList()
}
