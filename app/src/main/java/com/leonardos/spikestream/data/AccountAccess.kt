package com.leonardos.spikestream.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.leonardos.spikestream.utils.Constants
import com.leonardos.spikestream.utils.getHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.TimeZone
import java.util.Locale
import java.util.concurrent.TimeUnit

/** UI hints only. Every creation is independently authorized by the server. */
data class AccountAccess(val account: JSONObject) {
    val plan: String get() = account.optString("plan", "free")
    private fun past(value: String): Boolean = value.isNotBlank() && value != "null" && runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
        val position = ParsePosition(0)
        val date = parser.parse(value, position)
        date == null || position.index != value.length || date.time <= System.currentTimeMillis()
    }.getOrDefault(true)
    fun canCreate(kind: String): Boolean {
        if (plan != "free" && !account.optBoolean("subscriptionExpired", false) && past(account.optString("subscriptionExpiresAt"))) return false
        if (kind == "youtubeManaged" && !canPrepareYouTube()) return false
        return account.optJSONObject("allowed")?.optBoolean(kind, false) == true
    }
    fun canPrepareYouTube(): Boolean {
        val access = account.optJSONObject("youtubeManagedAccess") ?: return false
        if (plan != "free" && !account.optBoolean("subscriptionExpired", false) && past(account.optString("subscriptionExpiresAt"))) return false
        return access.optBoolean("allowed", false) && !past(access.optString("expiresAt"))
    }
    companion object {
        fun owner(token: String): String = runCatching {
            JSONObject(String(Base64.decode(token.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)).getString("key")
        }.getOrDefault(token)
        fun fromToken(token: String?): AccountAccess? = runCatching {
            val payload = String(Base64.decode(token!!.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            JSONObject(payload).optJSONObject("account")?.let { AccountAccess(it) }
        }.getOrNull()
    }
}
fun StreamDestination.accountKind(): String = when {
    youtubeManaged -> "youtubeManaged"
    platform == StreamPlatform.FACEBOOK -> "facebook"
    platformFromRtmpUrl(rtmpUrl) == StreamPlatform.YOUTUBE -> "youtubeDirect"
    platformFromRtmpUrl(rtmpUrl) == StreamPlatform.FACEBOOK -> "facebook"
    else -> "custom"
}
fun openAccountPlans(context: Context) {
    val lang = Locale.getDefault().language.takeIf { it in listOf("it", "en", "fr") } ?: "en"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Constants.BASE_URL + "/account-plans.html?lang=" + lang))) }
}
object AccountAccessApi {
    suspend fun refresh(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(Constants.BASE_URL + "/auth/entitlements").header("Authorization", "Bearer " + token).get().build()
            getHttpClient().newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else JSONObject(response.body()?.string().orEmpty()).optString("access_token").takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}
