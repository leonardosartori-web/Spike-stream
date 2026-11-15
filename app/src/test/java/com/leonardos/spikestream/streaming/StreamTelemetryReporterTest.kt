package com.leonardos.spikestream.streaming

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StreamTelemetryReporterTest {
    private class Timer : StreamReportScheduler {
        var now = 0L
        val queue = mutableListOf<Pair<Long, Runnable>>()
        override fun post(task: Runnable, delayMs: Long) { queue.add(now + delayMs to task) }
        override fun remove(task: Runnable) { queue.removeAll { it.second === task } }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = queue.filter { it.first <= end }.minByOrNull { it.first } ?: break
                queue.remove(next); now = next.first; next.second.run()
            }
            now = end
        }
    }
    private class Fixture(val accepted: Boolean = true) {
        val timer = Timer()
        val sent = mutableListOf<JSONObject>()
        val reporter = StreamTelemetryReporter("match", timer, { timer.now }) { payload, ack ->
            sent.add(payload); ack(accepted); true
        }
        fun live(health: StreamHealth = StreamHealth.GOOD, bitrate: Long = 2000000) =
            StreamTelemetry(status = BroadcastStatus.LIVE, health = health, actualBitrate = bitrate, fps = 30)
        fun start() { reporter.connected(); reporter.update(live()); timer.advance(0) }
    }

    @Test fun sendsEveryTenSecondsAndUsesLatestMetrics() {
        val f = Fixture(); f.start(); assertEquals(1, f.sent.size)
        f.reporter.update(f.live(bitrate = 1234000))
        f.timer.advance(9999); assertEquals(1, f.sent.size)
        f.timer.advance(1); assertEquals(2, f.sent.size)
        assertEquals(1234000, f.sent.last().getLong("bitrate"))
    }
    @Test fun changesAreCoalescedAndTerminalStopIsImmediate() {
        val f = Fixture(); f.start()
        f.reporter.update(f.live(StreamHealth.DEGRADED))
        f.reporter.update(f.live(StreamHealth.CRITICAL))
        f.timer.advance(1099); assertEquals(1, f.sent.size)
        f.timer.advance(1); assertEquals("CRITICAL", f.sent.last().getString("health"))
        f.reporter.update(StreamTelemetry())
        assertEquals("STOPPED", f.sent.last().getString("status"))
        val count = f.sent.size
        f.timer.advance(60000); assertEquals(count, f.sent.size)
    }
    @Test fun reconnectSendsOnlyCurrentStateWithoutAnOfflineQueue() {
        val f = Fixture(); f.start(); f.reporter.disconnected()
        f.reporter.update(f.live(StreamHealth.CRITICAL)); f.timer.advance(60000)
        assertEquals(1, f.sent.size)
        f.reporter.update(f.live()); f.reporter.connected(); f.timer.advance(0)
        assertEquals(2, f.sent.size); assertEquals("GOOD", f.sent.last().getString("health"))
        assertEquals(f.sent.first().getString("sessionId"), f.sent.last().getString("sessionId"))
    }
    @Test fun terminalErrorSurvivesIdleCleanupAndFailedFinalReportsHaveBoundedRetries() {
        val f = Fixture(false); f.start()
        f.reporter.update(StreamTelemetry(status = BroadcastStatus.ERROR, health = StreamHealth.CRITICAL))
        f.reporter.update(StreamTelemetry()); f.timer.advance(60000)
        assertEquals(3, f.sent.count { it.getString("status") == "ERROR" })
        assertFalse(f.sent.any { it.getString("status") == "STOPPED" })
    }
    @Test fun startingAgainCreatesNewSessionAndBatteryEventsDoNotReviveStoppedHeartbeat() {
        val f = Fixture(); f.start(); val firstSession = f.sent.last().getString("sessionId")
        f.reporter.battery(10, false); f.timer.advance(1100)
        assertEquals(10, f.sent.last().getInt("battery"))
        f.reporter.update(StreamTelemetry()); val count = f.sent.size
        f.reporter.battery(9, true); f.timer.advance(20000); assertEquals(count, f.sent.size)
        f.reporter.update(f.live()); f.timer.advance(0)
        assertNotEquals(firstSession, f.sent.last().getString("sessionId"))
        assertTrue(f.sent.last().getBoolean("charging"))
    }
}