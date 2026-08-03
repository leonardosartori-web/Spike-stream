@file:OptIn(ExperimentalMaterial3Api::class)

package com.leonardos.spikestream.activities

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import com.leonardos.spikestream.utils.Logger as Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.luminance
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
import com.leonardos.spikestream.BuildConfig
import com.leonardos.spikestream.utils.Constants
import com.leonardos.spikestream.ui.components.DefaultOverlayStyle
import com.leonardos.spikestream.data.OverlayStyleStorage
import com.leonardos.spikestream.R
import com.leonardos.spikestream.utils.TourManager
import com.leonardos.spikestream.ui.components.TourOverlay
import com.leonardos.spikestream.ui.components.TourStep
import com.leonardos.spikestream.ui.components.rememberTourController
import com.leonardos.spikestream.ui.components.tourHighlight
import com.leonardos.spikestream.ui.theme.SpikeStreamDialog
import com.leonardos.spikestream.data.*
import com.leonardos.spikestream.utils.RemoteConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


sealed class InviteResult {
    data class Success(val link: String) : InviteResult()
    data class Error(val messageResId: Int) : InviteResult()
}

data class CameraInfo(val id: String, val name: String, val facing: Int)

class MatchOptionsActivity : ComponentActivity() {

    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

                        // 1. Funzione isolata e riutilizzabile per caricare l'Ad ogni volta che serve
                        fun loadAd() {
                            adLoadingState.value = RemoteConfigManager.isInvitationLinkEnabled()
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
                                        adError.value = error
                                    }
                                }
                            )
                        }

                        // 2. Primo caricamento all'avvio
                        LaunchedEffect(Unit) {
                            loadAd()
                        }

                        if (adLoadingState.value) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            MatchOptionsScreen(
                                teamA = teamA,
                                teamB = teamB,
                                rtmpUrl = rtmpUrl,
                                matchId = matchId,
                                tokenManager = tokenManager,
                                rewardedAd = rewardedAdState.value,
                                onRewardedAdConsumed = {
                                    rewardedAdState.value = null // Resetta l'ad vecchio consumato
                                },
                                onRequestNewAd = {
                                    loadAd() // Chiamata per caricarne uno nuovo quando l'utente fallisce/chiude
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
    onRewardedAdConsumed: () -> Unit,
    onRequestNewAd: () -> Unit
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
        val tourController = rememberTourController(
            tourKey = TourManager.KEY_MATCH_OPTIONS,
            steps = listOf(
                TourStep(
                    id = "invite",
                    emoji = "🔗",
                    title = context.getString(R.string.tour_options_step1_title),
                    body = context.getString(R.string.tour_options_step1_body),
                    highlightKey = "invite"
                ),
                TourStep(
                    id = "overlay",
                    emoji = "🎯",
                    title = context.getString(R.string.tour_options_step2_title),
                    body = context.getString(R.string.tour_options_step2_body),
                    highlightKey = "overlay"
                ),
                TourStep(
                    id = "camera",
                    emoji = "📷",
                    title = context.getString(R.string.tour_options_step3_title),
                    body = context.getString(R.string.tour_options_step3_body),
                    highlightKey = "camera"
                ),
                TourStep(
                    id = "launch",
                    emoji = "🔴",
                    title = context.getString(R.string.tour_options_step4_title),
                    body = context.getString(R.string.tour_options_step4_body),
                    highlightKey = "launch"
                )
            )
        )


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
            SpikeStreamGlassCard {
                TeamAccentRow(
                    teamName = teamA,
                    currentColor = Color(overlayStyle.team1.accent),
                    onColorSelected = { color ->
                        val intColor = color.toArgb()
                        overlayStyle = overlayStyle.copy(
                            team1 = overlayStyle.team1.copy(accent = intColor)
                        )
                        scope.launch {
                            OverlayStyleStorage.setTeamAccent(context, teamA, intColor)
                        }
                    }
                )

                Spacer(Modifier.height(12.dp))

                TeamAccentRow(
                    teamName = teamB,
                    currentColor = Color(overlayStyle.team2.accent),
                    onColorSelected = { color ->
                        val intColor = color.toArgb()
                        overlayStyle = overlayStyle.copy(
                            team2 = overlayStyle.team2.copy(accent = intColor)
                        )
                        scope.launch {
                            OverlayStyleStorage.setTeamAccent(context, teamB, intColor)
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

            SpikeStreamGlassCard(
                modifier = Modifier.Companion.tourHighlight(tourController, "invite", RoundedCornerShape(24.dp))
            ) {
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
                            val currentToken = token

                            val adsEnabledFromRemote = RemoteConfigManager.isInvitationLinkEnabled()

                            if (adsEnabledFromRemote && ad != null) {
                                var userEarnedReward = false

                                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                                    override fun onAdDismissedFullScreenContent() {
                                        // Consumiamo l'ad vecchio in ogni caso
                                        onRewardedAdConsumed()

                                        if (userEarnedReward) {
                                            // Caso A: Ha completato l'annuncio -> Diamo il link
                                            scope.launch {
                                                delay(200)
                                                handleInviteLink(context, currentToken, matchId, scope)
                                            }
                                        } else {
                                            // Caso B: Ha chiuso in anticipo -> Errore e RICHIEDIAMO un nuovo Ad
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.ad_reward_required_error),
                                                Toast.LENGTH_SHORT
                                            ).show()

                                            // Questo rimette l'Activity in stato di caricamento e rigenera l'oggetto AdMob
                                            onRequestNewAd()
                                        }
                                    }

                                    override fun onAdFailedToShowFullScreenContent(p0: AdError) {
                                        onRewardedAdConsumed()
                                        // Se fallisce l'apertura, bypassiamo l'errore per non bloccare l'utente
                                        handleInviteLink(context, currentToken, matchId, scope)
                                    }
                                }

                                ad.show(activity) { rewardItem ->
                                    userEarnedReward = true
                                }

                            } else {
                                // Se gli ad sono disabilitati o l'ad è nullo per motivi di caricamento fallito a monte,
                                // generiamo direttamente il link.
                                handleInviteLink(context, currentToken, matchId, scope)
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .tourHighlight(tourController, "overlay", RoundedCornerShape(16.dp))
            ) {
                OverlayPositionPicker(
                    selectedPosition = selectedPosition,
                    onPositionSelected = { selectedPosition = it }
                )
            }

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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .tourHighlight(tourController, "camera", RoundedCornerShape(12.dp))
            ) {
                CameraPicker(
                    cameras = cameras,
                    selectedCameraId = selectedCameraId,
                    onCameraSelected = { selectedCameraId = it }
                )
            }

            Spacer(Modifier.height(40.dp))

            SpikeStreamPrimaryButton(
                modifier = Modifier.fillMaxWidth().tourHighlight(tourController, "launch", RoundedCornerShape(16.dp)),
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
                                val success = StreamApi.makeDeleteMatchRequest(token!!, matchId)
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
        TourOverlay(controller = tourController)
    }
}

@Composable
private fun TeamAccentRow(
    teamName: String,
    currentColor: Color,
    onColorSelected: (Color) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = teamName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.width(16.dp))
        TeamAccentEditor(
            teamName = teamName,
            currentColor = currentColor,
            onColorSelected = onColorSelected
        )
    }
}

