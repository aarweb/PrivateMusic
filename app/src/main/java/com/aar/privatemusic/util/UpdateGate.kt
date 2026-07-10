package com.aar.privatemusic.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Comprueba si hay versión nueva **al abrir la app en frío**, no sólo cuando se
 * entra en Ajustes: una actualización que hay que ir a buscar no se instala.
 *
 * "Actualizar automáticamente" no puede significar instalar solo: Android exige
 * que el usuario confirme cada APK en la pantalla del sistema. Lo que sí puede
 * hacer, y hace, es tenerla descargada para que instalar sea un toque.
 */
object UpdateGate {

    private const val PREFS = "settings"
    private const val KEY_AUTO = "auto_update"

    /** Una vez por proceso. Volver de segundo plano no es "abrir la app de 0". */
    private val checked = AtomicBoolean(false)

    fun autoUpdate(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoUpdate(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_AUTO, enabled) }
    }

    fun checkOnStart(context: Context, scope: CoroutineScope) {
        if (!checked.compareAndSet(false, true)) return
        scope.launch {
            val info = AppUpdater.check() ?: return@launch
            if (!info.isNewer) return@launch

            if (AppUpdater.hasCached(context, info.version)) {
                Feedback.show("PrivateMusic ${info.version} está lista: Ajustes › Actualizar")
                return@launch
            }
            if (!autoUpdate(context)) {
                Feedback.show("Hay una versión nueva: ${info.version}")
                return@launch
            }
            // Noventa megas por datos móviles sin avisar es una factura, no una
            // actualización. Con Wi-Fi se baja sola; sin él, se avisa y espera.
            if (!isUnmetered(context)) {
                Feedback.show("PrivateMusic ${info.version} se descargará al conectar a Wi-Fi")
                return@launch
            }
            val ok = AppUpdater.download(context, info.apkUrl, info.version) {}
            if (ok) Feedback.show("PrivateMusic ${info.version} descargada: Ajustes › Actualizar")
        }
    }

    private fun isUnmetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)
}
