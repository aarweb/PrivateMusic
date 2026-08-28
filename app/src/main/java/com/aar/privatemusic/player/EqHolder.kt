package com.aar.privatemusic.player

import android.content.Context
import android.media.audiofx.Equalizer
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.dsp.EqFilter
import com.aar.privatemusic.dsp.graphicEqFilter

/**
 * Equalizer del dispositivo usado únicamente para descubrir y editar sus bandas
 * y presets. El procesamiento se realiza en [AudioDsp] para ambos reproductores.
 */
object EqHolder {

    var equalizer: Equalizer? = null
        private set

    fun init(context: Context, audioSessionId: Int) {
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null
        runCatching {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = false
                AppSettings.readEqBands(context)?.let { saved ->
                    saved.forEachIndexed { i, level ->
                        if (i < numberOfBands) runCatching { setBandLevel(i.toShort(), level) }
                    }
                }
            }
        }
        AudioDsp.reload(context)
    }

    fun setEnabled(context: Context, value: Boolean) {
        runCatching { equalizer?.enabled = false }
        AppSettings.writeEqEnabled(context, value)
        AudioDsp.reload(context)
    }

    fun applySavedProfile(context: Context) {
        val eq = equalizer
        runCatching { eq?.enabled = false }
        runCatching {
            if (eq != null) {
                val saved = AppSettings.readEqBands(context)
                repeat(eq.numberOfBands.toInt()) { index ->
                    eq.setBandLevel(index.toShort(), saved?.getOrNull(index) ?: 0.toShort())
                }
            }
        }
        runCatching { eq?.enabled = false }
        AudioDsp.reload(context)
    }

    fun setBand(context: Context, band: Int, level: Short) {
        equalizer?.let {
            runCatching { it.setBandLevel(band.toShort(), level) }
            saveBands(context)
            AudioDsp.reload(context)
        }
    }

    fun usePreset(context: Context, preset: Short) {
        equalizer?.let {
            runCatching { it.usePreset(preset) }
            saveBands(context)
            AudioDsp.reload(context)
        }
    }

    fun filters(): List<EqFilter> {
        val eq = equalizer ?: return emptyList()
        return (0 until eq.numberOfBands).mapNotNull { band ->
            runCatching {
                val index = band.toShort()
                val range = eq.getBandFreqRange(index)
                graphicEqFilter(
                    centerFrequencyMilliHz = eq.getCenterFreq(index),
                    lowerFrequencyMilliHz = range.getOrElse(0) { 0 },
                    upperFrequencyMilliHz = range.getOrElse(1) { 0 },
                    levelMilliBel = eq.getBandLevel(index),
                )
            }.getOrNull()
        }
    }

    private fun saveBands(context: Context) {
        val eq = equalizer ?: return
        val levels = (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()) }
        AppSettings.writeEqBands(context, levels)
    }

    fun release() {
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null
    }
}
