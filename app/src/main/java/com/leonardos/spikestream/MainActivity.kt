package com.leonardos.spikestream

import com.leonardos.spikestream.BuildConfig
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import com.leonardos.spikestream.ui.theme.MyApplicationTheme
import com.leonardos.spikestream.ui.theme.SpikeStreamScreen
import com.leonardos.spikestream.ui.theme.SpikeStreamTextField
import com.leonardos.spikestream.ui.theme.SpikeStreamPrimaryButton
import com.leonardos.spikestream.ui.theme.SpikeStreamSecondaryButton
import com.leonardos.spikestream.ui.theme.SpikeStreamOutlinedButton
import com.leonardos.spikestream.ui.theme.SpikeStreamDangerButton
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.isImmediateUpdateAllowed
import java.util.concurrent.atomic.AtomicBoolean
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

class TokenManager(private val context: Context) {
    companion object {
        val TOKEN_KEY = stringPreferencesKey("auth_token")
        // Regex that validates the three-part Base64url JWT structure
        private val JWT_REGEX = Regex("^[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+$")

        fun isValidJwt(token: String): Boolean = JWT_REGEX.matches(token)
    }

    val tokenFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[TOKEN_KEY]
    }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = token
        }
    }

    suspend fun clearToken() {
        context.dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
        }
    }
}

sealed class GamesResult {
    data class Success(val games: List<JSONObject>) : GamesResult()
    data class Error(val message: String, val isAuthError: Boolean = false) : GamesResult()
}

sealed class AuthResult {
    data class Success(val token: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}


data class StreamInfo(
    val teamA: String,
    val teamB: String,
    val matchId: String,
    val rtmpUrl: String
) {
    val title: String get() = "$teamA vs $teamB"
}


class MainActivity : ComponentActivity() {

    private var isMobileAdsInitializeCalled = AtomicBoolean(false)
    private lateinit var consentInformation: ConsentInformation
    private lateinit var tokenManager: TokenManager
    private var latestIntentData: Uri? = null
    private lateinit var appUpdateManager: AppUpdateManager
    private val updateRequestCode = 123
    lateinit var rewardedAd: RewardedAd
    var isRewardedAdLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appUpdateManager = AppUpdateManagerFactory.create(this)
        checkForUpdates()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                )
                {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        // GDPR Consent Flow Initialization
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()
        consentInformation = UserMessagingPlatform.getConsentInformation(this)
        consentInformation.requestConsentInfoUpdate(
            this,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { loadAndShowError ->
                    if (loadAndShowError != null) {
                        Log.w("AdMob", "Consent form error: ${loadAndShowError.message}")
                    }
                    if (consentInformation.canRequestAds()) {
                        initializeMobileAdsSdk()
                    } else {
                        startCompose()
                    }
                }
            },
            { requestError ->
                Log.w("AdMob", "Consent info update failed: ${requestError.message}")
                if (consentInformation.canRequestAds()) {
                    initializeMobileAdsSdk()
                } else {
                    startCompose()
                }
            }
        )

        // Fallback or early start if already consented
        if (consentInformation.canRequestAds()) {
            initializeMobileAdsSdk()
        }

