package com.leonardos.spikestream

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.EncoderProfiles.VideoProfile
import android.media.MediaRecorder.VideoEncoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.rtplibrary.rtmp.RtmpCamera2
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.view.OpenGlView
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONObject

class StreamActivity : ComponentActivity(), ConnectCheckerRtmp {

    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private var imageFilter: ImageObjectFilterRender? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var oldBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private var bitrateMonitorJob: Job? = null
    private var isStreaming = false

    /** Parametri soft restart */
    private val MIN_BITRATE = 400 * 1024 // 400 kbps soglia minima
    private var currentWidth = 480
    private var currentHeight = 480
    private var currentBitrate = 900 * 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpikeStream::StreamLock")

        val rtmpUrl = intent.getStringExtra("RTMP_URL")
            ?: "rtmps://live-api-s.facebook.com:443/rtmp/YOUR_STREAM_KEY"

        val team1 = intent.getStringExtra("TEAM_1") ?: "Team A"
        val team2 = intent.getStringExtra("TEAM_2") ?: "Team B"
        val team1Pts = intent.getIntExtra("TEAM1_PTS", 0)
        val team2Pts = intent.getIntExtra("TEAM2_PTS", 0)
        val team1Sets = intent.getIntExtra("TEAM1_SETS", 0)
        val team2Sets = intent.getIntExtra("TEAM2_SETS", 0)
        val id_match = intent.getStringExtra("MATCH_ID") ?: ""

