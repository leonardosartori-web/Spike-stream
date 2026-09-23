package com.leonardos.spikestream.streaming

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leonardos.spikestream.activities.StreamActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Camera/microphone soak test against a private RTMP receiver supplied by the operator. */
@RunWith(AndroidJUnit4::class)
class StreamSoakTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    @Test fun sustainedCameraStream() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("rtmpUrl") ?: "rtmp://127.0.0.1:19350/stability"
        val durationMs = (args.getString("durationSeconds")?.toLong() ?: 120L).coerceIn(30, 7_200) * 1_000
        val context = instrumentation.targetContext
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        permissions.forEach {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} $it")).use { stream -> stream.readBytes() }
        }
        val report = File(context.filesDir, "stream-soak.jsonl")
        report.writeText("")
        val intent = Intent(context, StreamActivity::class.java).apply {
            putExtra("RTMP_URL", endpoint)
            putExtra("MATCH_ID", "")
            putExtra("TEAM_1", "TEST A")
            putExtra("TEAM_2", "TEST B")
            putExtra("CAMERA_ID", "0")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<StreamActivity>(intent).use { scenario ->
            lateinit var owner: StreamActivity
            val previewDeadline = SystemClock.elapsedRealtime() + 30_000
            var prepared = false
            while (!prepared && SystemClock.elapsedRealtime() < previewDeadline) {
                scenario.onActivity {
                    owner = it
                    prepared = field(it, "preparedCamera") != null && field(it, "cameraReleased") == false
                }
                if (!prepared) SystemClock.sleep(200)
            }
            assertTrue("Camera/audio preparation failed", prepared)
            scenario.onActivity { activity ->
                StreamActivity::class.java.getDeclaredMethod("startLiveSession", String::class.java,
                    Boolean::class.javaPrimitiveType, StreamQualityMode::class.java).apply { isAccessible = true }
                    .invoke(activity, endpoint,
                        activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
                        StreamQualityMode.AUTO)
            }
            val started = SystemClock.elapsedRealtime()
            var everLive = false
            var everFrames = false
            var background = false
            var resumed = false
            var lastLive = started
            while (SystemClock.elapsedRealtime() - started < durationMs) {
                val elapsed = SystemClock.elapsedRealtime() - started
                // Exercise offscreen rendering after one minute, then restore the preview.
                if (elapsed >= 60_000 && !background) {
                    scenario.moveToState(Lifecycle.State.CREATED)
                    background = true
                }
                if (elapsed >= 80_000 && background && !resumed) {
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    resumed = true
                }
                lateinit var telemetry: StreamTelemetry
                instrumentation.runOnMainSync {
                    val monitor = field(owner, "bitrateMonitor") as StreamBitrateMonitor
                    telemetry = field(monitor, "telemetry") as StreamTelemetry
                }
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val row = JSONObject().apply {
                    put("elapsedMs", elapsed)
                    put("status", telemetry.status.name)
                    put("fps", telemetry.fps)
                    put("bitrate", telemetry.actualBitrate)
                    put("targetBitrate", telemetry.targetVideoBitrate)
                    put("droppedFrames", telemetry.droppedFrames)
                    put("queueItems", telemetry.queueItems)
                    put("detail", telemetry.detail)
                    put("batteryPercent", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
                    put("batteryTemperatureTenthsC", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
                    put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    put("background", background && !resumed)
                }
                report.appendText(row.toString() + "\n")
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SOAK " + row + "\n") })
                assertNotEquals("Stream failed: ${telemetry.detail}", BroadcastStatus.ERROR, telemetry.status)
                assertNotEquals("Session stopped unexpectedly", BroadcastStatus.IDLE, telemetry.status)
                if (telemetry.status == BroadcastStatus.LIVE) {
                    everLive = true
                    lastLive = SystemClock.elapsedRealtime()
                }
                if (telemetry.fps > 0) everFrames = true
                assertTrue("RTMP failed to connect/recover within 45 seconds",
                    SystemClock.elapsedRealtime() - lastLive < 45_000)
                SystemClock.sleep(5_000)
            }
            assertTrue("No successful RTMP connection", everLive)
            assertTrue("No encoded video frames", everFrames)
            report.appendText(JSONObject().put("result", "PASS").put("durationMs", durationMs).toString() + "\n")
        }
    }
}
