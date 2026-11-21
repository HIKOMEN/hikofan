plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// yt-dlp kutuphanesinin surumu (tek yerde, gerekirse buradan guncelle)
val ytdlAndroid = "0.17.4"

android {
    namespace = "com.hikofan.mobil"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hikofan.mobil"
        minSdk = 24
        targetSdk = 34
        // GitHub her derlemede numarayi otomatik arttirir (guncelleme kontrolu bunu kullanir)
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.$run"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Sabit imza: tum surumler ayni anahtarla imzalanir, boylece guncelleme eskisinin ustune kurulur
    signingConfigs {
        getByName("debug") {
            storeFile = file("hikofan.keystore")
            storePassword = "hikofan123"
            keyAlias = "hikofan"
            keyPassword = "hikofan123"
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // guncelleme yamalarini (bsdiff/bzip2) acmak icin
    implementation("org.apache.commons:commons-compress:1.21")
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlAndroid")
}
