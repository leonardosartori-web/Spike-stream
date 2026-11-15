package com.leonardos.spikestream.streaming

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leonardos.spikestream.activities.StreamActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Local emulator receiver only. Run streaming/validation/rtmp_network_probe.py first. */
@RunWith(AndroidJUnit4::class)
class LocalRtmpTransportTest {
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    @Test fun encodes720pAdaptsAndReconnectsToLocalReceiver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} $permission")).use { it.readBytes() }
        }
        val endpoint = "rtmp://10.0.2.2:19350/live/probe"
        val intent = Intent(context, StreamActivity::class.java).apply {
            putExtra("RTMP_URL", endpoint); putExtra("MATCH_ID", "")
            putExtra("CAMERA_ID", "0"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val report = File(context.filesDir, "local-rtmp-telemetry.jsonl")
        report.writeText("")
        ActivityScenario.launch<StreamActivity>(intent).use { scenario ->
            var prepared = false
            val deadline = SystemClock.elapsedRealtime() + 45_000
            while (!prepared && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { prepared = field(it, "preparedCamera") != null }
                if (!prepared) SystemClock.sleep(200)
            }
            assertTrue("Camera not prepared", prepared)
            scenario.onActivity {
                it.javaClass.getDeclaredMethod("startLiveSession", String::class.java,
                    Boolean::class.javaPrimitiveType, StreamQualityMode::class.java).apply { isAccessible = true }
                    .invoke(it, endpoint, it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
                        StreamQualityMode.HD_720)
            }
            val start = SystemClock.elapsedRealtime()
            var hadLive = false
            var hadRecovery = false
            var resumed = false
            var measuredVideo = false
            var maxTarget = 0
            var minTarget = Int.MAX_VALUE
            var finalStatus = BroadcastStatus.IDLE
            while (SystemClock.elapsedRealtime() - start < 150_000) {
                scenario.onActivity { activity ->
                    val t = field(field(activity, "bitrateMonitor")!!, "telemetry") as StreamTelemetry
                    val ms = SystemClock.elapsedRealtime() - start
                    hadRecovery = hadRecovery || (hadLive && t.status == BroadcastStatus.RECOVERING)
                    hadLive = hadLive || t.status == BroadcastStatus.LIVE
                    resumed = resumed || (hadRecovery && t.status == BroadcastStatus.LIVE && t.actualBitrate > 0)
                    measuredVideo = measuredVideo || (t.encodedVideoBitrate > 100_000 && t.fps >= 20)
                    finalStatus = t.status
                    if (t.status == BroadcastStatus.LIVE) {
                        maxTarget = maxOf(maxTarget, t.targetVideoBitrate)
                        minTarget = minOf(minTarget, t.targetVideoBitrate)
                        assertEquals("720p", t.profileName)
                    }
                    report.appendText(JSONObject().put("ms", ms).put("status", t.status)
                        .put("sent", t.actualBitrate).put("encodedVideo", t.encodedVideoBitrate)
                        .put("encodedAudio", t.encodedAudioBitrate).put("target", t.targetVideoBitrate)
                        .put("fps", t.fps).put("queue", t.queueItems).put("drops", t.droppedFrames)
                        .put("resyncDrops", t.recoveryDroppedFrames).put("reason", t.adaptationReason)
                        .toString() + "\n")
                    assertNotEquals("Fatal streaming error", BroadcastStatus.ERROR, t.status)
                }
                SystemClock.sleep(1000)
            }
            assertTrue("No RTMP publish", hadLive)
            assertTrue("No measured encoded video", measuredVideo)
            assertTrue("Receiver disconnect was not detected", hadRecovery)
            assertTrue("RTMP did not resume after receiver disconnect", resumed)
            assertEquals(BroadcastStatus.LIVE, finalStatus)
            assertTrue("Rate shaping did not exercise adaptation", minTarget < maxTarget)
        }
    }
}