        fetchRemoteBaseUrl()
    }

    private fun fetchRemoteBaseUrl() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = getHttpClient()
                // Primary: your server. Secondary: a fallback like GitHub Gist.
                val request = Request.Builder()
                    .url("${Constants.REMOTE_CONFIG_URL}")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val newUrl = response.body()?.string()?.trim()
                    if (!newUrl.isNullOrBlank() && newUrl.startsWith("http")) {
                        Constants.BASE_URL = newUrl
                        Log.i("Config", "Remote BASE_URL updated: $newUrl")
                    }
                }
            } catch (e: Exception) {
                Log.w("Config", "Remote config fetch failed: ${e.message}")
            }
        }
    }

    private fun initializeMobileAdsSdk() {
        if (isMobileAdsInitializeCalled.getAndSet(true)) return
        MobileAds.initialize(this) { }
        loadAppOpenAd()
        loadRewardedAd()
    }

    private fun loadAppOpenAd() {
        // Load and show the App Open ad once at startup
        val adRequest = AdRequest.Builder().build()
        AppOpenAd.load(
            this,
            BuildConfig.ADMOB_APP_OPEN_ID,
            adRequest,
            AppOpenAd.APP_OPEN_AD_ORIENTATION_PORTRAIT,
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            // After ad, show the content
                            startCompose()
                        }

                        override fun onAdFailedToShowFullScreenContent(p0: AdError) {
                            startCompose()
                        }
                    }
                    ad.show(this@MainActivity)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // Fallback to app content
                    startCompose()
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    AppUpdateType.IMMEDIATE,
                    this,
                    updateRequestCode
                )
            }
        }
        loadRewardedAd() // ricarica sempre
    }

    private fun checkForUpdates() {
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo
        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    AppUpdateType.IMMEDIATE,
                    this,
                    updateRequestCode
                )
            }
        }
    }


    private fun loadRewardedAd() {
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            this,
            BuildConfig.ADMOB_REWARDED_ID,
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isRewardedAdLoaded = true
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    isRewardedAdLoaded = false
                }
            }
        )
    }

    private fun startCompose() {

        tokenManager = TokenManager(applicationContext)

        // Salva il primo intent ricevuto all'avvio
        latestIntentData = intent?.data

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                )
                {
                val tokenState = remember { mutableStateOf<String?>(null) }
                val coroutineScope = rememberCoroutineScope()
                val showRegister = remember { mutableStateOf(false) }
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    tokenManager.tokenFlow.collect { token ->
                        tokenState.value = token
                    }
                }

                // ✅ Deep-link token: validate JWT format before saving
                LaunchedEffect(latestIntentData) {
                    val jwt = latestIntentData?.getQueryParameter("token")
                    if (!jwt.isNullOrBlank()) {
                        if (TokenManager.isValidJwt(jwt)) {
                            coroutineScope.launch {
                                tokenManager.saveToken(jwt)
                                tokenState.value = jwt
                            }
                        } else {
                            Log.w("Auth", "Deep-link token failed JWT format validation — ignoring")
                        }
                        latestIntentData = null // always clear after processing
                    }
                }

                if (tokenState.value == null) {
                    if (showRegister.value) {
                        RegisterScreen(
                            onRegisterSuccess = { result ->
                                showRegister.value = false
                                Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                            },
                            onBackToLogin = { showRegister.value = false }
                        )
                    } else {
                        LoginScreen(
                            onLoginSuccess = { newToken ->
                                coroutineScope.launch {
                                    tokenManager.saveToken(newToken)
                                    tokenState.value = newToken
                                }
                            },
                            onRegisterClick = {
                                showRegister.value = true
                            },
                            onGoogleLoginClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("${Constants.BASE_URL}/auth/google")
                                )
                                startActivity(intent)
                            },
                            onForgotPasswordClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("${Constants.BASE_URL}/auth/reset-password")
                                )
                                startActivity(intent)
                            }
                        )
                    }
                } else {
                    DashboardScreen(
                        token = tokenState.value!!,
                        onCreateStreamClick = {
                            val intent = Intent(this@MainActivity, CreateMatchActivity::class.java)
                            startActivity(intent)
                        },
                        onTokenExpired = {
                            lifecycleScope.launch {
                                tokenManager.clearToken()
                                tokenState.value = null
                            }
                        }
                    )
                }
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // ✅ Aggiorna latestIntentData se arriva un nuovo deep link
        latestIntentData = intent?.data
    }

    override fun onRestart() {
        super.onRestart()
        // Se vuoi fare qualcosa al ritorno in foreground
    }
}


suspend fun makeLoginRequest(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
    try {
        val client = getHttpClient()
        val jsonBody = JSONObject().apply {
            put("email", email)
            put("password", password)
        }

        val mediaType = MediaType.get("application/json; charset=utf-8")
        val requestBody = RequestBody.create(mediaType, jsonBody.toString())

        val request = Request.Builder()
            .url("${Constants.BASE_URL}/auth/login")
            .post(requestBody)
            .build()
        val response = client.newCall(request).execute()

        val body = response.body()?.string() ?: ""
        if (response.isSuccessful) {
            val json = JSONObject(body)
            val token = json.getString("access_token")
            AuthResult.Success(token)
        } else {
            AuthResult.Error("Credenziali errate. Riprova o scegli un altro metodo")
        }
    } catch (e: Exception) {
        Log.e("Auth", "Login request failed", e)
        AuthResult.Error("Connessione non riuscita. Controlla la rete e riprova.")
    }
}



