package com.aar.privatemusic.ui.screens

import android.content.Context
import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.downloader.ArlCheck
import com.aar.privatemusic.downloader.DeezerDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Login de Deezer dentro de la app: el usuario entra con email/contraseña o con
 * Google/Apple; al detectar la cookie de sesión `arl` la validamos, guardamos
 * el plan (FLAC/HQ) y cerramos. Como salida de emergencia, el usuario puede
 * pegar el ARL copiado de un navegador de escritorio ([DeezerArlDialog]).
 *
 * Desde ago-2026 `www.deezer.com/login` redirige a `account.deezer.com`, una
 * app protegida por Akamai Bot Manager + reCAPTCHA Enterprise para todos los
 * métodos. Esos sistemas cruzan el User-Agent con los Client Hints
 * (`Sec-CH-UA`, `navigator.userAgentData`) que el WebView envía con su versión
 * real: un UA inventado con un Chrome antiguo es justo lo que puntúan como bot.
 * Por eso el UA sale del WebView instalado, quitando sólo el token "wv" (que
 * Google bloquea en su OAuth) y el "Version/x.y" que delata un WebView.
 *
 * Para que el login con Google funcione hacen falta además: (1) manejar la
 * ventana emergente que abre el botón de Google (`window.open`) como un WebView
 * hijo por encima, conservando `window.opener` para que el OAuth pueda devolver
 * el resultado a la ventana principal, y (2) adjuntar ese hijo a la jerarquía
 * de vistas ANTES de `sendToTarget()`: si no, Chromium entrega la navegación a
 * una vista sin ventana y la emergente se queda en blanco. Por eso el WebView
 * vive en un FrameLayout y no en un AndroidView creado al recomponer.
 */
private const val TAG = "DeezerLogin"
private const val LOGIN_URL = "https://www.deezer.com/login"

/** Hosts donde Deezer puede dejar el `arl` (la config actual usa `.deezer.com`). */
private val COOKIE_HOSTS = listOf(
    "https://www.deezer.com",
    "https://account.deezer.com",
    "https://auth.deezer.com",
)

/** Sin ningún `onPageFinished` en este tiempo, el WebView no está cargando nada. */
private const val LOAD_TIMEOUT_MS = 20_000L

/** Tras llegar a la web-app ya logueado, cuánto esperar la cookie antes de pedirla por JS. */
private const val ARL_GRACE_MS = 4_000L

private fun browserUserAgent(context: Context): String =
    runCatching { WebSettings.getDefaultUserAgent(context) }
        .getOrDefault("")
        .replace("; wv", "")
        .replace(Regex("""Version/\d+(\.\d+)* """), "")
        .ifBlank {
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        }

private fun WebView.applyLoginSettings() {
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(this, true)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.userAgentString = browserUserAgent(context)
    // El botón de Google abre una ventana emergente.
    settings.setSupportMultipleWindows(true)
    settings.javaScriptCanOpenWindowsAutomatically = true
}

