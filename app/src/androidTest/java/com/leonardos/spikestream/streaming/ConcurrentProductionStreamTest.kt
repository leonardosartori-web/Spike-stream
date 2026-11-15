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
import com.leonardos.spikestream.activities.TokenManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import io.socket.client.Socket

/** Operator-supplied temporary account; runs production Activity, socket and encoder unchanged. */
@RunWith(AndroidJUnit4::class)
class ConcurrentProductionStreamTest {
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun points(target: Any, name: String): Int = target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target) as Int
    @Test fun actualAppStreamsAndReceivesScore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configFile = File(context.filesDir, "concurrent-config.json")
        val config = JSONObject(configFile.readText())
        configFile.delete()
        val endpoint = config.getString("rtmpUrl")
        require(endpoint.startsWith("rtmp://10.0.2.2:"))
        val tokenManager = TokenManager(context)
        runBlocking { tokenManager.saveToken(config.getString("token")) }
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")).use { it.readBytes() }
        }
        val report = File(context.filesDir, "concurrent-state.json")
        val stop = File(context.filesDir, "concurrent-stop")
        val offline = File(context.filesDir, "concurrent-offline")
        stop.delete(); offline.delete()
        val intent = Intent(context, StreamActivity::class.java).apply {
            putExtra("RTMP_URL", endpoint); putExtra("MATCH_ID", config.getString("matchId"))
            putExtra("TEAM_1", config.getString("teamA")); putExtra("TEAM_2", config.getString("teamB"))
            putExtra("CAMERA_ID", "0"); putExtra("OVERLAY_POSITION", "TOP_LEFT")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ActivityScenario.launch<StreamActivity>(intent).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 45000
                var prepared = false
                while (!prepared && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { prepared = field(it, "preparedCamera") != null && field(it, "cameraReleased") == false }
                    if (!prepared) SystemClock.sleep(200)
                }
                assertTrue("Camera preparation failed", prepared)
                scenario.onActivity {
                    it.javaClass.getDeclaredMethod("startLiveSession", String::class.java, Boolean::class.javaPrimitiveType, StreamQualityMode::class.java).apply { isAccessible = true }
                        .invoke(it, endpoint, it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT, StreamQualityMode.STABLE_480)
                }
                val start = SystemClock.elapsedRealtime()
                var everFrames = false
                var everLive = false
                var socketPaused = false
                while (!stop.exists() && SystemClock.elapsedRealtime() - start < 300000) {
                    var row = JSONObject()
                    scenario.onActivity { activity ->
                        val manager = field(activity, "socketManager")!!
                        val socket = field(manager, "socket") as? Socket
                        if (offline.exists() && !socketPaused) { socket?.disconnect(); socketPaused = true }
                        if (!offline.exists() && socketPaused) { socket?.connect(); socketPaused = false }
                        val monitor = field(activity, "bitrateMonitor")!!
                        val telemetry = field(monitor, "telemetry") as StreamTelemetry
                        everFrames = everFrames || telemetry.fps > 0
                        everLive = everLive || telemetry.status == BroadcastStatus.LIVE
                        row = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime()-start)
                            .put("a", points(activity,"getCurrentTeam1Pts")).put("b", points(activity,"getCurrentTeam2Pts"))
                            .put("setsA", points(activity,"getCurrentTeam1Sets")).put("setsB", points(activity,"getCurrentTeam2Sets"))
                            .put("status",telemetry.status.name).put("fps",telemetry.fps).put("bitrate",telemetry.actualBitrate)
                            .put("socketConnected",socket?.connected()==true).put("offlineRequested",socketPaused)
                        assertNotEquals("Stream failed", BroadcastStatus.ERROR, telemetry.status)
                    }
                    val tmp = File(context.filesDir,"concurrent-state.tmp");tmp.writeText(row.toString());tmp.renameTo(report)
                    SystemClock.sleep(500)
                }
                assertTrue("No RTMP connection",everLive);assertTrue("No video frames encoded",everFrames)
                assertTrue("Driver did not complete within 5 minutes",stop.exists())
            }
        } finally { runBlocking { tokenManager.clearToken() }; configFile.delete() }
    }
}
