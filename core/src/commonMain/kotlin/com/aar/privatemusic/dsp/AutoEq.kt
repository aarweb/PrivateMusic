package com.aar.privatemusic.dsp

/** Un perfil AutoEQ: preamplificador (dB) y sus filtros. */
data class AutoEqProfile(val preampDb: Double, val filters: List<EqFilter>)

/**
 * Lee el formato `ParametricEQ.txt` de la base de datos AutoEQ:
 *
 *   Preamp: -6.7 dB
 *   Filter 1: ON PK Fc 105 Hz Gain 5.5 dB Q 0.70
 *   Filter 2: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
 *
 * Tipos: PK (peaking), LS/LSC (low shelf), HS/HSC (high shelf). Los filtros
 * `OFF` y los tipos desconocidos se ignoran.
 */
object AutoEq {
    private val preampRe = Regex("""Preamp:\s*(-?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
    private val filterRe = Regex(
        """Filter\s+\d+:\s+ON\s+(\w+)\s+Fc\s+(-?\d+(?:\.\d+)?)\s*Hz\s+Gain\s+(-?\d+(?:\.\d+)?)\s*dB\s+Q\s+(-?\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): AutoEqProfile {
        var preamp = 0.0
        val filters = mutableListOf<EqFilter>()
        for (line in text.lineSequence()) {
            preampRe.find(line)?.let { preamp = it.groupValues[1].toDouble() }
            val m = filterRe.find(line) ?: continue
            val type = when (m.groupValues[1].uppercase()) {
                "PK", "PEQ" -> EqFilterType.PK
                "LS", "LSC", "LSQ" -> EqFilterType.LS
                "HS", "HSC", "HSQ" -> EqFilterType.HS
                else -> continue
            }
            val fc = m.groupValues[2].toDouble()
            val gain = m.groupValues[3].toDouble()
            val q = m.groupValues[4].toDouble()
            filters.add(EqFilter(type, fc, gain, q))
        }
        return AutoEqProfile(preamp, filters)
    }
}
