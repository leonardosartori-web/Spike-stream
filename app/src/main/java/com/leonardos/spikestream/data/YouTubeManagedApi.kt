package com.leonardos.spikestream.data

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.leonardos.spikestream.utils.Constants
import com.leonardos.spikestream.utils.getHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class YouTubeManagedResult {
    data class Prepared(val stream: YouTubeStreamDestination) : YouTubeManagedResult()
    data object Closed : YouTubeManagedResult()
    data class Error(val code: String) : YouTubeManagedResult()
}

object YouTubeManagedApi {
    /** On-demand check; the backend rechecks before each new event. */
    suspend fun accessError(appToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(Constants.BASE_URL + "/users/me/youtube-managed-access")
                .header("Authorization", "Bearer " + appToken).get().build()
            getHttpClient().newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) "YOUTUBE_UNAVAILABLE"
                else if (JSONObject(response.body()?.string().orEmpty()).optBoolean("allowed", false)) null
                else "YOUTUBE_MANAGED_ACCESS_REQUIRED"
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { "YOUTUBE_UNAVAILABLE" }
    }

    suspend fun eligibility(appToken: String, matchId: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(Constants.BASE_URL + "/games/" + matchId + "/youtube-managed/access")
                .header("Authorization", "Bearer " + appToken).get().build()
            getHttpClient().newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else JSONObject(response.body()?.string().orEmpty()).optBoolean("allowed", false)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    suspend fun act(context: Context, appToken: String, googleToken: String, matchId: String, finish: Boolean): YouTubeManagedResult = withContext(Dispatchers.IO) {
        try {
            val operation = if (finish) "finish" else "prepare"
            val body = JSONObject().put("accessToken", googleToken)
            val request = Request.Builder().url("${Constants.BASE_URL}/games/$matchId/youtube-managed/$operation")
                .header("Authorization", "Bearer $appToken")
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), body.toString())).build()
            val client = getHttpClient().newBuilder().readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(65, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            client.newCall(request).execute().use { response ->
                val json = runCatching { JSONObject(response.body()?.string().orEmpty()) }.getOrDefault(JSONObject())
                if (response.isSuccessful) {
                    if (finish && json.optBoolean("closed", false)) YouTubeManagedResult.Closed
                    else if (!finish) StreamApi.parseYouTubeStream(json.optJSONObject("stream"))?.let { YouTubeManagedResult.Prepared(it) }
                        ?: YouTubeManagedResult.Error("YOUTUBE_UNAVAILABLE")
                    else YouTubeManagedResult.Error("YOUTUBE_CLOSE_PENDING")
                } else {
                    val code = json.optString("code").ifBlank { if (response.code() == 401) "YOUTUBE_AUTH_REQUIRED" else if (response.code() == 429) "YOUTUBE_RATE_LIMIT" else "YOUTUBE_UNAVAILABLE" }
                    if (code == "YOUTUBE_AUTH_REQUIRED") runCatching { GoogleAuthUtil.clearToken(context, googleToken) }
                    YouTubeManagedResult.Error(code)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { YouTubeManagedResult.Error("YOUTUBE_UNAVAILABLE") }
    }
}
