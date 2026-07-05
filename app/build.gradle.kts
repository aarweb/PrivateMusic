plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aar.privatemusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aar.privatemusic"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "1.25"

        ndk {
            // arm64 only: every phone since ~2016. Halves the APK (yt-dlp/ffmpeg per ABI).
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // CI: PM_KEYSTORE apunta a un debug.keystore estable para que todas
        // las releases compartan firma y las actualizaciones se instalen.
        getByName("debug").apply {
            System.getenv("PM_KEYSTORE")?.let { storeFile = file(it) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Required by youtubedl-android: the python/yt-dlp payload lives in jniLibs.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation(libs.reorderable)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.work)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.onnxruntime)
}
