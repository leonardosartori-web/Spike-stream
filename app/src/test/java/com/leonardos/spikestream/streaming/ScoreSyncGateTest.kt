package com.leonardos.spikestream.streaming

import org.junit.Assert.*
import org.junit.Test

class ScoreSyncGateTest {
    @Test fun snapshotRestoresPointsWhenNoEventWasReceived() {
        val gate = ScoreSyncGate()
        val ticket = gate.beginSnapshot()
        assertTrue(gate.accepts(ticket))
    }
    @Test fun lateHttpResponseCannotOverwriteNewSocketScore() {
        val gate = ScoreSyncGate()
        val request = gate.beginSnapshot()
        gate.invalidate() // score_update delivered while GET was in flight
        assertFalse(gate.accepts(request))
    }
    @Test fun reconnectRejectsResponseFromPreviousConnection() {
        val gate = ScoreSyncGate()
        val previous = gate.beginSnapshot()
        gate.invalidate() // disconnect
        val current = gate.beginSnapshot() // match_joined
        assertFalse(gate.accepts(previous))
        assertTrue(gate.accepts(current))
    }
    @Test fun logoutOrRoomRejectionInvalidatesPendingSnapshot() {
        val gate = ScoreSyncGate()
        val request = gate.beginSnapshot()
        gate.invalidate()
        assertFalse(gate.accepts(request))
    }
}