private fun readArlCookie(): String? {
    val jar = CookieManager.getInstance()
    return COOKIE_HOSTS.firstNotNullOfOrNull { host ->
        jar.getCookie(host)
            ?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("arl=") }
            ?.removePrefix("arl=")
            ?.takeIf { it.isNotBlank() }
    }
}

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
    // Último arl comprobado y cuándo: sin esto el sondeo machacaría la red validándolo.
    var checked by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // Lo que se le cuenta al usuario cuando algo falla; antes sólo iba al logcat.
    var error by remember { mutableStateOf<String?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    var arlDialog by remember { mutableStateOf(false) }

    // El arl que ya había al abrir (de una sesión anterior que no validó): al
    // iniciar sesión Deezer lo sustituye, y ese cambio es la señal de captura.
    val initialArl = remember { readArlCookie() }

    fun finish(arl: String, check: ArlCheck.Valid) {
        CookieManager.getInstance().flush()
        app.settings.setDeezerSession(arl, check.info.name, check.info.country, check.info.hasFlac, check.info.hasHq)
        onClose()
    }

    /**
     * Comprueba [candidate] (por defecto, la cookie); si es una sesión real
     * (USER_ID != 0), la guarda y cierra. Si no, y el arl es nuevo, lo dice.
     */
    suspend fun tryCaptureArl(candidate: String? = readArlCookie()) {
        if (busy) return
        val arl = candidate ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = checked
        // Revalida el mismo arl solo cada 5s (por si el fallo fue de red).
        if (last != null && last.first == arl && now - last.second < 5_000) return
        checked = arl to now
        busy = true
        val fresh = arl != initialArl
        // Tapar el formulario en cada reintento del arl viejo impediría
        // escribir: el overlay solo sale cuando la cookie ha cambiado.
        validating = fresh
        val result = withContext(Dispatchers.IO) { DeezerDownloader.checkArl(arl) }
        busy = false
        validating = false
        when (result) {
            is ArlCheck.Valid -> finish(arl, result)
            is ArlCheck.Rejected -> if (fresh) {
                Log.w(TAG, "arl nuevo pero Deezer no lo reconoce (USER_ID 0)")
                error = "Deezer no ha aceptado la sesión. Prueba otra vez o pega el ARL a mano."
            }
            is ArlCheck.Unreachable -> {
                Log.w(TAG, "no se pudo validar el arl: ${result.reason}", result.cause)
                if (fresh) error = "No se ha podido comprobar la sesión con Deezer (${result.reason})."
            }
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

    // Página que nunca llega: red caída, DNS, o un WebView cuyo renderer no arranca.
    LaunchedEffect(Unit) {
        delay(LOAD_TIMEOUT_MS)
        if (!pageLoaded && error == null) {
            Log.w(TAG, "ningún onPageFinished en ${LOAD_TIMEOUT_MS / 1000}s")
            error = "No se ha podido cargar la página de Deezer. Comprueba la conexión, o pega el ARL a mano."
        }
    }

    /**
     * Ya en la web-app de deezer.com con la sesión iniciada, el `arl` lo pone la
     * propia web-app al arrancar. Si tarda o no lo deja en la cookie, se lo
     * pedimos a gw-light desde dentro de la página (misma sesión, mismo `sid`).
     */
    fun requestArlViaJs(view: WebView) {
        scope.launch {
            delay(ARL_GRACE_MS)
            val current = readArlCookie()
            if (current != null && current != initialArl) return@launch
            val js = """
                (async () => {
                  try {
                    const r = await fetch('/ajax/gw-light.php?method=user.getArl&input=3&api_version=1.0&api_token=',
                      { method: 'POST', credentials: 'include', body: '{}' });
                    const j = await r.json();
                    return JSON.stringify({ arl: typeof j.results === 'string' ? j.results : '', err: j.error });
                  } catch (e) { return JSON.stringify({ arl: '', err: String(e) }); }
                })()
            """.trimIndent()
            view.evaluateJavascript(js) { raw ->
                val arl = runCatching {
                    // evaluateJavascript devuelve el string JSON entre comillas.
                    JSONObject(JSONObject("{\"v\":$raw}").getString("v")).optString("arl")
                }.getOrNull()
                if (!arl.isNullOrBlank()) {
                    Log.d(TAG, "arl obtenido por user.getArl")
                    scope.launch { tryCaptureArl(arl) }
                } else {
                    Log.d(TAG, "user.getArl sin arl: ${raw?.take(200)}")
                }
            }
        }
    }

    val pageWatcher = remember {
        object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val host = Uri.parse(url).host
                Log.d(TAG, "page: $host")
                pageLoaded = true
                scope.launch { tryCaptureArl() }
                if (host == "www.deezer.com" && !url.contains("/login")) requestArlViaJs(view)
            }

            /** Cambios de ruta de la SPA (pushState), que no disparan onPageFinished. */
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                scope.launch { tryCaptureArl() }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                err: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "error ${err.errorCode} en ${request.url.host}: ${err.description}")
                    error = "Deezer no responde (${err.description}). Comprueba la conexión."
                }
            }

            /** Sin query: ahí viajan tokens de sesión y del OAuth. */
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: android.webkit.WebResourceResponse,
            ) {
                if (response.statusCode >= 400) {
                    val u = request.url
                    Log.w(TAG, "http ${response.statusCode} ${request.method} ${u.host}${u.path}")
                    if (request.isForMainFrame) {
                        error = "Deezer ha devuelto un error ${response.statusCode}. Si se repite, pega el ARL a mano."
                    } else if (u.host == "handshake.deezer.com" && response.statusCode == 403) {
                        // El antibot de Deezer ha rechazado este WebView: reintentar no ayuda.
                        error = "Deezer ha bloqueado el inicio de sesión desde la app. Pega el ARL a mano."
                    }
                }
            }
        }
    }

    /** Los errores de consola son los que delatan por qué Deezer aborta el login. */
    fun logConsole(tag: String, msg: ConsoleMessage): Boolean {
        if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            Log.w(TAG, "$tag js: ${msg.message().take(300)}")
        }
        return true
    }

    // El WebView principal vive aquí; la emergente se añade encima, ya adjunta.
    val container = remember { FrameLayout(context) }

    /**
     * Nunca destruyas el WebView dentro de un callback suyo: `onCloseWindow`
     * llega mientras la emergente todavía está entregando el resultado del
     * OAuth al `opener` por postMessage, y arrasarla ahí deja a Deezer sin
     * respuesta ("Se ha producido un error"). Lo aplazamos a la cola de la
     * vista, cuando Chromium ya ha soltado la ventana.
     */
    fun dismissPopup(child: WebView) {
        popup = null
        child.post {
            container.removeView(child)
            child.destroy()
        }
    }

    /** Chrome client de la emergente: Google la cierra sola al terminar el OAuth. */
    fun popupChrome() = object : WebChromeClient() {
        override fun onCloseWindow(window: WebView) {
            dismissPopup(window)
            scope.launch { tryCaptureArl() }
        }

        override fun onConsoleMessage(msg: ConsoleMessage) = logConsole("popup", msg)
    }

    val mainChrome = remember {
        object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val child = WebView(context).apply {
                    applyLoginSettings()
                    webViewClient = pageWatcher
                    webChromeClient = popupChrome()
                }
                // Adjuntar ANTES de sendToTarget: si no, la emergente no pinta.
                container.addView(
                    child,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                popup = child
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                // Por si el cierre llega al padre en vez de a la emergente.
                popup?.let { dismissPopup(it) }
                scope.launch { tryCaptureArl() }
            }

            override fun onConsoleMessage(msg: ConsoleMessage) = logConsole("main", msg)
        }
    }

    val webView = remember {
        WebView(context).apply {
            applyLoginSettings()
            webViewClient = pageWatcher
            webChromeClient = mainChrome
            Log.d(TAG, "ua: ${settings.userAgentString}")
            loadUrl(LOGIN_URL)
        }
    }

    DisposableEffect(Unit) {
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        onDispose {
            // Aquí sí destruimos ya: el diálogo se va y no hay callback en curso.
            val child = popup
            popup = null
            container.removeAllViews()
            child?.destroy()
            webView.destroy()
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
                        if (popup != null) "Continuar" else "Iniciar sesión en Deezer",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { arlDialog = true }) { Text("Pegar ARL") }
                    IconButton(onClick = { popup?.let { dismissPopup(it) } ?: onClose() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cerrar")
                    }
                }
                error?.let { msg ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            error = null
                            pageLoaded = false
                            checked = null
                            webView.loadUrl(LOGIN_URL)
                        }) { Text("Reintentar") }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { container })

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

    if (arlDialog) {
        DeezerArlDialog(app, onDismiss = { arlDialog = false }, onDone = onClose)
    }
}

