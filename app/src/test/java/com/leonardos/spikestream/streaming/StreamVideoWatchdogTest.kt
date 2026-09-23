package com.leonardos.spikestream.streaming

import org.junit.Assert.*
import org.junit.Test

class StreamVideoWatchdogTest {
    @Test fun videoMustArriveWithinStartupGrace() {
        val watchdog = StreamVideoWatchdog()
        watchdog.start(100)
        assertFalse(watchdog.hasTimedOut(15_099))
        assertTrue(watchdog.hasTimedOut(15_100))
    }
    @Test fun detectsVideoFreezeEvenWithoutFurtherCallbacks() {
        val watchdog = StreamVideoWatchdog()
        watchdog.start(0)
        watchdog.onFps(30, 1_000)
        assertFalse(watchdog.hasTimedOut(15_999))
        assertTrue(watchdog.hasTimedOut(16_000))
    }
    @Test fun zeroFpsDoesNotKeepDeadEncoderAlive() {
        val watchdog = StreamVideoWatchdog()
        watchdog.start(0)
        for (now in 1_000L..15_000L step 1_000L) watchdog.onFps(0, now)
        assertTrue(watchdog.hasTimedOut(15_000))
    }
    @Test fun temporaryRendererHandoffRecoversWithinGrace() {
        val watchdog = StreamVideoWatchdog()
        watchdog.start(0)
        watchdog.onFps(30, 1_000)
        assertFalse(watchdog.hasTimedOut(8_000))
        watchdog.onFps(24, 9_000)
        assertFalse(watchdog.hasTimedOut(16_000))
    }
    @Test fun stoppedSessionCannotTimeOutOrBeRevivedByLateFps() {
        val watchdog = StreamVideoWatchdog()
        watchdog.start(0)
        watchdog.stop()
        watchdog.onFps(30, 2_000)
        assertFalse(watchdog.hasTimedOut(100_000))
        watchdog.start(100_000)
        assertFalse(watchdog.hasTimedOut(114_999))
        assertTrue(watchdog.hasTimedOut(115_000))
    }
    @Test fun continuousFramesRemainHealthyForThreeHours() {
        val watchdog = StreamVideoWatchdog()
        watchdog.start(0)
        for (now in 1_000L..10_800_000L step 1_000L) {
            watchdog.onFps(25, now)
            assertFalse(watchdog.hasTimedOut(now))
        }
    }
}
