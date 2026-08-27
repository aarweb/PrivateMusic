package com.aar.privatemusic.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    data class UpdatePrompt(
        val info: AppUpdater.UpdateInfo,
        val downloaded: Boolean,
        val downloading: Boolean = false,
    )

    private const val PREFS = "settings"
    private const val KEY_AUTO = "auto_update"

    /** Una vez por proceso. Volver de segundo plano no es "abrir la app de 0". */
    private val checked = AtomicBoolean(false)
    private val downloadMutex = Mutex()
    private val _prompt = MutableStateFlow<UpdatePrompt?>(null)
    val prompt = _prompt.asStateFlow()

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

            val downloaded = AppUpdater.hasCached(context, info.version)
            _prompt.value = UpdatePrompt(info, downloaded)
            if (downloaded) return@launch
            if (!autoUpdate(context)) {
                return@launch
            }
            // Noventa megas por datos móviles sin avisar es una factura, no una
            // actualización. Con Wi-Fi se baja sola; sin él, se avisa y espera.
            if (!isUnmetered(context)) {
                return@launch
            }
            ensureDownloaded(context, info)
        }
    }

    /** Oculta el aviso hasta que termine este proceso de la app. */
    fun consumePrompt() {
        _prompt.value = null
    }

    /** Descarga si hace falta y abre el instalador, sin competir con la descarga automática. */
    fun confirmPrompt(context: Context, scope: CoroutineScope) {
        val current = _prompt.value ?: return
        scope.launch {
            val ready = ensureDownloaded(context, current.info)
            if (ready && AppUpdater.installCached(context)) consumePrompt()
        }
    }

    private suspend fun ensureDownloaded(
        context: Context,
        info: AppUpdater.UpdateInfo,
    ): Boolean = downloadMutex.withLock {
        if (AppUpdater.hasCached(context, info.version)) {
            updatePrompt(info.version, downloaded = true, downloading = false)
            return@withLock true
        }

        updatePrompt(info.version, downloaded = false, downloading = true)
        val ok = AppUpdater.download(context, info.apkUrl, info.version) {}
        updatePrompt(info.version, downloaded = ok, downloading = false)
        ok
    }

    private fun updatePrompt(version: String, downloaded: Boolean, downloading: Boolean) {
        val current = _prompt.value ?: return
        if (current.info.version == version) {
            _prompt.value = current.copy(downloaded = downloaded, downloading = downloading)
        }
    }

    private fun isUnmetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)
}