/**
 * Vía manual: el usuario entra en deezer.com desde un navegador de escritorio y
 * pega aquí la cookie `arl`. Es la salida cuando el WebView no puede con el
 * antibot de Deezer o ni siquiera arranca.
 */
@Composable
fun DeezerArlDialog(app: PrivateMusicApp, onDismiss: () -> Unit, onDone: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text("Pegar ARL de Deezer") },
        text = {
            Column {
                Text(
                    "Abre deezer.com en el navegador de tu ordenador, entra en tu cuenta y " +
                        "copia el valor de la cookie «arl» (F12 → Aplicación → Cookies → " +
                        "deezer.com). Es la sesión de tu cuenta: no la compartas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    modifier = Modifier.padding(top = 16.dp),
                    label = { Text("Cookie arl") },
                    singleLine = true,
                    enabled = !checking,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                )
                error?.let {
                    Text(
                        it,
                        Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = value.isNotBlank() && !checking,
                onClick = {
                    checking = true
                    error = null
                    val arl = value.trim().removePrefix("arl=")
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { DeezerDownloader.checkArl(arl) }
                        checking = false
                        when (result) {
                            is ArlCheck.Valid -> {
                                app.settings.setDeezerSession(
                                    arl, result.info.name, result.info.country,
                                    result.info.hasFlac, result.info.hasHq,
                                )
                                onDismiss()
                                onDone()
                            }
                            is ArlCheck.Rejected -> error = "Ese ARL no vale (¿caducado, o mal copiado?)"
                            is ArlCheck.Unreachable -> error = "No se ha podido contactar con Deezer: ${result.reason}"
                        }
                    }
                },
            ) {
                if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Comprobar y guardar")
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !checking) { Text("Cancelar") } },
    )
}
