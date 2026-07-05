package com.aar.privatemusic.ui.screens

import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
 * Login de Deezer dentro de la app: el usuario entra con email/contraseña o con
 * Google/Apple; al detectar la cookie de sesión `arl` la validamos, guardamos
 * el plan (FLAC/HQ) y cerramos. El usuario nunca ve ni maneja el ARL.
 *
 * Para que el login con Google funcione hacen falta dos cosas: (1) un
 * User-Agent de navegador normal (sin el token "wv", que Google bloquea en
 * WebViews), y (2) manejar la ventana emergente que abre el botón de Google
 * (`window.open`) como un WebView hijo por encima, conservando `window.opener`
 * para que el OAuth pueda devolver el resultado a la ventana principal.
 */
private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

@Composable
fun DeezerLoginDialog(app: PrivateMusicApp, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var validating by remember { mutableStateOf(false) }
    // WebView emergente (p.ej. el OAuth de Google) mostrado por encima del principal.
    var popup by remember { mutableStateOf<WebView?>(null) }

    // Lee la cookie arl; si es de una sesión real (USER_ID != 0), la guarda y cierra.
    fun tryCaptureArl() {
        if (validating) return
        val cookies = CookieManager.getInstance()
        cookies.flush()
        val arl = cookies.getCookie("https://www.deezer.com")
            ?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("arl=") }
            ?.removePrefix("arl=")
            ?.takeIf { it.isNotBlank() }
            ?: return
        validating = true
        scope.launch {
            val info = withContext(Dispatchers.IO) { DeezerDownloader.fetchUserInfo(context, arl) }
            if (info != null) {
                app.settings.setDeezerSession(arl, info.name, info.country, info.hasFlac, info.hasHq)
                onClose()
            } else {
                validating = false // arl aún de invitado: seguir esperando al login real
            }
        }
    }

    val pageWatcher = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            Log.d("DeezerLogin", "page: ${Uri.parse(url).host}")
            tryCaptureArl()
        }
    }

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
                                settings.userAgentString = BROWSER_UA
                                // El botón de Google abre una ventana emergente.
                                settings.setSupportMultipleWindows(true)
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                webViewClient = pageWatcher
                                webChromeClient = object : WebChromeClient() {
                                    override fun onCreateWindow(
                                        view: WebView,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message,
                                    ): Boolean {
                                        val child = WebView(ctx).apply {
                                            cookies.setAcceptThirdPartyCookies(this, true)
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.userAgentString = BROWSER_UA
                                            settings.setSupportMultipleWindows(true)
                                            settings.javaScriptCanOpenWindowsAutomatically = true
                                            webViewClient = pageWatcher
                                        }
                                        popup = child
                                        (resultMsg.obj as WebView.WebViewTransport).webView = child
                                        resultMsg.sendToTarget()
                                        return true
                                    }

                                    override fun onCloseWindow(window: WebView) {
                                        // El OAuth cerró la emergente: recoge la sesión ya establecida.
                                        popup = null
                                        tryCaptureArl()
                                    }
                                }
                                loadUrl("https://www.deezer.com/login")
                            }
                        },
                    )

                    // Emergente de OAuth (Google/Apple) por encima, con opción de cerrar.
                    popup?.let { child ->
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Continuar",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { popup = null }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Cerrar")
                                    }
                                }
                                AndroidView(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    factory = { child },
                                )
                            }
                        }
                    }

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
