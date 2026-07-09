plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    compilerOptions {
        // El `expect object` que exige @ConstructedBy sigue marcado como beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            // `api`: quien use :core maneja RoomDatabase y Flow directamente.
            api(libs.room.kmp.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.sqlite.bundled)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// El compilador de Room corre una vez por objetivo.
dependencies {
    add("kspAndroid", libs.room.kmp.compiler)
    add("kspJvm", libs.room.kmp.compiler)
}

android {
    namespace = "com.aar.privatemusic.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
