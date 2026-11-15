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

sealed class YouTubeSetupResult {
    data class Success(val stream: YouTubeStreamDestination, val requiresStudio: Boolean) : YouTubeSetupResult()
    data class Error(val code: String) : YouTubeSetupResult()
}

/** Creates or recovers only a reusable key. Visibility is configured in YouTube Studio. */
object YouTubeSetupApi {
    const val SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl"

    suspend fun create(context: Context, appToken: String, googleToken: String, madeForKids: Boolean): YouTubeSetupResult =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().put("accessToken", googleToken)
                payload.put("madeForKids", madeForKids)
                val endpoint = "youtube-connect"
                val request = Request.Builder().url("${Constants.BASE_URL}/games/$endpoint")
                    .header("Authorization", "Bearer $appToken")
                    .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), payload.toString())).build()
                val client = getHttpClient().newBuilder().readTimeout(60, TimeUnit.SECONDS)
                    .callTimeout(65, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
                client.newCall(request).execute().use { response ->
                    val json = runCatching { JSONObject(response.body()?.string().orEmpty()) }.getOrDefault(JSONObject())
                    if (response.isSuccessful) {
                        val stream = StreamApi.parseYouTubeStream(json.optJSONObject("stream"))
                        if (stream == null) YouTubeSetupResult.Error("YOUTUBE_UNAVAILABLE")
                        else YouTubeSetupResult.Success(stream, json.optBoolean("requiresStudio", true))
                    } else {
                        val code = json.optString("code").ifBlank {
                            when (response.code()) {
                                401 -> "YOUTUBE_AUTH_REQUIRED"
                                429 -> "YOUTUBE_RATE_LIMIT"
                                else -> "YOUTUBE_UNAVAILABLE"
                            }
                        }
                        if (code == "YOUTUBE_AUTH_REQUIRED") runCatching { GoogleAuthUtil.clearToken(context, googleToken) }
                        YouTubeSetupResult.Error(code)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { YouTubeSetupResult.Error("YOUTUBE_UNAVAILABLE") }
        }
}
