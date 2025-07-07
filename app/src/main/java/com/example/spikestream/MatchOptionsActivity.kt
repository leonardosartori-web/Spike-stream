package com.example.spikestream

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.spikestream.ui.theme.MyApplicationTheme
import getUnsafeOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject

sealed class InviteResult {
    data class Success(val link: String) : InviteResult()
    data class Error(val message: String) : InviteResult()
}

class MatchOptionsActivity : ComponentActivity() {

    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenManager = TokenManager(applicationContext)

        val teamA = intent.getStringExtra("TEAM_1") ?: ""
        val teamB = intent.getStringExtra("TEAM_2") ?: ""
        val rtmpUrl = intent.getStringExtra("RTMP_URL") ?: ""
        val matchId = intent.getStringExtra("MATCH_ID") ?: ""

        setContent {
            MyApplicationTheme {
                val tokenState = remember { mutableStateOf<String?>(null) }
                val coroutineScope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    tokenManager.tokenFlow.collect { token ->
                        tokenState.value = token
                    }
                }

                val showRegister = remember { mutableStateOf(false) }

                if (tokenState.value == null) {
                    // Mostra la login
                    LoginScreen(onLoginSuccess = { newToken ->
                        coroutineScope.launch {
                            tokenManager.saveToken(newToken)
                            tokenState.value = newToken
                        }
                    },
                        onRegisterClick = {
                            showRegister.value = true
                        },
                        onGoogleLoginClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://spikestream.tooolky.com/auth/google")
                            )
                            startActivity(intent)
                        },
                        onForgotPasswordClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://spikestream.tooolky.com/auth/reset-password")
                            )
                            startActivity(intent)
                        }
                    )
                } else {
                    MatchOptionsScreen(teamA, teamB, rtmpUrl, matchId, tokenManager)
                }
            }

        }
    }
}

@Composable
fun MatchOptionsScreen(
    teamA: String,
    teamB: String,
    rtmpUrl: String,
    matchId: String,
    tokenManager: TokenManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val token by tokenManager.tokenFlow.collectAsState(initial = null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$teamA vs $teamB", style = MaterialTheme.typography.headlineSmall)
        Text(rtmpUrl, style = MaterialTheme.typography.headlineSmall)

        Button(onClick = {
            if (token != null) {
                scope.launch {
                    when (val result = makePostInviteLinkRequest(token!!, matchId)) {
                        is InviteResult.Success -> {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Invite Link", result.link)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Link copiato negli appunti!", Toast.LENGTH_SHORT).show()
                        }
                        is InviteResult.Error -> {
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                Toast.makeText(context, "Token non disponibile", Toast.LENGTH_SHORT).show()
            }
        }) {
            Text("Genera Link Invito")
        }

        Button(onClick = {
            val intent = Intent(context, StreamActivity::class.java).apply {
                putExtra("TEAM_1", teamA)
                putExtra("TEAM_2", teamB)
                putExtra("RTMP_URL", rtmpUrl)
                putExtra("MATCH_ID", matchId)
            }
            context.startActivity(intent)
        }) {
            Text("Avvia Streaming")
        }

    }
}


suspend fun makePostInviteLinkRequest(token: String, matchId: String): InviteResult = withContext(
    Dispatchers.IO) {
    try {
        val client = getUnsafeOkHttpClient()
        val request = Request.Builder()
            .url("https://spikestream.tooolky.com/auth/invite/$matchId")
            .addHeader("Authorization", "Bearer $token")
            .post(RequestBody.create(null, ByteArray(0))) // POST con corpo vuoto
            .build()

        val response = client.newCall(request).execute()
        val body = response.body()?.string() ?: ""

        if (response.isSuccessful) {
            val json = JSONObject(body)
            val url = json.getString("link")
            InviteResult.Success(url)
        } else {
            InviteResult.Error("HTTP ${response.code()}: $body")
        }
    } catch (e: Exception) {
        InviteResult.Error("Eccezione: ${e.localizedMessage}")
    }
}

