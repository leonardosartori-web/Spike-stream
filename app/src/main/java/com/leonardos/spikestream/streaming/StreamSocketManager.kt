package com.leonardos.spikestream.streaming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.leonardos.spikestream.utils.Constants
import com.leonardos.spikestream.R
import com.leonardos.spikestream.utils.Logger as Log
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.OkHttpClient
import org.json.JSONObject
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
            reconnectionAttempts = 5
            reconnectionDelay = 1000
            timeout = 20000
            auth = mapOf("token" to token)
        }

        try {
            val s = IO.socket(Constants.BASE_URL, authOpts)
            socket = s

            // 1. Listen for score updates from the collaborative scoreboards
            s.on("score_update") { args ->
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val data = args[0] as JSONObject
                    if (data.optString("matchId") == matchId) {
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

                        handler.post {
                            onScoreUpdated(aPts, bPts, aSets, bSets)
                        }
                    }
                }
            }

            // 2. Handle connection failures
            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val err = args.getOrNull(0)?.toString() ?: "Unknown error"
                handler.post {
                    Toast.makeText(context, context.getString(R.string.connection_failed) + ": $err", Toast.LENGTH_SHORT).show()
                }
            }

            // 3. Handle successful connection and join match room
            s.on(Socket.EVENT_CONNECT) {
                handler.post {
                    Toast.makeText(context, context.getString(R.string.connecting), Toast.LENGTH_SHORT).show()
                }
                s.emit("join_match", JSONObject().put("matchId", matchId))
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

    /**
     * Listens to system battery updates and reports battery depletion warnings (<= 30%) to the socket.
     */
    private fun startBatteryMonitoring() {
        stopBatteryMonitoring()

        var hasNotified = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val batteryPct = level * 100 / scale

                if (batteryPct <= 30 && !hasNotified) {
                    val msg = JSONObject().apply {
                        put("matchId", matchId)
                        put("battery", batteryPct)
                    }
                    socket?.emit("low_battery", msg)
                    hasNotified = true
                } else if (batteryPct > 30 && hasNotified) {
                    hasNotified = false
                }
            }
        }

        batteryReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    /**
     * Unregisters battery listeners safely.
     */
    private fun stopBatteryMonitoring() {
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
        stopBatteryMonitoring()
        socket?.let {
            if (it.connected()) {
                it.disconnect()
            }
            it.off()
        }
        socket = null
    }
}
