package com.aar.privatemusic.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicCreditsParserTest {
    @Test
    fun `parsea identidad creditos directos y relaciones del work`() {
        val parsed = MusicCreditsParser.parseRecording(FIXTURE)!!

        assertEquals("12345678-1234-4234-8234-123456789abc", parsed.recordingMbid)
        assertEquals("Luz del Sur", parsed.title)
        assertEquals("Ana & Los Faros", parsed.artist)
        assertEquals(listOf("pop", "indie"), parsed.genres)
        assertEquals(listOf("ESAAA2400001", "ESAAA2400002"), parsed.isrcs)

        assertEquals(
            MusicCredit(
                CreditGroup.PRODUCTION,
                "Producción",
                "Beatriz Ruiz",
                listOf("Acreditado como: B. Ruiz"),
            ),
            parsed.credits[0],
        )
        assertEquals(
            MusicCredit(
                CreditGroup.PERFORMANCE,
                "Instrumento",
                "Carlos Vega",
                listOf("Guitarra acústica", "12-string guitar"),
            ),
            parsed.credits[1],
        )
        assertEquals(
            MusicCredit(CreditGroup.TECHNICAL, "Grabación", "Fabián Lago"),
            parsed.credits[2],
        )
        assertEquals(
            MusicCredit(CreditGroup.WRITING, "Composición", "Diana Sol"),
            parsed.credits[3],
        )
        assertEquals(
            MusicCredit(CreditGroup.WRITING, "Letra", "Elena Mar"),
            parsed.credits[4],
        )
        assertEquals(
            MusicCredit(CreditGroup.WRITING, "Publicación", "Ediciones Horizonte"),
            parsed.credits[5],
        )
    }

    @Test
    fun `deduplica creditos y conexiones conservando el orden`() {
        val parsed = MusicCreditsParser.parseRecording(FIXTURE)!!

        assertEquals(6, parsed.credits.size)
        assertEquals(
            listOf(
                MusicConnection("Muestra", "Ritmo Antiguo", "Grupo Uno"),
                MusicConnection("Remezcla", "Luz del Sur (Club Remix)", "DJ Nube"),
            ),
            parsed.connections,
        )
    }

    @Test
    fun `json corrupto devuelve null`() {
        assertNull(MusicCreditsParser.parseRecording("{no-es-json"))
        assertNull(MusicCreditsParser.parseRecording("""{"title":"Sin id"}"""))
    }

    private companion object {
        val FIXTURE = """
            {
              "id": "12345678-1234-4234-8234-123456789abc",
              "title": "Luz del Sur",
              "artist-credit": [
                {"name":"Ana", "joinphrase":" & ", "artist":{"name":"Ana"}},
                {"name":"Los Faros", "joinphrase":"", "artist":{"name":"Los Faros"}}
              ],
              "isrcs": ["ESAAA2400001", "ESAAA2400002", "ESAAA2400001"],
              "genres": [{"name":"pop"}, {"name":"indie"}, {"name":"pop"}],
              "relations": [
                {
                  "target-type":"artist", "type":"producer", "direction":"forward",
                  "credited-as":"B. Ruiz", "artist":{"name":"Beatriz Ruiz"}
                },
                {
                  "target-type":"artist", "type":"instrument", "direction":"forward",
                  "attributes":["acoustic guitar", "12-string guitar"],
                  "attribute-values":{"acoustic guitar":"guitar"},
                  "attribute-credits":{"acoustic guitar":"Guitarra acústica"},
                  "artist":{"name":"Carlos Vega"}
                },
                {
                  "target-type":"artist", "type":"recording", "direction":"forward",
                  "artist":{"name":"Fabián Lago"}
                },
                {
                  "target-type":"work", "type":"performance", "work":{
                    "id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "title":"Luz del Sur",
                    "relations":[
                      {"target-type":"artist", "type":"composer", "artist":{"name":"Diana Sol"}},
                      {"target-type":"artist", "type":"lyricist", "artist":{"name":"Elena Mar"}},
                      {"target-type":"artist", "type":"composer", "artist":{"name":"Diana Sol"}},
                      {"target-type":"label", "type":"publisher", "label":{"name":"Ediciones Horizonte"}}
                    ]
                  }
                },
                {
                  "target-type":"recording", "type":"samples material", "direction":"forward",
                  "recording":{"title":"Ritmo Antiguo", "artist-credit":[{"name":"Grupo Uno"}]}
                },
                {
                  "target-type":"recording", "type":"samples material", "direction":"forward",
                  "recording":{"title":"Ritmo Antiguo", "artist-credit":[{"name":"Grupo Uno"}]}
                },
                {
                  "target-type":"recording", "type":"remix", "direction":"forward",
                  "recording":{"title":"Luz del Sur (Club Remix)", "artist-credit":[{"name":"DJ Nube"}]}
                }
              ]
            }
        """.trimIndent()
    }
}
