package com.aar.privatemusic.downloader

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeezerSourceTest {

    @Test
    fun `parsea un payload de Deezer con todos los metadatos`() {
        val json = """
            {"data":[{
              "id":4236072092,"title":"VLONE","duration":180,
              "artist":{"name":"Kidd Keo"},
              "album":{"title":"Álbum","cover_big":"https://example.com/big.jpg",
                       "cover_medium":"https://example.com/medium.jpg"},
              "preview":"https://example.com/preview.mp3"
            }]}
        """.trimIndent()

        assertEquals(
            listOf(DeezerTrack(
                id = 4236072092L,
                title = "VLONE",
                artist = "Kidd Keo",
                album = "Álbum",
                durationSec = 180,
                coverUrl = "https://example.com/big.jpg",
                previewUrl = "https://example.com/preview.mp3",
            )),
            DeezerSource.parseSearch(json),
        )
    }

    @Test
    fun `el parseo omite entradas sin titulo y conserva los valores por defecto`() {
        val json = """
            {"data":[null,7,{}, {"title":" "},
              {"id":1,"title":"Tema","artist":{"name":""},
               "album":{"cover_big":"","cover_medium":"https://example.com/medium.jpg"}},
              {"id":2,"title":"Otro"}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                track(1, "Tema", "Desconocido").copy(coverUrl = "https://example.com/medium.jpg"),
                track(2, "Otro", "Desconocido"),
            ),
            DeezerSource.parseSearch(json),
        )
    }

    @Test
    fun `un JSON invalido o sin data devuelve una lista vacia`() {
        listOf("", "no es JSON", "{", "[]", "{}", """{"data":null}""",
            """{"data":{}}""", """{"data":[]}""").forEach { json ->
            assertTrue(DeezerSource.parseSearch(json).isEmpty(), json)
        }
    }

    @Test
    fun `deduplica por id conservando la primera aparicion antes de limitar`() {
        val first = track(1, "VLONE", "Kidd Keo")
        val second = track(2, "VLONE", "Kidd Keo")
        val third = track(3, "VLONE", "Kidd Keo")

        assertEquals(
            listOf(first, second, third),
            DeezerSource.mergeResults(
                "VLONE Kidd Keo",
                listOf(first, first),
                listOf(first.copy(album = "Duplicado"), second, second, third),
                limit = 3,
            ),
        )
    }

    @Test
    fun `VLONE de Kidd Keo gana a One de Kidd Keo aunque llegue de respaldo`() {
        val one = track(1, "One", "Kidd Keo")
        val vlone = track(4236072092L, "VLONE", "Kidd Keo")

        assertEquals(
            listOf(vlone, one),
            DeezerSource.mergeResults("VLONE Kidd Keo", listOf(one), listOf(vlone), 25),
        )
        assertEquals(
            listOf(vlone),
            DeezerSource.mergeResults("VLONE Kidd Keo", listOf(one), listOf(vlone), 1),
        )
    }

    @Test
    fun `en empate de coincidencias prioriza los tokens del titulo`() {
        val inArtist = track(1, "Otro", "VLONE Kidd")
        val inTitle = track(2, "VLONE", "Kidd")

        assertEquals(
            listOf(inTitle, inArtist),
            DeezerSource.mergeResults("VLONE Kidd Keo", listOf(inArtist), listOf(inTitle), 25),
        )
    }

    @Test
    fun `conserva el orden de aparicion entre primarios y respaldos en empate`() {
        val first = track(3, "VLONE", "Kidd Keo")
        val second = track(1, "VLONE", "Kidd Keo")
        val third = track(2, "VLONE", "Kidd Keo")

        assertEquals(
            listOf(first, second, third),
            DeezerSource.mergeResults("VLONE Kidd Keo", listOf(first, second), listOf(third), 25),
        )
        assertTrue(DeezerSource.mergeResults("VLONE", listOf(first), listOf(third), 0).isEmpty())
    }

    @Test
    fun `cuenta tokens completos y no subcadenas`() {
        val partial = track(1, "VLONES", "Kiddie Keos")
        val complete = track(2, "Otro", "Kidd")

        assertEquals(
            listOf(complete, partial),
            DeezerSource.mergeResults("VLONE Kidd Keo", listOf(partial), listOf(complete), 25),
        )
        assertEquals(listOf("vlone", "kidd"), DeezerSource.fallbackTokens("VLONE Kidd Keo", listOf(partial)))
    }

    @Test
    fun `extrae letras y numeros Unicode en minusculas sin puntuacion`() {
        assertEquals(
            listOf("árbol", "niño", "東京", "１２３", "a1", "keo"),
            DeezerSource.queryTokens("ÁRBOL—NiÑO, 東京 / １２３ A1_keo"),
        )
        assertTrue(DeezerSource.fallbackTokens(
            "ÁRBOL NIÑO",
            listOf(track(1, "Árbol!", "Niño")),
        ).isEmpty())
    }

    @Test
    fun `expande la consulta libre sin resultados o con coincidencias parciales`() {
        assertEquals(listOf("vlone", "kidd"), DeezerSource.fallbackTokens("VLONE Kidd Keo", emptyList()))
        assertEquals(
            listOf("vlone", "kidd"),
            DeezerSource.fallbackTokens("VLONE Kidd Keo", listOf(track(1, "One", "Kidd Keo"))),
        )
        // Los tokens deben coincidir en un mismo resultado, no repartidos entre varios.
        assertEquals(
            listOf("vlone", "kidd"),
            DeezerSource.fallbackTokens("VLONE Kidd Keo", listOf(
                track(1, "VLONE", "Otro"), track(2, "One", "Kidd Keo"),
            )),
        )
    }

    @Test
    fun `no expande cuando algun primario contiene todos los tokens`() {
        assertTrue(DeezerSource.fallbackTokens(
            "VLONE Kidd Keo",
            listOf(track(1, "One", "Kidd Keo"), track(2, "vlone (Live)", "KIDD-KEO")),
        ).isEmpty())
    }

    @Test
    fun `no expande consultas avanzadas entre comillas o con menos de dos tokens`() {
        listOf(
            """track:"VLONE" artist:"Kidd Keo"""",
            "track:VLONE artist:Kidd",
            """"VLONE Kidd Keo"""",
            "'VLONE Kidd Keo'",
            "VLONE", "", "---",
        ).forEach { query ->
            assertTrue(DeezerSource.fallbackTokens(query, emptyList()).isEmpty(), query)
        }
    }

    @Test
    fun `selecciona hasta tres tokens distintos de al menos cuatro caracteres`() {
        assertEquals(
            listOf("vlone", "kidd", "árbol"),
            DeezerSource.fallbackTokens("VLONE vlone Kidd Keo Árbol Niño Extra", emptyList()),
        )
        assertEquals(listOf("vlone"), DeezerSource.fallbackTokens("Keo Sol VLONE", emptyList()))
    }

    @Test
    fun `usa hasta tres tokens de tres caracteres solo si no hay tokens mas largos`() {
        assertEquals(
            listOf("keo", "sol", "mar"),
            DeezerSource.fallbackTokens("KEO keo Sol Mar Luz El", emptyList()),
        )
        assertTrue(DeezerSource.fallbackTokens("Yo Tu 12", emptyList()).isEmpty())
        // Cada letra suplementaria ocupa dos unidades UTF-16, pero es un solo carácter.
        assertTrue(DeezerSource.fallbackTokens("𐐀𐐁 yo", emptyList()).isEmpty())
    }

    @Test
    fun `reconoce enlaces de Deezer sin aceptar dominios parecidos`() {
        assertTrue(DeezerSource.isDeezerLink("https://www.deezer.com/es/track/4299455132"))
        assertTrue(DeezerSource.isDeezerLink("https://link.deezer.com/s/34uVlHsXGYXpA9wRNHcAb"))
        assertFalse(DeezerSource.isDeezerLink("https://deezer.com.example.org/track/4299455132"))
        assertFalse(DeezerSource.isDeezerLink("http://www.deezer.com/track/4299455132"))
    }

    @Test
    fun `extrae el id de una pista directa con o sin idioma`() {
        assertEquals(4299455132L, DeezerSource.trackIdFromUrl("https://www.deezer.com/es/track/4299455132?utm=x"))
        assertEquals(42L, DeezerSource.trackIdFromUrl("https://deezer.com/track/42"))
        assertNull(DeezerSource.trackIdFromUrl("https://www.deezer.com/es/album/4299455132"))
    }

    @Test
    fun `convierte una ficha directa y encuentra tokens del album`() {
        val parsed = DeezerSource.parseTrack(JSONObject(
            """{
                "id":4299455132,"title":"After","duration":189,
                "artist":{"name":"Conep"},
                "album":{"title":"After (feat. Young Miko)","cover_big":"https://img/cover.jpg"},
                "preview":"https://audio/preview.mp3"
            }""".trimIndent(),
        ))

        assertEquals(4299455132L, parsed?.id)
        assertEquals("After (feat. Young Miko)", parsed?.album)
        assertTrue(DeezerSource.matchesQuery(parsed!!, DeezerSource.queryTokens("After Conep")))
        assertTrue(DeezerSource.matchesQuery(parsed, DeezerSource.queryTokens("After Young Miko")))
        assertFalse(DeezerSource.matchesQuery(parsed, DeezerSource.queryTokens("After Bad Bunny")))
    }

    private fun track(id: Long, title: String, artist: String) = DeezerTrack(
        id = id,
        title = title,
        artist = artist,
        album = "",
        durationSec = 0,
        coverUrl = "",
        previewUrl = "",
    )
}
