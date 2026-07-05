package com.aar.privatemusic.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent

/**
 * Compose-native Cast route picker (the stock MediaRouteButton needs a
 * FragmentActivity, which a Compose app doesn't have).
 */
@Composable
fun CastRouteDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val router = remember { MediaRouter.getInstance(context) }
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                )
            )
            .build()
    }
    var routes by remember { mutableStateOf(listOf<MediaRouter.RouteInfo>()) }
    var selectedId by remember { mutableStateOf(router.selectedRoute.id) }

    DisposableEffect(Unit) {
        val callback = object : MediaRouter.Callback() {
            private fun refresh() {
                routes = router.routes.filter { it.matchesSelector(selector) && !it.isDefault }
                selectedId = router.selectedRoute.id
            }
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
            override fun onRouteUnselected(r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        }
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        routes = router.routes.filter { it.matchesSelector(selector) && !it.isDefault }
        onDispose { router.removeCallback(callback) }
    }

    val casting = routes.any { it.id == selectedId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enviar a dispositivo") },
        text = {
            Column {
                if (routes.isEmpty()) {
                    Text(
                        "Buscando dispositivos en tu red…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                routes.forEach { route ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                router.selectRoute(route)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (route.id == selectedId) Icons.Filled.CastConnected else Icons.Filled.Tv,
                            contentDescription = null,
                            tint = if (route.id == selectedId) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            route.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (route.id == selectedId) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (casting) {
                TextButton(onClick = {
                    router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
                    onDismiss()
                }) { Text("Dejar de enviar") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
