package com.leonardos.spikestream.activities

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.leonardos.spikestream.utils.Logger as Log
import com.leonardos.spikestream.ui.theme.MyApplicationTheme
import com.leonardos.spikestream.ui.theme.SpikeStreamPrimaryButton
import com.leonardos.spikestream.ui.theme.SpikeStreamDangerButton
import com.pedro.common.ConnectChecker
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.GlStreamInterface
import com.pedro.library.view.OpenGlView
import com.leonardos.spikestream.ui.components.DefaultOverlayStyle
import com.leonardos.spikestream.data.GetGameResult
import com.leonardos.spikestream.R
import com.leonardos.spikestream.data.StreamApi.makeGetGameRequest
import com.leonardos.spikestream.utils.getHttpClient
import com.leonardos.spikestream.streaming.StreamBitrateMonitor
import com.leonardos.spikestream.streaming.StreamBrightnessManager
import com.leonardos.spikestream.streaming.BroadcastStatus
import com.leonardos.spikestream.streaming.NetworkTransport
import com.leonardos.spikestream.streaming.StreamHealth
import com.leonardos.spikestream.streaming.StreamNetworkManager
import com.leonardos.spikestream.streaming.StreamOverlayController
import com.leonardos.spikestream.streaming.StreamProfile
import com.leonardos.spikestream.streaming.StreamQualityMode
import com.leonardos.spikestream.streaming.StreamTelemetry
import com.leonardos.spikestream.streaming.StreamForegroundService
import com.leonardos.spikestream.streaming.StreamSocketManager
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * The main live streaming activity. Integrates the camera feed, GL score overlays,
 * and background network/bitrate monitors.
 */
class StreamActivity : ComponentActivity(), ConnectChecker {

    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView

    // Modular managers handling specific functionalities
    private lateinit var brightnessManager: StreamBrightnessManager
    private lateinit var networkManager: StreamNetworkManager
    private lateinit var bitrateMonitor: StreamBitrateMonitor
    private lateinit var overlayController: StreamOverlayController
    private lateinit var socketManager: StreamSocketManager
    private lateinit var tokenManager: TokenManager

    // Class-level Compose states to guarantee instant reactivity across native callbacks & UI
    private var currentTeam1Pts by mutableIntStateOf(0)
    private var currentTeam2Pts by mutableIntStateOf(0)
    private var currentTeam1Sets by mutableIntStateOf(0)
    private var currentTeam2Sets by mutableIntStateOf(0)

    private var translatePosition: TranslateTo = TranslateTo.BOTTOM
    private var isStreamingState by mutableStateOf(false)
    private var streamTelemetry by mutableStateOf(StreamTelemetry())
    private var activeProfile by mutableStateOf<StreamProfile?>(null)
    private var activeCameraId: String = "0"
    private var usingOffscreenRenderer = false
    private var lastNotificationUpdateAtMs = 0L
    private var lastNotificationStatus: BroadcastStatus? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Read intent data passed from configuration panel
        val rtmpUrl = intent.getStringExtra("RTMP_URL")
            ?: "rtmps://live-api-s.facebook.com:443/rtmp/YOUR_STREAM_KEY"

        val team1 = intent.getStringExtra("TEAM_1") ?: "Team A"
        val team2 = intent.getStringExtra("TEAM_2") ?: "Team B"
        val id_match = intent.getStringExtra("MATCH_ID") ?: ""
        val cameraId = intent.getStringExtra("CAMERA_ID") ?: "0"
        val overlayPositionString = intent.getStringExtra("OVERLAY_POSITION") ?: "BOTTOM"

        val team1Accent = intent.getIntExtra(
            "TEAM1_ACCENT",
            DefaultOverlayStyle.classic.team1.accent
        )
        val team2Accent = intent.getIntExtra(
            "TEAM2_ACCENT",
            DefaultOverlayStyle.classic.team2.accent
        )

        currentTeam1Pts = intent.getIntExtra("TEAM1_PTS", 0)
        currentTeam2Pts = intent.getIntExtra("TEAM2_PTS", 0)
        currentTeam1Sets = intent.getIntExtra("TEAM1_SETS", 0)
        currentTeam2Sets = intent.getIntExtra("TEAM2_SETS", 0)

