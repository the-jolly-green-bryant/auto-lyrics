plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val baseVersionName = "1.9.6"
val buildNumber = providers.gradleProperty("build.number").orNull?.toIntOrNull()

android {
    namespace = "com.autolyrics"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autolyrics"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber?.let { 1_906_000 + it } ?: 30
        versionName = buildNumber?.let { "$baseVersionName.$it" } ?: baseVersionName
    }

    signingConfigs {
        getByName("debug") {
            val signingStoreFile = providers.gradleProperty("signing.store.file").orNull
            val signingStorePassword = providers.gradleProperty("signing.store.password").orNull
            val signingKeyAlias = providers.gradleProperty("signing.key.alias").orNull
            val signingKeyPassword = providers.gradleProperty("signing.key.password").orNull

            if (listOf(
                    signingStoreFile,
                    signingStorePassword,
                    signingKeyAlias,
                    signingKeyPassword
                ).all { !it.isNullOrBlank() }
            ) {
                storeFile = rootProject.file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    val spotifyClientId = providers.gradleProperty("spotify.client.id")
        .orElse(providers.systemProperty("spotify.client.id"))
        .orElse("")
    defaultConfig {
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${spotifyClientId.get()}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"https://com.autolyrics/callback\"")
    }
}

dependencies {
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Android Auto - MediaBrowserService
    implementation("androidx.media:media:1.7.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Color extraction from album art
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ML Kit — on-device language detection & translation
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
