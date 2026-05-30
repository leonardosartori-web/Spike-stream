package com.leonardos.spikestream

import com.leonardos.spikestream.Logger as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Result wrapper for match fetching API.
 */
sealed class GetGameResult {
    data class Success(val team1Pts: Int, val team2Pts: Int, val team1Sets: Int, val team2Sets: Int) : GetGameResult()
    data class Error(val message: Int) : GetGameResult()
}

/**
 * Asynchronously performs a GET request to retrieve game details (sets, scores) before WebSocket connection.
 */
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
