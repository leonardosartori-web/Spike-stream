package com.leonardos.spikestream.streaming

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID

/** Latest state only: no offline queue, 10s heartbeat, coalesced immediate changes. */
class StreamTelemetryReporter(private val matchId: String,
    private val scheduler: StreamReportScheduler = AndroidStreamReportScheduler(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val send: (JSONObject, (Boolean) -> Unit) -> Boolean) {
    private var telemetry: StreamTelemetry? = null
    private var battery: Int? = null
    private var charging = false
    var sessionId = UUID.randomUUID().toString()
        private set
    private var sequence = 0L
    private var lastSent = -10_000L
    private var lastImportant = ""
    private var terminalAttempts = 0
    private var enabled = false
    private var started = false
    private val pending = Runnable { publish() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!enabled || !active()) return
            requestSend()
            scheduler.post(this, 10_000)
        }
    }

    fun connected() {
        enabled = true
        if (started) requestSend()
        restartHeartbeat()
    }

    fun disconnected() {
        enabled = false
        scheduler.remove(pending)
        scheduler.remove(heartbeat)
    }

    fun update(value: StreamTelemetry) {
        val wasActive = active()
        if (value.status == BroadcastStatus.IDLE && !started) return
        if (value.status == BroadcastStatus.IDLE && telemetry?.status == BroadcastStatus.IDLE) return
        // Idle updates after a fatal error must not replace the final error with a normal stop.
        if (value.status == BroadcastStatus.IDLE && telemetry?.status == BroadcastStatus.ERROR) return
        if (!wasActive && value.status !in listOf(BroadcastStatus.IDLE, BroadcastStatus.ERROR)) {
            sessionId = UUID.randomUUID().toString()
            sequence = 0
            terminalAttempts = 0
            started = true
        }
        telemetry = value
        val important = "${value.status}/${value.health}/${value.network.transport}/${batteryBand()}/$charging"
        if (important != lastImportant) {
            lastImportant = important
            terminalAttempts = 0
            if (!active()) {
                scheduler.remove(pending)
                publish() // Send the final state before Activity teardown disconnects the socket.
            } else requestSend()
        }
        if (wasActive != active()) restartHeartbeat()
    }

    fun battery(percent: Int, isCharging: Boolean) {
        val oldBand = batteryBand()
        val changed = charging != isCharging
        battery = percent.coerceIn(0, 100)
        charging = isCharging
        if (started && active() && (changed || oldBand != batteryBand())) requestSend()
    }

    private fun batteryBand() = when { battery == null -> -1; battery!! <= 15 -> 0; battery!! <= 30 -> 1; else -> 2 }
    private fun active() = telemetry?.status?.let { it != BroadcastStatus.IDLE && it != BroadcastStatus.ERROR } == true
    private fun restartHeartbeat() {
        scheduler.remove(heartbeat)
        if (enabled && active()) scheduler.post(heartbeat, 10_000)
    }

    private fun requestSend() {
        if (!enabled || !started) return
        scheduler.remove(pending)
        scheduler.post(pending, (1_100L - (clock() - lastSent)).coerceAtLeast(0))
    }

    private fun publish() {
        val value = telemetry ?: return
        if (!enabled || !started) return
        val session = sessionId
        val reportSequence = ++sequence
        val terminal = !active()
        if (terminal) terminalAttempts++
        val payload = JSONObject().apply {
            put("matchId", matchId); put("sessionId", session); put("sequence", reportSequence)
            put("status", if (value.status == BroadcastStatus.IDLE) "STOPPED" else value.status.name)
            put("health", value.health.name)
            put("battery", battery ?: JSONObject.NULL); put("charging", charging)
            put("bitrate", value.actualBitrate.coerceIn(0L, 100_000_000L))
            put("fps", value.fps.coerceIn(0, 240)); put("droppedFrames", value.droppedFrames.coerceAtLeast(0))
            put("reconnectAttempt", value.reconnectAttempt.coerceIn(0, 1_000_000))
            put("network", value.network.transport.name)
        }
        if (send(payload) { accepted ->
            scheduler.post(Runnable {
                if (!accepted && enabled && terminal && !active() && session == sessionId &&
                    sequence == reportSequence && terminalAttempts < 3) scheduler.post(pending, 10_000)
            }, 0)
        }) lastSent = clock()
    }

    fun close() { disconnected(); telemetry = null; started = false }
}

interface StreamReportScheduler {
    fun post(task: Runnable, delayMs: Long)
    fun remove(task: Runnable)
}

private class AndroidStreamReportScheduler : StreamReportScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(task: Runnable, delayMs: Long) { handler.postDelayed(task, delayMs) }
    override fun remove(task: Runnable) { handler.removeCallbacks(task) }
}
