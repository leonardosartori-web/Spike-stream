package com.leonardos.spikestream.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.leonardos.spikestream.activities.TokenManager
import com.leonardos.spikestream.R
import com.leonardos.spikestream.data.*
import kotlinx.coroutines.launch

/** Read the owned match on entry/resume, never poll YouTube. Server checkpoints are authoritative. */
@Composable
fun YouTubeEventDetails(
    matchId: String,
    tokenManager: TokenManager,
    loadMatch: suspend () -> org.json.JSONObject? = {
        val result = tokenManager.currentToken?.let { StreamApi.makeGetGamesRequest(it) }
        (result as? GamesResult.Success)?.games?.firstOrNull { it.optString("id") == matchId }
    }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var refreshing by remember(matchId) { mutableStateOf(false) }
    var loading by remember(matchId) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember(matchId) { mutableStateOf(false) }
    var closed by remember(matchId) { mutableStateOf(false) }
    var pending by remember(matchId) { mutableStateOf(false) }
    var broadcast by remember(matchId) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var chooseAccount by remember { mutableStateOf(false) }
    suspend fun refresh() {
        if (refreshing || busy) return
        refreshing = true
        loading = true
        loaded = false
        val game = loadMatch()
        if (game != null && game.optBoolean("youtubeManaged", false)) {
            closed = !game.isNull("youtubeClosedAt")
            pending = !closed && !game.isNull("youtubeCloseRequestedAt")
            broadcast = game.optString("youtubeBroadcastId").takeIf { !game.isNull("youtubeBroadcastId") && it.isNotBlank() }
            loaded = true
            error = null
        } else error = "YOUTUBE_UNAVAILABLE"
        loading = false
        refreshing = false
    }
    DisposableEffect(owner, matchId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { refresh() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(matchId) { refresh() }
    val authorize = rememberYouTubeAuthorization(onToken = { googleToken ->
        scope.launch {
            try {
                val token = tokenManager.currentToken
                if (token == null) error = "APP_AUTH_REQUIRED"
                else when (val result = YouTubeManagedApi.act(context, token, googleToken, matchId, true)) {
                    YouTubeManagedResult.Closed -> { closed = true; pending = false; error = null }
                    is YouTubeManagedResult.Error -> {
                        pending = true
                        error = result.code
                        chooseAccount = result.code in setOf("YOUTUBE_CHANNEL_REQUIRED", "YOUTUBE_PERMISSION")
                    }
                    else -> { pending = true; error = "YOUTUBE_CLOSE_PENDING" }
                }
            } finally { busy = false }
        }
    }, onFailure = { error = it; busy = false })
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.youtube_managed_ready), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.youtube_event_details_hint))
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (closed) Text(stringResource(R.string.youtube_managed_closed))
            else if (pending) Text(stringResource(R.string.youtube_close_pending))
            error?.let { Text(stringResource(youtubeFailureMessage(it)), color = MaterialTheme.colorScheme.error) }
            if (!loaded) TextButton(enabled = !loading && !busy, onClick = { scope.launch { refresh() } }) {
                Text(stringResource(R.string.destination_retry))
            }
            if (loaded && !closed) OutlinedButton(enabled = !busy && !loading, onClick = { confirm = true }) {
                Text(stringResource(if (pending) R.string.youtube_retry_close else R.string.youtube_finish))
            }
            broadcast?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }?.let { id ->
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id"))) } }) {
                    Text(stringResource(R.string.youtube_watch_link))
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.youtube_finish)) },
        text = { Text(stringResource(R.string.youtube_finish_confirm)) },
        confirmButton = { TextButton(onClick = {
            if (!busy) { confirm = false; busy = true; error = null; authorize(chooseAccount) }
        }) { Text(stringResource(R.string.youtube_finish)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}


