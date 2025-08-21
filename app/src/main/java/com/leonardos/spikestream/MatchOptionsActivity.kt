package com.leonardos.spikestream

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import com.leonardos.spikestream.ui.theme.MyApplicationTheme
import getUnsafeOkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject

sealed class InviteResult {
    data class Success(val link: String) : InviteResult()
    data class Error(val message: String) : InviteResult()
}

class MatchOptionsActivity : ComponentActivity() {

    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenManager = TokenManager(applicationContext)

        val teamA = intent.getStringExtra("TEAM_1") ?: ""
        val teamB = intent.getStringExtra("TEAM_2") ?: ""
        val rtmpUrl = intent.getStringExtra("RTMP_URL") ?: ""
        val matchId = intent.getStringExtra("MATCH_ID") ?: ""

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
                    } else {
                        val rewardedAdState = remember { mutableStateOf<RewardedInterstitialAd?>(null) }
                        val adLoadingState = remember { mutableStateOf(true) }
                        val adError = remember { mutableStateOf<LoadAdError?>(null) }

                        LaunchedEffect(Unit) {
                            val adRequest = AdRequest.Builder().build()
                            RewardedInterstitialAd.load(
                                this@MatchOptionsActivity,
                                "ca-app-pub-2622126149242920/7902181297",
                                adRequest,
                                object : RewardedInterstitialAdLoadCallback() {
                                    override fun onAdLoaded(ad: RewardedInterstitialAd) {
                                        rewardedAdState.value = ad
                                        adLoadingState.value = false
                                    }

                                    override fun onAdFailedToLoad(error: LoadAdError) {
                                        rewardedAdState.value = null
                                        adLoadingState.value = false
                                    }
                                }
                            )
                        }


                        if (adLoadingState.value) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            MatchOptionsScreen(
                                teamA, teamB, rtmpUrl, matchId, tokenManager,
                                rewardedAdState.value,
                                onRewardedAdConsumed = {
                                    rewardedAdState.value = null // reset
                                }
                            )
                        }
                    }
                }
            }

        }
    }

}

@Composable
fun MatchOptionsScreen(
    teamA: String,
    teamB: String,
    rtmpUrl: String,
    matchId: String,
    tokenManager: TokenManager,
    rewardedAd: RewardedInterstitialAd?,
    onRewardedAdConsumed: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val token by tokenManager.tokenFlow.collectAsState(initial = null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$teamA vs $teamB", style = MaterialTheme.typography.headlineSmall)
        Text(rtmpUrl, style = MaterialTheme.typography.headlineSmall)

        TextButton(onClick = {
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.score_editing_title_info))
                .setMessage(
                    HtmlCompat.fromHtml(
                    context.getString(R.string.score_editing_full_info),
                    HtmlCompat.FROM_HTML_MODE_LEGACY
                ))
                .setPositiveButton("OK", null)
                .show()
        }) {
            Text(context.getString(R.string.score_editing_title_info))
        }

        Button(onClick = {
            val ad = rewardedAd
            if (ad != null) {
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        onRewardedAdConsumed()
                        handleInviteLink(context, token!!, matchId, scope)
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                        onRewardedAdConsumed()
                        Toast.makeText(context, "Errore durante la visualizzazione", Toast.LENGTH_SHORT).show()
                    }
                }

                ad.show(activity) { rewardItem: RewardItem ->
                    //handleInviteLink(context, token!!, matchId, scope)
                }
            }
            else {
                handleInviteLink(context, token!!, matchId, scope)
            }
        }) {
            Text(stringResource(R.string.generate_link))
        }

        Button(onClick = {
            val intent = Intent(context, StreamActivity::class.java).apply {
                putExtra("TEAM_1", teamA)
                putExtra("TEAM_2", teamB)
                putExtra("RTMP_URL", rtmpUrl)
                putExtra("MATCH_ID", matchId)
            }
            context.startActivity(intent)
        }) {
            Text(stringResource(R.string.launch_stream))
        }
    }
}

fun handleInviteLink(
    context: Context,
    token: String?,
    matchId: String,
    scope: CoroutineScope
) {
    if (token != null) {
        scope.launch {
            when (val result = makePostInviteLinkRequest(token, matchId)) {
                is InviteResult.Success -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Invite Link", result.link)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, context.getString(R.string.copy_link), Toast.LENGTH_SHORT).show()
                }
                is InviteResult.Error -> {
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    } else {
        Toast.makeText(context, "Token non disponibile", Toast.LENGTH_SHORT).show()
    }
}






suspend fun makePostInviteLinkRequest(token: String, matchId: String): InviteResult = withContext(
    Dispatchers.IO) {
    try {
        val client = getUnsafeOkHttpClient()
        val request = Request.Builder()
            .url("https://spikestream.tooolky.com/auth/invite/$matchId")
            .addHeader("Authorization", "Bearer $token")
            .post(RequestBody.create(null, ByteArray(0))) // POST con corpo vuoto
            .build()

        val response = client.newCall(request).execute()
        val body = response.body()?.string() ?: ""

        if (response.isSuccessful) {
            val json = JSONObject(body)
            val url = json.getString("link")
            InviteResult.Success(url)
        } else {
            InviteResult.Error("HTTP ${response.code()}: $body")
        }
    } catch (e: Exception) {
        InviteResult.Error("Eccezione: ${e.localizedMessage}")
    }
}

