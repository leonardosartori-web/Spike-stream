@file:OptIn(ExperimentalMaterial3Api::class)

package com.leonardos.spikestream.activities

import com.leonardos.spikestream.BuildConfig
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import com.leonardos.spikestream.utils.Logger as Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.lifecycleScope
import com.leonardos.spikestream.ui.theme.*
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
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
import kotlinx.coroutines.launch
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.leonardos.spikestream.utils.Constants
import com.leonardos.spikestream.R
import com.leonardos.spikestream.utils.RemoteConfigManager
import com.leonardos.spikestream.utils.TourManager
import com.leonardos.spikestream.ui.components.TourOverlay
import com.leonardos.spikestream.ui.components.TourStep
import com.leonardos.spikestream.ui.components.rememberTourController
import com.leonardos.spikestream.ui.components.tourHighlight
import kotlinx.coroutines.delay
import com.leonardos.spikestream.data.*

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class TokenManager(private val context: Context) {

    private val PREFS_NAME = "secure_user_prefs"

    // Usiamo 'lazy' così l'inizializzazione avviene solo quando serve
    // e possiamo gestire l'eventuale crash del KeyStore
    private val sharedPrefs: SharedPreferences by lazy {
        try {
            createPrefs()
        } catch (e: Exception) {
            // Se arriviamo qui, il refactoring ha corrotto il legame con le chiavi
            android.util.Log.e("TokenManager", "Errore KeyStore rilevato. Reset delle preferenze.")

            // 1. Cancelliamo i dati corrotti fisicamente
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()

            // 2. Riprova a creare le preferenze (ora funzionerà perché il file è pulito)
            createPrefs()
        }
    }

    private fun createPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Inizializziamo il Flow in modo sicuro
    private val _tokenFlow = MutableStateFlow<String?>(
        try { sharedPrefs.getString("auth_token", null) } catch (e: Exception) { null }
    )
    val tokenFlow: Flow<String?> = _tokenFlow.asStateFlow()
    val currentToken: String? get() = _tokenFlow.value?.takeIf { it.isNotBlank() }

    companion object {
        private val AUTH_LOCK = Any()
        private val JWT_REGEX = Regex("^[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+$")
        fun isValidJwt(token: String): Boolean = JWT_REGEX.matches(token)
    }

    fun beginGoogleLogin(): String = synchronized(AUTH_LOCK) {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val state = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        check(sharedPrefs.edit().putString("pending_google_state", state).putLong("pending_google_started", System.currentTimeMillis()).commit())
        state
    }
    fun cancelGoogleLogin() = synchronized(AUTH_LOCK) {
        sharedPrefs.edit().remove("pending_google_state").remove("pending_google_started").apply()
    }
    suspend fun acceptGoogleCallback(raw: String): Boolean {
        val candidate = AppAuthCallback.token(raw) ?: return false
        val state = runCatching {
            org.json.JSONObject(String(android.util.Base64.decode(candidate.split('.')[1], android.util.Base64.URL_SAFE), Charsets.UTF_8)).optString("appLoginState")
        }.getOrNull() ?: return false
        fun pendingMatches() = AppAuthCallback.matches(sharedPrefs.getString("pending_google_state", null), state,
            sharedPrefs.getLong("pending_google_started", 0), System.currentTimeMillis())
        if (!synchronized(AUTH_LOCK) { pendingMatches() }) return false
        // The backend verifies the signature, expiry, account and access-token type.
        val verified = AccountAccessApi.refresh(candidate) ?: return false
        if (!isValidJwt(verified)) return false
        return withContext(Dispatchers.IO) {
            synchronized(AUTH_LOCK) {
                if (!pendingMatches()) false else {
                    val saved = sharedPrefs.edit().putString("auth_token", verified)
                        .remove("pending_google_state").remove("pending_google_started").commit()
                    if (saved) _tokenFlow.value = verified
                    saved
                }
            }
        }
    }

    suspend fun saveToken(token: String) {
        withContext(Dispatchers.IO) {
            try {
                synchronized(AUTH_LOCK) {
                    sharedPrefs.edit().putString("auth_token", token).remove("pending_google_state").remove("pending_google_started").apply()
                    _tokenFlow.value = token
                }
            } catch (e: Exception) {
                android.util.Log.e("TokenManager", "Impossibile salvare il token")
            }
        }
    }

    suspend fun clearToken() {
        withContext(Dispatchers.IO) {
            try {
                synchronized(AUTH_LOCK) {
                    sharedPrefs.edit().remove("auth_token").remove("pending_google_state").remove("pending_google_started").apply()
                    _tokenFlow.value = null
                }
            } catch (e: Exception) {
                android.util.Log.e("TokenManager", "Impossibile cancellare il token")
            }
        }
    }
}


