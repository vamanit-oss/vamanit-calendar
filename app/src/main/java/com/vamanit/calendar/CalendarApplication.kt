package com.vamanit.calendar

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class CalendarApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        initFirebase()
    }

    /**
     * Initialises Firebase without google-services.json / the google-services plugin.
     * Credentials come from local.properties → BuildConfig at compile time.
     * If none are set the app runs without push (WorkManager handles background sync).
     *
     * To enable FCM:
     *  1. Create a Firebase project at https://console.firebase.google.com
     *  2. Add an Android app (package: com.vamanit.calendar)
     *  3. Copy the four values below into local.properties:
     *       FIREBASE_PROJECT_ID  = your-project-id
     *       FIREBASE_APP_ID      = 1:XXXX:android:XXXX
     *       FIREBASE_API_KEY     = AIzaSy...
     *       FIREBASE_SENDER_ID   = 123456789
     *  4. Send FCM data messages to the token stored in FCMTokenStore.
     */
    private fun initFirebase() {
        if (BuildConfig.FIREBASE_APP_ID.isEmpty()) {
            Timber.d("Firebase not configured — push notifications disabled (WorkManager fallback active)")
            return
        }
        runCatching {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build()
            )
            Timber.d("Firebase initialised — FCM push notifications active")
        }.onFailure { Timber.e(it, "Firebase init failed") }
    }
}
