package com.leonardos.spikestream.data

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.leonardos.spikestream.R
import com.leonardos.spikestream.utils.Constants
import com.leonardos.spikestream.utils.Logger as Log
import com.leonardos.spikestream.utils.getHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed class AuthResult {
    data class Success(val token: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

sealed class GamesResult {
    data class Success(val games: List<JSONObject>) : GamesResult()
    data class Error(val message: String, val isAuthError: Boolean = false) : GamesResult()
}

sealed class CreateMatchResult {
    data class Success(val matchId: String) : CreateMatchResult()
    data class Error(val message: Int) : CreateMatchResult()
}

sealed class YouTubeFetchResult {
    data class Success(val rtmpUrl: String) : YouTubeFetchResult()
    object RateLimit : YouTubeFetchResult()
    object Error : YouTubeFetchResult()
}

sealed class InviteResult {
    data class Success(val link: String) : InviteResult()
    data class Error(val messageResId: Int) : InviteResult()
}

sealed class GetGameResult {
    data class Success(val team1Pts: Int, val team2Pts: Int, val team1Sets: Int, val team2Sets: Int) : GetGameResult()
    data class Error(val message: Int) : GetGameResult()
}

object StreamApi {

    suspend fun makeLoginRequest(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val client = getHttpClient()
            val jsonBody = JSONObject().apply {
                put("email", email)
                put("password", password)
            }

            val mediaType = MediaType.get("application/json; charset=utf-8")
            val requestBody = RequestBody.create(mediaType, jsonBody.toString())

            val request = Request.Builder()
                .url("${Constants.BASE_URL}/auth/login")
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()

            val body = response.body()?.string() ?: ""
            if (response.isSuccessful) {
                val json = JSONObject(body)
                val token = json.getString("access_token")
                AuthResult.Success(token)
            } else {
                AuthResult.Error("Credenziali errate. Riprova o scegli un altro metodo")
            }
        } catch (e: Exception) {
            Log.e("Auth", "Login request failed", e)
            AuthResult.Error("Connessione non riuscita. Controlla la rete e riprova.")
        }
    }

    suspend fun makeRegisterRequest(email: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            val client = getHttpClient()
            val json = JSONObject()
            json.put("email", email)
            json.put("password", password)

            val mediaType = MediaType.get("application/json; charset=utf-8")
            val requestBody = RequestBody.create(mediaType, json.toString())

            val request = Request.Builder()
                .url("${Constants.BASE_URL}/users")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                "Success! Controlla l'email per confermare."
            } else {
                Log.w("Auth", "Register failed: HTTP ${response.code()}")
                "Registrazione non riuscita. Controlla i dati e riprova."
            }

        } catch (e: Exception) {
            Log.e("Auth", "Register request failed", e)
            "Connessione non riuscita. Controlla la rete e riprova."
        }
    }

    suspend fun makeGetGamesRequest(token: String): GamesResult = withContext(Dispatchers.IO) {
        try {
            val client = getHttpClient()
            val request = Request.Builder()
                .url("${Constants.BASE_URL}/games")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            val response = client.newCall(request).execute()

            val body = response.body()?.string() ?: "[]"
            if (response.isSuccessful) {
                val json = JSONArray(body)
                val games = mutableListOf<JSONObject>()
                for (i in 0 until json.length()) {
                    val game = json.getJSONObject(i)
                    games.add(game)
                }
                GamesResult.Success(games)
            } else {
                val code = response.code()
                Log.w("Games", "Get games failed: HTTP $code")
                GamesResult.Error("Sessione scaduta. Accedi di nuovo.", isAuthError = code == 401)
            }

        } catch (e: Exception) {
            Log.e("Games", "Get games request failed", e)
            GamesResult.Error("Connessione non riuscita. Controlla la rete e riprova.")
        }
    }

    suspend fun makeCreateMatchRequest(
        token: String,
        teamAName: String,
        teamBName: String,
        rtmpUrl: String
    ): CreateMatchResult = withContext(Dispatchers.IO) {
        try {
            val client = getHttpClient()
            val jsonBody = JSONObject().apply {
                put("teamAName", teamAName)
                put("teamBName", teamBName)
                put("rtmpUrl", rtmpUrl)
            }

            val mediaType = MediaType.get("application/json; charset=utf-8")
            val requestBody = RequestBody.create(mediaType, jsonBody.toString())

            val request = Request.Builder()
                .url("${Constants.BASE_URL}/games")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body()?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(body)
                val matchId = json.getString("id")
                CreateMatchResult.Success(matchId)
            } else {
                Log.w("CreateMatch", "Create match failed: HTTP ${response.code()}")
                CreateMatchResult.Error(R.string.create_match_failed)
            }
        } catch (e: Exception) {
            Log.e("CreateMatch", "Create match request failed", e)
            CreateMatchResult.Error(R.string.connection_failed)
        }
    }

    suspend fun fetchYouTubeRTMP(context: Context, account: GoogleSignInAccount, jwtToken: String): YouTubeFetchResult = withContext(Dispatchers.IO) {
        try {
            val scope = "oauth2:https://www.googleapis.com/auth/youtube.readonly"
            val googleToken = GoogleAuthUtil.getToken(context, account.account!!, scope)

            val client = getHttpClient()
            val jsonBody = JSONObject().apply {
                put("accessToken", googleToken)
            }
            val mediaType = MediaType.get("application/json; charset=utf-8")
            val requestBody = RequestBody.create(mediaType, jsonBody.toString())

            val request = Request.Builder()
                .url("${Constants.BASE_URL}/games/youtube-rtmp")
                .addHeader("Authorization", "Bearer $jwtToken")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body()?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(body)
                val rtmp = json.optString("rtmpUrl", "")
                if (rtmp.isNotEmpty()) {
                    return@withContext YouTubeFetchResult.Success(rtmp)
                }
            } else if (response.code() == 429) {
                return@withContext YouTubeFetchResult.RateLimit
            } else {
                Log.e("YouTubeBackend", "Request failed: ${response.code()} - $body")
            }
            YouTubeFetchResult.Error
        } catch (e: Exception) {
            Log.e("YouTubeBackend", "Error fetching stream via Backend", e)
            YouTubeFetchResult.Error
        }
    }

    suspend fun fetchFacebookRTMP(fbAccessToken: String, appToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val client = getHttpClient()
            val json = JSONObject().apply {
                put("accessToken", fbAccessToken)
            }
            val body = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"),
                json.toString()
            )
            val request = Request.Builder()
                .url("${Constants.BASE_URL}/games/facebook-rtmp")
                .addHeader("Authorization", "Bearer $appToken")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body()?.string()
                val jsonResponse = JSONObject(responseBody ?: "{}")
                return@withContext jsonResponse.optString("rtmpUrl", null)
            }
            null
        } catch (e: Exception) {
            Log.e("FacebookRTMP", "Failed to fetch FB rtmp", e)
            null
        }
    }

    suspend fun makePostInviteLinkRequest(token: String, matchId: String): InviteResult = withContext(Dispatchers.IO) {
        try {
            val client = getHttpClient()
            val request = Request.Builder()
                .url("${Constants.BASE_URL}/auth/invite/$matchId")
                .addHeader("Authorization", "Bearer $token")
                .post(RequestBody.create(null, ByteArray(0)))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body()?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(body)
                val url = json.getString("link")
                InviteResult.Success(url)
            } else {
                Log.w("InviteLink", "Get invite link failed: HTTP ${response.code()}")
                InviteResult.Error(R.string.invite_link_error)
            }
        } catch (e: Exception) {
            Log.e("InviteLink", "Invite link request failed", e)
            InviteResult.Error(R.string.connection_failed)
        }
    }

    suspend fun makeDeleteMatchRequest(token: String, matchId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = getHttpClient()
            val request = Request.Builder()
                .url("${Constants.BASE_URL}/games/$matchId")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("Games", "Delete match request failed", e)
            false
        }
    }

    suspend fun makeDeleteMe(token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = getHttpClient()
            val request = Request.Builder()
                .url("${Constants.BASE_URL}/users/me")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("Settings", "Delete account failed", e)
            false
        }
    }

    suspend fun makeGetGameRequest(token: String, matchId: String): GetGameResult = withContext(Dispatchers.IO) {
        try {
            val client = getHttpClient()
            val request = Request.Builder()
                .url("${Constants.BASE_URL}/games/$matchId")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body()?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(body)
                val teamASets = json.getJSONArray("teamASets")
                val teamBSets = json.getJSONArray("teamBSets")

                if (teamASets.length() > 0 && teamBSets.length() > 0) {
                    val aPts = teamASets.getInt(teamASets.length() - 1)
                    val bPts = teamBSets.getInt(teamBSets.length() - 1)

                    val aSets = (0 until minOf(teamASets.length(), teamBSets.length()) - 1).count { i ->
                        teamASets.getInt(i) > teamBSets.getInt(i)
                    }
                    val bSets = (0 until minOf(teamASets.length(), teamBSets.length()) - 1).count { i ->
                        teamASets.getInt(i) < teamBSets.getInt(i)
                    }

                    GetGameResult.Success(aPts, bPts, aSets, bSets)
                } else {
                    GetGameResult.Success(0, 0, 0, 0)
                }
            } else {
                GetGameResult.Error(R.string.create_match_failed)
            }
        } catch (e: Exception) {
            Log.e("StreamApi", "Get game request failed", e)
            GetGameResult.Error(R.string.connection_failed)
        }
    }
}