        translatePosition = when (overlayPositionString) {
            "BOTTOM_LEFT" -> TranslateTo.BOTTOM_LEFT
            "BOTTOM_RIGHT" -> TranslateTo.BOTTOM_RIGHT
            "TOP_LEFT" -> TranslateTo.TOP_LEFT
            "TOP_RIGHT" -> TranslateTo.TOP_RIGHT
            else -> TranslateTo.BOTTOM
        }

        // Instantiate decoupled functional managers
        tokenManager = TokenManager(applicationContext)
        brightnessManager = StreamBrightnessManager(window)
        networkManager = StreamNetworkManager(applicationContext)
        overlayController = StreamOverlayController(
            context = applicationContext,
            team1 = team1,
            team2 = team2,
            team1Accent = team1Accent,
            team2Accent = team2Accent
        )

        bitrateMonitor = StreamBitrateMonitor(
            context = applicationContext,
            cameraProvider = {
                if (::rtmpCamera.isInitialized) rtmpCamera else null
            },
            onTelemetry = {
                streamTelemetry = it
                if (isStreamingState &&
                    it.sessionStartedAtMs > 0L &&
                    it.status != BroadcastStatus.IDLE
                ) {
                    updateForegroundNotification(it)
                }
            },
            onFatalError = { reason -> handleFatalStreamError(reason) },
        )
        bitrateMonitor.startObserving()

        socketManager = StreamSocketManager(
            context = applicationContext,
            matchId = id_match,
            client = getHttpClient(),
            onScoreUpdated = { team1Pts, team2Pts, team1Sets, team2Sets ->
                currentTeam1Pts = team1Pts
                currentTeam2Pts = team2Pts
                currentTeam1Sets = team1Sets
                currentTeam2Sets = team2Sets
            }
        )
        activeCameraId = cameraId

