package com.leonardos.spikestream

import com.leonardos.spikestream.BuildConfig
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.leonardos.spikestream.ui.theme.SpikeStreamScreen
import com.leonardos.spikestream.ui.theme.SpikeStreamPrimaryButton
import com.leonardos.spikestream.ui.theme.SpikeStreamSecondaryButton
import com.leonardos.spikestream.ui.theme.SpikeStreamOutlinedButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject

sealed class InviteResult {
    data class Success(val link: String) : InviteResult()
    data class Error(val messageResId: Int) : InviteResult()
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
                        val rewardedAdState = remember { mutableStateOf<RewardedInterstitialAd?>(null) }
                        val adLoadingState = remember { mutableStateOf(true) }
                        val adError = remember { mutableStateOf<LoadAdError?>(null) }

                        LaunchedEffect(Unit) {
                            val adRequest = AdRequest.Builder().build()
                            RewardedInterstitialAd.load(
                                this@MatchOptionsActivity,
                                BuildConfig.ADMOB_REWARDED_INTERSTITIAL_ID,
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

    SpikeStreamScreen {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Text(
            text = "$teamA vs $teamB",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp)
        )
        
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            // Mask the stream key for better security on screen
            val maskedUrl = if (rtmpUrl.contains("?")) {
                rtmpUrl.substringBefore("?") + " / *****"
            } else if (rtmpUrl.length > 50) {
                rtmpUrl.take(45) + "..."
            } else {
                rtmpUrl
            }
            Text(
                text = maskedUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }

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
            Text(
                text = context.getString(R.string.score_editing_title_info),
                color = com.leonardos.spikestream.ui.theme.AccentCyan
            )
        }
        SpikeStreamSecondaryButton(
            text = stringResource(R.string.generate_link),
            onClick = {
                val ad = rewardedAd
                if (ad != null) {
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            onRewardedAdConsumed()
                            handleInviteLink(context, token!!, matchId, scope)
                        }
                        override fun onAdFailedToShowFullScreenContent(p0: AdError) {
                            onRewardedAdConsumed()
                            handleInviteLink(context, token!!, matchId, scope)
                        }
                    }
                    ad.show(activity) { /* user earned reward */ }
                } else {
                    handleInviteLink(context, token!!, matchId, scope)
                }
            }
        )

        var selectedPosition by remember { mutableStateOf("BOTTOM_LEFT") }

        Text(
            text = stringResource(R.string.overlay_position_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.overlay_position_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )

        OverlayPositionPicker(
            selectedPosition = selectedPosition,
            onPositionSelected = { selectedPosition = it }
        )

        SpikeStreamPrimaryButton(
            text = stringResource(R.string.launch_stream),
            onClick = {
                val intent = Intent(context, StreamActivity::class.java).apply {
                    putExtra("TEAM_1", teamA)
                    putExtra("TEAM_2", teamB)
                    putExtra("RTMP_URL", rtmpUrl)
                    putExtra("MATCH_ID", matchId)
                    putExtra("OVERLAY_POSITION", selectedPosition)
                }
                context.startActivity(intent)
            }
        )
        }
    }
}

@Composable
fun OverlayPositionPicker(
    selectedPosition: String,
    onPositionSelected: (String) -> Unit
) {
    val accent = com.leonardos.spikestream.ui.theme.AccentCyan
    val labelMap = mapOf(
        "TOP_LEFT"     to stringResource(R.string.pos_top_left),
        "TOP_RIGHT"    to stringResource(R.string.pos_top_right),
        "BOTTOM_LEFT"  to stringResource(R.string.pos_bottom_left),
        "BOTTOM"       to stringResource(R.string.pos_bottom),
        "BOTTOM_RIGHT" to stringResource(R.string.pos_bottom_right)
    )

    // Mini scorecard preview chip
    @Composable
    fun ScoreChip(position: String) {
        val isSelected = selectedPosition == position
        val chipScale by animateFloatAsState(
            targetValue = if (isSelected) 1.08f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "chipScale"
        )
        val containerColor by animateColorAsState(
            targetValue = if (isSelected) accent else Color.White.copy(alpha = 0.18f),
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "chipColor"
        )
        val borderColor by animateColorAsState(
            targetValue = if (isSelected) accent else Color.White.copy(alpha = 0.35f),
            label = "borderColor"
        )
        val textColor = if (isSelected) Color.Black else Color.White

        Box(
            modifier = Modifier
                .scale(chipScale)
                .clip(RoundedCornerShape(6.dp))
                .background(containerColor)
                .border(1.5.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable { onPositionSelected(position) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "12 – 9",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    lineHeight = 12.sp
                )
                /*Text(
                    text = labelMap[position] ?: position,
                    fontSize = 8.sp,
                    color = textColor.copy(alpha = 0.8f),
                    lineHeight = 10.sp
                )*/
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 16:9 video frame
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1A1A2E), Color(0xFF16213E))
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            ) {
                // Camera icon/label in center
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "📹",
                        fontSize = 28.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = stringResource(R.string.stream_preview),
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Medium
                    )
                }

                // Top-left
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) { ScoreChip("TOP_LEFT") }

                // Top-right
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) { ScoreChip("TOP_RIGHT") }

                // Bottom-left
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) { ScoreChip("BOTTOM_LEFT") }

                // Bottom-center
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                ) { ScoreChip("BOTTOM") }

                // Bottom-right
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                ) { ScoreChip("BOTTOM_RIGHT") }
            }

            // Legend row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent)
                )
                Text(
                    text = stringResource(R.string.selected),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                )
                Text(
                    text = stringResource(R.string.available),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
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
                    val clip = ClipData.newPlainText(context.getString(R.string.invite_link), result.link)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, context.getString(R.string.copy_link), Toast.LENGTH_SHORT).show()
                }
                is InviteResult.Error -> {
                    Toast.makeText(context, context.getString(result.messageResId), Toast.LENGTH_LONG).show()
                }
            }
        }
    } else {
        Toast.makeText(context, context.getString(R.string.token_not_available), Toast.LENGTH_SHORT).show()
    }
}






suspend fun makePostInviteLinkRequest(token: String, matchId: String): InviteResult = withContext(
    Dispatchers.IO) {
    try {
        val client = getHttpClient()
        val request = Request.Builder()
            .url("${Constants.BASE_URL}/auth/invite/$matchId")
            .addHeader("Authorization", "Bearer $token")
            .post(RequestBody.create(null, ByteArray(0)))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body()?.string() ?: ""

        if (response.isSuccessful) {
            val json = JSONObject(body)
            val url = json.getString("link")
            InviteResult.Success(url)
        } else {
            Log.w("InviteLink", "Get invite link failed: HTTP ${response.code()}")
            InviteResult.Error(R.string.invite_link_error)
        }
    } catch (e: Exception) {
        Log.e("InviteLink", "Invite link request failed", e)
        InviteResult.Error(R.string.connection_failed)
    }
}