data class StreamInfo(
    val teamA: String,
    val teamB: String,
    val matchId: String,
    val rtmpUrl: String,
    val youtubeChannelId: String? = null,
    val youtubeChannelTitle: String? = null,
    val youtubeBroadcastId: String? = null,
    val youtubeManaged: Boolean = false
) {
    val title: String get() = "$teamA vs $teamB"
}


class MainActivity : AnimatedAppActivity() {

    private var isAppStarted = false
    private var hasAdShown = false
    private var isMobileAdsInitializeCalled = AtomicBoolean(false)
    private lateinit var consentInformation: ConsentInformation
    private lateinit var tokenManager: TokenManager
    private var latestIntentData by mutableStateOf<Uri?>(null)
    private var allowAppOpenAdForThisLaunch = true
    private lateinit var appUpdateManager: AppUpdateManager
    private var updateBlocked by mutableStateOf(false)
    private var updateBusy by mutableStateOf(false)
    private var updateStatus by mutableStateOf("")
    private var startupContinued = false
    private var configLoaded = false
    private val updateLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        updateFlowLaunched = false
        updateBusy = false
        if (result.resultCode != Activity.RESULT_OK) showUpdateFailure()
        else updateStatus = updateText("Riavvia l’app dopo l’aggiornamento.", "Restart the app after updating.")
    }
    private var updateGateResolved = false
    private var startRequested = false
    private var updateCheckInFlight = false
    private var updateFlowLaunched = false
    lateinit var rewardedAd: RewardedAd
    var isRewardedAdLoaded = false
    private val refreshTrigger = mutableStateOf(0)

    private val TAG_APP_OPEN = "APP_OPEN_FLOW"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        tokenManager = TokenManager(applicationContext)
        latestIntentData = intent?.takeIf { it.action == Intent.ACTION_VIEW && AppAuthCallback.token(it.dataString) != null }?.data
        appUpdateManager = AppUpdateManagerFactory.create(this)

        val hasStoredAuthentication = !tokenManager.currentToken.isNullOrBlank()
        val isAuthenticationCallback =
            !latestIntentData?.getQueryParameter("token").isNullOrBlank()

        // An unauthenticated user must see Login immediately. Do not hold the
        // authentication routing behind Remote Config, UMP or an App Open Ad.
        allowAppOpenAdForThisLaunch = hasStoredAuthentication && !isAuthenticationCallback
        if (!hasStoredAuthentication || isAuthenticationCallback) {
            startApp()
        }

        if (!isAppStarted) {
            setContent {
                RefinedApplicationTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }

        RemoteConfigManager.init {
            if (!isDestroyed && !isFinishing) {
                configLoaded = true
                evaluateUpdatePolicy()
            }
        }
    }

    private fun startApp() {
        Log.d(TAG_APP_OPEN, "startApp() called | hasStartedApp=$isAppStarted")

        if (!updateGateResolved) {
            startRequested = true
            Log.d(TAG_APP_OPEN, "startApp() waiting for mandatory update check")
            return
        }

        if (isAppStarted) {
            Log.d(TAG_APP_OPEN, "startApp() blocked (already started)")
            return
        }

        isAppStarted = true

        Log.d(TAG_APP_OPEN, "Launching startCompose()")

        runOnUiThread {
            startCompose()
        }
    }

    private fun initializeConsentFlow() {
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()

        consentInformation = UserMessagingPlatform.getConsentInformation(this)

        consentInformation.requestConsentInfoUpdate(
            this,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) {
                    // L'utente ha chiuso il form (avendo accettato o rifiutato).
                    // Inizializziamo AdMob in ogni caso per consentire gli annunci limitati.
                    initializeMobileAdsSdk()
                }
            },
            { error ->
                // Se il controllo del consenso fallisce (es. offline), andiamo comunque avanti
                initializeMobileAdsSdk()
            }
        )
    }

    private fun initializeMobileAdsSdk() {
        val already = isMobileAdsInitializeCalled.getAndSet(true)
        if (already) return

        MobileAds.initialize(this)

        val canShowAds = consentInformation.canRequestAds()

        // Gestione App Open Ad
        if (
            allowAppOpenAdForThisLaunch &&
            RemoteConfigManager.isAppOpenEnabled() &&
            canShowAds
        ) {
            loadAppOpenAd()
        } else {
            startApp()
        }

        if (RemoteConfigManager.isCreateMatchEnabled()) {
            loadRewardedAd()
        }
    }

    private fun loadAppOpenAd() {

        val adRequest = AdRequest.Builder().build()

        // ⏱ TIMEOUT 5 SECONDI
        lifecycleScope.launch {

            delay(5000)

            if (!isAppStarted && !hasAdShown) {
                startApp()
            }
        }

        AppOpenAd.load(
            this,
            BuildConfig.ADMOB_APP_OPEN_ID,
            adRequest,
            AppOpenAd.APP_OPEN_AD_ORIENTATION_PORTRAIT,
            object : AppOpenAd.AppOpenAdLoadCallback() {

                override fun onAdLoaded(ad: AppOpenAd) {


                    if (isAppStarted) {
                        return
                    }

                    hasAdShown = true

                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {

                        override fun onAdDismissedFullScreenContent() {
                            startApp()
                        }

                        override fun onAdFailedToShowFullScreenContent(error: AdError) {
                            startApp()
                        }

                        override fun onAdShowedFullScreenContent() {

                        }
                    }

                    ad.show(this@MainActivity)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    startApp()
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (
            ::appUpdateManager.isInitialized &&
            configLoaded &&
            !updateFlowLaunched
        ) {
            evaluateUpdatePolicy()
        }
        if (updateGateResolved && RemoteConfigManager.isCreateMatchEnabled()) {
            loadRewardedAd()
        }
    }

    private fun updateText(it: String, en: String): String =
        if (resources.configuration.locales[0].language == "it") it else en

    private fun evaluateUpdatePolicy() {
        if (isDestroyed || isFinishing) return
        if (!com.leonardos.spikestream.utils.UpdatePolicy.requiresUpdate(BuildConfig.VERSION_CODE.toLong(), RemoteConfigManager.minimumSupportedVersionCode())) {
            val wasBlocked = updateBlocked
            updateBlocked = false
            resolveUpdateGate()
            if (wasBlocked && isAppStarted) startCompose()
            if (!startupContinued) {
                startupContinued = true
                if (RemoteConfigManager.isAdsEnabled()) initializeConsentFlow() else startApp()
            }
            return
        }
        updateGateResolved = false
        if (updateBlocked) return
        updateBlocked = true
        setContent {
            RefinedApplicationTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(updateText("Aggiornamento necessario", "Update required"),
                            style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        Text(updateText(
                            "Per continuare a usare SpikeStream, aggiorna l’app da Google Play.",
                            "To continue using SpikeStream, update the app from Google Play."
                        ), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text(updateStatus, textAlign = TextAlign.Center)
                        if (updateBusy) CircularProgressIndicator()
                        Button(onClick = { checkForUpdates() }, enabled = !updateBusy) {
                            Text(updateText("Aggiorna", "Update"))
                        }
                        TextButton(onClick = {
                            updateBusy = true
                            RemoteConfigManager.refresh {
                                if (!isDestroyed && !isFinishing) {
                                    updateBusy = false
                                    updateStatus = updateText("Se necessario, controlla la connessione e riprova tra poco.",
                                        "If needed, check your connection and try again shortly.")
                                    evaluateUpdatePolicy()
                                }
                            }
                        }, enabled = !updateBusy) { Text(updateText("Riprova controllo", "Check again")) }
                        TextButton(onClick = { openPlayStore() }, enabled = !updateBusy) {
                            Text(updateText("Apri Google Play", "Open Google Play"))
                        }
                    }
                }
            }
        }
    }

    private fun checkForUpdates() {
        if (updateCheckInFlight || updateFlowLaunched) return

        val minimumSupportedVersion = RemoteConfigManager.minimumSupportedVersionCode()
        if (BuildConfig.VERSION_CODE.toLong() >= minimumSupportedVersion) {
            resolveUpdateGate()
            return
        }

        updateCheckInFlight = true
        updateBusy = true
        updateStatus = ""
        val request = ++updateRequestGeneration
        lifecycleScope.launch {
            delay(10000)
            if (request == updateRequestGeneration && !updateFlowLaunched) {
                updateRequestGeneration++
                updateCheckInFlight = false
                showUpdateFailure()
            }
        }

        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                if (isDestroyed || isFinishing || request != updateRequestGeneration) return@addOnSuccessListener
                updateRequestGeneration++
                updateCheckInFlight = false
                when (appUpdateInfo.updateAvailability()) {
                    UpdateAvailability.UPDATE_AVAILABLE -> {
                        if (appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                            launchRequiredUpdate(appUpdateInfo)
                        } else {
                            showUpdateFailure()
                        }
                    }

                    UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                        launchRequiredUpdate(appUpdateInfo)
                    }

                    // Remote Config has already marked this build unsupported.
                    // If Play has not propagated the replacement yet, do not
                    // let the obsolete build enter the app.
                    else -> showUpdateFailure()
                }
            }
            .addOnFailureListener {
                if (isDestroyed || isFinishing || request != updateRequestGeneration) return@addOnFailureListener
                updateRequestGeneration++
                updateCheckInFlight = false
                // Remote Config says this build is unsupported. If Play cannot
                // start its update flow, keep the old build out of the app and
                // direct the user to the store instead.
                showUpdateFailure()
            }
    }

    private fun launchRequiredUpdate(appUpdateInfo: com.google.android.play.core.appupdate.AppUpdateInfo) {
        if (updateFlowLaunched) return
        updateFlowLaunched = true
        val launched = runCatching { appUpdateManager.startUpdateFlowForResult(
            appUpdateInfo,
            updateLauncher,
            com.google.android.play.core.appupdate.AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
        ) }.getOrDefault(false)
        if (!launched) {
            updateFlowLaunched = false
            showUpdateFailure()
        }
    }

    private fun resolveUpdateGate() {
        updateGateResolved = true
        if (startRequested) startApp()
    }

    private var updateRequestGeneration = 0

    private fun showUpdateFailure() {
        updateBusy = false
        updateStatus = updateText(
            "Aggiornamento non disponibile o non completato. Controlla la connessione, riprova o apri Google Play.",
            "Update unavailable or not completed. Check your connection, try again or open Google Play."
        )
    }

    private fun openPlayStore() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        }.recoverCatching {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }.onFailure { showUpdateFailure() }
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
        setContent {
            RefinedApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                )
                {
                val tokenState = remember { mutableStateOf(tokenManager.currentToken) }
                val coroutineScope = rememberCoroutineScope()
                val showRegister = remember { mutableStateOf(false) }
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    tokenManager.tokenFlow.collect { token ->
                        tokenState.value = token
                    }
                }

                // Consume only callbacks bound to a login started by this app.
                LaunchedEffect(latestIntentData) {
                    val callback = latestIntentData ?: return@LaunchedEffect
                    val accepted = try { tokenManager.acceptGoogleCallback(callback.toString()) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    if (latestIntentData == callback) {
                        latestIntentData = null
                        this@MainActivity.intent?.data = null
                    }
                    if (accepted) tokenState.value = tokenManager.currentToken
                    else Toast.makeText(context, R.string.auth_callback_rejected, Toast.LENGTH_LONG).show()
                }

                AnimatedContent(
                    targetState = tokenState.value,
                    transitionSpec = {
                        (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 18 }) togetherWith
                            (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 24 })
                    },
                    label = "main_screen_transition"
                ) { activeToken ->
                    if (activeToken == null) {
                        AnimatedContent(
                            targetState = showRegister.value,
                            transitionSpec = {
                                (fadeIn(tween(200)) + slideInHorizontally(tween(220)) { if (targetState) it / 10 else -it / 10 }) togetherWith
                                    (fadeOut(tween(140)) + slideOutHorizontally(tween(180)) { if (targetState) -it / 12 else it / 12 })
                            },
                            label = "auth_screen_transition"
                        ) { showRegistration ->
                            if (showRegistration) {
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
                                    onRegisterClick = { showRegister.value = true },
                                    onGoogleLoginClick = {
                                        launchGoogleLogin(this@MainActivity)
                                    },
                                    onForgotPasswordClick = {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${Constants.BASE_URL}/auth/reset-password")))
                                    }
                                )
                            }
                        }
                    } else {
                        DashboardScreen(
                            token = activeToken,
                            refreshTrigger = refreshTrigger.value,
                            onCreateStreamClick = {
                                startActivity(Intent(this@MainActivity, CreateMatchActivity::class.java))
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // State-backed so an authentication callback is processed even when the
        // existing MainActivity instance receives the deep link.
        latestIntentData = intent.takeIf { it.action == Intent.ACTION_VIEW && AppAuthCallback.token(it.dataString) != null }?.data
    }

    override fun onRestart() {
        super.onRestart()
        refreshTrigger.value++
    }
}

@Composable
fun LoginScreen(
    onLoginSuccess: (String) -> Unit,
    onRegisterClick: () -> Unit,
    onGoogleLoginClick: () -> Unit,
    onForgotPasswordClick: () -> Unit
) {
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
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Compact Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                SpikeStreamBrandMark(size = 64.dp)
                
                Spacer(Modifier.width(20.dp))
                
                Column {
                    Text(
                        text = "SPIKESTREAM",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            SpikeStreamGlassCard {
                Text(
                    text = stringResource(R.string.login_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                SpikeStreamTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "Email",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) }
                )

                Spacer(Modifier.height(16.dp))

                SpikeStreamTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) }
                )

                AnimatedVisibility(
                    visible = errorMessage != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }

                Spacer(Modifier.height(24.dp))

                SpikeStreamPrimaryButton(
                    text = stringResource(R.string.login_title),
                    isLoading = isLoading,
                    onClick = {
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            when (val result = StreamApi.makeLoginRequest(email, password)) {
                                is AuthResult.Success -> {
                                    isLoading = false
                                    onLoginSuccess(result.token)
                                }
                                is AuthResult.Error -> {
                                    isLoading = false
                                    errorMessage = result.message
                                }
                            }
                        }
                    }
                )

                Spacer(Modifier.height(12.dp))

                SpikeStreamOutlinedButton(
                    text = stringResource(R.string.login_google),
                    onClick = onGoogleLoginClick
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(onClick = onRegisterClick) {
                    Text(
                        text = stringResource(R.string.login_register),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            TextButton(
                onClick = onForgotPasswordClick,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.login_password_forgot),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = { context.startActivity(Intent(context, InfoActivity::class.java)) }
            ) {
                Text(
                    text = HelpLabels.forLanguage(context.resources.configuration.locales[0].language).title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(
    token: String,
    refreshTrigger: Int,
    onCreateStreamClick: () -> Unit,
    onTokenExpired: () -> Unit
) {
    val streams = remember { mutableStateListOf<StreamInfo>() }
    val isRefreshing = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as Activity

    val tourController = rememberTourController(
        tourKey = TourManager.KEY_DASHBOARD,
        steps = listOf(
            TourStep(
                id = "match_card",
                emoji = "🏐",
                title = context.getString(R.string.tour_dashboard_step1_title),
                body = context.getString(R.string.tour_dashboard_step1_body),
                highlightKey = "match_card"
            ),
            TourStep(
                id = "fab_create",
                emoji = "➕",
                title = context.getString(R.string.tour_dashboard_step2_title),
                body = context.getString(R.string.tour_dashboard_step2_body),
                highlightKey = "fab_create"
            )
        )
    )

    fun loadStreams() {
        scope.launch {
            isRefreshing.value = true
            when (val result = StreamApi.makeGetGamesRequest(token)) {
                is GamesResult.Success -> {
                    val streamInfos = result.games.map { json ->
                        StreamInfo(
                            teamA = json.getString("teamAName"),
                            teamB = json.getString("teamBName"),
                            matchId = json.getString("id"),
                            rtmpUrl = json.getString("rtmpUrl"),
                            youtubeChannelId = json.optionalYouTubeString("youtubeChannelId"),
                            youtubeChannelTitle = json.optionalYouTubeString("youtubeChannelTitle"),
                            youtubeBroadcastId = json.optionalYouTubeString("youtubeBroadcastId"),
                            youtubeManaged = json.optBoolean("youtubeManaged", false)
                        )
                    }
                    streams.clear()
                    streams.addAll(streamInfos)
                    
                    // Sync recent RTMP list with current active matches
                    scope.launch(Dispatchers.IO) {
                        try {
                            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                            val prefs = EncryptedSharedPreferences.create(
                                context,
                                "secure_user_prefs",
                                masterKey,
                                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                            )
                            val currentUrls = streamInfos.map { it.rtmpUrl }.filter { it.isNotBlank() }.toSet()
                            prefs.edit().putStringSet("recent_rtmps", currentUrls).apply()
                        } catch (e: Exception) {
                            Log.e("Dashboard", "Failed to sync recent RTMPs", e)
                        }
                    }
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

    LaunchedEffect(refreshTrigger) {
        loadStreams()
    }

    SpikeStreamScreen(applySafeInsets = false) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.dashboard_title),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = stringResource(R.string.dashboard_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            context.startActivity(Intent(context, InfoActivity::class.java))
                        }) {
                            Icon(Icons.Default.Info, contentDescription = HelpLabels.forLanguage(context.resources.configuration.locales[0].language).title)
                        }
                        IconButton(onClick = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                        IconButton(onClick = onTokenExpired) {
                            Icon(Icons.Default.ExitToApp, contentDescription = "Logout", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    )
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    modifier = Modifier.Companion.tourHighlight(tourController, "fab_create", RoundedCornerShape(16.dp)),
                    onClick = {
                        if (
                                RemoteConfigManager.isCreateMatchEnabled() &&
                                (context as MainActivity).isRewardedAdLoaded
                            ) {
                            (context as MainActivity).rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                                override fun onAdDismissedFullScreenContent() {
                                    (context as MainActivity).isRewardedAdLoaded = false
                                    onCreateStreamClick()
                                }

                                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                    onCreateStreamClick()
                                }
                            }

                            (context as MainActivity).rewardedAd.show(activity) { }
                        } else {
                            onCreateStreamClick()
                        }
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_match_fab)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        ) { padding ->
            SwipeRefresh(
                state = rememberSwipeRefreshState(isRefreshing.value),
                onRefresh = { loadStreams() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (streams.isEmpty() && !isRefreshing.value) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.no_match),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(streams) { stream ->
                            SpikeStreamGlassCard(
                                modifier = Modifier.tourHighlight(tourController, "match_card", RoundedCornerShape(24.dp)),
                                onClick = {
                                    val intent = Intent(context, MatchOptionsActivity::class.java).apply {
                                        putExtra("TEAM_1", stream.teamA)
                                        putExtra("TEAM_2", stream.teamB)
                                        putExtra("MATCH_ID", stream.matchId)
                                        putExtra("RTMP_URL", stream.rtmpUrl)
                                        putExtra("YOUTUBE_MANAGED", stream.youtubeManaged)
                                        putExtra("YOUTUBE_BROADCAST_ID", stream.youtubeBroadcastId)
                                    }
                                    context.startActivity(intent)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SpikeStreamBrandMark(size = 48.dp)

                                    Spacer(Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = stream.teamA,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = " VS ",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Black,
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )
                                            Text(
                                                text = stream.teamB,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        val channelUrl = youtubeChannelUrl(stream.youtubeChannelId)
                                        if (channelUrl != null) {
                                            stream.youtubeChannelTitle?.let { channelTitle ->
                                                Text(
                                                    text = "YouTube · $channelTitle",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            val watchUrl = youtubeWatchUrl(stream.youtubeChannelId, stream.youtubeBroadcastId)
                                            TextButton(onClick = {
                                                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl ?: channelUrl))
                                                try {
                                                    context.startActivity(viewIntent)
                                                } catch (_: android.content.ActivityNotFoundException) {
                                                    Toast.makeText(context, context.getString(R.string.youtube_open_failed), Toast.LENGTH_SHORT).show()
                                                }
                                            }) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(if (watchUrl != null) R.string.youtube_watch_match else R.string.youtube_open_channel))
                                            }
                                        }
                                    }

                                    Icon(
                                        Icons.Default.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        // Bottom spacer for FAB
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
        TourOverlay(controller = tourController)
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
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                SpikeStreamBrandMark(size = 48.dp)
                
                Spacer(Modifier.width(16.dp))
                
                Text(
                    text = stringResource(R.string.create_account_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                )
            }

            Spacer(Modifier.height(16.dp))

            SpikeStreamGlassCard {
                SpikeStreamTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "Email",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) }
                )

                Spacer(Modifier.height(16.dp))

                SpikeStreamTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) }
                )

                AnimatedVisibility(visible = message != null) {
                    Text(
                        text = message ?: "",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }

                Spacer(Modifier.height(32.dp))

                SpikeStreamPrimaryButton(
                    text = stringResource(R.string.register_title),
                    isLoading = isLoading,
                    onClick = {
                        isLoading = true
                        message = null
                        scope.launch {
                            val result = StreamApi.makeRegisterRequest(email, password)
                            message = result
                            isLoading = false
                            if (result?.startsWith("Success") == true) {
                                onRegisterSuccess(successMsg)
                            }
                        }
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            TextButton(onClick = onBackToLogin) {
                Text(
                    text = stringResource(R.string.register_login_back),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