        setContent {
            StreamingScreen(team1, team2, team1Pts, team2Pts, team1Sets, team2Sets, rtmpUrl, id_match)
        }
    }

    /** 🔆 Luminosità */
    private fun setScreenBrightness(value: Float) {
        val lp = window.attributes
        if (oldBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            oldBrightness = lp.screenBrightness
        }
        lp.screenBrightness = value
        window.attributes = lp
    }

    private fun restoreBrightness() {
        if (oldBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            val lp = window.attributes
            lp.screenBrightness = oldBrightness
            window.attributes = lp
        }
    }

    /** 📶 Stima rete */
    private fun estimateNetworkQuality(): Triple<Int, Int, String> {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return when {
            caps == null -> Triple(480, 600 * 1024, "UNKNOWN")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Triple(1280, 2_000 * 1024, "WIFI")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Triple(854, 900 * 1024, "MOBILE")
            else -> Triple(480, 700 * 1024, "OTHER")
        }
    }

    /** 🔁 Monitor bitrate e soft restart */
    private fun startBitrateMonitor(streamUrl: String, team1: String, team2: String, team1Pts: Int, team2Pts: Int, team1Sets: Int, team2Sets: Int) {
        bitrateMonitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(1500) // ogni 1,5 secondi

                val (res, bitrate, type) = estimateNetworkQuality()
                val width = if (res == 1280) 1280 else 854
                val height = if (res == 1280) 720 else 480

                if (!rtmpCamera.isStreaming) continue

                runOnUiThread {
                    if (bitrate < MIN_BITRATE) {
                        // Soft restart a risoluzione più bassa
                        if (currentWidth != 854 || currentHeight != 480) {
                            rtmpCamera.stopStream()
                            rtmpCamera.prepareVideo(
                                854, 480, 25, 900 * 1024, 2, 0, VideoEncoder.H264, VideoProfile.HDR_NONE
                            )
                            rtmpCamera.prepareAudio(128 * 1024, 48000, true)
                            rtmpCamera.startStream(streamUrl)
                            currentWidth = 854
                            currentHeight = 480
                            currentBitrate = 900 * 1024
                            Log.i("Stream", "🔻 Soft restart a 480p per rete lenta")
                        }
                    } else {
                        // Aggiorna solo bitrate
                        if (currentBitrate != bitrate) {
                            rtmpCamera.setVideoBitrateOnFly(bitrate)
                            currentBitrate = bitrate
                            Log.i("Stream", "📶 Bitrate adattato a ${bitrate / 1024} kbps su $type")
                        }
                    }
                }
            }
        }
    }

    private fun stopBitrateMonitor() {
        bitrateMonitorJob?.cancel()
    }

    /** 🧱 UI */
    @Composable
    private fun StreamingScreen(
        team1Init: String, team2Init: String,
        team1PtsInit: Int, team2PtsInit: Int,
        team1SetsInit: Int, team2SetsInit: Int,
        streamUrl: String,
        matchId: String
    ) {
        val ctx = LocalContext.current
        val activity = ctx as Activity
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        var team1 by remember { mutableStateOf(team1Init) }
        var team2 by remember { mutableStateOf(team2Init) }
        var team1Pts by remember { mutableStateOf(team1PtsInit) }
        var team2Pts by remember { mutableStateOf(team2PtsInit) }
        var team1Sets by remember { mutableStateOf(team1SetsInit) }
        var team2Sets by remember { mutableStateOf(team2SetsInit) }

        var isStreamingState by remember { mutableStateOf(false) }
        var networkType by remember { mutableStateOf("UNKNOWN") }

        // Permessi
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.values.all { it }) if (::rtmpCamera.isInitialized) rtmpCamera.startPreview()
            else Toast.makeText(ctx, ctx.getString(R.string.permissions), Toast.LENGTH_SHORT).show()
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

                if (shouldShowRationale) launcher.launch(permissions)
                else {
                    Toast.makeText(ctx, ctx.getString(R.string.permissions), Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", ctx.packageName, null)
                    }
                    ctx.startActivity(intent)
                }
            }
        }

        // Socket setup
        val client = getUnsafeOkHttpClient()
        val opts = IO.Options().apply {
            transports = arrayOf("websocket")
            callFactory = client
            webSocketFactory = client
            reconnection = true
            reconnectionAttempts = 5
            reconnectionDelay = 1000
            timeout = 20000
        }
        val socket = remember { IO.socket("https://spikestream.tooolky.com", opts) }

        LaunchedEffect(Unit) {
            socket.on("score_update") { args ->
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val data = args[0] as JSONObject
                    if (data.get("matchId") == matchId) {
                        val teamASets = data.getJSONArray("teamASets")
                        val teamBSets = data.getJSONArray("teamBSets")

                        val aPts = teamASets.getInt(teamASets.length() - 1)
                        val bPts = teamBSets.getInt(teamBSets.length() - 1)

                        val aSets = (0 until minOf(teamASets.length(), teamBSets.length()) - 1).count { i ->
                            teamASets.getInt(i) > teamBSets.getInt(i)
                        }
                        val bSets = (0 until minOf(teamASets.length(), teamBSets.length()) - 1).count { i ->
                            teamASets.getInt(i) < teamBSets.getInt(i)
                        }

                        Handler(Looper.getMainLooper()).post {
                            team1Pts = aPts
                            team2Pts = bPts
                            team1Sets = aSets
                            team2Sets = bSets

                            Toast.makeText(ctx, "Score updated ${team1Pts} - ${team2Pts}", Toast.LENGTH_SHORT).show()

                            // 🔄 Aggiorna overlay
                            val bitmap = drawScoreBitmap(currentWidth, 400, team1, team2, team1Pts, team2Pts, team1Sets, team2Sets)
                            imageFilter?.setImage(bitmap)
                        }
                    }
                }
            }

            socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val err = args.getOrNull(0)?.toString() ?: "Unknown error"
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, "Connection error: $err", Toast.LENGTH_SHORT).show()
                }
            }

            socket.on(Socket.EVENT_CONNECT) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, "Connecting...", Toast.LENGTH_SHORT).show()
                }
                socket.emit("join_match", JSONObject().put("matchId", matchId))
            }

            try { socket.connect() } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, "Connection: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Controllo rete
        LaunchedEffect(Unit) {
            while (true) {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                networkType = when {
                    caps == null -> "UNKNOWN"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                    else -> "OTHER"
                }
                delay(2000)
            }
        }

        // UI
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    openGlView = OpenGlView(ctx)
                    rtmpCamera = RtmpCamera2(openGlView, this@StreamActivity)

                    openGlView.post {
                        val width = openGlView.width.takeIf { it > 0 } ?: 1280
                        val height = openGlView.height.takeIf { it > 0 } ?: 720

                        imageFilter = ImageObjectFilterRender().apply {
                            setImage(
                                drawScoreBitmap(
                                    width, height / 2,
                                    team1Init, team2Init,
                                    team1Pts, team2Pts,
                                    team1Sets, team2Sets
                                )
                            )
                            setDefaultScale(width, height)
                            setPosition(TranslateTo.BOTTOM_LEFT)
                        }
                        rtmpCamera.glInterface.addFilter(imageFilter)
                        rtmpCamera.startPreview()
                    }
                    openGlView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Overlay dinamico
            LaunchedEffect(team1Pts, team2Pts, team1Sets, team2Sets) {
                imageFilter?.let { filter ->
                    // Usa sempre le dimensioni correnti calcolate al momento dello start
                    filter.setImage(
                        drawScoreBitmap(
                            currentWidth,
                            currentHeight,
                            team1Init,
                            team2Init,
                            team1Pts,
                            team2Pts,
                            team1Sets,
                            team2Sets
                        )
                    )
                    filter.setDefaultScale(currentWidth, currentHeight)
                    filter.setPosition(TranslateTo.BOTTOM_LEFT)
                    // Non fare rtmCamera.glInterface.addFilter(filter) di nuovo!
                }
            }




            if (networkType == "MOBILE") {
                Text(
                    "⚠️ È consigliato connettersi al Wi-Fi per una diretta più stabile",
                    color = Color.Yellow,
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                )
            }

            Button(
                onClick = {
                    if (!isStreamingState) {
                        val (res, bitrate, _) = estimateNetworkQuality()
                        currentWidth = if (res == 1280) 1280 else 854
                        currentHeight = if (res == 1280) 720 else 480
                        currentBitrate = bitrate

                        if (rtmpCamera.prepareVideo(currentWidth, currentHeight, 25, currentBitrate, 2, 0, VideoEncoder.H264, VideoProfile.HDR_NONE) &&
                            rtmpCamera.prepareAudio(128 * 1024, 48000, true)
                        ) {
                            imageFilter = ImageObjectFilterRender().apply {
                                setImage(drawScoreBitmap(currentWidth, currentHeight, team1, team2, team1Pts, team2Pts, team1Sets, team2Sets))
                                setDefaultScale(currentWidth, currentHeight)
                                setPosition(TranslateTo.BOTTOM_LEFT)
                            }
                            rtmpCamera.glInterface.addFilter(imageFilter)
                            rtmpCamera.startStream(streamUrl)
                            isStreamingState = true
                            startBitrateMonitor(streamUrl, team1, team2, team1Pts, team2Pts, team1Sets, team2Sets)
                            wakeLock?.acquire()
                            setScreenBrightness(0.03f)
                        }
                    } else {
                        stopBitrateMonitor()
                        rtmpCamera.stopStream()
                        isStreamingState = false
                        if (wakeLock?.isHeld == true) wakeLock?.release()
                        restoreBrightness()
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreamingState) Color.Red else Color(0xFF00BFFF),
                    contentColor = Color.White
                )
            ) {
                Text(if (isStreamingState) stringResource(R.string.stop_stream) else stringResource(R.string.launch_stream))
            }
        }
    }


    private fun hasPermissions(ctx: Context) = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }


    /** 🧹 Lifecycle */
    override fun onStop() {
        super.onStop()
        stopBitrateMonitor()
        if (::rtmpCamera.isInitialized) {
            if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
            if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
        }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        restoreBrightness()
    }

    /** 🔌 RTMP callbacks */
    override fun onConnectionStartedRtmp(rtmpUrl: String) { Log.i("Stream", "Connessione iniziata: $rtmpUrl") }
    override fun onConnectionSuccessRtmp() { Log.i("Stream", "✅ Connessione RTMP stabilita") }
    override fun onConnectionFailedRtmp(reason: String) {
        Log.e("Stream", "❌ Connessione fallita: $reason")
        runOnUiThread {
            stopBitrateMonitor()
            rtmpCamera.stopStream()
            isStreaming = false
            if (wakeLock?.isHeld == true) wakeLock?.release()
            restoreBrightness()
        }
    }
    override fun onNewBitrateRtmp(bitrate: Long) {}
    override fun onDisconnectRtmp() { Log.w("Stream", "Disconnesso") }
    override fun onAuthErrorRtmp() { Log.e("Stream", "Errore autenticazione") }
    override fun onAuthSuccessRtmp() { Log.i("Stream", "Autenticazione ok") }
}
