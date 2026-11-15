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
 * Manages Socket.io score updates and private stream telemetry.
 * Integrates background battery receivers and handles main thread updates to the UI safely.
 */
class StreamSocketManager(
    private val context: Context,
    private val matchId: String,
    private val client: OkHttpClient,
    private val onProductionCommand: (JSONObject) -> Unit = {},
    private val onProductionConnect: () -> Unit = {},
    private val onConfig: (JSONObject) -> Unit = {},
    private val replayTeam: () -> String? = { null },
    private val onReplay: (String, Long, Long) -> Unit = { _, _, _ -> },
    private val onReplayAuthorized: () -> Unit = {},
    private val onReplayDenied: () -> Unit = {},
    private val onReplayStopped: () -> Unit = {},
    private val onScoreUpdated: (team1Pts: Int, team2Pts: Int, team1Sets: Int, team2Sets: Int) -> Unit
) {
    private companion object {
        const val ROOM_JOIN_TIMEOUT_MS = 10_000L
        const val SCORE_REFRESH_MS = 15_000L
        const val SCORE_RECONCILE_MS = 60_000L
    }
    private var socket: Socket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var batteryReceiver: BroadcastReceiver? = null
    private var latestBatteryPercent: Int? = null
    private var roomRetry: Runnable? = null
    private var lastRoomError: String? = null
    private var scoreRoomJoined = false
    private val scoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scoreSyncGate = ScoreSyncGate()
    private var scoreSyncJob: Job? = null
    private val telemetryReporter = StreamTelemetryReporter(matchId) { payload, acknowledged ->
        val active = socket
        if (active?.connected() != true) false else {
            active.emit("stream_telemetry", payload, object : AckWithTimeout(5_000) {
                override fun onSuccess(vararg args: Any?) {
                    val ok = (args.firstOrNull() as? JSONObject)?.optBoolean("ok", false) == true
                    acknowledged(ok)
                    if (ok) handler.post {
                        if (socket === active && active.connected()) {
                            sendProduction()
                            val status = payload.optString("status")
                            if (status == "LIVE" || status == "CONNECTING") syncReplay(active,payload.optString("sessionId"))
                        }
                    }
                }
                override fun onTimeout() { cancelTimer(); acknowledged(false) }
            })
            true
        }
    }

    private var onlineSince = 0L
    private var replaySession: String? = null
    private var clockUpperOffset = 0L
    private var clockCheckedAt = 0L
    private var replayRevision = -1L
    private var scoreRevision = -1L
    private var socketGeneration = 0
    private fun monotonicMs() = android.os.SystemClock.elapsedRealtime()
    private fun resetReplay() {
        socketGeneration++
        replaySession = null
        onReplayStopped()
    }

    private fun syncReplay(active: Socket, expected: String) {
        val team = replayTeam() ?: return
        val gen = socketGeneration
        val before = monotonicMs()
        active.emit("replay_sync", JSONObject().put("matchId",matchId).put("team",team), object : AckWithTimeout(2000) {
            override fun onSuccess(vararg args: Any?) {
                val reply = args.firstOrNull() as? JSONObject ?: return
                handler.post {
                    if (gen != socketGeneration || socket !== active || !active.connected() || team != replayTeam()) return@post
                    if (!reply.optBoolean("ok") || reply.optString("sessionId") != expected) {
                        replaySession = null
                        onReplayStopped()
                        if (reply.optBoolean("denied")) onReplayDenied()
                    } else {
                        if (telemetryReporter.sessionId != expected) return@post
                        onReplayAuthorized()
                        replaySession = expected
                        clockCheckedAt = before
                        clockUpperOffset = reply.getLong("serverNow") - before
                    }
                }
            }
            override fun onTimeout() { cancelTimer() }
        })
    }

    private fun applyScore(data: JSONObject): Boolean {
        val revision = data.optLong("scoreRevision", -1)
        if (revision >= 0 && revision <= scoreRevision) return revision == scoreRevision
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
            return false
        }
        // Reject an older HTTP response without stopping periodic recovery.
        scoreSyncGate.invalidate()
        onScoreUpdated(score.team1Pts, score.team2Pts, score.team1Sets, score.team2Sets)
        if (revision >= 0) scoreRevision = revision
        if (data.optBoolean("corrected")) onReplayStopped()
        return true
    }

    private var configGeneration = 0L
    private val reload = Runnable { config(JSONObject().put("action","get")) }
    private fun config(request: JSONObject) {
        val active = socket ?: return
        if (!active.connected()) return
        handler.removeCallbacks(reload)
        val gen = ++configGeneration
        fun finish(response: JSONObject?) {
            handler.post {
                if (socket !== active || !active.connected() || gen != configGeneration) return@post
                if (response?.optBoolean("ok") == true) onConfig(response)
                else {
                    val save = request.optString("action") == "save"
                    if (save) Toast.makeText(context, "Modifica sponsor non confermata. Ricarico la configurazione.", Toast.LENGTH_SHORT).show()
                    handler.postDelayed(reload, if (save) 1000L else 15000L)
                }
            }
        }
        active.emit("production_config", request.put("matchId",matchId), object : AckWithTimeout(5000) {
            override fun onSuccess(vararg args: Any?) = finish(args.firstOrNull() as? JSONObject)
            override fun onTimeout() { cancelTimer(); finish(null) }
        })
    }

    private var production: JSONObject? = null
    private var sentRevision: String? = null
    fun publishProduction(state: JSONObject) {
        production = state.put("matchId", matchId)
        sendProduction()
    }
    private fun sendProduction() {
        val active = socket ?: return
        val state = production ?: return
        if (active.connected() && sentRevision != state.optString("revision")) {
            active.emit("production_state", state, object : AckWithTimeout(3000) {
                override fun onSuccess(vararg args: Any?) {
                    if ((args.firstOrNull() as? JSONObject)?.optBoolean("ok") == true) handler.post {
                        if (socket === active && production === state) sentRevision = state.optString("revision")
                    }
                }
                override fun onTimeout() { cancelTimer() }
            })
        }
    }
    fun updateTelemetry(value: StreamTelemetry) = telemetryReporter.update(value)

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
                    applyScore(data)
                }
            }

            s.on("replay_access_changed") { args ->
                val enabled = (args.firstOrNull() as? JSONObject)?.optBoolean("enabled") ?: return@on
                handler.post {
                    if (socket !== s || !s.connected()) return@post
                    if (!enabled) { resetReplay(); onReplayDenied() }
                    handler.removeCallbacks(reload)
                    handler.postDelayed(reload, 500)
                }
            }
            s.on("sponsor_access_changed") {
                handler.post {
                    if (socket !== s || !s.connected()) return@post
                    handler.removeCallbacks(reload)
                    handler.postDelayed(reload, 500)
                }
            }
            s.on("replay_stop") { args ->
                if ((args.firstOrNull() as? JSONObject)?.optString("matchId") == matchId) handler.post { if (socket === s && s.connected()) onReplayStopped() }
            }
            s.on("replay_trigger") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val received = android.os.SystemClock.elapsedRealtimeNanos() / 1000
                val connection = s.id()
                handler.post {
                    if (socket !== s || !s.connected() || s.id() != connection || data.optString("matchId") != matchId ||
                        received / 1000 - onlineSince < 5000 || replaySession != telemetryReporter.sessionId || data.optString("sessionId") != replaySession ||
                        (data.optString("team") !in setOf("A","B") || (replayTeam() != "*" && data.optString("team") != replayTeam())) || monotonicMs() - clockCheckedAt > 20_000) return@post
                    val revision = data.optLong("scoreRevision", -1)
                    if (revision <= replayRevision || revision < scoreRevision) return@post
                    replayRevision = revision
                    val deadline = data.optLong("deadline") - clockUpperOffset
                    if (deadline - monotonicMs() !in 1L..1500L || !applyScore(data)) return@post
                    onReplay(data.getString("actionId"), received, deadline * 1000)
                }
            }
            s.on("production_config_changed") { args ->
                if ((args.firstOrNull() as? JSONObject)?.optString("matchId") == matchId) handler.post {
                    if (socket === s && s.connected()) { handler.removeCallbacks(reload); handler.postDelayed(reload, 400) }
                }
            }
            s.on("production_command") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                handler.post { if (socket === s && s.connected() && data.optString("matchId") == matchId) onProductionCommand(data) }
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
                    if (socket !== s || !s.connected()) return@post
                    Toast.makeText(context, context.getString(R.string.connecting), Toast.LENGTH_SHORT).show()
                    sentRevision = null
                    onlineSince = monotonicMs()
                    onProductionConnect()
                    reload.run()
                    telemetryReporter.connected()
                    requestRoomJoin(s)
                }
            }

            s.on("match_joined") { args ->
                val data = args.getOrNull(0) as? JSONObject ?: return@on
                if (data.optString("matchId") != matchId) return@on
                handler.post {
                    if (socket !== s || !s.connected()) return@post
                    cancelRoomRetry()
                    lastRoomError = null
                    scoreRoomJoined = true
                    startScoreSync(s, token)
                }
            }

            s.on("match_join_error") { args ->
                val data = args.getOrNull(0) as? JSONObject ?: return@on
                if (data.optString("matchId") != matchId) return@on
                handler.post {
                    if (socket !== s || !s.connected()) return@post
                    cancelRoomRetry()
                    scoreSyncGate.invalidate()
                    scoreRoomJoined = false
                    startScoreSync(s, token)
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
                        resetReplay()
                        // HTTP recovery remains available while WebSocket reconnects.
                        scoreSyncGate.invalidate()
                        scoreRoomJoined = false
                        startScoreSync(s, token)
                        cancelRoomRetry()
                        telemetryReporter.disconnected()
                        handler.removeCallbacks(reload)
                    }
                }
            }

            startScoreSync(s, token)
            s.connect()
            startBatteryMonitoring()

        } catch (e: Exception) {
            disconnect()
            Log.e("StreamSocketManager", "Failed to connect WebSocket", e)
            handler.post {
                Toast.makeText(context, context.getString(R.string.connection_failed) + ": ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestRoomJoin(activeSocket: Socket) {
        // All room timers and callbacks are serialized on the main thread.
        cancelRoomRetry()
        if (socket === activeSocket && activeSocket.connected()) {
            val timeout = Runnable {
                roomRetry = null
                requestRoomJoin(activeSocket)
            }
            roomRetry = timeout
            handler.postDelayed(timeout, ROOM_JOIN_TIMEOUT_MS)
            activeSocket.emit("join_match", JSONObject().put("matchId", matchId))
        }
    }

    private fun cancelScoreSync() {
        scoreSyncGate.invalidate()
        scoreSyncJob?.cancel()
        scoreSyncJob = null
    }

    private fun startScoreSync(activeSocket: Socket, token: String) {
        cancelScoreSync()
        scoreSyncJob = scoreScope.launch {
            var retryMs = 1_000L
            // Independent of room admission and WebSocket connectivity. Only one
            // request at a time; disconnect() or a new token cancels this session.
            while (socket === activeSocket) {
                val ticket = scoreSyncGate.beginSnapshot()
                when (val result = makeGetGameRequest(token, matchId)) {
                    is GetGameResult.Success -> {
                        if (socket === activeSocket && scoreSyncGate.accepts(ticket)) {
                            if (result.revision < 0 || result.revision >= scoreRevision) {
                                scoreRevision = maxOf(scoreRevision, result.revision)
                                onScoreUpdated(result.team1Pts, result.team2Pts, result.team1Sets, result.team2Sets)
                            }
                        }
                        retryMs = 1_000L
                        // WS supplies live points; reconcile missed packets periodically.
                        // A failed room join or disconnect restarts HTTP recovery immediately.
                        delay(if (scoreRoomJoined && activeSocket.connected()) SCORE_RECONCILE_MS else SCORE_REFRESH_MS)
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
     * Adds battery level and charging state to the private stream telemetry.
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
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                telemetryReporter.battery(latestBatteryPercent!!,
                    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
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

    /**
     * Unregisters battery listeners safely.
     */
    private fun stopBatteryMonitoring() {
        latestBatteryPercent = null
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
        scoreRoomJoined = false
        resetReplay()
        handler.removeCallbacks(reload)
        telemetryReporter.disconnected()
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
