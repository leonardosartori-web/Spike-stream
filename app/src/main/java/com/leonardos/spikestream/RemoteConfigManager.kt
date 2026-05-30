package com.leonardos.spikestream

import android.util.Log
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings

object RemoteConfigManager {

    private val remoteConfig = Firebase.remoteConfig

    fun init(onComplete: () -> Unit) {

        val settings = remoteConfigSettings {

            // sviluppo
            minimumFetchIntervalInSeconds = 0

            // produzione:
            // minimumFetchIntervalInSeconds = 3600
        }

        remoteConfig.setConfigSettingsAsync(settings)

        remoteConfig.setDefaultsAsync(
            mapOf(
                "ads_enabled" to true,
                "app_open_enabled" to true,
                "rewarded_enabled" to true
            )
        )

        remoteConfig.fetchAndActivate()
            .addOnCompleteListener {

                if (it.isSuccessful) {

                    Log.i(
                        "RemoteConfig",
                        "Config aggiornata"
                    )

                } else {

                    Log.w(
                        "RemoteConfig",
                        "Fetch fallito"
                    )
                }

                onComplete()
            }
    }

    fun isAdsEnabled(): Boolean {
        return remoteConfig.getBoolean("ads_enabled")
    }

    fun isAppOpenEnabled(): Boolean {
        return remoteConfig.getBoolean("app_open_enabled")
    }

    fun isRewardedEnabled(): Boolean {
        return remoteConfig.getBoolean("rewarded_enabled")
    }
}