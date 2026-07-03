package com.aar.privatemusic.player

import android.content.Context
import android.media.audiofx.Equalizer
import com.aar.privatemusic.data.AppSettings

/**
 * System Equalizer attached to the ExoPlayer audio session.
 * Lives in the playback service process; the settings UI drives it directly.
 */
object EqHolder {

    var equalizer: Equalizer? = null
        private set

    fun init(context: Context, audioSessionId: Int) {
        runCatching {
            equalizer?.release()
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = AppSettings.readEqEnabled(context)
                AppSettings.readEqBands(context)?.let { saved ->
                    saved.forEachIndexed { i, level ->
                        if (i < numberOfBands) runCatching { setBandLevel(i.toShort(), level) }
                    }
                }
            }
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        equalizer?.enabled = value
        AppSettings.writeEqEnabled(context, value)
    }

    fun setBand(context: Context, band: Int, level: Short) {
        equalizer?.let {
            runCatching { it.setBandLevel(band.toShort(), level) }
            saveBands(context)
        }
    }

    fun usePreset(context: Context, preset: Short) {
        equalizer?.let {
            runCatching { it.usePreset(preset) }
            saveBands(context)
        }
    }

    private fun saveBands(context: Context) {
        val eq = equalizer ?: return
        val levels = (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()) }
        AppSettings.writeEqBands(context, levels)
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
    }
}
