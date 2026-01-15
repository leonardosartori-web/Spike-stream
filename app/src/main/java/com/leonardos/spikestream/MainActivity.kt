package com.leonardos.spikestream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
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
    data class Error(val message: String) : GamesResult()
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

    private lateinit var tokenManager: TokenManager
    private var latestIntentData: Uri? = null
    lateinit var rewardedAd: RewardedAd
    var isRewardedAdLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this) {}

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

        // 👇 Carica e mostra l'annuncio all'avvio
        val adRequest = AdRequest.Builder().build()
        AppOpenAd.load(
            this,
            "ca-app-pub-2622126149242920/3023467125", // 👈 ID di test (sostituisci con il tuo)
            adRequest,
            AppOpenAd.APP_OPEN_AD_ORIENTATION_PORTRAIT,
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            // Quando l'annuncio finisce, mostra l'app
                            startCompose()
                        }

                        override fun onAdFailedToShowFullScreenContent(p0: AdError) {
                            startCompose()
                        }
                    }

                    ad.show(this@MainActivity)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // Se fallisce, continua subito
                    startCompose()
                }
            }
        )

        loadRewardedAd()


    }

    override fun onResume() {
        super.onResume()
        loadRewardedAd() // ricarica sempre
    }


    private fun loadRewardedAd() {
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            this,
            "ca-app-pub-2622126149242920/6851117213", // <-- tuo ID rewarded
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

                // ✅ Se arriva il token dal deep link, salvalo e aggiorna il tokenState
                LaunchedEffect(latestIntentData) {
                    val jwt = latestIntentData?.getQueryParameter("token")
                    if (!jwt.isNullOrBlank()) {
                        coroutineScope.launch {
                            tokenManager.saveToken(jwt)
                            tokenState.value = jwt
                            latestIntentData = null // pulisci il dato dopo l'uso
                        }
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
                                    Uri.parse("https://spikestream.tooolky.com/auth/google")
                                )
                                startActivity(intent)
                            },
                            onForgotPasswordClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://spikestream.tooolky.com/auth/reset-password")
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
        val client = getUnsafeOkHttpClient()
        val jsonBody = JSONObject().apply {
            put("email", email)
            put("password", password)
        }

        val mediaType = MediaType.get("application/json; charset=utf-8")
        val requestBody = RequestBody.create(mediaType, jsonBody.toString())

        val request = Request.Builder()
            .url("https://spikestream.tooolky.com/auth/login")
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
        AuthResult.Error("Errore: ${e.javaClass.simpleName} - ${e.message ?: "Nessun dettaglio"}")
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.CenterHorizontally))

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        if (errorMessage != null) {
            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        Button(
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

            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.login_title))
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                onGoogleLoginClick() // ✅ usa la callback
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.login_google))
        }


        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onRegisterClick, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.login_register))
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onForgotPasswordClick, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.login_password_forgot))
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = {
                context.startActivity(Intent(context, InfoActivity::class.java))
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Info app")
        }
    }
}

suspend fun makeGetGamesRequest(token: String): GamesResult = withContext(Dispatchers.IO) {
    try {
        val client = getUnsafeOkHttpClient()
        val request = Request.Builder()
            .url("https://spikestream.tooolky.com/games")
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
            GamesResult.Error("HTTP ${response.code()}: ${body}")
        }
        
    } catch (e: Exception) {
        GamesResult.Error("Eccezione: ${e.localizedMessage}")
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
                    onTokenExpired()
                }
            }
            isRefreshing.value = false
        }
    }

    LaunchedEffect(Unit) {
        loadStreams()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {


        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Dashboard", style = MaterialTheme.typography.headlineMedium)

            Row {
                // Icona Settings
                IconButton(
                    onClick = {
                        val intent = Intent(context, SettingsActivity::class.java)
                        context.startActivity(intent)
                    }
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }


                // Bottone Logout
                Button(
                    onClick = { onTokenExpired() }
                ) {
                    Text(stringResource(R.string.logout))
                }
            }
        }


        Spacer(Modifier.height(12.dp))

        SwipeRefresh(
            state = rememberSwipeRefreshState(isRefreshing.value),
            onRefresh = { loadStreams() },
            modifier = Modifier.weight(1f)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (streams.isNotEmpty()) {
                    items(streams) { stream ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            elevation = CardDefaults.cardElevation(4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stream.title)

                                Spacer(Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Button(onClick = {
                                        val intent = Intent(context, MatchOptionsActivity::class.java).apply {
                                            putExtra("RTMP_URL", stream.rtmpUrl)
                                            putExtra("TEAM_1", stream.teamA)
                                            putExtra("TEAM_2", stream.teamB)
                                            putExtra("MATCH_ID", stream.matchId)
                                        }
                                        context.startActivity(intent)
                                    }) {
                                        Text(stringResource(R.string.open))
                                    }

                                    Button(
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        onClick = {
                                            scope.launch {
                                                val success = makeDeleteMatch(token, stream.matchId)
                                                if (success) {
                                                    streams.remove(stream)
                                                } else {
                                                    Toast.makeText(context, context.getString(R.string.deletion_error), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    ) {
                                        Text(
                                            stringResource(R.string.delete),
                                            color = MaterialTheme.colorScheme.onError
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.no_match))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(stringResource(R.string.no_match1))

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                if ((context as MainActivity).isRewardedAdLoaded) {
                    (context as MainActivity).rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            (context as MainActivity).isRewardedAdLoaded = false
                            onCreateStreamClick() // vai alla pagina dopo l’annuncio
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
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.add_match))
        }
    }
}



suspend fun makeDeleteMatch(token: String, matchId: String): Boolean = withContext(
    Dispatchers.IO) {
    try {
        val client = getUnsafeOkHttpClient()
        val request = Request.Builder()
            .url("https://spikestream.tooolky.com/games/$matchId")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()

        val response = client.newCall(request).execute()
        response.isSuccessful

    } catch (e: Exception) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.register_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.CenterHorizontally))

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        if (message != null) {
            Text(message!!, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
        }

        Button(
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
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.register_title))
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onBackToLogin) {
            Text(stringResource(R.string.register_login_back))
        }
    }
}


suspend fun makeRegisterRequest(email: String, password: String): String? = withContext(Dispatchers.IO) {
    try {
        val client = getUnsafeOkHttpClient()
        val json = JSONObject()
        json.put("email", email)
        json.put("password", password)

        val mediaType = MediaType.get("application/json; charset=utf-8")
        val requestBody = RequestBody.create(mediaType, json.toString())

        val request = Request.Builder()
            .url("https://spikestream.tooolky.com/users")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()

        if (response.isSuccessful) {
            "Success! Controlla l'email per confermare."
        } else {
            "Errore: HTTP ${response.code()} - ${response.body()?.string()
                ?.let { JSONObject(it).getString("message") } ?: "Nessun messaggio"}"
        }

    } catch (e: Exception) {
        "Errore: ${e.message}"
    }
}
