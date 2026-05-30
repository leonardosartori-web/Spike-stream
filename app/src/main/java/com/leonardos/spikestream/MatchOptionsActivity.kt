@file:OptIn(ExperimentalMaterial3Api::class)

package com.leonardos.spikestream

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import com.leonardos.spikestream.Logger as Log
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
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
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import com.leonardos.spikestream.ui.theme.MyApplicationTheme
import com.leonardos.spikestream.ui.theme.SpikeStreamGlassCard
import com.leonardos.spikestream.ui.theme.SpikeStreamScreen
import com.leonardos.spikestream.ui.theme.SpikeStreamPrimaryButton
import com.leonardos.spikestream.ui.theme.SpikeStreamSecondaryButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.toArgb
import com.leonardos.spikestream.ui.theme.SpikeStreamDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import yuku.ambilwarna.AmbilWarnaDialog


sealed class InviteResult {
    data class Success(val link: String) : InviteResult()
    data class Error(val messageResId: Int) : InviteResult()
}

data class CameraInfo(val id: String, val name: String, val facing: Int)

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

fun findDefaultCamera(cameras: List<CameraInfo>): String {
    return cameras.firstOrNull {
        it.facing == CameraCharacteristics.LENS_FACING_BACK
    }?.id ?: cameras.firstOrNull()?.id ?: "0"
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

    var overlayStyle by remember {
        mutableStateOf(DefaultOverlayStyle.classic)
    }

    LaunchedEffect(teamA, teamB) {

        OverlayStyleStorage.getTeamAccent(
            context,
            teamA,
            DefaultOverlayStyle.classic.team1.accent
        ).collect { color ->

            overlayStyle = overlayStyle.copy(
                team1 = overlayStyle.team1.copy(accent = color)
            )
        }
    }

    LaunchedEffect(teamA, teamB) {

        OverlayStyleStorage.getTeamAccent(
            context,
            teamB,
            DefaultOverlayStyle.classic.team2.accent
        ).collect { color ->

            overlayStyle = overlayStyle.copy(
                team2 = overlayStyle.team2.copy(accent = color)
            )
        }
    }

    SpikeStreamScreen {
        var selectedPosition by remember { mutableStateOf("BOTTOM_LEFT") }
        var selectedCameraId by remember { mutableStateOf<String?>(null) }
        var showInfoDialog by remember { mutableStateOf(false) }
        var showDeleteDialog by remember { mutableStateOf(false) }

        val cameras = remember {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val list = mutableListOf<CameraInfo>()
            try {
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    val name = when (facing) {
                        CameraCharacteristics.LENS_FACING_BACK -> context.getString(R.string.camera_back)
                        CameraCharacteristics.LENS_FACING_FRONT -> context.getString(R.string.camera_front)
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> context.getString(R.string.camera_external)
                        else -> context.getString(R.string.camera_unknown, id)
                    }
                    list.add(CameraInfo(id, name, facing ?: -1))
                }
            } catch (e: Exception) {
                Log.e("Camera", "Error listing cameras", e)
            }
            list
        }

        LaunchedEffect(cameras) {
            if (selectedCameraId == null && cameras.isNotEmpty()) {
                selectedCameraId = findDefaultCamera(cameras)
            }
        }

        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {

                TeamAccentEditor(
                    currentColor = Color(overlayStyle.team1.accent),
                    onColorSelected = { color ->

                        val intColor = color.toArgb()

                        overlayStyle = overlayStyle.copy(
                            team1 = overlayStyle.team1.copy(accent = intColor)
                        )

                        scope.launch {
                            OverlayStyleStorage.setTeamAccent(
                                context,
                                teamA,
                                intColor
                            )
                        }
                    }
                )

                Spacer(Modifier.width(12.dp))

                Text(
                    text = "$teamA vs $teamB",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.width(12.dp))

                TeamAccentEditor(
                    currentColor = Color(overlayStyle.team2.accent),
                    onColorSelected = { color ->

                        val intColor = color.toArgb()

                        overlayStyle = overlayStyle.copy(
                            team2 = overlayStyle.team2.copy(accent = intColor)
                        )

                        scope.launch {
                            OverlayStyleStorage.setTeamAccent(
                                context,
                                teamB,
                                intColor
                            )
                        }
                    }
                )
            }

            TextButton(onClick = {

                val intColor = android.graphics.Color.rgb(220, 38, 38)

                overlayStyle = overlayStyle.copy(
                    team1 = overlayStyle.team1.copy(accent = intColor),
                    team2 = overlayStyle.team2.copy(accent = intColor)
                )

                scope.launch {

                    OverlayStyleStorage.setTeamAccent(
                        context,
                        teamA,
                        intColor
                    )

                    OverlayStyleStorage.setTeamAccent(
                        context,
                        teamB,
                        intColor
                    )
                }
            })
            {
                Text(stringResource(R.string.default_color))
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "$rtmpUrl",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )

            Spacer(Modifier.height(32.dp))

            SpikeStreamGlassCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.invite_scorers_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.invite_scorers_desc),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(24.dp))
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
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.overlay_position_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.overlay_position_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(Modifier.height(16.dp))

            OverlayPositionPicker(
                selectedPosition = selectedPosition,
                onPositionSelected = { selectedPosition = it }
            )

            Spacer(Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.camera_selection_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.camera_selection_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(Modifier.height(16.dp))

            CameraPicker(
                cameras = cameras,
                selectedCameraId = selectedCameraId,
                onCameraSelected = { selectedCameraId = it }
            )

            Spacer(Modifier.height(40.dp))

            SpikeStreamPrimaryButton(
                text = stringResource(R.string.launch_stream),
                onClick = {
                    val cameraId = selectedCameraId ?: return@SpikeStreamPrimaryButton
                    val intent = Intent(context, StreamActivity::class.java).apply {
                        putExtra("TEAM_1", teamA)
                        putExtra("TEAM_2", teamB)
                        putExtra("RTMP_URL", rtmpUrl)
                        putExtra("MATCH_ID", matchId)
                        putExtra("OVERLAY_POSITION", selectedPosition)
                        putExtra("CAMERA_ID", cameraId)
                        putExtra("TEAM1_ACCENT", overlayStyle.team1.accent)
                        putExtra("TEAM2_ACCENT", overlayStyle.team2.accent)
                    }
                    context.startActivity(intent)
                }
            )

            Spacer(Modifier.height(16.dp))

            TextButton(onClick = { showInfoDialog = true }) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.score_editing_title_info))
            }

            Spacer(Modifier.height(16.dp))

            TextButton(
                onClick = { showDeleteDialog = true },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red.copy(alpha = 0.7f))
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.delete_match))
            }

            Spacer(Modifier.height(16.dp))
        }

        if (showInfoDialog) {
            SpikeStreamDialog(
                onDismissRequest = { showInfoDialog = false },
                title = stringResource(R.string.score_editing_title_info),
                icon = { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) },
                content = {
                    Text(
                        text = HtmlCompat.fromHtml(
                            stringResource(R.string.score_editing_full_info),
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                        ).toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) {
                        Text(stringResource(android.R.string.ok), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        if (showDeleteDialog) {
            SpikeStreamDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = stringResource(R.string.delete_confirm_title),
                icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp)) },
                content = {
                    Text(
                        text = stringResource(R.string.delete_confirm_msg),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteDialog = false
                            scope.launch {
                                val success = makeDeleteMatchRequest(token!!, matchId)
                                if (success) {
                                    Toast.makeText(context, context.getString(R.string.delete_success), Toast.LENGTH_SHORT).show()
                                    activity.finish()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.connection_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.delete_match), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
fun TeamAccentEditor(
    currentColor: Color,
    onColorSelected: (Color) -> Unit
) {
    val context = LocalContext.current
    var colorInt = currentColor.toArgb()


    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(currentColor)
            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .clickable {

                val dialog = AmbilWarnaDialog(
                    context,
                    colorInt,
                    object : AmbilWarnaDialog.OnAmbilWarnaListener {
                        override fun onOk(dialog: AmbilWarnaDialog?, color: Int) {
                            onColorSelected(Color(color))
                        }

                        override fun onCancel(dialog: AmbilWarnaDialog?) {}
                    }
                )

                dialog.show()
            }
    )
}


@Composable
fun OverlayPositionPicker(
    selectedPosition: String,
    onPositionSelected: (String) -> Unit
) {
    val accent = MaterialTheme.colorScheme.secondary
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

@Composable
fun CameraPicker(
    cameras: List<CameraInfo>,
    selectedCameraId: String?,
    onCameraSelected: (String) -> Unit
) {
    val accent = MaterialTheme.colorScheme.secondary

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        cameras.forEach { camera ->
            val isSelected = selectedCameraId == camera.id
            val containerColor by animateColorAsState(
                targetValue = if (isSelected) accent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                label = "cameraBg"
            )
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) accent else Color.White.copy(alpha = 0.15f),
                label = "cameraBorder"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(containerColor)
                    .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
                    .clickable { onCameraSelected(camera.id) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = when (camera.facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "🤳"
                    CameraCharacteristics.LENS_FACING_BACK -> "📷"
                    else -> "📹"
                }

                Text(icon, fontSize = 20.sp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = camera.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "ID: ${camera.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

suspend fun makeDeleteMatchRequest(token: String, matchId: String): Boolean = withContext(Dispatchers.IO) {
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
        Log.e("DeleteMatch", "Delete match request failed", e)
        false
    }
}
