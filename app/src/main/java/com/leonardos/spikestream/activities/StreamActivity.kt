package com.leonardos.spikestream.activities

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.leonardos.spikestream.utils.Logger as Log
import com.leonardos.spikestream.ui.theme.MyApplicationTheme
import com.leonardos.spikestream.ui.theme.SpikeStreamPrimaryButton
import com.leonardos.spikestream.ui.theme.SpikeStreamDangerButton
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.rtplibrary.rtmp.RtmpCamera2
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.view.OpenGlView
import android.media.EncoderProfiles.VideoProfile
import android.media.MediaRecorder.VideoEncoder
import androidx.compose.ui.text.style.TextAlign
import com.leonardos.spikestream.ui.components.DefaultOverlayStyle
import com.leonardos.spikestream.data.GetGameResult
import com.leonardos.spikestream.R
import com.leonardos.spikestream.data.StreamApi.makeGetGameRequest
import com.leonardos.spikestream.utils.getHttpClient
import com.leonardos.spikestream.streaming.StreamBitrateMonitor
import com.leonardos.spikestream.streaming.StreamBrightnessManager
import com.leonardos.spikestream.streaming.StreamNetworkManager
import com.leonardos.spikestream.streaming.StreamOverlayController
import com.leonardos.spikestream.streaming.StreamSocketManager

/**
 * The main live streaming activity. Integrates the camera feed, GL score overlays,
 * and background network/bitrate monitors.
 */
class StreamActivity : ComponentActivity(), ConnectCheckerRtmp {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent display from turning off during live broadcast
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

