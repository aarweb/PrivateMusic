package com.aar.privatemusic

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.aar.privatemusic.downloader.SpotifyResolver
import com.aar.privatemusic.util.UpdateGate
import com.aar.privatemusic.widget.ACTION_PLAY_DAILY_MIX
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aar.privatemusic.ui.components.MiniPlayer
import com.aar.privatemusic.ui.screens.ArtistScreen
import com.aar.privatemusic.ui.screens.AutoPlaylistScreen
import com.aar.privatemusic.ui.screens.AutoPlaylistType
import com.aar.privatemusic.ui.screens.EqScreen
import com.aar.privatemusic.ui.screens.HomeScreen
import com.aar.privatemusic.ui.screens.LibraryScreen
import com.aar.privatemusic.ui.screens.PlayerScreen
import com.aar.privatemusic.ui.screens.PlaylistDetailScreen
import com.aar.privatemusic.ui.screens.PlaylistsScreen
import com.aar.privatemusic.ui.screens.QueueScreen
import com.aar.privatemusic.ui.screens.SearchScreen
import com.aar.privatemusic.ui.screens.SettingsScreen
import com.aar.privatemusic.ui.screens.SmartPlaylistDetailScreen
import com.aar.privatemusic.ui.screens.StatsScreen
import com.aar.privatemusic.ui.theme.PrivateMusicTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as PrivateMusicApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleShareIntent(intent)
        // Al abrir en frío, no sólo al entrar en Ajustes. Cuelga de `appScope`:
        // girar la pantalla no debe cancelar una descarga de noventa megas.
        UpdateGate.checkOnStart(this, app.appScope)

        setContent {
            val themeMode by app.settings.themeMode.collectAsState()
            PrivateMusicTheme(themeMode) {
                MainScaffold(app)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                Regex("""https?://\S+""").find(text)?.value?.let { url ->
                    when {
                        "youtu" in url -> app.downloader.enqueueUrl(url)
                        SpotifyResolver.isSpotifyUrl(url) -> app.appScope.launch {
                            runCatching {
                                val (title, tracks) = SpotifyResolver.resolve(url)
                                val playlistId = if (tracks.size > 1)
                                    app.repository.createPlaylist(title.take(40)) else null
                                tracks.forEach { track ->
                                    app.downloader.searchBestMatch(track.searchQuery, track.durationSec)
                                        ?.let { app.downloader.enqueue(it, playlistId) }
                                }
                            }
                        }
                    }
                }
            }
            ACTION_PLAY_DAILY_MIX -> {
                lifecycleScope.launch {
                    val mix = app.repository.buildDailyMix()
                    if (mix.isNotEmpty()) app.playerController.playQueue(mix, 0)
                }
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun MainScaffold(app: PrivateMusicApp) {
    val navController: NavHostController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val tabs = listOf(
        Tab("home", "Inicio") { Icon(Icons.Filled.Home, null) },
        Tab("search", "Buscar") { Icon(Icons.Filled.Search, null) },
        Tab("library", "Biblioteca") { Icon(Icons.Filled.LibraryMusic, null) },
        Tab("playlists", "Playlists") { Icon(Icons.Filled.QueueMusic, null) },
        Tab("settings", "Ajustes") { Icon(Icons.Filled.Settings, null) },
    )

    val nowPlaying by app.playerController.nowPlaying.collectAsState()

    // Global action feedback ("Añadida a la cola", …) as snackbars.
    val snackbarHostState = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.aar.privatemusic.util.Feedback.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg, withDismissAction = false)
        }
    }

    // La carátula del mini-reproductor y la del reproductor son el MISMO
    // elemento para Compose: al abrir el reproductor, vuela y crece en vez de
    // desaparecer abajo y aparecer arriba. Las dos tienen que estar dentro de
    // este layout, y cada una dentro de su propia AnimatedVisibility.
    androidx.compose.animation.SharedTransitionLayout {
    val sharedScope = this
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        bottomBar = {
            androidx.compose.animation.AnimatedVisibility(
                visible = currentRoute != "player",
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                val visibilityScope = this
                Column {
                    nowPlaying?.let { np ->
                        val song by app.repository.observeSong(np.songId)
                            .collectAsState(initial = null)
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        MiniPlayer(
                            controller = app.playerController,
                            onOpenPlayer = { navController.navigate("player") },
                            isFavorite = song?.isFavorite == true,
                            onToggleFavorite = {
                                val current = song ?: return@MiniPlayer
                                scope.launch {
                                    app.repository.setFavorite(current.id, !current.isFavorite)
                                }
                            },
                            coverModifier = with(sharedScope) {
                                Modifier.sharedElement(
                                    rememberSharedContentState(key = "cover"),
                                    animatedVisibilityScope = visibilityScope,
                                )
                            },
                        )
                    }
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo("home") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = tab.icon,
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                HomeScreen(
                    app,
                    onOpenSearch = { navController.navigate("search") },
                    onOpenPlaylist = { navController.navigate("playlist/$it") },
                    onOpenStats = { navController.navigate("stats") },
                    onOpenLibrary = { navController.navigate("library") },
                )
            }
            composable("search") { SearchScreen(app) }
            composable("library") {
                LibraryScreen(app, onOpenArtist = { name ->
                    navController.navigate("artist/" + android.net.Uri.encode(name))
                })
            }
            composable("artist/{name}") { entry ->
                val name = entry.arguments?.getString("name")
                    ?.let { android.net.Uri.decode(it) } ?: return@composable
                ArtistScreen(app, artistName = name)
            }
            composable("playlists") {
                PlaylistsScreen(
                    app,
                    onOpenPlaylist = { navController.navigate("playlist/$it") },
                    onOpenAuto = { navController.navigate("auto/${it.route}") },
                    onOpenSmart = { navController.navigate("smart/$it") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    app,
                    onOpenStats = { navController.navigate("stats") },
                    onOpenEq = { navController.navigate("eq") },
                )
            }
            composable("stats") { StatsScreen(app) }
            composable("eq") { EqScreen(app) }
            composable("smart/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                SmartPlaylistDetailScreen(app, smartPlaylistId = id)
            }
            composable("playlist/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                PlaylistDetailScreen(app, playlistId = id)
            }
            composable("auto/{type}") { entry ->
                val type = AutoPlaylistType.entries
                    .firstOrNull { it.route == entry.arguments?.getString("type") }
                    ?: return@composable
                AutoPlaylistScreen(app, type)
            }
            composable("player") {
                val visibilityScope = this
                PlayerScreen(
                    app,
                    onBack = { navController.popBackStack() },
                    onOpenQueue = { navController.navigate("queue") },
                    coverModifier = with(sharedScope) {
                        Modifier.sharedElement(
                            rememberSharedContentState(key = "cover"),
                            animatedVisibilityScope = visibilityScope,
                        )
                    },
                )
            }
            composable("queue") { QueueScreen(app, onBack = { navController.popBackStack() }) }
        }
    }
    }
}
