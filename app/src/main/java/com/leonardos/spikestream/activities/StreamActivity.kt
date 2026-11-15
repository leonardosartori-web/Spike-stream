package com.leonardos.spikestream.activities

import com.leonardos.spikestream.ui.components.ManagedYouTubeControls
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import com.leonardos.spikestream.data.BroadcastReport
import com.leonardos.spikestream.ui.components.BroadcastFeedbackDialog
import android.media.MediaCodec
import android.view.SurfaceHolder
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
import com.pedro.encoder.CodecErrorCallback
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.gl.TranslateTo
import com.leonardos.spikestream.streaming.AdaptiveRtmpCamera
import com.pedro.library.view.GlStreamInterface
import com.pedro.library.view.OpenGlView
import com.leonardos.spikestream.ui.components.DefaultOverlayStyle
import com.leonardos.spikestream.R
import kotlinx.coroutines.launch
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

    private var rtmpReadySince = Long.MAX_VALUE
    private var replayAuthorized = false
    private var selectedReplayTeam by mutableStateOf<String?>(null)
    private var replayFailed by mutableStateOf(false)
    private var replayFilter: com.leonardos.spikestream.streaming.ReplayFilter? = null

    private fun attachReplay() {
        if (streamTelemetry.status != BroadcastStatus.LIVE || streamTelemetry.health == StreamHealth.CRITICAL) return
        if (!replayAuthorized || replayFilter != null || selectedReplayTeam == null || replayFailed) return
        lateinit var filter: com.leonardos.spikestream.streaming.ReplayFilter
        filter = com.leonardos.spikestream.streaming.ReplayFilter(translatePosition == TranslateTo.TOP_RIGHT) { active, failed ->
            if (replayFilter === filter) {
                if (failed) {
                    replayFailed = true
                    detachReplay()
                    Toast.makeText(this,R.string.automatic_replay_unavailable,Toast.LENGTH_LONG).show()
                }
            }
        }
        replayFilter = filter
        desk.replayAttached = true
        rtmpCamera.glInterface.addFilter(0, filter)
    }

    private fun detachReplay() {
        replayFilter?.let { rtmpCamera.glInterface.removeFilter(it) }
        replayFilter = null
        if (::desk.isInitialized) desk.replayAttached = false
    }

    private lateinit var rtmpCamera: AdaptiveRtmpCamera
    private lateinit var openGlView: OpenGlView

    // Modular managers handling specific functionalities
    private lateinit var brightnessManager: StreamBrightnessManager
    private lateinit var networkManager: StreamNetworkManager
    private lateinit var bitrateMonitor: StreamBitrateMonitor
    private lateinit var overlayController: StreamOverlayController
    private lateinit var desk: com.leonardos.spikestream.streaming.ProductionDesk
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
    private var preparedCamera: AdaptiveRtmpCamera? = null
    private var preparedQualityMode: StreamQualityMode? = null
    private var preparedIsPortrait: Boolean? = null
    private var usingOffscreenRenderer = false
    private var cameraReleased = true
    private var cameraGeneration = 0L
    private var activityVisible = false
    private var closing = false
    private var activeReport: BroadcastReport? = null
    private var reportStartedElapsed = 0L
    private var connectionCount = 0
    private var pendingReport by mutableStateOf<BroadcastReport?>(null)
    private var showFeedback by mutableStateOf(false)
    private var exitAfterFeedback = false
    private var lastQualityMode = StreamQualityMode.AUTO
    private var lastNotificationUpdateAtMs = 0L
    private var lastNotificationStatus: BroadcastStatus? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        pendingReport = savedInstanceState?.getSerializable("pendingReport") as? BroadcastReport
        showFeedback = savedInstanceState?.getBoolean("showFeedback") == true
        exitAfterFeedback = savedInstanceState?.getBoolean("exitAfterFeedback") == true
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
                if (isStreamingState && replayAuthorized) {
                    if (it.status == BroadcastStatus.LIVE && it.health != StreamHealth.CRITICAL) attachReplay()
                    else if (replayFilter != null) detachReplay()
                }
                if (::socketManager.isInitialized) socketManager.updateTelemetry(it)
                if (isStreamingState &&
                    it.sessionStartedAtMs > 0L &&
                    it.status != BroadcastStatus.IDLE
                ) {
                    updateForegroundNotification(it)
                }
            },
            onFatalError = { reason -> handleFatalStreamError(reason) },
            onConnected = { connectionCount++ },
        )
        bitrateMonitor.startObserving()

        desk = com.leonardos.spikestream.streaming.ProductionDesk(this, overlayController::setVisible,
            overlayController::confirmFrame) {
            if (::socketManager.isInitialized) socketManager.publishProduction(it)
        }
        socketManager = StreamSocketManager(
            context = applicationContext,
            matchId = id_match,
            client = getHttpClient(),
            onProductionCommand = {
                desk.command(it)
                if (desk.spotActive) replayFilter?.stopReplay()
            },
            replayTeam = { selectedReplayTeam.takeIf { isStreamingState && !replayFailed } },
            onReplayStopped = { replayFilter?.stopReplay() },
            onReplayAuthorized = {
                replayAuthorized = true
                if (isStreamingState) attachReplay()
            },
            onReplayDenied = {
                replayAuthorized = false
                selectedReplayTeam = null
                detachReplay()
            },
            onReplay = { actionId, received, deadline ->
                if (!desk.spotActive && streamTelemetry.status == BroadcastStatus.LIVE &&
                    received / 1000 - rtmpReadySince >= 5000) replayFilter?.trigger(actionId,received,deadline)
            },
            onProductionConnect = desk::changed,
            onConfig = {
                val team = it.optString("replayTeam").takeIf { value -> value in setOf("A","B","*") }
                if (team != selectedReplayTeam) {
                    replayAuthorized = false
                    detachReplay()
                    selectedReplayTeam = team
                }
                desk.receiveConfig(it)
            },
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
        val youtubeManaged = remember { intent.getBooleanExtra("YOUTUBE_MANAGED", false) }
        var managedControlsHeight by remember { mutableIntStateOf(0) }
        val managedControlsPadding = with(LocalDensity.current) { managedControlsHeight.toDp() } + 16.dp
        var servingTeam by remember { mutableIntStateOf(0) }
        var overlayStyle by remember { mutableStateOf("classic") }
        BackHandler(enabled = !showFeedback && (isStreamingState || pendingReport != null)) {
            exitAfterFeedback = true
            if (isStreamingState) stopLiveSession(restorePreview = false)
            if (pendingReport != null) showFeedback = true else finish()
        }
        pendingReport?.takeIf { showFeedback }?.let { report ->
            BroadcastFeedbackDialog(report.sessionId) { rating, description ->
                if (rating != null) {
                    val queued = report.enqueue(applicationContext, rating, description).result
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { queued.get() }
                }
                pendingReport = null
                showFeedback = false
                if (rating == "problem") {
                    Toast.makeText(applicationContext, R.string.feedback_saved, Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@StreamActivity, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                    finish()
                } else if (exitAfterFeedback) {
                    finish()
                }
                exitAfterFeedback = false
            }
        }
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
        ) { result ->
            if (hasPermissions(ctx)) {
                val mediaPermissionResult =
                    result.containsKey(Manifest.permission.CAMERA) ||
                        result.containsKey(Manifest.permission.RECORD_AUDIO)

                // The first permission grant must follow the same preparation
                // path as subsequent launches. Starting the camera directly
                // would bypass the OpenGL score overlay and encoder profile.
                if (mediaPermissionResult && ::openGlView.isInitialized) {
                    openGlView.post {
                        if (!isStreamingState &&
                            !preparePreview(selectedQualityMode, isPortrait, cameraId)
                        ) {
                            Toast.makeText(
                                ctx,
                                "Configurazione camera/audio non supportata",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
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
                if (token != null && matchId.isNotBlank()) {
                    socketManager.connect(token)
                } else {
                    socketManager.disconnect()
                }
            }
        }

        // Layout composition
        Box(Modifier.fillMaxSize()) {
            key(isPortrait) {
                AndroidView(
                    factory = { ctx ->
                        releaseCurrentCamera()
                        val previewView = OpenGlView(ctx)
                        openGlView = previewView
                        createCamera(previewView)
                        previewView.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                if (openGlView === previewView) {
                                    if (isStreamingState) restoreStreamPreview() else restoreIdlePreview()
                                }
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                        })

                        openGlView.post {
                            // On the first launch the permission dialog is still
                            // pending. Its callback will prepare the preview once
                            // camera and microphone access have been granted.
                            if (openGlView !== previewView || !activityVisible || closing ||
                                !previewView.holder.surface.isValid || !hasPermissions(ctx)
                            ) return@post
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
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        if (::openGlView.isInitialized && openGlView === view) releaseCurrentCamera()
                    },
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
                if (!isStreamingState && pendingReport?.outcome == "error" && !showFeedback) {
                    Button(onClick = { showFeedback = true }, modifier = Modifier.align(Alignment.TopCenter)) {
                        Text(stringResource(R.string.feedback_finish_attempt))
                    }
                }
                if (isStreamingState) {
                    StreamTelemetryPanel(
                        telemetry = streamTelemetry,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }

                if (isStreamingState) {
                    if (!youtubeManaged) SpikeStreamDangerButton(
                        text = stringResource(R.string.youtube_pause_video),
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
                            .padding(bottom = if (youtubeManaged) managedControlsPadding else 82.dp),
                    )

                    if (!youtubeManaged) SpikeStreamPrimaryButton(
                        text = stringResource(R.string.launch_stream),
                        onClick = { startLiveSession(streamUrl, isPortrait, selectedQualityMode) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .widthIn(min = 200.dp)
                    )
                }
                if (youtubeManaged) ManagedYouTubeControls(
                    matchId = matchId, streaming = isStreamingState,
                    appToken = { tokenManager.currentToken },
                    canStart = { activityVisible && !closing && hasPermissions(ctx) },
                    onStart = { url -> if (activityVisible && !closing && !isFinishing && !isStreamingState) startLiveSession(url, isPortrait, selectedQualityMode) },
                    onPause = { stopLiveSession(restorePreview = true, prompt = false) },
                    onClosed = { showFeedback = pendingReport != null },
                    initialBroadcastId = intent.getStringExtra("YOUTUBE_BROADCAST_ID"),
                    modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { managedControlsHeight = it.height }.fillMaxWidth()
                )
            }
        }
    }

    private fun preparePreview(
        qualityMode: StreamQualityMode,
        isPortrait: Boolean,
        cameraId: String,
    ): Boolean {
        if (!::rtmpCamera.isInitialized || isStreamingState || !activityVisible || closing) return false
        if (!::openGlView.isInitialized || !openGlView.holder.surface.isValid || !hasPermissions(this)) return false
        lastQualityMode = qualityMode

        val preparedProfile = reusablePreparedProfile(qualityMode, isPortrait)
            ?: prepareEncoders(qualityMode, isPortrait) ?: run {
                releaseCurrentCamera()
                return false
            }
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

        if (hasPermissions(this) && !rtmpCamera.isOnPreview) {
            if (runCatching { rtmpCamera.startPreview(cameraId) }.isFailure) {
                releaseCurrentCamera()
                return false
            }
        }
        return true
    }

    /**
     * Prepares camera and microphone once for both preview and broadcast.
     * Recreating AudioRecord and MediaCodec when the operator taps LIVE is
     * unnecessary and is less reliable on older Android 7/8 vendor codecs.
     */
    private fun prepareEncoders(
        qualityMode: StreamQualityMode,
        isPortrait: Boolean,
    ): StreamProfile? {
        if (cameraReleased || preparedCamera != null) createCamera(openGlView)
        invalidatePreparedEncoders()

        val preferredProfile = networkManager.selectProfile(qualityMode)
        val preparedProfile = networkManager.prepareVideoCompat(
            camera = rtmpCamera,
            preferredProfile = preferredProfile,
            isPortrait = isPortrait,
        ) ?: return null

        val audioPrepared = runCatching {
            // SponsorAudio supplies 48 kHz stereo PCM; keep the capture/encoder
            // clock identical so inserting an ad cannot change speed or A/V sync.
            rtmpCamera.prepareAudio(128_000, 48000, true)
        }.getOrElse {
            Log.e("Stream", "Preparazione microfono/AAC non riuscita", it)
            false
        }
        if (!audioPrepared) return null

        activeProfile = preparedProfile
        preparedCamera = rtmpCamera
        preparedQualityMode = qualityMode
        preparedIsPortrait = isPortrait
        return preparedProfile
    }

    private fun reusablePreparedProfile(
        qualityMode: StreamQualityMode,
        isPortrait: Boolean,
    ): StreamProfile? = activeProfile?.takeIf {
        !cameraReleased && preparedCamera === rtmpCamera &&
            preparedQualityMode == qualityMode &&
            preparedIsPortrait == isPortrait
    }

    private fun invalidatePreparedEncoders() {
        preparedCamera = null
        preparedQualityMode = null
        preparedIsPortrait = null
    }

    private fun releaseCurrentCamera() {
        rtmpReadySince = Long.MAX_VALUE
        replayAuthorized = false
        detachReplay()
        if (::desk.isInitialized) { desk.setLive(false); desk.detach() }
        if (!::rtmpCamera.isInitialized || cameraReleased) return
        cameraReleased = true
        cameraGeneration++ // Discard callbacks already queued by the previous instance.
        invalidatePreparedEncoders()
        overlayController.removeOverlay(rtmpCamera)
        // Also releases prepared codecs when startStream failed or was never called.
        runCatching { rtmpCamera.stopStream() }
            .onFailure { Log.e("Stream", "Arresto encoder non riuscito", it) }
        runCatching { rtmpCamera.stopPreview() }
            .onFailure { Log.e("Stream", "Rilascio camera non riuscito", it) }
        usingOffscreenRenderer = false
    }

    private fun dispatchCameraEvent(generation: Long, action: () -> Unit) {
        // Always queue: never tear down a codec from inside its own callback.
        mainExecutorCompat.post {
            if (generation == cameraGeneration && !cameraReleased && !closing) action()
        }
    }

    private val mainExecutorCompat = android.os.Handler(android.os.Looper.getMainLooper())

    private fun createCamera(view: OpenGlView) {
        releaseCurrentCamera()
        val generation = ++cameraGeneration
        val checker = object : ConnectChecker {
            override fun onConnectionStarted(url: String) = dispatchCameraEvent(generation) { this@StreamActivity.onConnectionStarted(url) }
            override fun onConnectionSuccess() = dispatchCameraEvent(generation) { this@StreamActivity.onConnectionSuccess() }
            override fun onConnectionFailed(reason: String) = dispatchCameraEvent(generation) { this@StreamActivity.onConnectionFailed(reason) }
            override fun onNewBitrate(bitrate: Long) = dispatchCameraEvent(generation) { this@StreamActivity.onNewBitrate(bitrate) }
            override fun onDisconnect() = dispatchCameraEvent(generation) { this@StreamActivity.onDisconnect() }
            override fun onAuthError() = dispatchCameraEvent(generation) { this@StreamActivity.onAuthError() }
            override fun onAuthSuccess() = dispatchCameraEvent(generation) { this@StreamActivity.onAuthSuccess() }
        }
        rtmpCamera = AdaptiveRtmpCamera(view, checker)
        rtmpCamera.setVideoCodec(com.pedro.common.VideoCodec.H264)
        rtmpCamera.setAudioCodec(com.pedro.common.AudioCodec.AAC)
        cameraReleased = false
        rtmpCamera.setFpsListener { fps ->
            dispatchCameraEvent(generation) { bitrateMonitor.onFps(fps) }
        }
        fun mediaFailure(reason: String) = dispatchCameraEvent(generation) {
            if (isStreamingState) bitrateMonitor.onMediaError(reason)
            else {
                releaseCurrentCamera()
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            }
        }
        rtmpCamera.setEncoderErrorCallback(object : CodecErrorCallback {
            override fun onCodecError(type: CodecUtil.CodecTypeError, e: MediaCodec.CodecException) {
                Log.e("Stream", "Errore codec $type", e)
                mediaFailure("Errore encoder $type")
            }
            override fun onEncodeError(type: CodecUtil.CodecTypeError, e: IllegalStateException): Boolean {
                Log.e("Stream", "Codifica interrotta: $type", e)
                mediaFailure("Codifica interrotta: $type")
                return false
            }
        })
        rtmpCamera.setCameraCallbacks(object : CameraCallbacks {
            override fun onCameraChanged(facing: CameraHelper.Facing) = Unit
            override fun onCameraOpened() = Unit
            override fun onCameraError(error: String) = mediaFailure("Camera non disponibile")
            override fun onCameraDisconnected() = mediaFailure("Camera disconnessa")
        })
    }

    private fun restoreIdlePreview() {
        if (!::openGlView.isInitialized || closing || !activityVisible) return
        val view = openGlView
        view.post {
            if (openGlView !== view || closing || !activityVisible || isStreamingState ||
                !view.holder.surface.isValid || !hasPermissions(this)
            ) return@post
            val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            if (!preparePreview(lastQualityMode, portrait, activeCameraId)) {
                Log.w("Stream", "Preview non disponibile")
            }
        }
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
                    text = "Encoder video %.1f Mbps • audio %d kbps".format(
                        telemetry.encodedVideoBitrate / 1_000_000.0,
                        telemetry.encodedAudioBitrate / 1_000),
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelMedium,
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
                        if (telemetry.recoveryDroppedFrames > 0) {
                            append("  •  risinc. ${telemetry.recoveryDroppedFrames}")
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
                "480p con bitrate adattivo alla connessione"
            StreamQualityMode.STABLE_480 ->
                "480p per ridurre il consumo di banda"
            StreamQualityMode.HD_720 ->
                "720p con recupero graduale della qualità"
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
        if (isStreamingState || closing || !activityVisible) return
        if (!::rtmpCamera.isInitialized || !hasPermissions(this)) {
            Toast.makeText(this, getString(R.string.permissions), Toast.LENGTH_SHORT).show()
            return
        }

        pendingReport = null
        showFeedback = false
        connectionCount = 0
        reportStartedElapsed = SystemClock.elapsedRealtime()
        activeReport = BroadcastReport.create(tokenManager.currentToken, intent.getStringExtra("MATCH_ID").orEmpty())
        activeReport?.enqueue(applicationContext)
        val snapshot = networkManager.currentSnapshot()
        if (!snapshot.hasUsableInternet) {
            completeReport("error", prompt = false)
            Toast.makeText(
                this,
                "Nessuna connessione Internet validata",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        overlayController.removeOverlay(rtmpCamera)
        val preparedProfile = reusablePreparedProfile(qualityMode, isPortrait)
            ?: prepareEncoders(qualityMode, isPortrait)

        if (preparedProfile == null) {
            completeReport("error", prompt = false)
            releaseCurrentCamera()
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

        runCatching {
            bitrateMonitor.beginSession(preparedProfile)
            StreamForegroundService.start(
                this,
                "Spike Stream LIVE",
                "Connessione alla piattaforma • ${preparedProfile.name}",
            )
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            isStreamingState = true
            replayFailed = false
            replayAuthorized = false
            desk.attach(rtmpCamera, width, height)
            rtmpCamera.startStream(streamUrl)
            desk.setLive(true)
        }.onSuccess {
            if (isStreamingState) brightnessManager.setDimmed(true)
        }.onFailure {
            Log.e("Stream", "Avvio RTMP non riuscito", it)
            stopLiveSession(restorePreview = true, outcome = "error", prompt = false)
            Toast.makeText(this, "Impossibile avviare la diretta", Toast.LENGTH_LONG).show()
        }
    }

    private fun completeReport(outcome: String, prompt: Boolean) {
        val report = activeReport ?: return
        activeReport = null
        val completed = report.copy(
            outcome = outcome,
            durationSeconds = (SystemClock.elapsedRealtime() - reportStartedElapsed) / 1000,
            reconnections = (connectionCount - 1).coerceAtLeast(0),
        )
        completed.enqueue(applicationContext)
        pendingReport = completed
        showFeedback = prompt
    }

    private fun stopLiveSession(restorePreview: Boolean, outcome: String = "stopped", prompt: Boolean = true) {
        completeReport(outcome, prompt)
        bitrateMonitor.endSession()
        releaseCurrentCamera()
        StreamForegroundService.stop(this)
        isStreamingState = false
        usingOffscreenRenderer = false
        lastNotificationUpdateAtMs = 0L
        lastNotificationStatus = null
        brightnessManager.restore()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER

        if (restorePreview) restoreIdlePreview()
    }

    private fun handleFatalStreamError(reason: String) {
        runOnUiThread {
            if (closing) return@runOnUiThread
            completeReport("error", prompt = false)
            releaseCurrentCamera()
            StreamForegroundService.stop(this)
            isStreamingState = false
            usingOffscreenRenderer = false
            brightnessManager.restore()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            Toast.makeText(this, "Diretta interrotta: $reason", Toast.LENGTH_LONG).show()
            restoreIdlePreview()
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
            Log.w("Stream", "Aggiornamento notifica LIVE non riuscito")
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
            detachReplay()
            desk.detach(transfer = true)
            rtmpCamera.replaceView(applicationContext)
            // The encoder input is portrait when rotation=90, but the published
            // canvas is deliberately landscape 16:9. GlStreamInterface otherwise
            // treats that canvas as portrait and changes its viewport.
            (rtmpCamera.glInterface as? GlStreamInterface)
                ?.setStreamIsPortrait(false)
            applyOverlayToCurrentRenderer()
            attachReplay()
            activeProfile?.let { desk.attach(rtmpCamera,it.outputWidth,it.outputHeight) }
            usingOffscreenRenderer = true
        }.onFailure {
            Log.e("Stream", "Passaggio al renderer OpenGL offscreen non riuscito", it)
            bitrateMonitor.onMediaError("Impossibile mantenere il video in background")
        }
    }

    private fun restoreStreamPreview() {
        if (!::rtmpCamera.isInitialized ||
            !::openGlView.isInitialized ||
            !rtmpCamera.isStreaming ||
            !usingOffscreenRenderer
        ) return

        openGlView.post {
            if (closing || !activityVisible || cameraReleased ||
                !openGlView.holder.surface.isValid ||
                !rtmpCamera.isStreaming || !usingOffscreenRenderer
            ) return@post
            runCatching {
                detachReplay()
                desk.detach(transfer = true)
                rtmpCamera.replaceView(openGlView)
                applyOverlayToCurrentRenderer()
                attachReplay()
                activeProfile?.let { desk.attach(rtmpCamera,it.outputWidth,it.outputHeight) }
                usingOffscreenRenderer = false
            }.onFailure {
                Log.e("Stream", "Ripristino preview OpenGL non riuscito", it)
                bitrateMonitor.onMediaError("Impossibile ripristinare il video")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        if (isStreamingState) {
            restoreStreamPreview()
        } else restoreIdlePreview()
    }

    override fun onStop() {
        activityVisible = false
        if (isStreamingState) {
            moveStreamToOffscreenRenderer()
        } else releaseCurrentCamera()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable("pendingReport", pendingReport)
        outState.putBoolean("showFeedback", showFeedback)
        outState.putBoolean("exitAfterFeedback", exitAfterFeedback)
    }

    override fun onDestroy() {
        closing = true
        activityVisible = false
        if (isStreamingState) {
            stopLiveSession(restorePreview = false, outcome = "interrupted", prompt = false)
        } else releaseCurrentCamera()
        desk.close()
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
        rtmpReadySince = SystemClock.elapsedRealtime()
        bitrateMonitor.onConnectionSuccess()
    }

    override fun onConnectionFailed(reason: String) {
        Log.e("Stream", "Connessione RTMP fallita")
        replayFilter?.stopReplay()
        bitrateMonitor.onConnectionFailed(reason)
    }

    override fun onNewBitrate(bitrate: Long) {
        bitrateMonitor.onNewBitrate(bitrate)
    }

    override fun onDisconnect() {
        Log.w("Stream", "RTMP disconnesso")
        replayFilter?.stopReplay()
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
