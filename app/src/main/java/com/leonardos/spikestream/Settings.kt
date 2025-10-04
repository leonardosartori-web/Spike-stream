package com.leonardos.spikestream

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

class SettingsActivity : ComponentActivity() {

    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tokenManager = TokenManager(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                val tokenState = remember { mutableStateOf<String?>(null) }
                val coroutineScope = rememberCoroutineScope()
                val context = LocalContext.current

                // ascolta il token da DataStore
                LaunchedEffect(Unit) {
                    tokenManager.tokenFlow.collect { token ->
                        tokenState.value = token
                    }
                }

                if (tokenState.value == null) {
                    // loader se il token non c'è
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    SettingsScreen(
                        jwtToken = tokenState.value!!,
                        onAccountDeleted = {
                            coroutineScope.launch {
                                tokenManager.clearToken()   // elimina token
                            }
                            Toast.makeText(
                                context,
                                R.string.account_deleted_success,
                                Toast.LENGTH_LONG
                            ).show()
                            finish() // chiude la SettingsActivity
                        }
                    )
                }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    jwtToken: String,
    onAccountDeleted: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = { showDialog = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            enabled = !isLoading
        ) {
            Text(if (isLoading) stringResource(R.string.deleting) else stringResource(R.string.delete_account))
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.confirm_deletion)) },
            text = { Text(stringResource(R.string.delete_confirmation_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        isLoading = true
                        scope.launch {
                            val success = makeDeleteMe(jwtToken)
                            if (success) {
                                onAccountDeleted()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

suspend fun makeDeleteMe(token: String): Boolean = withContext(
    Dispatchers.IO) {
    try {
        val client = getUnsafeOkHttpClient()
        val request = Request.Builder()
            .url("https://spikestream.tooolky.com/users/me")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()

        val response = client.newCall(request).execute()
        response.isSuccessful

    } catch (e: Exception) {
        false
    }
}
