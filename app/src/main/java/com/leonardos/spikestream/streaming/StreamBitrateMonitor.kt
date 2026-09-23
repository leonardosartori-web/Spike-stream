package com.leonardos.spikestream.streaming

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.leonardos.spikestream.utils.Logger as Log
import com.pedro.common.socket.base.SocketType
import com.pedro.library.rtmp.RtmpCamera2
import kotlin.math.max
import kotlin.math.min

enum class BroadcastStatus(val label: String) {
    IDLE("PRONTO"),
    CONNECTING("CONNESSIONE"),
    LIVE("LIVE"),
    RECOVERING("RIPRISTINO"),
    WAITING_NETWORK("IN ATTESA DI RETE"),
    ERROR("ERRORE"),
}

enum class StreamHealth {
    GOOD,
    DEGRADED,
    CRITICAL,
}

data class StreamTelemetry(
    val status: BroadcastStatus = BroadcastStatus.IDLE,
    val health: StreamHealth = StreamHealth.GOOD,
    val network: NetworkSnapshot = NetworkSnapshot.disconnected(),
    val actualBitrate: Long = 0L,
    val targetVideoBitrate: Int = 0,
    val fps: Int = 0,
    val droppedFrames: Long = 0L,
    val queueItems: Int = 0,
    val queueCapacity: Int = 0,
    val profileName: String = "",
    val reconnectAttempt: Int = 0,
    val sessionStartedAtMs: Long = 0L,
    val detail: String = "",
) {
    val elapsedSeconds: Long
        get() = if (sessionStartedAtMs == 0L) 0L
        else ((SystemClock.elapsedRealtime() - sessionStartedAtMs) / 1_000L).coerceAtLeast(0L)
}

/**
 * Owns the runtime network policy for one RTMP session:
 *
 * - validated default-network monitoring;
 * - fast RTMP reconnect after Wi-Fi/mobile hand-off;
 * - sender-queue and dropped-frame based bitrate adaptation;
 * - a stable telemetry model for the operator UI.
 *
 * Resolution is never changed while live. A resolution restart is much more
 * disruptive to YouTube/Facebook/Twitch than temporary bitrate reduction.
 */
