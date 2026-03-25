import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.vamanit.calendar"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file("../thinkcloud-upload.jks")
            storePassword = localProps.getProperty("KEYSTORE_PASSWORD")
            keyAlias = localProps.getProperty("KEY_ALIAS", "thinkcloud")
            keyPassword = localProps.getProperty("KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.vamanit.calendar"
        minSdk = 26  // azure-core (MS Graph SDK) requires API 26+
        targetSdk = 35
        versionCode = 8
        versionName = "1.8.0"

        buildConfigField("String", "PHONE_CLIENT_SECRET",
            "\"${localProps.getProperty("PHONE_CLIENT_SECRET", "")}\"")
        buildConfigField("String", "TV_CLIENT_SECRET",
            "\"${localProps.getProperty("TV_CLIENT_SECRET", "")}\"")

        // Firebase FCM — populate from local.properties to enable push notifications
        // Leave empty to run without push (WorkManager fallback still active)
        buildConfigField("String", "FIREBASE_PROJECT_ID",
            "\"${localProps.getProperty("FIREBASE_PROJECT_ID", "")}\"")
        buildConfigField("String", "FIREBASE_APP_ID",
            "\"${localProps.getProperty("FIREBASE_APP_ID", "")}\"")
        buildConfigField("String", "FIREBASE_API_KEY",
            "\"${localProps.getProperty("FIREBASE_API_KEY", "")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID",
            "\"${localProps.getProperty("FIREBASE_SENDER_ID", "")}\"")

        // Cloud Functions webhook base URL — required for Graph + Google push subscriptions
        // e.g. https://us-central1-<your-project>.cloudfunctions.net
        // Leave empty to disable webhook push (FCM still works if messages are sent externally)
        buildConfigField("String", "BACKEND_WEBHOOK_BASE_URL",
            "\"${localProps.getProperty("BACKEND_WEBHOOK_BASE_URL", "")}\"")
    }

    buildTypes {
        debug {
            // No suffix — keeps package = com.vamanit.calendar so MSAL redirect URI matches Azure
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Resolve META-INF conflicts from Google API client libs
    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/*.kotlin_module",
            "META-INF/INDEX.LIST",
            "META-INF/LICENSE.md",
            "META-INF/NOTICE.md"
        )
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)

    // AndroidX
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.activity.ktx)
    implementation(libs.core.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.recyclerview)
    implementation(libs.workmanager)
    implementation(libs.leanback)
    implementation(libs.constraintlayout)
    implementation(libs.swiperefreshlayout)
    implementation(libs.cardview)
    implementation(libs.material)

    // Hilt DI
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.work)
    kapt(libs.hilt.work.compiler)

    // Google Calendar via AppAuth (MIT-compatible OAuth2, no proprietary play-services-auth)
    implementation(libs.appauth)
    implementation(libs.google.auth.lib) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.api.client.android) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.calendar.api) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.http.android) {
        exclude(group = "org.apache.httpcomponents")
    }

    // Microsoft (MSAL = MIT, Graph SDK = MIT)
    implementation(libs.msal) {
        exclude(group = "com.microsoft.device.display")
    }

    // Networking + Utils
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.timber)

    // Encrypted SharedPreferences — token + secret storage (Apache 2.0)
    implementation(libs.security.crypto)

    // Play Integrity API — device / app attestation
    implementation(libs.play.integrity)

    // Firebase Cloud Messaging — push-triggered calendar refresh (Apache 2.0)
    // Source: https://github.com/firebase/firebase-android-sdk — Apache 2.0, MIT-compatible
    implementation(libs.firebase.messaging)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
