package com.leonardos.spikestream.streaming

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leonardos.spikestream.activities.StreamActivity
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only an unreachable loopback RTMP endpoint; never publishes a public broadcast. */
@RunWith(AndroidJUnit4::class)
class StreamLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun grantMediaPermissions() {
        val pkg = instrumentation.targetContext.packageName
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS).forEach { permission ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("pm grant $pkg $permission")
            ).use { it.readBytes() }
        }
    }

    private fun launch(): ActivityScenario<StreamActivity> {
        val context = instrumentation.targetContext
        return ActivityScenario.launch(Intent(context, StreamActivity::class.java).apply {
            putExtra("RTMP_URL", "rtmp://127.0.0.1:1/live/lifecycle-test")
            putExtra("MATCH_ID", "") // No account or score service needed by these tests.
            putExtra("CAMERA_ID", "0")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun field(activity: StreamActivity, name: String): Any? =
        StreamActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun camera(activity: StreamActivity) = field(activity, "rtmpCamera") as? RtmpCamera2

    private fun awaitState(scenario: ActivityScenario<StreamActivity>, message: String,
        predicate: (StreamActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        do {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail(message)
    }

    @Test fun rotationReleasesPreviousCameraAndPreparesNewPreview() {
        launch().use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            awaitState(scenario, "Portrait preview unavailable") { camera(it)?.isOnPreview == true }
            lateinit var oldCamera: RtmpCamera2
            scenario.onActivity {
                oldCamera = camera(it)!!
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            awaitState(scenario, "Rotation did not replace and prepare camera") {
                camera(it) !== oldCamera && camera(it)?.isOnPreview == true
            }
            scenario.onActivity {
                assertFalse("Old camera remains open", oldCamera.isOnPreview)
                assertFalse(oldCamera.isStreaming)
            }
        }
    }

    @Test fun repeatedBackgroundResumeReleasesAndRecreatesPreview() {
        launch().use { scenario ->
            repeat(3) {
                awaitState(scenario, "Preview unavailable") { camera(it)?.isOnPreview == true }
                lateinit var previous: RtmpCamera2
                scenario.onActivity { previous = camera(it)!! }
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.runOnMainSync {
                    assertFalse("Stopped Activity holds camera", previous.isOnPreview)
                    assertFalse(previous.isStreaming)
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitState(scenario, "Preview did not resume") {
                    camera(it) !== previous && camera(it)?.isOnPreview == true
                }
            }
        }
    }

    @Test fun fatalErrorInBackgroundRestoresVisiblePreview() {
        launch().use { scenario ->
            awaitState(scenario, "Preview unavailable") { camera(it)?.isOnPreview == true }
            lateinit var owner: StreamActivity
            scenario.onActivity { activity ->
                owner = activity
                StreamActivity::class.java.getDeclaredMethod("startLiveSession",
                    String::class.java, Boolean::class.javaPrimitiveType,
                    StreamQualityMode::class.java).apply { isAccessible = true }
                    .invoke(activity, "rtmp://127.0.0.1:1/live/lifecycle-test",
                        activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT,
                        StreamQualityMode.AUTO)
                assertTrue("Local encoder did not start", camera(activity)!!.isStreaming)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync {
                assertEquals(false, field(owner, "activityVisible"))
                assertFalse(camera(owner)!!.glInterface is OpenGlView)
                (field(owner, "bitrateMonitor") as StreamBitrateMonitor).onAuthError()
                assertEquals(true, field(owner, "cameraReleased"))
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitState(scenario, "Fatal error left preview offscreen") {
                camera(it)?.isOnPreview == true && camera(it)?.glInterface is OpenGlView &&
                    camera(it)?.isStreaming == false
            }
        }
    }
}


