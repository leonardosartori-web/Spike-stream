package com.leonardos.spikestream.utils

import android.util.Log
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
        }

        remoteConfig.setConfigSettingsAsync(settings)

        remoteConfig.setDefaultsAsync(
            mapOf(
                "app_open_enabled" to true,
                "invitation_link_enabled" to true,
                "create_match_enabled" to true,
                "base_url" to "https://spikestream.tooolky.com"
            )
        )

        remoteConfig.fetchAndActivate()
            .addOnCompleteListener {
                isReady = true

                if (it.isSuccessful) {
                    val newUrl = remoteConfig.getString("base_url")
                    if (newUrl.isNotBlank() && newUrl.startsWith("http")) {
                        Constants.BASE_URL = newUrl
                    }
                }

                onComplete()
            }
    }

    private fun safeBool(key: String, default: Boolean): Boolean {
        if (!isReady) return default
        return remoteConfig.getBoolean(key)
    }

    fun isAdsEnabled() = isCreateMatchEnabled() or isInvitationLinkEnabled() or isAppOpenEnabled()
    fun isAppOpenEnabled() = safeBool("app_open_enabled", true)

    fun isCreateMatchEnabled() = safeBool("create_match_enabled", true)
    fun isInvitationLinkEnabled() = safeBool("invitation_link_enabled", true)
}