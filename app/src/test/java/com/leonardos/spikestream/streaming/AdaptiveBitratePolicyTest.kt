package com.leonardos.spikestream.streaming

import org.junit.Assert.*
import org.junit.Test

class AdaptiveBitratePolicyTest {
    private val profile = StreamProfile.HD_720
    private fun sample(t: Long, q: Int = 0, drops: Long = 0, sent: Long = 400_000,
                       frames: Long = 30, fps: Int = 30) =
        AdaptiveBitratePolicy.Sample(t, q, 200, drops, sent, frames, fps)
    private fun policy() = AdaptiveBitratePolicy(profile).apply { connected(0) }

    @Test fun startupBurstDoesNotImmediatelyReduceBitrate() {
        val p = policy()
        for (t in 1L..7L) {
            val d = p.sample(sample(t * 1000, q = 60), 3_000_000)
            assertFalse(d.congested); assertEquals(3_000_000, d.target)
        }
        assertTrue(p.sample(sample(8000, q = 60), 3_000_000).congested)
    }
    @Test fun severePressureStillProtectsStartup() {
        val d = policy().sample(sample(3000, q = 150), 3_000_000)
        assertTrue(d.congested); assertTrue(d.resync); assertEquals(1_950_000, d.target)
    }
    @Test fun isolatedKeyframeBurstDoesNotTriggerCongestion() {
        val p = policy()
        assertFalse(p.sample(sample(10_000, q = 70), 3_000_000).congested)
        assertFalse(p.sample(sample(11_000), 3_000_000).congested)
    }
    @Test fun sustainedQueueReducesAndCooldownPreventsRepeatedCuts() {
        val p = policy()
        p.sample(sample(10_000, q = 80), 3_000_000)
        val d = p.sample(sample(12_000, q = 80), 3_000_000)
        assertEquals(2_400_000, d.target)
        assertEquals(d.target, p.sample(sample(13_000, q = 80), d.target).target)
    }
    @Test fun recoveryDependsOnElapsedTimeNotCallbackCount() {
        val p = policy()
        repeat(500) { assertEquals(3_000_000, p.sample(sample(10_000), 3_000_000).target) }
        assertEquals(3_000_000, p.sample(sample(24_999), 3_000_000).target)
        assertEquals(3_300_000, p.sample(sample(25_000), 3_000_000).target)
    }
    @Test fun recoveryCanReachFourMbpsWithoutMobileCeiling() {
        val p = policy(); var target = 1_500_000
        for (t in 8L..240L) target = p.sample(sample(t * 1000), target).target
        assertEquals(4_000_000, target)
    }
    @Test fun lowOutputWithoutQueueIsNotNetworkCongestion() {
        val p = policy()
        for (t in 8L..90L) assertFalse(p.sample(sample(t * 1000, sent = 40_000), 3_000_000).congested)
    }
    @Test fun slowEncoderDoesNotProbeForMoreBandwidth() {
        val p = policy()
        for (t in 8L..90L) {
            val d = p.sample(sample(t * 1000, fps = 12, frames = 12), 3_000_000)
            assertFalse(d.congested); assertEquals(3_000_000, d.target)
        }
    }
    @Test fun noOutputCannotCauseUpwardProbes() {
        val p = policy()
        for (t in 8L..90L) assertEquals(3_000_000,
            p.sample(sample(t * 1000, sent = 0, frames = 0), 3_000_000).target)
    }
    @Test fun bitrateNeverFallsBelowProfileFloor() {
        val p = policy(); var target = 3_000_000
        for (t in 1L..90L) target = p.sample(sample(t * 1000, q = 190, drops = 20), target).target
        assertEquals(profile.minBitrate, target)
    }
    @Test fun droppedReferenceFramesTriggerResyncButNotEveryTick() {
        val p = policy()
        assertTrue(p.sample(sample(10_000, drops = 1), 3_000_000).resync)
        assertFalse(p.sample(sample(11_000, drops = 1), 3_000_000).resync)
        assertTrue(p.sample(sample(15_000, drops = 1), 3_000_000).resync)
    }
    @Test fun stalledSenderReconnectsWithCooldownAcrossReconnects() {
        val p = policy()
        assertFalse(p.sample(sample(10_000, q = 100, sent = 0), 3_000_000).reconnect)
        assertTrue(p.sample(sample(18_000, q = 100, sent = 0), 3_000_000).reconnect)
        p.connected(20_000)
        p.sample(sample(21_000, q = 100, sent = 0), 3_000_000)
        assertFalse(p.sample(sample(29_000, q = 100, sent = 0), 3_000_000).reconnect)
        assertTrue(p.sample(sample(48_000, q = 100, sent = 0), 3_000_000).reconnect)
    }
    @Test fun reconnectStartsFreshWarmupAndClearsRecoveryHistory() {
        val p = policy()
        p.sample(sample(10_000), 3_000_000)
        p.connected(25_000)
        assertEquals(3_000_000, p.sample(sample(26_000, q = 70), 3_000_000).target)
        assertFalse(p.sample(sample(27_000, q = 70), 3_000_000).congested)
    }
    @Test fun pressureInterruptsRecoveryWindow() {
        val p = policy()
        p.sample(sample(10_000), 3_000_000)
        p.sample(sample(24_000, q = 70), 3_000_000)
        assertEquals(3_000_000, p.sample(sample(25_000), 3_000_000).target)
        assertEquals(3_000_000, p.sample(sample(39_000), 3_000_000).target)
    }
    @Test fun fifteenMinuteVariableUplinkSimulationRecoversWithoutUnboundedQueue() {
        val p = policy(); var target = 3_000_000; var queued = 0.0
        var reductions = 0; var increases = 0; var maxQueue = 0
        // Model the application queue at 30 video + ~43 AAC frames/s; not a radio/TCP test.
        for (second in 1..900) {
            val uplink = when (second) { in 1..60 -> 5_000_000; in 61..180 -> 1_200_000; else -> 5_000_000 }
            val generated = target + 128_000.0
            val drained = uplink / generated * 73
            val beforeLimit = (queued + 73 - drained).coerceAtLeast(0.0)
            val drops = (beforeLimit - 200).coerceAtLeast(0.0).toLong()
            queued = beforeLimit.coerceAtMost(200.0)
            maxQueue = maxOf(maxQueue, queued.toInt())
            val d = p.sample(sample(second * 1000L, queued.toInt(), drops,
                minOf(uplink.toLong(), generated.toLong()) / 8), target)
            if (d.target < target) reductions++
            if (d.target > target) increases++
            if (d.resync) queued = 0.0
            target = d.target
            assertTrue(target in profile.minBitrate..profile.maxBitrate)
        }
        assertTrue(reductions > 0); assertTrue(increases > 0)
        assertTrue(maxQueue <= 200); assertEquals(4_000_000, target); assertEquals(0.0, queued, 0.01)
    }
}
