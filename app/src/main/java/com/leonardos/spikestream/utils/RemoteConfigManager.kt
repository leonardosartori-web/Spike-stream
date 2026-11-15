package com.leonardos.spikestream.utils

import android.os.Handler
import android.os.Looper
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings

object RemoteConfigManager {

    private val remoteConfig = Firebase.remoteConfig

    @Volatile
    private var isReady = false

    fun init(onComplete: () -> Unit) {

        val settings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600 // 👈 PRODUZIONE (NON 0)
            fetchTimeoutInSeconds = 8
        }

        val settingsTask = remoteConfig.setConfigSettingsAsync(settings)

        remoteConfig.setDefaultsAsync(
            mapOf(
                "app_open_enabled" to true,
                "invitation_link_enabled" to true,
                "create_match_enabled" to true,
                // 0 keeps every installed version enabled until a value is
                // explicitly published from Firebase Remote Config.
                "minimum_supported_version_code" to 0L,
                "help_content_it" to "",
                "help_content_en" to "",
                "help_content_fr" to "",
                "base_url" to "https://spikestream.tooolky.com"
            )
        )

        var completed = false
        val handler = Handler(Looper.getMainLooper())
        val finish = Runnable {
            if (!completed) {
                completed = true
                isReady = true
                onComplete()
            }
        }
        handler.postDelayed(finish, 10000)
        settingsTask.continueWithTask { remoteConfig.ensureInitialized() }.continueWithTask {
            remoteConfig.fetchAndActivate()
        }
            .addOnCompleteListener {
                isReady = true

                if (it.isSuccessful) {
                    val newUrl = remoteConfig.getString("base_url")
                    if (newUrl.isNotBlank() && newUrl.startsWith("http")) {
                        Constants.BASE_URL = newUrl
                    }
                }

                handler.removeCallbacks(finish)
                finish.run()
            }
    }

    private fun safeBool(key: String, default: Boolean): Boolean {
        if (!isReady) return default
        return remoteConfig.getBoolean(key)
    }

    fun isInitialized(): Boolean = isReady

    fun helpContent(language: String): String? {
        val value = remoteConfig.getValue("help_content_${if (language in setOf("it", "fr")) language else "en"}")
        return if (value.source == com.google.firebase.remoteconfig.FirebaseRemoteConfig.VALUE_SOURCE_REMOTE)
            value.asString() else null
    }

    /** Opening Help respects the normal fetch interval; Refresh is explicit. */
    fun refreshHelp(force: Boolean = false, onComplete: (Boolean) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var completed = false
        fun finish(success: Boolean) {
            if (!completed) {
                completed = true
                onComplete(success)
            }
        }
        val timeout = Runnable { finish(false) }
        handler.postDelayed(timeout, 10000)
        remoteConfig.ensureInitialized().continueWithTask {
            if (force) remoteConfig.fetch(0) else remoteConfig.fetch()
        }.continueWithTask { fetch ->
            if (!fetch.isSuccessful) throw (fetch.exception ?: IllegalStateException("Help fetch failed"))
            remoteConfig.activate()
        }.addOnCompleteListener { result ->
            handler.removeCallbacks(timeout)
            finish(result.isSuccessful)
        }
    }

    fun minimumSupportedVersionCode(): Long {
        if (!isReady) return 0L
        return UpdatePolicy.minimumVersion(remoteConfig.getString("minimum_supported_version_code"))
    }

    // Only a user-requested retry bypasses the normal fetch interval.
    fun refresh(onComplete: () -> Unit) {
        var completed = false
        val handler = Handler(Looper.getMainLooper())
        val finish = Runnable {
            if (!completed) {
                completed = true
                onComplete()
            }
        }
        handler.postDelayed(finish, 10000)
        remoteConfig.fetch(0).continueWithTask { task ->
            if (task.isSuccessful) remoteConfig.activate()
            else com.google.android.gms.tasks.Tasks.forResult(false)
        }.addOnCompleteListener {
            handler.removeCallbacks(finish)
            finish.run()
        }
    }

    fun isAdsEnabled() = isCreateMatchEnabled() or isInvitationLinkEnabled() or isAppOpenEnabled()
    fun isAppOpenEnabled() = safeBool("app_open_enabled", true)

    fun isCreateMatchEnabled() = safeBool("create_match_enabled", true)
    fun isInvitationLinkEnabled() = safeBool("invitation_link_enabled", true)
}