        // Global crash handler logging
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CRASH_FATAL", "Crash in thread ${thread.name}", throwable)
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
            networkManager = networkManager,
            isStreamingProvider = { ::rtmpCamera.isInitialized && rtmpCamera.isStreaming },
            isPortraitProvider = { resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT },
            onSoftRestart = { width, height ->
                if (::rtmpCamera.isInitialized && rtmpCamera.isStreaming) {
                    val isPortrait =
                        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                    rtmpCamera.stopStream()
                    networkManager.prepareVideoCompat(
                        camera   = rtmpCamera,
                        width    = width,
                        height   = height,
                        bitrate  = 900 * 1024,
                        rotation = if (isPortrait) 90 else 0
                    )
                    rtmpCamera.prepareAudio(128 * 1024, 48000, true)
                    rtmpCamera.startStream(rtmpUrl)
                    Log.i("Stream", "🔻 Soft restart a ${width}x${height} per rete lenta")
                }
            },
            onBitrateAdjust = { bitrate, type ->
                if (::rtmpCamera.isInitialized && rtmpCamera.isStreaming) {
                    rtmpCamera.setVideoBitrateOnFly(bitrate)
                    Log.i("Stream", "📶 Bitrate adattato a ${bitrate / 1024} kbps su $type")
                }
            }
        )

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

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER

        var networkType by remember { mutableStateOf("UNKNOWN") }
        var servingTeam by remember { mutableIntStateOf(0) }
        var overlayStyle by remember { mutableStateOf("classic") }

        // Camera and Microphone Permissions Launcher
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.values.all { it }) {
                if (::rtmpCamera.isInitialized) rtmpCamera.startPreview(cameraId)
            } else {
                Toast.makeText(ctx, ctx.getString(R.string.permissions), Toast.LENGTH_SHORT).show()
            }
        }

        LaunchedEffect(Unit) {
            if (!hasPermissions(ctx)) {
                val permissions = arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )

                val shouldShowRationale = permissions.any {
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
                }

                if (shouldShowRationale) {
                    launcher.launch(permissions)
                } else {
                    Toast.makeText(ctx, ctx.getString(R.string.permissions), Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", ctx.packageName, null)
                    }
                    ctx.startActivity(intent)
                }
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

        // Restores full state when changing screen orientation to prevent issues
        LaunchedEffect(isPortrait) {
            if (isStreamingState) {
                bitrateMonitor.stop()
                if (::rtmpCamera.isInitialized) {
                    if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
                    if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
                }
                isStreamingState = false
                brightnessManager.restore()
                Toast.makeText(ctx, ctx.getString(R.string.orientation_changed_error), Toast.LENGTH_SHORT).show()
            }
        }

        // Listens to network type change dynamically for warnings
        DisposableEffect(Unit) {
            val cm = ctx.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    networkType = when {
                        caps == null -> "UNKNOWN"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                        else -> "OTHER"
                    }
                }

                override fun onLost(network: Network) {
                    networkType = "DISCONNECTED"
                }
            }

            cm.registerDefaultNetworkCallback(networkCallback)
            onDispose {
                cm.unregisterNetworkCallback(networkCallback)
            }
        }

        // Layout composition
        Box(Modifier.fillMaxSize()) {
            key(isPortrait) {
                AndroidView(
                    factory = { ctx ->
                        openGlView = OpenGlView(ctx)
                        rtmpCamera = RtmpCamera2(openGlView, this@StreamActivity)

                        openGlView.post {
                            val (resBase, bitrateSelected, _) = networkManager.estimateNetworkQuality()
                            val (width, height) = networkManager.getDimsForOrientation(isPortrait, resBase)

                            rtmpCamera.prepareVideo(width, height, 25, bitrateSelected, 1, if (!isPortrait) 0 else 90)
                            rtmpCamera.prepareAudio(128 * 1024, 48000, true)

                            /*val bitmapSfondo = BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher_background)
                            val imageFilter = ImageObjectFilterRender()
                            rtmpCamera.glInterface.addFilter(imageFilter)
                            imageFilter.setImage(bitmapSfondo)
                            imageFilter.setPosition(0f, 0f)
                            imageFilter.setScale(100f, 100f)*/

                            overlayController.applyOverlay(
                                rtmpCamera = rtmpCamera,
                                width = width,
                                height = height,
                                overlayPosition = translatePosition,
                                team1Pts = currentTeam1Pts,
                                team2Pts = currentTeam2Pts,
                                team1Sets = currentTeam1Sets,
                                team2Sets = currentTeam2Sets
                            )
                            rtmpCamera.startPreview(cameraId)
                        }

                        openGlView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Sync scoreboard rendering when scores or attributes change
            LaunchedEffect(currentTeam1Pts, currentTeam2Pts, currentTeam1Sets, currentTeam2Sets, servingTeam, overlayStyle, isPortrait) {
                val (resBase, _, _) = networkManager.estimateNetworkQuality()
                val (width, height) = networkManager.getDimsForOrientation(isPortrait, resBase)
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
                    scope = this          // <-- unica modifica qui
                )
            }

            if (isStreamingState) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.streaming_launched),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.streaming_warning),
                            color = Color.Yellow,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (networkType == "MOBILE") {
                Text(
                    stringResource(R.string.wifi_warning),
                    color = Color.Yellow,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                )
            }

            if (isStreamingState) {
                SpikeStreamDangerButton(
                    text = stringResource(R.string.stop_stream),
                    onClick = {
                        bitrateMonitor.stop()
                        rtmpCamera.stopStream()
                        overlayController.removeOverlay(rtmpCamera)

                        val (resBase, _, _) = networkManager.estimateNetworkQuality()
                        val (width, height) = networkManager.getDimsForOrientation(isPortrait, resBase)

                        overlayController.applyOverlay(
                            rtmpCamera = rtmpCamera,
                            width = width,
                            height = height,
                            overlayPosition = translatePosition,
                            team1Pts = currentTeam1Pts,
                            team2Pts = currentTeam2Pts,
                            team1Sets = currentTeam1Sets,
                            team2Sets = currentTeam2Sets
                        )

                        isStreamingState = false
                        brightnessManager.restore()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .widthIn(min = 200.dp)
                )
            } else {
                SpikeStreamPrimaryButton(
                    text = stringResource(R.string.launch_stream),
                    onClick = {
                        overlayController.removeOverlay(rtmpCamera)
                        val (resBase, bitrateSelected, _) = networkManager.estimateNetworkQuality()
                        val (width, height) = networkManager.getDimsForOrientation(isPortrait, resBase)

                        val preparedVideo = networkManager.prepareVideoCompat(
                            camera   = rtmpCamera,
                            width    = width,
                            height   = height,
                            bitrate  = bitrateSelected,
                            rotation = if (!isPortrait) 0 else 90
                        )

                        if (preparedVideo &&
                            rtmpCamera.prepareAudio(128 * 1024, 48000, true)
                        ) {
                            overlayController.applyOverlay(
                                rtmpCamera = rtmpCamera,
                                width = 1280,
                                height = 720,
                                overlayPosition = translatePosition,
                                team1Pts = currentTeam1Pts,
                                team2Pts = currentTeam2Pts,
                                team1Sets = currentTeam1Sets,
                                team2Sets = currentTeam2Sets
                            )
                            rtmpCamera.startStream(streamUrl)
                            isStreamingState = true

                            // Start reactive bitrate monitor
                            bitrateMonitor.start(
                                initialWidth = width,
                                initialHeight = height,
                                initialBitrate = bitrateSelected
                            )
                            brightnessManager.setDimmed(true)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .widthIn(min = 200.dp)
                )
            }
        }
    }

    private fun hasPermissions(ctx: Context) = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    override fun onStop() {
        super.onStop()
        bitrateMonitor.stop()
        socketManager.disconnect()
        if (::rtmpCamera.isInitialized) {
            if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
            if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
        }
        isStreamingState = false
        brightnessManager.restore()
    }

    // 🔌 RTMP callbacks implementation
    override fun onConnectionStartedRtmp(rtmpUrl: String) {
        Log.i("Stream", "RTMP Connection initiated")
    }

    override fun onConnectionSuccessRtmp() {
        Log.i("Stream", "✅ RTMP Connection successfully established")
        runOnUiThread {
            Toast.makeText(this, getString(R.string.stream_connected), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionFailedRtmp(reason: String) {
        Log.e("Stream", "❌ RTMP Connection failed: $reason")
        bitrateMonitor.stop()
        runOnUiThread {
            Toast.makeText(this, getString(R.string.connection_failed) + ": $reason", Toast.LENGTH_LONG).show()
            if (::rtmpCamera.isInitialized) {
                rtmpCamera.stopStream()
                overlayController.removeOverlay(rtmpCamera)

                // Restore preview scoreboard overlay correctly
                val (resBase, _, _) = networkManager.estimateNetworkQuality()
                val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                val (width, height) = networkManager.getDimsForOrientation(isPortrait, resBase)

                overlayController.applyOverlay(
                    rtmpCamera = rtmpCamera,
                    width = width,
                    height = height,
                    overlayPosition = translatePosition,
                    team1Pts = currentTeam1Pts,
                    team2Pts = currentTeam2Pts,
                    team1Sets = currentTeam1Sets,
                    team2Sets = currentTeam2Sets
                )
            }
            // Fix bridged UI state: reset button and display overlays on fail
            isStreamingState = false
            brightnessManager.restore()
        }
    }

    override fun onNewBitrateRtmp(bitrate: Long) {}

    override fun onDisconnectRtmp() {
        Log.w("Stream", "RTMP Disconnected")
        runOnUiThread {
            Toast.makeText(this, getString(R.string.stream_disconnected), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAuthErrorRtmp() {
        Log.e("Stream", "RTMP Authentication error")
    }

    override fun onAuthSuccessRtmp() {
        Log.i("Stream", "RTMP Authentication success")
    }
}
