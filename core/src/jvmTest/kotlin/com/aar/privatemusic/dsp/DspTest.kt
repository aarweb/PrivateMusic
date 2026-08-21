package com.aar.privatemusic.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class DspTest {

    private val fs = 44100

    /** RMS de un tono de [freq] Hz tras pasar por [eq], sobre 1 s. */
    private fun toneRmsThroughEq(freq: Double, preampDb: Double, filters: List<EqFilter>): Double {
        val eq = ParametricEq().apply { configure(fs, 1, preampDb, filters) }
        val n = fs
        val buf = ShortArray(n) { (sin(2 * PI * freq * it / fs) * 10000).toInt().toShort() }
        eq.process(buf, n)
        // Descarta el arranque del filtro (primeros 2000).
        var sum = 0.0
        for (i in 2000 until n) sum += buf[i].toDouble() * buf[i]
        return sqrt(sum / (n - 2000))
    }

    private fun refRms(freq: Double): Double {
        val n = fs
        var sum = 0.0
        for (i in 2000 until n) {
            val v = sin(2 * PI * freq * i / fs) * 10000
            sum += v * v
        }
        return sqrt(sum / (n - 2000))
    }

    @Test
    fun peakingCutAttenuatesTargetLeavesOthers() {
        val cut = listOf(EqFilter(EqFilterType.PK, 1000.0, -12.0, 3.0))
        val ref1k = refRms(1000.0)
        val at1k = toneRmsThroughEq(1000.0, 0.0, cut)
        val at100 = toneRmsThroughEq(100.0, 0.0, cut)
        // -12 dB en 1 kHz ~ factor 0.25; damos margen por el ancho del filtro.
        assertTrue(at1k < ref1k * 0.45, "1 kHz debe caer bastante: $at1k vs $ref1k")
        assertTrue(at100 > refRms(100.0) * 0.9, "100 Hz casi intacto: $at100")
    }

    @Test
    fun lowShelfBoostRaisesBass() {
        val shelf = listOf(EqFilter(EqFilterType.LS, 100.0, 6.0, 0.7))
        val at60 = toneRmsThroughEq(60.0, 0.0, shelf)
        assertTrue(at60 > refRms(60.0) * 1.5, "60 Hz sube con low-shelf +6dB: $at60 vs ${refRms(60.0)}")
    }

    @Test
    fun emptyEqIsPassthrough() {
        val eq = ParametricEq().apply { configure(fs, 2, 0.0, emptyList()) }
        assertTrue(!eq.isActive())
        val buf = shortArrayOf(100, -200, 300, -400)
        val copy = buf.copyOf()
        eq.process(buf, buf.size)
        assertTrue(buf.contentEquals(copy), "sin filtros no cambia nada")
    }

    @Test
    fun autoEqParsesRealProfile() {
        val text = """
            Preamp: -6.7 dB
            Filter 1: ON PK Fc 105 Hz Gain 5.5 dB Q 0.70
            Filter 2: ON LSC Fc 25 Hz Gain 3.0 dB Q 0.70
            Filter 3: OFF PK Fc 200 Hz Gain 2.0 dB Q 1.0
            Filter 4: ON HSC Fc 10000 Hz Gain -2.5 dB Q 0.70
        """.trimIndent()
        val p = AutoEq.parse(text)
        assertEquals(-6.7, p.preampDb, 1e-9)
        assertEquals(3, p.filters.size, "los OFF se ignoran")
        assertEquals(EqFilterType.PK, p.filters[0].type)
        assertEquals(105.0, p.filters[0].freqHz, 1e-9)
        assertEquals(5.5, p.filters[0].gainDb, 1e-9)
        assertEquals(EqFilterType.LS, p.filters[1].type)
        assertEquals(EqFilterType.HS, p.filters[2].type)
        assertEquals(-2.5, p.filters[2].gainDb, 1e-9)
    }

    @Test
    fun crossfeedBleedsLeftIntoRight() {
        val cf = Crossfeed().apply { configure(fs, 2, 2) }
        assertTrue(cf.isActive())
        // Señal sólo en L (100 Hz), R en silencio.
        val n = 4410
        val buf = ShortArray(n * 2)
        for (i in 0 until n) buf[i * 2] = (sin(2 * PI * 100 * i / fs) * 10000).toInt().toShort()
        cf.process(buf, buf.size)
        var rEnergy = 0.0
        var lEnergy = 0.0
        for (i in 500 until n) {
            lEnergy += buf[i * 2].toDouble() * buf[i * 2]
            rEnergy += buf[i * 2 + 1].toDouble() * buf[i * 2 + 1]
        }
        assertTrue(rEnergy > 0.0, "R debe recibir sangrado de L")
        assertTrue(rEnergy < lEnergy, "el sangrado es atenuado: R=$rEnergy L=$lEnergy")
    }

    @Test
    fun crossfeedOffIsPassthrough() {
        val cf = Crossfeed().apply { configure(fs, 2, 0) }
        assertTrue(!cf.isActive())
        val buf = shortArrayOf(1000, 0, 2000, 0)
        val copy = buf.copyOf()
        cf.process(buf, buf.size)
        assertTrue(buf.contentEquals(copy))
    }

    @Test
    fun replayGainParsing() {
        assertEquals(-6.35f, ReplayGain.parseGainDb("-6.35 dB"))
        assertEquals(3.2f, ReplayGain.parseGainDb("+3.2 dB"))
        assertEquals(-6.35f, ReplayGain.parseGainDb("-6.35 dB (measured)"))
        assertEquals(null, ReplayGain.parseGainDb(""))
        assertEquals(null, ReplayGain.parseGainDb("nope"))
        assertEquals(-3f, ReplayGain.r128ToDb(-768))
        assertEquals(1f, ReplayGain.r128ToDb(256))
    }
}
