package com.aar.privatemusic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aar.privatemusic.data.ThemeMode
import com.aar.privatemusic.hilight.HiLightStatus
import com.aar.privatemusic.ui.screens.HiLightSettingContent
import com.aar.privatemusic.ui.theme.PrivateMusicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HiLightSettingTest {
    @get:Rule val compose = createComposeRule()
    private var toggled: Boolean? = null
    private var requests = 0

    private fun show(status: HiLightStatus, enabled: Boolean = false) {
        compose.setContent {
            PrivateMusicTheme(themeMode = ThemeMode.LIGHT) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.statusBarsPadding().padding(16.dp)) {
                        HiLightSettingContent(status, enabled, { toggled = it }, { requests++ }, {}, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val directory = instrumentation.targetContext.getExternalFilesDir("hilight-qa")!!
        java.io.File(directory, "${status.name}-${enabled}.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun unsupportedPhoneHidesOption() {
        show(HiLightStatus.UNSUPPORTED)
        compose.onNodeWithText("HiLight al reproducir").assertDoesNotExist()
    }

    @Test fun permissionMustBeExplicitBeforeEnabling() {
        show(HiLightStatus.NEEDS_PERMISSION)
        compose.onNodeWithContentDescription("HiLight al reproducir").assertIsNotEnabled()
        compose.onNodeWithText("Autorizar acceso").performClick()
        assertEquals(1, requests)
        assertEquals(null, toggled)
    }

    @Test fun missingShizukuDisablesOption() {
        show(HiLightStatus.NEEDS_SHIZUKU)
        compose.onNodeWithContentDescription("HiLight al reproducir").assertIsNotEnabled()
        compose.onNodeWithText("Configurar Shizuku").assertIsEnabled()
    }

    @Test fun verifiedAccessAllowsOptIn() {
        show(HiLightStatus.READY)
        compose.onNodeWithContentDescription("HiLight al reproducir").assertIsEnabled().performClick()
        assertEquals(true, toggled)
    }

    @Test fun lostAccessStillAllowsTurningSavedPreferenceOff() {
        show(HiLightStatus.NEEDS_SHIZUKU, enabled = true)
        compose.onNodeWithContentDescription("HiLight al reproducir").assertIsOn().assertIsEnabled().performClick()
        assertEquals(false, toggled)
    }
}
