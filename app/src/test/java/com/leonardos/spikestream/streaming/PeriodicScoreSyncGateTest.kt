package com.leonardos.spikestream.streaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicScoreSyncGateTest {
    @Test fun websocketEventRejectsOldSnapshotButAllowsNextPeriodicRecovery() {
        val gate = ScoreSyncGate()
        val beforeEvent = gate.beginSnapshot()
        gate.invalidate()
        assertFalse(gate.accepts(beforeEvent))
        val nextPoll = gate.beginSnapshot()
        assertTrue(gate.accepts(nextPoll))
        assertFalse(gate.accepts(beforeEvent))
    }

    @Test fun disconnectRejectsInflightResultButAllowsHttpRecoveryWhileSocketIsOffline() {
        val gate = ScoreSyncGate()
        val beforeDisconnect = gate.beginSnapshot()
        gate.invalidate()
        val offlinePoll = gate.beginSnapshot()
        assertFalse(gate.accepts(beforeDisconnect))
        assertTrue(gate.accepts(offlinePoll))
        gate.invalidate() // explicit stop / token replacement
        assertFalse(gate.accepts(offlinePoll))
    }
}
