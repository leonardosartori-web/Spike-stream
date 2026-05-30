package com.leonardos.spikestream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import com.leonardos.spikestream.Logger as Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.auth.GoogleAuthUtil
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import com.leonardos.spikestream.ui.theme.MyApplicationTheme
import com.leonardos.spikestream.ui.theme.SpikeStreamDialog
import com.leonardos.spikestream.ui.theme.SpikeStreamGlassCard
import com.leonardos.spikestream.ui.theme.SpikeStreamScreen
import com.leonardos.spikestream.ui.theme.SpikeStreamTextField
import com.leonardos.spikestream.ui.theme.SpikeStreamPrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONObject


class CreateMatchActivity: ComponentActivity() {

    private lateinit var tokenManager: TokenManager
    private lateinit var callbackManager: CallbackManager

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        callbackManager.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenManager = TokenManager(applicationContext)
        callbackManager = CallbackManager.Factory.create()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                )
                {
                    val tokenState = remember { mutableStateOf<String?>(null) }
                    val coroutineScope = rememberCoroutineScope()

                    LaunchedEffect(Unit) {
                        tokenManager.tokenFlow.collect { token ->
                            tokenState.value = token
                        }
                    }

                    val showRegister = remember { mutableStateOf(false) }

                    if (tokenState.value == null) {
                        // Mostra la login
                        LoginScreen(onLoginSuccess = { newToken ->
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
                    } else {
                        CreateMatchScreen(tokenManager, callbackManager)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun CreateMatchScreen(tokenManager: TokenManager, callbackManager: CallbackManager) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val token by tokenManager.tokenFlow.collectAsState(initial = null)

        var team1 by remember { mutableStateOf("") }
        var team2 by remember { mutableStateOf("") }
        var streamUrl by remember { mutableStateOf("rtmp://") }
        var isLoading by remember { mutableStateOf(false) }
        var isYouTubeLoading by remember { mutableStateOf(false) }

        // Gestione RTMP Recenti (Sincronizzati da MainActivity)
        val masterKey = remember { MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }
        val prefs = remember { 
            EncryptedSharedPreferences.create(
                context,
                "secure_user_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        var recentRtmps by remember { mutableStateOf(emptyList<String>()) }

        LaunchedEffect(Unit) {
            recentRtmps = prefs.getStringSet("recent_rtmps", emptySet())?.toList() ?: emptyList()
        }

        // Google Sign-In setup
        val gso = remember {
            Log.d("YouTubeAuth", "Using Client ID: ${BuildConfig.GOOGLE_WEB_CLIENT_ID}")
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .requestScopes(Scope("https://www.googleapis.com/auth/youtube.readonly"))
                .build()
        }
        val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

        // Facebook Login setup - already initialized in Activity
        
        // Callback registration
        LaunchedEffect(Unit) {
            LoginManager.getInstance().registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    val fbAccessToken = result.accessToken.token
                    if (token != null) {
                        scope.launch {
                            isYouTubeLoading = true
                            val resultRtmp = fetchFacebookRTMP(fbAccessToken, token!!)
                            if (resultRtmp != null) {
                                streamUrl = resultRtmp
                                Toast.makeText(context, context.getString(R.string.fb_load_success), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.fb_load_error), Toast.LENGTH_LONG).show()
                            }
                            isYouTubeLoading = false
                        }
                    } else {
                        Toast.makeText(context, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onCancel() { Log.d("FacebookAuth", "Login cancelled") }
                override fun onError(error: FacebookException) {
                    Log.e("FacebookAuth", "Login error", error)
                    Toast.makeText(context, context.getString(R.string.fb_load_error), Toast.LENGTH_LONG).show()
                }
            })
        }

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                if (account != null) {
                    scope.launch {
                        isYouTubeLoading = true
                        val result = fetchYouTubeRTMP(context, account, token!!)
                        when (result) {
                            is YouTubeFetchResult.Success -> {
                                streamUrl = result.rtmpUrl
                                Toast.makeText(context, context.getString(R.string.yt_load_success), Toast.LENGTH_SHORT).show()
                            }
                            is YouTubeFetchResult.RateLimit -> {
                                Toast.makeText(context, context.getString(R.string.yt_rate_limit_error), Toast.LENGTH_LONG).show()
                            }
                            is YouTubeFetchResult.Error -> {
                                Toast.makeText(context, context.getString(R.string.yt_load_error), Toast.LENGTH_LONG).show()
                            }
                        }
                        isYouTubeLoading = false
                    }
                }
            } catch (e: Exception) {
                Log.e("YouTubeAuth", "Sign in failed", e)
                Toast.makeText(context, context.getString(R.string.yt_login_error), Toast.LENGTH_SHORT).show()
            }
        }

        SpikeStreamScreen {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(40.dp))

                Text(
                    text = stringResource(R.string.create_match_title).uppercase(),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = stringResource(R.string.configure_stream_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )

                Spacer(Modifier.height(40.dp))

                SpikeStreamGlassCard {
                    Text(
                        text = stringResource(R.string.match_details_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    SpikeStreamTextField(
                        value = team1,
                        onValueChange = { team1 = it },
                        label = stringResource(R.string.local_team),
                        leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) }
                    )

                    Spacer(Modifier.height(16.dp))

                    SpikeStreamTextField(
                        value = team2,
                        onValueChange = { team2 = it },
                        label = stringResource(R.string.guest_team),
                        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) }
                    )

                    //Spacer(Modifier.height(24.dp))
                    TextButton(onClick = {
                        val message = HtmlCompat.fromHtml(
                            context.getString(R.string.guide_body),
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                        )

                        val textView = TextView(context).apply {
                            text = message
                            movementMethod = LinkMovementMethod.getInstance()
                            setPadding(48, 32, 48, 0)
                            setTextIsSelectable(true)
                        }

                        android.app.AlertDialog.Builder(context)
                            .setTitle(context.getString(R.string.guide_title))
                            .setView(textView)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.guide_button_show))
                    }

                    Text(
                        text = stringResource(R.string.streaming_url_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    Text(
                        text = stringResource(R.string.rtmp_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    SpikeStreamTextField(
                        value = streamUrl,
                        onValueChange = { streamUrl = it },
                        label = stringResource(R.string.url_rtmp_label),
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                    )

                    Spacer(Modifier.height(12.dp))

                    // 1. Usa indirizzo recente (Scelta principale consigliata)
                    /*if (recentRtmps.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.use_recent_rtmp),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        recentRtmps.take(3).forEach { rtmp ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        streamUrl = rtmp
                                        Toast.makeText(context, context.getString(R.string.recent_loaded_toast), Toast.LENGTH_SHORT).show()
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = rtmp,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }*/

                    // Tasto "Recenti" completo, reso molto evidente (Pieno e grande)
                    var showRecentMenu by remember { mutableStateOf(false) }

                    Button(
                        onClick = { showRecentMenu = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        enabled = recentRtmps.isNotEmpty()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.recent_rtmp_title).uppercase(),
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    if (showRecentMenu) {
                        SpikeStreamDialog(
                            onDismissRequest = { showRecentMenu = false },
                            title = stringResource(R.string.recent_rtmp_title),
                            icon = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) },
                            content = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                ) {
                                    recentRtmps.forEach { rtmp ->
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { 
                                                    streamUrl = rtmp
                                                    showRecentMenu = false
                                                },
                                            color = Color.Transparent
                                        ) {
                                            Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) {
                                                Text(
                                                    text = rtmp,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(top = 12.dp),
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showRecentMenu = false }) {
                                    Text(stringResource(R.string.close_button), fontWeight = FontWeight.Bold)
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    Spacer(Modifier.height(16.dp))

                    // 2. Social Networks (Secondari e meno evidenti)
                    Text(
                        text = stringResource(R.string.or_social_rtmp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Bottone YouTube (Secondario, sottile OutlinedButton)
                        OutlinedButton(
                            onClick = { 
                                googleSignInClient.signOut().addOnCompleteListener {
                                    launcher.launch(googleSignInClient.signInIntent)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.Red
                            ),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isYouTubeLoading
                        ) {
                            if (isYouTubeLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Red)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).background(Color.Red, CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text("YOUTUBE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Bottone Facebook (Secondario, sottile OutlinedButton)
                        OutlinedButton(
                            onClick = {
                                LoginManager.getInstance().logIn(
                                    context as Activity,
                                    listOf("publish_video")
                                )
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, Color(0xFF1877F2).copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF1877F2)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isYouTubeLoading
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(Color(0xFF1877F2), CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text("FACEBOOK", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))

                    SpikeStreamPrimaryButton(
                        text = stringResource(R.string.create_match),
                        isLoading = isLoading,
                        enabled = team1.isNotBlank() && team2.isNotBlank() && (streamUrl.startsWith("rtmp://") || streamUrl.startsWith("rtmps://")),
                        onClick = {
                            if (validateInput(team1, team2, streamUrl)) {
                                if (token != null) {
                                    isLoading = true
                                    scope.launch {
                                        when (val result = makeCreateMatchRequest(token!!, team1, team2, streamUrl)) {
                                            is CreateMatchResult.Success -> {
                                                // Salva l'RTMP nei recenti
                                                val updatedSet = recentRtmps.toMutableSet()
                                                updatedSet.add(streamUrl)
                                                prefs.edit().putStringSet("recent_rtmps", updatedSet.take(5).toSet()).apply()
                                                
                                                isLoading = false
                                                finish()
                                            }
                                            is CreateMatchResult.Error -> {
                                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                                isLoading = false
                                            }
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, context.getString(R.string.token_not_found), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                //Spacer(Modifier.height(24.dp))


            }
        }
    }



    private fun validateInput(team1: String, team2: String, streamUrl: String): Boolean {
        return when {
            team1.isBlank() -> {
                Toast.makeText(this, this.getString(R.string.create_match_error1), Toast.LENGTH_SHORT).show()
                false
            }
            team2.isBlank() -> {
                Toast.makeText(this, this.getString(R.string.create_match_error2), Toast.LENGTH_SHORT).show()
                false
            }
            !(streamUrl.startsWith("rtmp://") || streamUrl.startsWith("rtmps://")) -> {
                // Questo controlla entrambi i protocolli
                Toast.makeText(this, getString(R.string.create_match_error3), Toast.LENGTH_SHORT).show()
                false
            }
            else -> true
        }
    }
}


suspend fun makeCreateMatchRequest(
    token: String,
    teamAName: String,
    teamBName: String,
    rtmpUrl: String
): CreateMatchResult = withContext(Dispatchers.IO) {
    try {
        val client = getHttpClient()
        val jsonBody = JSONObject().apply {
            put("teamAName", teamAName)
            put("teamBName", teamBName)
            put("rtmpUrl", rtmpUrl)
        }

        val mediaType = MediaType.get("application/json; charset=utf-8")
        val requestBody = RequestBody.create(mediaType, jsonBody.toString())

        val request = Request.Builder()
            .url("${Constants.BASE_URL}/games")
            .addHeader("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body()?.string() ?: ""

        if (response.isSuccessful) {
            val json = JSONObject(body)
            val matchId = json.getString("id")
            CreateMatchResult.Success(matchId)
        } else {
            Log.w("CreateMatch", "Create match failed: HTTP ${response.code()}")
            CreateMatchResult.Error(R.string.create_match_failed)
        }
    } catch (e: Exception) {
        Log.e("CreateMatch", "Create match request failed", e)
        CreateMatchResult.Error(R.string.connection_failed)
    }
}

sealed class CreateMatchResult {
    data class Success(val matchId: String) : CreateMatchResult()
    data class Error(val message: Int) : CreateMatchResult()
}

sealed class YouTubeFetchResult {
    data class Success(val rtmpUrl: String) : YouTubeFetchResult()
    object RateLimit : YouTubeFetchResult()
    object Error : YouTubeFetchResult()
}

private suspend fun fetchYouTubeRTMP(context: android.content.Context, account: GoogleSignInAccount, jwtToken: String): YouTubeFetchResult = withContext(Dispatchers.IO) {
    try {
        val scope = "oauth2:https://www.googleapis.com/auth/youtube.readonly"
        // Ottiene il token di accesso OAuth2 di Google
        val googleToken = GoogleAuthUtil.getToken(context, account.account!!, scope)

        val client = getHttpClient()
        val jsonBody = JSONObject().apply {
            put("accessToken", googleToken)
        }
        val mediaType = MediaType.get("application/json; charset=utf-8")
        val requestBody = RequestBody.create(mediaType, jsonBody.toString())

        val request = Request.Builder()
            .url("${Constants.BASE_URL}/games/youtube-rtmp")
            .addHeader("Authorization", "Bearer $jwtToken")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body()?.string() ?: ""

        if (response.isSuccessful) {
            val json = JSONObject(body)
            val rtmp = json.optString("rtmpUrl", "")
            if (rtmp.isNotEmpty()) {
                return@withContext YouTubeFetchResult.Success(rtmp)
            }
        } else if (response.code() == 429) {
            return@withContext YouTubeFetchResult.RateLimit
        } else {
            Log.e("YouTubeBackend", "Request failed: ${response.code()} - $body")
        }
        YouTubeFetchResult.Error
    } catch (e: Exception) {
        Log.e("YouTubeBackend", "Error fetching stream via Backend", e)
        YouTubeFetchResult.Error
    }
}

suspend fun fetchFacebookRTMP(fbAccessToken: String, appToken: String): String? = withContext(Dispatchers.IO) {
    try {
        val client = getHttpClient()
        val json = JSONObject().apply {
            put("accessToken", fbAccessToken)
        }
        val body = RequestBody.create(
            MediaType.parse("application/json; charset=utf-8"),
            json.toString()
        )
        val request = Request.Builder()
            .url("${Constants.BASE_URL}/games/facebook-rtmp")
            .addHeader("Authorization", "Bearer $appToken")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body()?.string()
            val jsonResponse = JSONObject(responseBody ?: "{}")
            return@withContext jsonResponse.optString("rtmpUrl", null)
        }
        null
    } catch (e: Exception) {
        Log.e("FacebookRTMP", "Failed to fetch FB rtmp", e)
        null
    }
}
