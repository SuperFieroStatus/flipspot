import java.util.Properties

plugins {
    id("com.android.application")
}

// SPOTIFY_CLIENT_ID lives in local.properties (never committed).
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val spotifyClientId: String = localProps.getProperty("SPOTIFY_CLIENT_ID", "").trim()

android {
    namespace = "dev.mike.flipspot"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mike.flipspot"
        minSdk = 30
        targetSdk = 30
        versionCode = 4
        versionName = "0.4.0-m4"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"flipspot://callback\"")

        ndk {
            // No native code yet; this pins the ABIs once the Spotify .aar files arrive.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key on purpose: it installs over the debug build (keeping sign-in,
            // cache and pins) and needs no second SHA-1 on the Spotify dashboard. For distribution,
            // create a release keystore, register its SHA-1, and point this at it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 30 is deliberate: the TCL Flip 2 runs Android 11 and this is never shipped to Play.
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

dependencies {
    // App Remote is only published as an .aar on GitHub (spotify/android-sdk v0.8.0).
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.11.0") // App Remote's default JSON mapper
    // Auth 2.2.0+ does PKCE through the installed Spotify app and returns a refresh token.
    implementation("com.spotify.android:auth:5.0.0")
}
