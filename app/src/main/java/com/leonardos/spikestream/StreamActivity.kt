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
import android.content.res.Configuration
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
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.leonardos.spikestream.ui.theme.MyApplicationTheme
import com.leonardos.spikestream.ui.theme.AppGradient
import com.leonardos.spikestream.ui.theme.SpikeStreamPrimaryButton
import com.leonardos.spikestream.ui.theme.SpikeStreamDangerButton
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.rtplibrary.rtmp.RtmpCamera2
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.view.OpenGlView
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray

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
        val overlayPositionString = intent.getStringExtra("OVERLAY_POSITION") ?: "BOTTOM"
        
        val translatePosition = when (overlayPositionString) {
            "BOTTOM_LEFT" -> TranslateTo.BOTTOM_LEFT
            "BOTTOM_RIGHT" -> TranslateTo.BOTTOM_RIGHT
            "TOP_LEFT" -> TranslateTo.TOP_LEFT
            "TOP_RIGHT" -> TranslateTo.TOP_RIGHT
            else -> TranslateTo.BOTTOM
        }

        tokenManager = TokenManager(applicationContext)

        scoreRenderer = ScoreOverlayRenderer(team1, team2, applicationContext)

        setContent {
            MyApplicationTheme() {
                StreamingScreen(
                    team1,
                    team2,
                    team1Pts,
                    team2Pts,
                    team1Sets,
                    team2Sets,
                    rtmpUrl,
                    id_match,
                    translatePosition
                )
            }
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
    private fun getNetworkQualityFromCaps(caps: NetworkCapabilities?): Triple<Int, Int, String> {
        return when {
            caps == null -> Triple(480, 600 * 1024, "UNKNOWN")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Triple(1280, 2_000 * 1024, "WIFI")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Triple(854, 900 * 1024, "MOBILE")
            else -> Triple(480, 700 * 1024, "OTHER")
        }
    }

    private fun estimateNetworkQuality(): Triple<Int, Int, String> {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return getNetworkQualityFromCaps(cm.getNetworkCapabilities(cm.activeNetwork))
    }

    private fun getDimsForOrientation(isPortrait: Boolean, resBase: Int): Pair<Int, Int> {
        val longDim = if (resBase >= 1280) 1280 else 854
        val shortDim = if (resBase >= 1280) 720 else 480
        return if (isPortrait) shortDim to longDim else longDim to shortDim
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
                if (now - lastAdjustTime < 8000) return

                val caps = cm.getNetworkCapabilities(network)
                val (res, bitrate, type) = getNetworkQualityFromCaps(caps)

                val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                val (width, height) = getDimsForOrientation(isPortrait, res)

                runOnUiThread {
                    if (bitrate < MIN_BITRATE && (lastWidth != width || lastHeight != height)) {
                        lastAdjustTime = System.currentTimeMillis()
                        // Soft restart a risoluzione più bassa
                        rtmpCamera.stopStream()
                        rtmpCamera.prepareVideo(
                            width, height, 25, 900 * 1024, 2, if (isPortrait) 90 else 0, VideoEncoder.H264, VideoProfile.HDR_NONE
                        )
                        rtmpCamera.prepareAudio(128 * 1024, 48000, true)
                        rtmpCamera.startStream(streamUrl)

                        currentWidth = width
                        currentHeight = height
                        currentBitrate = 900 * 1024

                        lastWidth = width
                        lastHeight = height

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
        team2Sets: Int,
        servingTeam: Int,
        style: String,
        position: TranslateTo
    ) {
        var hash = 17
        hash = hash * 31 + team1Score
        hash = hash * 31 + team2Score
        hash = hash * 31 + team1Sets
        hash = hash * 31 + team2Sets
        hash = hash * 31 + servingTeam
        hash = hash * 31 + style.hashCode()
        hash = hash * 31 + width
        hash = hash * 31 + height

        if (hash != lastScoreHash) {
            lastScoreHash = hash

            val bmp = scoreRenderer.render(width, height, team1Score, team2Score, team1Sets, team2Sets)

            imageFilter?.setPosition(position)

            lastScoreBitmap?.recycle()
            lastScoreBitmap = bmp

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
        matchId: String,
        overlayPosition: TranslateTo
    ) {
        val ctx = LocalContext.current
        val activity = ctx as Activity
        val scope = rememberCoroutineScope()
        val configuration = LocalConfiguration.current
        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER

        var team1 by remember { mutableStateOf(team1Init) }
        var team2 by remember { mutableStateOf(team2Init) }
        var team1Pts by remember { mutableStateOf(team1PtsInit) }
        var team2Pts by remember { mutableStateOf(team2PtsInit) }
        var team1Sets by remember { mutableStateOf(team1SetsInit) }
        var team2Sets by remember { mutableStateOf(team2SetsInit) }

        var isStreamingState by remember { mutableStateOf(false) }
        var networkType by remember { mutableStateOf("UNKNOWN") }
        var servingTeam by remember { mutableIntStateOf(0) }
        var overlayStyle by remember { mutableStateOf("classic") }

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

        // Socket setup — JWT is passed via handshake auth (not inside event payloads)
        // The token comes from the Activity's tokenManager which is already in scope;
        // we read it once here using the token passed through the Intent pipeline.
        // NOTE: we use the token already collected from the parent LaunchedEffect below.
        val client = getHttpClient()
        // tokenForSocket is collected asynchronously below and stored in state
        val tokenForSocket = remember { mutableStateOf<String?>(null) }

        // ✅ Collezione del token
        LaunchedEffect(Unit) {
            tokenManager.tokenFlow.collect { t ->
                tokenForSocket.value = t
            }
        }

        // ✅ Fetch iniziale dei dati del match (score/sets)
        LaunchedEffect(tokenForSocket.value) {
            val t = tokenForSocket.value
            if (t != null) {
                when (val result = makeGetGameRequest(t, matchId)) {
                    is GetGameResult.Success -> {
                        team1Pts = result.team1Pts
                        team2Pts = result.team2Pts
                        team1Sets = result.team1Sets
                        team2Sets = result.team2Sets
                    }
                    is GetGameResult.Error -> {
                        Log.e("Stream", "Failed to load initial game state: ${result.message}")
                    }
                }
            }
        }

        // Build socket with auth once we have the token
        val socket = remember(tokenForSocket.value) {
            val authOpts = IO.Options().apply {
                transports = arrayOf("websocket")
                callFactory = client
                webSocketFactory = client
                reconnection = true
                reconnectionAttempts = 5
                reconnectionDelay = 1000
                timeout = 20000
                val t = tokenForSocket.value
                if (t != null) auth = mapOf("token" to t)
            }
            IO.socket(Constants.BASE_URL, authOpts)
        }

        DisposableEffect(socket) {
            // Only connect if the token is present (we rely on socket recreation if the token arrives later)
            if (tokenForSocket.value != null) {
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

                        team1Pts = aPts
                        team2Pts = bPts
                        team1Sets = aSets
                        team2Sets = bSets
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

            try {
                socket.connect()
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, "Connection: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        onDispose {
            if (socket.connected()) {
                socket.disconnect()
                socket.off()
            }
        }
    }

        // Handle orientation change during stream
        LaunchedEffect(isPortrait) {
            if (isStreamingState) {
                stopBitrateMonitorReactive()
                if (::rtmpCamera.isInitialized && rtmpCamera.isStreaming) {
                    rtmpCamera.stopStream()
                }
                isStreamingState = false
                restoreBrightness()
                Toast.makeText(ctx, "Orientamento cambiato: ricrea RTMP...", Toast.LENGTH_SHORT).show()
            }
        }

        // 🔋 Low battery notification — token is already in socket handshake, not repeated here
        LaunchedEffect(Unit) {
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
                        // Token is authenticated at socket handshake level — no need to resend it here
                        val msg = JSONObject().apply {
                            put("matchId", matchId)
                            put("battery", batteryPct)
                        }
                        socket.emit("low_battery", msg)
                        hasNotified = true
                    }
                    batteryPct > 30 && hasNotified -> {
                        hasNotified = false
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

        val (res, bitrate, _) = estimateNetworkQuality()
        val (wInitial, hInitial) = getDimsForOrientation(isPortrait, res)
        currentWidth = wInitial
        currentHeight = hInitial
        currentBitrate = bitrate

        // UI
        Box(Modifier.fillMaxSize()) {
            key(isPortrait) {
                AndroidView(
                    factory = { ctx ->
                        openGlView = OpenGlView(ctx)
                        rtmpCamera = RtmpCamera2(openGlView, this@StreamActivity)

                        openGlView.post {
                            // First, calculate the correct dimensions based on orientation
                            val (resBase, bitrateSelected, _) = estimateNetworkQuality()
                            val (width, height) = getDimsForOrientation(isPortrait, resBase)

                            // Prepare video with identical resolution to broadcast for preview quality
                            rtmpCamera.prepareVideo(width, height, 25, bitrateSelected, 2, if (!isPortrait) 0 else 90)
                            rtmpCamera.prepareAudio(128 * 1024, 48000, true)

                            imageFilter = ImageObjectFilterRender().apply {
                                setImage(
                                    scoreRenderer.render(
                                        width,
                                        height,
                                        team1Pts,
                                        team2Pts,
                                        team1Sets,
                                        team2Sets
                                    )
                                )
                                setDefaultScale(width, height)
                                setPosition(overlayPosition)
                            }

                            rtmpCamera.glInterface.addFilter(imageFilter)
                            rtmpCamera.startPreview()
                        }

                        openGlView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Overlay dinamico
            LaunchedEffect(team1Pts, team2Pts, team1Sets, team2Sets, servingTeam, overlayStyle, isPortrait) {
                // Determine current width/height based on orientation
                val (resBase, _, _) = estimateNetworkQuality()
                val (width, height) = getDimsForOrientation(isPortrait, resBase)

                updateOverlayIfChanged(
                    width,
                    height,
                    team1Pts,
                    team2Pts,
                    team1Sets,
                    team2Sets,
                    servingTeam,
                    overlayStyle,
                    overlayPosition
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

            if (isStreamingState) {
                SpikeStreamDangerButton(
                    text = stringResource(R.string.stop_stream),
                    onClick = {
                        stopBitrateMonitorReactive()
                        rtmpCamera.stopStream()
                        isStreamingState = false
                        restoreBrightness()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(min = 200.dp)
                )

            } else {
                SpikeStreamPrimaryButton(
                    text = stringResource(R.string.launch_stream),
                    onClick = {
                        rtmpCamera.glInterface.removeFilter(0)
                        val (resBase, bitrateSelected, _) = estimateNetworkQuality()
                        val (width, height) = getDimsForOrientation(isPortrait, resBase)

                        if (rtmpCamera.prepareVideo(width, height, 25, bitrateSelected, 2, if (!isPortrait) 0 else 90, VideoEncoder.H264, VideoProfile.HDR_NONE) &&
                            rtmpCamera.prepareAudio(128 * 1024, 48000, true)
                        ) {
                            currentWidth = width
                            currentHeight = height
                            currentBitrate = bitrateSelected

                            imageFilter = ImageObjectFilterRender().apply {
                                setImage(scoreRenderer.render(width, height, team1Pts, team2Pts, team1Sets, team2Sets))
                                setDefaultScale(if (!isPortrait) width else height, if (!isPortrait) height else width)
                                setPosition(overlayPosition)
                            }
                            rtmpCamera.glInterface.addFilter(imageFilter)
                            rtmpCamera.startStream(streamUrl)
                            isStreamingState = true
                            startBitrateMonitorReactive(streamUrl)
                            setScreenBrightness(0.02f)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(min = 200.dp)
                )
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
    // Redacted: do NOT log rtmpUrl — it contains the stream key
    override fun onConnectionStartedRtmp(rtmpUrl: String) { Log.i("Stream", "Connessione RTMP avviata") }
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

sealed class GetGameResult {
    data class Success(val team1Pts: Int, val team2Pts: Int, val team1Sets: Int, val team2Sets: Int) : GetGameResult()
    data class Error(val message: String) : GetGameResult()
}

suspend fun makeGetGameRequest(token: String, matchId: String): GetGameResult = withContext(Dispatchers.IO) {
    try {
        val client = getHttpClient()
        val request = Request.Builder()
            .url("${Constants.BASE_URL}/games/$matchId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        val response = client.newCall(request).execute()
        val body = response.body()?.string() ?: ""

        if (response.isSuccessful) {
            val json = JSONObject(body)
            val teamASets = json.getJSONArray("teamASets")
            val teamBSets = json.getJSONArray("teamBSets")

            if (teamASets.length() > 0 && teamBSets.length() > 0) {
                val aPts = teamASets.getInt(teamASets.length() - 1)
                val bPts = teamBSets.getInt(teamBSets.length() - 1)

                val aSets = (0 until minOf(teamASets.length(), teamBSets.length()) - 1).count { i ->
                    teamASets.getInt(i) > teamBSets.getInt(i)
                }
                val bSets = (0 until minOf(teamASets.length(), teamBSets.length()) - 1).count { i ->
                    teamASets.getInt(i) < teamBSets.getInt(i)
                }

                GetGameResult.Success(aPts, bPts, aSets, bSets)
            } else {
                GetGameResult.Success(0, 0, 0, 0)
            }
        } else {
            GetGameResult.Error("Errore caricamento dati match: ${response.code()}")
        }
    } catch (e: Exception) {
        Log.e("Stream", "Get game request failed", e)
        GetGameResult.Error("Connessione fallita")
    }
}
