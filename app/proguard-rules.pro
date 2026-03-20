# Google API Client (Apache 2.0)
-keep class com.google.api.** { *; }
-keep class com.google.apis.** { *; }
-dontwarn com.google.api.**
-dontwarn com.google.apis.**

# AppAuth (Apache 2.0)
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**

# Google Auth Library (BSD-3)
-keep class com.google.auth.** { *; }
-dontwarn com.google.auth.**

# MSAL (MIT)
-keep class com.microsoft.identity.** { *; }
-dontwarn com.microsoft.identity.**

# Microsoft Graph SDK (MIT)
-keep class com.microsoft.graph.** { *; }
-dontwarn com.microsoft.graph.**

# Gson (Apache 2.0)
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp (Apache 2.0)
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin (Apache 2.0)
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Hilt (Apache 2.0)
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Nimbus JOSE / MSAL optional tink+findbugs deps (not bundled at runtime)
-dontwarn com.google.crypto.tink.**
-dontwarn edu.umd.cs.findbugs.**
-dontwarn com.nimbusds.jose.crypto.Ed25519**
-dontwarn com.nimbusds.jose.crypto.X25519**
-dontwarn com.nimbusds.jose.crypto.impl.XC20P
-dontwarn com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator
-dontwarn com.nimbusds.jose.jwk.OctetKeyPair
-dontwarn com.yubico.**

# Play Integrity (Google)
-keep class com.google.android.play.core.integrity.** { *; }
-dontwarn com.google.android.play.core.integrity.**
