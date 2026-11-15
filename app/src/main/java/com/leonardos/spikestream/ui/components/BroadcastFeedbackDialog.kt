package com.leonardos.spikestream.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.leonardos.spikestream.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BroadcastFeedbackDialog(sessionId: String, onDone: suspend (String?, String?) -> Unit) {
    var describe by rememberSaveable(sessionId) { mutableStateOf(false) }
    var note by rememberSaveable(sessionId) { mutableStateOf("") }
    var saving by remember(sessionId) { mutableStateOf(false) }
    var saveFailed by remember(sessionId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit(rating: String?, description: String? = null) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                onDone(rating, description)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                saveFailed = true
            } finally {
                saving = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { submit(null) },
        title = { Text(stringResource(if (describe) R.string.feedback_description else R.string.feedback_question)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (describe) {
                    OutlinedTextField(value = note, onValueChange = { if (it.length <= 1000) note = it },
                        placeholder = { Text(stringResource(R.string.feedback_hint)) },
                        supportingText = { Text("${note.length}/1000") },
                        minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                } else {
                    Button(enabled = !saving, onClick = { submit("ok") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.feedback_ok))
                    }
                    OutlinedButton(enabled = !saving, onClick = { describe = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.feedback_problem))
                    }
                }
                if (saveFailed) Text(stringResource(R.string.feedback_save_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = { submit(null) }) { Text(stringResource(R.string.feedback_skip)) } },
        confirmButton = { if (describe) TextButton(enabled = !saving && note.isNotBlank(), onClick = { submit("problem", note.trim()) }) {
            Text(stringResource(R.string.feedback_send))
        } },
    )
}
