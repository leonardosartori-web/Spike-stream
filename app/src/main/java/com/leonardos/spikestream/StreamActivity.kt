package com.leonardos.spikestream

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.EncoderProfiles.VideoProfile
import android.media.MediaRecorder.VideoEncoder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject

class StreamActivity : ComponentActivity(), ConnectCheckerRtmp {

    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private var imageFilter: ImageObjectFilterRender? = null
    private var oldBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private var isStreaming = false

    /** Parametri soft restart */
    private val MIN_BITRATE = 400 * 1024 // 400 kbps soglia minima
    private var currentWidth = 854
    private var currentHeight = 480
    private var currentBitrate = 900 * 1024

    private lateinit var tokenManager: TokenManager

    private lateinit var scoreRenderer: ScoreOverlayRenderer


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val rtmpUrl = intent.getStringExtra("RTMP_URL")
            ?: "rtmps://live-api-s.facebook.com:443/rtmp/YOUR_STREAM_KEY"

        val team1 = intent.getStringExtra("TEAM_1") ?: "Team A"
        val team2 = intent.getStringExtra("TEAM_2") ?: "Team B"
        val team1Pts = intent.getIntExtra("TEAM1_PTS", 0)
        val team2Pts = intent.getIntExtra("TEAM2_PTS", 0)
        val team1Sets = intent.getIntExtra("TEAM1_SETS", 0)
        val team2Sets = intent.getIntExtra("TEAM2_SETS", 0)
        val id_match = intent.getStringExtra("MATCH_ID") ?: ""

        tokenManager = TokenManager(applicationContext)

        scoreRenderer = ScoreOverlayRenderer(team1, team2)

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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun startBitrateMonitorReactive(streamUrl: String) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        var lastBitrate = currentBitrate
        var lastWidth = currentWidth
        var lastHeight = currentHeight

        var lastAdjustTime = 0L

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                adjustBitrate(network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                adjustBitrate(network)
            }

            override fun onLost(network: Network) {
                // opzionale: gestione perdita rete
            }

