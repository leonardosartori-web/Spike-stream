package com.leonardos.spikestream.streaming

/** Monotonic-time policy. A low encoded/sent bitrate alone is NOT congestion. */
class AdaptiveBitratePolicy(private val profile: StreamProfile) {
    data class Sample(
        val nowMs: Long,
        val queueItems: Int,
        val queueCapacity: Int,
        val droppedFrames: Long,
        val sentBytes: Long,
        val encodedFrames: Long,
        val fps: Int,
    )

    data class Decision(
        val target: Int,
        val congested: Boolean,
        val resync: Boolean = false,
        val reconnect: Boolean = false,
        val reason: String = "steady",
    )

    private var connectedAt = 0L
    private var lastAdjustmentAt = 0L
    private var pressureSince: Long? = null
    private var clearSince: Long? = null
    private var stalledSince: Long? = null
    private var lastResyncAt = 0L
    private var lastReconnectAt: Long? = null

    fun connected(nowMs: Long) {
        connectedAt = nowMs
        lastAdjustmentAt = nowMs
        pressureSince = null
        clearSince = null
        stalledSince = null
        lastResyncAt = nowMs - 5_000
        // Keep reconnect cooldown across connections; a bad link must not flap.
    }

    fun sample(s: Sample, currentTarget: Int): Decision {
        val target = currentTarget.coerceIn(profile.minBitrate, profile.maxBitrate)
        val percent = if (s.queueCapacity > 0) s.queueItems * 100 / s.queueCapacity else 0
        val pressure = percent >= 25 || s.droppedFrames > 0
        val severe = percent >= 65 || s.droppedFrames > 0
        pressureSince = if (pressure) pressureSince ?: s.nowMs else null
        val confirmed = pressureSince?.let { s.nowMs - it >= 2_000 } == true
        val warmingUp = s.nowMs - connectedAt < 8_000
        val congested = severe || (!warmingUp && confirmed)
        val stalled = s.sentBytes == 0L && s.encodedFrames > 0 && s.queueItems > 0
        stalledSince = if (stalled) stalledSince ?: s.nowMs else null
        val reconnect = stalledSince?.let { s.nowMs - it >= 8_000 } == true &&
            (lastReconnectAt?.let { s.nowMs - it >= 30_000 } != false)
        if (reconnect) lastReconnectAt = s.nowMs

        // Recover from dropped reference frames / excessive queued latency at an IDR.
        val resync = severe && s.nowMs - lastResyncAt >= 5_000
        if (resync) lastResyncAt = s.nowMs
        if (pressure || warmingUp || s.fps < profile.fps * 8 / 10 || s.sentBytes <= 0) {
            clearSince = null
        } else if (percent <= 5) {
            if (clearSince == null) clearSince = s.nowMs
        } else clearSince = null

        if (congested && s.nowMs - lastAdjustmentAt >= 3_000) {
            lastAdjustmentAt = s.nowMs
            val reduced = (target * if (severe) 0.65 else 0.80).toInt()
                .coerceAtLeast(profile.minBitrate)
            return Decision(reduced, true, resync, reconnect, if (severe) "queue-severe" else "queue-sustained")
        }
        if (clearSince?.let { s.nowMs - it >= 15_000 } == true) {
            clearSince = s.nowMs
            lastAdjustmentAt = s.nowMs
            val increased = maxOf(target + 150_000, (target * 1.10).toInt())
                .coerceAtMost(profile.maxBitrate)
            return Decision(increased, false, reason = "clear-queue-probe")
        }
        return Decision(target, congested, resync, reconnect,
            when { reconnect -> "sender-stalled"; resync -> "queue-resync"; warmingUp -> "warmup"; else -> "steady" })
    }
}