@Composable
fun LoginScreen(onLoginSuccess: (String) -> Unit, onRegisterClick: () -> Unit, onGoogleLoginClick: () -> Unit, onForgotPasswordClick: () -> Unit) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    SpikeStreamScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🏐 SPIKESTREAM",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Email Field with modern styling
            SpikeStreamTextField(
                value = email,
                onValueChange = { email = it },
                label = "Email",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )

            Spacer(Modifier.height(16.dp))

            SpikeStreamTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                visualTransformation = PasswordVisualTransformation()
            )

            Spacer(Modifier.height(8.dp))

            // Error Message
            if (errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Login Button with gradient
            SpikeStreamPrimaryButton(
                text = stringResource(R.string.login_title),
                isLoading = isLoading,
                onClick = {
                    isLoading = true
                    errorMessage = null

                    scope.launch {
                        when (val result = makeLoginRequest(email, password)) {
                            is AuthResult.Success -> {
                                isLoading = false
                                onLoginSuccess(result.token)
                            }
                            is AuthResult.Error -> {
                                isLoading = false
                                errorMessage = "Errore: ${result.message}"
                            }
                        }
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // Google Login Button
            SpikeStreamOutlinedButton(
                text = stringResource(R.string.login_google),
                onClick = { onGoogleLoginClick() }
            )

            Spacer(Modifier.height(24.dp))

            // Bottom Links centered and stacked
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = onRegisterClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.login_register),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                TextButton(
                    onClick = onForgotPasswordClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.login_password_forgot),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(16.dp))

                TextButton(
                    onClick = {
                        context.startActivity(Intent(context, InfoActivity::class.java))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Info app",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            }
        }
    }


suspend fun makeGetGamesRequest(token: String): GamesResult = withContext(Dispatchers.IO) {
    try {
        val client = getHttpClient()
        val request = Request.Builder()
            .url("${Constants.BASE_URL}/games")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        val response = client.newCall(request).execute()

        val body = response.body()?.string() ?: "[]"
        if (response.isSuccessful) {
            val json = JSONArray(body)
            val games = mutableListOf<JSONObject>()
            for (i in 0 until json.length()) {
                val game = json.getJSONObject(i)
                games.add(game)
            }
            GamesResult.Success(games)
        } else {
            // Log server details internally; don't expose HTTP codes/body to UI
            val code = response.code()
            Log.w("Games", "Get games failed: HTTP $code")
            GamesResult.Error("Sessione scaduta. Accedi di nuovo.", isAuthError = code == 401)
        }

    } catch (e: Exception) {
        Log.e("Games", "Get games request failed", e)
        GamesResult.Error("Connessione non riuscita. Controlla la rete e riprova.")
    }
}


@Composable
fun DashboardScreen(
    token: String,
    onCreateStreamClick: () -> Unit,
    onTokenExpired: () -> Unit
) {
    val streams = remember { mutableStateListOf<StreamInfo>() }
    val isRefreshing = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as Activity

    fun loadStreams() {
        scope.launch {
            isRefreshing.value = true
            when (val result = makeGetGamesRequest(token)) {
                is GamesResult.Success -> {
                    streams.clear()
                    val streamInfos = result.games.map { json ->
                        StreamInfo(
                            teamA = json.getString("teamAName"),
                            teamB = json.getString("teamBName"),
                            matchId = json.getString("id"),
                            rtmpUrl = json.getString("rtmpUrl")
                        )
                    }
                    streams.addAll(streamInfos)
                }
                is GamesResult.Error -> {
                    isRefreshing.value = false
                    if (result.isAuthError) {
                        onTokenExpired()
                    } else {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            isRefreshing.value = false
        }
    }

    LaunchedEffect(Unit) {
        loadStreams()
    }

    SpikeStreamScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "🏐 Dashboard",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        text = "Your volleyball matches",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }

                Row {
                    IconButton(
                        onClick = {
                            val intent = Intent(context, SettingsActivity::class.java)
                            context.startActivity(intent)
                        }
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    SpikeStreamDangerButton(
                        text = stringResource(R.string.logout),
                        onClick = { onTokenExpired() },
                        outlined = true,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Match List
            SwipeRefresh(
                state = rememberSwipeRefreshState(isRefreshing.value),
                onRefresh = { loadStreams() },
                modifier = Modifier.weight(1f)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (streams.isNotEmpty()) {
                        items(streams) { stream ->
                            // Modern Match Card
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    // Match Title
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Start,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stream.title,
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                    }

                                    Spacer(Modifier.height(16.dp))

                                    // Action Buttons
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SpikeStreamPrimaryButton(
                                            text = stringResource(R.string.open),
                                            onClick = {
                                                val intent = Intent(context, MatchOptionsActivity::class.java).apply {
                                                    putExtra("RTMP_URL", stream.rtmpUrl)
                                                    putExtra("TEAM_1", stream.teamA)
                                                    putExtra("TEAM_2", stream.teamB)
                                                    putExtra("MATCH_ID", stream.matchId)
                                                }
                                                context.startActivity(intent)
                                            },
                                            modifier = Modifier.weight(1f)
                                        )

                                        SpikeStreamDangerButton(
                                            text = stringResource(R.string.delete),
                                            onClick = {
                                                scope.launch {
                                                    val success = makeDeleteMatch(token, stream.matchId)
                                                    if (success) {
                                                        streams.remove(stream)
                                                    } else {
                                                        Toast.makeText(context, context.getString(R.string.deletion_error), Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            outlined = true
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            // Empty State
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "🏐",
                                    style = MaterialTheme.typography.displayLarge
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.no_match),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Helper Text
            Text(
                text = stringResource(R.string.no_match1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Add Match Button
            SpikeStreamPrimaryButton(
                text = "➕ " + stringResource(R.string.add_match),
                onClick = {
                    if ((context as MainActivity).isRewardedAdLoaded) {
                        (context as MainActivity).rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() {
                                (context as MainActivity).isRewardedAdLoaded = false
                                onCreateStreamClick()
                            }

                            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                onCreateStreamClick()
                            }
                        }

                        (context as MainActivity).rewardedAd.show(activity) {
                            // onUserEarnedReward
                        }
                    } else {
                        onCreateStreamClick()
                    }
                }
            )
        }
    }
}



suspend fun makeDeleteMatch(token: String, matchId: String): Boolean = withContext(
    Dispatchers.IO) {
    try {
        val client = getHttpClient()
        val request = Request.Builder()
            .url("${Constants.BASE_URL}/games/$matchId")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()

        val response = client.newCall(request).execute()
        response.isSuccessful

    } catch (e: Exception) {
        Log.e("Games", "Delete match failed", e)
        false
    }
}


@Composable
fun RegisterScreen(onRegisterSuccess: (String) -> Unit, onBackToLogin: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val successMsg = stringResource(R.string.register_success)

    SpikeStreamScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🏐 SPIKESTREAM",
                style = MaterialTheme.typography.displaySmall,
                color = com.leonardos.spikestream.ui.theme.VolleyballOrange,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = stringResource(R.string.register_title),
                style = MaterialTheme.typography.headlineSmall,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(bottom = 32.dp)
            )

            SpikeStreamTextField(
                value = email,
                onValueChange = { email = it },
                label = "Email",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )

            Spacer(Modifier.height(16.dp))

            SpikeStreamTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                visualTransformation = PasswordVisualTransformation()
            )

            Spacer(Modifier.height(16.dp))

            if (message != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = message!!,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            SpikeStreamPrimaryButton(
                text = stringResource(R.string.register_title),
                isLoading = isLoading,
                onClick = {
                    isLoading = true
                    message = null

                    scope.launch {
                        val result = makeRegisterRequest(email, password)
                        message = result
                        isLoading = false
                        if (result?.startsWith("Success") == true) {
                            onRegisterSuccess(successMsg)
                        }
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            TextButton(onClick = onBackToLogin) {
                Text(
                    text = stringResource(R.string.register_login_back),
                    color = com.leonardos.spikestream.ui.theme.AccentCyan
                )
            }
        }
    }
}


suspend fun makeRegisterRequest(email: String, password: String): String? = withContext(Dispatchers.IO) {
    try {
        val client = getHttpClient()
        val json = JSONObject()
        json.put("email", email)
        json.put("password", password)

        val mediaType = MediaType.get("application/json; charset=utf-8")
        val requestBody = RequestBody.create(mediaType, json.toString())

        val request = Request.Builder()
            .url("${Constants.BASE_URL}/users")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()

        if (response.isSuccessful) {
            "Success! Controlla l'email per confermare."
        } else {
            // Log server detail privately; show user a generic message
            Log.w("Auth", "Register failed: HTTP ${response.code()}")
            "Registrazione non riuscita. Controlla i dati e riprova."
        }

    } catch (e: Exception) {
        Log.e("Auth", "Register request failed", e)
        "Connessione non riuscita. Controlla la rete e riprova."
    }
}
