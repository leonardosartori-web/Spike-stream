package com.leonardos.spikestream

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.leonardos.spikestream.ui.theme.MyApplicationTheme
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import getUnsafeOkHttpClient
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

sealed class MatchResult {
    data class Success(val games: JSONObject) : MatchResult()
    data class Error(val message: String) : MatchResult()
}

class StreamActivity : ComponentActivity(), ConnectChecker {

    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var imageFilter: ImageObjectFilterRender


    private val streamW = 1280
    private val streamH = 720

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rtmpUrl = intent.getStringExtra("RTMP_URL") ?: "rtmp://example.com/live/stream"
        val team1 = intent.getStringExtra("TEAM_1") ?: "Squadra 1"
        val team2 = intent.getStringExtra("TEAM_2") ?: "Squadra 2"
        val matchId = intent.getStringExtra("MATCH_ID") ?: "Match id"

        setContent { MyApplicationTheme { Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ){ StreamingScreen(team1, team2, rtmpUrl, matchId) } }  }
    }

    fun drawScoreBitmap(
        width: Int,
        height: Int,
        team1: String,
        team2: String,
        team1Pts: Int,
        team2Pts: Int,
        team1Sets: Int,
        team2Sets: Int,
        parz1: List<Int>,
        parz2: List<Int>
    ): Bitmap {
        // Dimensioni e configurazione
        val bitmapWidth = 400
        val bitmapHeight = 150
        val cornerRadius = 8f
        val padding = 8f
        val columnWidth = bitmapWidth / 5f
        val rowHeight = bitmapHeight / 2f

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Sfondo bianco con ombreggiatura leggera
        val backgroundPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            setShadowLayer(4f, 2f, 2f, Color.argb(50, 0, 0, 0))
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat()),
            cornerRadius, cornerRadius, backgroundPaint
        )

        // Stili testo
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val highlightPaint = Paint().apply {
            color = Color.parseColor("#E91E63") // Rosso acceso
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        fun drawAutoSizedText(
            canvas: Canvas,
            text: String,
            centerX: Float,
            centerY: Float,
            maxWidth: Float,
            paint: Paint,
            maxTextSize: Float = 32f,
            minTextSize: Float = 12f
        ) {
            paint.textSize = maxTextSize
            // Riduci la dimensione del testo finché la larghezza è troppo grande o non scendi sotto la minima
            while (paint.measureText(text) > maxWidth && paint.textSize > minTextSize) {
                paint.textSize -= 1f
            }
            // Draw text centered vertical and horizontal
            canvas.drawText(
                text,
                centerX,
                centerY - (paint.ascent() + paint.descent()) / 2,
                paint
            )
        }


        fun drawCell(
            text: String,
            colStart: Float,
            colSpan: Float,
            row: Float,
            highlight: Boolean = false,
            autoSize: Boolean = false
        ) {
            val centerX = (colStart + colSpan / 2) * columnWidth
            val centerY = row * rowHeight + rowHeight / 2 + padding
            val paintToUse = if (highlight) highlightPaint else textPaint

            if (autoSize && colSpan > 1f) {
                drawAutoSizedText(canvas, text, centerX, centerY, colSpan * columnWidth - padding * 2, paintToUse)
            } else {
                canvas.drawText(
                    text,
                    centerX,
                    centerY - (paintToUse.ascent() + paintToUse.descent()) / 2,
                    paintToUse
                )
            }
        }

        drawCell(team1, 0f, 3f, 0f, autoSize = true)
        drawCell(team2, 0f, 3f, 1f, autoSize = true)
        drawCell(team1Pts.toString(), 3f, 1f, 0f)
        drawCell(team1Sets.toString(), 4f, 1f, 0f, highlight = team1Sets > team2Sets)
        drawCell(team2Pts.toString(), 3f, 1f, 1f)
        drawCell(team2Sets.toString(), 4f, 1f, 1f, highlight = team2Sets > team1Sets)


        return bitmap
    }



    /* ------------------ COMPOSE UI ------------------ */
    @Composable
    private fun StreamingScreen(team1: String, team2: String, streamUrl: String, matchId: String) {
        val ctx = LocalContext.current

        val activity = ctx as Activity
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        var isStreaming by remember { mutableStateOf(false) }

        var team1Sets by remember { mutableStateOf(0) }
        var team2Sets by remember { mutableStateOf(0) }
        var team1Pts by remember { mutableStateOf(0) }
        var team2Pts by remember { mutableStateOf(0) }
        val parz1 = remember { mutableStateListOf<Int>() }
        val parz2 = remember { mutableStateListOf<Int>() }

        LaunchedEffect(team1Pts, team2Pts, team1Sets, team2Sets, parz1, parz2) {
            if (::imageFilter.isInitialized) {
                imageFilter.setImage(
                    drawScoreBitmap(
                        streamW, 400,
                        team1, team2,
                        team1Pts, team2Pts,
                        team1Sets, team2Sets,
                        parz1, parz2
                    )
                )
            }
        }

        /* ---------- permessi ---------- */
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.values.all { it }) if (::rtmpCamera.isInitialized) rtmpCamera.startPreview()
            else Toast.makeText(ctx, ctx.getString(R.string.permissions), Toast.LENGTH_SHORT).show()
        }

        LaunchedEffect(Unit) {
            if (!hasPermissions(ctx)) {
                val activity = ctx as Activity
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
                    // Se non si devono mostrare razionali, ma i permessi sono negati -> l’utente ha selezionato “Non chiedere più”
                    // Mostra un messaggio e porta alle impostazioni
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.permissions),
                        Toast.LENGTH_LONG
                    ).show()

                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", ctx.packageName, null)
                    }
                    ctx.startActivity(intent)
                }
            }
        }


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

        val socket = IO.socket("https://spikestream.tooolky.com", opts)


        LaunchedEffect(Unit) {

            socket.on("score_update") { args ->
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val data = args[0] as JSONObject

                    if (data.get("matchId") == matchId) {

                        val teamASets = data.getJSONArray("teamASets")
                        val teamBSets = data.getJSONArray("teamBSets")

                        team1Pts = teamASets.getInt(teamASets.length() - 1)
                        team2Pts = teamBSets.getInt(teamBSets.length() - 1)

                        team1Sets = 0
                        team2Sets = 0

                        val setsCount = minOf(teamASets.length(), teamBSets.length())

                        for (i in 0 until  setsCount - 1) {
                            val a = teamASets.getInt(i)
                            val b = teamBSets.getInt(i)

                            when {
                                a > b -> team1Sets++
                                b > a -> team2Sets++
                            }
                        }

                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(ctx, "Score updated ${team1Pts} - ${team2Pts}", Toast.LENGTH_SHORT).show()
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

            try {
                socket.connect()
            } catch (e: Exception) {
                //Toast.makeText(ctx, "Connection: ${e.message}", Toast.LENGTH_SHORT).show()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, "Connection: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

        }



        val connectChecker = object : ConnectChecker {
            override fun onConnectionSuccess() {
                Toast.makeText(ctx, ctx.getString(R.string.streaming_launched), Toast.LENGTH_SHORT).show()
            }

            override fun onConnectionFailed(reason: String) {
                Toast.makeText(ctx, "Errore: $reason", Toast.LENGTH_SHORT).show()
                rtmpCamera.stopStream()
            }
            override fun onAuthError() {}
            override fun onAuthSuccess() {}
            override fun onConnectionStarted(url: String) {}
            override fun onDisconnect() {}
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    openGlView = OpenGlView(ctx)
                    rtmpCamera = RtmpCamera2(openGlView, connectChecker)

                    openGlView.post {
                        // In landscape, potresti voler invertire width e height
                        val width = openGlView.width.takeIf { it > 0 } ?: 1280
                        val height = openGlView.height.takeIf { it > 0 } ?: 720

                        imageFilter = ImageObjectFilterRender().apply {
                            setImage(drawScoreBitmap(
                                width, 400,  // Modifica le dimensioni per il landscape
                                team1, team2,
                                team1Pts, team2Pts,
                                team1Sets, team2Sets,
                                parz1, parz2
                            ))
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

            // Pulsante di controllo dello streaming in overlay
            Button(
                onClick = {
                    if (!isStreaming) {

                        // Configurazione per landscape
                        val streamWidth = 1280  // o una dimensione adatta al landscape
                        val streamHeight = 720

                        if (rtmpCamera.prepareVideo(1920, 1080, 30, 4_000 * 1024, 2, 0) &&
                            rtmpCamera.prepareAudio()) {

                            rtmpCamera.setVideoCodec(VideoCodec.H264)

                            imageFilter = ImageObjectFilterRender().apply {
                                setImage(drawScoreBitmap(
                                    streamWidth, 400,
                                    team1, team2,
                                    team1Pts, team2Pts,
                                    team1Sets, team2Sets,
                                    parz1, parz2
                                ))
                                setDefaultScale(streamWidth, streamHeight)
                                setPosition(TranslateTo.BOTTOM_LEFT)
                            }

                            rtmpCamera.glInterface.addFilter(imageFilter)
                            rtmpCamera.startStream(streamUrl)
                            isStreaming = true
                        }
                    } else {
                        rtmpCamera.stopStream()
                        isStreaming = false
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Text(if (isStreaming) stringResource(R.string.stop_stream) else stringResource(R.string.launch_stream))
            }
        }
    }

    /* ------------------ PERMESSI ------------------ */
    private fun hasPermissions(ctx: Context) = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /* ------------------ CONNECT CHECKER ------------------ */
    override fun onConnectionSuccess() =
        runOnUiThread { Toast.makeText(this, this.getString(R.string.streaming_launched), Toast.LENGTH_SHORT).show() }

    override fun onConnectionFailed(reason: String) =
        runOnUiThread {
            Toast.makeText(this, "Errore: $reason", Toast.LENGTH_SHORT).show()
            if (::rtmpCamera.isInitialized && rtmpCamera.isStreaming) rtmpCamera.stopStream()
        }

    override fun onConnectionStarted(url: String) {}
    override fun onDisconnect() {}
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onNewBitrate(bitrate: Long) {}

    /* ------------------ LIFECYCLE CLEANUP ------------------ */
    override fun onStop() {
        super.onStop()
        if (::rtmpCamera.isInitialized) {
            if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
            if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
        }
    }
}

suspend fun makeGetMatchRequest(token: String, match_id: String): MatchResult = withContext(Dispatchers.IO) {
    try {
        val client = getUnsafeOkHttpClient()
        val request = Request.Builder()
            .url("https://spikestream.tooolky.com/games/${match_id}")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        val response = client.newCall(request).execute()

        val body = response.body()?.string() ?: "[]"
        if (response.isSuccessful) {
            val json = JSONObject(body)
            MatchResult.Success(json)
        } else {
            MatchResult.Error("HTTP ${response.code()}: ${body}")
        }

    } catch (e: Exception) {
        MatchResult.Error("Eccezione: ${e.localizedMessage}")
    }
}


