package com.aar.privatemusic.ui.screens

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.downloader.DeezerDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Login de Deezer dentro de la app: el usuario entra con su email/contraseña
 * normales; al detectar la cookie de sesión `arl` la validamos, guardamos el
 * plan (FLAC/HQ) y cerramos. El usuario nunca ve ni maneja el ARL.
 */
@Composable
fun DeezerLoginDialog(app: PrivateMusicApp, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var validating by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Iniciar sesión en Deezer",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Cerrar")
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val cookies = CookieManager.getInstance()
                            cookies.setAcceptCookie(true)
                            WebView(ctx).apply {
                                cookies.setAcceptThirdPartyCookies(this, true)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, url: String) {
                                        if (validating) return
                                        cookies.flush()
                                        val arl = cookies.getCookie("https://www.deezer.com")
                                            ?.split(";")?.map { it.trim() }
                                            ?.firstOrNull { it.startsWith("arl=") }
                                            ?.removePrefix("arl=")
                                            ?.takeIf { it.isNotBlank() }
                                            ?: return
                                        // arl presente: validarlo (USER_ID != 0) y guardar.
                                        validating = true
                                        scope.launch {
                                            val info = withContext(Dispatchers.IO) {
                                                DeezerDownloader.fetchUserInfo(context, arl)
                                            }
                                            if (info != null) {
                                                app.settings.setDeezerSession(
                                                    arl, info.name, info.country, info.hasFlac, info.hasHq,
                                                )
                                                onClose()
                                            } else {
                                                // arl aún de invitado: sigue esperando al login real.
                                                validating = false
                                            }
                                        }
                                    }
                                }
                                loadUrl("https://www.deezer.com/login")
                            }
                        },
                    )
                    if (validating) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
