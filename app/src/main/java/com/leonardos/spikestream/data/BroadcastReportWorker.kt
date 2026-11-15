package com.leonardos.spikestream.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.leonardos.spikestream.activities.TokenManager
import com.leonardos.spikestream.utils.Constants
import com.leonardos.spikestream.utils.getHttpClient
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.TimeUnit

data class BroadcastReport(
    val owner: String,
    val matchId: String,
    val sessionId: String = UUID.randomUUID().toString(),
    val startedAt: Long = System.currentTimeMillis(),
    val outcome: String = "unknown",
    val durationSeconds: Long? = null,
    val reconnections: Int? = null,
) : Serializable {
    fun enqueue(context: Context, rating: String? = null, description: String? = null): androidx.work.Operation {
        val payload = JSONObject().put("startedAt", startedAt).put("outcome", outcome)
            .put("durationSeconds", durationSeconds).put("reconnections", reconnections)
            .put("rating", rating).put("description", description)
        val data = Data.Builder().putString("owner", owner).putString("matchId", matchId)
            .putString("sessionId", sessionId).putString("payload", payload.toString()).build()
        val work = OneTimeWorkRequest.Builder(BroadcastReportWorker::class.java)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(data).build()
        return WorkManager.getInstance(context).enqueue(work)
    }

    companion object {
        fun create(token: String?, matchId: String): BroadcastReport? {
            if (token == null || matchId.isBlank()) return null
            val owner = AccountAccess.owner(token)
            // AccountAccess falls back to the token for older callers. Never persist that fallback.
            return if (owner == token || owner.isBlank()) null else BroadcastReport(owner, matchId)
        }
    }
}

class BroadcastReportWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val token = TokenManager(applicationContext).currentToken ?: return Result.retry()
        if (AccountAccess.owner(token) != inputData.getString("owner")) return Result.retry()
        val matchId = inputData.getString("matchId") ?: return Result.failure()
        val sessionId = inputData.getString("sessionId") ?: return Result.failure()
        val payload = inputData.getString("payload") ?: return Result.failure()
        val request = Request.Builder()
            .url("${Constants.BASE_URL}/games/$matchId/broadcast-reports/$sessionId")
            .header("Authorization", "Bearer $token")
            .put(RequestBody.create(MediaType.get("application/json; charset=utf-8"), payload)).build()
        return try {
            client.newCall(request).execute().use { response ->
                when (response.code()) {
                    204 -> Result.success()
                    408, 429, 401, in 500..599 -> Result.retry()
                    else -> Result.failure()
                }
            }
        } catch (_: IOException) {
            Result.retry()
        }
    }

    companion object {
        private val client = getHttpClient().newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    }
}