            private fun adjustBitrate(network: Network) {
                if (!rtmpCamera.isStreaming) return

                val now = System.currentTimeMillis()
                if (now - lastAdjustTime < 5000) return

                val caps = cm.getNetworkCapabilities(network)
                val (res, bitrate, type) = when {
                    caps == null -> Triple(480, 600 * 1024, "UNKNOWN")
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Triple(1280, 2_000 * 1024, "WIFI")
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Triple(854, 900 * 1024, "MOBILE")
                    else -> Triple(480, 700 * 1024, "OTHER")
                }

                val width = if (res == 1280) 1280 else 854
                val height = if (res == 1280) 720 else 480

                runOnUiThread {
                    if (bitrate < MIN_BITRATE && (lastWidth != 854 || lastHeight != 480)) {
                        // Soft restart a risoluzione più bassa
                        rtmpCamera.stopStream()
                        rtmpCamera.prepareVideo(
                            854, 480, 25, 900 * 1024, 2, 0, VideoEncoder.H264, VideoProfile.HDR_NONE
                        )
                        rtmpCamera.prepareAudio(128 * 1024, 48000, true)
                        rtmpCamera.startStream(streamUrl)

                        currentWidth = 854
                        currentHeight = 480
                        currentBitrate = 900 * 1024

                        lastWidth = 854
                        lastHeight = 480
                        lastBitrate = currentBitrate

                        Log.i("Stream", "🔻 Soft restart a 480p per rete lenta")
                    } else if (bitrate >= MIN_BITRATE && bitrate != lastBitrate) {
                        // Aggiorna solo bitrate
                        rtmpCamera.setVideoBitrateOnFly(bitrate)
                        currentBitrate = bitrate
                        lastBitrate = bitrate
                        Log.i("Stream", "📶 Bitrate adattato a ${bitrate / 1024} kbps su $type")
                    }
                }
            }
        }

        cm.registerDefaultNetworkCallback(networkCallback!!)
    }

    private fun stopBitrateMonitorReactive() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let { cm.unregisterNetworkCallback(it) }
    }


    private var lastScoreHash: Int = 0
    private var lastScoreBitmap: Bitmap? = null

    private fun updateOverlayIfChanged(
        width: Int,
        height: Int,
        team1Score: Int,
        team2Score: Int,
        team1Sets: Int,
        team2Sets: Int
    ) {
        val hash = listOf(team1Score, team2Score, team1Sets, team2Sets).hashCode()

        if (hash != lastScoreHash) {
            lastScoreHash = hash

            // Bitmap più piccola per ridurre lavoro GPU
            val bmp = scoreRenderer.render(width, height, team1Score, team2Score, team1Sets, team2Sets)

            lastScoreBitmap?.recycle()
            lastScoreBitmap = bmp

            // Aggiorna solo l’immagine del filtro, non ricreare filtro
            imageFilter?.setImage(bmp)
        }
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

        DisposableEffect(Unit) {
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

                            //Toast.makeText(ctx, "Score updated ${team1Pts} - ${team2Pts}", Toast.LENGTH_SHORT).show()

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

            onDispose {
                if (socket.connected()) {
                    socket.disconnect()
                    socket.off()
                }
            }
        }

        val tokenState = remember { mutableStateOf<String?>(null) }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            tokenManager.tokenFlow.collect { token ->
                tokenState.value = token
            }
        }

        // 🔋 Monitoraggio livello batteria e invio via socket con JWT
        LaunchedEffect(tokenState.value) {
            val token = tokenState.value ?: return@LaunchedEffect
            var hasNotified = false

            val batteryFlow = callbackFlow<Int> {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        val batteryPct = level * 100 / scale
                        trySend(batteryPct)
                    }
                }
                ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                awaitClose { ctx.unregisterReceiver(receiver) }
            }

            batteryFlow.collect { batteryPct ->
                when {
                    batteryPct <= 30 && !hasNotified -> {
                        val msg = JSONObject().apply {
                            put("matchId", matchId)
                            put("battery", batteryPct)
                            put("token", token)
                        }
                        socket.emit("low_battery", msg)
                        //Log.i("BatteryMonitor", "⚠️ Low battery sent: $batteryPct% with token")
                        hasNotified = true
                    }
                    batteryPct > 30 && hasNotified -> {
                        hasNotified = false
                        //Log.i("BatteryMonitor", "🔋 Battery level restored: $batteryPct%")
                    }
                }
            }
        }

        // Controllo rete
        DisposableEffect(Unit) {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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

            // ✅ Registra il callback
            cm.registerDefaultNetworkCallback(networkCallback)

            // ✅ Cleanup automatico quando il composable viene distrutto
            onDispose {
                cm.unregisterNetworkCallback(networkCallback)
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
                            setImage(scoreRenderer.render(currentWidth, currentHeight, team1Pts, team2Pts, team1Sets, team2Sets))
                            setDefaultScale(currentWidth, currentHeight)
                            setPosition(TranslateTo.BOTTOM_LEFT)
                        }
                        rtmpCamera.glInterface.addFilter(imageFilter)
                        rtmpCamera.startPreview()
                    }
                    openGlView
                },
                modifier = if (isStreamingState) Modifier.size(1.dp) else Modifier.fillMaxSize()
            )

            // Overlay dinamico
            LaunchedEffect(team1Pts, team2Pts, team1Sets, team2Sets) {
                updateOverlayIfChanged(
                    currentWidth,
                    currentHeight,
                    team1Pts,
                    team2Pts,
                    team1Sets,
                    team2Sets
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
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp)) // piccolo spazio tra le righe
                        Text(
                            text = stringResource(R.string.streaming_warning),
                            color = Color.Yellow,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            if (networkType == "MOBILE") {
                Text(
                    stringResource(R.string.wifi_warning),
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
                                setImage(scoreRenderer.render(currentWidth, currentHeight, team1Pts, team2Pts, team1Sets, team2Sets))
                                setDefaultScale(currentWidth, currentHeight)
                                setPosition(TranslateTo.BOTTOM_LEFT)
                            }
                            rtmpCamera.glInterface.addFilter(imageFilter)
                            rtmpCamera.startStream(streamUrl)
                            isStreamingState = true
                            startBitrateMonitorReactive(streamUrl)
                            setScreenBrightness(0.02f)
                        }
                    } else {
                        stopBitrateMonitorReactive()
                        rtmpCamera.stopStream()
                        isStreamingState = false
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
        stopBitrateMonitorReactive()
        if (::rtmpCamera.isInitialized) {
            if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
            if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
        }
        restoreBrightness()
    }

    /** 🔌 RTMP callbacks */
    override fun onConnectionStartedRtmp(rtmpUrl: String) { Log.i("Stream", "Connessione iniziata: $rtmpUrl") }
    override fun onConnectionSuccessRtmp() { Log.i("Stream", "✅ Connessione RTMP stabilita") }
    override fun onConnectionFailedRtmp(reason: String) {
        Log.e("Stream", "❌ Connessione fallita: $reason")
        stopBitrateMonitorReactive()
        runOnUiThread {
            rtmpCamera.stopStream()
            isStreaming = false
            restoreBrightness()
        }
    }
    override fun onNewBitrateRtmp(bitrate: Long) {}
    override fun onDisconnectRtmp() { Log.w("Stream", "Disconnesso") }
    override fun onAuthErrorRtmp() { Log.e("Stream", "Errore autenticazione") }
    override fun onAuthSuccessRtmp() { Log.i("Stream", "Autenticazione ok") }
}
