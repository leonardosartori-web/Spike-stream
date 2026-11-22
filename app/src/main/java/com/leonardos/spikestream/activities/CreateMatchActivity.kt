package com.leonardos.spikestream.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.facebook.CallbackManager
import com.leonardos.spikestream.R
import com.leonardos.spikestream.data.*
import com.leonardos.spikestream.ui.components.*
import com.leonardos.spikestream.ui.theme.*
import com.leonardos.spikestream.utils.TourManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CreateMatchActivity: AnimatedAppActivity() {

    private lateinit var tokenManager: TokenManager
    private lateinit var callbackManager: CallbackManager

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        callbackManager.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tokenManager = TokenManager(applicationContext)
        callbackManager = CallbackManager.Factory.create()

        setContent {
            RefinedApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                )
                {
                    val token by tokenManager.tokenFlow.collectAsState(initial = tokenManager.currentToken)
                    if (token == null) {
                        LaunchedEffect(Unit) {
                            startActivity(Intent(this@CreateMatchActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                            finish()
                        }
                    } else {
                        CreateMatchScreen(tokenManager, callbackManager)
                    }
                }
            }
        }
    }

    @Composable
    fun CreateMatchScreen(tokenManager: TokenManager, callbackManager: CallbackManager) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val token by tokenManager.tokenFlow.collectAsState(initial = tokenManager.currentToken)
        val appToken = token ?: return
        val accountOwner = remember(appToken) { AccountAccess.owner(appToken) }
        val account = remember(appToken) { AccountAccess.fromToken(appToken) }
        var checkingAccount by remember { mutableStateOf(true) }
        var accountRefreshFailed by remember { mutableStateOf(false) }
        suspend fun refreshAccount() {
            checkingAccount = true
            try {
                val refreshed = AccountAccessApi.refresh(appToken)
                accountRefreshFailed = refreshed == null || !tokenManager.saveToken(refreshed, expectedToken = appToken)
            } finally { checkingAccount = false }
        }
        LaunchedEffect(Unit) { refreshAccount() }
        val store = remember(accountOwner) { StreamDestinationStore(context, appToken) }
        var team1 by rememberSaveable { mutableStateOf("") }
        var team2 by rememberSaveable { mutableStateOf("") }
        var destination by remember(accountOwner) { mutableStateOf<StreamDestination?>(null) }
        var destinationLoading by remember { mutableStateOf(false) }
        var isLoading by remember { mutableStateOf(false) }
        var showYouTubeReminder by rememberSaveable(accountOwner) { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<Int?>(null) }
        // Reuse a prepared Facebook live if match creation fails and the user retries.
        var preparedFacebookUrl by remember(destination) { mutableStateOf<String?>(null) }
        val tourController = rememberTourController(
            tourKey = TourManager.KEY_CREATE_MATCH,
            steps = listOf(
                TourStep(id = "teams", emoji = "👥", title = context.getString(R.string.tour_create_step1_title),
                    body = context.getString(R.string.tour_create_step1_body), highlightKey = "teams"),
                TourStep(id = "rtmp", emoji = "📡", title = context.getString(R.string.destination_title),
                    body = context.getString(R.string.destination_subtitle), highlightKey = "rtmp"),
                TourStep(id = "create_btn", emoji = "✅", title = context.getString(R.string.tour_create_step4_title),
                    body = context.getString(R.string.tour_create_step4_body), highlightKey = "create_btn")
            )
        )

        val createMatch: () -> Unit = {
            val selected = destination
            if (team1.isNotBlank() && team2.isNotBlank() && selected != null && selected.isReady && !checkingAccount && !accountRefreshFailed && account?.canCreate(selected.accountKind()) == true && !destinationLoading && !isLoading) {
                isLoading = true
                errorMessage = null
                val homeTeam = team1.trim()
                val awayTeam = team2.trim()
                scope.launch {
                    try {
                        val url = if (selected.platform == StreamPlatform.FACEBOOK) {
                            preparedFacebookUrl ?: when (val response = StreamApi.createFacebookLive(
                                selected, appToken, "$homeTeam vs $awayTeam"
                            )) {
                                is FacebookResult.Success -> response.value.also { preparedFacebookUrl = it }
                                is FacebookResult.Error -> {
                                    errorMessage = response.reason.messageResource()
                                    return@launch
                                }
                            }
                        } else selected.rtmpUrl.trim()
                        when (val result = StreamApi.makeCreateMatchRequest(appToken, homeTeam, awayTeam, url, selected.youtube,
                            selected.youtubeManaged, selected.youtubeMadeForKids)) {
                            is CreateMatchResult.Success -> {
                                runCatching { store.remember(selected) }
                                finish()
                            }
                            is CreateMatchResult.Error -> errorMessage = result.message
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errorMessage = R.string.destination_network_error
                    } finally { isLoading = false }
                }
            }
        }

        if (showYouTubeReminder) {
            AlertDialog(
                onDismissRequest = { showYouTubeReminder = false },
                properties = DialogProperties(dismissOnClickOutside = false),
                icon = {
                    Icon(Icons.Default.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                },
                title = { Text(stringResource(R.string.youtube_public_reminder_title), fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.youtube_public_reminder_body)) },
                confirmButton = {
                    Button(onClick = {
                        showYouTubeReminder = false
                        createMatch()
                    }) { Text(stringResource(R.string.youtube_public_reminder_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showYouTubeReminder = false }) {
                        Text(stringResource(R.string.youtube_public_reminder_back))
                    }
                }
            )
        }

        SpikeStreamScreen {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(40.dp))
                Text(stringResource(R.string.create_match_title),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.create_intro), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                Spacer(Modifier.height(32.dp))

                SpikeStreamGlassCard {
                    Text(stringResource(R.string.create_step_teams), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 20.dp))
                    OutlinedTextField(value = team1, onValueChange = { team1 = it }, enabled = !isLoading,
                        label = { Text(stringResource(R.string.local_team)) }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().tourHighlight(tourController, "teams", RoundedCornerShape(16.dp)))
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = team2, onValueChange = { team2 = it }, enabled = !isLoading,
                        label = { Text(stringResource(R.string.guest_team)) }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(20.dp))
                SpikeStreamGlassCard {
                    key(accountOwner) {
                        StreamDestinationPicker(
                            appToken = appToken, callbackManager = callbackManager, store = store,
                            selection = destination, busy = destinationLoading || checkingAccount, creating = isLoading, accountAccess = account,
                            onSelectionChange = { destination = it; errorMessage = null },
                            onBusyChange = { destinationLoading = it },
                            modifier = Modifier.tourHighlight(tourController, "rtmp", RoundedCornerShape(16.dp))
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.create_step_review), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.create_next), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    if (checkingAccount) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (accountRefreshFailed) Text(stringResource(R.string.account_refresh_failed), color = MaterialTheme.colorScheme.error)
                    if (accountRefreshFailed || destination?.let { account?.canCreate(it.accountKind()) != true } == true) TextButton(enabled = !checkingAccount && !isLoading, onClick = { scope.launch { refreshAccount() } }) { Text(stringResource(R.string.account_refresh)) }
                    if (destination != null && account?.canCreate(destination!!.accountKind()) != true) {
                        Text(stringResource(R.string.account_locked))
                        TextButton(onClick = { openAccountPlans(context) }) { Text(stringResource(R.string.account_plans)) }
                    }
                    errorMessage?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }
                    val guidance = when {
                        team1.isBlank() || team2.isBlank() -> R.string.create_missing_teams
                        checkingAccount -> R.string.create_checking
                        destinationLoading -> R.string.destination_loading
                        destination?.isReady != true -> R.string.create_missing_channel
                        else -> null
                    }
                    guidance?.let {
                        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }
                    if (team1.isNotBlank() && team2.isNotBlank() && destination?.isReady == true) {
                        Text(team1.trim() + " • " + team2.trim(), fontWeight = FontWeight.Bold)
                        Text(destination!!.name, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }
                    SpikeStreamPrimaryButton(
                        modifier = Modifier.fillMaxWidth().tourHighlight(tourController, "create_btn", RoundedCornerShape(16.dp)),
                        text = stringResource(R.string.create_match), isLoading = isLoading,
                        enabled = team1.isNotBlank() && team2.isNotBlank() && destination?.isReady == true &&
                            !destinationLoading && !isLoading && !checkingAccount && !accountRefreshFailed && destination?.let { account?.canCreate(it.accountKind()) } == true,
                        onClick = {
                            val selected = destination
                            if (selected?.youtubeManaged != true && (selected?.platform == StreamPlatform.YOUTUBE ||
                                platformFromRtmpUrl(selected?.rtmpUrl.orEmpty()) == StreamPlatform.YOUTUBE)) {
                                showYouTubeReminder = true
                            } else {
                                createMatch()
                            }
                        }
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
            TourOverlay(controller = tourController)
        }
    }
}
