package com.leonardos.spikestream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.leonardos.spikestream.R
import com.leonardos.spikestream.data.openAccountPlans
import com.leonardos.spikestream.data.YouTubeManagedApi
import com.leonardos.spikestream.data.YouTubeManagedResult
import com.leonardos.spikestream.ui.theme.SpikeStreamPrimaryButton
import com.leonardos.spikestream.ui.theme.SpikeStreamDangerButton
import kotlinx.coroutines.launch

/** Only an explicit confirmed Stop closes YouTube. Lifecycle exits and signal loss never do. */
@Composable
fun ManagedYouTubeControls(matchId: String, streaming: Boolean, appToken: () -> String?,
    canStart: () -> Boolean, onStart: (String) -> Unit, onPause: () -> Unit,
    initialBroadcastId: String?, modifier: Modifier = Modifier,
    authorizeOverride: (((String) -> Unit) -> Unit)? = null,
    requestOverride: (suspend (String, String, Boolean) -> YouTubeManagedResult)? = null,
    onClosed: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var accessLocked by remember(matchId) { mutableStateOf(false) }
    LaunchedEffect(matchId) {
        if (requestOverride == null && initialBroadcastId.isNullOrBlank() && !streaming) {
            appToken()?.let { accessLocked = YouTubeManagedApi.eligibility(it, matchId) == false }
        }
    }
    var busy by remember { mutableStateOf(false) }
    var finishing by rememberSaveable(matchId) { mutableStateOf(false) }
    var closed by rememberSaveable(matchId) { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var chooseAccount by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Reuse the consent from Start while this screen lives; never save credentials in saved state.
    var googleToken by remember(matchId) { mutableStateOf<String?>(null) }
    suspend fun perform(token: String) {
        try {
            val app = appToken()
            if (app == null) error = "APP_AUTH_REQUIRED"
            else when (val result = (requestOverride?.invoke(app, token, finishing) ?: YouTubeManagedApi.act(context, app, token, matchId, finishing))) {
                is YouTubeManagedResult.Prepared -> {
                    if (!finishing) { chooseAccount = false; onStart(result.stream.rtmpUrl) }
                }
                YouTubeManagedResult.Closed -> { closed = true; error = null; googleToken = null; onClosed() }
                is YouTubeManagedResult.Error -> {
                    error = result.code
                    if (result.code == "YOUTUBE_MANAGED_ACCESS_REQUIRED") accessLocked = true
                    chooseAccount = result.code in setOf("YOUTUBE_CHANNEL_REQUIRED", "YOUTUBE_PERMISSION")
                    if (chooseAccount || result.code == "YOUTUBE_AUTH_REQUIRED") googleToken = null
                    if (result.code == "YOUTUBE_CLOSE_PENDING") finishing = true
                    if (result.code == "YOUTUBE_EVENT_FINISHED") { closed = true; onClosed() }
                }
            }
        } finally { busy = false }
    }
    val acceptToken: (String) -> Unit = { token ->
        googleToken = token
        scope.launch { perform(token) }
    }
    val authorizeGoogle = rememberYouTubeAuthorization(onToken = acceptToken, onFailure = { error = it; busy = false })
    fun authorize() {
        if (authorizeOverride != null) authorizeOverride(acceptToken) else authorizeGoogle(chooseAccount)
    }
    fun finish() {
        if (busy) return
        onPause()
        finishing = true
        error = null
        busy = true
        val cached = googleToken
        if (cached != null) scope.launch { perform(cached) } else authorize()
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
      if (closed || finishing || streaming || busy || error != null || accessLocked) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.78f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (accessLocked && !streaming && !finishing) {
            Text(stringResource(R.string.account_saved_match_locked), color = Color.White)
            TextButton(onClick = { openAccountPlans(context) }) { Text(stringResource(R.string.account_plans)) }
        }
        if (closed) Text(stringResource(R.string.youtube_managed_closed), color = Color.White)
        else if (finishing && !busy) Text(stringResource(R.string.youtube_close_pending), color = Color.White)
        else if (streaming && !busy) Text(stringResource(R.string.youtube_managed_stop_hint), color = Color.White)
        if (busy) Text(stringResource(if (finishing) R.string.youtube_closing else R.string.youtube_preparing), color = Color.White)
        error?.takeUnless { it == "YOUTUBE_CLOSE_PENDING" || it == "YOUTUBE_EVENT_FINISHED" }?.let {
            Text(stringResource(youtubeFailureMessage(it)), color = MaterialTheme.colorScheme.error)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
      }
      val buttonModifier = Modifier.padding(16.dp).widthIn(min = 200.dp)
      val buttonText = (if (accessLocked && !streaming && !finishing) "🔒 " else "") + stringResource(when {
          finishing -> R.string.youtube_retry_close
          streaming -> R.string.youtube_pause_video
          else -> R.string.launch_stream
      })
      val onAction: () -> Unit = {
          if (!busy && !closed) {
              if (streaming || finishing) confirmStop = true
              else if (accessLocked) openAccountPlans(context)
              else if (canStart()) { error = null; busy = true; authorize() }
          }
      }
      if (streaming || finishing) SpikeStreamDangerButton(
          text = buttonText, onClick = onAction, modifier = buttonModifier, enabled = !busy && !closed)
      else SpikeStreamPrimaryButton(
          text = buttonText, onClick = onAction, modifier = buttonModifier, enabled = !busy && !closed,
          isLoading = busy)
    }

    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false },
        title = { Text(stringResource(R.string.youtube_finish)) },
        text = { Text(stringResource(R.string.youtube_stop_confirm)) },
        confirmButton = { TextButton(onClick = { confirmStop = false; finish() }) {
            Text(stringResource(R.string.youtube_stop_confirm_action))
        } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) } })
}
