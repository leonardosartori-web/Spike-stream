package com.leonardos.spikestream.streaming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.leonardos.spikestream.utils.Constants
import com.leonardos.spikestream.R
import com.leonardos.spikestream.utils.Logger as Log
import io.socket.client.IO
import io.socket.client.AckWithTimeout
import io.socket.client.Socket
import okhttp3.OkHttpClient
import org.json.JSONObject
import com.leonardos.spikestream.data.GetGameResult
import com.leonardos.spikestream.data.StreamApi.makeGetGameRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.Exception

/**
 * Manages Socket.io connections for real-time score updates and battery logging.
 * Integrates background battery receivers and handles main thread updates to the UI safely.
 */
class StreamSocketManager(
    private val context: Context,
    private val matchId: String,
    private val client: OkHttpClient,
    private val onScoreUpdated: (team1Pts: Int, team2Pts: Int, team1Sets: Int, team2Sets: Int) -> Unit
) {
    private var socket: Socket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var batteryReceiver: BroadcastReceiver? = null
    private var latestBatteryPercent: Int? = null
    private var batteryWarningAcknowledged = false
    private var batteryWarningBlocked = false
    private var batteryRetry: Runnable? = null
    private var roomRetry: Runnable? = null
    private var lastRoomError: String? = null
    private val scoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scoreSyncGate = ScoreSyncGate()
    private var scoreSyncJob: Job? = null

    /**
     * Instantiates the Socket.io client with custom transports and JWT Auth options, then connects.
     */
    fun connect(token: String) {
        disconnect() // Clean any stale socket connection first

        val authOpts = IO.Options().apply {
            transports = arrayOf("websocket")
            callFactory = client
            webSocketFactory = client
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1000
            reconnectionDelayMax = 30_000
            timeout = 20000
            auth = mapOf("token" to token)
        }

        try {
            val s = IO.socket(Constants.BASE_URL, authOpts)
            socket = s

            // 1. Listen for score updates from the collaborative scoreboards
            s.on("score_update") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                handler.post {
                    if (socket !== s || !s.connected() || data.optString("matchId") != matchId) return@post
                    val score = runCatching {
                        val a = data.getJSONArray("teamASets")
                        val b = data.getJSONArray("teamBSets")
                        require(a.length() in 1..6 && b.length() in 1..6)
                        val teamA = (0 until a.length()).map { a.getInt(it).also { pts -> require(pts >= 0) } }
                        val teamB = (0 until b.length()).map { b.getInt(it).also { pts -> require(pts >= 0) } }
                        val completed = 0 until minOf(teamA.size, teamB.size) - 1
                        GetGameResult.Success(teamA.last(), teamB.last(),
                            completed.count { teamA[it] > teamB[it] },
                            completed.count { teamA[it] < teamB[it] })
                    }.getOrElse {
                        Log.w("StreamSocketManager", "Aggiornamento punteggio non valido")
                        return@post
                    }
                    cancelScoreSync()
                    onScoreUpdated(score.team1Pts, score.team2Pts, score.team1Sets, score.team2Sets)
                }
            }

            // 2. Handle connection failures
            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val err = args.getOrNull(0)?.toString() ?: "Unknown error"
                handler.post {
                    if (socket !== s) return@post
                    Toast.makeText(context, context.getString(R.string.connection_failed) + ": $err", Toast.LENGTH_SHORT).show()
                }
            }

            // 3. Handle successful connection and join match room
            s.on(Socket.EVENT_CONNECT) {
                handler.post {
                    if (socket !== s) return@post
                    Toast.makeText(context, context.getString(R.string.connecting), Toast.LENGTH_SHORT).show()
                    sendBatteryWarningIfNeeded()
                }
                requestRoomJoin(s)
            }

            s.on("match_joined") { args ->
                val data = args.getOrNull(0) as? JSONObject ?: return@on
                if (data.optString("matchId") != matchId) return@on
                handler.post {
                    if (socket !== s) return@post
                    cancelRoomRetry()
                    lastRoomError = null
                    syncScoreAfterJoin(s, token)
                }
            }

            s.on("match_join_error") { args ->
                val data = args.getOrNull(0) as? JSONObject ?: return@on
                if (data.optString("matchId") != matchId) return@on
                handler.post {
                    if (socket !== s) return@post
                    cancelRoomRetry()
                    cancelScoreSync()
                    val reason = data.optString("reason", "unavailable")
                    if (lastRoomError != reason) {
                        Toast.makeText(context, context.getString(R.string.connection_failed) + " [$reason]", Toast.LENGTH_LONG).show()
                        lastRoomError = reason
                    }
                    val retryDelay = data.optLong("retryAfterMs", 0)
                    if (retryDelay > 0 && reason != "priority_replaced") {
                        val retry = Runnable {
                            roomRetry = null
                            requestRoomJoin(s)
                        }
                        roomRetry = retry
                        handler.postDelayed(retry, retryDelay.coerceIn(1000L, 30000L))
                    }
                }
            }

            s.on(Socket.EVENT_DISCONNECT) {
                handler.post {
                    if (socket === s) {
                        cancelScoreSync()
                        cancelBatteryRetry()
                        cancelRoomRetry()
                    }
                }
            }

            s.connect()
            startBatteryMonitoring()

        } catch (e: Exception) {
            Log.e("StreamSocketManager", "Failed to connect WebSocket", e)
            handler.post {
                Toast.makeText(context, context.getString(R.string.connection_failed) + ": ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestRoomJoin(activeSocket: Socket) {
        if (socket === activeSocket && activeSocket.connected()) {
            activeSocket.emit("join_match", JSONObject().put("matchId", matchId))
        }
    }

    private fun cancelScoreSync() {
        scoreSyncGate.invalidate()
        scoreSyncJob?.cancel()
        scoreSyncJob = null
    }

    private fun syncScoreAfterJoin(activeSocket: Socket, token: String) {
        cancelScoreSync()
        val ticket = scoreSyncGate.beginSnapshot()
        scoreSyncJob = scoreScope.launch {
            var retryMs = 1_000L
            while (socket === activeSocket && activeSocket.connected() && scoreSyncGate.accepts(ticket)) {
                when (val result = makeGetGameRequest(token, matchId)) {
                    is GetGameResult.Success -> {
                        if (socket === activeSocket && activeSocket.connected() && scoreSyncGate.accepts(ticket)) {
                            onScoreUpdated(result.team1Pts, result.team2Pts, result.team1Sets, result.team2Sets)
                        }
                        return@launch
                    }
                    is GetGameResult.Error -> {
                        delay(retryMs)
                        retryMs = (retryMs * 2).coerceAtMost(30_000L)
                    }
                }
            }
        }
    }

    private fun cancelRoomRetry() {
        roomRetry?.let { handler.removeCallbacks(it) }
        roomRetry = null
    }

    /**
     * Listens to system battery updates and reports battery depletion warnings (<= 30%) to the socket.
     */
    private fun startBatteryMonitoring() {
        stopBatteryMonitoring()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (scale <= 0 || level < 0 || level > scale) return
                latestBatteryPercent = (level.toLong() * 100 / scale).toInt()

                if (latestBatteryPercent!! > 30) {
                    cancelBatteryRetry()
                    batteryWarningAcknowledged = false
                    batteryWarningBlocked = false
                } else {
                    sendBatteryWarningIfNeeded()
                }
            }
        }

        batteryReceiver = receiver
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Keep one warning pending until accepted, retrying only the current reading while connected. */
    private fun sendBatteryWarningIfNeeded() {
        val activeSocket = socket ?: return
        val percent = latestBatteryPercent ?: return
        if (!activeSocket.connected() || percent > 30 || batteryWarningAcknowledged ||
            batteryWarningBlocked || batteryRetry != null) return

        val retry = Runnable {
            batteryRetry = null
            if (socket === activeSocket) sendBatteryWarningIfNeeded()
        }
        batteryRetry = retry
        handler.postDelayed(retry, 30_000)

        activeSocket.emit("low_battery", JSONObject().apply {
            put("matchId", matchId)
            put("battery", percent)
        }, object : AckWithTimeout(20_000) {
            override fun onSuccess(vararg args: Any?) {
                handler.post {
                    // Ignore acknowledgments from an old connection or battery episode.
                    if (socket !== activeSocket || batteryRetry !== retry) return@post
                    val result = args.firstOrNull() as? JSONObject ?: return@post
                    if (result.optBoolean("ok", false)) {
                        cancelBatteryRetry()
                        batteryWarningAcknowledged = true
                    } else if (result.has("retryable") && !result.optBoolean("retryable")) {
                        cancelBatteryRetry()
                        batteryWarningBlocked = true
                    }
                    // Throttling and temporary errors retry after 30s.
                }
            }

            override fun onTimeout() {
                // The library removes the pending ACK and any buffered packet.
                // Our main-thread retry is already scheduled; release its timer too.
                cancelTimer()
            }
        })
    }

    private fun cancelBatteryRetry() {
        batteryRetry?.let(handler::removeCallbacks)
        batteryRetry = null
    }

    /**
     * Unregisters battery listeners safely.
     */
    private fun stopBatteryMonitoring() {
        cancelBatteryRetry()
        latestBatteryPercent = null
        batteryWarningAcknowledged = false
        batteryWarningBlocked = false
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        batteryReceiver = null
    }

    /**
     * Disconnects the socket and cleans up all callbacks and receivers.
     */
    fun disconnect() {
        cancelScoreSync()
        cancelRoomRetry()
        lastRoomError = null
        stopBatteryMonitoring()
        socket?.let {
            // Also stop reconnection and pending battery ACK timers while offline.
            it.disconnect()
            it.off()
        }
        socket = null
    }
}
