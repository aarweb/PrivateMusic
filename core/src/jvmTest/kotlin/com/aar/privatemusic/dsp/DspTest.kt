package com.aar.privatemusic.dsp

import kotlin.math.PI
import kotlin.math.abs
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
    fun graphicEqBandConvertsAndroidUnitsToPeakFilter() {
        val filter = graphicEqFilter(
            centerFrequencyMilliHz = 1_000_000,
            lowerFrequencyMilliHz = 800_000,
            upperFrequencyMilliHz = 1_200_000,
            levelMilliBel = 350,
        )

        assertEquals(EqFilterType.PK, filter.type)
        assertEquals(1000.0, filter.freqHz, 1e-9)
        assertEquals(3.5, filter.gainDb, 1e-9)
        assertEquals(2.5, filter.q, 1e-9)
    }

    @Test
    fun graphicEqBandUsesSafeQForInvalidOrExtremeRanges() {
        val invalid = graphicEqFilter(100_000, 200_000, 100_000, (-125).toShort())
        val narrow = graphicEqFilter(1_000_000, 999_999, 1_000_000, 0)

        assertEquals(100.0, invalid.freqHz, 1e-9)
        assertEquals(-1.25, invalid.gainDb, 1e-9)
        assertEquals(1.0, invalid.q, 1e-9)
        assertEquals(10.0, narrow.q, 1e-9)
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
    fun stereoWidenerOffIsBitExactPassthrough() {
        val widener = StereoWidener().apply { configure(fs, 2, 0) }
        val buf = shortArrayOf(32767, -32768, 1234, -5678, 0, 1)
        val copy = buf.copyOf()

        widener.process(buf, buf.size)

        assertTrue(!widener.isActive())
        assertTrue(buf.contentEquals(copy))
    }

    @Test
    fun stereoWidenerMonoIsBitExactPassthrough() {
        val widener = StereoWidener().apply { configure(fs, 1, 3) }
        val buf = shortArrayOf(32767, -32768, 1234, -5678, 0)
        val copy = buf.copyOf()

        widener.process(buf, buf.size)

        assertTrue(!widener.isActive())
        assertTrue(buf.contentEquals(copy))
    }

    @Test
    fun stereoWidenerSideEnergyGrowsWithLevel() {
        fun sideEnergy(level: Int): Double {
            val frames = fs / 2
            val buf = ShortArray(frames * 2)
            for (frame in 0 until frames) {
                val side = (sin(2 * PI * 2000.0 * frame / fs) * 8000).toInt().toShort()
                buf[frame * 2] = side
                buf[frame * 2 + 1] = (-side.toInt()).toShort()
            }
            StereoWidener().apply { configure(fs, 2, level) }.process(buf, buf.size)
            var energy = 0.0
            for (frame in 2000 until frames) {
                val difference = buf[frame * 2].toDouble() - buf[frame * 2 + 1]
                energy += difference * difference
            }
            return energy
        }

        val soft = sideEnergy(1)
        val medium = sideEnergy(2)
        val strong = sideEnergy(3)
        assertTrue(soft < medium, "nivel suave=$soft debe quedar por debajo de medio=$medium")
        assertTrue(medium < strong, "nivel medio=$medium debe quedar por debajo de fuerte=$strong")
    }

    @Test
    fun stereoWidenerPreservesMonoSumWithoutLimiting() {
        val frames = fs / 4
        val buf = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            val mid = sin(2 * PI * 440.0 * frame / fs) * 5000
            val side = sin(2 * PI * 3000.0 * frame / fs) * 4000
            buf[frame * 2] = (mid + side).toInt().toShort()
            buf[frame * 2 + 1] = (mid - side).toInt().toShort()
        }
        val original = buf.copyOf()

        StereoWidener().apply { configure(fs, 2, 3) }.process(buf, buf.size)

        for (frame in 0 until frames) {
            val before = original[frame * 2].toInt() + original[frame * 2 + 1].toInt()
            val after = buf[frame * 2].toInt() + buf[frame * 2 + 1].toInt()
            assertTrue(abs(after - before) <= 1, "la suma mono cambia en el frame $frame: $before -> $after")
        }
    }

    @Test
    fun stereoWidenerFullScaleUsesCommonPeakProtectionWithoutWrap() {
        val buf = ShortArray(256 * 2) { index ->
            if (index % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
        }

        StereoWidener().apply { configure(fs, 2, 3) }.process(buf, buf.size)

        for (frame in 0 until 256) {
            val left = buf[frame * 2].toInt()
            val right = buf[frame * 2 + 1].toInt()
            assertTrue(left > 0 && right < 0, "no debe haber wrap: L=$left R=$right")
            assertTrue(abs(abs(left) - abs(right)) <= 1, "el escalado común conserva la simetría: L=$left R=$right")
        }
    }

    @Test
    fun stereoWidenerExpandsLowSideFrequenciesLessThanHighOnes() {
        fun sideGain(freq: Double): Double {
            val frames = fs
            val buf = ShortArray(frames * 2)
            var inputEnergy = 0.0
            for (frame in 0 until frames) {
                val side = (sin(2 * PI * freq * frame / fs) * 6000).toInt()
                buf[frame * 2] = side.toShort()
                buf[frame * 2 + 1] = (-side).toShort()
                if (frame >= 4000) inputEnergy += (side * 2.0) * (side * 2.0)
            }
            StereoWidener().apply { configure(fs, 2, 3) }.process(buf, buf.size)
            var outputEnergy = 0.0
            for (frame in 4000 until frames) {
                val difference = buf[frame * 2].toDouble() - buf[frame * 2 + 1]
                outputEnergy += difference * difference
            }
            return sqrt(outputEnergy / inputEnergy)
        }

        val bassGain = sideGain(50.0)
        val trebleGain = sideGain(4000.0)
        assertTrue(
            trebleGain > bassGain + 0.15,
            "los graves laterales deben ensancharse menos: graves=$bassGain agudos=$trebleGain",
        )
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
