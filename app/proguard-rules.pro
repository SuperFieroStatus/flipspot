# R8 rules for FlipSpot release builds.

# Spotify App Remote: talks to the Spotify app as JSON mapped by Gson onto these types via reflection.
# (Mirrors the .aar's own proguard.txt, kept here in case a local-file .aar's consumer rules are skipped.)
# Keep all of it: with only the .aar's narrower rules, the shrunk build reported "Spotify app not
# installed" (the Spotify locators are created reflectively). App Remote is small; the cost is ~100 KB.
-keep class com.spotify.protocol.** { *; }
-keep class com.spotify.android.appremote.** { *; }
-keep class com.spotify.sdk.android.auth.** { *; }
-dontwarn com.spotify.android.appremote.api.ContentApi$ContentType
-dontwarn com.spotify.android.appremote.api.PlayerApi$StreamType
-dontwarn com.spotify.protocol.types.*
# App Remote ships an optional Jackson mapper; FlipSpot uses Gson.
-dontwarn com.fasterxml.jackson.**
# Compile-time-only nullability annotations referenced by App Remote.
-dontwarn com.spotify.base.annotations.**
-dontwarn javax.annotation.**

# Gson field names come from @SerializedName.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep line numbers so release crash traces in logcat stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
