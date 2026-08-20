package com.aar.privatemusic.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterTest {

    @Test
    fun `parsea los capitulos del dump-single-json de yt-dlp`() {
        val json = """
            {"id":"abc","title":"Full Album","chapters":[
              {"start_time":0.0,"end_time":200.0,"title":"01 - Primera"},
              {"start_time":200.0,"end_time":410.5,"title":"02 - Segunda / Bonus"},
              {"start_time":410.5,"end_time":600.0,"title":""}
            ]}
        """.trimIndent()
        val chapters = Chapter.parseFrom(json)
        assertEquals(3, chapters.size)
        assertEquals(1, chapters[0].index)
        assertEquals("01 - Primera", chapters[0].title)
        assertEquals(200, chapters[0].durationSec)
        assertEquals(210, chapters[1].durationSec)
        // Título vacío → nombre por defecto con su número.
        assertEquals("Capítulo 3", chapters[2].title)
    }

    @Test
    fun `sin capitulos devuelve lista vacia`() {
        assertTrue(Chapter.parseFrom("""{"id":"abc","title":"Song"}""").isEmpty())
        assertTrue(Chapter.parseFrom("no es json").isEmpty())
    }
}
