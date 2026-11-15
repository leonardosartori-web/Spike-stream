package com.leonardos.spikestream.ui.components

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.leonardos.spikestream.BuildConfig
import com.leonardos.spikestream.R
import com.leonardos.spikestream.data.YouTubeSetupApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Consent stays in Google's UI. Access tokens live only in memory for the current operation. */
@Composable
fun rememberYouTubeAuthorization(onToken: (String) -> Unit, onFailure: (String) -> Unit): (Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenCallback by rememberUpdatedState(onToken)
    val failureCallback by rememberUpdatedState(onFailure)
    var account by remember { mutableStateOf<GoogleSignInAccount?>(null) }
    val fetchToken = remember { java.util.concurrent.atomic.AtomicReference<() -> Unit>({}) }
    val permission = remember { Scope(YouTubeSetupApi.SCOPE) }
    val client = remember(context) {
        GoogleSignIn.getClient(context, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID).requestScopes(permission).build())
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) fetchToken.get().invoke() else failureCallback("YOUTUBE_AUTH_REQUIRED")
    }
    fetchToken.set {
        val selected = account?.account
        if (selected == null) failureCallback("YOUTUBE_AUTH_REQUIRED")
        else scope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    GoogleAuthUtil.getToken(context, selected, "oauth2:${YouTubeSetupApi.SCOPE}")
                }
                tokenCallback(token)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (recoverable: UserRecoverableAuthException) {
                val intent = recoverable.intent
                if (intent == null) failureCallback("YOUTUBE_AUTH_REQUIRED")
                else runCatching { consent.launch(intent) }.onFailure { failureCallback("YOUTUBE_AUTH_REQUIRED") }
            } catch (_: Exception) { failureCallback("YOUTUBE_AUTH_REQUIRED") }
        }
    }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        account = if (result.resultCode == Activity.RESULT_OK)
            runCatching { GoogleSignIn.getSignedInAccountFromIntent(result.data).result }.getOrNull() else null
        if (account == null) failureCallback("YOUTUBE_AUTH_REQUIRED") else fetchToken.get().invoke()
    }
    return { chooseAccount ->
        account = GoogleSignIn.getLastSignedInAccount(context)
        if (!chooseAccount && account != null && GoogleSignIn.hasPermissions(account, permission)) fetchToken.get().invoke()
        else if (chooseAccount) client.signOut().addOnCompleteListener {
            runCatching { login.launch(client.signInIntent) }.onFailure { failureCallback("YOUTUBE_AUTH_REQUIRED") }
        } else runCatching { login.launch(client.signInIntent) }.onFailure { failureCallback("YOUTUBE_AUTH_REQUIRED") }
    }
}

fun youtubeFailureMessage(code: String): Int = when (code) {
    "YOUTUBE_MANAGED_ACCESS_REQUIRED" -> R.string.youtube_managed_access_required
    "YOUTUBE_CLOSE_PENDING" -> R.string.youtube_close_pending
    "YOUTUBE_EVENT_FINISHED" -> R.string.youtube_automatic_finished
    "YOUTUBE_MATCH_REQUIRED" -> R.string.youtube_automatic_missing
    "YOUTUBE_ENABLE_LIVE" -> R.string.youtube_setup_enable_live
    "YOUTUBE_AUTH_REQUIRED", "YOUTUBE_PERMISSION", "APP_AUTH_REQUIRED" -> R.string.youtube_setup_permission
    "YOUTUBE_CHANNEL_REQUIRED", "YOUTUBE_CHANNEL_MISMATCH" -> R.string.youtube_setup_channel
    "YOUTUBE_QUOTA" -> R.string.youtube_setup_quota
    "YOUTUBE_RATE_LIMIT", "YOUTUBE_TOO_MANY_RESULTS" -> R.string.yt_rate_limit_error
    "YOUTUBE_BUSY" -> R.string.youtube_setup_busy
    "YOUTUBE_STREAM_IN_USE" -> R.string.youtube_setup_in_use
    "YOUTUBE_RESOURCE_MISSING" -> R.string.youtube_setup_finished
    "YOUTUBE_RECONCILE" -> R.string.youtube_setup_reconcile
    "YOUTUBE_INITIAL_EVENT_CHANGED", "YOUTUBE_INVALID_STATE" -> R.string.youtube_setup_invalid
    else -> R.string.destination_network_error
}
