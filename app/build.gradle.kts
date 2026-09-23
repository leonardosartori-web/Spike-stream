import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

// Load local.properties so AdMob IDs never live in committed source
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "com.leonardos.spikestream"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.leonardos.spikestream"
        minSdk = 24
        targetSdk = 37
        versionCode = 58
        versionName = "2.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AdMob IDs injected from local.properties — never written into source files
        buildConfigField("String", "ADMOB_APP_ID",
            "\"${localProps.getProperty("ADMOB_APP_ID") ?: ""}\"")
        buildConfigField("String", "ADMOB_APP_OPEN_ID",
            "\"${localProps.getProperty("ADMOB_APP_OPEN_ID") ?: ""}\"")
        buildConfigField("String", "ADMOB_REWARDED_ID",
            "\"${localProps.getProperty("ADMOB_REWARDED_ID") ?: ""}\"")
        buildConfigField("String", "ADMOB_REWARDED_INTERSTITIAL_ID",
            "\"${localProps.getProperty("ADMOB_REWARDED_INTERSTITIAL_ID") ?: ""}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID",
            "\"${localProps.getProperty("GOOGLE_WEB_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "FACEBOOK_APP_ID",
            "\"${localProps.getProperty("FACEBOOK_APP_ID") ?: ""}\"")
        buildConfigField("String", "FACEBOOK_CLIENT_TOKEN",
            "\"${localProps.getProperty("FACEBOOK_CLIENT_TOKEN") ?: ""}\"")

        // AdMob App ID manifest placeholder (read from BuildConfig value above)
        manifestPlaceholders["admobAppId"] = localProps.getProperty("ADMOB_APP_ID") ?: ""
        manifestPlaceholders["facebookAppId"] = localProps.getProperty("FACEBOOK_APP_ID") ?: ""
        manifestPlaceholders["facebookClientToken"] = localProps.getProperty("FACEBOOK_CLIENT_TOKEN") ?: ""
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true   // required for BuildConfig fields above
    }
}

dependencies {

    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-config-ktx")

    implementation("com.github.pedroSG94.RootEncoder:library:2.8.0")
    implementation("io.socket:socket.io-client:2.1.0")                          // updated 2.0.1 → 2.1.0
    implementation("androidx.datastore:datastore-preferences:1.1.1")            // updated 1.0.0 → 1.1.1
    implementation("androidx.security:security-crypto:1.1.0-alpha06")           // NEW: encrypted token storage
    implementation("org.json:json:20210307")
    implementation("com.google.accompanist:accompanist-swiperefresh:0.33.2-alpha")
    implementation("com.google.android.gms:play-services-ads:23.5.0")           // updated 22.6.0 → 23.5.0
    implementation("com.google.android.ump:user-messaging-platform:2.2.0")      // NEW: GDPR Consent Flow (AdMob requirement)
    implementation("com.google.android.play:app-update-ktx:2.1.0")               // NEW: In-app updates
    // AdMob 23.5.0 still requests WorkManager 2.7.0/Room 2.2.5. That pair is
    // incompatible with the current AGP/R8 release build and crashes inside
    // InitializationProvider on a clean install before MainActivity starts.
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("org.slf4j:slf4j-nop:2.0.9")
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Google Auth
    implementation(libs.google.auth)

    // Facebook Login
    implementation("com.facebook.android:facebook-login:18.3.0")

}
