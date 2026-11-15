package com.leonardos.spikestream.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.leonardos.spikestream.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BroadcastFeedbackDialogTest {
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun problemUsesFreeTextAndRetainsItOnLocalSaveFailure() {
        val visible = mutableStateOf(true)
        var attempts = 0
        var saved: Pair<String?, String?>? = null
        compose.setContent {
            MaterialTheme {
                if (visible.value) BroadcastFeedbackDialog("session") { rating, note ->
                    attempts++
                    if (attempts == 1) throw IllegalStateException("test write failure")
                    saved = rating to note
                    visible.value = false
                }
            }
        }
        compose.onNodeWithText(label(R.string.feedback_problem)).performClick()
        compose.onNodeWithText(label(R.string.feedback_send)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("  Audio interrotto  ")
        compose.onNodeWithText(label(R.string.feedback_send)).performClick()
        compose.onNodeWithText(label(R.string.feedback_save_failed)).assertExists()
        compose.onNodeWithText(label(R.string.feedback_send)).performClick()
        compose.runOnIdle { assertEquals("problem" to "Audio interrotto", saved) }
    }

    @Test fun positiveAndSkipTakeOneTap() {
        val session = mutableStateOf("first")
        val answers = mutableListOf<Pair<String?, String?>>()
        compose.setContent {
            MaterialTheme {
                BroadcastFeedbackDialog(session.value) { rating, note ->
                    answers.add(rating to note)
                    session.value = "second"
                }
            }
        }
        compose.onNodeWithText(label(R.string.feedback_ok)).performClick()
        compose.onNodeWithText(label(R.string.feedback_skip)).performClick()
        compose.runOnIdle { assertEquals(listOf("ok" to null, null to null), answers) }
    }
}