        setContent {
            MyApplicationTheme {
                StreamingScreen(
                    team1 = team1,
                    team2 = team2,
                    streamUrl = rtmpUrl,
                    matchId = id_match,
                    cameraId = cameraId
                )
            }
        }
    }

    @Composable
    private fun StreamingScreen(
        team1: String,
        team2: String,
        streamUrl: String,
        matchId: String,
        cameraId: String
    ) {
        val ctx = LocalContext.current
        val activity = ctx as Activity
        val configuration = LocalConfiguration.current
        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        var servingTeam by remember { mutableIntStateOf(0) }
        var overlayStyle by remember { mutableStateOf("classic") }
        var selectedQualityMode by rememberSaveable {
            mutableStateOf(StreamQualityMode.AUTO)
        }

        // A live encoder cannot change rotation without a restart. Lock only for
        // the duration of the broadcast so an accidental device turn never drops it.
        LaunchedEffect(isStreamingState) {
            activity.requestedOrientation = if (isStreamingState) {
                ActivityInfo.SCREEN_ORIENTATION_LOCKED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
        }

        // Camera and Microphone Permissions Launcher
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (hasPermissions(ctx)) {
                if (::rtmpCamera.isInitialized && !rtmpCamera.isOnPreview) {
                    runCatching { rtmpCamera.startPreview(cameraId) }
                        .onFailure {
                            Log.e("Stream", "Avvio preview dopo i permessi non riuscito", it)
                        }
                }
            } else {
                Toast.makeText(ctx, ctx.getString(R.string.permissions), Toast.LENGTH_SHORT).show()
            }
        }

        LaunchedEffect(Unit) {
            if (!hasPermissions(ctx)) {
                val permissions = buildList {
                    add(Manifest.permission.CAMERA)
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                launcher.launch(permissions.toTypedArray())
            } else if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Optional: the broadcast still works if notification permission is denied.
                launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }

        // Authenticates and connects WebSocket once the User token is loaded
        LaunchedEffect(Unit) {
            tokenManager.tokenFlow.collect { token ->
                if (token != null) {
                    // Pre-fetch initial points state
                    when (val result = makeGetGameRequest(token, matchId)) {
                        is GetGameResult.Success -> {
                            currentTeam1Pts = result.team1Pts
                            currentTeam2Pts = result.team2Pts
                            currentTeam1Sets = result.team1Sets
                            currentTeam2Sets = result.team2Sets
                        }
                        is GetGameResult.Error -> {
                            Log.e("Stream", "Failed to load initial game state: ${result.message}")
                        }
                    }
                    // Connect socket cleanly
                    socketManager.connect(token)
                }
            }
        }

        // Forza keyframe periodici ogni 2s: fix per encoder hardware (Android < S) che ignorano
        // KEY_I_FRAME_INTERVAL causando GOP da ~11s. Su Android S+ prepareVideoModern già
        // garantisce GOP corretto — il forcing sarebbe ridondante e aumenterebbe il bitrate.
        /*if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            LaunchedEffect(isStreamingState) {
                if (isStreamingState) {
                    while (true) {
                        kotlinx.coroutines.delay(2_000L)
                        if (::rtmpCamera.isInitialized && rtmpCamera.isStreaming) {
                            rtmpCamera.requestKeyFrame()
                        }
                    }
                }
            }
        }*/

        // Layout composition
        Box(Modifier.fillMaxSize()) {
            key(isPortrait) {
                AndroidView(
                    factory = { ctx ->
                        openGlView = OpenGlView(ctx)
                        rtmpCamera = RtmpCamera2(openGlView, this@StreamActivity)

                        openGlView.post {
                            if (!preparePreview(
                                    qualityMode = selectedQualityMode,
                                    isPortrait = isPortrait,
                                    cameraId = cameraId,
                                )
                            ) {
                                Toast.makeText(
                                    ctx,
                                    "Configurazione camera/audio non supportata",
                                    Toast.LENGTH_LONG,
                                ).show()
                                return@post
                            }
                        }

                        openGlView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Sync scoreboard rendering when scores or attributes change
            LaunchedEffect(currentTeam1Pts, currentTeam2Pts, currentTeam1Sets, currentTeam2Sets, servingTeam, overlayStyle, isPortrait) {
                val profile = activeProfile ?: networkManager.selectInitialProfile()
                val width = profile.outputWidth
                val height = profile.outputHeight
                overlayController.updateOverlayIfChanged(
                    team1Pts = currentTeam1Pts,
                    team2Pts = currentTeam2Pts,
                    team1Sets = currentTeam1Sets,
                    team2Sets = currentTeam2Sets,
                    servingTeam = servingTeam,
                    overlayStyle = overlayStyle,
                    overlayPosition = translatePosition,
                    videoWidth = width,
                    videoHeight = height,
                    scope = this
                )
            }

            // Keep the camera preview truly edge-to-edge. Only interactive
            // controls are inset from status/navigation bars and display cutouts.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                if (isStreamingState) {
                    StreamTelemetryPanel(
                        telemetry = streamTelemetry,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }

                if (isStreamingState) {
                    SpikeStreamDangerButton(
                        text = stringResource(R.string.stop_stream),
                        onClick = { stopLiveSession(restorePreview = true) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .widthIn(min = 200.dp)
                    )
                } else {
                    StreamQualitySelector(
                        selectedMode = selectedQualityMode,
                        resolvedProfile = activeProfile ?: networkManager.selectProfile(
                                selectedQualityMode,
                                streamTelemetry.network.takeIf {
                                    it.transport != NetworkTransport.NONE
                                } ?: networkManager.currentSnapshot(),
                            ),
                        onModeSelected = { mode ->
                            if (mode != selectedQualityMode) {
                                selectedQualityMode = mode
                                if (::openGlView.isInitialized) {
                                    openGlView.post {
                                        if (!isStreamingState &&
                                            !preparePreview(mode, isPortrait, cameraId)
                                        ) {
                                            Toast.makeText(
                                                ctx,
                                                "Profilo video non supportato dal dispositivo",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 82.dp),
                    )

                    SpikeStreamPrimaryButton(
                        text = stringResource(R.string.launch_stream),
                        onClick = {
                            startLiveSession(streamUrl, isPortrait, selectedQualityMode)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .widthIn(min = 200.dp)
                    )
                }
            }
        }
    }

    private fun preparePreview(
        qualityMode: StreamQualityMode,
        isPortrait: Boolean,
        cameraId: String,
    ): Boolean {
        if (!::rtmpCamera.isInitialized || isStreamingState) return false

        val preferredProfile = networkManager.selectProfile(qualityMode)
        val preparedProfile = networkManager.prepareVideoCompat(
            camera = rtmpCamera,
            preferredProfile = preferredProfile,
            isPortrait = isPortrait,
        ) ?: return false

        if (!rtmpCamera.prepareAudio(128 * 1024, 48000, true)) return false

        activeProfile = preparedProfile
        overlayController.applyOverlay(
            rtmpCamera = rtmpCamera,
            width = preparedProfile.outputWidth,
            height = preparedProfile.outputHeight,
            overlayPosition = translatePosition,
            team1Pts = currentTeam1Pts,
            team2Pts = currentTeam2Pts,
            team1Sets = currentTeam1Sets,
            team2Sets = currentTeam2Sets,
        )
        rtmpCamera.setFpsListener { fps -> bitrateMonitor.onFps(fps) }

        if (hasPermissions(this) && !rtmpCamera.isOnPreview) {
            rtmpCamera.startPreview(cameraId)
        }
        return true
    }

    @Composable
    private fun StreamTelemetryPanel(
        telemetry: StreamTelemetry,
        modifier: Modifier = Modifier,
    ) {
        val statusColor = when (telemetry.status) {
            BroadcastStatus.LIVE -> when (telemetry.health) {
                StreamHealth.GOOD -> Color(0xFF39D98A)
                StreamHealth.DEGRADED -> Color(0xFFFFC857)
                StreamHealth.CRITICAL -> Color(0xFFFF5C5C)
            }
            BroadcastStatus.ERROR -> Color(0xFFFF5C5C)
            BroadcastStatus.WAITING_NETWORK,
            BroadcastStatus.RECOVERING -> Color(0xFFFFC857)
            else -> Color(0xFF8AB4F8)
        }
        val networkLabel = when (telemetry.network.transport) {
            NetworkTransport.WIFI -> "WI-FI"
            NetworkTransport.CELLULAR -> "MOBILE"
            NetworkTransport.ETHERNET -> "ETHERNET"
            NetworkTransport.VPN -> "VPN"
            NetworkTransport.OTHER -> "RETE"
            NetworkTransport.NONE -> "OFFLINE"
        }
        var timerNowMs by remember(telemetry.sessionStartedAtMs) {
            mutableLongStateOf(SystemClock.elapsedRealtime())
        }
        LaunchedEffect(telemetry.sessionStartedAtMs) {
            while (telemetry.sessionStartedAtMs > 0L) {
                timerNowMs = SystemClock.elapsedRealtime()
                kotlinx.coroutines.delay(1_000L)
            }
        }
        val totalSeconds = if (telemetry.sessionStartedAtMs > 0L) {
            ((timerNowMs - telemetry.sessionStartedAtMs) / 1_000L).coerceAtLeast(0L)
        } else {
            0L
        }
        val duration = "%02d:%02d:%02d".format(
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60,
        )
        val outgoingMbps = telemetry.actualBitrate / 1_000_000.0
        val targetMbps = telemetry.targetVideoBitrate / 1_000_000.0

        Card(
            modifier = modifier
                .fillMaxWidth(0.96f)
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.82f),
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                statusColor.copy(alpha = 0.7f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(statusColor)
                    )
                    Text(
                        text = telemetry.status.label,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = duration,
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                Text(
                    text = buildString {
                        append("RTMP %.1f Mbps".format(outgoingMbps))
                        append("  •  video target %.1f".format(targetMbps))
                        append("  •  ${telemetry.fps} fps")
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Text(
                    text = buildString {
                        append("${telemetry.profileName}  •  $networkLabel")
                        if (telemetry.droppedFrames > 0) {
                            append("  •  scartati ${telemetry.droppedFrames}")
                        }
                        if (telemetry.queueCapacity > 0) {
                            append("  •  coda ${telemetry.queueItems}/${telemetry.queueCapacity}")
                        }
                    },
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelMedium,
                )

                if (telemetry.detail.isNotBlank()) {
                    Text(
                        text = telemetry.detail,
                        color = statusColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    @Composable
    private fun StreamQualitySelector(
        selectedMode: StreamQualityMode,
        resolvedProfile: StreamProfile,
        onModeSelected: (StreamQualityMode) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val modes = listOf(
            StreamQualityMode.AUTO to "AUTO",
            StreamQualityMode.STABLE_480 to "480p",
            StreamQualityMode.HD_720 to "720p",
        )
        val description = when (selectedMode) {
            StreamQualityMode.AUTO ->
                "Consigliato nelle palestre: stabilità prima della risoluzione"
            StreamQualityMode.STABLE_480 ->
                "Massima continuità su Wi-Fi, 4G e 5G"
            StreamQualityMode.HD_720 ->
                "Per connessioni con upload realmente stabile"
        }

        Card(
            modifier = modifier
                .fillMaxWidth(0.96f)
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.82f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "QUALITÀ DIRETTA",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${resolvedProfile.name} • ${resolvedProfile.fps} fps",
                        color = Color(0xFF39D98A),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    modes.forEach { (mode, label) ->
                        FilterChip(
                            selected = mode == selectedMode,
                            onClick = { onModeSelected(mode) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    private fun startLiveSession(
        streamUrl: String,
        isPortrait: Boolean,
        qualityMode: StreamQualityMode,
    ) {
        if (!::rtmpCamera.isInitialized || !hasPermissions(this)) {
            Toast.makeText(this, getString(R.string.permissions), Toast.LENGTH_SHORT).show()
            return
        }

        val snapshot = networkManager.currentSnapshot()
        if (!snapshot.hasUsableInternet) {
            Toast.makeText(
                this,
                "Nessuna connessione Internet validata",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        overlayController.removeOverlay(rtmpCamera)
        val preferredProfile = networkManager.selectProfile(qualityMode, snapshot)
        val preparedProfile = networkManager.prepareVideoCompat(
            camera = rtmpCamera,
            preferredProfile = preferredProfile,
            isPortrait = isPortrait,
        )
        val audioPrepared = preparedProfile != null &&
            rtmpCamera.prepareAudio(128 * 1024, 48000, true)

        if (preparedProfile == null || !audioPrepared) {
            Toast.makeText(
                this,
                "Il dispositivo non supporta una configurazione streaming compatibile",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        activeProfile = preparedProfile
        val width = preparedProfile.outputWidth
        val height = preparedProfile.outputHeight
        overlayController.applyOverlay(
            rtmpCamera = rtmpCamera,
            width = width,
            height = height,
            overlayPosition = translatePosition,
            team1Pts = currentTeam1Pts,
            team2Pts = currentTeam2Pts,
            team1Sets = currentTeam1Sets,
            team2Sets = currentTeam2Sets,
        )

        bitrateMonitor.beginSession(preparedProfile)
        StreamForegroundService.start(
            this,
            "Spike Stream LIVE",
            "Connessione alla piattaforma • ${preparedProfile.name}",
        )

        runCatching {
            rtmpCamera.startStream(streamUrl)
        }.onSuccess {
            isStreamingState = true
            usingOffscreenRenderer = false
            brightnessManager.setDimmed(true)
        }.onFailure {
            Log.e("Stream", "Avvio RTMP non riuscito", it)
            bitrateMonitor.endSession()
            StreamForegroundService.stop(this)
            brightnessManager.restore()
            Toast.makeText(this, "Impossibile avviare la diretta", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLiveSession(restorePreview: Boolean) {
        bitrateMonitor.endSession()
        if (::rtmpCamera.isInitialized && rtmpCamera.isStreaming) {
            runCatching { rtmpCamera.stopStream() }
        }
        StreamForegroundService.stop(this)
        isStreamingState = false
        usingOffscreenRenderer = false
        lastNotificationUpdateAtMs = 0L
        lastNotificationStatus = null
        brightnessManager.restore()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER

        if (restorePreview &&
            ::rtmpCamera.isInitialized &&
            !rtmpCamera.isOnPreview &&
            hasPermissions(this)
        ) {
            runCatching { rtmpCamera.startPreview(activeCameraId) }
        }
    }

    private fun handleFatalStreamError(reason: String) {
        runOnUiThread {
            if (::rtmpCamera.isInitialized && rtmpCamera.isStreaming) {
                runCatching { rtmpCamera.stopStream() }
            }
            StreamForegroundService.stop(this)
            isStreamingState = false
            usingOffscreenRenderer = false
            brightnessManager.restore()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            Toast.makeText(this, "Diretta interrotta: $reason", Toast.LENGTH_LONG).show()
            if (::rtmpCamera.isInitialized &&
                !rtmpCamera.isOnPreview &&
                hasPermissions(this)
            ) {
                runCatching { rtmpCamera.startPreview(activeCameraId) }
            }
        }
    }

    private fun updateForegroundNotification(telemetry: StreamTelemetry) {
        val now = SystemClock.elapsedRealtime()
        val statusChanged = telemetry.status != lastNotificationStatus
        if (!statusChanged && now - lastNotificationUpdateAtMs < 5_000L) return

        val bitrate = if (telemetry.actualBitrate > 0L) {
            "%.2f Mbps".format(telemetry.actualBitrate / 1_000_000.0)
        } else {
            "connessione…"
        }
        runCatching {
            StreamForegroundService.update(
                this,
                "Spike Stream • ${telemetry.status.label}",
                "$bitrate • ${telemetry.profileName} • ${telemetry.detail}",
            )
        }.onSuccess {
            lastNotificationUpdateAtMs = now
            lastNotificationStatus = telemetry.status
        }.onFailure {
            Log.w("Stream", "Aggiornamento notifica LIVE non riuscito: ${it.message}")
        }
    }

    private fun hasPermissions(ctx: Context) = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    private fun applyOverlayToCurrentRenderer() {
        val profile = activeProfile ?: return
        overlayController.applyOverlay(
            rtmpCamera = rtmpCamera,
            width = profile.outputWidth,
            height = profile.outputHeight,
            overlayPosition = translatePosition,
            team1Pts = currentTeam1Pts,
            team2Pts = currentTeam2Pts,
            team1Sets = currentTeam1Sets,
            team2Sets = currentTeam2Sets,
        )
    }

    private fun moveStreamToOffscreenRenderer() {
        if (!::rtmpCamera.isInitialized ||
            !rtmpCamera.isStreaming ||
            usingOffscreenRenderer
        ) return

        runCatching {
            // OpenGlView belongs to the Activity and its Surface can disappear in
            // background. RootEncoder's context renderer owns an offscreen EGL surface.
            rtmpCamera.replaceView(applicationContext)
            // The encoder input is portrait when rotation=90, but the published
            // canvas is deliberately landscape 16:9. GlStreamInterface otherwise
            // treats that canvas as portrait and changes its viewport.
            (rtmpCamera.glInterface as? GlStreamInterface)
                ?.setStreamIsPortrait(false)
            applyOverlayToCurrentRenderer()
            usingOffscreenRenderer = true
        }.onFailure {
            Log.e("Stream", "Passaggio al renderer OpenGL offscreen non riuscito", it)
        }
    }

    private fun restoreStreamPreview() {
        if (!::rtmpCamera.isInitialized ||
            !::openGlView.isInitialized ||
            !rtmpCamera.isStreaming ||
            !usingOffscreenRenderer
        ) return

        openGlView.post {
            if (!rtmpCamera.isStreaming || !usingOffscreenRenderer) return@post
            runCatching {
                rtmpCamera.replaceView(openGlView)
                applyOverlayToCurrentRenderer()
                usingOffscreenRenderer = false
            }.onFailure {
                Log.e("Stream", "Ripristino preview OpenGL non riuscito", it)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (isStreamingState) {
            restoreStreamPreview()
        } else if (::rtmpCamera.isInitialized &&
            !isStreamingState &&
            !rtmpCamera.isOnPreview &&
            hasPermissions(this)
        ) {
            runCatching { rtmpCamera.startPreview(activeCameraId) }
        }
    }

    override fun onStop() {
        if (isStreamingState) {
            moveStreamToOffscreenRenderer()
        } else if (::rtmpCamera.isInitialized && rtmpCamera.isOnPreview) {
            runCatching { rtmpCamera.stopPreview() }
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isStreamingState) {
            stopLiveSession(restorePreview = false)
        } else if (::rtmpCamera.isInitialized && rtmpCamera.isOnPreview) {
            runCatching { rtmpCamera.stopPreview() }
        }
        bitrateMonitor.stopObserving()
        socketManager.disconnect()
        brightnessManager.restore()
        super.onDestroy()
    }

    // RTMP callbacks
    override fun onConnectionStarted(url: String) {
        Log.i("Stream", "Connessione RTMP avviata")
        bitrateMonitor.onConnectionStarted()
    }

    override fun onConnectionSuccess() {
        Log.i("Stream", "Connessione RTMP stabilita")
        bitrateMonitor.onConnectionSuccess()
    }

    override fun onConnectionFailed(reason: String) {
        Log.e("Stream", "Connessione RTMP fallita: $reason")
        bitrateMonitor.onConnectionFailed(reason)
    }

    override fun onNewBitrate(bitrate: Long) {
        bitrateMonitor.onNewBitrate(bitrate)
    }

    override fun onDisconnect() {
        Log.w("Stream", "RTMP disconnesso")
        bitrateMonitor.onDisconnect()
    }

    override fun onAuthError() {
        Log.e("Stream", "Errore autenticazione RTMP")
        bitrateMonitor.onAuthError()
    }

    override fun onAuthSuccess() {
        Log.i("Stream", "Autenticazione RTMP riuscita")
    }
}
