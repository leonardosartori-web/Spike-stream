package com.leonardos.spikestream.ui.components

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.leonardos.spikestream.BuildConfig
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.leonardos.spikestream.R
import com.leonardos.spikestream.data.*
import com.leonardos.spikestream.ui.theme.SpikeStreamDialog
import com.leonardos.spikestream.utils.RtmpUrlValidation
import com.leonardos.spikestream.utils.RtmpUrlValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Keeps the direct social buttons and manual URL entry, with a selection summary. */
@Composable
fun StreamDestinationPicker(
    appToken: String,
    callbackManager: CallbackManager,
    store: StreamDestinationStore,
    selection: StreamDestination?,
    busy: Boolean,
    creating: Boolean,
    onSelectionChange: (StreamDestination?) -> Unit,
    onBusyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accountAccess: AccountAccess? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val select by rememberUpdatedState(onSelectionChange)
    val setBusy by rememberUpdatedState(onBusyChange)
    var loadingPlatform by remember { mutableStateOf<StreamPlatform?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }
    var noticePlatform by remember { mutableStateOf<StreamPlatform?>(null) }
    var noticeIsSetup by remember { mutableStateOf(false) }
    var selectionNotice by remember { mutableStateOf<Int?>(null) }
    var youtubeStreams by remember { mutableStateOf<List<YouTubeStreamDestination>>(emptyList()) }
    var youtubeCached by remember { mutableStateOf(false) }
    var checkingManagedAccess by remember { mutableStateOf(false) }
    var managedAccessError by remember { mutableStateOf<String?>(null) }
    var showCreateYouTube by remember { mutableStateOf(false) }
    var showYouTubeOptions by remember { mutableStateOf(false) }
    var setupManaged by remember { mutableStateOf(false) }
    var setupMadeForKids by remember { mutableStateOf(false) }
    var showStudioGuide by remember { mutableStateOf(false) }
    var showYouTube by remember { mutableStateOf(false) }
    var facebookDestinations by remember { mutableStateOf<List<FacebookDestination>>(emptyList()) }
    var facebookToken by remember { mutableStateOf<String?>(null) }
    var showFacebook by remember { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }
    var recent by remember(store) { mutableStateOf(store.recent()) }
    var url by remember { mutableStateOf(selection?.rtmpUrl.orEmpty()) }
    var revealUrl by remember { mutableStateOf(false) }
    val enabled = !busy && !creating

    LaunchedEffect(store) {
        if (recent.isEmpty()) {
            // The previous Recent button was populated from active matches. Keep those available
            // after upgrading, without importing another account's unscoped preference entries.
            val result = StreamApi.makeGetGamesRequest(appToken)
            if (result is GamesResult.Success) {
                val imported = result.games.sortedByDescending { it.optString("createdAt") }.mapNotNull { game ->
                    val address = game.optString("rtmpUrl").trim()
                    val host = streamDestinationHost(address).lowercase()
                    if (!RtmpUrlValidator.isValid(address) || host == "facebook.com" || host.endsWith(".facebook.com")) {
                        return@mapNotNull null
                    }
                    val name = listOf(game.optString("teamAName"), game.optString("teamBName"))
                        .filter { it.isNotBlank() }.joinToString(" vs ")
                        .ifBlank { context.getString(R.string.destination_existing_match) }
                    val channelId = game.optionalYouTubeString("youtubeChannelId")
                    val youtube = channelId?.let {
                        YouTubeStreamDestination(game.optString("youtubeStreamId"), name, name, address, "", "",
                            youtubeChannelId = it, youtubeChannelTitle = game.optionalYouTubeString("youtubeChannelTitle"))
                    }
                    StreamDestination(if (youtube == null) StreamPlatform.CUSTOM else StreamPlatform.YOUTUBE,
                        name, address, youtube = youtube,
                        youtubeManaged = game.optBoolean("youtubeManaged", false),
                        youtubeMadeForKids = game.optBoolean("youtubeMadeForKids", false))
                }
                runCatching { store.importExisting(imported) }
                recent = store.recent()
            }
        }
    }

    fun finishLoading() {
        loadingPlatform = null
        setBusy(false)
    }

    fun beginLoading(platform: StreamPlatform, setup: Boolean = false) {
        loadingPlatform = platform
        noticePlatform = platform
        noticeIsSetup = setup
        notice = null
        setBusy(true)
    }

    fun choose(destination: StreamDestination, hint: Int? = null) {
        url = destination.rtmpUrl
        revealUrl = false
        selectionNotice = hint
        notice = null
        select(destination)
    }

    fun available(kind: String): Boolean = accountAccess?.canCreate(kind) == true
    fun openPage(address: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
            .onFailure { notice = R.string.destination_browser_error; noticeIsError = true }
    }

    val googleClient = remember(context) {
        GoogleSignIn.getClient(context, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestScopes(Scope("https://www.googleapis.com/auth/youtube.readonly")).build())
    }

    suspend fun loadYouTube(account: GoogleSignInAccount) {
        try {
            val accountKey = account.id ?: account.email.orEmpty()
            youtubeCached = false
            val result = StreamApi.fetchYouTubeRTMP(context, account, appToken)
            youtubeStreams = when (result) {
                is YouTubeFetchResult.Success -> result.streams.filter { it.preconfigured }.also {
                    runCatching { store.cacheYouTube(accountKey, it) }
                }
                is YouTubeFetchResult.NoStreams -> {
                    runCatching { store.clearYouTube(accountKey) }
                    emptyList()
                }
                else -> emptyList() // Never resurrect an unverified or now-reserved key from an old cache.
            }
            if (youtubeStreams.isNotEmpty()) {
                showYouTube = true
            } else {
                noticeIsError = true
                notice = when (result) {
                    is YouTubeFetchResult.NoStreams -> R.string.youtube_no_preconfigured_keys
                    is YouTubeFetchResult.Success -> R.string.youtube_verify_key_again
                    is YouTubeFetchResult.RateLimit -> R.string.yt_rate_limit_error
                    else -> R.string.destination_network_error
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = R.string.destination_network_error
            noticeIsError = true
        } finally { finishLoading() }
    }

    val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            notice = R.string.destination_login_cancelled
            noticeIsError = false
            finishLoading()
        } else {
            val account = runCatching { GoogleSignIn.getSignedInAccountFromIntent(result.data).result }.getOrNull()
            if (account == null) {
                notice = R.string.yt_login_error
                noticeIsError = true
                finishLoading()
            } else {
                beginLoading(StreamPlatform.YOUTUBE)
                scope.launch { loadYouTube(account) }
            }
        }
    }

    fun connectYouTube() {
        beginLoading(StreamPlatform.YOUTUBE)
        googleClient.signOut().addOnCompleteListener {
            runCatching { googleLauncher.launch(googleClient.signInIntent) }.onFailure {
                notice = R.string.yt_login_error
                noticeIsError = true
                finishLoading()
            }
        }
    }

    val createYouTubeKey = rememberYouTubeAuthorization(
        onToken = { googleToken ->
            scope.launch {
                try {
                    when (val result = YouTubeSetupApi.create(context, appToken, googleToken, setupMadeForKids)) {
                        is YouTubeSetupResult.Success -> {
                            val stream = result.stream
                            val destination = StreamDestination(StreamPlatform.YOUTUBE, stream.displayName, stream.rtmpUrl, youtube = stream,
                                youtubeManaged = true, youtubeMadeForKids = setupMadeForKids)
                            runCatching { store.remember(destination) }
                            recent = store.recent()
                            choose(destination, R.string.youtube_managed_ready)
                        }
                        is YouTubeSetupResult.Error -> { notice = youtubeFailureMessage(result.code); noticeIsError = true }
                    }
                } finally { finishLoading() }
            }
        },
        onFailure = { code -> notice = youtubeFailureMessage(code); noticeIsError = true; finishLoading() }
    )

    DisposableEffect(callbackManager, appToken) {
        val manager = LoginManager.getInstance()
        manager.registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
            override fun onSuccess(result: LoginResult) {
                beginLoading(StreamPlatform.FACEBOOK)
                facebookToken = result.accessToken.token
                scope.launch {
                    try {
                        when (val response = StreamApi.fetchFacebookDestinations(result.accessToken.token, appToken)) {
                            is FacebookResult.Success -> {
                                facebookDestinations = response.value
                                showFacebook = response.value.isNotEmpty()
                                if (!showFacebook) {
                                    notice = R.string.destination_fb_unavailable
                                    noticeIsError = true
                                }
                            }
                            is FacebookResult.Error -> {
                                notice = response.reason.messageResource()
                                noticeIsError = true
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        notice = R.string.destination_network_error
                        noticeIsError = true
                    } finally { finishLoading() }
                }
            }
            override fun onCancel() {
                notice = R.string.destination_login_cancelled
                noticeIsError = false
                finishLoading()
            }
            override fun onError(error: FacebookException) {
                notice = R.string.fb_load_error
                noticeIsError = true
                finishLoading()
            }
        })
        onDispose { manager.unregisterCallback(callbackManager) }
    }

    fun connectFacebook() {
        openPage("${com.leonardos.spikestream.utils.Constants.BASE_URL}/facebook-guide.html")
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.destination_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.destination_subtitle), style = MaterialTheme.typography.bodyMedium)

        TextButton(enabled = enabled, onClick = {
            val guide = TextView(context).apply {
                text = HtmlCompat.fromHtml(context.getString(R.string.guide_body), HtmlCompat.FROM_HTML_MODE_LEGACY)
                movementMethod = LinkMovementMethod.getInstance()
                val inset = (24 * context.resources.displayMetrics.density).toInt()
                setPadding(inset, inset, inset, inset)
                setTextIsSelectable(true)
            }
            AlertDialog.Builder(context).setTitle(context.getString(R.string.guide_title))
                .setView(ScrollView(context).apply { addView(guide) })
                .setPositiveButton(android.R.string.ok, null).show()
        }) { Text(stringResource(R.string.guide_button_show)) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showYouTubeOptions = true }, enabled = enabled, modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)) {
                if (loadingPlatform == StreamPlatform.YOUTUBE) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("YouTube")
            }
            OutlinedButton(onClick = { connectFacebook() }, enabled = enabled, modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, Color(0xFF1877F2).copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1877F2))) {
                if (loadingPlatform == StreamPlatform.FACEBOOK) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Facebook")
            }
        }
        Button(onClick = { recent = store.recent(); showRecent = true },
            enabled = enabled && recent.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary)) {
            Text(stringResource(R.string.recent_rtmp_title))
        }

        if (busy) Text(stringResource(R.string.destination_loading), style = MaterialTheme.typography.bodySmall)
        notice?.let { message ->
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
                color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (message == R.string.youtube_no_preconfigured_keys) TextButton(enabled = enabled, onClick = {
                if (available("youtubeManaged")) {
                    setupManaged = true
                    showCreateYouTube = true
                } else openAccountPlans(context)
            }) { Text(stringResource(R.string.youtube_configure_event)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = enabled, onClick = {
                    if (noticeIsSetup) showCreateYouTube = true
                    else if (noticePlatform == StreamPlatform.FACEBOOK) connectFacebook() else connectYouTube()
                }) { Text(stringResource(R.string.destination_retry)) }
            }
        }

        selection?.takeIf { it.isReady }?.let { destination ->
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.destination_selected), style = MaterialTheme.typography.labelMedium)
                    Text(destination.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(destinationDescription(destination), style = MaterialTheme.typography.bodySmall)
                    if (destination.platform == StreamPlatform.YOUTUBE) Text(stringResource(if (destination.youtubeManaged) R.string.youtube_managed_ready else R.string.youtube_studio_summary), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (destination.platform == StreamPlatform.FACEBOOK)
                        R.string.destination_facebook_publish else R.string.destination_not_live),
                        style = MaterialTheme.typography.bodySmall)
                    if (destination.platform == StreamPlatform.YOUTUBE) TextButton(enabled = enabled, onClick = { showYouTubeOptions = true }) {
                        Text(stringResource(R.string.youtube_manage_destination))
                    }
                    selectionNotice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        HorizontalDivider()
        Text((if (available("custom")) "" else "🔒 ") + stringResource(R.string.destination_full_url), style = MaterialTheme.typography.titleMedium)
        if (!available("custom")) TextButton(onClick = { openAccountPlans(context) }) { Text(stringResource(R.string.account_plans)) }
        Text(stringResource(R.string.destination_manual_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = url, enabled = enabled,
            onValueChange = { value ->
                url = value
                selectionNotice = null
                notice = null
                // Manual edits deliberately discard social metadata that may no longer match the URL.
                select(value.takeIf { it.isNotBlank() }?.let {
                    StreamDestination(StreamPlatform.CUSTOM, streamDestinationHost(it), it.trim())
                })
            },
            label = { Text(stringResource(R.string.url_rtmp_label)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            visualTransformation = if (revealUrl) VisualTransformation.None else PasswordVisualTransformation(),
            isError = url.isNotBlank() && !RtmpUrlValidator.isValid(url)
        )
        if (url.isNotEmpty()) {
            TextButton(onClick = { revealUrl = !revealUrl }, enabled = enabled) {
                Text(stringResource(if (revealUrl) R.string.destination_hide_key else R.string.destination_show_key))
            }
        }
        if (url.isNotBlank()) {
            val validation = RtmpUrlValidator.validate(url)
            if (validation != RtmpUrlValidation.VALID) {
                Text(stringResource(when (validation) {
                    RtmpUrlValidation.YOUTUBE_STREAM_KEY_MISSING -> R.string.create_match_error_youtube_key
                    RtmpUrlValidation.FACEBOOK_STREAM_KEY_MISSING -> R.string.create_match_error_facebook_key
                    else -> R.string.create_match_error3
                }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (selection?.platform == StreamPlatform.YOUTUBE || noticePlatform == StreamPlatform.YOUTUBE) {
            Text(stringResource(if (selection?.youtubeManaged == true) R.string.youtube_managed_ready else R.string.destination_youtube_hint), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = enabled, onClick = { openPage("https://studio.youtube.com") }) {
                Text(stringResource(R.string.destination_open_youtube))
            }
        }
        if (selection?.platform == StreamPlatform.FACEBOOK || noticePlatform == StreamPlatform.FACEBOOK) {
            TextButton(enabled = enabled, onClick = { openPage("https://www.facebook.com/live/producer") }) {
                Text(stringResource(R.string.destination_open_facebook))
            }
        }
    }

    if (showYouTubeOptions) {
        DestinationDialog("YouTube", { showYouTubeOptions = false }) {
            if (notice == R.string.youtube_verify_key_again) Text(stringResource(R.string.youtube_verify_key_again))
            Text(stringResource(R.string.youtube_recommended), style = MaterialTheme.typography.labelLarge)
            Text((if (available("youtubeDirect")) "" else "🔒 ") + stringResource(R.string.youtube_studio_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.youtube_studio_description))
            Text(stringResource(R.string.youtube_reserved_key_explanation), style = MaterialTheme.typography.bodySmall)
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                if (available("youtubeDirect")) {
                    setupManaged = false
                    showYouTubeOptions = false
                    connectYouTube()
                } else openAccountPlans(context)
            }) { Text(stringResource(R.string.youtube_select_key)) }
            TextButton(onClick = { showStudioGuide = true }) { Text(stringResource(R.string.youtube_studio_guide_title)) }
            HorizontalDivider()
            Text((if (available("youtubeManaged")) "" else "🔒 ") + stringResource(R.string.youtube_managed_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.youtube_managed_description))
            Text(stringResource(R.string.youtube_managed_access_hint), style = MaterialTheme.typography.bodySmall)
            managedAccessError?.let { Text(stringResource(youtubeFailureMessage(it)), color = MaterialTheme.colorScheme.error) }
            if (checkingManagedAccess) CircularProgressIndicator()
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !checkingManagedAccess, onClick = {
                if (!available("youtubeManaged")) {
                    openAccountPlans(context)
                } else {
                checkingManagedAccess = true
                managedAccessError = null
                scope.launch {
                    try {
                        val error = YouTubeManagedApi.accessError(appToken)
                        managedAccessError = error
                        if (error == null) {
                            setupManaged = true
                            showYouTubeOptions = false
                            showCreateYouTube = true
                        }
                    } finally { checkingManagedAccess = false }
                }
                }
            }) { Text(stringResource(R.string.youtube_configure_event)) }
        }
    }
    if (showStudioGuide) DestinationDialog(stringResource(R.string.youtube_studio_guide_title), { showStudioGuide = false }) {
        Text(stringResource(R.string.youtube_studio_guide))
        TextButton(onClick = { openPage("https://studio.youtube.com") }) { Text(stringResource(R.string.destination_open_youtube)) }
    }
    if (showCreateYouTube) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCreateYouTube = false },
            title = { Text(stringResource(R.string.youtube_managed_title)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.youtube_managed_description))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = setupMadeForKids, onCheckedChange = { setupMadeForKids = it })
                        Text(stringResource(R.string.youtube_setup_kids))
                    }
                    Text(stringResource(R.string.youtube_setup_kids_hint))
                    Text(stringResource(R.string.youtube_reserved_key_explanation))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreateYouTube = false
                    beginLoading(StreamPlatform.YOUTUBE, setup = true)
                    createYouTubeKey(true)
                }) { Text(stringResource(R.string.youtube_setup_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateYouTube = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    if (showYouTube) {
        DestinationDialog(stringResource(R.string.yt_stream_select_title), { showYouTube = false }) {
            Text(stringResource(R.string.youtube_preconfigured_keys_hint), style = MaterialTheme.typography.bodySmall)
            if (youtubeCached) Text(stringResource(R.string.destination_cached), style = MaterialTheme.typography.bodySmall)
            youtubeStreams.forEach { stream ->
                val destination = StreamDestination(StreamPlatform.YOUTUBE, stream.displayName, stream.rtmpUrl, youtube = stream,
                    youtubeManaged = setupManaged, youtubeMadeForKids = setupMadeForKids)
                DestinationRow(destination.name, destinationDescription(destination)) {
                    runCatching { store.remember(destination) }
                    recent = store.recent()
                    choose(destination, if (setupManaged) R.string.youtube_managed_ready else R.string.youtube_studio_description)
                    showYouTube = false
                }
            }
        }
    }
    if (showFacebook) {
        DestinationDialog(stringResource(R.string.destination_facebook_select), { showFacebook = false }) {
            Text(stringResource(R.string.destination_facebook_hint), style = MaterialTheme.typography.bodySmall)
            facebookDestinations.forEach { target ->
                DestinationRow(target.name, stringResource(if (target.type == "page") R.string.destination_page else R.string.destination_account)) {
                    choose(StreamDestination(StreamPlatform.FACEBOOK, target.name, facebook = target, facebookAccessToken = facebookToken))
                    showFacebook = false
                }
            }
        }
    }
    if (showRecent) {
        DestinationDialog(stringResource(R.string.recent_rtmp_title), { showRecent = false }) {
            recent.forEach { destination ->
                val used = if (destination.lastUsed > 0) java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(destination.lastUsed)) else ""
                DestinationRow(destination.name, listOf(destinationDescription(destination),
                    if (destination.platform == StreamPlatform.YOUTUBE) stringResource(when {
                        destination.youtubeManaged -> R.string.youtube_managed_ready
                        destination.youtube?.preconfigured == true -> R.string.youtube_studio_summary
                        else -> R.string.youtube_key_needs_verification
                    }) else "",
                    used).filter { it.isNotBlank() }.joinToString(" · ")) {
                    showRecent = false
                    if (destination.requiresYouTubeKeyVerification) {
                        notice = R.string.youtube_verify_key_again
                        noticeIsError = false
                        showYouTubeOptions = true
                    } else choose(destination, R.string.destination_recent_hint)
                }
            }
        }
    }
}

@Composable
private fun destinationDescription(destination: StreamDestination): String = listOfNotNull(
    when (destination.platform) {
        StreamPlatform.YOUTUBE -> "YouTube"
        StreamPlatform.FACEBOOK -> "Facebook"
        StreamPlatform.CUSTOM -> stringResource(R.string.destination_custom)
    },
    destination.youtube?.youtubeChannelTitle,
    streamDestinationHost(destination.rtmpUrl).takeIf { it.isNotBlank() }
).joinToString(" · ")

@Composable
private fun DestinationDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SpikeStreamDialog(onDismissRequest = onDismiss, title = title, content = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_button)) } })
}

@Composable
private fun DestinationRow(name: String, description: String, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun FacebookFailure.messageResource(): Int = when (this) {
    FacebookFailure.SESSION -> R.string.destination_fb_session
    FacebookFailure.PERMISSION -> R.string.destination_fb_permissions
    FacebookFailure.DESTINATION -> R.string.destination_fb_unavailable
    FacebookFailure.RATE_LIMIT -> R.string.fb_load_error
    FacebookFailure.NETWORK -> R.string.destination_network_error
}
