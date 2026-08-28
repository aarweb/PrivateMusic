package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioOutputClassifierTest {
    @Test fun `stereo PCM is PCM and not Atmos`() {
        val result = AudioOutputClassifier.resolve(
            AudioInputInfo("audio/flac", 2, 48_000, 900_000),
            AudioTrackInfo(2, 12, offload = false, pcmEncoding = true),
        )
        assertEquals(AudioOutputKind.PCM, result.outputKind)
        assertEquals(2, result.input?.channelCount)
        assertFalse(result.atmos)
    }

    @Test fun `multichannel PCM keeps channel metadata`() {
        val result = AudioOutputClassifier.resolve(
            AudioInputInfo("audio/raw", 6, 96_000, null),
            AudioTrackInfo(2, 252, offload = false, pcmEncoding = true),
        )
        assertEquals(AudioOutputKind.PCM, result.outputKind)
        assertEquals(6, result.input?.channelCount)
    }

    @Test fun `EAC3 JOC passthrough is the only Atmos inference`() {
        val result = AudioOutputClassifier.resolve(
            AudioInputInfo(AudioOutputClassifier.E_AC3_JOC_MIME, 6, 48_000, 768_000),
            AudioTrackInfo(18, 252, offload = false, pcmEncoding = false),
        )
        assertEquals(AudioOutputKind.PASSTHROUGH, result.outputKind)
        assertTrue(result.atmos)
    }

    @Test fun `offload wins over encoded classification`() {
        val result = AudioOutputClassifier.resolve(
            AudioInputInfo("audio/eac3", 6, 48_000, 768_000),
            AudioTrackInfo(6, 252, offload = true, pcmEncoding = false),
        )
        assertEquals(AudioOutputKind.OFFLOAD, result.outputKind)
        assertFalse(result.atmos)
    }

    @Test fun `unknown format stays honest`() {
        val result = AudioOutputClassifier.resolve(
            AudioInputInfo(null, null, null, null),
            AudioTrackInfo(0, 0, offload = false, pcmEncoding = false),
        )
        assertEquals(AudioOutputKind.UNKNOWN, result.outputKind)
        assertFalse(result.atmos)
    }
}
