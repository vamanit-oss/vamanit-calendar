plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.vamanit.calendar"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file("../thinkcloud-upload.jks")
            storePassword = "thinkcloud2026"
            keyAlias = "thinkcloud"
            keyPassword = "thinkcloud2026"
        }
    }

    defaultConfig {
        applicationId = "com.vamanit.calendar"
        minSdk = 26  // azure-core (MS Graph SDK) requires API 26+
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
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

    // Play Integrity API — device / app attestation
    implementation(libs.play.integrity)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
