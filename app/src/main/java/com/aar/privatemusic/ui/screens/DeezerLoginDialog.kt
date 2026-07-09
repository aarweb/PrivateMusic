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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
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
    // Overlay opaco: solo cuando comprobamos un arl recién aparecido.
    var validating by remember { mutableStateOf(false) }
    // WebView emergente (p.ej. el OAuth de Google) mostrado por encima del principal.
    var popup by remember { mutableStateOf<WebView?>(null) }
    // Comprobación en curso, sin UI: evita peticiones solapadas del sondeo.
    var busy by remember { mutableStateOf(false) }
    // Último arl comprobado y cuándo: Deezer pone un arl de invitado antes de
    // iniciar sesión, así que sin esto el sondeo machacaría la red validándolo.
    var checked by remember { mutableStateOf<Pair<String, Long>?>(null) }

    fun readArl(): String? =
        CookieManager.getInstance().getCookie("https://www.deezer.com")
            ?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("arl=") }
            ?.removePrefix("arl=")
            ?.takeIf { it.isNotBlank() }

    // El arl que ya había al abrir (de invitado o de una sesión anterior): al
    // iniciar sesión Deezer lo sustituye, y ese cambio es la señal de captura.
    val initialArl = remember { readArl() }

    // Lee la cookie arl; si es de una sesión real (USER_ID != 0), la guarda y cierra.
    suspend fun tryCaptureArl() {
        if (busy) return
        val arl = readArl() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = checked
        // Revalida el mismo arl solo cada 5s (por si el fallo fue de red).
        if (last != null && last.first == arl && now - last.second < 5_000) return
        checked = arl to now
        busy = true
        // Tapar el formulario en cada reintento del arl de invitado impediría
        // escribir: el overlay solo sale cuando la cookie ha cambiado.
        validating = arl != initialArl
        val info = withContext(Dispatchers.IO) { DeezerDownloader.fetchUserInfo(context, arl) }
        if (info != null) {
            CookieManager.getInstance().flush()
            app.settings.setDeezerSession(arl, info.name, info.country, info.hasFlac, info.hasHq)
            onClose()
        } else {
            // arl aún de invitado: seguir esperando al login real
            busy = false
            validating = false
        }
    }

    // Deezer termina el login con navegación de SPA (y el OAuth de Google en una
    // emergente): ahí onPageFinished ya no vuelve a dispararse, así que sin este
    // sondeo el diálogo se quedaba abierto sobre la web-app (pantalla en blanco).
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tryCaptureArl()
        }
    }

    val pageWatcher = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            Log.d("DeezerLogin", "page: ${Uri.parse(url).host}")
            scope.launch { tryCaptureArl() }
        }

        /** Cambios de ruta de la SPA (pushState), que no disparan onPageFinished. */
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            scope.launch { tryCaptureArl() }
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
                                        scope.launch { tryCaptureArl() }
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
                        // Opaco a propósito: la web-app de Deezer queda detrás y en
                        // WebView se ve en blanco mientras validamos la sesión.
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Text(
                                        "Validando sesión…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
