# Vamanit® Calendar

> Android TV & Phone calendar dashboard connecting Google Calendar and Microsoft 365.
> Single APK · Native Kotlin · MIT Licensed · All dependencies MIT/Apache 2.0/BSD-3

---

## Features

- **TV Kiosk Dashboard** — full-screen always-on schedule with live clock, D-pad navigable event cards
- **Phone Agenda View** — pull-to-refresh scrollable agenda grouped by day
- **Single APK** — detects TV vs phone at runtime via `UiModeManager`
- **Google Calendar** — OAuth2 via AppAuth (no proprietary Play Services auth)
- **Microsoft 365 / Outlook** — MSAL + Microsoft Graph API
- **Background refresh** — WorkManager periodic sync every 15 minutes
- **Dark theme** — optimised for both TV and OLED phone screens

---

## Prerequisites

### 1. Google Cloud Console

1. Create a project and enable **Google Calendar API**
2. OAuth consent screen → add scope `https://www.googleapis.com/auth/calendar.readonly`
3. Create OAuth 2.0 credential → type: **Web Application**
   - Authorized redirect URI: `com.vamanit.calendar:/oauth2redirect`
4. Copy the **Client ID** into `GoogleAuthProvider.kt`:
   ```kotlin
   const val CLIENT_ID = "YOUR_GOOGLE_CLIENT_ID"
   ```

### 2. Azure App Registration

1. Register app at portal.azure.com → Accounts in any org + personal Microsoft
2. Add redirect URI (Public client/native):
   `msauth://com.vamanit.calendar/{BASE64_KEYSTORE_HASH}`
   ```bash
   keytool -exportcert -alias androiddebugkey \
     -keystore ~/.android/debug.keystore \
     -storepass android | openssl sha1 -binary | openssl base64
   ```
3. API Permissions → Microsoft Graph → Delegated: `User.Read`, `Calendars.Read`, `offline_access`
4. Update `res/raw/msal_config.json` with your **client_id** and redirect URI hash
5. Update `AndroidManifest.xml` MSAL `BrowserTabActivity` `android:path` with the same hash

> **Android TV note:** Set `"authorization_user_agent": "WEBVIEW"` in `msal_config.json` for TVs without Chrome.

---

## Logo Assets

Replace the placeholder drawables with real Vamanit® assets:

| File | Size | Used for |
|---|---|---|
| `app/src/main/res/drawable/vamanit_logo.png` | Any (prefer 2x) | Sign-in screen, phone header, TV top-left |
| `app/src/main/res/drawable/tv_banner.png` | **320 × 180 px** | Android TV launcher banner (required) |
| `app/src/main/mipmap-*/ic_launcher.png` | Standard densities | App icon |

After adding PNGs, delete `vamanit_logo.xml` and `tv_banner.xml`.

---

## Build

```bash
# Clone
git clone https://github.com/vamanit-oss/vamanit-calendar.git
cd vamanit-calendar

# Debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release APK
./gradlew assembleRelease
```

## Install

```bash
# Phone (USB)
adb install app/build/outputs/apk/debug/app-debug.apk

# Android TV (Wi-Fi ADB)
adb connect <TV_IP>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

```
MVVM + Clean Architecture + Hilt DI
├── auth/          GoogleAuthProvider (AppAuth) · MicrosoftAuthProvider (MSAL) · AuthManager
├── data/
│   ├── model/     CalendarEvent · CalendarSource
│   ├── remote/    GoogleCalendarDataSource · MicrosoftCalendarDataSource
│   └── repository CalendarRepository (interface) · CalendarRepositoryImpl
├── domain/        GetUpcomingEventsUseCase · RefreshEventsUseCase
├── ui/
│   ├── signin/    SignInActivity · SignInViewModel
│   ├── dashboard/ DashboardActivity (TV/phone router) · DashboardViewModel
│   ├── tv/        TvDashboardFragment · TvEventCardAdapter
│   └── phone/     PhoneAgendaFragment · AgendaAdapter
└── worker/        CalendarRefreshWorker (WorkManager, 15 min)
```

---

## Dependency Licenses

All MIT-compatible (MIT, Apache 2.0, or BSD-3):

| Library | License |
|---|---|
| Kotlin, Coroutines, AndroidX, Hilt, OkHttp, Gson, Timber | Apache 2.0 |
| AppAuth (Google OAuth2, replaces proprietary play-services-auth) | Apache 2.0 |
| Google API Client, Calendar API | Apache 2.0 |
| Google Auth Library | BSD-3-Clause |
| MSAL | MIT |
| Microsoft Graph SDK | MIT |

---

## License

MIT License — Copyright (c) 2025 Vamanit®
See [LICENSE](LICENSE) for full text.
