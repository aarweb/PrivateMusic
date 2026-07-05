package com.aar.privatemusic.downloader

import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desencripta los streams de Deezer (cifrado BF_CBC_STRIPE): sólo 1 de cada 3
 * bloques de 2048 bytes va cifrado con Blowfish/CBC, con una clave derivada del
 * id del track. `javax.crypto` trae Blowfish de serie, sin dependencias.
 */
object BlowfishDecryptor {

    private const val BF_SECRET = "g4el58wc0zvf9na1"
    private val IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    /** Clave Blowfish de 16 bytes derivada del id del track (MD5 + XOR con el secreto). */
    private fun keyFor(trackId: String): ByteArray {
        val md5 = MessageDigest.getInstance("MD5").digest(trackId.toByteArray(Charsets.US_ASCII))
        val hex = md5.joinToString("") { "%02x".format(it) } // 32 chars
        return ByteArray(16) { i ->
            (hex[i].code xor hex[i + 16].code xor BF_SECRET[i].code).toByte()
        }
    }

    /** Lee [input] (cifrado a rayas) y escribe [output] en claro. */
    fun decryptFile(input: File, output: File, trackId: String) {
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyFor(trackId), "Blowfish"),
            IvParameterSpec(IV),
        )
        input.inputStream().use { fis ->
            output.outputStream().use { fos ->
                val buffer = ByteArray(2048)
                var chunkIdx = 0
                while (true) {
                    val read = fis.readNBytesCompat(buffer)
                    if (read <= 0) break
                    // Sólo los bloques completos de 2048 bytes cada 3 van cifrados.
                    val chunk = if (read == 2048 && chunkIdx % 3 == 0) {
                        cipher.doFinal(buffer)
                    } else {
                        buffer.copyOf(read)
                    }
                    fos.write(chunk)
                    chunkIdx++
                }
            }
        }
    }

    /** Lee hasta llenar [buf] (o EOF); InputStream.read puede devolver menos de lo pedido. */
    private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }
}
