package com.leonardos.spikestream.data
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.leonardos.spikestream.activities.TokenManager
import com.leonardos.spikestream.utils.Constants
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

object AppAuthCallback {
    fun token(raw: String?): String? = runCatching {
        if (raw == null || raw.length > 20000) return null
        val uri = URI(raw)
        if (uri.scheme != "https" || uri.host != "spikestream.tooolky.com" || uri.rawPath != "/auth/app-auth" ||
            uri.userInfo != null || uri.fragment != null || uri.port !in listOf(-1, 443)) return null
        val pairs = uri.rawQuery?.split('&') ?: return null
        if (pairs.size != 1) return null
        val pair = pairs.single().split('=', limit = 2)
        if (pair.size != 2 || pair[0] != "token") return null
        URLDecoder.decode(pair[1], "UTF-8").takeIf { it.length in 1..16384 && it.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) }
    }.getOrNull()
    fun matches(expected: String?, received: String?, issuedAt: Long, now: Long): Boolean =
        expected != null && received != null && expected.matches(Regex("[a-f0-9]{64}")) &&
            now - issuedAt in 0..600000 && MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), received.toByteArray(Charsets.UTF_8))
}
fun launchGoogleLogin(context: Context) {
    val manager = TokenManager(context)
    try {
        val state = manager.beginGoogleLogin()
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Constants.BASE_URL + "/auth/google").buildUpon().appendQueryParameter("appState", state).build()))
    } catch (_: Exception) {
        runCatching { manager.cancelGoogleLogin() }
        android.widget.Toast.makeText(context, com.leonardos.spikestream.R.string.auth_callback_rejected, android.widget.Toast.LENGTH_LONG).show()
    }
}
