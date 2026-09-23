package com.leonardos.spikestream.streaming

/** Main-thread only. Reject snapshots overtaken by events, reconnects or disconnects. */
internal class ScoreSyncGate {
    private var revision = 0L
    fun invalidate() { revision++ }
    fun beginSnapshot(): Long = ++revision
    fun accepts(ticket: Long): Boolean = ticket == revision
}
