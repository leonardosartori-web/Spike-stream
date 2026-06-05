package com.leonardos.spikestream.activities

import com.leonardos.spikestream.ui.theme.MyApplicationTheme
import com.leonardos.spikestream.ui.theme.SpikeStreamScreen
import com.leonardos.spikestream.ui.theme.SpikeStreamDangerButton
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.text.font.FontWeight
import com.leonardos.spikestream.ui.theme.SpikeStreamGlassCard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import com.leonardos.spikestream.ui.theme.SpikeStreamDialog
import android.content.Intent
import com.leonardos.spikestream.R
import com.leonardos.spikestream.utils.TourManager
import com.leonardos.spikestream.data.*

class SettingsActivity : ComponentActivity() {

    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tokenManager = TokenManager(applicationContext)

        setContent {
            MyApplicationTheme {
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
                    SpikeStreamScreen(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
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
    val context = LocalContext.current

    SpikeStreamScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            Text(
                stringResource(R.string.account_settings_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black)
            )

            Spacer(Modifier.height(40.dp))

            SpikeStreamGlassCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.settings_replay_tour),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            TourManager.resetAll(context)
                            Toast.makeText(context, "Tutorial reset", Toast.LENGTH_SHORT).show()
                            // Riavvia MainActivity per ricaricare il tour della Dashboard
                            val intent = Intent(context, MainActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_replay_tour), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SpikeStreamGlassCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.delete_account),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.delete_account_warning),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(24.dp))
                    SpikeStreamDangerButton(
                        text = if (isLoading) stringResource(R.string.deleting) else stringResource(
                            R.string.delete_account),
                        onClick = { showDialog = true },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showDialog) {
        SpikeStreamDialog(
            onDismissRequest = { showDialog = false },
            title = stringResource(R.string.confirm_deletion),
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp)) },
            content = {
                Text(
                    text = stringResource(R.string.delete_confirmation_text),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDialog = false
                        isLoading = true
                        scope.launch {
                            val success = StreamApi.makeDeleteMe(jwtToken)
                            if (success) {
                                onAccountDeleted()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.delete_account), color = Color.White, fontWeight = FontWeight.Bold)
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
