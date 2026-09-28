package com.aar.privatemusic.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.aar.privatemusic.util.RefreshRateRequests

val LocalSongListRefreshRequests = compositionLocalOf<RefreshRateRequests?> { null }

@Composable
fun SongListRefreshRate(state: LazyListState, hasSongs: Boolean) {
    val requests = LocalSongListRefreshRequests.current
    val scrolling by remember(state) { derivedStateOf { state.isScrollInProgress } }
    val token = remember { Any() }
    DisposableEffect(requests, token, scrolling, hasSongs) {
        if (scrolling && hasSongs) requests?.acquire(token)
        onDispose { requests?.release(token) }
    }
}

@Composable
fun rememberSongListState(hasSongs: Boolean): LazyListState {
    val state = rememberLazyListState()
    SongListRefreshRate(state, hasSongs)
    return state
}
