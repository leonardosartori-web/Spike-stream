package com.leonardos.spikestream.streaming

/** Independent of network callbacks: audio can keep RTMP alive after video stops. */
internal class StreamVideoWatchdog(private val timeoutMs: Long = 15_000L) {
    private var lastProgressAt: Long? = null

    fun start(nowMs: Long) { lastProgressAt = nowMs }
    fun stop() { lastProgressAt = null }
    fun onFps(fps: Int, nowMs: Long) {
        if (fps > 0 && lastProgressAt != null) lastProgressAt = nowMs
    }
    fun hasTimedOut(nowMs: Long): Boolean =
        lastProgressAt?.let { nowMs - it >= timeoutMs } ?: false
}
