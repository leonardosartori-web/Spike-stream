import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Load local.properties so AdMob IDs never live in committed source
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "com.leonardos.spikestream"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.leonardos.spikestream"
        minSdk = 24
        targetSdk = 35
        versionCode = 29
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AdMob IDs injected from local.properties — never written into source files
        buildConfigField("String", "ADMOB_APP_ID",
            "\"${localProps["ADMOB_APP_ID"] ?: ""}\"")
        buildConfigField("String", "ADMOB_APP_OPEN_ID",
            "\"${localProps["ADMOB_APP_OPEN_ID"] ?: ""}\"")
        buildConfigField("String", "ADMOB_REWARDED_ID",
            "\"${localProps["ADMOB_REWARDED_ID"] ?: ""}\"")
        buildConfigField("String", "ADMOB_REWARDED_INTERSTITIAL_ID",
            "\"${localProps["ADMOB_REWARDED_INTERSTITIAL_ID"] ?: ""}\"")

        // AdMob App ID manifest placeholder (read from BuildConfig value above)
        manifestPlaceholders["admobAppId"] = localProps["ADMOB_APP_ID"] ?: ""
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true   // required for BuildConfig fields above
    }
}

dependencies {

    implementation("com.github.pedroSG94.rtmp-rtsp-stream-client-java:rtplibrary:2.2.6")
    implementation("io.socket:socket.io-client:2.1.0")                          // updated 2.0.1 → 2.1.0
    implementation("androidx.datastore:datastore-preferences:1.1.1")            // updated 1.0.0 → 1.1.1
    implementation("androidx.security:security-crypto:1.1.0-alpha06")           // NEW: encrypted token storage
    implementation("org.json:json:20210307")
    implementation("com.google.accompanist:accompanist-swiperefresh:0.33.2-alpha")
    implementation("com.google.android.gms:play-services-ads:23.5.0")           // updated 22.6.0 → 23.5.0
    implementation("com.google.android.ump:user-messaging-platform:2.2.0")      // NEW: GDPR Consent Flow (AdMob requirement)
    implementation("com.google.android.play:app-update-ktx:2.1.0")               // NEW: In-app updates
    implementation("org.slf4j:slf4j-nop:2.0.9")
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
}
