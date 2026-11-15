package com.leonardos.spikestream.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.leonardos.spikestream.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class YouTubeControlsLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun compactPortraitAndLandscapeOnlyExposeStartOrStop() {
        val streaming = mutableStateOf(false)
        val landscape = mutableStateOf(false)
        var pauses = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(if (landscape.value) 568.dp else 320.dp, if (landscape.value) 320.dp else 568.dp)) {
                    ManagedYouTubeControls("layout-fixture", streaming.value, { null }, { false }, {}, { pauses++ }, null)
                }
            }
        }
        for (wide in listOf(false, true)) {
            compose.runOnIdle { landscape.value = wide; streaming.value = false }
            compose.onNodeWithText(context.getString(R.string.launch_stream), ignoreCase = true).assertIsDisplayed().performClick()
            compose.onAllNodes(hasClickAction()).assertCountEquals(1)
            compose.onNodeWithText(context.getString(R.string.youtube_finish)).assertDoesNotExist()
            compose.runOnIdle { streaming.value = true }
            compose.onNodeWithText(context.getString(R.string.youtube_pause_video)).assertIsDisplayed().performClick()
            compose.onNodeWithText(context.getString(R.string.youtube_stop_confirm)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
            compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        }
        compose.runOnIdle { assertEquals(0, pauses) }
    }

    @Test fun reopeningMatchRestoresPendingClosureFromBackendState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val visible = mutableStateOf(true)
        val saved = org.json.JSONObject().put("id", "fixture").put("youtubeManaged", true)
            .put("youtubeCloseRequestedAt", "2026-11-02T10:00:00Z")
        val tokens = com.leonardos.spikestream.activities.TokenManager(context)
        compose.setContent {
            MaterialTheme {
                if (visible.value) YouTubeEventDetails("fixture", tokens, loadMatch = { saved })
            }
        }
        compose.onNodeWithText(context.getString(R.string.youtube_retry_close)).assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText(context.getString(R.string.youtube_retry_close)).assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { saved.put("youtubeClosedAt", "2026-11-02T10:01:00Z"); visible.value = true }
        compose.onNodeWithText(context.getString(R.string.youtube_managed_closed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.youtube_retry_close)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.youtube_finish)).assertDoesNotExist()
    }

    @Test fun youtubeChoiceRecommendsStudioWithoutConvertingSavedDestination() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = com.leonardos.spikestream.data.StreamDestination(
            com.leonardos.spikestream.data.StreamPlatform.YOUTUBE, "UI test destination", "rtmps://a.rtmps.youtube.com/live2/test-key", youtubeManaged = true)
        val store = com.leonardos.spikestream.data.StreamDestinationStore(context, "youtube-ui-fixture")
        store.remember(saved)
        var changed = false
        val callbacks = com.facebook.CallbackManager.Factory.create()
        compose.setContent {
            MaterialTheme {
                StreamDestinationPicker("youtube-ui-fixture", callbacks, store, saved, false, false, { changed = true }, {})
            }
        }
        compose.onNodeWithText("YouTube", substring = false).performClick()
        compose.onNodeWithText(context.getString(R.string.youtube_recommended)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.youtube_select_key)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.youtube_configure_event)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.youtube_recommended)).performScrollTo()
        compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "youtube-choice.png").outputStream().use {
            shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.runOnIdle {
            assertEquals(false, changed)
            assertEquals(true, store.recent().first().youtubeManaged)
        }
    }

    @Test fun confirmedStopClosesManagedEventAndRetryNeverStartsVideoAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val streaming = mutableStateOf(false)
        val calls = mutableListOf<Boolean>()
        var starts = 0
        var pauses = 0
        var failClose = true
        compose.setContent {
            MaterialTheme {
                ManagedYouTubeControls("fixture", streaming.value, { "app-fixture" }, { true },
                    { starts++; streaming.value = true }, { pauses++; streaming.value = false }, null,
                    authorizeOverride = { it("google-fixture") },
                    requestOverride = { _, _, finish ->
                        calls.add(finish)
                        if (!finish) com.leonardos.spikestream.data.YouTubeManagedResult.Prepared(
                            com.leonardos.spikestream.data.YouTubeStreamDestination("key", "title", "label", "rtmps://example.test/key", "RTMPS", "inactive"))
                        else if (failClose) com.leonardos.spikestream.data.YouTubeManagedResult.Error("YOUTUBE_CLOSE_PENDING")
                        else com.leonardos.spikestream.data.YouTubeManagedResult.Closed
                    })
            }
        }
        compose.onNodeWithText(context.getString(R.string.launch_stream), ignoreCase = true).performClick()
        compose.onNodeWithText(context.getString(R.string.youtube_pause_video)).performClick()
        compose.onNodeWithText(context.getString(R.string.youtube_stop_confirm_action)).performClick()
        compose.onNodeWithText(context.getString(R.string.youtube_retry_close)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(false, true), calls); assertEquals(1, starts); assertEquals(1, pauses); failClose = false }
        compose.onNodeWithText(context.getString(R.string.youtube_retry_close)).performClick()
        compose.onNodeWithText(context.getString(R.string.youtube_stop_confirm_action)).performClick()
        compose.onNodeWithText(context.getString(R.string.youtube_managed_closed)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(false, true, true), calls); assertEquals(1, starts) }
    }

    @Test fun rejectedAccessShowsLockAndNeverStartsVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var starts = 0
        compose.setContent {
            MaterialTheme {
                ManagedYouTubeControls("fixture", false, { "app-fixture" }, { true }, { starts++ }, {}, null,
                    authorizeOverride = { it("google-fixture") },
                    requestOverride = { _, _, _ -> com.leonardos.spikestream.data.YouTubeManagedResult.Error("YOUTUBE_MANAGED_ACCESS_REQUIRED") })
            }
        }
        compose.onNodeWithText(context.getString(R.string.launch_stream), ignoreCase = true).performClick()
        compose.onNodeWithText(context.getString(R.string.account_saved_match_locked)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.account_plans)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test fun failedPreparationDoesNotStartVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var starts = 0
        compose.setContent {
            MaterialTheme {
                ManagedYouTubeControls("fixture", false, { "app-fixture" }, { true }, { starts++ }, {}, null,
                    authorizeOverride = { it("google-fixture") },
                    requestOverride = { _, _, _ -> com.leonardos.spikestream.data.YouTubeManagedResult.Error("YOUTUBE_UNAVAILABLE") })
            }
        }
        compose.onNodeWithText(context.getString(R.string.launch_stream), ignoreCase = true).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, starts) }
    }
}
