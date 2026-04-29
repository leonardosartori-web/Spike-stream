package com.leonardos.spikestream

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import com.leonardos.spikestream.ui.theme.SpikeStreamGlassCard
import com.leonardos.spikestream.ui.theme.SpikeStreamScreen
import com.leonardos.spikestream.ui.theme.SpikeStreamTextField
import com.leonardos.spikestream.ui.theme.SpikeStreamPrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject


class CreateMatchActivity: ComponentActivity() {

    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenManager = TokenManager(applicationContext)

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
                        CreateMatchScreen(tokenManager)
                    }
                }
            }
        }
    }

    @Composable
    fun CreateMatchScreen(tokenManager: TokenManager) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val token by tokenManager.tokenFlow.collectAsState(initial = null)

        var team1 by remember { mutableStateOf("") }
        var team2 by remember { mutableStateOf("") }
        var streamUrl by remember { mutableStateOf("rtmp://") }
        var isLoading by remember { mutableStateOf(false) }

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

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = stringResource(R.string.streaming_url_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    SpikeStreamTextField(
                        value = streamUrl,
                        onValueChange = { streamUrl = it },
                        label = stringResource(R.string.url_rtmp_label),
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                    )

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

                Spacer(Modifier.height(24.dp))

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
