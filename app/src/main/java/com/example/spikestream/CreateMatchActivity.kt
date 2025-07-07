package com.example.spikestream

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.spikestream.ui.theme.MyApplicationTheme
import getUnsafeOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject


class CreateMatchActivity: ComponentActivity() {

    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenManager = TokenManager(applicationContext)
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
                    CreateMatchScreen(tokenManager)
                }
            }
        }
    }

    @Composable
    fun CreateMatchScreen(tokenManager: TokenManager) {

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val token by tokenManager.tokenFlow.collectAsState(initial = null)

        var team1 by remember { mutableStateOf("") }
        var team2 by remember { mutableStateOf("") }
        var streamUrl by remember { mutableStateOf("rtmp://") }

        var showHelp by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {

            Text(
                text = "Configura Streaming",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

// Testo cliccabile per mostrare/nascondere la guida
            Text(
                text = if (showHelp) "❌ Nascondi guida per lo streaming" else "ℹ️ Mostra guida per lo streaming",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                ),
                modifier = Modifier
                    .clickable { showHelp = !showHelp }
                    .padding(bottom = if (showHelp) 16.dp else 32.dp)
            )

            if (showHelp) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Informazioni",
                                tint = Color(0xFFFFA000),
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = "Come configurare lo streaming su YouTube, Twitch o Instagram:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = """
📺 **YouTube**:
1. Vai su https://studio.youtube.com
2. Clicca su “Vai dal vivo”
3. Troverai:
   - URL RTMP: rtmp://a.rtmp.youtube.com/live2
   - Chiave: es. abcd-1234-xxxx-zzzz
4. Inserisci qui: rtmp://a.rtmp.youtube.com/live2/abcd-1234-xxxx-zzzz

🎮 **Twitch**:
1. Vai su https://dashboard.twitch.tv
2. Menu > Impostazioni > Stream
3. URL RTMP: rtmp://live.twitch.tv/app
4. Chiave: es. live_12543_...
5. Inserisci qui: rtmp://live.twitch.tv/app/live_12543_...

""".trimIndent(),
                            style = MaterialTheme.typography.bodySmall
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Sicurezza",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = "L’URL RTMP viene salvato in forma criptata e non è visibile a nessuno.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }
            }

            // Input fields
            OutlinedTextField(
                value = team1,
                onValueChange = { team1 = it },
                label = { Text("Squadra Casa") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = team2,
                onValueChange = { team2 = it },
                label = { Text("Squadra Ospiti") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = streamUrl,
                onValueChange = { streamUrl = it },
                label = { Text("URL RTMP") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (validateInput(team1, team2, streamUrl)) {
                        if (token != null) {
                            scope.launch {
                                when (val result = makeCreateMatchRequest(token!!, team1, team2, streamUrl)) {
                                    is CreateMatchResult.Success -> {
                                        val finalMatchId = result.matchId
                                        val intent = Intent(context, MatchOptionsActivity::class.java).apply {
                                            putExtra("TEAM_1", team1)
                                            putExtra("TEAM_2", team2)
                                            putExtra("RTMP_URL", streamUrl)
                                            putExtra("MATCH_ID", finalMatchId)
                                        }
                                        context.startActivity(intent)
                                    }
                                    is CreateMatchResult.Error -> {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(context, "Token non disponibile", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = team1.isNotBlank() && team2.isNotBlank() && streamUrl.startsWith("rtmp://")
            ) {
                Text("Crea partita")
            }
        }

    }


    private fun validateInput(team1: String, team2: String, streamUrl: String): Boolean {
        return when {
            team1.isBlank() -> {
                Toast.makeText(this, "Inserisci nome squadra casa", Toast.LENGTH_SHORT).show()
                false
            }
            team2.isBlank() -> {
                Toast.makeText(this, "Inserisci nome squadra ospiti", Toast.LENGTH_SHORT).show()
                false
            }
            !streamUrl.startsWith("rtmp://") -> {
                Toast.makeText(this, "URL deve iniziare con rtmp://", Toast.LENGTH_SHORT).show()
                false
            }
            else -> true
        }
    }
}


suspend fun makeCreateMatchRequest(
    token: String,
    teamAName: String,
    teamBName: String,
    rtmpUrl: String
): CreateMatchResult = withContext(Dispatchers.IO) {
    try {
        val client = getUnsafeOkHttpClient()
        val jsonBody = JSONObject().apply {
            put("teamAName", teamAName)
            put("teamBName", teamBName)
            put("rtmpUrl", rtmpUrl)
        }

        val mediaType = MediaType.get("application/json; charset=utf-8")
        val requestBody = RequestBody.create(mediaType, jsonBody.toString())

        val request = Request.Builder()
            .url("https://spikestream.tooolky.com/games")
            .addHeader("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body()?.string() ?: ""

        if (response.isSuccessful) {
            val json = JSONObject(body)
            val matchId = json.getString("id") // se la risposta include matchId
            CreateMatchResult.Success(matchId)
        } else {
            CreateMatchResult.Error("Errore ${response.code()}: $body")
        }
    } catch (e: Exception) {
        CreateMatchResult.Error("Eccezione: ${e.localizedMessage}")
    }
}

sealed class CreateMatchResult {
    data class Success(val matchId: String) : CreateMatchResult()
    data class Error(val message: String) : CreateMatchResult()
}