@Composable
fun TeamAccentEditor(
    teamName: String,
    currentColor: Color,
    onColorSelected: (Color) -> Unit
) {
    var showColorDialog by remember { mutableStateOf(false) }
    var draftColor by remember { mutableStateOf(currentColor) }
    val iconColor = if (currentColor.luminance() > 0.52f) Color.Black else Color.White

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(currentColor)
            .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape)
            .clickable {
                draftColor = currentColor
                showColorDialog = true
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = stringResource(R.string.team_color_change, teamName),
            tint = iconColor,
            modifier = Modifier.size(21.dp)
        )
    }

    if (showColorDialog) {
        val hsv = FloatArray(3).also {
            android.graphics.Color.colorToHSV(draftColor.toArgb(), it)
        }
        val presetColors = remember {
            listOf(
                Color(0xFFDC2626), Color(0xFFEA580C), Color(0xFFF59E0B),
                Color(0xFF16A34A), Color(0xFF0D9488), Color(0xFF0891B2),
                Color(0xFF2563EB), Color(0xFF4F46E5), Color(0xFF7C3AED),
                Color(0xFFC026D3), Color(0xFFDB2777), Color(0xFF475569)
            )
        }

        SpikeStreamDialog(
            onDismissRequest = { showColorDialog = false },
            title = stringResource(R.string.team_color_picker_title, teamName),
            icon = {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = draftColor,
                    modifier = Modifier.size(34.dp)
                )
            },
            content = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(58.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(draftColor)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                        RoundedCornerShape(16.dp)
                                    )
                            )
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.team_color_selected),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                                Text(
                                    text = "#%06X".format(draftColor.toArgb() and 0xFFFFFF),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Text(
                        text = stringResource(R.string.team_color_quick),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))

                    presetColors.chunked(6).forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            rowColors.forEach { color ->
                                val isSelected =
                                    (draftColor.toArgb() and 0xFFFFFF) ==
                                        (color.toArgb() and 0xFFFFFF)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            if (isSelected) 3.dp else 1.dp,
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                Color.White.copy(alpha = 0.35f)
                                            },
                                            CircleShape
                                        )
                                        .clickable { draftColor = color }
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    ColorSlider(
                        label = stringResource(R.string.team_color_hue),
                        value = hsv[0],
                        valueRange = 0f..360f,
                        color = draftColor,
                        onValueChange = { hue ->
                            draftColor = Color(
                                android.graphics.Color.HSVToColor(
                                    floatArrayOf(hue, hsv[1], hsv[2])
                                )
                            )
                        }
                    )
                    ColorSlider(
                        label = stringResource(R.string.team_color_saturation),
                        value = hsv[1],
                        valueRange = 0f..1f,
                        color = draftColor,
                        onValueChange = { saturation ->
                            draftColor = Color(
                                android.graphics.Color.HSVToColor(
                                    floatArrayOf(hsv[0], saturation, hsv[2])
                                )
                            )
                        }
                    )
                    ColorSlider(
                        label = stringResource(R.string.team_color_brightness),
                        value = hsv[2],
                        valueRange = 0f..1f,
                        color = draftColor,
                        onValueChange = { brightness ->
                            draftColor = Color(
                                android.graphics.Color.HSVToColor(
                                    floatArrayOf(hsv[0], hsv[1], brightness)
                                )
                            )
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onColorSelected(draftColor)
                        showColorDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = draftColor,
                        contentColor = if (draftColor.luminance() > 0.52f) {
                            Color.Black
                        } else {
                            Color.White
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.team_color_apply),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showColorDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    color: Color,
    onValueChange: (Float) -> Unit
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
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
            val result = StreamApi.makePostInviteLinkRequest(token, matchId)

            withContext(Dispatchers.Main) {

                // Usiamo il reference esplicito alla tua sealed class specifica
                when (result) {
                    is com.leonardos.spikestream.data.InviteResult.Success -> {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(context.getString(R.string.invite_link), result.link)
                            clipboard.setPrimaryClip(clip)
                            Log.d("SPIKESTREAM_DEBUG", "5. Clipboard impostata con successo")
                            Toast.makeText(context, context.getString(R.string.copy_link), Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e("SPIKESTREAM_DEBUG", "ERRORE CLIPBOARD: ", e)
                        }
                    }
                    is com.leonardos.spikestream.data.InviteResult.Error -> {
                        Log.w("SPIKESTREAM_DEBUG", "4. Errore API gestito: ${result.messageResId}")
                        Toast.makeText(context, context.getString(result.messageResId), Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        Log.e("SPIKESTREAM_DEBUG", "4a. Il risultato è finito ancora nell'ELSE! Tipo reale: ${result::class.java.name}")
                    }
                }
            }
        }
    } else {
        Log.w("SPIKESTREAM_DEBUG", "1a. Token nullo, operazione annullata")
        Toast.makeText(context, context.getString(R.string.token_not_available), Toast.LENGTH_SHORT).show()
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