class StreamBitrateMonitor(
    context: Context,
    private val cameraProvider: () -> RtmpCamera2?,
    private val onTelemetry: (StreamTelemetry) -> Unit,
    private val onFatalError: (String) -> Unit,
) {
    companion object {
        private const val RTMP_CACHE_FRAMES = 200
        private const val CONGESTION_PERCENT = 25f
        private const val SEVERE_CONGESTION_PERCENT = 50
        private const val CONGESTION_CONFIRMATION_SAMPLES = 2
        private const val DROPPED_FRAMES_PRESSURE = 2L
        private const val DROPPED_FRAMES_SEVERE = 8L
        private const val LOW_FPS_CONFIRM_MS = 3_000L
        private const val HEALTH_RECOVERY_MS = 8_000L
        private const val ADJUST_COOLDOWN_MS = 3_000L
        private const val RECOVERY_WINDOW_SAMPLES = 15
        private const val SOCKET_TIMEOUT_MS = 12_000L
        private const val NETWORK_LOSS_GRACE_MS = 2_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkMonitor = ValidatedNetworkMonitor(context) { handleNetworkChanged(it) }

    private var telemetry = StreamTelemetry()
    private var profile: StreamProfile? = null
    private var sessionActive = false
    private var intentionalStop = false
    private var connectionLive = false
    private var retryScheduled = false
    private var pendingRetryReason: String? = null
    private var reconnectAttempt = 0
    private var lastAdjustmentAt = 0L
    private var stableSamples = 0
    private var lastDroppedFrames = 0L
    private var totalDroppedFrames = 0L
    private var congestionSamples = 0
    private var lowFpsStartedAt = 0L
    private var lastDegradedAt = 0L
    private var pendingNetworkLoss: Runnable? = null
    private val videoWatchdog = StreamVideoWatchdog()
    private val watchdogTick = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            if (videoWatchdog.hasTimedOut(SystemClock.elapsedRealtime())) {
                failPermanently("Il video non produce frame da 15 secondi")
                return
            }
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    fun onMediaError(reason: String) {
        runOnMain { if (sessionActive) failPermanently(reason) }
    }

    private fun stopWatchdog() {
        mainHandler.removeCallbacks(watchdogTick)
        videoWatchdog.stop()
    }

    fun startObserving() {
        networkMonitor.start()
    }

    fun stopObserving() {
        stopWatchdog()
        cancelPendingNetworkLoss()
        networkMonitor.stop()
    }

    /**
     * Must be invoked immediately before startStream.
     */
    fun beginSession(selectedProfile: StreamProfile) {
        runOnMain {
            val camera = cameraProvider() ?: run {
                onFatalError("Camera non inizializzata")
                return@runOnMain
            }
            val client = camera.streamClient

            runCatching {
                client.setReTries(Int.MAX_VALUE)
                client.setSocketType(SocketType.JAVA)
                client.setSocketTimeout(SOCKET_TIMEOUT_MS)
                // RootEncoder implements this check with ICMP/Echo. CDNs and
                // mobile networks commonly block Echo even while RTMP is healthy.
                client.setCheckServerAlive(false)
                // RootEncoder 2.8.0 schedules client pings in a separate coroutine
                // without catching socket errors (RtmpClient.handleMessages).
                // A disconnect/retry can close its socket while a ping is pending,
                // causing an uncaught SocketException and killing the whole app.
                // Keep the library default: server ping replies and read/write
                // failure detection still work without unsolicited client pings.
                client.shouldSendPings(false)
                client.shouldFailOnRead(true)
                // Keep the operator readout stable without hiding changes for
                // several seconds after a hand-off or an adaptive adjustment.
                client.setBitrateExponentialFactor(0.2f)
                client.resizeCache(RTMP_CACHE_FRAMES)
                client.clearCache()
                // RootEncoder can include endpoints in verbose logs; keep stream keys private.
                client.setLogs(false)
            }.onFailure {
                Log.e("StreamResilience", "Configurazione client RTMP non riuscita", it)
            }

            profile = selectedProfile
            sessionActive = true
            stopWatchdog()
            videoWatchdog.start(SystemClock.elapsedRealtime())
            mainHandler.postDelayed(watchdogTick, 1_000L)
            intentionalStop = false
            connectionLive = false
            retryScheduled = false
            pendingRetryReason = null
            reconnectAttempt = 0
            lastAdjustmentAt = 0L
            stableSamples = 0
            lastDroppedFrames = 0L
            totalDroppedFrames = 0L
            congestionSamples = 0
            lowFpsStartedAt = 0L
            lastDegradedAt = 0L

            publish(
                telemetry.copy(
                    status = BroadcastStatus.CONNECTING,
                    health = StreamHealth.DEGRADED,
                    actualBitrate = 0L,
                    targetVideoBitrate = selectedProfile.startBitrate,
                    fps = 0,
                    droppedFrames = 0L,
                    queueItems = 0,
                    queueCapacity = RTMP_CACHE_FRAMES,
                    profileName = selectedProfile.name,
                    reconnectAttempt = 0,
                    sessionStartedAtMs = SystemClock.elapsedRealtime(),
                    detail = "Apertura canale RTMP",
                )
            )
        }
    }

    fun endSession() {
        runOnMain {
            stopWatchdog()
            cancelPendingNetworkLoss()
            intentionalStop = true
            sessionActive = false
            connectionLive = false
            retryScheduled = false
            pendingRetryReason = null
            profile = null
            publish(
                StreamTelemetry(
                    status = BroadcastStatus.IDLE,
                    network = telemetry.network,
                )
            )
        }
    }

    fun onConnectionStarted() {
        runOnMain {
            if (!sessionActive) return@runOnMain
            retryScheduled = false
            publish(
                telemetry.copy(
                    status = if (reconnectAttempt == 0) {
                        BroadcastStatus.CONNECTING
                    } else {
                        BroadcastStatus.RECOVERING
                    },
                    health = StreamHealth.DEGRADED,
                    detail = if (reconnectAttempt == 0) {
                        "Negoziazione con la piattaforma"
                    } else {
                        "Tentativo $reconnectAttempt"
                    },
                )
            )
        }
    }

    fun onConnectionSuccess() {
        runOnMain {
            if (!sessionActive) return@runOnMain
            connectionLive = true
            retryScheduled = false
            pendingRetryReason = null
            reconnectAttempt = 0
            stableSamples = 0
            congestionSamples = 0
            lowFpsStartedAt = 0L
            lastDegradedAt = 0L
            cameraProvider()?.requestKeyFrame()
            publish(
                telemetry.copy(
                    status = BroadcastStatus.LIVE,
                    health = StreamHealth.GOOD,
                    reconnectAttempt = 0,
                    detail = "Trasmissione stabile",
                )
            )
        }
    }

    fun onConnectionFailed(reason: String) {
        runOnMain {
            if (!sessionActive || intentionalStop) return@runOnMain
            connectionLive = false
            pendingRetryReason = reason

            if (isPermanentFailure(reason)) {
                failPermanently(reason)
            } else if (!telemetry.network.hasUsableInternet) {
                publish(
                    telemetry.copy(
                        status = BroadcastStatus.WAITING_NETWORK,
                        health = StreamHealth.CRITICAL,
                        detail = "La diretta riparte appena torna Internet",
                    )
                )
            } else {
                scheduleRetry(reason, retryDelayMs(reconnectAttempt))
            }
        }
    }

    fun onAuthError() {
        runOnMain {
            if (sessionActive) failPermanently("Chiave stream o autorizzazione non valida")
        }
    }

    fun onDisconnect() {
        runOnMain {
            if (!sessionActive || intentionalStop) return@runOnMain
            connectionLive = false
            publish(
                telemetry.copy(
                    status = BroadcastStatus.RECOVERING,
                    health = StreamHealth.CRITICAL,
                    detail = "Collegamento interrotto, ripristino automatico",
                )
            )
        }
    }

    fun onFps(fps: Int) {
        runOnMain {
            if (!sessionActive) return@runOnMain
            videoWatchdog.onFps(fps, SystemClock.elapsedRealtime())
            publish(telemetry.copy(fps = fps, health = calculateHealth(fps = fps)))
        }
    }

    fun onNewBitrate(bitrate: Long) {
        runOnMain {
            if (!sessionActive) return@runOnMain
            val selectedProfile = profile ?: return@runOnMain
            val client = cameraProvider()?.streamClient ?: return@runOnMain

            val queueItems = runCatching { client.getItemsInCache() }.getOrDefault(0)
            val queueCapacity = runCatching { client.getCacheSize() }.getOrDefault(RTMP_CACHE_FRAMES)
            val rawDropped = runCatching {
                client.getDroppedAudioFrames() + client.getDroppedVideoFrames()
            }.getOrDefault(0L)
            val droppedDelta = if (rawDropped >= lastDroppedFrames) {
                rawDropped - lastDroppedFrames
            } else {
                // Sender counters reset after a reconnect.
                rawDropped
            }
            lastDroppedFrames = rawDropped
            totalDroppedFrames += droppedDelta

            val senderUnderPressure = runCatching {
                client.hasCongestion(CONGESTION_PERCENT)
            }.getOrDefault(false) || droppedDelta >= DROPPED_FRAMES_PRESSURE
            congestionSamples = if (senderUnderPressure) {
                (congestionSamples + 1).coerceAtMost(CONGESTION_CONFIRMATION_SAMPLES)
            } else {
                0
            }
            val queuePercent = if (queueCapacity > 0) {
                queueItems * 100 / queueCapacity
            } else {
                0
            }
            val severeCongestion =
                queuePercent >= SEVERE_CONGESTION_PERCENT ||
                    droppedDelta >= DROPPED_FRAMES_SEVERE
            val congested =
                severeCongestion ||
                    congestionSamples >= CONGESTION_CONFIRMATION_SAMPLES

            val target = adaptBitrate(
                congested = congested,
                selectedProfile = selectedProfile,
            )

            publish(
                telemetry.copy(
                    actualBitrate = bitrate,
                    targetVideoBitrate = target,
                    droppedFrames = totalDroppedFrames,
                    queueItems = queueItems,
                    queueCapacity = queueCapacity,
                    health = calculateHealth(congested = congested),
                    detail = when {
                        telemetry.status != BroadcastStatus.LIVE -> telemetry.detail
                        congested -> "Rete congestionata: qualità adattata"
                        target < selectedProfile.startBitrate -> "Qualità adattiva"
                        else -> "Trasmissione stabile"
                    },
                )
            )
        }
    }

    private fun handleNetworkChanged(snapshot: NetworkSnapshot) {
        runOnMain {
            if (!sessionActive) {
                cancelPendingNetworkLoss()
                publish(telemetry.copy(network = snapshot))
                return@runOnMain
            }

            if (!snapshot.hasUsableInternet) {
                scheduleNetworkLoss(snapshot)
                return@runOnMain
            }

            cancelPendingNetworkLoss()
            val previous = telemetry.network
            val previousStatus = telemetry.status
            publish(telemetry.copy(network = snapshot))

            val selectedProfile = profile ?: return@runOnMain
            val recoveredInternet = !previous.hasUsableInternet && snapshot.hasUsableInternet
            val transportChanged =
                previous.transport != NetworkTransport.NONE &&
                    previous.transport != snapshot.transport
            val defaultNetworkChanged =
                previous.networkHandle != 0L &&
                    snapshot.networkHandle != 0L &&
                    previous.networkHandle != snapshot.networkHandle

            if (transportChanged || defaultNetworkChanged) {
                // TCP cannot migrate to another Android Network, even when both
                // routes are Wi-Fi. Reconnect on the new default route instead of
                // waiting for the old socket timeout.
                val safeTarget = min(telemetry.targetVideoBitrate, bitrateCeiling(selectedProfile))
                    .coerceAtLeast(selectedProfile.minBitrate)
                setTargetBitrate(safeTarget)
                pendingRetryReason = if (transportChanged) {
                    "Cambio rete ${previous.transport} → ${snapshot.transport}"
                } else {
                    "Nuova rete ${snapshot.transport}"
                }
                scheduleRetry(pendingRetryReason!!, 250L)
            } else if (
                recoveredInternet &&
                !connectionLive &&
                (previousStatus == BroadcastStatus.WAITING_NETWORK || pendingRetryReason != null)
            ) {
                // Do not restart the first RTMP negotiation merely because its
                // initial network snapshot was delivered a few milliseconds late.
                setTargetBitrate(
                    min(selectedProfile.startBitrate, bitrateCeiling(selectedProfile))
                )
                scheduleRetry(pendingRetryReason ?: "Rete ripristinata", 250L)
            }
        }
    }

    /**
     * Android can briefly remove NET_CAPABILITY_VALIDATED while keeping the
     * same usable link. Waiting a short grace period prevents needless RTMP
     * reconnects without slowing a real Wi-Fi/mobile hand-off.
     */
    private fun scheduleNetworkLoss(snapshot: NetworkSnapshot) {
        if (pendingNetworkLoss != null) return

        val runnable = Runnable {
            pendingNetworkLoss = null
            if (!sessionActive) return@Runnable

            val selectedProfile = profile ?: return@Runnable
            connectionLive = false
            pendingRetryReason = pendingRetryReason ?: "Internet non disponibile"
            setTargetBitrate(selectedProfile.minBitrate)
            publish(
                telemetry.copy(
                    network = snapshot,
                    status = BroadcastStatus.WAITING_NETWORK,
                    health = StreamHealth.CRITICAL,
                    detail = "In attesa di una rete valida",
                )
            )
        }
        pendingNetworkLoss = runnable
        mainHandler.postDelayed(runnable, NETWORK_LOSS_GRACE_MS)
    }

    private fun cancelPendingNetworkLoss() {
        pendingNetworkLoss?.let(mainHandler::removeCallbacks)
        pendingNetworkLoss = null
    }

    private fun scheduleRetry(reason: String, delayMs: Long) {
        if (retryScheduled || !sessionActive) return
        val camera = cameraProvider() ?: return
        if (!camera.isStreaming) {
            failPermanently("Encoder non più attivo")
            return
        }

        reconnectAttempt++
        val scheduled = runCatching {
            camera.streamClient.reTry(delayMs, reason, null)
        }.getOrElse {
            Log.e("StreamResilience", "Errore durante il retry RTMP", it)
            false
        }

        if (scheduled) {
            retryScheduled = true
            publish(
                telemetry.copy(
                    status = BroadcastStatus.RECOVERING,
                    health = StreamHealth.CRITICAL,
                    reconnectAttempt = reconnectAttempt,
                    detail = "Ripristino automatico tra ${(delayMs / 1_000f)} s",
                )
            )
        } else {
            failPermanently(reason)
        }
    }

    private fun adaptBitrate(
        congested: Boolean,
        selectedProfile: StreamProfile,
    ): Int {
        val now = SystemClock.elapsedRealtime()
        val currentTarget = telemetry.targetVideoBitrate
            .takeIf { it > 0 }
            ?: selectedProfile.startBitrate
        val canAdjust = now - lastAdjustmentAt >= ADJUST_COOLDOWN_MS

        return when {
            canAdjust && congested -> {
                stableSamples = 0
                lastAdjustmentAt = now
                val reduced = max(selectedProfile.minBitrate, (currentTarget * 0.78f).toInt())
                setTargetBitrate(reduced)
                reduced
            }
            congested -> {
                stableSamples = 0
                currentTarget
            }
            else -> {
                stableSamples++
                if (canAdjust && stableSamples >= RECOVERY_WINDOW_SAMPLES) {
                    stableSamples = 0
                    lastAdjustmentAt = now
                    val ceiling = bitrateCeiling(selectedProfile)
                    val increased = min(
                        ceiling,
                        max(currentTarget + 100_000, (currentTarget * 1.08f).toInt()),
                    )
                    setTargetBitrate(increased)
                    increased
                } else {
                    currentTarget
                }
            }
        }
    }

    private fun bitrateCeiling(selectedProfile: StreamProfile): Int {
        // linkUpstreamBandwidthKbps describes the local link and is especially
        // unreliable in emulators. Real sender congestion is the source of truth.
        return (if (telemetry.network.transport == NetworkTransport.CELLULAR) {
            val mobileCeiling = if (selectedProfile == StreamProfile.HD_720) {
                1_850_000
            } else {
                1_400_000
            }
            min(selectedProfile.maxBitrate, mobileCeiling)
        } else {
            selectedProfile.maxBitrate
        }).coerceAtLeast(selectedProfile.minBitrate)
    }

    private fun setTargetBitrate(bitrate: Int) {
        if (bitrate <= 0 || bitrate == telemetry.targetVideoBitrate) return
        runCatching { cameraProvider()?.setVideoBitrateOnFly(bitrate) }
            .onFailure { Log.e("StreamResilience", "Cambio bitrate fallito", it) }
        telemetry = telemetry.copy(targetVideoBitrate = bitrate)
    }

    private fun calculateHealth(
        fps: Int = telemetry.fps,
        congested: Boolean = false,
    ): StreamHealth {
        val selectedProfile = profile
        val now = SystemClock.elapsedRealtime()
        val critical =
            telemetry.status == BroadcastStatus.WAITING_NETWORK ||
                telemetry.status == BroadcastStatus.ERROR ||
                telemetry.status == BroadcastStatus.RECOVERING
        if (critical) return StreamHealth.CRITICAL

        if (congested) {
            lastDegradedAt = now
            return StreamHealth.DEGRADED
        }

        val lowFps =
            selectedProfile != null &&
                fps >= 0 &&
                fps < selectedProfile.fps * 7 / 10
        if (lowFps) {
            if (lowFpsStartedAt == 0L) lowFpsStartedAt = now
            if (now - lowFpsStartedAt >= LOW_FPS_CONFIRM_MS) {
                lastDegradedAt = now
                return StreamHealth.DEGRADED
            }
        } else {
            lowFpsStartedAt = 0L
        }

        return when {
            telemetry.health == StreamHealth.DEGRADED &&
                now - lastDegradedAt < HEALTH_RECOVERY_MS -> StreamHealth.DEGRADED
            else -> StreamHealth.GOOD
        }
    }

    private fun retryDelayMs(attempt: Int): Long {
        val schedule = longArrayOf(1_000L, 2_000L, 4_000L, 7_000L, 12_000L, 20_000L)
        return schedule[attempt.coerceIn(0, schedule.lastIndex)]
    }

    private fun isPermanentFailure(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized.contains("endpoint malformed") ||
            normalized.contains("url malformed") ||
            normalized.contains("publish failed") ||
            normalized.contains("bad name")
    }

    private fun failPermanently(reason: String) {
        stopWatchdog()
        cancelPendingNetworkLoss()
        sessionActive = false
        connectionLive = false
        retryScheduled = false
        publish(
            telemetry.copy(
                status = BroadcastStatus.ERROR,
                health = StreamHealth.CRITICAL,
                detail = reason,
            )
        )
        onFatalError(reason)
    }

    private fun publish(value: StreamTelemetry) {
        telemetry = value
        onTelemetry(value)
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
}
