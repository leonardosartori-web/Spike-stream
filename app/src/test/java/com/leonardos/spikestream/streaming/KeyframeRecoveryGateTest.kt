package com.leonardos.spikestream.streaming

import org.junit.Assert.*
import org.junit.Test

class KeyframeRecoveryGateTest {
    @Test fun startupWaitsForDecodableVideoAndAlignedAudio() {
        val gate = KeyframeRecoveryGate()
        assertFalse(gate.audio(100)); assertFalse(gate.video(false, 100))
        assertTrue(gate.video(true, 200)); assertFalse(gate.audio(199))
        assertTrue(gate.audio(200)); assertTrue(gate.video(false, 201))
    }
    @Test fun resyncDiscardsOldGopAndStaleAudio() {
        val gate = KeyframeRecoveryGate(); gate.video(true, 100)
        gate.reset()
        assertFalse(gate.video(false, 300)); assertFalse(gate.audio(300))
        assertTrue(gate.video(true, 500)); assertFalse(gate.audio(499))
        assertTrue(gate.audio(501)); assertFalse(gate.video(false, 450))
    }
    @Test fun newConnectionCanRestartTimestampEpoch() {
        val gate = KeyframeRecoveryGate(); gate.video(true, 100000)
        gate.reset()
        assertTrue(gate.video(true, 0)); assertTrue(gate.audio(0))
    }
}
